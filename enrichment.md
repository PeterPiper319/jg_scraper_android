# Tender Enrichment Pipeline Documentation

This document explains the architecture, logic, and schemas of the updated South African Tender Enrichment pipeline. It is designed to serve as a comprehensive reference for discussion with Gemini.

---

## 1. Core Architecture Overview

The enrichment pipeline has been transitioned from an **on-device local model** (Gemma) to a **remote MoE model** (OpenRouter Llama 4 Scout, model ID: `meta-llama/llama-4-scout`) combined with a custom **on-device keyword search index** (TF-IDF Vector Index).

This hybrid approach allows the application to achieve highly accurate data extraction over large document sets (such as multi-page PDFs, bills of quantities, and pricing schedules) without requiring the installation of multi-GB local model binaries.

```
                  ┌──────────────────────────────┐
                  │   Tender PDF Document Files  │
                  └──────────────┬───────────────┘
                                 │
                                 ▼ (Page chunking & tokenization)
                  ┌──────────────────────────────┐
                  │     TenderVectorIndex        │
                  │   (TF-IDF Search Engine)     │
                  └──────────────┬───────────────┘
                                 │
         ┌───────────────────────┴───────────────────────┐
         ▼ (Retrieve top 3 chunks)                       ▼ (Retrieve top 6 chunks)
┌─────────────────────────────────┐             ┌─────────────────────────────────┐
│     Stage 1: Classification     │             │    Stage 2: Deep Extraction     │
│   (Document Type & Industry)    │             │       (Complete Specs Schema)   │
└────────────────┬────────────────┘             └────────────────┬────────────────┘
                 │ (Prompt classification)                       │ (Prompt extraction)
                 └───────────────┬───────────────────────────────┘
                                 │
                                 ▼
                    ┌──────────────────────────┐
                    │ OpenRouter Llama 4 Scout │
                    └────────────┬─────────────┘
                                 │
                                 ▼ (JSON parse & normalize)
                    ┌──────────────────────────┐
                    │  gemma-manifest-         │
                    │  enrichment.json         │
                    └──────────────────────────┘
```

---

## 2. Step-by-Step Execution Flow

When a tender is enriched, the `TenderAutomationProcessor` executes the following sequence:

### Step 1: Document Chunking & Local Vector Indexing
The text extracted from all downloaded tender PDFs is split into overlapping chunks of **1,000 characters** with a **200-character overlap**. 
A local `TenderVectorIndex` is instantiated from these chunks. It normalizes terms (removing standard stop words) and computes TF-IDF scores for every term across the documents.

### Step 2: Stage 1 - Classification (Document Type & Industry)
The pipeline retrieves the top 3 chunks most relevant to terms like `"tender advert type, industry category, services scope"`. It sends these chunks to Llama 4 Scout alongside the schema definitions in [`industry.json`](file:///C:/Users/HFX/Desktop/Jillian Projects/jgs/Android/src/app/src/main/assets/industry.json).
- **Document Type Classification**: The model determines if the document is a `"Tender"` (complete RFQ/RFP with compliance rules) or an `"Advert"` (brief notice).
- **Industry Classification**: Matches the document to one of the strict industry IDs (e.g. `it_telecom`, `construction_civil`, `manufacturing`, etc.) and returns the corresponding specializations, skills, and capabilities found in the text.

### Step 3: Stage 2 - Deep Schema Extraction
The pipeline performs a vector search using key terms representing the entire target schema:
`"CIDB grading class of work, briefing date venue compulsory, submission box address portal, tax compliance CSD SBD forms, estimated value budget, functionality evaluation criteria threshold"`
The top 6 chunks are retrieved (providing around 5,000–6,000 characters of high-signal context). These chunks are sent to Llama 4 Scout along with the schema defined in [`tender.json`](file:///C:/Users/HFX/Desktop/Jillian Projects/jgs/Android/src/app/src/main/assets/tender.json) to extract specific administrative, financial, and technical constraints.

### Step 4: Normalization & Flat JSON Assembly
The extracted values from Stage 1 and Stage 2 are parsed and normalized. Any blank or `"null"` strings are standardized to `"Not found"`. 
A flat JSON payload matching [`enrichment_example.md`](file:///C:/Users/HFX/Desktop/Jillian Projects/jgs/enrichment_eample.md) is created containing:
- **`generatedAt`**: Timestamp of generation.
- **`tenderId`**: ID of the tender.
- **`fields`**: Flat array of key-value definitions with labels, evidence fields, and status flags (e.g. `"status": "WARNING"` for missing critical fields).
- **`criticalFieldsNeedingReview`**: An array listing all critical fields that returned `"Not found"`.

### Step 5: Manifest Merge & Firebase Sync
Top-level variables (like `title`, `organ_of_State`, `cidb_grading`, `preference_points`, `briefingDate`) are promoted directly to `manifest.json`. The updated tender folder is then marked as uploaded and synchronized to Firebase.

---

## 3. Schemas Used in the Pipeline

### A. [`industry.json`](file:///C:/Users/HFX/Desktop/Jillian Projects/jgs/Android/src/app/src/main/assets/industry.json)
Specifies the taxonomy of the 14 industries supported by the app. Each industry defines:
- Snake_case `id` and display `name`.
- Lists of `specializations`, `skills`, and `capabilities` typical of that industry.

### B. [`tender.json`](file:///C:/Users/HFX/Desktop/Jillian Projects/jgs/Android/src/app/src/main/assets/tender.json)
The target JSON schema representing the extracted tender data. Key sections include:
- `tender_metadata` (reference number, institution type, category, locality).
- `critical_dates` (briefing dates, closing date, validity).
- `submission_mechanics` (submission method, box address, copy requirements).
- `administrative_compliance` (CSD, SARS, CIPC, COIDA).
- `statutory_forms` (SBD / MBD checklist).
- `preferential_procurement` (scoring systems like 80/20 or 90/10).
- `industry_credentials` (CIDB grades, class of work, professional body registrations).
- `financial_criteria` (ZAR value, financials requirements).
- `technical_functionality` (minimum threshold, evaluation criteria matrix).
