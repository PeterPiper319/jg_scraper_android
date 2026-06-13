import datetime
import logging
import re
from typing import Dict, Any, List
from schemas.tender_schema import SouthAfricanTenderExtraction
from schemas.industry_schema import IndustryClassification

logger = logging.getLogger("Normalizer")

CRITICAL_FIELDS = {
    "tender_metadata_tender_reference_number",
    "tender_metadata_tender_title",
    "tender_metadata_issuing_institution",
    "tender_metadata_procurement_category",
    "critical_dates_closing_date_time",
    "critical_dates_compulsory_briefing_is_compulsory",
    "submission_mechanics_submission_method",
    "preferential_procurement_scoring_system_applicable",
    "industry_credentials_cidb_requirements_minimum_grade",
    "industry_credentials_cidb_requirements_class_of_work",
    "financial_criteria_estimated_tender_value_zar"
}

def normalize_val(val: Any) -> str:
    """
    Standardizes empty, None, or placeholder strings to 'Not found'.
    Preserves meaningful status like 'Not applicable'.
    """
    if val is None:
        return "Not found"
    s = str(val).strip()
    if not s or s.lower() in ("null", "none", "n/a", "not found"):
        return "Not found"
    return s

def build_flat_fields(classification: IndustryClassification, extraction: SouthAfricanTenderExtraction) -> List[Dict[str, Any]]:
    fields = []

    def add_field(field_name: str, label: str, value: Any, is_critical: bool = False):
        val_str = normalize_val(value)
        # Pull verbatim evidence from LLM evidence map if available (could be a dict or a Pydantic model)
        evidence_map_dict = extraction.evidence_map if isinstance(extraction.evidence_map, dict) else extraction.evidence_map.dict()
        evidence_str = evidence_map_dict.get(field_name, "") if val_str not in ("Not found", "Not applicable") else ""
        if str(evidence_str).lower() in ("not found", "none", "null", "n/a", ""):
            evidence_str = ""

        fields.append({
            "field": field_name,
            "label": label,
            "value": val_str,
            "status": "WARNING" if val_str == "Not found" else "DONE",
            "evidence": evidence_str,
            "evidenceScore": 0 if not evidence_str else 100,
            "evidenceConfidence": "none" if not evidence_str else "high",
            "sourceFile": "",
            "isCritical": is_critical or (field_name in CRITICAL_FIELDS)
        })

    # Stage 1 - Classification
    add_field("document_type", "Document Type", classification.document_type)
    add_field("classified_industry", "Classified Industry", classification.classified_industry)
    add_field("industry_id", "Industry ID", classification.industry_id)
    add_field("matched_specializations", "Matched Specializations", ", ".join(classification.matched_specializations))
    add_field("matched_skills", "Matched Skills", ", ".join(classification.matched_skills))
    add_field("matched_capabilities", "Matched Capabilities", ", ".join(classification.matched_capabilities))
    add_field("classification_reasoning", "Classification Reasoning", classification.classification_reasoning)

    # Stage 2 - Extraction: Metadata
    meta = extraction.tender_metadata
    add_field("tender_metadata_tender_reference_number", "Tender Reference Number", meta.tender_reference_number, True)
    add_field("tender_metadata_tender_title", "Tender Title", meta.tender_title, True)
    add_field("tender_metadata_tender_description", "Tender Description", meta.tender_description)
    add_field("tender_metadata_issuing_institution", "Issuing Institution", meta.issuing_institution, True)
    add_field("tender_metadata_institution_type", "Institution Type", meta.institution_type)
    add_field("tender_metadata_procurement_category", "Procurement Category", meta.procurement_category, True)

    geo = meta.geographic_locality
    if geo:
        add_field("tender_metadata_geographic_locality_province", "Geographic Locality Province", geo.province)
        add_field("tender_metadata_geographic_locality_district_municipality", "Geographic Locality District Municipality", geo.district_municipality)
        add_field("tender_metadata_geographic_locality_local_municipality", "Geographic Locality Local Municipality", geo.local_municipality)
        add_field("tender_metadata_geographic_locality_ward", "Geographic Locality Ward", geo.ward)
    else:
        add_field("tender_metadata_geographic_locality_province", "Geographic Locality Province", None)
        add_field("tender_metadata_geographic_locality_district_municipality", "Geographic Locality District Municipality", None)
        add_field("tender_metadata_geographic_locality_local_municipality", "Geographic Locality Local Municipality", None)
        add_field("tender_metadata_geographic_locality_ward", "Geographic Locality Ward", None)

    # Critical Dates
    dates = extraction.critical_dates
    add_field("critical_dates_publish_date", "Publish Date", dates.publish_date)
    add_field("critical_dates_compulsory_briefing_is_compulsory", "Briefing: Is Compulsory", dates.compulsory_briefing.is_compulsory, True)
    add_field("critical_dates_compulsory_briefing_briefing_date_time", "Briefing: Date Time", dates.compulsory_briefing.briefing_date_time)
    add_field("critical_dates_compulsory_briefing_briefing_venue", "Briefing: Venue", dates.compulsory_briefing.briefing_venue)
    add_field("critical_dates_closing_date_time", "Closing Date Time", dates.closing_date_time, True)
    add_field("critical_dates_validity_period_days", "Validity Period Days", dates.validity_period_days)

    # Submission mechanics
    sub = extraction.submission_mechanics
    add_field("submission_mechanics_submission_method", "Submission Method", sub.submission_method, True)
    add_field("submission_mechanics_physical_box_address", "Physical Box Address", sub.physical_box_address)
    add_field("submission_mechanics_electronic_portal_url", "Electronic Portal Url", sub.electronic_portal_url)
    add_field("submission_mechanics_required_hard_copies", "Required Hard Copies", sub.required_hard_copies)

    # Administrative compliance
    admin = extraction.administrative_compliance
    add_field("administrative_compliance_csd_registration_required", "CSD Registration Required", admin.csd_registration_required)
    add_field("administrative_compliance_sars_tax_compliance_pin_required", "SARS Tax Compliance Pin Required", admin.sars_tax_compliance_pin_required)
    add_field("administrative_compliance_cipc_annual_returns_good_standing_required", "CIPC Good Standing Required", admin.cipc_annual_returns_good_standing_required)
    add_field("administrative_compliance_coida_letter_of_good_standing_required", "COIDA Letter of Good Standing Required", admin.coida_letter_of_good_standing_required)

    # Preferential procurement
    pref = extraction.preferential_procurement
    add_field("preferential_procurement_scoring_system_applicable", "Preference Scoring System", pref.scoring_system_applicable, True)

    # Industry credentials (CIDB) - Conditional Validation to eliminate alert fatigue
    cidb = extraction.industry_credentials.cidb_requirements
    cidb_required = cidb.is_required
    if not cidb_required:
        grade_val = "Not applicable"
        class_val = "Not applicable"
    else:
        grade_val = cidb.minimum_grade
        class_val = cidb.class_of_work

    add_field("industry_credentials_cidb_requirements_is_required", "CIDB Required", cidb_required)
    add_field("industry_credentials_cidb_requirements_minimum_grade", "CIDB Minimum Grade", grade_val, True)
    add_field("industry_credentials_cidb_requirements_class_of_work", "CIDB Class of Work", class_val, True)

    stat = extraction.statutory_forms.mbd_forms
    add_field("statutory_forms_mbd_forms_mbd_1_required", "MBD 1 Required", stat.mbd_1_required)
    add_field("statutory_forms_mbd_forms_mbd_4_required", "MBD 4 Required", stat.mbd_4_required)
    add_field("statutory_forms_mbd_forms_mbd_6_1_required", "MBD 6.1 Required", stat.mbd_6_1_required)
    add_field("statutory_forms_mbd_forms_mbd_15_required", "MBD 15 Required", stat.mbd_15_required)

    sbd = extraction.statutory_forms.sbd_forms
    add_field("statutory_forms_sbd_forms_sbd_1_required", "SBD 1 Required", sbd.sbd_1_required)
    add_field("statutory_forms_sbd_forms_sbd_4_required", "SBD 4 Required", sbd.sbd_4_required)
    add_field("statutory_forms_sbd_forms_sbd_6_1_required", "SBD 6.1 Required", sbd.sbd_6_1_required)

    # Financial criteria
    import math
    fin = extraction.financial_criteria
    est_val = fin.estimated_tender_value_zar
    if est_val is None or (isinstance(est_val, float) and math.isnan(est_val)):
        est_val = 0.0

    add_field("financial_criteria_estimated_tender_value_zar", "Estimated Tender Value ZAR", est_val, True)
    add_field("financial_criteria_audited_financials_required", "Audited Financials Required", fin.audited_financials_required)

    # Technical functionality
    tech = extraction.technical_functionality
    add_field("technical_functionality_has_functionality_threshold", "Has Functionality Threshold", tech.has_functionality_threshold)
    add_field("technical_functionality_minimum_threshold_percentage", "Minimum Functionality Threshold %", tech.minimum_threshold_percentage)

    return fields

def run_regulatory_audits(extraction: SouthAfricanTenderExtraction, fields: List[Dict[str, Any]], critical_reviews: List[str]):
    """
    Executes domain regulatory checks and appends WARNING audit findings directly to fields.
    """
    estimated_value = extraction.financial_criteria.estimated_tender_value_zar
    scoring_system = extraction.preferential_procurement.scoring_system_applicable
    cidb_grade = extraction.industry_credentials.cidb_requirements.minimum_grade
    
    findings = []

    # 1. 80/20 vs 90/10 PPPFA rules (ZAR 50 Million threshold)
    if estimated_value and estimated_value > 0:
        if estimated_value > 50000000.0 and scoring_system == "80/20":
            findings.append(
                f"REGULATORY_ANOMALY: Tender value estimated at R{estimated_value:,.2f} exceeds R50 Million, "
                "but the 80/20 preference system is stipulated instead of 90/10."
            )
        elif estimated_value <= 50000000.0 and estimated_value >= 30000.0 and scoring_system == "90/10":
            findings.append(
                f"REGULATORY_ANOMALY: Tender value estimated at R{estimated_value:,.2f} is under R50 Million, "
                "but the 90/10 preference system is stipulated instead of 80/20."
            )

    # 2. CIDB Grade limits vs Estimated Value
    if estimated_value and estimated_value > 0 and cidb_grade and (1 <= cidb_grade <= 8):
        max_limits = {
            1: 500000.0,
            2: 1000000.0,
            3: 3000000.0,
            4: 6000000.0,
            5: 10000000.0,
            6: 20000000.0,
            7: 60000000.0,
            8: 200000000.0
        }
        limit = max_limits.get(cidb_grade, float('inf'))
        if estimated_value > limit:
            findings.append(
                f"REGULATORY_ANOMALY: Requisite CIDB Grade {cidb_grade} has a maximum value limit of "
                f"R{limit:,.2f}, which is lower than the estimated tender value of R{estimated_value:,.2f}."
            )

    # 3. Preference Points Omission Warning
    if scoring_system in ("None", "Not found", ""):
        findings.append(
            "REGULATORY_ANOMALY: Public procurement regulations require an 80/20 or 90/10 preference point system, "
            "but scoring system is specified as None or Not found."
        )

    # 4. Functionality threshold mathematical contradiction
    has_threshold = extraction.technical_functionality.has_functionality_threshold
    threshold_pct = extraction.technical_functionality.minimum_threshold_percentage
    if has_threshold:
        if threshold_pct is None or threshold_pct <= 0.0:
            findings.append(
                "REGULATORY_ANOMALY: Technical functionality evaluation threshold is enabled, "
                "but minimum threshold is missing or specified as 0%."
            )

    # Write findings directly into the fields list as WARNINGS
    for idx, finding in enumerate(findings):
        field_id = f"regulatory_audit_finding_{idx}"
        fields.append({
            "field": field_id,
            "label": "Regulatory Audit Finding",
            "value": finding,
            "status": "WARNING",
            "evidence": "",
            "evidenceScore": 100,
            "evidenceConfidence": "high",
            "sourceFile": "",
            "isCritical": True
        })
        critical_reviews.append(field_id)

def extract_contacts_from_chunks(chunks: List[Any]) -> Dict[str, Any]:
    """
    Strips email addresses and phone numbers out of the vector chunks deterministically.
    """
    emails = set()
    phones = set()
    contact_lines = []

    email_regex = re.compile(r'\b[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}\b')
    phone_regex = re.compile(r'\b(?:\+?27|0)\s*[1-9]\d(?:\s*-?\s*\d){7,8}\b')

    for chunk in chunks:
        lines = chunk.content.split('\n')
        for line in lines:
            line_str = line.strip()
            found_emails = email_regex.findall(line_str)
            found_phones = phone_regex.findall(line_str)

            valid_phones = []
            for p in found_phones:
                cleaned = re.sub(r'[\s-]', '', p)
                if 9 <= len(cleaned) <= 12 and not cleaned.startswith("2026"):
                    valid_phones.append(p)

            if found_emails or valid_phones:
                clean_text = re.sub(r'<[^>]+>', '', line_str).strip()
                contact_lines.append({
                    "text": clean_text,
                    "sourceFile": chunk.source_file,
                    "hasEmail": bool(found_emails),
                    "hasPhone": bool(valid_phones),
                    "hasFax": False
                })
                for email in found_emails:
                    emails.add(email.lower())
                for phone in valid_phones:
                    phones.add(phone.strip())

    return {
        "emails": list(emails),
        "phoneNumbers": list(phones),
        "faxNumbers": [],
        "addressLines": [],
        "contactLines": contact_lines,
        "hasContactSignals": len(emails) > 0 or len(phones) > 0
    }

def build_final_enrichment_payload(tender_id: str, classification: IndustryClassification, extraction: SouthAfricanTenderExtraction, chunks: List[Any] = None) -> Dict[str, Any]:
    # --- HARD TAXONOMY OVERRIDE ---
    if classification.classified_industry and "solar" in classification.classified_industry.lower():
        text_content = ""
        if chunks:
            text_content = " ".join([c.content.lower() for c in chunks])
        if extraction.tender_metadata.tender_title:
            text_content += " " + extraction.tender_metadata.tender_title.lower()
            
        mechanical_phrases = ["boiler", "chassis", "structural steel", "turbine"]
        if any(phrase in text_content for phrase in mechanical_phrases):
            classification.classified_industry = "Manufacturing & Industrial"
            classification.industry_id = "manufacturing"
            classification.matched_specializations = ["Metal Fabrication, Machining & Welding"]
            classification.classification_reasoning = "Overridden by validation layer: heavy mechanical phrases detected."

    # --- LEGISLATIVE GATEKEEPER ---
    inst_type = extraction.tender_metadata.institution_type
    if inst_type in ["State Owned Enterprise", "National Department"]:
        extraction.statutory_forms.mbd_forms.mbd_1_required = False
        extraction.statutory_forms.mbd_forms.mbd_4_required = False
        extraction.statutory_forms.mbd_forms.mbd_6_1_required = False
        extraction.statutory_forms.mbd_forms.mbd_15_required = False

    fields = build_flat_fields(classification, extraction)
    
    critical_reviews = []
    # Perform audits
    run_regulatory_audits(extraction, fields, critical_reviews)

    # Gather critical empty/missing fields
    for f in fields:
        if f["isCritical"] and f["value"] == "Not found":
            critical_reviews.append(f["field"])

    # Eliminate duplicates
    critical_reviews = list(set(critical_reviews))

    # Basic stats
    done_count = sum(1 for f in fields if f["status"] == "DONE")
    warning_count = sum(1 for f in fields if f["status"] == "WARNING")
    total_fields = len(fields)

    # Extract contacts
    contact_details = {
        "emails": [],
        "phoneNumbers": [],
        "faxNumbers": [],
        "addressLines": [],
        "contactLines": [],
        "hasContactSignals": False
    }
    if chunks:
        contact_details = extract_contacts_from_chunks(chunks)

    # Promoted addresses from extraction if found
    box_address = normalize_val(extraction.submission_mechanics.physical_box_address)
    if box_address != "Not found":
        contact_details["addressLines"].append({
            "text": box_address,
            "sourceFile": ""
        })

    payload = {
        "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "tenderId": tender_id,
        "summary": {
            "resultCount": total_fields,
            "criticalFieldCount": len(CRITICAL_FIELDS),
            "lowConfidenceCount": warning_count,
            "mediumConfidenceCount": 0,
            "highConfidenceCount": done_count
        },
        "confidenceCounts": {
            "high": done_count,
            "none": warning_count,
            "medium": 0,
            "low": 0
        },
        "criticalFieldsNeedingReview": critical_reviews,
        "contactDetails": contact_details,
        "fields": fields
    }
    return payload
