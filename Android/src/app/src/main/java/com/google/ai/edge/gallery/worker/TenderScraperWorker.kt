package com.google.ai.edge.gallery.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.ai.edge.gallery.MainActivity
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.TenderFileManager
import com.google.ai.edge.gallery.data.TenderScraper
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val TAG = "AGTenderScraperWorker"
const val TENDER_SCRAPER_WORK_NAME = "tender_scraper_background"
const val TENDER_SCRAPER_PROGRESS_STATUS = "tender_scraper_progress_status"
const val TENDER_SCRAPER_PROGRESS_COMPLETED = "tender_scraper_progress_completed"
const val TENDER_SCRAPER_PROGRESS_TOTAL = "tender_scraper_progress_total"
private const val BATCH_SIZE = 5
private const val CONCURRENT_TENDER_LIMIT = 1
private const val FOREGROUND_NOTIFICATION_CHANNEL_ID = "tender_scraper_channel_foreground"
private const val FOREGROUND_NOTIFICATION_ID = 2002
private var channelCreated = false

@HiltWorker
class TenderScraperWorker @AssistedInject constructor(
  @Assisted appContext: Context,
  @Assisted params: WorkerParameters,
  private val tenderScraper: TenderScraper,
  private val tenderFileManager: TenderFileManager,
  private val gemmaModelResolver: GemmaModelResolver,
  private val tenderAutomationProcessor: TenderAutomationProcessor,
) : CoroutineWorker(appContext, params) {

  init {
    if (!channelCreated) {
      val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel =
        NotificationChannel(
          FOREGROUND_NOTIFICATION_CHANNEL_ID,
          "Tender Scraper",
          NotificationManager.IMPORTANCE_LOW,
        ).apply {
          description = "Background tender scraping, enrichment, and upload progress"
        }
      notificationManager.createNotificationChannel(channel)
      channelCreated = true
    }
  }

  override suspend fun doWork(): Result {
    return try {
      val maxTendersToProcess = 50 // Sanity limit for a single background run

      publishProgress(
        status = "Starting background scraper...",
        completed = 0,
        total = maxTendersToProcess,
        indeterminate = true,
      )

      val model = gemmaModelResolver.resolveDownloadedGemmaModel()
        ?: return Result.failure()

      val pendingTenderIds = tenderFileManager.listPendingTenderIds().toMutableSet()
      if (pendingTenderIds.isNotEmpty()) {
        publishProgress(
          status = "Resuming ${pendingTenderIds.size} pending tender(s) from local storage...",
          completed = 0,
          total = pendingTenderIds.size,
        )
      }

      var totalProcessed = 0

      while (totalProcessed < maxTendersToProcess) {
        publishProgress(
          status = "Scraping next batch of up to $BATCH_SIZE tenders...",
          completed = totalProcessed,
          total = maxTendersToProcess,
          indeterminate = true,
        )

        val scrapeResult =
          tenderScraper.fetchLatestTenders(
            limit = BATCH_SIZE,
            onStatus = { status -> Log.d(TAG, status) },
          )

        if (scrapeResult.failureMessage != null) {
          throw IOException(scrapeResult.failureMessage)
        }

        if (scrapeResult.newTenderIds.isEmpty()) {
            Log.d(TAG, "No more new tenders found in this batch.")
            break
        }

        val batchTenderIds = scrapeResult.newTenderIds
        for ((index, tenderId) in batchTenderIds.withIndex()) {
            val position = totalProcessed + 1
            val status = "Processing tender $position: $tenderId"
            Log.d(TAG, status)
            publishProgress(status = status, completed = totalProcessed, total = maxTendersToProcess)
            try {
              tenderAutomationProcessor.enrichAndUploadTender(model, tenderId)
              totalProcessed++
            } catch (error: Exception) {
              Log.e(TAG, "Failed background processing for $tenderId", error)
              // Continue with next tender in batch
            }
        }

        if (scrapeResult.exhausted) break
      }

      if (totalProcessed == 0) {
        Log.d(TAG, "No new tenders were processed. Worker finished successfully.")
        publishProgress(status = "No new tenders found to process.", completed = 0, total = 0)
        return Result.success()
      }

      publishProgress(
        status = "Background scraper completed $totalProcessed tender(s).",
        completed = totalProcessed,
        total = totalProcessed,
      )
      Result.success()
    } catch (e: Exception) {
      Log.e(TAG, "Tender scraper worker failed", e)
      publishProgress(
        status = "Background scraper failed: ${e.message ?: "unknown error"}",
        completed = 0,
        total = BATCH_SIZE,
      )
      if (isTransientNetworkError(e)) {
        Result.retry()
      } else {
        Result.failure()
      }
    }
  }

  private fun isTransientNetworkError(error: Throwable): Boolean {
    val message = error.message.orEmpty()
    return error is IOException ||
      message.contains("405") ||
      message.contains("500") ||
      message.contains("502") ||
      message.contains("503") ||
      message.contains("504")
  }

  private suspend fun publishProgress(
    status: String,
    completed: Int,
    total: Int,
    indeterminate: Boolean = false,
  ) {
    setProgress(
      workDataOf(
        TENDER_SCRAPER_PROGRESS_STATUS to status,
        TENDER_SCRAPER_PROGRESS_COMPLETED to completed,
        TENDER_SCRAPER_PROGRESS_TOTAL to total,
      ),
    )
    setForeground(createForegroundInfo(status, completed, total, indeterminate))
  }

  private fun createForegroundInfo(
    status: String,
    completed: Int,
    total: Int,
    indeterminate: Boolean,
  ): ForegroundInfo {
    val intent = Intent(applicationContext, MainActivity::class.java)
    val pendingIntent =
      PendingIntent.getActivity(
        applicationContext,
        0,
        intent,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      )

    val builder =
      NotificationCompat.Builder(applicationContext, FOREGROUND_NOTIFICATION_CHANNEL_ID)
        .setContentTitle(applicationContext.getString(R.string.app_name))
        .setContentText(status)
        .setSmallIcon(R.mipmap.ic_launcher)
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(pendingIntent)

    if (indeterminate || total <= 0) {
      builder.setProgress(0, 0, true)
    } else {
      builder.setProgress(total, completed.coerceIn(0, total), false)
    }

    return ForegroundInfo(
      FOREGROUND_NOTIFICATION_ID,
      builder.build(),
      ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )
  }
}
