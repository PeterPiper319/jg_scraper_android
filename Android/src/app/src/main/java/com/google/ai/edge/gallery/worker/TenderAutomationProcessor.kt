package com.google.ai.edge.gallery.worker

import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.common.ThermalGuard
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
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
    private const val MAX_CONSOLIDATED_DOCUMENT_CHARS = 24000
    private const val CHUNK_SIZE = 1500
  }

  suspend fun enrichAndUploadTender(model: Model, tenderId: String, statusUpdater: ((String) -> Unit)? = null): Boolean {
    try {
      val folder = fileManager.getTenderFolder(tenderId)
      if (!hasGemmaReadableDocuments(folder)) {
        fileManager.clearTenderUploadedMarker(folder)
        firebaseSync.uploadTenderFolder(folder)
        fileManager.markTenderUploaded(folder)
        return true
      }

      val prepared = prepareTenderDocuments(model = model, tenderId = tenderId) ?: return false
      fileManager.clearTenderUploadedMarker(prepared.folder)

      // Fix 1: Pre-seed enrichment from existing manifest API fields (free data, no Gemma needed)
      val manifest = JSONObject(File(prepared.folder, "manifest.json").readText())
      val combinedEnrichment = runMapReduceExtraction(model, tenderId, prepared.readableFiles)
      val comprehensiveEnrichment = runComprehensiveTenderExtraction(
        model = model,
        tenderId = tenderId,
        folder = prepared.folder,
        documentBundle = prepared.documentBundle,
      )
      val companyMatchSignals = runCompanyMatchSignalExtraction(
        model = model,
        tenderId = tenderId,
        folder = prepared.folder,
        documentBundle = prepared.documentBundle,
      )
      mergeComprehensiveEnrichment(combinedEnrichment, comprehensiveEnrichment)
      mergeCompanyMatchSignals(combinedEnrichment, companyMatchSignals)
      
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
      statusUpdater?.invoke("Running tender classification...")
      val classificationPrompt = """
        Based on the following extracted details from a tender, determine if the document(s) represent a full tender package or just a short advertisement/invitation.
        Full tenders typically have mandatory returnable documents, detailed functionality evaluation criteria, and pricing details. Adverts lack these.
        
        EXTRACTED DETAILS:
        ${combinedEnrichment.toString(2)}
        
        Return ONLY a valid JSON object matching this schema: {"document_type": "ADVERT" or "FULL_TENDER"}
      """.trimIndent()
      val classResponse = runGemmaInferenceForResult(model, tenderId, classificationPrompt, resetConversation = true)
      val classJson = try { extractJsonObject(classResponse) } catch (e: Exception) { JSONObject() }
      val classification = classJson.optString("document_type", "UNKNOWN")
      combinedEnrichment.put("document_type", classification)
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
    val documentBundle: String,
  )

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

    val documentBundle = buildDocumentBundle(readableFiles, MAX_CONSOLIDATED_DOCUMENT_CHARS)
    if (documentBundle.isBlank()) {
      return null
    }

    val initialized = ensureModelInitialized(model)
    if (!initialized) {
      return null
    }

    return PreparedTenderDocuments(folder = folder, readableFiles = readableFiles, documentBundle = documentBundle)
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
    files: List<File>
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

    for (file in files) {
      val extractedText = extractTextForGemma(file)
      if (extractedText.isBlank()) continue

      val fileChunks = extractedText.chunked(CHUNK_SIZE)

      for (chunk in fileChunks) {
        if (anchorRegex.containsMatchIn(chunk)) {
          val scoutPrompt = """
            Analyze this text block from a South African tender document.
            Identify if it contains information about CIDB Grading, Functionality Criteria, Compulsory Briefing, Scope of Work/Duration, Contact Details, Mandatory Returnable Documents, Local Content/Subcontracting, Financial/Pricing Info, Key Personnel, Deadlines/Timelines, BBBEE/Eligibility, Previous Experience, or Logistics/Locality.
            Return ONLY a valid JSON object matching this schema:
            {"contains_cidb": boolean, "contains_functionality": boolean, "contains_briefing": boolean, "contains_scope_duration": boolean, "contains_contact": boolean, "contains_returnables": boolean, "contains_local_content": boolean, "contains_financials": boolean, "contains_personnel": boolean, "contains_deadlines": boolean, "contains_bbbee": boolean, "contains_experience": boolean, "contains_logistics": boolean}
            
            TEXT BLOCK:
            $chunk
          """.trimIndent()

          val safeToRun = ThermalGuard.awaitSafeTemperature(context)
          if (!safeToRun) {
            Log.w(TAG, "Skipping chunk for $tenderId — device too hot.")
            continue
          }

          val scoutResponse = runGemmaInferenceForResult(model, tenderId, scoutPrompt)
          val scoutJson = try { extractJsonObject(scoutResponse) } catch (e: Exception) { continue }

          if (scoutJson.optBoolean("contains_cidb", false)) {
            val cidbPrompt = """
              Extract the CIDB Grading (e.g. 7GB, 5CE) and Preference Points (e.g. 80/20 or 90/10) from this text.
              Return ONLY a valid JSON object: {"cidb_grading": "string or null", "preference_points": "string or null", "is_jv_allowed": boolean}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, cidbPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("compliance_meta") ?: JSONObject()
            if (json.has("cidb_grading") && !json.isNull("cidb_grading")) current.put("cidb_grading", json.optString("cidb_grading"))
            if (json.has("preference_points") && !json.isNull("preference_points")) current.put("preference_points", json.optString("preference_points"))
            if (json.has("is_jv_allowed")) current.put("is_jv_allowed", json.optBoolean("is_jv_allowed"))
            finalEnrichment.put("compliance_meta", current)
          }

          if (scoutJson.optBoolean("contains_functionality", false)) {
            val funcPrompt = """
              Extract the Functionality Evaluation Criteria and Minimum Threshold from this text. 
              Look for numeric points/weights assigned to criteria like Experience, Methodology, or Key Personnel.
              Return ONLY a valid JSON object: {"minimum_threshold": integer or null, "criteria_weights": [{"criterion": "string", "weight": integer}]}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, funcPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("functionality_score") ?: JSONObject()
            if (json.has("minimum_threshold") && !json.isNull("minimum_threshold")) current.put("minimum_threshold", json.optInt("minimum_threshold"))
            if (json.has("criteria_weights") && json.optJSONArray("criteria_weights") != null) {
                val existingArr = current.optJSONArray("criteria_weights") ?: org.json.JSONArray()
                val newArr = json.optJSONArray("criteria_weights")
                if (newArr != null) {
                    for (i in 0 until newArr.length()) existingArr.put(newArr.getJSONObject(i))
                }
                current.put("criteria_weights", existingArr)
            }
            finalEnrichment.put("functionality_score", current)
          }

          if (scoutJson.optBoolean("contains_briefing", false)) {
            val briefPrompt = """
              Extract the Compulsory Briefing details from this text.
              Return ONLY a valid JSON object: {"is_compulsory": boolean, "date": "string or null", "venue": "string or null"}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, briefPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("briefing") ?: JSONObject()
            if (json.has("is_compulsory")) current.put("is_compulsory", json.optBoolean("is_compulsory"))
            if (json.has("date") && !json.isNull("date")) current.put("date", json.optString("date"))
            if (json.has("venue") && !json.isNull("venue")) current.put("venue", json.optString("venue"))
            finalEnrichment.put("briefing", current)
          }

          if (scoutJson.optBoolean("contains_scope_duration", false)) {
            val scopePrompt = """
              Extract the Scope of Work (a brief summary of the goods/services required) and the Contract Duration (e.g., '36 months', '3 years', 'once-off').
              Return ONLY a valid JSON object: {"scope_of_work": "string or null", "duration": "string or null"}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, scopePrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("technical_specs") ?: JSONObject()
            if (json.has("scope_of_work") && !json.isNull("scope_of_work")) current.put("scope_of_work", json.optString("scope_of_work"))
            if (json.has("duration") && !json.isNull("duration")) current.put("duration", json.optString("duration"))
            finalEnrichment.put("technical_specs", current)
          }

          if (scoutJson.optBoolean("contains_contact", false)) {
            val contactPrompt = """
              Extract the Contact Details for Enquiries from this text.
              Return ONLY a valid JSON object: {"contact_person": "string or null", "email": "string or null", "phone": "string or null"}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, contactPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("contact_details") ?: JSONObject()
            if (json.has("contact_person") && !json.isNull("contact_person")) current.put("contact_person", json.optString("contact_person"))
            if (json.has("email") && !json.isNull("email")) current.put("email", json.optString("email"))
            if (json.has("phone") && !json.isNull("phone")) current.put("phone", json.optString("phone"))
            finalEnrichment.put("contact_details", current)
          }

          if (scoutJson.optBoolean("contains_returnables", false)) {
            val returnablesPrompt = """
              Extract any Mandatory Returnable Documents mentioned in this text (e.g., Tax Clearance Certificate, BBBEE Certificate, SBD Forms).
              Return ONLY a valid JSON object: {"mandatory_documents": ["string", "string"]}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, returnablesPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("returnables") ?: JSONObject()
            if (json.has("mandatory_documents") && json.optJSONArray("mandatory_documents") != null) {
              val existingArr = current.optJSONArray("mandatory_documents") ?: org.json.JSONArray()
              val newArr = json.optJSONArray("mandatory_documents")
              if (newArr != null) {
                for (i in 0 until newArr.length()) existingArr.put(newArr.getString(i))
              }
              current.put("mandatory_documents", existingArr)
            }
            finalEnrichment.put("returnables", current)
          }

          if (scoutJson.optBoolean("contains_local_content", false)) {
            val localContentPrompt = """
              Extract the Local Content Requirements and Mandatory Subcontracting Details from this text.
              Return ONLY a valid JSON object: {"minimum_local_content_percent": integer or null, "mandatory_subcontracting_percent": integer or null}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, localContentPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("local_content") ?: JSONObject()
            if (json.has("minimum_local_content_percent") && !json.isNull("minimum_local_content_percent")) current.put("minimum_local_content_percent", json.optInt("minimum_local_content_percent"))
            if (json.has("mandatory_subcontracting_percent") && !json.isNull("mandatory_subcontracting_percent")) current.put("mandatory_subcontracting_percent", json.optInt("mandatory_subcontracting_percent"))
            finalEnrichment.put("local_content", current)
          }

          if (scoutJson.optBoolean("contains_financials", false)) {
            val financialsPrompt = """
              Extract Financial and Pricing Information such as Pricing Strategy, Penalties for Delay, Estimated Budget or Value, Performance Guarantees or Sureties, Tender Deposit or Fee, and Payment Terms from this text.
              Return ONLY a valid JSON object: {"pricing_strategy": "string or null", "penalties_for_delay": "string or null", "estimated_budget_or_value": "string or null", "performance_guarantee": "string or null", "tender_deposit": "string or null", "payment_terms": "string or null"}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, financialsPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("financials") ?: JSONObject()
            if (json.has("pricing_strategy") && !json.isNull("pricing_strategy")) current.put("pricing_strategy", json.optString("pricing_strategy"))
            if (json.has("penalties_for_delay") && !json.isNull("penalties_for_delay")) current.put("penalties_for_delay", json.optString("penalties_for_delay"))
            if (json.has("estimated_budget_or_value") && !json.isNull("estimated_budget_or_value")) current.put("estimated_budget_or_value", json.optString("estimated_budget_or_value"))
            if (json.has("performance_guarantee") && !json.isNull("performance_guarantee")) current.put("performance_guarantee", json.optString("performance_guarantee"))
            if (json.has("tender_deposit") && !json.isNull("tender_deposit")) current.put("tender_deposit", json.optString("tender_deposit"))
            if (json.has("payment_terms") && !json.isNull("payment_terms")) current.put("payment_terms", json.optString("payment_terms"))
            finalEnrichment.put("financials", current)
          }

          if (scoutJson.optBoolean("contains_personnel", false)) {
            val personnelPrompt = """
              Extract Key Personnel Requirements from this text.
              Return ONLY a valid JSON object: {"required_key_personnel": ["string", "string"]}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, personnelPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("key_personnel") ?: JSONObject()
            if (json.has("required_key_personnel") && json.optJSONArray("required_key_personnel") != null) {
              val existingArr = current.optJSONArray("required_key_personnel") ?: org.json.JSONArray()
              val newArr = json.optJSONArray("required_key_personnel")
              if (newArr != null) {
                for (i in 0 until newArr.length()) existingArr.put(newArr.getString(i))
              }
              current.put("required_key_personnel", existingArr)
            }
            finalEnrichment.put("key_personnel", current)
          }

          if (scoutJson.optBoolean("contains_deadlines", false)) {
            val deadlinesPrompt = """
              Extract Key Deadlines and Timelines such as Clarification Deadline and Validity Period (in days) from this text.
              Return ONLY a valid JSON object: {"clarification_deadline": "string or null", "validity_period_days": integer or null}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, deadlinesPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("deadlines") ?: JSONObject()
            if (json.has("clarification_deadline") && !json.isNull("clarification_deadline")) current.put("clarification_deadline", json.optString("clarification_deadline"))
            if (json.has("validity_period_days") && !json.isNull("validity_period_days")) current.put("validity_period_days", json.optInt("validity_period_days"))
            finalEnrichment.put("deadlines", current)
          }

          if (scoutJson.optBoolean("contains_bbbee", false)) {
            val bbbeePrompt = """
              Extract BBBEE Requirements such as minimum contributor level and EME/QSE set-aside instructions from this text.
              Return ONLY a valid JSON object: {"bbbee_minimum_level": "string or null", "eme_qse_set_aside": "string or null"}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, bbbeePrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("eligibility") ?: JSONObject()
            if (json.has("bbbee_minimum_level") && !json.isNull("bbbee_minimum_level")) current.put("bbbee_minimum_level", json.optString("bbbee_minimum_level"))
            if (json.has("eme_qse_set_aside") && !json.isNull("eme_qse_set_aside")) current.put("eme_qse_set_aside", json.optString("eme_qse_set_aside"))
            finalEnrichment.put("eligibility", current)
          }

          if (scoutJson.optBoolean("contains_experience", false)) {
            val experiencePrompt = """
              Extract Previous Experience Requirements such as years of experience required or number of similar completed projects from this text.
              Return ONLY a valid JSON object: {"years_experience_required": "string or null", "similar_projects_required": "string or null"}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, experiencePrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("experience") ?: JSONObject()
            if (json.has("years_experience_required") && !json.isNull("years_experience_required")) current.put("years_experience_required", json.optString("years_experience_required"))
            if (json.has("similar_projects_required") && !json.isNull("similar_projects_required")) current.put("similar_projects_required", json.optString("similar_projects_required"))
            finalEnrichment.put("experience", current)
          }

          if (scoutJson.optBoolean("contains_logistics", false)) {
            val logisticsPrompt = """
              Extract Geographic and Logistical Constraints such as locality points awarded for specific municipalities and delivery lead times/schedule from this text.
              Return ONLY a valid JSON object: {"locality_points_awarded": "string or null", "delivery_lead_time": "string or null"}
              
              TEXT BLOCK:
              $chunk
            """.trimIndent()
            val resp = runGemmaInferenceForResult(model, tenderId, logisticsPrompt)
            val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
            val current = finalEnrichment.optJSONObject("logistics") ?: JSONObject()
            if (json.has("locality_points_awarded") && !json.isNull("locality_points_awarded")) current.put("locality_points_awarded", json.optString("locality_points_awarded"))
            if (json.has("delivery_lead_time") && !json.isNull("delivery_lead_time")) current.put("delivery_lead_time", json.optString("delivery_lead_time"))
            finalEnrichment.put("logistics", current)
          }
        }
      }
    }
    return finalEnrichment
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

  private suspend fun runCompanyMatchSignalExtraction(
    model: Model,
    tenderId: String,
    folder: File,
    documentBundle: String,
  ): JSONObject {
    if (documentBundle.isBlank()) {
      return JSONObject()
    }

    val prompt = buildCompanyMatchSignalPrompt(
      tenderId = tenderId,
      manifestContext = buildManifestContext(folder),
      documentBundle = documentBundle,
    )
    val response = runGemmaInferenceForResult(model, tenderId, prompt, resetConversation = true)
    return try {
      extractJsonObject(response)
    } catch (e: Exception) {
      Log.w(TAG, "Company match extraction parse failed for $tenderId", e)
      JSONObject()
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

  private fun buildDocumentBundle(files: List<File>, maxPromptChars: Int): String {
    val chunks = mutableListOf<String>()
    var totalChars = 0

    for (file in files) {
      val extractedText = extractTextForGemma(file)
      if (extractedText.isBlank()) {
        continue
      }

      val header = "FILE: ${file.name}\n"
      val remainingChars = maxPromptChars - totalChars - header.length
      if (remainingChars <= 0) {
        break
      }

      val normalizedText = extractedText.take(MAX_FILE_TEXT_CHARS).trim()
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
        ]
      }

      Rules:
      - Keep all text compact and evidence-based.
      - If a field is missing, use null or "unknown" or an empty array as appropriate.
      - Only use the allowed industry values.
      - For beeLevel, prefer the explicit B-BBEE contributor level.
      - For requirements and billOfQuantities, include the most important items that help match the tender to a company profile.
      - If the documents are only an advert rather than a full tender pack, say so in documentType and explain the limitation in completeTenderDescription.
      - Do not use markdown code fences.

      $manifestContext

      DOCUMENTS:
      $documentBundle
    """.trimIndent()
  }

  private fun buildCompanyMatchSignalPrompt(
    tenderId: String,
    manifestContext: String,
    documentBundle: String,
  ): String {
    return """
      You are extracting company-matching signals for tender $tenderId.

      Return exactly one valid JSON object and nothing else.

      Schema:
      {
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
      - Be evidence-based and compact.
      - Only include items that materially help match a tender to a company profile.
      - Prefer exact named places, registrations, certifications, and capabilities from the documents.
      - Use empty arrays for missing list fields.
      - Use null for contractTerm when absent.
      - Use unknown for contractType when the contract shape is not stated.
      - Use null for siteWorkRequired when the documents do not make it clear.
      - Do not use markdown code fences.

      $manifestContext

      DOCUMENTS:
      $documentBundle
    """.trimIndent()
  }

  private fun mergeStringArrayIfPresent(target: JSONObject, source: JSONObject, key: String) {
    val values = source.optJSONArray(key) ?: return
    if (values.length() == 0) {
      return
    }
    target.put(key, values)
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
    return try {
      JSONObject(sanitizeGemmaJsonCandidate(codeFenceStripped))
    } catch (_: Exception) {
      val start = codeFenceStripped.indexOf('{')
      val end = codeFenceStripped.lastIndexOf('}')
      if (start == -1 || end == -1 || end <= start) {
        throw IllegalArgumentException("No JSON object found in Gemma response.")
      }
      JSONObject(sanitizeGemmaJsonCandidate(codeFenceStripped.substring(start, end + 1)))
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

    return output.toString()
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
