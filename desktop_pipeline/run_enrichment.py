import os
import sys
import json
import logging
from typing import List

# Setup Firebase
try:
    import firebase_admin
    from firebase_admin import credentials, firestore
except ImportError:
    firebase_admin = None

# Setup Logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(name)s: %(message)s")
logger = logging.getLogger("TenderEnrichmentPipeline")

# Add current folder to path to resolve absolute imports
sys.path.append(os.path.dirname(os.path.abspath(__file__)))

from parser.ParserOrchestrator import ParserOrchestrator
from chunker.HierarchicalChunker import HierarchicalChunker
from pipeline.extractor import run_classification, run_extraction
from pipeline.normalizer import build_final_enrichment_payload
from pipeline.manifest_builder import merge_enrichment_into_manifest
from pipeline.pii_redactor import redact_contact_names
from pipeline.ocds_mapper import build_ocds_release

def run_pipeline(tender_folder: str):
    if not os.path.isdir(tender_folder):
        logger.error(f"Provided path is not a directory: {tender_folder}")
        sys.exit(1)

    tender_id = os.path.basename(os.path.normpath(tender_folder))
    logger.info(f"Starting enrichment pipeline for tender ID: {tender_id} in {tender_folder}")

    # 1. Discover readable documents
    allowed_extensions = {".pdf", ".docx", ".xlsx", ".xls", ".pptx", ".txt", ".md"}
    doc_files = []
    for f in os.listdir(tender_folder):
        file_path = os.path.join(tender_folder, f)
        if os.path.isfile(file_path):
            ext = os.path.splitext(f)[1].lower()
            if ext in allowed_extensions:
                if f.lower() not in ("manifest.json", "support-documents.json", "concept-manifest-enrichment.json"):
                    doc_files.append(file_path)

    if not doc_files:
        logger.warning(f"No parseable documents found in {tender_folder}. Skipping enrichment.")
        return

    logger.info(f"Found {len(doc_files)} document(s) for parsing: {[os.path.basename(x) for x in doc_files]}")

    # 2. Parse documents using layout-aware parser (Docling/MinerU fallbacks)
    all_chunks = []
    chunker = HierarchicalChunker(target_chunk_size=1500)

    for doc_path in doc_files:
        try:
            parsed = ParserOrchestrator.parse_file(doc_path)
            markdown = parsed["markdown"]
            tables = parsed["tables_html"]
            
            # Hierarchical chunking
            doc_chunks = chunker.chunk_document(markdown, os.path.basename(doc_path), tables)
            all_chunks.extend(doc_chunks)
            logger.info(f"Successfully chunked '{os.path.basename(doc_path)}' into {len(doc_chunks)} hierarchical chunks.")
        except Exception as e:
            logger.error(f"Failed parsing document '{os.path.basename(doc_path)}': {e}")
            continue

    if not all_chunks:
        logger.error("No text chunks could be extracted from any document. Aborting pipeline.")
        sys.exit(1)

    logger.info(f"Total chunks indexed in local SimpleVectorIndex: {len(all_chunks)}")

    # 3. Load industry.json
    # Look for industry.json in parent directory of the repository
    industry_json_path = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "industry.json"))
    if not os.path.exists(industry_json_path):
        logger.error(f"industry.json not found at {industry_json_path}")
        sys.exit(1)

    with open(industry_json_path, "r", encoding="utf-8") as f:
        industry_json_text = f.read()

    # 4. Stage 1: Classification (Document Type & Industry)
    try:
        classification = run_classification(all_chunks, industry_json_text)
        logger.info(f"Stage 1 Classification Complete: DocType={classification.document_type}, Industry={classification.classified_industry}")
    except Exception as e:
        logger.error(f"Stage 1 Classification failed: {e}")
        sys.exit(1)

    # 5. Stage 2: Deep Schema Extraction
    try:
        extraction = run_extraction(all_chunks)
        logger.info(f"Stage 2 Schema Extraction Complete: Ref={extraction.tender_metadata.tender_reference_number}")
    except Exception as e:
        logger.error(f"Stage 2 Schema Extraction failed: {e}")
        sys.exit(1)

    # 6. Normalize, Perform Audits, & Construct Enrichment Payload
    enrichment_payload = build_final_enrichment_payload(tender_id, classification, extraction, all_chunks)
    
    # 6.5. POPIA PII Redaction
    enrichment_payload = redact_contact_names(enrichment_payload)
    
    enrichment_output_path = os.path.join(tender_folder, "concept-manifest-enrichment.json")
    with open(enrichment_output_path, "w", encoding="utf-8") as f:
        json.dump(enrichment_payload, f, indent=2)
    logger.info(f"Saved manifest enrichment payload to '{enrichment_output_path}'")

    # 6.8. Export OCDS Release
    try:
        ocds_release = build_ocds_release(tender_id, enrichment_payload)
        ocds_output_path = os.path.join(tender_folder, "ocds-release.json")
        with open(ocds_output_path, "w", encoding="utf-8") as f:
            json.dump(ocds_release, f, indent=2)
        logger.info(f"Exported OCDS Release payload to '{ocds_output_path}'")
    except Exception as e:
        logger.error(f"Failed to generate OCDS Release: {e}")

    # 7. Merge fields back into manifest.json
    manifest_path = os.path.join(tender_folder, "manifest.json")
    updated_manifest = merge_enrichment_into_manifest(manifest_path, classification, extraction, enrichment_payload)
    
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(updated_manifest, f, indent=2)
    logger.info(f"Updated manifest.json saved at '{manifest_path}'")

    print("\n*** Enrichment Successful! ***")
    print(f"Document Classification: {classification.document_type}")
    print(f"Matched Industry: {classification.classified_industry}")
    print(f"Estimated Value ZAR: R{extraction.financial_criteria.estimated_tender_value_zar:,.2f}")
    print(f"Critical Fields Needing Review: {len(enrichment_payload['criticalFieldsNeedingReview'])}")

    # 8. Sync to Firestore Database
    if firebase_admin:
        try:
            print("\nSyncing to Firestore...")
            if not firebase_admin._apps:
                cred_path = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "serviceAccountKey.json")
                if os.path.exists(cred_path):
                    cred = credentials.Certificate(cred_path)
                    firebase_admin.initialize_app(cred)
                else:
                    # Try default application credentials if explicit file is missing
                    firebase_admin.initialize_app()
                    
            db = firestore.client()
            doc_ref = db.collection("tenders").document(tender_id)
            doc_ref.set(updated_manifest, merge=True)
            print(f"Successfully synced tender '{tender_id}' to Firestore 'tenders' collection.")
            logger.info(f"Successfully synced tender '{tender_id}' to Firestore.")
        except Exception as e:
            print(f"Failed to sync to Firestore: {e}")
            logger.error(f"Failed to sync to Firestore: {e}")
    else:
        print("\nSkipping Firestore sync: firebase-admin package is not installed.")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python run_enrichment.py <tender_folder_path>")
        sys.exit(1)

    # Check for library imports to notify developer of missing dependencies
    missing_deps = []
    try:
        import pydantic
    except ImportError:
        missing_deps.append("pydantic")
    try:
        import instructor
    except ImportError:
        missing_deps.append("instructor")
    try:
        import openai
    except ImportError:
        missing_deps.append("openai")
    try:
        import docx
    except ImportError:
        missing_deps.append("python-docx")
    try:
        import openpyxl
    except ImportError:
        missing_deps.append("openpyxl")
    try:
        import pptx
    except ImportError:
        missing_deps.append("python-pptx")
    try:
        import docling
    except ImportError:
        # docling is primary but optional if using text/fallbacks
        pass

    if missing_deps:
        print("\n[WARNING] Missing Python dependencies for full desktop execution.")
        print(f"Please install them via: pip install {' '.join(missing_deps)}")
        print("Continuing execution with potential import failures...\n")

    run_pipeline(sys.argv[1])
