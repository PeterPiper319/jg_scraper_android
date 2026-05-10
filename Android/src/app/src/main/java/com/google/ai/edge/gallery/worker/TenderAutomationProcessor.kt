package com.google.ai.edge.gallery.worker

import android.os.Build
import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.common.ThermalGuard
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.TenderFileManager
import com.google.ai.edge.gallery.data.TenderScraper
import com.google.ai.edge.gallery.infrastructure.FirebaseSync
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class TenderAutomationProcessor @Inject constructor(
  @ApplicationContext private val context: Context,
  private val fileManager: TenderFileManager,
  private val scraper: TenderScraper,
  private val firebaseSync: FirebaseSync,
) {
  companion object {
    private const val TAG = "AGTenderAutomation"
    private const val GEMMA_ENRICHMENT_FILENAME = "gemma-manifest-enrichment.json"
    private const val MAX_FILE_TEXT_CHARS = 24000
    private const val MAX_ENRICHMENT_PREP_PROMPT_CHARS = 24000
    private const val MAX_CONSOLIDATED_DOCUMENT_CHARS = 12000
    private const val CHUNK_SIZE = 2200
    private const val MAX_MAP_REDUCE_CHUNKS = 4
    private const val MAX_PRIORITY_DOCUMENTS = 3
  }

  private val highPriorityDocumentRegex =
    Regex(
      """\b(rfp|rfq|bid|tender|tor|terms?[ _-]?of[ _-]?reference|spec(ification|s)?|scope|pricing|price schedule|boq|bill[ _-]?of[ _-]?quantities)\b""",
      RegexOption.IGNORE_CASE,
    )

  private val lowPriorityDocumentRegex =
    Regex(
      """\b(gcc|general[ _-]?conditions|scc|special[ _-]?conditions|sbd|standard[ _-]?bidding|declaration|certificate|tax|annex(ure)?|terms[ _-]?and[ _-]?conditions|form[ _-]?[a-z0-9]+)\b""",
      RegexOption.IGNORE_CASE,
    )

  private data class ScoredChunk(
    val fileName: String,
    val text: String,
    val score: Int,
  )

  private data class ScoredDocument(
    val file: File,
    val score: Int,
  )

  suspend fun enrichAndUploadTender(model: Model, tenderId: String, statusUpdater: ((String) -> Unit)? = null): Boolean {
    try {
      val inferenceModel = createTenderInferenceModel(model)
      val folder = fileManager.getTenderFolder(tenderId)
      if (!hasGemmaReadableDocuments(folder)) {
        fileManager.clearTenderUploadedMarker(folder)
        firebaseSync.uploadTenderFolder(folder)
        fileManager.markTenderUploaded(folder)
        return true
      }

      val prepared = prepareTenderDocuments(model = inferenceModel, tenderId = tenderId) ?: return false
      fileManager.clearTenderUploadedMarker(prepared.folder)

      // Fix 1: Pre-seed enrichment from existing manifest API fields (free data, no Gemma needed)
      val manifest = JSONObject(File(prepared.folder, "manifest.json").readText())
      val combinedEnrichment = runMapReduceExtraction(inferenceModel, tenderId, prepared.extractedDocuments)
      val comprehensiveEnrichment = runComprehensiveTenderExtraction(
        model = inferenceModel,
        tenderId = tenderId,
        folder = prepared.folder,
        documentBundle = prepared.documentBundle,
      )
      mergeComprehensiveEnrichment(combinedEnrichment, comprehensiveEnrichment)
      mergeCompanyMatchSignals(combinedEnrichment, comprehensiveEnrichment)
      seedManifestCompanySignals(combinedEnrichment, manifest)
      
      // Pre-seed briefing from manifest API data if Gemma didn't find it
      val briefingFromGemma = combinedEnrichment.optJSONObject("briefing") ?: JSONObject()
      if (!briefingFromGemma.has("is_compulsory") || briefingFromGemma.isNull("is_compulsory")) {
        briefingFromGemma.put("is_compulsory", manifest.optBoolean("briefingCompulsory", false))
      }
      if (!briefingFromGemma.has("venue") || briefingFromGemma.isNull("venue")) {
        val venue = manifest.optString("briefingVenue", "").ifBlank { null }
        if (venue != null) briefingFromGemma.put("venue", venue)
      }
      combinedEnrichment.put("briefing", briefingFromGemma)

      // Verification based on extracted data
      val cidb = combinedEnrichment.optJSONObject("compliance_meta")?.optString("cidb_grading")
      val minThreshold = combinedEnrichment.optJSONObject("functionality_score")?.optInt("minimum_threshold", -1)
      if (cidb.isNullOrEmpty() && (minThreshold == null || minThreshold == -1 || minThreshold == 0)) {
        combinedEnrichment.put("verification_status", "INSUFFICIENT_CONTEXT")
        Log.w(TAG, "Tender $tenderId lacked sufficient context (INSUFFICIENT_CONTEXT).")
      } else {
        combinedEnrichment.put("verification_status", "VALID")
      }

      // Classification
      statusUpdater?.invoke("Finalizing tender classification...")
      val classification = deriveDocumentClassification(combinedEnrichment)
      combinedEnrichment.put("document_type", classification)
      deriveTenderAdvertValue(combinedEnrichment)?.let { combinedEnrichment.put("tenderAdvertType", it) }
      Log.d(TAG, "Tender $tenderId classified as ${"$classification"}")
      statusUpdater?.invoke("Tender $tenderId classified as ${"$classification"}")

      fileManager.saveTextFile(prepared.folder, GEMMA_ENRICHMENT_FILENAME, combinedEnrichment.toString(2))
      mergeGemmaEnrichmentIntoManifest(prepared.folder, combinedEnrichment)
      firebaseSync.uploadTenderFolder(prepared.folder)
      fileManager.markTenderUploaded(prepared.folder)
      statusUpdater?.invoke("Tender enrichment and upload completed.")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Failed during Gemma enrichment for $tenderId", e)
      statusUpdater?.invoke("Enrichment failed: ${e.message}")
      return false
    }
  }

  private data class PreparedTenderDocuments(
    val folder: File,
    val readableFiles: List<File>,
    val extractedDocuments: List<ExtractedTenderDocument>,
    val documentBundle: String,
  )

  private data class ExtractedTenderDocument(
    val file: File,
    val text: String,
  )

  private fun createTenderInferenceModel(model: Model): Model {
    if (!shouldPreferNpuForTenderInference(model)) {
      return model
    }

    val configValues = model.configValues.toMutableMap()
    configValues[ConfigKeys.ACCELERATOR.label] = Accelerator.NPU.label

    Log.d(TAG, "Using NPU-first tender inference on ${Build.MODEL}")

    return model.copy(
      instance = null,
      initializing = false,
      cleanUpAfterInit = false,
      configValues = configValues,
      prevConfigValues = model.prevConfigValues.toMap(),
    )
  }

  private fun shouldPreferNpuForTenderInference(model: Model): Boolean {
    if (model.runtimeType != RuntimeType.LITERT_LM) {
      return false
    }

    if (!model.accelerators.contains(Accelerator.NPU)) {
      return false
    }

    if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) {
      return false
    }

    val deviceModel = Build.MODEL.uppercase()
    return deviceModel.startsWith("SM-S918") || deviceModel.startsWith("SM-F946")
  }

  private suspend fun prepareTenderDocuments(model: Model, tenderId: String): PreparedTenderDocuments? {
    val folder = fileManager.getTenderFolder(tenderId)
    val readableFiles =
      folder.listFiles()
        ?.filter { it.isFile }
        ?.filterNot { it.name.equals("manifest.json", ignoreCase = true) }
        ?.filterNot { it.name.equals("support-documents.json", ignoreCase = true) }
        ?.filter { isGemmaReadableFile(it) }
        .orEmpty()

    if (readableFiles.isEmpty()) {
      return null
    }

    val prioritizedFiles = prioritizeGemmaReadableFiles(readableFiles)
    Log.d(
      TAG,
      "Tender $tenderId prioritized ${prioritizedFiles.size}/${readableFiles.size} document(s) for Gemma: ${prioritizedFiles.joinToString { it.name }}",
    )

    val extractedDocuments = extractTenderDocuments(prioritizedFiles)
    if (extractedDocuments.isEmpty()) {
      return null
    }

    val documentBundle = buildDocumentBundle(extractedDocuments, MAX_CONSOLIDATED_DOCUMENT_CHARS)
    if (documentBundle.isBlank()) {
      return null
    }

    val initialized = ensureModelInitialized(model)
    if (!initialized) {
      return null
    }

    return PreparedTenderDocuments(
      folder = folder,
      readableFiles = prioritizedFiles,
      extractedDocuments = extractedDocuments,
      documentBundle = documentBundle,
    )
  }

  private fun prioritizeGemmaReadableFiles(files: List<File>): List<File> {
    val scored = files.map { file ->
      ScoredDocument(file = file, score = scoreTenderDocument(file.name))
    }

    val prioritized = scored
      .filter { it.score > 0 }
      .sortedByDescending { it.score }
      .map { it.file }
      .take(MAX_PRIORITY_DOCUMENTS)

    if (prioritized.isNotEmpty()) {
      return prioritized
    }

    return scored
      .sortedByDescending { it.score }
      .map { it.file }
      .take(MAX_PRIORITY_DOCUMENTS.coerceAtLeast(1))
  }

  private fun scoreTenderDocument(fileName: String): Int {
    val normalized = fileName.substringBeforeLast('.').replace(Regex("[_-]+"), " ")
    val highPriorityHits = highPriorityDocumentRegex.findAll(normalized).count()
    val lowPriorityHits = lowPriorityDocumentRegex.findAll(normalized).count()
    val boqBonus = if (normalized.contains("boq", ignoreCase = true)) 2 else 0
    val pdfBonus = if (fileName.endsWith(".pdf", ignoreCase = true)) 1 else 0
    return (highPriorityHits * 4) + boqBonus + pdfBonus - (lowPriorityHits * 5)
  }

  private suspend fun runGemmaInferenceForResult(model: Model, tenderId: String, prompt: String, resetConversation: Boolean = true): String {
    val deferred = CompletableDeferred<String>()
    var response = ""
    val coroutineScope = CoroutineScope(currentCoroutineContext())

    Log.d(TAG, "Gemma prompt length for $tenderId: ${prompt.length} chars")
    if (resetConversation) {
      model.runtimeHelper.resetConversation(model = model, supportImage = false, supportAudio = false)
      delay(300)
    }
    model.runtimeHelper.runInference(
      model = model,
      input = prompt,
      resultListener = { partialResult, done, _ ->
        if (partialResult.isNotBlank()) {
          response += partialResult
        }
        if (done && !deferred.isCompleted) {
          deferred.complete(response.ifBlank { "Gemma completed without returning any text." })
        }
      },
      cleanUpListener = {
        if (!deferred.isCompleted) {
          deferred.completeExceptionally(IllegalStateException("Gemma inference stopped before completion."))
        }
      },
      onError = { error ->
        if (!deferred.isCompleted) {
          deferred.completeExceptionally(IllegalStateException(error))
        }
      },
      coroutineScope = coroutineScope,
    )

    return deferred.await()
  }

  private suspend fun ensureModelInitialized(model: Model): Boolean {
    if (model.instance != null) {
      return true
    }

    var initializationError = ""
    model.runtimeHelper.initialize(
      context = context,
      model = model,
      supportImage = false,
      supportAudio = false,
      onDone = { error -> initializationError = error },
      coroutineScope = CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
    )

    val deadline = System.currentTimeMillis() + 30000L
    while (model.instance == null && initializationError.isEmpty() && System.currentTimeMillis() < deadline) {
      delay(100)
    }

    return model.instance != null && initializationError.isEmpty()
  }

  private suspend fun runMapReduceExtraction(
    model: Model,
    tenderId: String,
    documents: List<ExtractedTenderDocument>
  ): JSONObject {
    val finalEnrichment = JSONObject()
    finalEnrichment.put("compliance_meta", JSONObject())
    finalEnrichment.put("functionality_score", JSONObject())
    finalEnrichment.put("briefing", JSONObject())
    finalEnrichment.put("technical_specs", JSONObject())
    finalEnrichment.put("contact_details", JSONObject())
    finalEnrichment.put("returnables", JSONObject())
    finalEnrichment.put("local_content", JSONObject())
    finalEnrichment.put("financials", JSONObject())
    finalEnrichment.put("key_personnel", JSONObject())
    finalEnrichment.put("deadlines", JSONObject())
    finalEnrichment.put("eligibility", JSONObject())
    finalEnrichment.put("experience", JSONObject())
    finalEnrichment.put("logistics", JSONObject())

    val anchorRegex = Regex("\\b(cidb|grade|gb|ce|evaluation|functionality|weighting|80/20|90/10|briefing|compulsory|scope|duration|months|enquiries|contact|returnable|mandatory|local content|subcontracting|sbd 6.2|pricing|penalties|personnel|ecsa|clarification|validity|bbbee|level|eme|qse|experience|similar|projects|guarantee|surety|deposit|fee|payment terms|milestone|locality|municipality|lead time|schedule)\\b", RegexOption.IGNORE_CASE)

    val candidateChunks = selectHighestSignalChunks(documents, anchorRegex)
    Log.d(
      TAG,
      "Tender $tenderId selected ${candidateChunks.size} high-signal chunk(s) for map-reduce extraction.",
    )

    for (chunk in candidateChunks) {
      val safeToRun = ThermalGuard.awaitSafeTemperature(context)
      if (!safeToRun) {
        Log.w(TAG, "Skipping chunk for $tenderId — device too hot.")
        continue
      }

      val chunkPrompt = buildChunkExtractionPrompt(chunk.text)
      val chunkResponse = runGemmaInferenceForResult(model, tenderId, chunkPrompt)
      val chunkJson = try {
        extractJsonObject(chunkResponse)
      } catch (e: Exception) {
        Log.w(TAG, "Chunk enrichment parse failed for $tenderId from ${chunk.fileName}", e)
        continue
      }

      mergeMapReduceChunkEnrichment(finalEnrichment, chunkJson)
    }
    return finalEnrichment
  }

  private fun selectHighestSignalChunks(
    documents: List<ExtractedTenderDocument>,
    anchorRegex: Regex,
  ): List<ScoredChunk> {
    val scoredChunks = mutableListOf<ScoredChunk>()

    for (document in documents) {
      if (document.text.isBlank()) {
        continue
      }

      val fileChunks = document.text.chunked(CHUNK_SIZE)
      for ((index, rawChunk) in fileChunks.withIndex()) {
        val chunk = rawChunk.trim()
        if (chunk.isBlank() || !anchorRegex.containsMatchIn(chunk)) {
          continue
        }

        val matchCount = anchorRegex.findAll(chunk).count()
        val headingBonus = if (index == 0) 3 else 0
        val evidenceBonus = if (chunk.contains(Regex("\\b(mandatory|required|must|shall|compulsory)\\b", RegexOption.IGNORE_CASE))) 2 else 0
        scoredChunks += ScoredChunk(
          fileName = document.file.name,
          text = chunk,
          score = matchCount + headingBonus + evidenceBonus,
        )
      }
    }

    return scoredChunks
      .sortedByDescending { it.score }
      .distinctBy { it.text.lowercase() }
      .take(MAX_MAP_REDUCE_CHUNKS)
  }

  private suspend fun runComprehensiveTenderExtraction(
    model: Model,
    tenderId: String,
    folder: File,
    documentBundle: String,
  ): JSONObject {
    if (documentBundle.isBlank()) {
      return JSONObject()
    }

    val prompt = buildGemmaCombinedEnrichmentPrompt(
      tenderId = tenderId,
      manifestContext = buildManifestContext(folder),
      documentBundle = documentBundle,
    )
    val response = runGemmaInferenceForResult(model, tenderId, prompt, resetConversation = true)
    return try {
      extractJsonObject(response)
    } catch (e: Exception) {
      Log.w(TAG, "Comprehensive enrichment parse failed for $tenderId", e)
      JSONObject()
    }
  }

  private fun mergeComprehensiveEnrichment(target: JSONObject, source: JSONObject) {
    val documentType = source.optString("documentType", "unknown")
    if (documentType.isNotBlank() && documentType != "unknown") {
      target.put("documentType", documentType)
    }

    val briefDescription = source.optString("briefDescription", "").ifBlank { null }
    if (briefDescription != null) {
      target.put("briefDescription", briefDescription)
    }

    val industry = source.optString("industry", "unknown")
    if (industry.isNotBlank() && industry != "unknown") {
      target.put("industry", industry)
    }

    val beeLevel = source.optString("beeLevel", "unknown")
    if (beeLevel.isNotBlank() && beeLevel != "unknown") {
      target.put("beeLevel", beeLevel)
    }

    val completeDescription = source.optString("completeTenderDescription", "").ifBlank { null }
    if (completeDescription != null) {
      target.put("completeTenderDescription", completeDescription)
    }

    val estimatedValue = source.optJSONObject("estimatedTenderValue")
    if (estimatedValue != null && hasMeaningfulEstimatedTenderValue(estimatedValue)) {
      target.put("estimatedTenderValue", estimatedValue)
    }

    val requirements = source.optJSONArray("requirements")
    if (requirements != null && requirements.length() > 0) {
      target.put("requirements", requirements)
    }

    val billOfQuantities = source.optJSONArray("billOfQuantities")
    if (billOfQuantities != null && billOfQuantities.length() > 0) {
      target.put("billOfQuantities", billOfQuantities)
    }
  }

  private fun mergeCompanyMatchSignals(target: JSONObject, source: JSONObject) {
    val companyMatchSignals = JSONObject()

    mergeStringArrayIfPresent(companyMatchSignals, source, "targetProvinces")
    mergeStringArrayIfPresent(companyMatchSignals, source, "targetMunicipalities")
    mergeStringArrayIfPresent(companyMatchSignals, source, "deliveryLocations")
    mergeStringArrayIfPresent(companyMatchSignals, source, "requiredCertifications")
    mergeStringArrayIfPresent(companyMatchSignals, source, "requiredRegistrations")
    mergeStringArrayIfPresent(companyMatchSignals, source, "requiredEquipment")
    mergeStringArrayIfPresent(companyMatchSignals, source, "requiredCapabilities")
    mergeStringArrayIfPresent(companyMatchSignals, source, "sectorTags")
    mergeStringArrayIfPresent(companyMatchSignals, source, "subcontractingPreferences")
    mergeStringArrayIfPresent(companyMatchSignals, source, "riskFlags")

    val contractType = source.optString("contractType", "unknown")
    if (contractType.isNotBlank() && contractType != "unknown") {
      companyMatchSignals.put("contractType", contractType)
    }

    val contractTerm = source.optString("contractTerm", "").ifBlank { null }
    if (contractTerm != null) {
      companyMatchSignals.put("contractTerm", contractTerm)
    }

    if (source.has("siteWorkRequired") && !source.isNull("siteWorkRequired")) {
      companyMatchSignals.put("siteWorkRequired", source.optBoolean("siteWorkRequired"))
    }

    if (companyMatchSignals.length() > 0) {
      target.put("companyMatchSignals", companyMatchSignals)
    }
  }

  private fun seedManifestCompanySignals(target: JSONObject, manifest: JSONObject) {
    val companyMatchSignals = target.optJSONObject("companyMatchSignals") ?: JSONObject()
    var changed = false

    if ((companyMatchSignals.optJSONArray("targetProvinces")?.length() ?: 0) == 0) {
      val provinces = normalizeSignalEntries(manifest.optString("province", ""), "targetProvinces")
      if (provinces.length() > 0) {
        companyMatchSignals.put("targetProvinces", provinces)
        changed = true
      }
    }

    if ((companyMatchSignals.optJSONArray("deliveryLocations")?.length() ?: 0) == 0) {
      val locations = normalizeSignalEntries(manifest.optString("delivery", ""), "deliveryLocations")
      if (locations.length() > 0) {
        companyMatchSignals.put("deliveryLocations", locations)
        changed = true
      }
    }

    val contractType = companyMatchSignals.optString("contractType", "unknown")
    if (contractType.isBlank() || contractType == "unknown") {
      inferContractType(manifest)?.let {
        companyMatchSignals.put("contractType", it)
        changed = true
      }
    }

    val contractTerm = companyMatchSignals.optString("contractTerm", "").ifBlank { null }
    if (contractTerm == null) {
      inferContractTerm(manifest)?.let {
        companyMatchSignals.put("contractTerm", it)
        changed = true
      }
    }

    if (changed && companyMatchSignals.length() > 0) {
      target.put("companyMatchSignals", companyMatchSignals)
    }
  }

  private fun hasMeaningfulEstimatedTenderValue(value: JSONObject): Boolean {
    if (!value.isNull("amount") && value.optDouble("amount", Double.NaN).isFinite()) {
      return true
    }

    return value.optString("displayValue", "").isNotBlank()
  }

  private fun extractTextForGemma(file: File): String {
    return try {
      when (file.extension.lowercase()) {
        "pdf" -> scraper.extractText(file)
        "txt", "md", "csv" -> file.readText()
        else -> ""
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract text from ${file.name}", e)
      ""
    }
  }

  private fun isGemmaReadableFile(file: File): Boolean {
    return when (file.extension.lowercase()) {
      "pdf", "txt", "md", "csv" -> true
      else -> false
    }
  }

  private fun hasGemmaReadableDocuments(folder: File): Boolean {
    return folder.listFiles()
      ?.asSequence()
      ?.filter { it.isFile }
      ?.filterNot { it.name.equals("manifest.json", ignoreCase = true) }
      ?.filterNot { it.name.equals("support-documents.json", ignoreCase = true) }
      ?.any(::isGemmaReadableFile)
      ?: false
  }

  private fun extractTenderDocuments(files: List<File>): List<ExtractedTenderDocument> {
    return files.mapNotNull { file ->
      val extractedText = extractTextForGemma(file)
      if (extractedText.isBlank()) {
        null
      } else {
        ExtractedTenderDocument(file = file, text = extractedText)
      }
    }
  }

  private fun buildDocumentBundle(documents: List<ExtractedTenderDocument>, maxPromptChars: Int): String {
    val chunks = mutableListOf<String>()
    var totalChars = 0

    for (document in documents) {
      val header = "FILE: ${document.file.name}\n"
      val remainingChars = maxPromptChars - totalChars - header.length
      if (remainingChars <= 0) {
        break
      }

      val normalizedText = document.text.take(MAX_FILE_TEXT_CHARS).trim()
      val fittedText = normalizedText.take(remainingChars)
      if (fittedText.isBlank()) {
        continue
      }

      val chunk = (header + fittedText).trimEnd()
      chunks += chunk
      totalChars += chunk.length
    }

    return chunks.joinToString(separator = "\n\n---\n\n")
  }

  private fun buildManifestContext(folder: File): String {
    val manifest = JSONObject(File(folder, "manifest.json").readText())
    return """
      TENDER METADATA:
      tender_No: ${manifest.optString("tender_No", "")}
      description: ${manifest.optString("description", "")}
      type: ${manifest.optString("type", "")}
      organ_of_State: ${manifest.optString("organ_of_State", "")}
      province: ${manifest.optString("province", "")}
      closing_Date: ${manifest.optString("closing_Date", "")}
      delivery: ${manifest.optString("delivery", "")}
    """.trimIndent()
  }

  private fun buildGemmaCombinedEnrichmentPrompt(
    tenderId: String,
    manifestContext: String,
    documentBundle: String,
  ): String {
    return """
      You are extracting comprehensive tender details for tender $tenderId.

      Return exactly one valid JSON object and nothing else.

      Schema:
      {
        "documentType": "tender|advert|mixed|unknown",
        "briefDescription": "short plain-English summary",
        "industry": "one of: Information Technology & Telecommunications | Construction & Civil Engineering | Medical & Health Services | Security & Guarding Services | Professional & Consulting Services | Agriculture, Forestry & Fishing | Manufacturing & Industrial | Energy, Water & Waste Management | Transport, Storage & Logistics | Education & Training | Media, Advertising & Marketing | Tourism, Hospitality & Catering | Legal | unknown",
        "beeLevel": "B-BBEE level text such as Level 1, Level 2, exempted micro enterprise, QSE, non-compliant, or unknown",
        "estimatedTenderValue": {
          "amount": number or null,
          "currency": "ZAR or other currency code or null",
          "displayValue": "original value text if present, else null",
          "confidence": "high|medium|low"
        },
        "completeTenderDescription": "full detailed description based on the documents",
        "requirements": [
          {
            "category": "compliance|technical|professional_body|experience|pricing|administrative|mandatory_document|other",
            "requirement": "specific requirement text",
            "mandatory": true,
            "evidence": "short quote or paraphrase from the document"
          }
        ],
        "billOfQuantities": [
          {
            "item": "line item name",
            "description": "line item description",
            "quantity": "quantity text if present",
            "unit": "unit if present",
            "rate": "rate text if present",
            "amount": "amount text if present",
            "notes": "extra notes if present"
          }
        ],
        "targetProvinces": ["province names relevant to delivery, service coverage, or locality scoring"],
        "targetMunicipalities": ["municipality or district names explicitly relevant to this tender"],
        "deliveryLocations": ["delivery sites, project sites, campuses, depots, offices, or towns"],
        "requiredCertifications": ["ISO, OEM, professional certifications, or named accreditations"],
        "requiredRegistrations": ["CIDB, CSD, NHBRC, PSIRA, CIPC, professional councils, or other registrations"],
        "requiredEquipment": ["named equipment, vehicles, machinery, software platforms, or tools bidders must have"],
        "requiredCapabilities": ["specific services, technical capabilities, delivery abilities, staffing capabilities, or domain skills needed to perform the contract"],
        "sectorTags": ["short searchable tags for the tender domain and work package"],
        "contractType": "once_off|term|panel|framework|unknown",
        "contractTerm": "duration text such as 36 months, 3 years, once-off, or null",
        "siteWorkRequired": true,
        "subcontractingPreferences": ["designated groups, local subcontracting, set-asides, or partner preferences"],
        "riskFlags": ["short reasons a supplier may be disqualified or struggle to qualify"]
      }

      Rules:
      - Keep all text compact and evidence-based.
      - Keep briefDescription under 240 characters.
      - Keep completeTenderDescription under 900 characters.
      - If a field is missing, use null or "unknown" or an empty array as appropriate.
      - Only use the allowed industry values.
      - For beeLevel, prefer the explicit B-BBEE contributor level.
      - For requirements, include at most 8 of the highest-signal items that help match the tender to a company profile.
      - For billOfQuantities, include at most 3 representative line items. Prefer an empty array over long menu-style or repetitive item dumps.
      - For company-match fields, prefer exact named places, registrations, certifications, equipment, and capabilities from the documents.
      - For targetProvinces, only return South African province names when the province is explicit or strongly implied.
      - For requiredRegistrations and requiredCertifications, return specific named items like CIDB or ISO 9001, not generic filler such as "all applicable registrations".
      - For requiredCapabilities and riskFlags, keep each item short, specific, and commercially useful for supplier matching.
      - If the documents are only an advert rather than a full tender pack, say so in documentType and explain the limitation in completeTenderDescription.
      - Do not use markdown code fences.

      $manifestContext

      DOCUMENTS:
      $documentBundle
    """.trimIndent()
  }

  private fun buildChunkExtractionPrompt(chunk: String): String {
    return """
      You are extracting structured tender signals from one text block of a South African tender document.

      Return exactly one valid JSON object and nothing else.

      Schema:
      {
        "compliance_meta": {
          "cidb_grading": "string or null",
          "preference_points": "string or null",
          "is_jv_allowed": true
        },
        "functionality_score": {
          "minimum_threshold": 0,
          "criteria_weights": [{"criterion": "string", "weight": 0}]
        },
        "briefing": {
          "is_compulsory": true,
          "date": "string or null",
          "venue": "string or null"
        },
        "technical_specs": {
          "scope_of_work": "string or null",
          "duration": "string or null"
        },
        "contact_details": {
          "contact_person": "string or null",
          "email": "string or null",
          "phone": "string or null"
        },
        "returnables": {
          "mandatory_documents": ["string"]
        },
        "local_content": {
          "minimum_local_content_percent": 0,
          "mandatory_subcontracting_percent": 0
        },
        "financials": {
          "pricing_strategy": "string or null",
          "penalties_for_delay": "string or null",
          "estimated_budget_or_value": "string or null",
          "performance_guarantee": "string or null",
          "tender_deposit": "string or null",
          "payment_terms": "string or null"
        },
        "key_personnel": {
          "required_key_personnel": ["string"]
        },
        "deadlines": {
          "clarification_deadline": "string or null",
          "validity_period_days": 0
        },
        "eligibility": {
          "bbbee_minimum_level": "string or null",
          "eme_qse_set_aside": "string or null"
        },
        "experience": {
          "years_experience_required": "string or null",
          "similar_projects_required": "string or null"
        },
        "logistics": {
          "locality_points_awarded": "string or null",
          "delivery_lead_time": "string or null"
        }
      }

      Rules:
      - Be evidence-based and compact.
      - Use null for missing strings, empty arrays for missing lists, and omit nested fields you cannot support from this text block.
      - Only extract what is visible in this block. Do not infer across missing context.
      - Do not use markdown code fences.

      TEXT BLOCK:
      $chunk
    """.trimIndent()
  }

  private fun mergeMapReduceChunkEnrichment(target: JSONObject, source: JSONObject) {
    mergeNestedObject(
      target = target,
      source = source,
      key = "compliance_meta",
      stringKeys = listOf("cidb_grading", "preference_points"),
      booleanKeys = listOf("is_jv_allowed"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "functionality_score",
      intKeys = listOf("minimum_threshold"),
      arrayKeys = listOf("criteria_weights"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "briefing",
      stringKeys = listOf("date", "venue"),
      booleanKeys = listOf("is_compulsory"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "technical_specs",
      stringKeys = listOf("scope_of_work", "duration"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "contact_details",
      stringKeys = listOf("contact_person", "email", "phone"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "returnables",
      arrayKeys = listOf("mandatory_documents"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "local_content",
      intKeys = listOf("minimum_local_content_percent", "mandatory_subcontracting_percent"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "financials",
      stringKeys = listOf(
        "pricing_strategy",
        "penalties_for_delay",
        "estimated_budget_or_value",
        "performance_guarantee",
        "tender_deposit",
        "payment_terms",
      ),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "key_personnel",
      arrayKeys = listOf("required_key_personnel"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "deadlines",
      stringKeys = listOf("clarification_deadline"),
      intKeys = listOf("validity_period_days"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "eligibility",
      stringKeys = listOf("bbbee_minimum_level", "eme_qse_set_aside"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "experience",
      stringKeys = listOf("years_experience_required", "similar_projects_required"),
    )
    mergeNestedObject(
      target = target,
      source = source,
      key = "logistics",
      stringKeys = listOf("locality_points_awarded", "delivery_lead_time"),
    )
  }

  private fun mergeNestedObject(
    target: JSONObject,
    source: JSONObject,
    key: String,
    stringKeys: List<String> = emptyList(),
    intKeys: List<String> = emptyList(),
    booleanKeys: List<String> = emptyList(),
    arrayKeys: List<String> = emptyList(),
  ) {
    val sourceObject = source.optJSONObject(key) ?: return
    val targetObject = target.optJSONObject(key) ?: JSONObject()

    for (field in stringKeys) {
      val value = sourceObject.optString(field, "").ifBlank { null }
      if (value != null) {
        targetObject.put(field, value)
      }
    }

    for (field in intKeys) {
      if (sourceObject.has(field) && !sourceObject.isNull(field)) {
        targetObject.put(field, sourceObject.optInt(field))
      }
    }

    for (field in booleanKeys) {
      if (sourceObject.has(field) && !sourceObject.isNull(field)) {
        targetObject.put(field, sourceObject.optBoolean(field))
      }
    }

    for (field in arrayKeys) {
      val sourceArray = sourceObject.optJSONArray(field) ?: continue
      if (sourceArray.length() == 0) {
        continue
      }
      val targetArray = targetObject.optJSONArray(field) ?: JSONArray()
      for (index in 0 until sourceArray.length()) {
        targetArray.put(sourceArray.get(index))
      }
      targetObject.put(field, targetArray)
    }

    target.put(key, targetObject)
  }

  private fun deriveDocumentClassification(enrichment: JSONObject): String {
    return when (enrichment.optString("documentType", "unknown").lowercase()) {
      "advert" -> "ADVERT"
      "tender", "mixed" -> "FULL_TENDER"
      else -> {
        val hasReturnables = enrichment.optJSONObject("returnables")?.optJSONArray("mandatory_documents")?.length()?.let { it > 0 } == true
        val hasFunctionality = enrichment.optJSONObject("functionality_score")?.has("minimum_threshold") == true ||
          (enrichment.optJSONObject("functionality_score")?.optJSONArray("criteria_weights")?.length() ?: 0) > 0
        val hasFinancials = enrichment.optJSONObject("financials")?.let {
          it.optString("pricing_strategy", "").isNotBlank() ||
            it.optString("estimated_budget_or_value", "").isNotBlank() ||
            it.optString("tender_deposit", "").isNotBlank()
        } == true
        when {
          hasReturnables || hasFunctionality || hasFinancials -> "FULL_TENDER"
          else -> "UNKNOWN"
        }
      }
    }
  }

  private fun deriveTenderAdvertValue(enrichment: JSONObject): String? {
    return when (deriveDocumentClassification(enrichment)) {
      "ADVERT" -> "advert"
      "FULL_TENDER" -> "tender"
      else -> when (enrichment.optString("documentType", "unknown").lowercase()) {
        "advert" -> "advert"
        "tender", "mixed" -> "tender"
        else -> null
      }
    }
  }

  private fun mergeStringArrayIfPresent(target: JSONObject, source: JSONObject, key: String) {
    val values = source.optJSONArray(key) ?: return
    val normalizedValues = normalizeSignalEntries(values, key)
    if (normalizedValues.length() == 0) {
      return
    }
    target.put(key, normalizedValues)
  }

  private fun extractJsonObject(rawResponse: String): JSONObject {
    val trimmed = rawResponse.trim()
    val codeFenceStripped =
      trimmed
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()
    val candidates = linkedSetOf<String>()
    addJsonCandidate(candidates, codeFenceStripped)

    val firstBrace = codeFenceStripped.indexOf('{')
    if (firstBrace != -1) {
      addJsonCandidate(candidates, codeFenceStripped.substring(firstBrace))
    }

    extractBalancedJsonObjectCandidate(codeFenceStripped)?.let { addJsonCandidate(candidates, it) }
    repairTruncatedJsonObjectCandidate(codeFenceStripped)?.let { addJsonCandidate(candidates, it) }

    for (candidate in candidates) {
      try {
        return JSONObject(candidate)
      } catch (_: Exception) {
      }
    }

    throw IllegalArgumentException("No JSON object found in Gemma response.")
  }

  private fun addJsonCandidate(candidates: MutableSet<String>, rawCandidate: String) {
    val sanitized = sanitizeGemmaJsonCandidate(rawCandidate)
    if (sanitized.isNotBlank()) {
      candidates += sanitized
    }
  }

  private fun sanitizeGemmaJsonCandidate(rawJson: String): String {
    val cleaned = rawJson.trim()
    val output = StringBuilder(cleaned.length)
    var index = 0
    var inString = false
    var escaped = false

    while (index < cleaned.length) {
      val current = cleaned[index]

      if (inString) {
        when {
          escaped -> {
            output.append(current)
            escaped = false
          }
          current == '\\' -> {
            output.append(current)
            escaped = true
          }
          current == '"' -> {
            val next = nextNonWhitespace(cleaned, index + 1)
            if (next == ':' || next == ',' || next == '}' || next == ']') {
              output.append(current)
              inString = false
            } else {
              output.append("\\\"")
            }
          }
          current == '\n' -> output.append("\\n")
          current == '\r' -> output.append("\\r")
          current == '\t' -> output.append("\\t")
          else -> output.append(current)
        }
        index++
        continue
      }

      if (current == ',') {
        var lookAhead = index + 1
        while (lookAhead < cleaned.length && cleaned[lookAhead].isWhitespace()) {
          lookAhead++
        }

        if (lookAhead < cleaned.length) {
          val next = cleaned[lookAhead]
          if (next == ',' || next == '}' || next == ']') {
            index++
            continue
          }
        }
      }

      if ((current == '{' || current == '[') && index + 1 < cleaned.length) {
        output.append(current)
        var lookAhead = index + 1
        while (lookAhead < cleaned.length && cleaned[lookAhead].isWhitespace()) {
          output.append(cleaned[lookAhead])
          lookAhead++
        }
        if (lookAhead < cleaned.length && cleaned[lookAhead] == ',') {
          index = lookAhead + 1
          continue
        }
        index++
        continue
      }

      if (current == '"') {
        inString = true
      }

      output.append(current)
      index++
    }

    if (inString) {
      output.append('"')
    }

    return output
      .toString()
      .replace(Regex(",\\s*([}\\]])"), "$1")
      .trim()
  }

  private fun extractBalancedJsonObjectCandidate(value: String): String? {
    val start = value.indexOf('{')
    if (start == -1) {
      return null
    }

    val stack = ArrayDeque<Char>()
    var inString = false
    var escaped = false

    for (index in start until value.length) {
      val current = value[index]
      if (inString) {
        when {
          escaped -> escaped = false
          current == '\\' -> escaped = true
          current == '"' -> inString = false
        }
        continue
      }

      when (current) {
        '"' -> inString = true
        '{', '[' -> stack.addLast(current)
        '}' -> {
          if (stack.isNotEmpty() && stack.last() == '{') {
            stack.removeLast()
          }
          if (stack.isEmpty()) {
            return value.substring(start, index + 1)
          }
        }
        ']' -> {
          if (stack.isNotEmpty() && stack.last() == '[') {
            stack.removeLast()
          }
          if (stack.isEmpty()) {
            return value.substring(start, index + 1)
          }
        }
      }
    }

    return null
  }

  private fun repairTruncatedJsonObjectCandidate(value: String): String? {
    val start = value.indexOf('{')
    if (start == -1) {
      return null
    }

    val stack = ArrayDeque<Char>()
    val repaired = StringBuilder(value.length + 8)
    var inString = false
    var escaped = false

    for (index in start until value.length) {
      val current = value[index]
      repaired.append(current)

      if (inString) {
        when {
          escaped -> escaped = false
          current == '\\' -> escaped = true
          current == '"' -> inString = false
        }
        continue
      }

      when (current) {
        '"' -> inString = true
        '{', '[' -> stack.addLast(current)
        '}' -> if (stack.isNotEmpty() && stack.last() == '{') stack.removeLast()
        ']' -> if (stack.isNotEmpty() && stack.last() == '[') stack.removeLast()
      }
    }

    if (repaired.isEmpty()) {
      return null
    }

    if (inString) {
      repaired.append('"')
    }

    while (repaired.isNotEmpty() && repaired.last().isWhitespace()) {
      repaired.setLength(repaired.length - 1)
    }
    if (repaired.isNotEmpty() && repaired.last() == ',') {
      repaired.setLength(repaired.length - 1)
    }

    while (stack.isNotEmpty()) {
      repaired.append(if (stack.removeLast() == '{') '}' else ']')
    }

    return repaired.toString()
  }

  private fun normalizeSignalEntries(values: JSONArray, key: String): JSONArray {
    val normalized = linkedSetOf<String>()

    for (index in 0 until values.length()) {
      val rawValue = values.optString(index, "")
      normalized += normalizeSignalValues(rawValue, key)
    }

    return JSONArray(normalized.toList())
  }

  private fun normalizeSignalEntries(rawValue: String, key: String): JSONArray {
    return JSONArray(normalizeSignalValues(rawValue, key))
  }

  private fun normalizeSignalValues(rawValue: String, key: String): List<String> {
    val items = splitSignalValues(rawValue, key)
      .mapNotNull { normalizeSignalValue(it, key) }
      .distinctBy { it.lowercase() }

    return items
  }

  private fun splitSignalValues(rawValue: String, key: String): List<String> {
    val cleaned = rawValue
      .replace(Regex("[\\r\\n]+"), "\n")
      .replace(Regex("\\s+"), " ")
      .trim()
      .trim(',', ';')

    if (cleaned.isBlank()) {
      return emptyList()
    }

    val separatorRegex =
      when (key) {
        "targetProvinces", "targetMunicipalities", "deliveryLocations" -> Regex("\\s*(?:;|/|\\|)\\s*|\\s+\\n\\s+")
        else -> Regex("\\s*(?:,|;|/|\\||\\band\\b)\\s*|\\s+\\n\\s+")
      }

    return cleaned
      .split(separatorRegex)
      .map { it.trim().trim('-', '*', '•', '.', ' ') }
      .filter { it.isNotBlank() }
  }

  private fun normalizeSignalValue(rawValue: String, key: String): String? {
    val compact = rawValue
      .replace(Regex("\\s+"), " ")
      .trim()
      .trim(',', ';', ':', '-', ' ')

    if (compact.isBlank()) {
      return null
    }

    val lower = compact.lowercase()
    val genericPattern = Regex("^(unknown|n/?a|none|null|not specified|tbd|various|all applicable|if applicable)$")
    if (genericPattern.matches(lower)) {
      return null
    }

    if ((key == "requiredRegistrations" || key == "requiredCertifications") &&
      (lower.contains("other registrations") || lower.contains("other certifications"))
    ) {
      return null
    }

    return when (key) {
      "targetProvinces" -> normalizeProvinceName(compact)
      "requiredRegistrations", "requiredCertifications" -> normalizeAccreditationName(compact)
      "contractType" -> normalizeContractType(compact)
      else -> compact
        .replace(Regex("^(must have|must be|ability to|capable of)\\s+", RegexOption.IGNORE_CASE), "")
        .trim()
        .takeIf { it.length >= 3 }
    }
  }

  private fun normalizeProvinceName(value: String): String? {
    val normalized = value.lowercase().replace(Regex("[^a-z]"), "")
    val province =
      when (normalized) {
        "easterncape" -> "Eastern Cape"
        "freestate" -> "Free State"
        "gauteng" -> "Gauteng"
        "kwazulunatal", "kzn" -> "KwaZulu-Natal"
        "limpopo" -> "Limpopo"
        "mpumalanga" -> "Mpumalanga"
        "northwest" -> "North West"
        "northerncape" -> "Northern Cape"
        "westerncape", "wc" -> "Western Cape"
        else -> value.takeIf { it.length >= 4 }
      }

    return province?.trim()
  }

  private fun normalizeAccreditationName(value: String): String {
    return value
      .replace(Regex("\\bcidb\\b", RegexOption.IGNORE_CASE), "CIDB")
      .replace(Regex("\\bcsd\\b", RegexOption.IGNORE_CASE), "CSD")
      .replace(Regex("\\bnhbrc\\b", RegexOption.IGNORE_CASE), "NHBRC")
      .replace(Regex("\\bpsira\\b", RegexOption.IGNORE_CASE), "PSIRA")
      .replace(Regex("\\bcipc\\b", RegexOption.IGNORE_CASE), "CIPC")
      .replace(Regex("\\bb-?bbbee\\b", RegexOption.IGNORE_CASE), "B-BBEE")
      .replace(Regex("\\biso\\b", RegexOption.IGNORE_CASE), "ISO")
      .trim()
  }

  private fun inferContractType(manifest: JSONObject): String? {
    val source = listOf(
      manifest.optString("type", ""),
      manifest.optString("description", ""),
    ).joinToString(" ").lowercase()

    return when {
      source.contains("framework") -> "framework"
      source.contains("panel") -> "panel"
      source.contains("month") || source.contains("year") || source.contains("term contract") -> "term"
      source.contains("once off") || source.contains("once-off") -> "once_off"
      else -> null
    }
  }

  private fun inferContractTerm(manifest: JSONObject): String? {
    val source = listOf(
      manifest.optString("type", ""),
      manifest.optString("description", ""),
    ).joinToString(" ")

    return Regex("\\b\\d+\\s*(day|days|month|months|year|years)\\b", RegexOption.IGNORE_CASE)
      .find(source)
      ?.value
  }

  private fun normalizeContractType(value: String): String? {
    val normalized = value.lowercase().trim()
    return when {
      normalized.contains("framework") -> "framework"
      normalized.contains("panel") -> "panel"
      normalized.contains("term") || normalized.contains("month") || normalized.contains("year") -> "term"
      normalized.contains("once") -> "once_off"
      normalized == "unknown" -> null
      else -> value
    }
  }

  private fun nextNonWhitespace(value: String, startIndex: Int): Char? {
    var index = startIndex
    while (index < value.length) {
      val current = value[index]
      if (!current.isWhitespace()) {
        return current
      }
      index++
    }
    return null
  }

  private fun mergeGemmaEnrichmentIntoManifest(folder: File, enrichment: JSONObject) {
    val manifestFile = File(folder, "manifest.json")
    val manifest = JSONObject(manifestFile.readText())

    // Fix 2: Remove old duplication — no more nested gemmaEnrichment blob.
    // Fix 3: Promote all extracted fields directly to top-level manifest fields.

    // compliance_meta → top-level
    val compliance = enrichment.optJSONObject("compliance_meta")
    if (compliance != null) {
      val cidb = compliance.optString("cidb_grading", "").ifBlank { null }
      if (cidb != null) manifest.put("cidb_grading", cidb)
      val prefPoints = compliance.optString("preference_points", "").ifBlank { null }
      if (prefPoints != null) manifest.put("preference_points", prefPoints)
      if (compliance.has("is_jv_allowed")) manifest.put("is_jv_allowed", compliance.optBoolean("is_jv_allowed"))
    }

    // functionality_score → top-level
    val funcScore = enrichment.optJSONObject("functionality_score")
    if (funcScore != null) {
      val threshold = funcScore.optInt("minimum_threshold", -1)
      if (threshold > 0) manifest.put("min_functionality_threshold", threshold)
      val criteria = funcScore.optJSONArray("criteria_weights")
      if (criteria != null && criteria.length() > 0) manifest.put("functionality_criteria", criteria)
    }

    // briefing → top-level (merges with existing manifest API data)
    val briefing = enrichment.optJSONObject("briefing")
    if (briefing != null) {
      if (briefing.has("is_compulsory")) manifest.put("briefingCompulsory", briefing.optBoolean("is_compulsory"))
      val date = briefing.optString("date", "").ifBlank { null }
      if (date != null) manifest.put("briefingDate", date)
      val venue = briefing.optString("venue", "").ifBlank { null }
      if (venue != null && !manifest.has("briefingVenue")) manifest.put("briefingVenue", venue)
    }

    // technical_specs -> top-level
    val technicalSpecs = enrichment.optJSONObject("technical_specs")
    if (technicalSpecs != null) {
      val scope = technicalSpecs.optString("scope_of_work", "").ifBlank { null }
      if (scope != null) manifest.put("scope_of_work", scope)
      val duration = technicalSpecs.optString("duration", "").ifBlank { null }
      if (duration != null) manifest.put("contract_duration", duration)
    }

    // contact_details -> top-level
    val contactDetails = enrichment.optJSONObject("contact_details")
    if (contactDetails != null) {
      val contactPerson = contactDetails.optString("contact_person", "").ifBlank { null }
      if (contactPerson != null) manifest.put("contact_person", contactPerson)
      val email = contactDetails.optString("email", "").ifBlank { null }
      if (email != null) manifest.put("contact_email", email)
      val phone = contactDetails.optString("phone", "").ifBlank { null }
      if (phone != null) manifest.put("contact_phone", phone)
    }

    // returnables -> top-level
    val returnables = enrichment.optJSONObject("returnables")
    if (returnables != null) {
      val docs = returnables.optJSONArray("mandatory_documents")
      if (docs != null && docs.length() > 0) manifest.put("mandatory_documents", docs)
    }

    // local_content -> top-level
    val localContent = enrichment.optJSONObject("local_content")
    if (localContent != null) {
      if (localContent.has("minimum_local_content_percent")) manifest.put("minimum_local_content_percent", localContent.optInt("minimum_local_content_percent"))
      if (localContent.has("mandatory_subcontracting_percent")) manifest.put("mandatory_subcontracting_percent", localContent.optInt("mandatory_subcontracting_percent"))
    }

    // financials -> top-level
    val financials = enrichment.optJSONObject("financials")
    if (financials != null) {
      val pricingStrategy = financials.optString("pricing_strategy", "").ifBlank { null }
      if (pricingStrategy != null) manifest.put("pricing_strategy", pricingStrategy)
      val penalties = financials.optString("penalties_for_delay", "").ifBlank { null }
      if (penalties != null) manifest.put("penalties_for_delay", penalties)
      val budget = financials.optString("estimated_budget_or_value", "").ifBlank { null }
      if (budget != null) manifest.put("estimated_budget_or_value", budget)
    }

    // key_personnel -> top-level
    val keyPersonnel = enrichment.optJSONObject("key_personnel")
    if (keyPersonnel != null) {
      val personnel = keyPersonnel.optJSONArray("required_key_personnel")
      if (personnel != null && personnel.length() > 0) manifest.put("required_key_personnel", personnel)
    }

    // deadlines -> top-level
    val deadlines = enrichment.optJSONObject("deadlines")
    if (deadlines != null) {
      val clarification = deadlines.optString("clarification_deadline", "").ifBlank { null }
      if (clarification != null) manifest.put("clarification_deadline", clarification)
      if (deadlines.has("validity_period_days")) manifest.put("validity_period_days", deadlines.optInt("validity_period_days"))
    }

    // eligibility -> top-level
    val eligibility = enrichment.optJSONObject("eligibility")
    if (eligibility != null) {
      val bbbee = eligibility.optString("bbbee_minimum_level", "").ifBlank { null }
      if (bbbee != null) manifest.put("bbbee_minimum_level", bbbee)
      val setAside = eligibility.optString("eme_qse_set_aside", "").ifBlank { null }
      if (setAside != null) manifest.put("eme_qse_set_aside", setAside)
    }

    // experience -> top-level
    val experience = enrichment.optJSONObject("experience")
    if (experience != null) {
      val years = experience.optString("years_experience_required", "").ifBlank { null }
      if (years != null) manifest.put("years_experience_required", years)
      val similar = experience.optString("similar_projects_required", "").ifBlank { null }
      if (similar != null) manifest.put("similar_projects_required", similar)
    }

    // logistics -> top-level
    val logistics = enrichment.optJSONObject("logistics")
    if (logistics != null) {
      val locality = logistics.optString("locality_points_awarded", "").ifBlank { null }
      if (locality != null) manifest.put("locality_points_awarded", locality)
      val leadTime = logistics.optString("delivery_lead_time", "").ifBlank { null }
      if (leadTime != null) manifest.put("delivery_lead_time", leadTime)
    }

    // financials -> expand top-level with new fields
    val financialsExpanded = enrichment.optJSONObject("financials")
    if (financialsExpanded != null) {
      val guarantee = financialsExpanded.optString("performance_guarantee", "").ifBlank { null }
      if (guarantee != null) manifest.put("performance_guarantee", guarantee)
      val deposit = financialsExpanded.optString("tender_deposit", "").ifBlank { null }
      if (deposit != null) manifest.put("tender_deposit", deposit)
      val paymentTerms = financialsExpanded.optString("payment_terms", "").ifBlank { null }
      if (paymentTerms != null) manifest.put("payment_terms", paymentTerms)
    }

    // verification status
    manifest.put("gemma_verification", enrichment.optString("verification_status", "UNKNOWN"))

    // classification
    val classification = enrichment.optString("document_type", "UNKNOWN")
    if (classification != "UNKNOWN") manifest.put("document_type", classification)

    val documentType = enrichment.optString("documentType", "unknown")
    if (documentType.isNotBlank() && documentType != "unknown") manifest.put("documentType", documentType)

    val tenderAdvertType = enrichment.optString("tenderAdvertType", "").ifBlank {
      deriveTenderAdvertValue(enrichment)
    }
    if (tenderAdvertType != null) {
      manifest.put("tenderAdvertType", tenderAdvertType)
    }

    val briefDescription = enrichment.optString("briefDescription", "").ifBlank { null }
    if (briefDescription != null) manifest.put("briefDescription", briefDescription)

    val completeTenderDescription = enrichment.optString("completeTenderDescription", "").ifBlank { null }
    if (completeTenderDescription != null) manifest.put("completeTenderDescription", completeTenderDescription)

    val industry = enrichment.optString("industry", "unknown")
    if (industry.isNotBlank() && industry != "unknown") {
      manifest.put("industry", industry)
      manifest.put("industryCategory", industry)
    }

    val beeLevel = enrichment.optString("beeLevel", "unknown")
    if (beeLevel.isNotBlank() && beeLevel != "unknown") {
      manifest.put("beeLevel", beeLevel)
      manifest.put("bbbeeLevel", beeLevel)
    }

    val estimatedTenderValue = enrichment.optJSONObject("estimatedTenderValue")
    if (estimatedTenderValue != null && hasMeaningfulEstimatedTenderValue(estimatedTenderValue)) {
      manifest.put("estimatedTenderValue", estimatedTenderValue)
    }

    val requirements = enrichment.optJSONArray("requirements")
    if (requirements != null && requirements.length() > 0) {
      manifest.put("requirements", requirements)
    }

    val billOfQuantities = enrichment.optJSONArray("billOfQuantities")
    if (billOfQuantities != null && billOfQuantities.length() > 0) {
      manifest.put("billOfQuantities", billOfQuantities)
    }

    val companyMatchSignals = enrichment.optJSONObject("companyMatchSignals")
    if (companyMatchSignals != null && companyMatchSignals.length() > 0) {
      manifest.put("companyMatchSignals", companyMatchSignals)

      val targetProvinces = companyMatchSignals.optJSONArray("targetProvinces")
      if (targetProvinces != null && targetProvinces.length() > 0) {
        manifest.put("targetProvinces", targetProvinces)
      }

      val targetMunicipalities = companyMatchSignals.optJSONArray("targetMunicipalities")
      if (targetMunicipalities != null && targetMunicipalities.length() > 0) {
        manifest.put("targetMunicipalities", targetMunicipalities)
      }

      val deliveryLocations = companyMatchSignals.optJSONArray("deliveryLocations")
      if (deliveryLocations != null && deliveryLocations.length() > 0) {
        manifest.put("deliveryLocations", deliveryLocations)
      }

      val requiredCertifications = companyMatchSignals.optJSONArray("requiredCertifications")
      if (requiredCertifications != null && requiredCertifications.length() > 0) {
        manifest.put("requiredCertifications", requiredCertifications)
      }

      val requiredRegistrations = companyMatchSignals.optJSONArray("requiredRegistrations")
      if (requiredRegistrations != null && requiredRegistrations.length() > 0) {
        manifest.put("requiredRegistrations", requiredRegistrations)
      }

      val requiredEquipment = companyMatchSignals.optJSONArray("requiredEquipment")
      if (requiredEquipment != null && requiredEquipment.length() > 0) {
        manifest.put("requiredEquipment", requiredEquipment)
      }

      val requiredCapabilities = companyMatchSignals.optJSONArray("requiredCapabilities")
      if (requiredCapabilities != null && requiredCapabilities.length() > 0) {
        manifest.put("requiredCapabilities", requiredCapabilities)
      }

      val sectorTags = companyMatchSignals.optJSONArray("sectorTags")
      if (sectorTags != null && sectorTags.length() > 0) {
        manifest.put("sectorTags", sectorTags)
      }

      val subcontractingPreferences = companyMatchSignals.optJSONArray("subcontractingPreferences")
      if (subcontractingPreferences != null && subcontractingPreferences.length() > 0) {
        manifest.put("subcontractingPreferences", subcontractingPreferences)
      }

      val riskFlags = companyMatchSignals.optJSONArray("riskFlags")
      if (riskFlags != null && riskFlags.length() > 0) {
        manifest.put("riskFlags", riskFlags)
      }

      val contractType = companyMatchSignals.optString("contractType", "").ifBlank { null }
      if (contractType != null) {
        manifest.put("contractType", contractType)
      }

      val contractTerm = companyMatchSignals.optString("contractTerm", "").ifBlank { null }
      if (contractTerm != null) {
        manifest.put("contractTerm", contractTerm)
      }

      if (companyMatchSignals.has("siteWorkRequired") && !companyMatchSignals.isNull("siteWorkRequired")) {
        manifest.put("siteWorkRequired", companyMatchSignals.optBoolean("siteWorkRequired"))
      }
    }

    // Remove stale duplication fields from old pipeline if present
    manifest.remove("gemmaEnrichment")

    fileManager.writeManifest(folder, manifest.toString(2))
  }
}
