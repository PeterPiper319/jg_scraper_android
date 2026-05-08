package com.google.ai.edge.gallery.ui.scraper

import android.content.Context
import android.util.Log
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.ai.edge.gallery.common.ThermalGuard
import com.google.ai.edge.gallery.common.processLlmResponse
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TenderFileManager
import com.google.ai.edge.gallery.data.TenderScraper
import com.google.firebase.storage.StorageException
import com.google.ai.edge.gallery.infrastructure.FirebaseSync
import com.google.ai.edge.gallery.runtime.runtimeHelper
import com.google.ai.edge.gallery.worker.TENDER_SCRAPER_PROGRESS_COMPLETED
import com.google.ai.edge.gallery.worker.TENDER_SCRAPER_PROGRESS_STATUS
import com.google.ai.edge.gallery.worker.TENDER_SCRAPER_PROGRESS_TOTAL
import com.google.ai.edge.gallery.worker.TENDER_SCRAPER_WORK_NAME
import com.google.ai.edge.gallery.worker.TenderScraperWorker
import com.google.ai.edge.gallery.worker.getScraperConstraints
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject

data class TenderFolder(
    val tenderId: String,
    val files: List<File>
)

data class ScraperUiState(
    val isScraping: Boolean = false,
    val scrapeStatus: String = "",
    val hasResumableSession: Boolean = false,
    val isBackgroundScraperRunning: Boolean = false,
    val canResumeBackgroundScraper: Boolean = false,
    val backgroundScraperStatus: String = "",
    val firebaseTenderIds: List<String> = emptyList(),
    val firebaseListStatus: String = "",
    val isCleaningExpiredFirebaseTenders: Boolean = false,
    val firebaseCleanupStatus: String = "",
    val firebaseDownloadStatusByTender: Map<String, String> = emptyMap(),
    val downloadedTenders: List<TenderFolder> = emptyList(),
    val gemmaReadCheckStatusByTender: Map<String, String> = emptyMap(),
    val gemmaReadCheckResultByTender: Map<String, String> = emptyMap(),
    val gemmaEnrichmentStatusByTender: Map<String, String> = emptyMap(),
    val firebaseUploadStatusByTender: Map<String, String> = emptyMap(),
    val bulkEnrichmentStatus: String = "",
)

private data class ScrapeAutomationSession(
    val sessionId: String,
    val targetLimit: Int,
    val scrapedTenderIds: MutableList<String> = mutableListOf(),
    val enrichedTenderIds: MutableList<String> = mutableListOf(),
    val uploadedTenderIds: MutableList<String> = mutableListOf(),
    var stage: String = "scraping",
    var currentTenderId: String? = null,
    var status: String = "",
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("sessionId", sessionId)
            put("targetLimit", targetLimit)
            put("scrapedTenderIds", JSONArray(scrapedTenderIds))
            put("enrichedTenderIds", JSONArray(enrichedTenderIds))
            put("uploadedTenderIds", JSONArray(uploadedTenderIds))
            put("stage", stage)
            put("currentTenderId", currentTenderId ?: JSONObject.NULL)
            put("status", status)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ScrapeAutomationSession {
            return ScrapeAutomationSession(
                sessionId = json.getString("sessionId"),
                targetLimit = json.optInt("targetLimit", 100),
                scrapedTenderIds = jsonArrayToMutableList(json.optJSONArray("scrapedTenderIds")),
                enrichedTenderIds = jsonArrayToMutableList(json.optJSONArray("enrichedTenderIds")),
                uploadedTenderIds = jsonArrayToMutableList(json.optJSONArray("uploadedTenderIds")),
                stage = json.optString("stage", "scraping"),
                currentTenderId = json.optString("currentTenderId", "").ifBlank { null },
                status = json.optString("status", ""),
            )
        }

        private fun jsonArrayToMutableList(array: JSONArray?): MutableList<String> {
            return mutableListOf<String>().apply {
                if (array == null) return@apply
                for (index in 0 until array.length()) {
                    add(array.optString(index))
                }
            }
        }
    }
}

@HiltViewModel
class TenderScraperViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val automationProcessor: com.google.ai.edge.gallery.worker.TenderAutomationProcessor
) : ViewModel() {

    companion object {
        private const val TAG = "AGTenderScraperVM"
        private const val GEMMA_READ_CHECK_FILENAME = "gemma-read-check.txt"
        private const val GEMMA_ENRICHMENT_FILENAME = "gemma-manifest-enrichment.json"
        private const val SESSION_STAGE_SCRAPING = "scraping"
        private const val SESSION_STAGE_ENRICHING = "enriching"
        private const val SESSION_STAGE_UPLOADING = "uploading"
        private const val SESSION_STAGE_STOPPED = "stopped"
        private const val SESSION_STAGE_FAILED = "failed"
        private const val MAX_FILE_TEXT_CHARS = 24000
        private const val MAX_READ_CHECK_PROMPT_CHARS = 24000
        private const val MAX_ENRICHMENT_PREP_PROMPT_CHARS = 24000
        private const val MAX_CONSOLIDATED_DOCUMENT_CHARS = 24000
    }

    private val fileManager = TenderFileManager(context)
    private val scraper = TenderScraper(context, fileManager)
    private val firebaseSync = FirebaseSync(context)
    private val workManager = WorkManager.getInstance(context)
    private var stopRequested = false
    private var activeAutomationSession: ScrapeAutomationSession? = null
    private var backgroundWorkerObserver: Observer<List<WorkInfo>>? = null

    private val _uiState = MutableStateFlow(ScraperUiState())
    val uiState: StateFlow<ScraperUiState> = _uiState

    init {
        loadDownloadedTenders()
        restoreAutomationSession()
        loadFirebaseTenders()
        observeBackgroundScraperWork()
    }

    fun enqueueBackgroundScraper() {
        _uiState.value =
            _uiState.value.copy(
                backgroundScraperStatus = "Background scraper scheduled. Waiting for Wi-Fi and charging.",
                canResumeBackgroundScraper = false,
            )
        enqueueBackgroundScraper(existingWorkPolicy = ExistingWorkPolicy.KEEP)
    }

    fun cancelBackgroundScraper() {
        workManager.cancelUniqueWork(TENDER_SCRAPER_WORK_NAME)
        _uiState.value =
            _uiState.value.copy(
                backgroundScraperStatus = "Cancelling background scraper...",
                canResumeBackgroundScraper = true,
            )
    }

    fun resumeBackgroundScraper() {
        _uiState.value =
            _uiState.value.copy(
                backgroundScraperStatus = "Resuming background scraper from pending tenders...",
                canResumeBackgroundScraper = false,
            )
        enqueueBackgroundScraper(existingWorkPolicy = ExistingWorkPolicy.REPLACE)
    }

    private fun enqueueBackgroundScraper(existingWorkPolicy: ExistingWorkPolicy) {
        val request =
            OneTimeWorkRequestBuilder<TenderScraperWorker>()
                .setConstraints(getScraperConstraints())
                .build()

        workManager.enqueueUniqueWork(TENDER_SCRAPER_WORK_NAME, existingWorkPolicy, request)
    }

    fun startScraping(limit: Int) {
        Log.d("ScraperDebug", "Button clicked, starting scrape for $limit tenders...")
        viewModelScope.launch(Dispatchers.IO) {
            Log.d("ScraperDebug", "Setting isScraping to true")
            resetProcessingStatus(isScraping = true)
            try {
                updateScrapeStatus("Starting scrape for $limit tenders...")
                scraper.fetchLatestTenders(limit, ::updateScrapeStatus)
            } finally {
                Log.d("ScraperDebug", "Setting isScraping to false")
                delay(2000) // Keep progress visible for 2 seconds
                _uiState.value = _uiState.value.copy(isScraping = false)
                loadDownloadedTenders()
            }
        }
    }

    fun scrapeEnrichAndUploadLatest(model: Model, limit: Int) {
        Log.d(TAG, "Starting automated scrape/enrich/upload for $limit tenders")
        viewModelScope.launch(Dispatchers.IO) launch@ {
            val session = ScrapeAutomationSession(
                sessionId = System.currentTimeMillis().toString(),
                targetLimit = limit, // -1 means continuous
                stage = SESSION_STAGE_SCRAPING,
                status = "Starting automated scrape...",
            )
            runAutomationSession(model, session, isResume = false)
        }
    }

    fun resumeScrapeEnrichAndUpload(model: Model) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = loadAutomationSession() ?: return@launch
            reconcileSessionWithStoredTenders(session)
            runAutomationSession(model, session, isResume = true)
        }
    }

    fun requestStopScraper() {
        stopRequested = true
        activeAutomationSession?.let { session ->
            session.stage = SESSION_STAGE_STOPPED
            session.status = "Stopping after the current step finishes..."
            persistAutomationSession(session)
            updateScrapeStatus(session.status)
            _uiState.value = _uiState.value.copy(hasResumableSession = true)
        }
    }

    fun nukeAllTenders() {
        viewModelScope.launch(Dispatchers.IO) {
            updateScrapeStatus("Deleting all local tenders...")
            try {
                val tendersDir = File(context.getExternalFilesDir(null), "tenders")
                if (tendersDir.exists()) {
                    tendersDir.deleteRecursively()
                }

                updateScrapeStatus("Deleting all Firebase tenders...")
                val success = firebaseSync.deleteAllTenders()
                
                if (success) {
                    updateScrapeStatus("Successfully deleted all local and Firebase tenders.")
                } else {
                    updateScrapeStatus("Local tenders deleted, but failed to delete Firebase tenders.")
                }
                
                loadDownloadedTenders()
                loadFirebaseTenders()
            } catch (e: Exception) {
                Log.e(TAG, "Error nuking tenders", e)
                updateScrapeStatus("Error deleting tenders: ${e.message}")
            }
        }
    }

    private suspend fun runAutomationSession(
        model: Model,
        session: ScrapeAutomationSession,
        isResume: Boolean,
    ) {
        normalizeAutomationSession(session)
        stopRequested = false
        activeAutomationSession = session
        resetProcessingStatus(isScraping = true)
        _uiState.value = _uiState.value.copy(hasResumableSession = false)

        try {
            if (isResume) {
                session.status = "Resuming automation from ${session.stage}..."
            }
            persistAutomationSession(session)
            updateScrapeStatus(session.status)

            while (!stopRequested) {
                val currentTotal = session.scrapedTenderIds.size
                if (session.targetLimit != -1 && currentTotal >= session.targetLimit) {
                    break
                }

                val batchSize = 1
                val remainingToTarget = if (session.targetLimit == -1) batchSize else session.targetLimit - currentTotal
                val nextBatchLimit = min(batchSize, remainingToTarget)

                if (nextBatchLimit <= 0) break

                session.stage = SESSION_STAGE_SCRAPING
                session.status = "Scraping next batch of up to $nextBatchLimit tender(s)..."
                persistAutomationSession(session)
                updateScrapeStatus(session.status)

                val newBatchIds = mutableListOf<String>()
                val scrapeResult = scraper.fetchLatestTenders(
                    limit = nextBatchLimit,
                    onStatus = { status ->
                        session.status = status
                        updateScrapeStatus(status)
                    },
                    shouldStop = { stopRequested },
                    onNewTenderSaved = { tenderId ->
                        val normalizedTenderId = normalizeTenderId(tenderId)
                        if (!session.scrapedTenderIds.contains(normalizedTenderId)) {
                            session.scrapedTenderIds.add(normalizedTenderId)
                            newBatchIds.add(normalizedTenderId)
                        }
                        persistAutomationSession(session)
                    },
                    sessionId = session.sessionId,
                )

                loadDownloadedTenders()

                if (newBatchIds.isEmpty() && scrapeResult.exhausted) {
                    if (session.targetLimit == -1) {
                        session.status = "No new tenders found. Waiting 30s before next poll..."
                        updateScrapeStatus(session.status)
                        delay(30000)
                        continue
                    } else {
                        session.status = "No more new tenders found."
                        updateScrapeStatus(session.status)
                        break
                    }
                }

                // Process the current batch immediately
                for ((index, tenderId) in newBatchIds.withIndex()) {
                    val position = session.scrapedTenderIds.indexOf(tenderId) + 1
                    val totalLabel = if (session.targetLimit == -1) "continuous" else session.targetLimit.toString()
                    session.currentTenderId = tenderId

                    if (stopRequested) {
                        stopAutomationSession(session, "Automation stopped. Resume will continue from $tenderId.")
                        return
                    }

                    if (!session.enrichedTenderIds.contains(tenderId) || !session.uploadedTenderIds.contains(tenderId)) {
                        session.stage = SESSION_STAGE_ENRICHING
                        session.status = "Automation $position/$totalLabel: Processing $tenderId"
                        updateScrapeStatus(session.status)
                        persistAutomationSession(session)

                        try {
                            val success = enrichManifestWithGemmaInternal(model, tenderId)
                            if (success) {
                                session.enrichedTenderIds.add(tenderId)
                                session.uploadedTenderIds.add(tenderId)
                                persistAutomationSession(session)
                            } else {
                                failAutomationSession(session, "Automation paused on enrichment failure for $tenderId.")
                                return
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Unexpected error processing tender $tenderId", e)
                            failAutomationSession(session, "Automation paused on error: ${e.message}")
                            return
                        }
                    }
                }

                if (scrapeResult.stopped || stopRequested) {
                    stopAutomationSession(session, "Automation stopped.")
                    return
                }

                if (scrapeResult.failureMessage != null) {
                    failAutomationSession(session, "Automation paused after error: ${scrapeResult.failureMessage}")
                    return
                }
            }

            session.currentTenderId = null
            session.status = "Automation completed for ${session.uploadedTenderIds.size} tender(s)."
            updateScrapeStatus(session.status)
            clearAutomationSession()
        } finally {
            delay(2000)
            activeAutomationSession = null
            _uiState.value = _uiState.value.copy(isScraping = false)
            loadDownloadedTenders()
        }
    }

    fun loadDownloadedTenders() {
        val tendersDir = File(context.getExternalFilesDir(null), "tenders")
        val tenderFolders = tendersDir.listFiles { file -> file.isDirectory }?.map { dir ->
            TenderFolder(
                tenderId = dir.name,
                files = dir.listFiles()?.toList() ?: emptyList()
            )
        } ?: emptyList()
        _uiState.value = _uiState.value.copy(downloadedTenders = tenderFolders)
    }

    fun loadFirebaseTenders() {
        viewModelScope.launch(Dispatchers.IO) {
            updateFirebaseListStatus("Loading tender folders from Firebase...")
            try {
                val tenders = firebaseSync.listTenderFolders().map { it.tenderId }
                _uiState.value = _uiState.value.copy(firebaseTenderIds = tenders)
                updateFirebaseListStatus("Loaded ${tenders.size} tender folder(s) from Firebase.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to list Firebase tenders", e)
                updateFirebaseListStatus("Failed to load Firebase tenders: ${e.message ?: "unknown error"}")
            }
        }
    }

    fun removeExpiredFirebaseTenders() {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value =
                _uiState.value.copy(
                    isCleaningExpiredFirebaseTenders = true,
                    firebaseCleanupStatus = "Checking Firebase tenders for expired closing dates...",
                )

            try {
                val result = firebaseSync.removeExpiredTenderFolders()
                val summary = buildFirebaseCleanupSummary(result)
                _uiState.value =
                    _uiState.value.copy(
                        isCleaningExpiredFirebaseTenders = false,
                        firebaseCleanupStatus = summary,
                    )
                loadFirebaseTenders()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove expired Firebase tenders", e)
                _uiState.value =
                    _uiState.value.copy(
                        isCleaningExpiredFirebaseTenders = false,
                        firebaseCleanupStatus =
                            "Failed to remove expired Firebase tenders: ${e.message ?: "unknown error"}",
                    )
            }
        }
    }

    fun downloadTenderFromFirebase(tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            downloadTenderFromFirebaseInternal(tenderId)
        }
    }

    fun enrichFirebaseTender(model: Model, tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val downloaded = downloadTenderFromFirebaseInternal(tenderId)
            if (!downloaded) {
                return@launch
            }

            val enriched = enrichManifestWithGemmaInternal(model, tenderId)
            if (!enriched) {
                return@launch
            }

            uploadTenderToFirebaseInternal(tenderId)
        }
    }

    fun enrichAllFirebaseTenders(model: Model) {
        viewModelScope.launch(Dispatchers.IO) {
            val tenderIds = _uiState.value.firebaseTenderIds
            if (tenderIds.isEmpty()) {
                _uiState.value = _uiState.value.copy(bulkEnrichmentStatus = "No Firebase tenders loaded. Refresh and try again.")
                return@launch
            }

            stopRequested = false
            resetProcessingStatus(isScraping = true)
            _uiState.value = _uiState.value.copy(
                bulkEnrichmentStatus = "Starting bulk enrichment for ${tenderIds.size} tender(s)...",
            )

            var succeeded = 0
            var failed = 0
            var lastError = ""

            try {
                for ((index, tenderId) in tenderIds.withIndex()) {
                    if (stopRequested) {
                        _uiState.value = _uiState.value.copy(
                            bulkEnrichmentStatus = "Bulk enrichment stopped after $index/${tenderIds.size} tender(s). Succeeded: $succeeded, Failed: $failed.${if (lastError.isNotBlank()) " Last error: $lastError" else ""}",
                        )
                        break
                    }

                    val progress = "${index + 1}/${tenderIds.size}"
                    _uiState.value = _uiState.value.copy(
                        bulkEnrichmentStatus = "[$progress] Downloading $tenderId...",
                    )

                    val downloaded = downloadTenderFromFirebaseInternal(tenderId)
                    if (!downloaded) {
                        Log.w(TAG, "Bulk enrich: skipping $tenderId — download failed")
                        failed++
                        lastError = "Download failed for $tenderId"
                        continue
                    }

                    if (stopRequested) break

                    _uiState.value = _uiState.value.copy(
                        bulkEnrichmentStatus = "[$progress] Enriching $tenderId...",
                    )

                    val enriched = enrichManifestWithGemmaInternal(model, tenderId)
                    if (!enriched) {
                        Log.w(TAG, "Bulk enrich: skipping upload for $tenderId — enrichment failed")
                        failed++
                        lastError = "Enrichment failed for $tenderId"
                        continue
                    }

                    if (stopRequested) break

                    _uiState.value = _uiState.value.copy(
                        bulkEnrichmentStatus = "[$progress] Uploading $tenderId...",
                    )

                    val uploaded = uploadTenderToFirebaseInternal(tenderId)
                    if (uploaded) {
                        succeeded++
                    } else {
                        failed++
                        lastError = "Upload failed for $tenderId"
                    }

                    // Cool down delay between tenders
                    delay(3000)
                }

                if (!stopRequested) {
                    _uiState.value = _uiState.value.copy(
                        bulkEnrichmentStatus = "Bulk enrichment complete. Succeeded: $succeeded, Failed: $failed.${if (lastError.isNotBlank()) " Last error: $lastError" else ""}",
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Bulk enrichment crashed", e)
                _uiState.value = _uiState.value.copy(
                    bulkEnrichmentStatus = "Bulk enrichment crashed: ${e.message ?: "unknown error"}. Succeeded: $succeeded, Failed: $failed.",
                )
            } finally {
                _uiState.value = _uiState.value.copy(isScraping = false)
            }
        }
    }

    fun getManifestContent(tenderId: String): String {
        val folder = fileManager.getTenderFolder(tenderId)
        val manifestFile = File(folder, "manifest.json")
        return try {
            manifestFile.readText()
        } catch (e: Exception) {
            "Error reading manifest: ${e.message}"
        }
    }

    fun getTenderFiles(tenderId: String): List<File> {
        val folder = fileManager.getTenderFolder(tenderId)
        return folder.listFiles()?.toList() ?: emptyList()
    }

    fun getManifestFile(tenderId: String): File {
        val folder = fileManager.getTenderFolder(tenderId)
        return File(folder, "manifest.json")
    }

    fun getTenderTitle(tenderId: String): String {
        val manifestFile = getManifestFile(tenderId)
        return try {
            val manifest = JSONObject(manifestFile.readText())
            manifest.optString("title").ifBlank { tenderId }
        } catch (e: Exception) {
            tenderId
        }
    }

    fun getGemmaReadCheckContent(tenderId: String): String {
        val folder = fileManager.getTenderFolder(tenderId)
        val resultFile = File(folder, GEMMA_READ_CHECK_FILENAME)
        return try {
            resultFile.readText()
        } catch (e: Exception) {
            uiState.value.gemmaReadCheckResultByTender[tenderId]
                ?: "No Gemma read check has been run for this tender yet."
        }
    }

    fun runGemmaReadCheck(model: Model, tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val prepared =
                prepareTenderDocuments(
                    model = model,
                    tenderId = tenderId,
                    statusUpdater = ::updateGemmaStatus,
                    resultUpdater = ::updateGemmaResult,
                    maxPromptChars = MAX_READ_CHECK_PROMPT_CHARS,
                )
                ?: return@launch

            runGemmaInference(
                model = model,
                tenderId = tenderId,
                prompt = buildGemmaReadCheckPrompt(tenderId = tenderId, documentBundle = prepared.documentBundle),
                onPartial = { response -> updateGemmaResult(tenderId, response) },
                onDone = { finalResponse ->
                    fileManager.saveTextFile(prepared.folder, GEMMA_READ_CHECK_FILENAME, finalResponse)
                    updateGemmaResult(tenderId, finalResponse)
                    updateGemmaStatus(tenderId, "Gemma read check completed.")
                },
                onStopped = { updateGemmaStatus(tenderId, "Gemma read check stopped.") },
                onError = { error ->
                    Log.e(TAG, "Gemma read check failed for $tenderId: $error")
                    updateGemmaStatus(tenderId, error)
                    updateGemmaResult(tenderId, "Gemma read check failed: $error")
                },
                statusUpdater = ::updateGemmaStatus,
                readableFileCount = prepared.readableFiles.size,
            )
        }
    }

    fun uploadTenderToFirebase(tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            uploadTenderToFirebaseInternal(tenderId)
        }
    }

    fun enrichManifestWithGemma(model: Model, tenderId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            enrichManifestWithGemmaInternal(model, tenderId)
        }
    }

    private suspend fun enrichManifestWithGemmaInternal(model: Model, tenderId: String): Boolean {
        updateGemmaEnrichmentStatus(tenderId, "Running full enrichment pipeline...")
        val success = automationProcessor.enrichAndUploadTender(model, tenderId) { status ->
            updateGemmaEnrichmentStatus(tenderId, status)
        }
        if (success) {
            updateGemmaEnrichmentStatus(tenderId, "Gemma manifest enrichment completed.")
        } else {
            updateGemmaEnrichmentStatus(tenderId, "Gemma enrichment failed or was skipped.")
        }
        return success
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

        val anchorRegex = Regex("\\b(cidb|grade|gb|ce|evaluation|functionality|weighting|80/20|90/10|briefing|compulsory)\\b", RegexOption.IGNORE_CASE)
        val chunkSize = 1500

        for (file in files) {
            val text = extractTextForGemma(file)
            if (text.isBlank()) continue

            for (chunk in text.chunked(chunkSize)) {
                if (!anchorRegex.containsMatchIn(chunk)) continue

                val isSafe = ThermalGuard.awaitSafeTemperature(context)
                if (!isSafe) {
                    Log.w(TAG, "Skipping chunk for $tenderId — device too hot (${ThermalGuard.statusLabel(ThermalGuard.getCurrentStatus(context))}).")
                    updateGemmaEnrichmentStatus(tenderId, "⚠️ Paused: device cooling down...")
                    continue
                }

                val scoutResp = runGemmaInferenceQuiet(
                    model, tenderId,
                    """Analyze this text block from a South African tender document.
Identify if it contains CIDB Grading, Functionality Criteria, or Compulsory Briefing.
Return ONLY valid JSON: {"contains_cidb": boolean, "contains_functionality": boolean, "contains_briefing": boolean}

TEXT:
$chunk""",
                    "Scouting page..."
                )
                val scoutJson = try { extractJsonObject(scoutResp) } catch (e: Exception) { continue }

                if (scoutJson.optBoolean("contains_cidb", false)) {
                    val resp = runGemmaInferenceQuiet(
                        model, tenderId,
                        """Extract the CIDB Grading and Preference Points from this text.
Return ONLY valid JSON: {"cidb_grading": "string or null", "preference_points": "string or null", "is_jv_allowed": boolean}

TEXT:
$chunk""",
                        "Extracting CIDB..."
                    )
                    val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
                    val cur = finalEnrichment.optJSONObject("compliance_meta") ?: JSONObject()
                    if (json.has("cidb_grading") && !json.isNull("cidb_grading")) cur.put("cidb_grading", json.optString("cidb_grading"))
                    if (json.has("preference_points") && !json.isNull("preference_points")) cur.put("preference_points", json.optString("preference_points"))
                    if (json.has("is_jv_allowed")) cur.put("is_jv_allowed", json.optBoolean("is_jv_allowed"))
                    finalEnrichment.put("compliance_meta", cur)
                }

                if (scoutJson.optBoolean("contains_functionality", false)) {
                    val resp = runGemmaInferenceQuiet(
                        model, tenderId,
                        """Extract Functionality Evaluation Criteria from this text.
Return ONLY valid JSON: {"minimum_threshold": integer or null, "criteria_weights": [{"criterion": "string", "weight": integer}]}

TEXT:
$chunk""",
                        "Extracting Functionality..."
                    )
                    val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
                    val cur = finalEnrichment.optJSONObject("functionality_score") ?: JSONObject()
                    if (json.has("minimum_threshold") && !json.isNull("minimum_threshold")) cur.put("minimum_threshold", json.optInt("minimum_threshold"))
                    if (json.has("criteria_weights") && json.optJSONArray("criteria_weights") != null) {
                        val arr = cur.optJSONArray("criteria_weights") ?: JSONArray()
                        val newArr = json.optJSONArray("criteria_weights")
                        if (newArr != null) for (i in 0 until newArr.length()) arr.put(newArr.getJSONObject(i))
                        cur.put("criteria_weights", arr)
                    }
                    finalEnrichment.put("functionality_score", cur)
                }

                if (scoutJson.optBoolean("contains_briefing", false)) {
                    val resp = runGemmaInferenceQuiet(
                        model, tenderId,
                        """Extract Compulsory Briefing details from this text.
Return ONLY valid JSON: {"is_compulsory": boolean, "date": "string or null", "venue": "string or null"}

TEXT:
$chunk""",
                        "Extracting Briefing..."
                    )
                    val json = try { extractJsonObject(resp) } catch (e: Exception) { JSONObject() }
                    val cur = finalEnrichment.optJSONObject("briefing") ?: JSONObject()
                    if (json.has("is_compulsory")) cur.put("is_compulsory", json.optBoolean("is_compulsory"))
                    if (json.has("date") && !json.isNull("date")) cur.put("date", json.optString("date"))
                    if (json.has("venue") && !json.isNull("venue")) cur.put("venue", json.optString("venue"))
                    finalEnrichment.put("briefing", cur)
                }
            }
        }
        return finalEnrichment
    }

    private suspend fun runGemmaInferenceQuiet(
        model: Model,
        tenderId: String,
        prompt: String,
        status: String,
    ): String = runGemmaInferenceForResult(
        model = model,
        tenderId = tenderId,
        prompt = prompt,
        runningStatus = status,
        statusUpdater = ::updateGemmaEnrichmentStatus,
    )

    private suspend fun uploadTenderToFirebaseInternal(tenderId: String): Boolean {
        val folder = fileManager.getTenderFolder(tenderId)
        val files = folder.listFiles()?.filter { it.isFile }.orEmpty()
        if (files.isEmpty()) {
            updateFirebaseUploadStatus(tenderId, "No tender files found to upload.")
            return false
        }

        updateFirebaseUploadStatus(tenderId, "Uploading ${files.size} file(s) to Firebase...")

        return try {
            fileManager.clearTenderUploadedMarker(folder)
            val result = firebaseSync.uploadTenderFolder(folder)
            fileManager.markTenderUploaded(folder)
            updateFirebaseUploadStatus(
                tenderId,
                "Uploaded ${result.uploadedPaths.size} file(s) to ${result.uploadedPaths.firstOrNull()?.substringBeforeLast('/') ?: "/tenders/$tenderId"}",
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Firebase upload failed for $tenderId", e)
            val message =
                when (e) {
                    is StorageException -> {
                        if (e.errorCode == StorageException.ERROR_NOT_AUTHORIZED) {
                            "Firebase upload blocked: Storage returned 403 Permission denied for /tenders/$tenderId. Update Storage rules or use a trusted backend/service account upload path."
                        } else {
                            "Firebase upload failed: ${e.message ?: "storage error"}"
                        }
                    }
                    else -> "Firebase upload failed: ${e.message ?: "unknown error"}"
                }
            updateFirebaseUploadStatus(
                tenderId,
                message,
            )
            false
        }
    }

    private suspend fun downloadTenderFromFirebaseInternal(tenderId: String): Boolean {
        updateFirebaseDownloadStatus(tenderId, "Downloading tender files from Firebase...")
        return try {
            val folder = fileManager.clearTenderFolder(tenderId)
            val result = firebaseSync.downloadTenderFolder(tenderId, folder)
            updateFirebaseDownloadStatus(
                tenderId,
                "Downloaded ${result.downloadedFiles.size} file(s) from Firebase.",
            )
            loadDownloadedTenders()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to download tender $tenderId from Firebase", e)
            updateFirebaseDownloadStatus(
                tenderId,
                "Failed to download from Firebase: ${e.message ?: "unknown error"}",
            )
            false
        }
    }

    private data class PreparedTenderDocuments(
        val folder: File,
        val readableFiles: List<File>,
        val documentBundle: String,
    )

    private suspend fun prepareTenderDocuments(
        model: Model,
        tenderId: String,
        statusUpdater: (String, String) -> Unit,
        resultUpdater: ((String, String) -> Unit)?,
        maxPromptChars: Int,
    ): PreparedTenderDocuments? {
        statusUpdater(tenderId, "Preparing documents for ${model.displayName.ifEmpty { model.name }}...")

        val folder = fileManager.getTenderFolder(tenderId)
        val readableFiles =
            folder.listFiles()
                ?.filter { it.isFile }
                ?.filterNot { it.name.equals("manifest.json", ignoreCase = true) }
                ?.filterNot { it.name.equals("support-documents.json", ignoreCase = true) }
                ?.filter { isGemmaReadableFile(it) }
                .orEmpty()

        if (readableFiles.isEmpty()) {
            val message = "No readable downloaded documents found for Gemma."
            statusUpdater(tenderId, message)
            resultUpdater?.invoke(tenderId, message)
            return null
        }

        val documentBundle = buildDocumentBundle(readableFiles, maxPromptChars)
        if (documentBundle.isBlank()) {
            val message = "Downloaded documents were found, but no text could be extracted."
            statusUpdater(tenderId, message)
            resultUpdater?.invoke(tenderId, message)
            return null
        }

        statusUpdater(tenderId, "Initializing ${model.displayName.ifEmpty { model.name }}...")
        val initialized = ensureModelInitialized(model)
        if (!initialized) {
            statusUpdater(tenderId, "Failed to initialize ${model.displayName.ifEmpty { model.name }}.")
            return null
        }

        return PreparedTenderDocuments(folder = folder, readableFiles = readableFiles, documentBundle = documentBundle)
    }

    private fun runGemmaInference(
        model: Model,
        tenderId: String,
        prompt: String,
        onPartial: (String) -> Unit,
        onDone: (String) -> Unit,
        onStopped: () -> Unit,
        onError: (String) -> Unit,
        statusUpdater: (String, String) -> Unit,
        readableFileCount: Int,
    ) {
        var response = ""
        var firstPartial = true

        Log.d(TAG, "Gemma prompt length for $tenderId: ${prompt.length} chars")

        statusUpdater(
            tenderId,
            "Reading ${readableFileCount} downloaded document(s) with ${model.displayName.ifEmpty { model.name }}..."
        )
        model.runtimeHelper.resetConversation(model = model, supportImage = false, supportAudio = false)
        Thread.sleep(300)
        model.runtimeHelper.runInference(
            model = model,
            input = prompt,
            resultListener = { partialResult, done, _ ->
                if (firstPartial && partialResult.isNotBlank()) {
                    firstPartial = false
                    statusUpdater(tenderId, "Gemma is responding...")
                }

                if (partialResult.isNotBlank()) {
                    response += partialResult
                    onPartial(response)
                }

                if (done) {
                    onDone(response.ifBlank { "Gemma completed without returning any text." })
                }
            },
            cleanUpListener = { onStopped() },
            onError = { error -> onError(error) },
            coroutineScope = viewModelScope,
        )
    }

    private suspend fun runGemmaInferenceForResult(
        model: Model,
        tenderId: String,
        prompt: String,
        runningStatus: String,
        statusUpdater: (String, String) -> Unit,
    ): String {
        val deferred = CompletableDeferred<String>()
        var response = ""
        var firstPartial = true

        Log.d(TAG, "Gemma prompt length for $tenderId: ${prompt.length} chars")
        statusUpdater(tenderId, runningStatus)
        model.runtimeHelper.resetConversation(model = model, supportImage = false, supportAudio = false)
        delay(300)
        model.runtimeHelper.runInference(
            model = model,
            input = prompt,
            resultListener = { partialResult, done, _ ->
                if (firstPartial && partialResult.isNotBlank()) {
                    firstPartial = false
                    statusUpdater(tenderId, "Gemma is responding...")
                }

                if (partialResult.isNotBlank()) {
                    response += partialResult
                }

                if (done && !deferred.isCompleted) {
                    deferred.complete(response.ifBlank { "Gemma completed without returning any text." })
                }
            },
            cleanUpListener = {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(
                        IllegalStateException("Gemma inference stopped before completion."),
                    )
                }
            },
            onError = { error ->
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException(error))
                }
            },
            coroutineScope = viewModelScope,
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
            coroutineScope = viewModelScope,
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

    private fun buildGemmaReadCheckPrompt(tenderId: String, documentBundle: String): String {
        return """
            You are validating whether a local Gemma model can read downloaded tender documents.

            Read the supplied tender documents for tender ${tenderId} and return a compact plain-text report with exactly these sections:
            READABLE: yes or no
            FILES_READ: comma-separated file names you were able to use
            SUMMARY: 2-4 sentences summarizing the tender documents
            EVIDENCE: 2-4 bullet points quoting or paraphrasing specific details from the documents

            If the text is incomplete, noisy, or unreadable, say so explicitly.
            Do not return markdown code fences.

            DOCUMENTS:
            ${documentBundle}
        """.trimIndent()
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

    private fun buildGemmaCoreDetailsPrompt(
        tenderId: String,
        manifestContext: String,
        documentBundle: String,
    ): String {
        return """
            You are extracting core tender details from tender documents.

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
                            "completeTenderDescription": "full detailed description based on the documents"
            }

            Rules:
            - Keep all text compact and evidence-based.
            - If a field is missing, use null or "unknown" as appropriate.
            - Only use the allowed industry values.
            - For beeLevel, prefer the explicit B-BBEE contributor level or stated BEE preference level from the documents. If not stated, use "unknown".
            - If the documents are an advert rather than a full tender pack, say so in documentType and explain the limitation in completeTenderDescription.

                        ${manifestContext}

            DOCUMENTS:
            ${documentBundle}
        """.trimIndent()
    }

        private fun buildGemmaRequirementsPrompt(
                tenderId: String,
                manifestContext: String,
                documentBundle: String,
        ): String {
                return """
                        You are extracting a complete list of tender requirements for tender ${tenderId}.

                        Return exactly one valid JSON object and nothing else.
                        Schema:
                        {
                            "requirements": [
                                {
                                    "category": "compliance|technical|professional_body|experience|pricing|administrative|mandatory_document|other",
                                    "requirement": "specific requirement text",
                                    "mandatory": true,
                                    "evidence": "short quote or paraphrase from the document"
                                }
                            ]
                        }

                        Rules:
                        - Include business compliance, registrations, tax, CSD, CIDB, ISO, NHBRC, professional bodies, mandatory forms, pricing rules, delivery requirements, and technical requirements when present.
                        - Be exhaustive, but do not invent requirements.
                        - Use an empty array if nothing is present.

                        ${manifestContext}

                        DOCUMENTS:
                        ${documentBundle}
                """.trimIndent()
        }

        private fun buildGemmaBoqPrompt(
                tenderId: String,
                manifestContext: String,
                documentBundle: String,
        ): String {
                return """
                        You are extracting the bill of quantities or schedule of items for tender ${tenderId}.

                        Return exactly one valid JSON object and nothing else.
                        Schema:
                        {
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
                        - Include every identifiable BOQ or schedule item from the provided text.
                        - If there is no BOQ in the provided text, return an empty array.
                        - Do not invent quantities or prices.

                        ${manifestContext}

                        DOCUMENTS:
                        ${documentBundle}
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
            val candidate = codeFenceStripped.substring(start, end + 1)
            JSONObject(sanitizeGemmaJsonCandidate(candidate))
        }
    }

    private fun sanitizeGemmaJsonCandidate(rawJson: String): String {
        // Simple but robust sanitization
        var cleaned = rawJson.trim()

        // Handle common truncation: if it doesn't end with } or ], it might be truncated.
        // We try to close it minimally to allow parsing of what we have.
        if (cleaned.isNotBlank() && !cleaned.endsWith("}") && !cleaned.endsWith("]")) {
            val lastOpenBrace = cleaned.lastIndexOf('{')
            val lastOpenBracket = cleaned.lastIndexOf('[')
            if (lastOpenBrace > lastOpenBracket) {
                // If it looks like it was in a string, close the string first
                if (cleaned.count { it == '"' } % 2 != 0) {
                    cleaned += "\""
                }
                cleaned += "}"
            } else if (lastOpenBracket > lastOpenBrace) {
                cleaned += "]"
            }
        }

        // Remove trailing commas before closing braces/brackets
        cleaned = cleaned.replace(Regex(",\\s*\\}"), "}")
        cleaned = cleaned.replace(Regex(",\\s*\\]"), "]")

        // Basic attempt to fix unescaped newlines in strings
        val fixed = StringBuilder()
        var inString = false
        var escaped = false
        for (i in cleaned.indices) {
            val c = cleaned[i]
            if (escaped) {
                fixed.append(c)
                escaped = false
                continue
            }
            if (c == '\\') {
                fixed.append(c)
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                fixed.append(c)
                continue
            }
            if (c == '\n' && inString) {
                fixed.append("\\n")
                continue
            }
            fixed.append(c)
        }
        
        return fixed.toString()
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

        // Fix 2: Remove old gemmaEnrichment duplication blob.
        // Fix 3: Promote all Gemma-extracted fields directly to top-level manifest fields.

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

        // verification status
        manifest.put("gemma_verification", enrichment.optString("verification_status", "UNKNOWN"))

        // Remove stale duplication fields from old pipeline if present
        manifest.remove("gemmaEnrichment")
        manifest.remove("documentType")
        manifest.remove("briefDescription")
        manifest.remove("completeTenderDescription")
        manifest.remove("estimatedTenderValue")
        manifest.remove("requirements")
        manifest.remove("billOfQuantities")
        manifest.remove("industryCategory")

        fileManager.writeManifest(folder, manifest.toString(2))
    }

    private fun extractIndustryValue(json: JSONObject): String {
        return json.optString("industry", json.optString("industryCategory", "unknown"))
    }

    private fun extractBeeLevelValue(json: JSONObject): String {
        return json.optString("beeLevel", json.optString("bbbEELevel", json.optString("bbbeeLevel", "unknown")))
    }

    private fun updateGemmaStatus(tenderId: String, status: String) {
        _uiState.value =
            _uiState.value.copy(
                gemmaReadCheckStatusByTender = _uiState.value.gemmaReadCheckStatusByTender + (tenderId to status)
            )
    }

    private fun updateGemmaResult(tenderId: String, result: String) {
        _uiState.value =
            _uiState.value.copy(
                gemmaReadCheckResultByTender = _uiState.value.gemmaReadCheckResultByTender + (tenderId to result)
            )
    }

    private fun updateGemmaEnrichmentStatus(tenderId: String, status: String) {
        _uiState.value =
            _uiState.value.copy(
                gemmaEnrichmentStatusByTender = _uiState.value.gemmaEnrichmentStatusByTender + (tenderId to status)
            )
    }

    private fun updateFirebaseUploadStatus(tenderId: String, status: String) {
        _uiState.value =
            _uiState.value.copy(
                firebaseUploadStatusByTender = _uiState.value.firebaseUploadStatusByTender + (tenderId to status)
            )
    }

    private fun updateScrapeStatus(status: String) {
        _uiState.value = _uiState.value.copy(scrapeStatus = status)
    }

    private fun updateFirebaseListStatus(status: String) {
        _uiState.value = _uiState.value.copy(firebaseListStatus = status)
    }

    private fun updateFirebaseDownloadStatus(tenderId: String, status: String) {
        _uiState.value =
            _uiState.value.copy(
                firebaseDownloadStatusByTender = _uiState.value.firebaseDownloadStatusByTender + (tenderId to status)
            )
    }

    private fun buildFirebaseCleanupSummary(
        result: com.google.ai.edge.gallery.infrastructure.ExpiredTenderCleanupResult,
    ): String {
        val deletedCount = result.deletedTenderIds.size
        val retainedCount = result.retainedTenderIds.size
        val unreadableCount = result.unreadableTenderIds.size

        val parts = mutableListOf("Removed $deletedCount expired tender(s)", "kept $retainedCount active")
        if (unreadableCount > 0) {
            parts += "skipped $unreadableCount with missing or unreadable closing dates"
        }

        return parts.joinToString(separator = "; ", postfix = ".")
    }

    private fun resetProcessingStatus(isScraping: Boolean) {
        _uiState.value =
            _uiState.value.copy(
                isScraping = isScraping,
                scrapeStatus = "",
                hasResumableSession = false,
                firebaseDownloadStatusByTender = emptyMap(),
                gemmaReadCheckStatusByTender = emptyMap(),
                gemmaReadCheckResultByTender = emptyMap(),
                gemmaEnrichmentStatusByTender = emptyMap(),
                firebaseUploadStatusByTender = emptyMap(),
                bulkEnrichmentStatus = "",
            )
    }

    private fun restoreAutomationSession() {
        val session = loadAutomationSession() ?: return
        reconcileSessionWithStoredTenders(session)
        normalizeAutomationSession(session)
        activeAutomationSession = session
        _uiState.value =
            _uiState.value.copy(
                scrapeStatus = session.status.ifBlank { "A previous scraper session can be resumed." },
                hasResumableSession = true,
            )
    }

    private fun observeBackgroundScraperWork() {
        val observer = Observer<List<WorkInfo>> { workInfos ->
            val workInfo = workInfos.firstOrNull()
            if (workInfo == null) {
                _uiState.value =
                    _uiState.value.copy(
                        isBackgroundScraperRunning = false,
                        canResumeBackgroundScraper = false,
                    )
                return@Observer
            }

            val progress = workInfo.progress
            val status = progress.getString(TENDER_SCRAPER_PROGRESS_STATUS).orEmpty()
            val completed = progress.getInt(TENDER_SCRAPER_PROGRESS_COMPLETED, 0)
            val total = progress.getInt(TENDER_SCRAPER_PROGRESS_TOTAL, 0)
            val summary =
                when {
                    status.isBlank() -> backgroundStatusForState(workInfo.state)
                    total > 0 -> "$status ($completed/$total)"
                    else -> status
                }

            _uiState.value =
                _uiState.value.copy(
                    isBackgroundScraperRunning =
                        workInfo.state == WorkInfo.State.RUNNING ||
                            workInfo.state == WorkInfo.State.ENQUEUED ||
                            workInfo.state == WorkInfo.State.BLOCKED,
                    canResumeBackgroundScraper =
                        workInfo.state == WorkInfo.State.CANCELLED ||
                            workInfo.state == WorkInfo.State.FAILED,
                    backgroundScraperStatus = summary,
                )
        }

        workManager.getWorkInfosForUniqueWorkLiveData(TENDER_SCRAPER_WORK_NAME).observeForever(observer)
        backgroundWorkerObserver = observer
    }

    private fun backgroundStatusForState(state: WorkInfo.State): String {
        return when (state) {
            WorkInfo.State.ENQUEUED -> "Background scraper queued. Waiting for Wi-Fi and charging."
            WorkInfo.State.RUNNING -> "Background scraper is running..."
            WorkInfo.State.SUCCEEDED -> "Background scraper completed successfully."
            WorkInfo.State.CANCELLED -> "Background scraper cancelled. Resume will continue pending tenders."
            WorkInfo.State.FAILED -> "Background scraper failed. Resume will retry pending tenders."
            WorkInfo.State.BLOCKED -> "Background scraper is blocked and waiting on constraints."
        }
    }

    private fun reconcileSessionWithStoredTenders(session: ScrapeAutomationSession) {
        val markedTenderIds = fileManager.findTenderIdsForSession(session.sessionId)
        markedTenderIds.forEach { tenderId ->
            val normalizedTenderId = normalizeTenderId(tenderId)
            if (!session.scrapedTenderIds.contains(normalizedTenderId)) {
                session.scrapedTenderIds.add(normalizedTenderId)
            }
        }
        normalizeAutomationSession(session)
        persistAutomationSession(session)
    }

    private fun loadAutomationSession(): ScrapeAutomationSession? {
        val raw = fileManager.readScrapeAutomationSession() ?: return null
        return try {
            ScrapeAutomationSession.fromJson(JSONObject(raw)).also(::normalizeAutomationSession)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read scraper automation session", e)
            null
        }
    }

    private fun persistAutomationSession(session: ScrapeAutomationSession) {
        normalizeAutomationSession(session)
        fileManager.saveScrapeAutomationSession(session.toJson().toString(2))
    }

    private fun normalizeAutomationSession(session: ScrapeAutomationSession) {
        session.scrapedTenderIds.normalizeTenderIdList()
        session.enrichedTenderIds.normalizeTenderIdList()
        session.uploadedTenderIds.normalizeTenderIdList()
        session.currentTenderId = session.currentTenderId?.let(::normalizeTenderId)
    }

    private fun MutableList<String>.normalizeTenderIdList() {
        val normalized = this.map(::normalizeTenderId).distinct()
        clear()
        addAll(normalized)
    }

    private fun normalizeTenderId(tenderId: String): String {
        return fileManager.normalizeTenderId(tenderId)
    }

    private fun hasGemmaReadableDocuments(tenderId: String): Boolean {
        val folder = fileManager.getTenderFolder(tenderId)
        return folder.listFiles()
            ?.asSequence()
            ?.filter { it.isFile }
            ?.filterNot { it.name.equals("manifest.json", ignoreCase = true) }
            ?.filterNot { it.name.equals("support-documents.json", ignoreCase = true) }
            ?.any { file ->
                when (file.extension.lowercase()) {
                    "pdf", "txt", "md", "csv" -> true
                    else -> false
                }
            }
            ?: false
    }

    private fun clearAutomationSession() {
        fileManager.clearScrapeAutomationSession()
        _uiState.value = _uiState.value.copy(hasResumableSession = false)
    }

    private fun stopAutomationSession(session: ScrapeAutomationSession, status: String) {
        session.stage = SESSION_STAGE_STOPPED
        session.currentTenderId = session.currentTenderId
        session.status = status
        persistAutomationSession(session)
        updateScrapeStatus(status)
        _uiState.value = _uiState.value.copy(hasResumableSession = true)
    }

    private fun failAutomationSession(session: ScrapeAutomationSession, status: String) {
        session.stage = SESSION_STAGE_FAILED
        session.status = status
        persistAutomationSession(session)
        updateScrapeStatus(status)
        _uiState.value = _uiState.value.copy(hasResumableSession = true)
    }

    override fun onCleared() {
        backgroundWorkerObserver?.let { observer ->
            workManager.getWorkInfosForUniqueWorkLiveData(TENDER_SCRAPER_WORK_NAME).removeObserver(observer)
        }
        super.onCleared()
    }

    private fun buildGemmaCombinedEnrichmentPrompt(
        tenderId: String,
        manifestContext: String,
        documentBundle: String,
    ): String {
        return """
            You are extracting comprehensive tender details for tender ${tenderId}.
            
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
            - For requirements and billOfQuantities, include the most important items (max 10 each).
            - Ensure the JSON is well-formed and complete.
            - If no BOQ or requirements are found, return empty arrays.

            ${manifestContext}

            DOCUMENTS:
            ${documentBundle}
        """.trimIndent()
    }
}

