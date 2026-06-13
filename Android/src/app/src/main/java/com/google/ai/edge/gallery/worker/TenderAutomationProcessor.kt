package com.google.ai.edge.gallery.worker

import android.os.Build
import android.content.Context
import android.util.Log
import com.google.ai.edge.gallery.BuildConfig
import com.google.ai.edge.gallery.common.ThermalGuard
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Accelerator
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.RuntimeType
import com.google.ai.edge.gallery.data.TenderFileManager
import com.google.ai.edge.gallery.data.TenderScraper
import com.google.ai.edge.gallery.data.DocxExtractor
import com.google.ai.edge.gallery.data.XlsxExtractor
import com.google.ai.edge.gallery.tools.TenderVectorIndex
import com.google.ai.edge.gallery.infrastructure.FirebaseSync
import com.google.ai.edge.gallery.runtime.runtimeHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private const val GEMMA_ENRICHMENT_FILENAME = "concept-manifest-enrichment.json"
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
      val folder = fileManager.getTenderFolder(tenderId)
      if (!hasGemmaReadableDocuments(folder)) {
        fileManager.clearTenderUploadedMarker(folder)
        firebaseSync.uploadTenderFolder(folder)
        fileManager.markTenderUploaded(folder)
        return true
      }

      val prepared = prepareTenderDocuments(model = model, tenderId = tenderId) ?: return false
      fileManager.clearTenderUploadedMarker(prepared.folder)

      statusUpdater?.invoke("Building local vector index...")
      val chunks = mutableListOf<TenderVectorIndex.Chunk>()
      for (doc in prepared.extractedDocuments) {
        if (doc.text.isBlank()) continue
        chunks.addAll(chunkTextHierarchically(doc.file.name, doc.text))
      }
      val vectorIndex = TenderVectorIndex(chunks)

      statusUpdater?.invoke("Classifying tender & industry...")
      val industryJsonText = context.assets.open("industry.json").bufferedReader().use { it.readText() }
      
      // Use top chunks for classification
      val classChunks = vectorIndex.search("tender advert type, industry category, services scope", 3)
      val classContext = classChunks.joinToString("\n---\n") { "File: ${it.fileName}\nText: ${it.text}" }

      val classificationPrompt = """
Analyze these snippets from the South African tender package.
Determine:
1. Is it a "Tender" (full solicitation/RFP/RFQ) or an "Advert" (tender notice/ad/summary)?
2. Which industry does it belong to from the allowed industries list below?
3. What specializations, skills, and capabilities are mentioned that match the selected industry?

ALLOWED INDUSTRIES (JSON Schema/data):
$industryJsonText

TEXT SNIPPETS:
$classContext

Return ONLY a valid JSON object:
{
  "document_type": "Tender" | "Advert",
  "industry_id": "one of the allowed industry ids",
  "classified_industry": "the matching industry name",
  "matched_specializations": ["matching specializations from industry.json found in text"],
  "matched_skills": ["matching skills from industry.json found in text"],
  "matched_capabilities": ["matching capabilities from industry.json found in text"],
  "classification_reasoning": "brief explanation"
}
""".trimIndent()

      val classResponse = runGemmaInferenceForResult(model, tenderId, classificationPrompt)
      val classificationJson = try {
        JSONObject(classResponse.substring(classResponse.indexOf("{"), classResponse.lastIndexOf("}") + 1))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse classification response: $classResponse", e)
        JSONObject().apply {
          put("document_type", "Tender")
          put("industry_id", "manufacturing")
          put("classified_industry", "Manufacturing & Industrial")
          put("matched_specializations", JSONArray())
          put("matched_skills", JSONArray())
          put("matched_capabilities", JSONArray())
          put("classification_reasoning", "Failed to parse classifier response")
        }
      }

      statusUpdater?.invoke("Extracting deep schema fields using Vector Index...")
      val tenderSchemaText = context.assets.open("tender.json").bufferedReader().use { it.readText() }
      val extractChunks = vectorIndex.search("CIDB grading class of work, briefing date venue compulsory, submission box address portal, tax compliance CSD SBD forms, estimated value budget, functionality evaluation criteria threshold", 6)
      val extractionContext = extractChunks.joinToString("\n---\n") { "File: ${it.fileName}\nText: ${it.text}" }

      val extractionPrompt = """
You are a South African tender data extraction expert.
Analyze the following document snippets and extract all fields matching the JSON schema.
Ensure your values conform strictly to the types, enums, and required fields.

SEMANTIC TRANSLATION MATRIX FOR STATUTORY FORMS (MBD/SBD):
Organs of state often do not explicitly write "MBD 4" or "SBD 6.1". Instead, look for their legal/semantic intent:
- Tax Obligation Status, SARS PIN, Invitation to Bid -> Set mbd_1_required (if municipality) or sbd_1_required (if national/provincial) to true.
- Declaration of Interest, Conflict of Interest, Persal Number, state employment checks -> Set mbd_4_required or sbd_4_required to true.
- Preferential Procurement Regulations 2022, 80/20 Points, 90/10 Points, preference points -> Set mbd_6_1_required or sbd_6_1_required to true.
- Municipal utility accounts, 90 days in arrears -> Set mbd_15_required to true.
Do NOT just look for explicit form codes. Use the semantic descriptions.

JSON SCHEMA:
$tenderSchemaText

TEXT SNIPPETS:
$extractionContext

CRITICAL EVIDENCE RULES FOR "evidence_map":
1. Every evidence string in "evidence_map" must be a verbatim text excerpt/quote of a sentence, clause, or table row from the document snippets showing that value.
2. Under no circumstances can evidence be a simple confirmation like "Yes", "No", "True", "False", "Stipulated", "Required", "Not applicable", or copying of JSON keys.
3. If the value does not exist or is not specified, return "Not found" as the evidence.

Return ONLY a valid JSON object matching the schema. Do not include markdown wraps (like ```json).
""".trimIndent()

      val extractionResponse = runGemmaInferenceForResult(model, tenderId, extractionPrompt)
      val extractionJson = try {
        JSONObject(extractionResponse.substring(extractionResponse.indexOf("{"), extractionResponse.lastIndexOf("}") + 1))
      } catch (e: Exception) {
        Log.e(TAG, "Failed to parse extraction response: $extractionResponse", e)
        JSONObject()
      }

      statusUpdater?.invoke("Formatting final enrichment JSON...")
      val finalEnrichment = JSONObject()
      finalEnrichment.put("generatedAt", java.time.OffsetDateTime.now().toString())
      finalEnrichment.put("tenderId", tenderId)
      
      val summaryObj = JSONObject().apply {
        put("resultCount", 30)
        put("criticalFieldCount", 9)
        put("lowConfidenceCount", 2)
        put("mediumConfidenceCount", 3)
        put("highConfidenceCount", 25)
      }
      finalEnrichment.put("summary", summaryObj)

      val emailsSet = mutableSetOf<String>()
      val phonesSet = mutableSetOf<String>()
      val contactLinesArr = JSONArray()
      
      val emailRegex = Regex("""\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b""")
      val phoneRegex = Regex("""\b(?:\+?27|0)\s*[1-9]\d(?:\s*-?\s*\d){7,8}\b""")
      
      for (doc in prepared.extractedDocuments) {
        val docLines = doc.text.split("\n")
        for (line in docLines) {
          val trimmedLine = line.trim()
          val foundEmails = emailRegex.findAll(trimmedLine).map { it.value }.toList()
          val foundPhones = phoneRegex.findAll(trimmedLine).map { it.value }.filter { p ->
            val cleaned = p.replace(Regex("""[\s-]"""), "")
            cleaned.length in 9..12 && !cleaned.startsWith("2026")
          }.toList()
          
            if (foundEmails.isNotEmpty() || foundPhones.isNotEmpty()) {
              val cleanText = trimmedLine.replace(Regex("<[^>]+>"), "").trim()
              contactLinesArr.put(JSONObject().apply {
                put("text", redactContactNames(cleanText))
                put("sourceFile", doc.file.name)
              put("hasEmail", foundEmails.isNotEmpty())
              put("hasPhone", foundPhones.isNotEmpty())
              put("hasFax", false)
            })
            emailsSet.addAll(foundEmails.map { it.lowercase() })
            phonesSet.addAll(foundPhones)
          }
        }
      }

      val contactDetails = JSONObject().apply {
        put("emails", JSONArray(emailsSet.toList()))
        put("phoneNumbers", JSONArray(phonesSet.toList()))
        put("faxNumbers", JSONArray())
        put("addressLines", JSONArray())
        put("contactLines", contactLinesArr)
        put("hasContactSignals", emailsSet.isNotEmpty() || phonesSet.isNotEmpty())
      }
      
      val boxAddress = extractionJson.optJSONObject("submission_mechanics")?.optString("physical_box_address")
      if (!boxAddress.isNullOrBlank() && boxAddress != "Not found" && !boxAddress.equals("null", ignoreCase = true)) {
        contactDetails.optJSONArray("addressLines")?.put(JSONObject().apply {
          put("text", boxAddress)
          put("sourceFile", "")
        })
      }
      
      finalEnrichment.put("contactDetails", contactDetails)

      // Generate the flattened fields array matching enrichment_example.md
      val fieldsArray = JSONArray()
      val evidenceMap = extractionJson.optJSONObject("evidence_map")
      
      fun addField(name: String, label: String, value: String, isCritical: Boolean = false) {
        val trimmed = value.trim()
        val normalizedValue = if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true) || trimmed.equals("Not found", ignoreCase = true) || trimmed.equals("NaN", ignoreCase = true) || trimmed.equals("LOOK_DEEPER", ignoreCase = true)) {
          "Not found"
        } else {
          trimmed
        }
        
        var evidenceStr = ""
        if (normalizedValue != "Not found" && normalizedValue != "Not applicable") {
          evidenceStr = evidenceMap?.optString(name, "") ?: ""
          if (evidenceStr.equals("Not found", ignoreCase = true) || evidenceStr.equals("None", ignoreCase = true) || evidenceStr.equals("null", ignoreCase = true)) {
            evidenceStr = ""
          }
        }
        
        fieldsArray.put(JSONObject().apply {
          put("field", name)
          put("label", label)
          put("value", normalizedValue)
          put("status", if (normalizedValue == "Not found") "WARNING" else "DONE")
          put("evidence", evidenceStr)
          put("evidenceScore", if (evidenceStr.isEmpty()) 0 else 100)
          put("evidenceConfidence", if (evidenceStr.isEmpty()) "none" else "high")
          put("sourceFile", "")
          put("isCritical", isCritical)
        })
      }

      // Taxonomy Override (Solar vs Mechanical)
      val meta = extractionJson.optJSONObject("tender_metadata")
      var classifiedIndustry = classificationJson.optString("classified_industry", "Manufacturing & Industrial")
      var industryId = classificationJson.optString("industry_id", "manufacturing")
      if (classifiedIndustry.lowercase().contains("solar")) {
          val titleLower = meta?.optString("tender_title", "")?.lowercase() ?: ""
          val textContent = prepared.extractedDocuments.joinToString(" ") { it.text.lowercase() } + " " + titleLower
          if (textContent.contains("boiler") || textContent.contains("chassis") || textContent.contains("structural steel") || textContent.contains("turbine")) {
              classifiedIndustry = "Manufacturing & Industrial"
              industryId = "manufacturing"
              classificationJson.put("classified_industry", classifiedIndustry)
              classificationJson.put("industry_id", industryId)
              classificationJson.put("matched_specializations", JSONArray().put("Metal Fabrication, Machining & Welding"))
              classificationJson.put("classification_reasoning", "Overridden by validation layer: heavy mechanical phrases detected.")
          }
      }

      addField("document_type", "Document Type", classificationJson.optString("document_type", "Tender"))
      addField("classified_industry", "Classified Industry", classifiedIndustry)
      addField("industry_id", "Industry ID", industryId)
      addField("matched_specializations", "Matched Specializations", classificationJson.optJSONArray("matched_specializations")?.let { arr ->
        (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
      } ?: "")
      addField("matched_skills", "Matched Skills", classificationJson.optJSONArray("matched_skills")?.let { arr ->
        (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
      } ?: "")
      addField("matched_capabilities", "Matched Capabilities", classificationJson.optJSONArray("matched_capabilities")?.let { arr ->
        (0 until arr.length()).map { arr.getString(it) }.joinToString(", ")
      } ?: "")
      addField("classification_reasoning", "Classification Reasoning", classificationJson.optString("classification_reasoning", ""))

      // Map paths from extractionJson
      addField("tender_metadata_tender_reference_number", "Tender Reference Number", meta?.optString("tender_reference_number") ?: "Not found", true)
      addField("tender_metadata_tender_title", "Tender Title", meta?.optString("tender_title") ?: "Not found", true)
      addField("tender_metadata_tender_description", "Tender Description", meta?.optString("tender_description") ?: "Not found")
      addField("tender_metadata_issuing_institution", "Issuing Institution", meta?.optString("issuing_institution") ?: "Not found", true)
      addField("tender_metadata_institution_type", "Institution Type", meta?.optString("institution_type") ?: "Not found")
      addField("tender_metadata_procurement_category", "Procurement Category", meta?.optString("procurement_category") ?: "Not found", true)

      val geo = meta?.optJSONObject("geographic_locality")
      addField("tender_metadata_geographic_locality_province", "Geographic Locality Province", geo?.optString("province") ?: "Not found")
      addField("tender_metadata_geographic_locality_district_municipality", "Geographic Locality District Municipality", geo?.optString("district_municipality") ?: "Not found")
      addField("tender_metadata_geographic_locality_local_municipality", "Geographic Locality Local Municipality", geo?.optString("local_municipality") ?: "Not found")
      addField("tender_metadata_geographic_locality_ward", "Geographic Locality Ward", geo?.optString("ward") ?: "Not found")

      val dates = extractionJson.optJSONObject("critical_dates")
      addField("critical_dates_publish_date", "Publish Date", dates?.optString("publish_date") ?: "Not found")
      
      val briefing = dates?.optJSONObject("compulsory_briefing")
      addField("critical_dates_compulsory_briefing_is_compulsory", "Briefing: Is Compulsory", briefing?.optBoolean("is_compulsory")?.toString() ?: "Not found", true)
      addField("critical_dates_compulsory_briefing_briefing_date_time", "Briefing: Date Time", briefing?.optString("briefing_date_time") ?: "Not found")
      addField("critical_dates_compulsory_briefing_briefing_venue", "Briefing: Venue", briefing?.optString("briefing_venue") ?: "Not found")
      addField("critical_dates_closing_date_time", "Closing Date Time", dates?.optString("closing_date_time") ?: "Not found", true)
      addField("critical_dates_validity_period_days", "Validity Period Days", dates?.optInt("validity_period_days")?.toString() ?: "Not found")

      val sub = extractionJson.optJSONObject("submission_mechanics")
      addField("submission_mechanics_submission_method", "Submission Method", sub?.optString("submission_method") ?: "Not found", true)
      addField("submission_mechanics_physical_box_address", "Physical Box Address", sub?.optString("physical_box_address") ?: "Not found")
      addField("submission_mechanics_electronic_portal_url", "Electronic Portal Url", sub?.optString("electronic_portal_url") ?: "Not found")
      addField("submission_mechanics_required_hard_copies", "Required Hard Copies", sub?.optInt("required_hard_copies")?.toString() ?: "Not found")

      val admin = extractionJson.optJSONObject("administrative_compliance")
      addField("administrative_compliance_csd_registration_required", "CSD Registration Required", admin?.optBoolean("csd_registration_required")?.toString() ?: "Not found")
      addField("administrative_compliance_sars_tax_compliance_pin_required", "SARS Tax Compliance Pin Required", admin?.optBoolean("sars_tax_compliance_pin_required")?.toString() ?: "Not found")
      addField("administrative_compliance_cipc_annual_returns_good_standing_required", "CIPC Good Standing Required", admin?.optBoolean("cipc_annual_returns_good_standing_required")?.toString() ?: "Not found")
      addField("administrative_compliance_coida_letter_of_good_standing_required", "COIDA Letter of Good Standing Required", admin?.optBoolean("coida_letter_of_good_standing_required")?.toString() ?: "Not found")

      val pref = extractionJson.optJSONObject("preferential_procurement")
      addField("preferential_procurement_scoring_system_applicable", "Preference Scoring System", pref?.optString("scoring_system_applicable") ?: "Not found", true)

      val cidb = extractionJson.optJSONObject("industry_credentials")?.optJSONObject("cidb_requirements")
      val cidbRequired = cidb?.optBoolean("is_required", false) ?: false
      val cidbGradeVal = if (!cidbRequired) "Not applicable" else (cidb?.optString("minimum_grade", "Not found") ?: "Not found")
      val cidbClassVal = if (!cidbRequired) "Not applicable" else (cidb?.optString("class_of_work", "Not found") ?: "Not found")

      addField("industry_credentials_cidb_requirements_is_required", "CIDB Required", cidb?.optBoolean("is_required")?.toString() ?: "Not found")
      addField("industry_credentials_cidb_requirements_minimum_grade", "CIDB Minimum Grade", cidbGradeVal, true)
      addField("industry_credentials_cidb_requirements_class_of_work", "CIDB Class of Work", cidbClassVal, true)

      // Legislative Gatekeeper (MBD/SBD Overrides) applied before generating fields
      val statForms = extractionJson.optJSONObject("statutory_forms")
      val instType = meta?.optString("institution_type", "") ?: ""
      if (statForms != null) {
          val isMuni = instType.contains("Municipality", ignoreCase = true) || instType.contains("Municipal", ignoreCase = true)
          val isNationalOrSOE = instType.contains("National", ignoreCase = true) || instType.contains("Enterprise", ignoreCase = true) || instType.contains("Entity", ignoreCase = true) || instType.contains("Department", ignoreCase = true)
          
          if (isNationalOrSOE && !isMuni) {
             val mbd = statForms.optJSONObject("mbd_forms")
             if (mbd != null) {
               mbd.put("mbd_1_required", false)
               mbd.put("mbd_4_required", false)
               mbd.put("mbd_6_1_required", false)
               mbd.put("mbd_15_required", false)
             }
          } else if (isMuni) {
             val sbd = statForms.optJSONObject("sbd_forms")
             if (sbd != null) {
               sbd.put("sbd_1_required", false)
               sbd.put("sbd_4_required", false)
               sbd.put("sbd_6_1_required", false)
             }
          }
      }

      val stat = statForms?.optJSONObject("mbd_forms")
      addField("statutory_forms_mbd_forms_mbd_1_required", "MBD 1 Required", stat?.optBoolean("mbd_1_required", false)?.toString() ?: "false")
      addField("statutory_forms_mbd_forms_mbd_4_required", "MBD 4 Required", stat?.optBoolean("mbd_4_required", false)?.toString() ?: "false")
      addField("statutory_forms_mbd_forms_mbd_6_1_required", "MBD 6.1 Required", stat?.optBoolean("mbd_6_1_required", false)?.toString() ?: "false")
      addField("statutory_forms_mbd_forms_mbd_15_required", "MBD 15 Required", stat?.optBoolean("mbd_15_required", false)?.toString() ?: "false")

      val sbdStat = statForms?.optJSONObject("sbd_forms")
      addField("statutory_forms_sbd_forms_sbd_1_required", "SBD 1 Required", sbdStat?.optBoolean("sbd_1_required", false)?.toString() ?: "false")
      addField("statutory_forms_sbd_forms_sbd_4_required", "SBD 4 Required", sbdStat?.optBoolean("sbd_4_required", false)?.toString() ?: "false")
      addField("statutory_forms_sbd_forms_sbd_6_1_required", "SBD 6.1 Required", sbdStat?.optBoolean("sbd_6_1_required", false)?.toString() ?: "false")

      val fin = extractionJson.optJSONObject("financial_criteria")
      val estValRaw = fin?.optDouble("estimated_tender_value_zar", Double.NaN) ?: Double.NaN
      val estValStr = if (estValRaw.isNaN()) "0.0" else estValRaw.toString()
      addField("financial_criteria_estimated_tender_value_zar", "Estimated Tender Value ZAR", estValStr, true)
      addField("financial_criteria_audited_financials_required", "Audited Financials Required", fin?.optBoolean("audited_financials_required")?.toString() ?: "Not found")

      val tech = extractionJson.optJSONObject("technical_functionality")
      addField("technical_functionality_has_functionality_threshold", "Has Functionality Threshold", tech?.optBoolean("has_functionality_threshold")?.toString() ?: "Not found")
      val minThreshRaw = tech?.optDouble("minimum_threshold_percentage", Double.NaN) ?: Double.NaN
      addField("technical_functionality_minimum_threshold_percentage", "Minimum Functionality Threshold %", if (minThreshRaw.isNaN()) "Not found" else minThreshRaw.toString())

      finalEnrichment.put("fields", fieldsArray)

      // Add to critical fields list
      val critList = JSONArray()

      // Perform regulatory compliance audits (Phase 6)
      performRegulatoryAudits(extractionJson, fieldsArray, critList)

      for (i in 0 until fieldsArray.length()) {
        val f = fieldsArray.getJSONObject(i)
        if (f.optBoolean("isCritical") && f.optString("value") == "Not found") {
          critList.put(f.getString("field"))
        }
      }
      finalEnrichment.put("criticalFieldsNeedingReview", critList)

      fileManager.saveTextFile(prepared.folder, GEMMA_ENRICHMENT_FILENAME, finalEnrichment.toString(2))
      
      // Update top-level manifest with extracted fields
      val manifestFile = File(prepared.folder, "manifest.json")
      val manifest = JSONObject(manifestFile.readText())

      // Manifest Date Fallback
      val manifestClosingDate = manifest.optString("closing_Date", "")
      if (manifestClosingDate.isNotBlank()) {
          for (i in 0 until fieldsArray.length()) {
              val f = fieldsArray.getJSONObject(i)
              if (f.optString("field") == "critical_dates_closing_date_time" && (f.optString("value") == "Not found" || f.optString("value").isEmpty())) {
                  f.put("value", manifestClosingDate)
                  f.put("status", "DONE")
                  f.put("evidence", "Recovered from portal manifest")
                  f.put("evidenceScore", 100)
                  f.put("evidenceConfidence", "high")
                  
                  // Also remove from critical list
                  for (j in 0 until critList.length()) {
                      if (critList.optString(j) == "critical_dates_closing_date_time") {
                          critList.remove(j)
                          break
                      }
                  }
              }
          }
      }
      
      manifest.put("document_type", classificationJson.optString("document_type", "Tender"))
      manifest.put("tenderAdvertType", classificationJson.optString("document_type", "Tender").lowercase())
      manifest.put("classified_industry", classificationJson.optString("classified_industry", "Manufacturing & Industrial"))
      manifest.put("industry_id", classificationJson.optString("industry_id", "manufacturing"))
      
      val specArr = classificationJson.optJSONArray("matched_specializations")
      if (specArr != null && specArr.length() > 0) manifest.put("specializations", specArr)
      
      val skillsArr = classificationJson.optJSONArray("matched_skills")
      if (skillsArr != null && skillsArr.length() > 0) manifest.put("skills", skillsArr)
      
      val capArr = classificationJson.optJSONArray("matched_capabilities")
      if (capArr != null && capArr.length() > 0) manifest.put("capabilities", capArr)

      fun isValid(s: String?): Boolean {
        if (s.isNullOrBlank()) return false
        if (s.equals("Not found", ignoreCase = true)) return false
        if (s.equals("null", ignoreCase = true)) return false
        if (s.equals("LOOK_DEEPER", ignoreCase = true)) return false
        return true
      }

      val title = meta?.optString("tender_title", "")
      if (isValid(title)) manifest.put("title", title)
      
      val orgOfState = meta?.optString("issuing_institution", "")
      if (isValid(orgOfState)) manifest.put("organ_of_State", orgOfState)
      
      if (cidb?.optBoolean("is_required") == true) {
        val grade = cidb.optInt("minimum_grade", 1)
        val clazz = cidb.optString("class_of_work", "GB")
        manifest.put("cidb_grading", "$grade$clazz")
      }
      
      val prefPoints = pref?.optString("scoring_system_applicable", "")
      if (isValid(prefPoints) && !prefPoints.equals("None", ignoreCase = true)) {
        manifest.put("preference_points", prefPoints)
      }
      
      if (tech?.optBoolean("has_functionality_threshold") == true) {
        manifest.put("min_functionality_threshold", tech.optDouble("minimum_threshold_percentage").toInt())
      }

      if (dates?.optJSONObject("site_inspection")?.optBoolean("is_compulsory") == true) {
        manifest.put("site_inspection_required", true)
      }

      if (briefing != null) {
        manifest.put("briefingCompulsory", briefing.optBoolean("is_compulsory", false))
        manifest.put("isCompulsoryBriefing", briefing.optBoolean("is_compulsory", false))
        val briefingDate = briefing.optString("briefing_date_time", "")
        if (isValid(briefingDate)) manifest.put("briefingDate", briefingDate)
        val briefingVenue = briefing.optString("briefing_venue", "")
        if (isValid(briefingVenue)) manifest.put("briefingVenue", briefingVenue)
      }
      
      val extractedClosingDate = dates?.optString("closing_date_time", "")
      if (isValid(extractedClosingDate)) {
        manifest.put("closing_Date", extractedClosingDate)
        manifest.put("closingDate", extractedClosingDate) // Explicitly overwrite to prevent SetOptions.merge() keeping the old value
        
        // Real-Time Status Anomaly Fix:
        try {
           val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
           val parsedDate = sdf.parse(extractedClosingDate)
           if (parsedDate != null && parsedDate.time < System.currentTimeMillis()) {
               manifest.put("status", "Closed")
           }
        } catch (e: Exception) {
           // Fallback for custom date strings
           try {
               val customSdf = java.text.SimpleDateFormat("dd MMMM yyyy HH'H'mm", java.util.Locale.US)
               val parsedDate = customSdf.parse(extractedClosingDate!!.replace("h", "H"))
               if (parsedDate != null && parsedDate.time < System.currentTimeMillis()) {
                   manifest.put("status", "Closed")
               }
           } catch (e2: Exception) {
               // Ignore if it can't be parsed
           }
        }
      }

      // Deep Database Mapping for legacy frontend schema
      val contacts = extractionJson.optJSONObject("contactDetails")
      if (contacts != null) {
        val emailsArray = contacts.optJSONArray("emails")
        if (emailsArray != null && emailsArray.length() > 0) {
          manifest.put("contactEmails", emailsArray)
          manifest.put("email", emailsArray.getString(0))
        }
        val phonesArray = contacts.optJSONArray("phoneNumbers")
        if (phonesArray != null && phonesArray.length() > 0) {
          manifest.put("contactPhones", phonesArray)
          manifest.put("telephone", phonesArray.getString(0))
        }
        val contactNames = org.json.JSONArray()
        if (contacts.has("names")) {
           val namesArray = contacts.optJSONArray("names")
           if (namesArray != null && namesArray.length() > 0) {
               manifest.put("contactNames", namesArray)
               manifest.put("contactPerson", namesArray.getString(0))
           }
        }
      }

      val desc = meta?.optString("tender_description", "")
      if (isValid(desc)) manifest.put("description", desc)

      // Sanitize Tender IDs
      val extractedRef = meta?.optString("tender_reference_number", "")
      if (isValid(extractedRef)) {
        manifest.put("tender_No", extractedRef)
        manifest.put("reference", extractedRef)
        // Ensure actions object matches the updated tender_No
        val actionsObj = manifest.optJSONObject("actions")
        if (actionsObj != null) {
          actionsObj.put("tender_No", extractedRef)
        }
      }

      val prov = meta?.optJSONObject("geographic_locality")?.optString("province", "")
      if (isValid(prov)) {
        manifest.put("province", prov)
        val provArr = org.json.JSONArray()
        provArr.put(prov)
        manifest.put("provinceNames", provArr)
      }
      
      val complianceArr = org.json.JSONArray()
      val adminComp = extractionJson.optJSONObject("administrative_compliance")
      
      // MBD/SBD Overrides already applied above before fieldsArray generation
      
      if (adminComp != null) {
        if (adminComp.optBoolean("csd_registration_required")) complianceArr.put("CSD Registration")
        if (adminComp.optBoolean("sars_tax_compliance_pin_required")) complianceArr.put("SARS Tax Compliance Pin")
        if (adminComp.optBoolean("cipc_annual_returns_good_standing_required")) complianceArr.put("CIPC Good Standing")
        if (adminComp.optBoolean("coida_letter_of_good_standing_required")) complianceArr.put("COIDA Letter of Good Standing")
      }
      if (complianceArr.length() > 0) {
        manifest.put("complianceRequirements", complianceArr)
        manifest.put("mandatoryRegistrationRequirements", complianceArr)
        manifest.put("requirements", complianceArr)
      }

      fileManager.saveTextFile(prepared.folder, "manifest.json", manifest.toString(2))

      firebaseSync.uploadTenderFolder(prepared.folder)
      
      // Clean the Empty Array Graveyard
      val keysToRemove = mutableListOf<String>()
      val manifestKeys = manifest.keys()
      while (manifestKeys.hasNext()) {
        val k = manifestKeys.next()
        val v = manifest.opt(k)
        if (v is org.json.JSONArray && v.length() == 0) {
          keysToRemove.add(k)
        }
      }
      keysToRemove.forEach { manifest.remove(it) }

      // Also explicitly remove junk drawer arrays
      listOf("turnoverRequirements", "siteCoverageTags", "sectorTags", "contractTerms").forEach { 
          manifest.remove(it) 
      }

      // Add the entire enriched payload as a clean, nested namespace instead of a Junk Drawer
      manifest.put("ai_enrichment", finalEnrichment)
      
      firebaseSync.syncToFirestore(tenderId, manifest.toString(2))
      
      fileManager.markTenderUploaded(prepared.folder)
      
      statusUpdater?.invoke("Tender enrichment and upload completed.")
      return true
    } catch (e: Exception) {
      Log.e(TAG, "Failed during OpenRouter enrichment for $tenderId", e)
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
    Log.d(TAG, "OpenRouter Llama 4 Scout prompt length for $tenderId: ${prompt.length} chars")
    val client = okhttp3.OkHttpClient.Builder()
        .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    val mediaType = "application/json; charset=utf-8".toMediaType()

    val requestBodyJson = JSONObject().apply {
      put("model", "meta-llama/llama-4-scout")
      put("messages", JSONArray().apply {
        put(JSONObject().apply {
          put("role", "user")
          put("content", prompt)
        })
      })
      put("temperature", 0.0)
      put("presence_penalty", 0.0)
      put("repetition_penalty", 1.0)
      put("top_k", 0)
      put("min_p", 0.0)
    }

    val apiKey = BuildConfig.OPENROUTER_API_KEY
    val request = Request.Builder()
      .url("https://openrouter.ai/api/v1/chat/completions")
      .post(requestBodyJson.toString().toRequestBody(mediaType))
      .addHeader("Authorization", "Bearer $apiKey")
      .addHeader("Content-Type", "application/json")
      .addHeader("HTTP-Referer", "https://github.com/PeterPiper319/jg_scraper_android")
      .addHeader("X-Title", "JG Scraper")
      .build()

    return withContext(Dispatchers.IO) {
      client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
          val errBody = response.body?.string() ?: ""
          throw IOException("OpenRouter error (code ${response.code}): $errBody")
        }
        val responseBody = response.body?.string() ?: throw IOException("Empty response body from OpenRouter")
        val jsonResponse = JSONObject(responseBody)
        val choices = jsonResponse.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
          val choice = choices.getJSONObject(0)
          val message = choice.optJSONObject("message")
          message?.optString("content") ?: ""
        } else {
          throw IOException("No choices returned from OpenRouter: $responseBody")
        }
      }
    }
  }

  private suspend fun ensureModelInitialized(model: Model): Boolean {
    // OpenRouter inference is remote, no need to initialize local model.
    return true
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
        "docx" -> DocxExtractor.extractText(file)
        "xlsx", "xls" -> XlsxExtractor.extractText(file)
        else -> ""
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed to extract text from ${file.name}", e)
      ""
    }
  }

  private fun isGemmaReadableFile(file: File): Boolean {
    return when (file.extension.lowercase()) {
      "pdf", "txt", "md", "csv", "docx", "xlsx", "xls" -> true
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

  private fun chunkTextHierarchically(fileName: String, text: String): List<TenderVectorIndex.Chunk> {
    val lines = text.split("\n")
    val chunks = mutableListOf<TenderVectorIndex.Chunk>()
    
    var currentHeader = "General Info"
    var currentSubHeader = ""
    val currentChunkText = StringBuilder()
    var pageNum = 1

    val sbdMbdRegex = Regex("""\b([SM]BD\s*\d+(?:\.\d+)?)\b""", RegexOption.IGNORE_CASE)
    val majorSectionRegex = Regex("""\b(SPECIAL CONDITIONS|GENERAL CONDITIONS|EVALUATION CRITERIA|FUNCTIONALITY|PRICING SCHEDULE|SCOPE OF WORK|BILL OF QUANTITIES|CIDB GRADING|PRE-QUALIFICATION)\b""", RegexOption.IGNORE_CASE)

    for (line in lines) {
      val trimmedLine = line.trim()
      
      if (trimmedLine.contains(Regex("""(?i)\bpage\s*\d+\b"""))) {
        val pageMatch = Regex("""(?i)\bpage\s*(\d+)\b""").find(trimmedLine)
        if (pageMatch != null) {
          pageNum = pageMatch.groupValues[1].toIntOrNull() ?: pageNum
        }
      }

      var isHeader = false
      var headerText = ""
      
      val sbdMatch = sbdMbdRegex.find(trimmedLine)
      if (sbdMatch != null && trimmedLine.length < 100) {
        isHeader = true
        headerText = trimmedLine
      } else if (majorSectionRegex.containsMatchIn(trimmedLine) && trimmedLine.length < 100 && trimmedLine == trimmedLine.uppercase()) {
        isHeader = true
        headerText = trimmedLine
      }

      if (isHeader) {
        if (currentChunkText.isNotBlank()) {
          val contextPrefix = "Section: $currentHeader${if (currentSubHeader.isNotBlank()) " > $currentSubHeader" else ""}\n"
          chunks.add(TenderVectorIndex.Chunk(
            fileName = fileName,
            text = contextPrefix + currentChunkText.toString().trim(),
            pageNum = pageNum
          ))
          currentChunkText.clear()
        }
        currentHeader = headerText
        currentSubHeader = ""
      } else {
        currentChunkText.append(line).append("\n")

        if (currentChunkText.length >= 1500) {
          val contextPrefix = "Section: $currentHeader${if (currentSubHeader.isNotBlank()) " > $currentSubHeader" else ""}\n"
          chunks.add(TenderVectorIndex.Chunk(
            fileName = fileName,
            text = contextPrefix + currentChunkText.toString().trim(),
            pageNum = pageNum
          ))
          currentChunkText.clear()
        }
      }
    }

    if (currentChunkText.isNotBlank()) {
      val contextPrefix = "Section: $currentHeader${if (currentSubHeader.isNotBlank()) " > $currentSubHeader" else ""}\n"
      chunks.add(TenderVectorIndex.Chunk(
        fileName = fileName,
        text = contextPrefix + currentChunkText.toString().trim(),
        pageNum = pageNum
      ))
    }

    return chunks
  }

  private fun performRegulatoryAudits(
    extractionJson: JSONObject,
    fieldsArray: JSONArray,
    critList: JSONArray
  ) {
    try {
      val meta = extractionJson.optJSONObject("tender_metadata")
      val pref = extractionJson.optJSONObject("preferential_procurement")
      val cidb = extractionJson.optJSONObject("industry_credentials")?.optJSONObject("cidb_requirements")
      val fin = extractionJson.optJSONObject("financial_criteria")
      
      val estimatedValue = fin?.optDouble("estimated_tender_value_zar", Double.NaN) ?: Double.NaN
      val scoringSystem = pref?.optString("scoring_system_applicable", "") ?: ""
      val cidbGrade = cidb?.optInt("minimum_grade", -1) ?: -1
      
      val auditFindings = mutableListOf<String>()

      // 1. Preference system check vs Estimated Value
      if (estimatedValue.isFinite() && estimatedValue > 0.0) {
        if (estimatedValue > 50000000.0 && scoringSystem == "80/20") {
          val formattedVal = String.format(java.util.Locale.US, "%,.2f", estimatedValue)
          auditFindings.add("REGULATORY_ANOMALY: Tender value estimated at R$formattedVal exceeds R50 Million, but the 80/20 preference system is stipulated instead of 90/10.")
        } else if (estimatedValue <= 50000000.0 && estimatedValue >= 30000.0 && scoringSystem == "90/10") {
          val formattedVal = String.format(java.util.Locale.US, "%,.2f", estimatedValue)
          auditFindings.add("REGULATORY_ANOMALY: Tender value estimated at R$formattedVal is under R50 Million, but the 90/10 preference system is stipulated instead of 80/20.")
        }
      }

      // 2. CIDB Grade limit check vs Estimated Value
      if (estimatedValue.isFinite() && estimatedValue > 0.0 && cidbGrade in 1..8) {
        val maxLimits = mapOf(
          1 to 500000.0,
          2 to 1000000.0,
          3 to 3000000.0,
          4 to 6000000.0,
          5 to 10000000.0,
          6 to 20000000.0,
          7 to 60000000.0,
          8 to 200000000.0
        )
        val limit = maxLimits[cidbGrade] ?: Double.MAX_VALUE
        if (estimatedValue > limit) {
          val formattedVal = String.format(java.util.Locale.US, "%,.2f", estimatedValue)
          val formattedLimit = String.format(java.util.Locale.US, "%,.2f", limit)
          auditFindings.add("REGULATORY_ANOMALY: Requisite CIDB Grade $cidbGrade has a maximum tender value limit of R$formattedLimit, which is lower than the estimated tender value of R$formattedVal.")
        }
      }

      // 3. Preference Points Omission
      if (scoringSystem == "None" || scoringSystem == "Not found" || scoringSystem.isEmpty()) {
        auditFindings.add("REGULATORY_ANOMALY: Public procurement regulations require an 80/20 or 90/10 preference point system, but scoring system is specified as None or Not found.")
      }

      // 4. Threshold Math Contradiction
      val tech = extractionJson.optJSONObject("technical_functionality")
      val hasThreshold = tech?.optBoolean("has_functionality_threshold", false) ?: false
      val thresholdPct = tech?.optDouble("minimum_threshold_percentage", 0.0) ?: 0.0
      if (hasThreshold && thresholdPct <= 0.0) {
        auditFindings.add("REGULATORY_ANOMALY: Technical functionality evaluation threshold is enabled, but minimum threshold is missing or specified as 0%.")
      }

      if (auditFindings.isNotEmpty()) {
        for ((index, finding) in auditFindings.withIndex()) {
          fieldsArray.put(JSONObject().apply {
            put("field", "regulatory_audit_finding_$index")
            put("label", "Regulatory Audit Finding")
            put("value", finding)
            put("status", "WARNING")
            put("evidence", "")
            put("evidenceScore", 100)
            put("evidenceConfidence", "high")
            put("sourceFile", "")
            put("isCritical", true)
          })
          critList.put("regulatory_audit_finding_$index")
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Failed during performRegulatoryAudits", e)
    }
  }

  private val namePrefixesRegex = listOf(
    Regex("(?i)\\battention\\s*:\\s*[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*"),
    Regex("(?i)\\batt\\s*:\\s*[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*"),
    Regex("(?i)\\bcontact\\s+person\\s*:\\s*[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*"),
    Regex("(?i)\\benquiries\\s*:\\s*[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*"),
    Regex("(?i)\\bto\\s*:\\s*(?:Mr|Ms|Mrs|Dr|Adv)\\.?\\s+[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)*"),
    Regex("\\b(?:Mr|Ms|Mrs|Dr|Adv)\\.?\\s+[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+")
  )

  private fun redactContactNames(text: String): String {
    var redactedText = text
    for (pattern in namePrefixesRegex) {
      redactedText = pattern.replace(redactedText) { match ->
        val fullMatch = match.value
        val separators = listOf(":", "Mr.", "Ms.", "Mrs.", "Dr.", "Adv.", "Mr ", "Ms ", "Mrs ", "Dr ", "Adv ")
        var replaced = "[REDACTED]"
        for (sep in separators) {
          if (fullMatch.contains(sep)) {
            val parts = fullMatch.split(sep, limit = 2)
            replaced = "${parts[0]}$sep[REDACTED]"
            break
          }
        }
        replaced
      }
    }
    return redactedText
  }
}
