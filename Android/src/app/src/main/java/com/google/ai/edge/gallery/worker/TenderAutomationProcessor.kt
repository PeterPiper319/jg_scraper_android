package com.google.ai.edge.gallery.worker

import android.content.Context
import android.util.Log
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
    private const val MAX_FILE_TEXT_CHARS = 7000
    private const val MAX_ENRICHMENT_PREP_PROMPT_CHARS = 7000
    private const val MAX_CONSOLIDATED_DOCUMENT_CHARS = 7000
  }

  suspend fun enrichAndUploadTender(model: Model, tenderId: String) {
    var response = ""
    try {
      val folder = fileManager.getTenderFolder(tenderId)
      if (!hasGemmaReadableDocuments(folder)) {
        fileManager.clearTenderUploadedMarker(folder)
        firebaseSync.uploadTenderFolder(folder)
        fileManager.markTenderUploaded(folder)
        return
      }

      val prepared = prepareTenderDocuments(model = model, tenderId = tenderId) ?: return
      fileManager.clearTenderUploadedMarker(prepared.folder)
      val manifestContext = buildManifestContext(prepared.folder)
      val combinedEnrichment = JSONObject()

      response =
        runGemmaInferenceForResult(
          model = model,
          tenderId = tenderId,
          prompt =
            buildGemmaCombinedEnrichmentPrompt(
              tenderId = tenderId,
              manifestContext = manifestContext,
              documentBundle = buildDocumentBundle(prepared.readableFiles, MAX_CONSOLIDATED_DOCUMENT_CHARS),
            ),
        )
      val enrichmentJson = extractJsonObject(response)
      combinedEnrichment.put("documentType", enrichmentJson.optString("documentType", "unknown"))
      combinedEnrichment.put("briefDescription", enrichmentJson.optString("briefDescription", ""))
      combinedEnrichment.put("industry", extractIndustryValue(enrichmentJson))
      combinedEnrichment.put("beeLevel", extractBeeLevelValue(enrichmentJson))
      combinedEnrichment.put(
        "estimatedTenderValue",
        enrichmentJson.optJSONObject("estimatedTenderValue") ?: JSONObject.NULL,
      )
      combinedEnrichment.put(
        "completeTenderDescription",
        enrichmentJson.optString("completeTenderDescription", ""),
      )
      combinedEnrichment.put(
        "requirements",
        enrichmentJson.optJSONArray("requirements") ?: JSONArray(),
      )
      combinedEnrichment.put(
        "billOfQuantities",
        enrichmentJson.optJSONArray("billOfQuantities") ?: JSONArray(),
      )

      fileManager.saveTextFile(prepared.folder, GEMMA_ENRICHMENT_FILENAME, combinedEnrichment.toString(2))
      mergeGemmaEnrichmentIntoManifest(prepared.folder, combinedEnrichment)
      firebaseSync.uploadTenderFolder(prepared.folder)
      fileManager.markTenderUploaded(prepared.folder)
    } catch (e: Exception) {
      Log.e(TAG, "Failed during Gemma consolidated enrichment for $tenderId. Response preview: ${if (response.length > 200) response.take(200) + "..." else response}", e)
    }
  }

  private data class PreparedTenderDocuments(
    val folder: File,
    val readableFiles: List<File>,
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

    val documentBundle = buildDocumentBundle(readableFiles, MAX_ENRICHMENT_PREP_PROMPT_CHARS)
    if (documentBundle.isBlank()) {
      return null
    }

    val initialized = ensureModelInitialized(model)
    if (!initialized) {
      return null
    }

    return PreparedTenderDocuments(folder = folder, readableFiles = readableFiles)
  }

  private suspend fun runGemmaInferenceForResult(model: Model, tenderId: String, prompt: String): String {
    val deferred = CompletableDeferred<String>()
    var response = ""
    val coroutineScope = CoroutineScope(currentCoroutineContext())

    Log.d(TAG, "Gemma prompt length for $tenderId: ${prompt.length} chars")
    model.runtimeHelper.resetConversation(model = model, supportImage = false, supportAudio = false)
    delay(300)
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
      chunks.add(chunk)
      totalChars += chunk.length
    }

    return chunks.joinToString(separator = "\n\n---\n\n")
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
      - Include business compliance, technical requirements, and BOQ items if present.
      - If no BOQ or requirements are found, return empty arrays.

      $manifestContext

      DOCUMENTS:
      $documentBundle
    """.trimIndent()
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
    manifest.put("documentType", enrichment.optString("documentType", "unknown"))
    manifest.put("briefDescription", enrichment.optString("briefDescription", ""))
    manifest.put("industry", extractIndustryValue(enrichment))
    manifest.put("beeLevel", extractBeeLevelValue(enrichment))
    manifest.remove("industryCategory")
    manifest.put("completeTenderDescription", enrichment.optString("completeTenderDescription", ""))
    manifest.put(
      "estimatedTenderValue",
      enrichment.optJSONObject("estimatedTenderValue") ?: JSONObject.NULL,
    )
    manifest.put(
      "requirements",
      enrichment.optJSONArray("requirements") ?: JSONArray(),
    )
    manifest.put(
      "billOfQuantities",
      enrichment.optJSONArray("billOfQuantities") ?: JSONArray(),
    )
    val normalizedEnrichment = JSONObject(enrichment.toString())
    normalizedEnrichment.put("industry", extractIndustryValue(enrichment))
    normalizedEnrichment.put("beeLevel", extractBeeLevelValue(enrichment))
    normalizedEnrichment.remove("industryCategory")
    manifest.put("gemmaEnrichment", normalizedEnrichment)
    fileManager.writeManifest(folder, manifest.toString(2))
  }

  private fun extractIndustryValue(json: JSONObject): String {
    return json.optString("industry", json.optString("industryCategory", "unknown"))
  }

  private fun extractBeeLevelValue(json: JSONObject): String {
    return json.optString("beeLevel", json.optString("bbbEELevel", json.optString("bbbeeLevel", "unknown")))
  }
}
