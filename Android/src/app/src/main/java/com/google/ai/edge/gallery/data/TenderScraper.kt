package com.google.ai.edge.gallery.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.storage.FirebaseStorage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import org.jsoup.Jsoup
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.concurrent.TimeUnit
import java.io.IOException
import java.net.URLEncoder
import kotlin.math.min
import org.json.JSONArray
import org.json.JSONObject

class TenderScraper(
    private val context: Context,
    private val fileManager: TenderFileManager,
) {

    data class ScrapeResult(
        val newTenderIds: List<String>,
        val stopped: Boolean = false,
        val exhausted: Boolean = false,
        val failureMessage: String? = null,
    )

    companion object {
        private const val OCR_FALLBACK_MIN_TEXT_CHARS = 600
        private const val OCR_FALLBACK_MIN_TEXT_PER_PAGE = 250
        private const val OCR_RENDER_SCALE = 2
        private const val OCR_MAX_PAGES = 12
    }

    fun fetchLatestTenders(
        limit: Int,
        onStatus: ((String) -> Unit)? = null,
        shouldStop: (() -> Boolean)? = null,
        onNewTenderSaved: ((String) -> Unit)? = null,
        sessionId: String? = null,
    ): ScrapeResult {
        return scrapeTenderList(limit, onStatus, shouldStop, onNewTenderSaved, sessionId)
    }

    fun scrapeTenderList(
        limit: Int = -1,
        onStatus: ((String) -> Unit)? = null,
        shouldStop: (() -> Boolean)? = null,
        onNewTenderSaved: ((String) -> Unit)? = null,
        sessionId: String? = null,
    ): ScrapeResult {
        val newTenderIds = mutableListOf<String>()
        try {
            val client = OkHttpClient()
            val pageSize = when {
                limit > 0 -> min(limit, 100)
                else -> 25
            }
            var processed = 0
            var start = 0

            onStatus?.invoke("Starting scrape for ${if (limit > 0) "$limit new tenders" else "latest tenders"}...")

            while (limit <= 0 || processed < limit) {
                if (shouldStop?.invoke() == true) {
                    onStatus?.invoke("Scrape stopped. Saved $processed${if (limit > 0) "/$limit" else ""} new tenders.")
                    return ScrapeResult(newTenderIds = newTenderIds, stopped = true)
                }

                val remaining = if (limit > 0) limit - processed else pageSize
                val batchSize = if (limit > 0) min(pageSize, remaining.coerceAtLeast(1)) else pageSize
                onStatus?.invoke(
                    "Fetching tenders page starting at ${start + 1}. Saved $processed${if (limit > 0) "/$limit" else ""} new tenders so far...",
                )
                val url =
                    "https://www.etenders.gov.za/Home/PaginatedTenderOpportunities?draw=1&start=$start&length=$batchSize&status=1&order%5B0%5D%5Bcolumn%5D=0&order%5B0%5D%5Bdir%5D=desc&columns%5B0%5D%5Bdata%5D=AdvertisedDate&columns%5B0%5D%5Bname%5D=&columns%5B0%5D%5Bsearchable%5D=true&columns%5B0%5D%5Borderable%5D=true&columns%5B0%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B0%5D%5Bsearch%5D%5Bregex%5D=false"
                val request =
                    Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        .addHeader("Referer", "https://www.etenders.gov.za/Home/opportunities?id=1")
                        .addHeader("X-Requested-With", "XMLHttpRequest")
                        .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                        .build()

                val response = client.newCall(request).execute()
                Log.d("ScraperDebug", "API GET response code: ${response.code} for start=$start length=$batchSize")
                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    Log.e("ScraperError", "Empty response from API")
                    return ScrapeResult(newTenderIds = newTenderIds, failureMessage = "Empty response from API")
                }

                Log.d("ScraperDebug", "API response length: ${responseBody.length}")
                Log.d("ScraperDebug", "API response preview: ${responseBody.take(500)}")

                val jsonObject = JSONObject(responseBody)
                val dataArray = jsonObject.getJSONArray("data")
                Log.d("ScraperDebug", "Parsed data array with ${dataArray.length()} items from start=$start")
                if (dataArray.length() == 0) {
                    onStatus?.invoke("No more tenders returned by eTenders. Saved $processed new tenders.")
                    return ScrapeResult(newTenderIds = newTenderIds, exhausted = true)
                }

                for (i in 0 until dataArray.length()) {
                    if (limit > 0 && processed >= limit) break

                    if (shouldStop?.invoke() == true) {
                        onStatus?.invoke("Scrape stopped. Saved $processed${if (limit > 0) "/$limit" else ""} new tenders.")
                        return ScrapeResult(newTenderIds = newTenderIds, stopped = true)
                    }

                    val tenderObj = dataArray.getJSONObject(i)
                    val tenderNo = tenderObj.getString("tender_No")
                    val description = tenderObj.getString("description")

                    if (fileManager.hasTenderFolder(tenderNo)) {
                        Log.d("ScraperDebug", "Skipping already-scraped tender: $tenderNo - $description")
                        onStatus?.invoke("Skipping existing tender $tenderNo. Saved $processed${if (limit > 0) "/$limit" else ""} new tenders so far...")
                        continue
                    }

                    Log.d("ScraperDebug", "Processing tender: $tenderNo - $description")
                    onStatus?.invoke(
                        "Saving tender ${processed + 1}${if (limit > 0) "/$limit" else ""}: $tenderNo",
                    )

                    try {
                        val folder = fileManager.getTenderFolder(tenderNo)
                        fileManager.writeManifest(folder, tenderObj.toString(2))
                        if (!sessionId.isNullOrBlank()) {
                            fileManager.markTenderFolderSession(folder, sessionId)
                        }

                        val supportDocuments = tenderObj.optJSONArray("supportDocument") ?: JSONArray()
                        if (supportDocuments.length() > 0) {
                            fileManager.saveTextFile(folder, "support-documents.json", supportDocuments.toString(2))
                            downloadSupportDocuments(client, tenderNo, supportDocuments)
                        }

                        Log.d(
                            "ScraperDebug",
                            "Saved tender $tenderNo with ${supportDocuments.length()} support document entries",
                        )
                        processed++
                        newTenderIds.add(tenderNo)
                        onNewTenderSaved?.invoke(tenderNo)
                        onStatus?.invoke(
                            "Saved $processed${if (limit > 0) "/$limit" else ""} new tenders. Latest: $tenderNo",
                        )
                    } catch (e: Exception) {
                        Log.e("ScraperError", "Failed to process tender $tenderNo", e)
                        onStatus?.invoke("Failed to process tender $tenderNo: ${e.message ?: "unknown error"}")
                    }
                }

                start += dataArray.length()
                if (dataArray.length() < batchSize) {
                    break
                }
            }
            Log.d("ScraperDebug", "Processed $processed new tenders")
            onStatus?.invoke("Scrape completed. Saved $processed${if (limit > 0) "/$limit" else ""} new tenders.")
            return ScrapeResult(newTenderIds = newTenderIds, exhausted = true)
        } catch (e: Exception) {
            Log.e("ScraperError", "Error scraping tenders", e)
            onStatus?.invoke("Scrape failed: ${e.message ?: "unknown error"}")
            return ScrapeResult(newTenderIds = newTenderIds, failureMessage = e.message ?: "unknown error")
        }
    }

    private fun downloadSupportDocuments(
        client: OkHttpClient,
        tenderNumber: String,
        supportDocuments: JSONArray,
    ) {
        for (index in 0 until supportDocuments.length()) {
            val supportDocument = supportDocuments.optJSONObject(index) ?: continue
            val supportDocumentId = supportDocument.optString("supportDocumentID")
            val extension = supportDocument.optString("extension", "")
            val fileName = supportDocument.optString(
                "fileName",
                "$supportDocumentId$extension",
            )

            if (supportDocumentId.isBlank()) {
                continue
            }

            val encodedFileName = URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
            val blobName = "$supportDocumentId$extension"
            val downloadUrl =
                "https://www.etenders.gov.za/home/Download/?blobName=$blobName&downloadedFileName=$encodedFileName"

            Log.d("ScraperDebug", "Downloading attachment for $tenderNumber: $fileName")
            downloadFile(client, downloadUrl, tenderNumber, fileName)
        }
    }

    private fun downloadFile(client: OkHttpClient, url: String, tenderNumber: String, filename: String) {
        try {
            val request =
                Request.Builder()
                    .url(url)
                    .addHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                    )
                    .addHeader("Referer", "https://www.etenders.gov.za/Home/opportunities?id=1")
                    .build()
            val response = client.newCall(request).execute()
            val bytes = response.body?.bytes()
            if (response.isSuccessful && bytes != null) {
                val folder = fileManager.getTenderFolder(tenderNumber)
                fileManager.saveDocument(folder, filename, bytes)
                Log.d("ScraperDebug", "Downloaded $filename for tender $tenderNumber")
            } else {
                Log.w("ScraperDebug", "Attachment download failed for $url with code ${response.code}")
            }
        } catch (e: Exception) {
            Log.e("ScraperError", "Failed to download $url", e)
        }
    }

    fun scrapeCompanyContactDetailsForTender(tenderNo: String, tenderObj: JSONObject): Boolean {
        return try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
            
            // Construct detail URL
            val tenderId = tenderObj.optString("tender_ID", "")
            val detailUrl = if (tenderId.isNotBlank()) {
                "https://www.etenders.gov.za/Home/tenderDetails?tenderId=$tenderId"
            } else {
                "https://www.etenders.gov.za/Home/Details/$tenderNo"
            }

            Log.d("ScraperDebug", "Scraping company details from: $detailUrl")

            val request = Request.Builder()
                .url(detailUrl)
                .build()

            val response = client.newCall(request).execute()
            val htmlContent = response.body?.string()

            if (response.isSuccessful && !htmlContent.isNullOrBlank()) {
                val companyDetails = extractCompanyDetailsFromHtmlInternal(htmlContent)
                if (companyDetails.isNotEmpty()) {
                    // Save company details to local cache
                    saveCompanyDetailsToCacheInternal(tenderNo, companyDetails)
                    Log.d("ScraperDebug", "Saved company details for tender $tenderNo: $companyDetails")
                    return true
                }
            } else {
                Log.w("ScraperDebug", "Failed to fetch detail page for tender $tenderNo: ${response.code}")
            }
            false
        } catch (e: Exception) {
            Log.e("ScraperError", "Failed to scrape company details for tender $tenderNo", e)
            false
        }
    }

    private fun extractCompanyDetailsFromHtmlInternal(htmlContent: String): Map<String, String> {
        val details = mutableMapOf<String, String>()

        try {
            val document = Jsoup.parse(htmlContent)

            // Look for company/procuring entity information
            val procuringEntitySelectors = listOf(
                ".procuring-entity",
                ".organization",
                ".company",
                "[data-label*='Entity']",
                "[data-label*='Organization']",
                ".tender-details .entity",
                ".tender-info .company"
            )

            for (selector in procuringEntitySelectors) {
                val element = document.selectFirst(selector)
                if (element != null && element.text().isNotBlank()) {
                    details["companyName"] = element.text().trim()
                    break
                }
            }

            // Look for contact information
            val contactSelectors = listOf(
                ".contact-info",
                ".contact-details",
                "[data-label*='Contact']",
                ".person",
                ".contact-person"
            )

            for (selector in contactSelectors) {
                val element = document.selectFirst(selector)
                if (element != null && element.text().isNotBlank()) {
                    details["contactPerson"] = element.text().trim()
                    break
                }
            }

            // Look for phone/email information
            val phoneSelectors = listOf(
                ".phone",
                ".telephone",
                "[data-label*='Phone']",
                "[data-label*='Tel']"
            )

            for (selector in phoneSelectors) {
                val element = document.selectFirst(selector)
                if (element != null && element.text().isNotBlank()) {
                    details["phone"] = element.text().trim()
                    break
                }
            }

            val emailSelectors = listOf(
                ".email",
                "[data-label*='Email']",
                "a[href^='mailto:']"
            )

            for (selector in emailSelectors) {
                val element = document.selectFirst(selector)
                if (element != null) {
                    val email = element.attr("href")?.removePrefix("mailto:") ?: element.text()
                    if (email.isNotBlank() && email.contains("@")) {
                        details["email"] = email.trim()
                        break
                    }
                }
            }

            // If we didn't find structured data, try to extract from general content
            if (details.isEmpty()) {
                val textContent = document.text()
                // Use regex to find potential contact information
                val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
                val phoneRegex = Regex("\\+?\\d{1,4}?[-.\\s]?\\(?\\d{1,3}\\)?[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,4}")

                emailRegex.find(textContent)?.let { match ->
                    details["email"] = match.value
                }

                phoneRegex.find(textContent)?.let { match ->
                    details["phone"] = match.value
                }
            }

        } catch (e: Exception) {
            Log.e("ScraperError", "Failed to parse HTML for company details", e)
        }

        return details
    }

    private fun saveCompanyDetailsToCacheInternal(tenderNo: String, companyDetails: Map<String, String>) {
        try {
            val companiesFile = File(context.filesDir, "companies_cache.json")
            val companiesData = if (companiesFile.exists()) {
                JSONObject(companiesFile.readText())
            } else {
                JSONObject()
            }

            val companyJson = JSONObject().apply {
                companyDetails.forEach { (key, value) ->
                    put(key, value)
                }
                put("tenderNo", tenderNo)
                put("lastUpdated", System.currentTimeMillis())
            }

            // Use company name as key if available, otherwise use tender number
            val key = companyDetails["companyName"] ?: tenderNo
            companiesData.put(key, companyJson)

            companiesFile.writeText(companiesData.toString(2))
        } catch (e: Exception) {
            Log.e("ScraperError", "Failed to save company details to cache", e)
        }
    }

    fun scrapeAwardedTendersFromPortal(
        limit: Int = -1,
        onStatus: ((String) -> Unit)? = null,
        shouldStop: (() -> Boolean)? = null,
        onNewTenderSaved: ((String) -> Unit)? = null,
    ): ScrapeResult {
        val newTenderIds = mutableListOf<String>()
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()

            val pageSize = when {
                limit > 0 -> min(limit, 100)
                else -> 25
            }
            var processed = 0
            var start = 0

            onStatus?.invoke("Starting scrape for awarded tenders from portal...")

            while (limit <= 0 || processed < limit) {
                if (shouldStop?.invoke() == true) {
                    onStatus?.invoke("Scrape stopped. Saved $processed awarded tenders.")
                    return ScrapeResult(newTenderIds = newTenderIds, stopped = true)
                }

                val remaining = if (limit > 0) limit - processed else pageSize
                val batchSize = if (limit > 0) min(pageSize, remaining.coerceAtLeast(1)) else pageSize
                onStatus?.invoke(
                    "Fetching awarded tenders page starting at ${start + 1}. Saved $processed${if (limit > 0) "/$limit" else ""} awarded tenders so far...",
                )

                // Use status=2 for awarded tenders
                val url =
                    "https://www.etenders.gov.za/Home/PaginatedTenderOpportunities?draw=1&start=$start&length=$batchSize&status=2&order%5B0%5D%5Bcolumn%5D=0&order%5B0%5D%5Bdir%5D=desc&columns%5B0%5D%5Bdata%5D=AdvertisedDate&columns%5B0%5D%5Bname%5D=&columns%5B0%5D%5Bsearchable%5D=true&columns%5B0%5D%5Borderable%5D=true&columns%5B0%5D%5Bsearch%5D%5Bvalue%5D=&columns%5B0%5D%5Bsearch%5D%5Bregex%5D=false"

                val request =
                    Request.Builder()
                        .url(url)
                        .get()
                        .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36")
                        .addHeader("Referer", "https://www.etenders.gov.za/Home/opportunities?id=2")
                        .addHeader("X-Requested-With", "XMLHttpRequest")
                        .addHeader("Accept", "application/json, text/javascript, */*; q=0.01")
                        .build()

                val response = client.newCall(request).execute()
                Log.d("AwardedScraperDebug", "API GET response code: ${response.code} for awarded tenders start=$start length=$batchSize")
                val responseBody = response.body?.string()
                if (responseBody.isNullOrEmpty()) {
                    Log.e("AwardedScraperError", "Empty response from awarded tenders API")
                    return ScrapeResult(newTenderIds = newTenderIds, failureMessage = "Empty response from API")
                }

                Log.d("AwardedScraperDebug", "API response length: ${responseBody.length}")
                Log.d("AwardedScraperDebug", "API response preview: ${responseBody.take(500)}")

                val jsonObject = JSONObject(responseBody)
                val dataArray = jsonObject.getJSONArray("data")
                Log.d("AwardedScraperDebug", "Parsed data array with ${dataArray.length()} items from awarded tenders start=$start")
                if (dataArray.length() == 0) {
                    onStatus?.invoke("No more awarded tenders returned by eTenders. Saved $processed awarded tenders.")
                    return ScrapeResult(newTenderIds = newTenderIds, exhausted = true)
                }

                for (i in 0 until dataArray.length()) {
                    if (limit > 0 && processed >= limit) break

                    if (shouldStop?.invoke() == true) {
                        onStatus?.invoke("Scrape stopped. Saved $processed${if (limit > 0) "/$limit" else ""} awarded tenders.")
                        return ScrapeResult(newTenderIds = newTenderIds, stopped = true)
                    }

                    val tenderObj = dataArray.getJSONObject(i)
                    val tenderNo = tenderObj.getString("tender_No")
                    val description = tenderObj.getString("description")
                    val awardedTender = AwardedTender.fromJson(tenderObj)

                    // Check if we already have this tender
                    if (fileManager.hasTenderFolder(tenderNo)) {
                        if (awardedTender.companyName.isNotBlank()) {
                            saveAwardedTenderToCacheInternal(awardedTender)
                            Log.d("AwardedScraperDebug", "Refreshed awarded tender cache for existing tender $tenderNo: $awardedTender")
                        }
                        Log.d("AwardedScraperDebug", "Skipping already-scraped awarded tender: $tenderNo - $description")
                        onStatus?.invoke("Skipping existing awarded tender $tenderNo. Saved $processed${if (limit > 0) "/$limit" else ""} awarded tenders so far...")
                        continue
                    }

                    Log.d("AwardedScraperDebug", "Processing awarded tender: $tenderNo - $description")
                    onStatus?.invoke(
                        "Saving awarded tender ${processed + 1}${if (limit > 0) "/$limit" else ""}: $tenderNo",
                    )

                    try {
                        val folder = fileManager.getTenderFolder(tenderNo)
                        fileManager.writeManifest(folder, tenderObj.toString(2))

                        if (awardedTender.companyName.isNotBlank()) {
                            saveAwardedTenderToCacheInternal(awardedTender)
                            Log.d("AwardedScraperDebug", "Saved awarded tender $tenderNo: $awardedTender")
                        }

                        newTenderIds.add(tenderNo)
                        processed++

                        onNewTenderSaved?.invoke(tenderNo)

                        // Small delay to be respectful to the server
                        Thread.sleep(200)

                    } catch (e: Exception) {
                        Log.e("AwardedScraperError", "Failed to save awarded tender $tenderNo", e)
                        processed++
                    }
                }

                start += batchSize
            }

            onStatus?.invoke("Completed scraping awarded tenders. Saved $processed awarded tenders.")
            return ScrapeResult(newTenderIds = newTenderIds)

        } catch (e: Exception) {
            Log.e("AwardedScraperError", "Failed to scrape awarded tenders", e)
            return ScrapeResult(newTenderIds = newTenderIds, failureMessage = e.message)
        }
    }

    private fun extractCompanyDetailsFromAwardedTender(tenderObj: JSONObject): Map<String, String> {
        val details = mutableMapOf<String, String>()

        try {
            tenderObj.optJSONArray("company")?.optJSONObject(0)?.let { companyObj ->
                val companyName = companyObj.optString("company", "")
                if (companyName.isNotBlank()) {
                    details["companyName"] = companyName.trim()
                }

                val companyContact = companyObj.optString("contactPerson", "")
                if (companyContact.isNotBlank()) {
                    details["contactPerson"] = companyContact.trim()
                }

                val companyEmail = companyObj.optString("updatedBy", "")
                if (companyEmail.isNotBlank() && companyEmail.contains("@")) {
                    details["email"] = companyEmail.trim()
                }

                val companyBidAmount = companyObj.optString("tenderAmount", "")
                if (companyBidAmount.isNotBlank()) {
                    details["bidAmount"] = companyBidAmount.trim()
                }
            }

            // For awarded tenders, look for winner/awardee information
            // The API response might contain fields like "awardee", "winningCompany", "supplier", etc.
            val possibleCompanyFields = listOf(
                "awardee", "winningCompany", "supplier", "company", "organization",
                "awardedTo", "successfulBidder", "contractor"
            )

            for (field in possibleCompanyFields) {
                val companyName = tenderObj.optString(field, "")
                if (companyName.isNotBlank()) {
                    details["companyName"] = companyName.trim()
                    break
                }
            }

            // Look for contact person
            val possibleContactFields = listOf(
                "contactPerson", "contact", "representative", "person", "contactName"
            )

            for (field in possibleContactFields) {
                val contactPerson = tenderObj.optString(field, "")
                if (contactPerson.isNotBlank()) {
                    details["contactPerson"] = contactPerson.trim()
                    break
                }
            }

            // Look for phone
            val possiblePhoneFields = listOf(
                "phone", "telephone", "tel", "contactNumber", "phoneNumber"
            )

            for (field in possiblePhoneFields) {
                val phone = tenderObj.optString(field, "")
                if (phone.isNotBlank()) {
                    details["phone"] = phone.trim()
                    break
                }
            }

            // Look for email
            val possibleEmailFields = listOf(
                "email", "emailAddress", "contactEmail"
            )

            for (field in possibleEmailFields) {
                val email = tenderObj.optString(field, "")
                if (email.isNotBlank() && email.contains("@")) {
                    details["email"] = email.trim()
                    break
                }
            }

            val industry = tenderObj.optString("category", "")
            if (industry.isNotBlank()) {
                details["industry"] = industry.trim()
            }

            val bidAmount = tenderObj.optString("tenderAmount", "")
            if (bidAmount.isNotBlank()) {
                details["bidAmount"] = bidAmount.trim()
            }

            val awardContact = tenderObj.optString("awardContact", "")
            if (awardContact.isNotBlank() && !details.containsKey("contactPerson")) {
                details["contactPerson"] = awardContact.trim()
            }

            // If no structured data found, try to extract from description or other fields
            if (details.isEmpty()) {
                val description = tenderObj.optString("description", "")
                val allText = tenderObj.toString()

                // Simple regex patterns for contact info
                val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}")
                val phoneRegex = Regex("\\+?\\d{1,4}?[-.\\s]?\\(?\\d{1,3}\\)?[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,4}[-.\\s]?\\d{1,4}")

                emailRegex.find(allText)?.let { match ->
                    details["email"] = match.value
                }

                phoneRegex.find(allText)?.let { match ->
                    details["phone"] = match.value
                }

                // Try to extract company name from description
                val companyPatterns = listOf(
                    Regex("awarded to ([^,.]+)", RegexOption.IGNORE_CASE),
                    Regex("successful bidder ([^,.]+)", RegexOption.IGNORE_CASE),
                    Regex("contract awarded to ([^,.]+)", RegexOption.IGNORE_CASE)
                )

                for (pattern in companyPatterns) {
                    pattern.find(description)?.let { match ->
                        val companyName = match.groupValues[1].trim()
                        if (companyName.isNotBlank()) {
                            details["companyName"] = companyName
                            break
                        }
                    }
                }
            }

        } catch (e: Exception) {
            Log.e("AwardedScraperError", "Failed to extract company details from awarded tender", e)
        }

        return details
    }

    private fun saveAwardedTenderToCacheInternal(awardedTender: AwardedTender) {
        try {
            val awardedTendersFile = File(context.filesDir, "companies_cache.json")
            val awardedTendersData = if (awardedTendersFile.exists()) {
                JSONObject(awardedTendersFile.readText())
            } else {
                JSONObject()
            }

            awardedTendersData.put(awardedTender.cacheKey(), awardedTender.toJson())
            awardedTendersFile.writeText(awardedTendersData.toString(2))
        } catch (e: Exception) {
            Log.e("AwardedScraperError", "Failed to save awarded tender to cache", e)
        }
    }

    private fun extractPdfText(file: File): String {
        val document = PDDocument.load(file)
        return try {
            PDFTextStripper().getText(document)
        } finally {
            document.close()
        }
    }

    private fun countPdfPages(file: File): Int {
        val document = PDDocument.load(file)
        return try {
            document.numberOfPages
        } finally {
            document.close()
        }
    }

    private fun shouldUseOcrFallback(text: String, pageCount: Int): Boolean {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        val minimumExpectedChars = maxOf(OCR_FALLBACK_MIN_TEXT_CHARS, pageCount * OCR_FALLBACK_MIN_TEXT_PER_PAGE)
        return normalized.length < minimumExpectedChars
    }

    private fun extractPdfTextWithMlKit(file: File): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val pages = mutableListOf<String>()
        var fileDescriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null

        try {
            fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(fileDescriptor)
            val pageCount = min(renderer.pageCount, OCR_MAX_PAGES)

            for (pageIndex in 0 until pageCount) {
                val page = renderer.openPage(pageIndex)
                try {
                    val bitmap =
                        Bitmap.createBitmap(
                            page.width * OCR_RENDER_SCALE,
                            page.height * OCR_RENDER_SCALE,
                            Bitmap.Config.ARGB_8888,
                        )
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        val result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)))
                        val pageText = result.text.trim()
                        if (pageText.isNotBlank()) {
                            pages += "PAGE ${pageIndex + 1}\n$pageText"
                        }
                    } finally {
                        bitmap.recycle()
                    }
                } finally {
                    page.close()
                }
            }
        } catch (e: Exception) {
            Log.e("ScraperError", "ML Kit OCR fallback failed for ${file.name}", e)
        } finally {
            renderer?.close()
            fileDescriptor?.close()
            recognizer.close()
        }

        return pages.joinToString(separator = "\n\n")
    }

    fun generateManifest(rawText: String): String {
        val prompt = "Extract the following fields from this tender text into a single JSON object: referenceNumber, procuringEntity, closingDate, and cidbGrade. Return ONLY valid JSON.\n\n$rawText"
        // TODO: Call the InferenceModel (Gemma 4 E4B) with the prompt
        // For now, return a placeholder JSON
        return "{\"referenceNumber\":\"PLACEHOLDER\",\"procuringEntity\":\"PLACEHOLDER\",\"closingDate\":\"PLACEHOLDER\",\"cidbGrade\":\"PLACEHOLDER\"}"
    }

    private fun generateManifest(rawText: String, tenderNumber: String, title: String): String {
        return """
        {
            "tender_number": "$tenderNumber",
            "title": "$title",
            "extracted_text": "$rawText"
        }
        """.trimIndent()
    }

    fun syncTenderToFirebase(tenderFolder: File) {
        try {
            val storage = FirebaseStorage.getInstance()
            val folderName = tenderFolder.name
            val storageRef = storage.reference.child("tenders/$folderName")

            tenderFolder.listFiles()?.forEach { file ->
                val fileRef = storageRef.child(file.name)
                val uploadTask = fileRef.putFile(Uri.fromFile(file))
                // Note: In a real app, handle upload success/failure with listeners
                uploadTask.addOnSuccessListener {
                    // Upload successful
                }.addOnFailureListener { exception ->
                    // Handle failure
                    exception.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun extractText(file: File): String {
        val pdfText = extractPdfText(file)
        val pageCount = countPdfPages(file)
        if (!shouldUseOcrFallback(pdfText, pageCount)) {
            return pdfText
        }

        Log.d("ScraperDebug", "Using ML Kit OCR fallback for ${file.name}")
        val ocrText = extractPdfTextWithMlKit(file)
        return when {
            ocrText.isBlank() -> pdfText
            pdfText.isBlank() -> ocrText
            ocrText.length > pdfText.length -> ocrText
            else -> listOf(pdfText.trim(), ocrText.trim()).joinToString(separator = "\n\n")
        }
    }
}