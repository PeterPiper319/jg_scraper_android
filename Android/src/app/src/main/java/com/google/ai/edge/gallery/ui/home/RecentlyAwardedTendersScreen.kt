package com.google.ai.edge.gallery.ui.home

import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.data.AwardedTender
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.scraper.TenderScraperViewModel
import java.io.File
import org.json.JSONObject

@Composable
fun RecentlyAwardedTendersScreen(
    modelManagerViewModel: ModelManagerViewModel,
    navigateToScraper: () -> Unit,
) {
    val context = LocalContext.current
    val tenderScraperViewModel: TenderScraperViewModel = hiltViewModel()
    val scraperUiState by tenderScraperViewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var awardedTenders by remember { mutableStateOf<List<AwardedTender>>(emptyList()) }

    LaunchedEffect(Unit) {
        awardedTenders = loadAwardedTendersCache(context)
    }

    LaunchedEffect(scraperUiState.scrapeStatus) {
        if (!scraperUiState.isScraping && scraperUiState.scrapeStatus.contains("Completed", ignoreCase = true)) {
            awardedTenders = loadAwardedTendersCache(context)
        }
    }

    val filteredAwardedTenders = remember(awardedTenders, searchQuery) {
        val query = searchQuery.trim()
        if (query.isBlank()) {
            awardedTenders
        } else {
            awardedTenders.filter { tender ->
                tender.awardID.toString().contains(query, ignoreCase = true) ||
                    tender.companyName.contains(query, ignoreCase = true) ||
                    tender.contactPerson.contains(query, ignoreCase = true) ||
                    tender.email.contains(query, ignoreCase = true) ||
                    tender.telNo.contains(query, ignoreCase = true) ||
                    tender.industry.contains(query, ignoreCase = true) ||
                    tender.bidAmount.contains(query, ignoreCase = true) ||
                    tender.tenderNo.contains(query, ignoreCase = true)
            }
        }
    }

    FirebaseTenderScreenScaffold(
        title = "Recently Awarded Tenders",
        currentPage = FirebaseTenderPage.BROWSER,
        navigateToScraper = navigateToScraper,
        navigateToFirebaseBrowser = {},
        navigateToFirebaseEnrichment = {},
        isBusy = scraperUiState.isScraping,
        searchQuery = searchQuery,
        onSearchQueryChanged = { searchQuery = it },
        onRefresh = {
            awardedTenders = loadAwardedTendersCache(context)
        },
        searchLabel = "Search company, contact, email, tel, award ID",
        listStatus = "Showing ${filteredAwardedTenders.size} awarded tenders",
        emptyMessage = "No awarded tenders match the current search.",
        extraActions = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        Log.d("RecentlyAwardedTendersScreen", "Scrape Awarded Tenders button clicked")
                        Toast.makeText(context, "Starting awarded tenders scraping from portal...", Toast.LENGTH_SHORT).show()
                        tenderScraperViewModel.scrapeCompanyContactsForAwardedTenders()
                    },
                    enabled = !scraperUiState.isScraping,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Scrape Awarded Tenders")
                }
                Button(
                    onClick = { tenderScraperViewModel.requestStopScraper() },
                    enabled = scraperUiState.isScraping,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Stop")
                }
            }
        },
        tenderIds = filteredAwardedTenders.map { it.awardID.toString() },
        cardContent = { tenderId ->
            filteredAwardedTenders.firstOrNull { it.awardID.toString() == tenderId }?.let { tender ->
                AwardedTenderCard(tender = tender)
            }
        },
    )
}

@Composable
internal fun AwardedTenderCard(
    tender: AwardedTender,
) {
    val context = LocalContext.current

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = tender.companyName.uppercase(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
            )

            if (tender.webAddress.isNotBlank()) {
                Text(
                    text = "Web Address: ${tender.webAddress}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable {
                        val rawUrl = tender.webAddress.trim()
                        val normalizedUrl = when {
                            rawUrl.startsWith("http://", ignoreCase = true) -> rawUrl
                            rawUrl.startsWith("https://", ignoreCase = true) -> rawUrl
                            else -> "https://$rawUrl"
                        }
                        val browserIntent = Intent(Intent.ACTION_VIEW).apply {
                            data = Uri.parse(normalizedUrl)
                        }
                        context.startActivity(browserIntent)
                    },
                )
            } else {
                Text(
                    text = "Web Address: Not listed on eTenders",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (tender.industry.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(
                        text = tender.industry,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }

            if (tender.tenderNo.isNotBlank()) {
                Text(
                    text = "Tender No: ${tender.tenderNo}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (tender.contactPerson.isNotBlank()) {
                Text(
                    text = "Contact Person: ${tender.contactPerson}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (tender.telNo.isNotBlank()) {
                Text(
                    text = "Tel No: ${tender.telNo}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                    modifier = Modifier.clickable {
                        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:${Uri.encode(tender.telNo)}")
                        }
                        context.startActivity(dialIntent)
                    },
                )
            }

            if (tender.email.isNotBlank()) {
                Text(
                    text = "Email: ${tender.email}",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                    ),
                    modifier = Modifier.clickable {
                        val emailIntent = Intent(Intent.ACTION_SENDTO).apply {
                            data = Uri.parse("mailto:${Uri.encode(tender.email)}")
                        }
                        context.startActivity(emailIntent)
                    },
                )
            } else {
                Text(
                    text = "Email: Not listed on eTenders",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

private fun loadAwardedTendersCache(context: android.content.Context): List<AwardedTender> {
    return try {
        val companiesFile = File(context.filesDir, "companies_cache.json")
        if (!companiesFile.exists()) {
            return emptyList()
        }

        val companiesData = JSONObject(companiesFile.readText())
        val awardedTenders = mutableListOf<AwardedTender>()

        companiesData.keys().forEach { key ->
            val companyJson = companiesData.getJSONObject(key)
            awardedTenders += AwardedTender.fromJson(companyJson)
        }

        awardedTenders.sortedByDescending { it.awardID }
    } catch (e: Exception) {
        Log.e("RecentlyAwardedTendersScreen", "Failed to load awarded tenders cache", e)
        emptyList()
    }
}
