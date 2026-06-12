# Phase 2 Implementation Plan — JGS Tender Pipeline

**Prepared:** 12 June 2026  
**Based on:** Gold Standard Status Report + Android multi-format bug analysis

---

## Contents

1. [Stream A — Pipeline Gaps from Status Report](#stream-a)
2. [Stream B — Android Multi-Format Document Fix](#stream-b)
3. [Stream C — Desktop Pipeline UI](#stream-c)
4. [Delivery Sequence & Effort Estimates](#delivery)

---

<a name="stream-a"></a>
## Stream A — Outstanding Pipeline Gaps

These are the items marked 🟡 or ❌ in the Gold Standard Status Report.

---

### A1 — `requirements.txt` for Desktop Pipeline

**Problem:** There is no `requirements.txt`. Any new environment or CI server needs to manually guess dependencies.

**Fix:** Create `desktop_pipeline/requirements.txt` listing every library the pipeline uses.

```
# desktop_pipeline/requirements.txt
pydantic>=2.7.0
instructor>=1.4.0
openai>=1.30.0
python-docx>=1.1.0
openpyxl>=3.1.0
python-pptx>=0.6.23
pypdf>=4.2.0
docling>=2.5.0
magic-pdf>=3.1.0   # MinerU — enables cross-page table merging for scanned PDFs
```

**Files to create/edit:**
- **NEW** `desktop_pipeline/requirements.txt`

---

### A2 — Activate MinerU Native Cross-Page Table Merging

**Problem:** `MinerUParser.py` attempts `import magic_pdf` but silently skips it. Real MinerU is never used — the parser falls back to basic Python libraries that cannot perform cross-page table merging (critical for SBD 3.1 pricing schedules that span multiple pages).

**Fix:** After MinerU is installed (via `requirements.txt`), update `MinerUParser.py` to properly invoke the `magic_pdf` CLI pipeline and return its HTML table output.

**Files to edit:**
- `desktop_pipeline/parser/MinerUParser.py` — replace the silent `try/except ImportError` skip with a real `subprocess` call to the MinerU pipeline when `magic_pdf` is available.

---

### A3 — Semantic Embedding Upgrade for Vector Index

**Problem:** `SimpleVectorIndex` uses TF-IDF cosine similarity. This works for keyword-heavy procurement text but fails when the query uses synonyms the document does not literally contain (e.g. query for "briefing compulsory" against text that says "mandatory site meeting").

**Fix:** Add an optional semantic embedding path using `sentence-transformers` (`all-MiniLM-L6-v2`, ~23 MB, CPU-friendly). Fall back to TF-IDF when sentence-transformers is not installed.

**Files to edit:**
- `desktop_pipeline/pipeline/extractor.py` — add `SemanticVectorIndex` class that uses cosine similarity on sentence embeddings, with a fallback import guard.

---

### A4 — OCDS Release Schema Mapping Layer

**Problem:** The PDF dedicates an entire chapter (pages 14–15) to OCDS interoperability. Nothing is implemented.

**What to build:** A post-processing module `pipeline/ocds_mapper.py` that accepts the `gemma-manifest-enrichment.json` output and transforms it into a valid OCDS Release Schema v1.1.5 JSON object, ready to be compiled with `ocdskit`.

**Mapping table:**

| Our Field | OCDS Target |
|---|---|
| `tenderId` | `ocid` (prefixed with `ocds-jgs-`) |
| `tender_metadata_tender_reference_number` | `tender.id` |
| `tender_metadata_tender_title` | `tender.title` |
| `tender_metadata_issuing_institution` | `buyer.name` |
| `critical_dates_closing_date_time` | `tender.tenderPeriod.endDate` |
| `critical_dates_compulsory_briefing_briefing_date_time` | `tender.enquiryPeriod.endDate` |
| `financial_criteria_estimated_tender_value_zar` | `tender.value.amount` |
| `tender_metadata_geographic_locality_province` | `tender.deliveryLocations[0].description` |
| `preferential_procurement_scoring_system_applicable` | `tender.milestones[]` (custom extension) |
| `industry_credentials_cidb_requirements_minimum_grade` | `tender.eligibilityCriteria` |

**Files to create:**
- **NEW** `desktop_pipeline/pipeline/ocds_mapper.py`

**Files to edit:**
- `desktop_pipeline/run_enrichment.py` — add Step 8: call `ocds_mapper.build_ocds_release()` and save `ocds-release.json` next to `gemma-manifest-enrichment.json`.

---

### A5 — POPIA PII Redaction Before Firebase Upload

**Problem:** Contact details (names, emails, phone numbers) scraped from tender documents are uploaded raw to Firebase without any POPIA-compliant redaction step.

**Fix:** Add a `pipeline/pii_redactor.py` utility that strips or hashes direct contact name fields from the `contactDetails.contactLines` array before the Firebase upload marker is set. Email addresses and phone numbers used for bidder enquiries are publicly advertised and are exempt, but named individuals (e.g. `contact_person`) should be replaced with `[REDACTED]` unless the tender explicitly publishes that person's details as the public enquiry contact.

**Files to create:**
- **NEW** `desktop_pipeline/pipeline/pii_redactor.py`

**Files to edit:**
- `desktop_pipeline/run_enrichment.py` — call `pii_redactor.redact_contact_names()` on the payload before saving `gemma-manifest-enrichment.json`.

---

<a name="stream-b"></a>
## Stream B — Android Multi-Format Document Fix

> [!IMPORTANT]
> **Root Cause Identified:** In [`TenderAutomationProcessor.kt`](file:///C:/Users/HFX/Desktop/Jillian%20Projects/jgs/Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/TenderAutomationProcessor.kt), two functions control which files are processed:
>
> **`isGemmaReadableFile()`** (line 841) — accepts only `pdf`, `txt`, `md`, `csv`.  
> **`extractTextForGemma()`** (line 828) — calls `scraper.extractText()` for PDF, `file.readText()` for txt/md/csv, and **returns `""` for everything else**.
>
> When a tender folder contains only a `.docx` file (like `00393_26`), `hasGemmaReadableDocuments()` returns `false` if no PDF is present alongside it, causing the enrichment to be skipped entirely and only the bare manifest uploaded.

---

### B1 — Add Apache POI for DOCX + XLSX Parsing (Android)

**Problem:** No Android library for DOCX or XLSX parsing is present in `build.gradle.kts`. The app needs `Apache POI` (Android-compatible variant) to extract text from these formats on-device.

**Recommended library:** `org.apache.poi:poi-ooxml` with the Android-compatible shaded JAR, or the lighter-weight alternative `com.github.SUPERCILEX.poi-android`.

**Files to edit:**
- `Android/src/app/build.gradle.kts` — add dependencies:
  ```kotlin
  // DOCX / XLSX text extraction
  implementation("org.apache.poi:poi-ooxml:5.2.5") {
      exclude(group = "org.bouncycastle")  // conflicts with Android
  }
  ```
- `Android/src/gradle/libs.versions.toml` — add `poi_ooxml` version alias.

> [!WARNING]
> Apache POI `poi-ooxml` is ~15 MB. If APK size is a concern, use the lighter `docx4j-android` fork (~4 MB for DOCX-only) and `FastExcel` (~500 KB for XLSX-only). Both are confirmed Android-compatible as of 2026.

---

### B2 — Implement DOCX Text Extractor on Android

**What to build:** A Kotlin utility object `DocxExtractor` inside the `data` package that reads a `.docx` file using Apache POI and returns clean plain text, preserving paragraph order.

**Implementation sketch:**

```kotlin
// Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DocxExtractor.kt
object DocxExtractor {
    fun extractText(file: File): String {
        return try {
            XWPFDocument(file.inputStream()).use { doc ->
                doc.paragraphs
                    .filter { it.text.isNotBlank() }
                    .joinToString("\n") { it.text.trim() }
            }
        } catch (e: Exception) {
            Log.e("DocxExtractor", "Failed to extract DOCX text from ${file.name}", e)
            ""
        }
    }
}
```

**Files to create:**
- **NEW** `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/DocxExtractor.kt`

---

### B3 — Implement XLSX Text Extractor on Android

**What to build:** A Kotlin utility object `XlsxExtractor` that reads `.xlsx` files using Apache POI (or FastExcel) and returns tab-delimited plain text per row, preserving table structure.

**Files to create:**
- **NEW** `Android/src/app/src/main/java/com/google/ai/edge/gallery/data/XlsxExtractor.kt`

---

### B4 — Wire New Extractors into `TenderAutomationProcessor`

**What to change:** Update the two functions in `TenderAutomationProcessor.kt`:

**`isGemmaReadableFile()`** — expand the accepted extensions:
```kotlin
// BEFORE (line 842-844):
"pdf", "txt", "md", "csv" -> true
else -> false

// AFTER:
"pdf", "txt", "md", "csv", "docx", "xlsx", "xls" -> true
else -> false
```

**`extractTextForGemma()`** — add DOCX and XLSX branches:
```kotlin
// AFTER (add to when block):
"docx" -> DocxExtractor.extractText(file)
"xlsx", "xls" -> XlsxExtractor.extractText(file)
```

**Files to edit:**
- `Android/src/app/src/main/java/com/google/ai/edge/gallery/worker/TenderAutomationProcessor.kt`
  - Lines 830–834: `extractTextForGemma()` — add `docx` and `xlsx` branches
  - Lines 842–844: `isGemmaReadableFile()` — add `docx`, `xlsx`, `xls` to accepted set

---

### B5 — Rebuild and Regression Test

After B1–B4 are complete:

1. Run `.\gradlew.bat assembleDebug` in `Android/src/`
2. Install APK on device via `android run`
3. Trigger enrichment on a tender folder containing only a `.docx` file (e.g. `00393_26`)
4. Verify `gemma-manifest-enrichment.json` is written with non-empty field values
5. Verify Firebase upload completes

---

<a name="stream-c"></a>
## Stream C — Desktop Pipeline UI

**Goal:** A clean, standalone web-based UI that wraps `run_enrichment.py` so non-technical operators can run the pipeline on a tender folder without touching the command line. It should also display the `gemma-manifest-enrichment.json` output visually.

---

### C1 — Technology Choice

| Option | Verdict |
|---|---|
| Plain HTML + CSS + JS served by Python `http.server` | ✅ **Recommended** — zero additional dependencies, ships alongside the existing pipeline |
| React/Vite SPA | Overkill — adds node_modules to a Python project |
| Streamlit | Good alternative if you want Python-only, but adds a large dependency |

**Decision:** Single-file HTML/CSS/JS dashboard served by a lightweight Python Flask server (`app.py`). Flask is a single `pip install flask` addition.

---

### C2 — UI Pages / Screens

#### Screen 1 — Dashboard (Home)

```
┌─────────────────────────────────────────────────────┐
│  🔷 JGS Tender Intelligence Pipeline                │
│  ─────────────────────────────────────────────────  │
│  [📁 Browse Tender Folder]  [▶ Run Enrichment]      │
│                                                     │
│  Selected: C:\...\00393_26\                         │
│                                                     │
│  ┌────────────── Live Log ─────────────────────┐    │
│  │  [INFO] Parsing 1 document(s)...           │    │
│  │  [INFO] Chunked into 24 hierarchical chunks│    │
│  │  [INFO] Stage 1 Classification complete    │    │
│  │  [INFO] Stage 2 Extraction complete        │    │
│  │  [SUCCESS] Enrichment complete ✅          │    │
│  └─────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────┘
```

#### Screen 2 — Enrichment Results Viewer

```
┌─────────────────────────────────────────────────────────────┐
│  📋 Tender 00393_26 — Enrichment Results                    │
│  Generated: 2026-06-12 03:15  │  Fields: 39  │  ⚠ 10       │
│  ─────────────────────────────────────────────────────────  │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ 🟢 Tender Reference Number                           │   │
│  │    00393/26                                          │   │
│  │    Evidence: "BID No. 00393/26"                      │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │ 🟢 Closing Date                                      │   │
│  │    2026-07-15T11:00:00                               │   │
│  │    Evidence: "Bids must be received by..."           │   │
│  ├──────────────────────────────────────────────────────┤   │
│  │ 🔴 Geographic Province                              │   │
│  │    Not found                             ⚠ WARNING  │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  📧 Contacts                                                │
│  kea@joburgtheatre.com   ntombizodwa@joburgtheatre.com      │
│  📞 0800007277                                              │
│                                                             │
│  [📥 Export JSON]  [🔁 Re-run Enrichment]                   │
└─────────────────────────────────────────────────────────────┘
```

#### Screen 3 — Batch Mode (folder of tender subfolders)

```
┌─────────────────────────────────────────────────────┐
│  Batch Enrichment                                   │
│  ─────────────────────────────────────────────────  │
│  [📁 Select Root Folder]                            │
│                                                     │
│  Subfolders detected:                               │
│  ✅ 00393_26  (already enriched — skip)             │
│  ⏳ 00450_26  (pending)                             │
│  ⏳ 00512_26  (pending)                             │
│                                                     │
│  Progress: ████████░░░░  2/3 tenders                │
└─────────────────────────────────────────────────────┘
```

---

### C3 — File Structure for Desktop UI

```
desktop_pipeline/
├── ui/
│   ├── app.py                   ← Flask server, exposes REST endpoints
│   ├── static/
│   │   ├── index.html           ← Single-page dashboard
│   │   ├── style.css            ← Design system (dark mode, glassmorphism)
│   │   └── app.js               ← All UI logic (fetch, DOM updates, SSE)
│   └── templates/               ← (empty — HTML is served from static/)
├── run_enrichment.py            ← Existing (unchanged)
├── requirements.txt             ← Updated to add flask
└── ...
```

---

### C4 — Flask API Endpoints

| Method | Endpoint | Purpose |
|---|---|---|
| `GET` | `/api/status` | Health check, returns pipeline version |
| `POST` | `/api/run` | Triggers `run_enrichment.py` on a given folder path; returns `task_id` |
| `GET` | `/api/run/<task_id>/stream` | **Server-Sent Events (SSE)** — streams live log lines from the subprocess |
| `GET` | `/api/results?folder=<path>` | Reads and returns `gemma-manifest-enrichment.json` from a folder |
| `GET` | `/api/batch?root=<path>` | Lists sub-folders, their enrichment status (done/pending) |
| `POST` | `/api/batch/run` | Runs enrichment on all pending sub-folders sequentially |

---

### C5 — Design System

| Token | Value |
|---|---|
| Background | `#0d1117` (GitHub dark) |
| Surface | `#161b22` |
| Surface elevated | `rgba(255,255,255,0.05)` glassmorphism card |
| Accent | `#2ea043` (green — success/DONE) |
| Warning | `#d29922` (amber) |
| Danger | `#f85149` (red — critical WARNING) |
| Font | `Inter` (Google Fonts) |
| Border radius | `12px` |
| Animation | Smooth 200ms ease-in-out on all state changes |

**Status dot mapping:**
- 🟢 `DONE` + evidence present → green pill
- 🟡 `DONE` + no evidence → amber pill (extracted but unverified)
- 🔴 `WARNING` → red pill + ⚠ badge
- 🔵 Classification fields → blue pill (no evidence expected)

---

### C6 — Live Log Streaming (SSE)

The Flask endpoint `/api/run/<task_id>/stream` uses **Server-Sent Events** to push log lines to the browser in real-time as `run_enrichment.py` runs in a subprocess. This avoids websockets (no extra library needed) and gives the operator live feedback without polling.

```python
# Pseudocode — app.py
@app.route("/api/run/<task_id>/stream")
def stream_log(task_id):
    def generate():
        proc = subprocess.Popen(
            ["python", "run_enrichment.py", folder_path],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True
        )
        for line in proc.stdout:
            yield f"data: {json.dumps({'log': line.rstrip()})}\n\n"
        yield f"data: {json.dumps({'done': True})}\n\n"
    return Response(generate(), mimetype="text/event-stream")
```

---

<a name="delivery"></a>
## Delivery Sequence & Effort Estimates

| # | Task | Stream | Est. Effort |
|---|---|---|---|
| 1 | `requirements.txt` | A1 | 15 min |
| 2 | Android: add POI dependency + `DocxExtractor.kt` | B1–B2 | 2 hrs |
| 3 | Android: add `XlsxExtractor.kt` | B3 | 1 hr |
| 4 | Android: wire both into `TenderAutomationProcessor` | B4 | 30 min |
| 5 | Android: rebuild + regression test | B5 | 1 hr |
| 6 | Desktop UI: `app.py` Flask server + SSE | C3–C6 | 3 hrs |
| 7 | Desktop UI: `index.html` / `style.css` / `app.js` | C2–C5 | 4 hrs |
| 8 | MinerU native activation | A2 | 1 hr |
| 9 | Semantic embedding upgrade | A3 | 2 hrs |
| 10 | OCDS mapper | A4 | 3 hrs |
| 11 | POPIA redactor | A5 | 1 hr |

### Recommended Order

```
Phase 2a (Fixes — do first):
  A1 → B1 → B2 → B3 → B4 → B5

Phase 2b (UI — high value, fast):
  C3 → C4 → C5 → C6 (app.py + static files)

Phase 2c (Gold Standard extras):
  A2 → A3 → A4 → A5
```

> [!TIP]
> Start with **B1–B5** immediately — it is the highest-impact fix. Every tender folder that contains only a `.docx` (which is the majority of scraped tenders) is currently being skipped on Android.
