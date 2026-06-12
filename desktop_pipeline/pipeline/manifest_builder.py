import json
import logging
from typing import Dict, Any
from schemas.tender_schema import SouthAfricanTenderExtraction
from schemas.industry_schema import IndustryClassification

logger = logging.getLogger("ManifestBuilder")

def merge_enrichment_into_manifest(manifest_path: str, classification: IndustryClassification, extraction: SouthAfricanTenderExtraction, enrichment_payload: Dict[str, Any]) -> Dict[str, Any]:
    """
    Loads existing manifest.json, promotes relevant extraction fields directly 
    to the top-level keys, removes empty arrays, nests the full extraction payload 
    under 'ai_enrichment', and returns the modified manifest dict.
    """
    try:
        with open(manifest_path, "r", encoding="utf-8") as f:
            manifest = json.load(f)
    except Exception as e:
        logger.warning(f"Could not load manifest from '{manifest_path}'. Starting with clean dictionary. Error: {e}")
        manifest = {}

    # Classifications
    manifest["document_type"] = classification.document_type
    manifest["tenderAdvertType"] = classification.document_type.lower()
    manifest["classified_industry"] = classification.classified_industry
    manifest["industry_id"] = classification.industry_id

    # Metadata promotions
    meta = extraction.tender_metadata
    if meta.tender_title:
        manifest["title"] = meta.tender_title
    if meta.issuing_institution:
        manifest["organ_of_State"] = meta.issuing_institution
    if meta.geographic_locality and meta.geographic_locality.province:
        manifest["province"] = meta.geographic_locality.province
    if meta.geographic_locality and meta.geographic_locality.local_municipality:
        manifest["delivery"] = meta.geographic_locality.local_municipality

    # CIDB promotion (e.g. 7GB)
    cidb = extraction.industry_credentials.cidb_requirements
    if cidb.is_required and cidb.minimum_grade and cidb.class_of_work:
        manifest["cidb_grading"] = f"{cidb.minimum_grade}{cidb.class_of_work}"

    # Preference point system
    pref = extraction.preferential_procurement
    if pref.scoring_system_applicable and pref.scoring_system_applicable != "None":
        manifest["preference_points"] = pref.scoring_system_applicable

    # Briefing and closing dates
    dates = extraction.critical_dates
    if dates.compulsory_briefing:
        manifest["briefingCompulsory"] = dates.compulsory_briefing.is_compulsory
        if dates.compulsory_briefing.briefing_date_time:
            manifest["briefingDate"] = dates.compulsory_briefing.briefing_date_time
        if dates.compulsory_briefing.briefing_venue:
            manifest["briefingVenue"] = dates.compulsory_briefing.briefing_venue

    if dates.closing_date_time:
        manifest["closingDate"] = dates.closing_date_time

    # Technical threshold
    tech = extraction.technical_functionality
    if tech.has_functionality_threshold and tech.minimum_threshold_percentage is not None:
        manifest["min_functionality_threshold"] = int(tech.minimum_threshold_percentage)

    # Clean old gemmaEnrichment nested structures to ensure compatibility
    if "gemmaEnrichment" in manifest:
        del manifest["gemmaEnrichment"]

    # Delete empty arrays (The Empty Array Graveyard Sweeper)
    keys_to_delete = []
    for k, v in manifest.items():
        if isinstance(v, list) and len(v) == 0:
            keys_to_delete.append(k)
    for k in keys_to_delete:
        del manifest[k]

    # Map validated properties into the nested 'ai_enrichment' namespace
    manifest["ai_enrichment"] = enrichment_payload

    # Deep Type Sanitizer (Recursive string to None/float conversion)
    def sanitize(obj):
        if isinstance(obj, dict):
            for k in list(obj.keys()):
                v = obj[k]
                if isinstance(v, str):
                    s = v.strip().lower()
                    if s in ["null", "not found", "look_deeper"]:
                        obj[k] = None
                    elif s == "nan":
                        obj[k] = 0.0
                else:
                    sanitize(v)
        elif isinstance(obj, list):
            for i, v in enumerate(obj):
                if isinstance(v, str):
                    s = v.strip().lower()
                    if s in ["null", "not found", "look_deeper"]:
                        obj[i] = None
                    elif s == "nan":
                        obj[i] = 0.0
                else:
                    sanitize(v)

    sanitize(manifest)

    return manifest
