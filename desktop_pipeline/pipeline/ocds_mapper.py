import datetime
from typing import Dict, Any

def build_ocds_release(tender_id: str, enrichment_payload: Dict[str, Any]) -> Dict[str, Any]:
    """
    Transforms the Gemma enrichment payload into a valid OCDS v1.1.5 Release.
    """
    schema = enrichment_payload.get("extractedSchema", {})
    metadata = schema.get("tender_metadata", {})
    dates = schema.get("critical_dates", {})
    financial = schema.get("financial_criteria", {})
    pref = schema.get("preferential_procurement", {})
    credentials = schema.get("industry_credentials", {})
    
    # Base OCID and Release ID
    ocid = f"ocds-jgs-{tender_id}"
    release_id = f"rel-{tender_id}-{int(datetime.datetime.now(datetime.timezone.utc).timestamp())}"
    
    # Map values
    tender_ref = metadata.get("tender_reference_number") or "Unknown"
    tender_title = metadata.get("tender_title") or "Unknown Title"
    buyer_name = metadata.get("issuing_institution") or "Unknown Institution"
    
    closing_date = dates.get("closing_date_time")
    briefing_date = dates.get("compulsory_briefing", {}).get("briefing_date_time") if dates.get("compulsory_briefing") else None
    
    est_value = financial.get("estimated_tender_value_zar")
    province = metadata.get("geographic_locality", {}).get("province")
    score_system = pref.get("scoring_system_applicable")
    cidb_grade = credentials.get("cidb_requirements", {}).get("minimum_grade") if credentials.get("cidb_requirements") else None

    # Construct OCDS Release Structure
    release = {
        "ocid": ocid,
        "id": release_id,
        "date": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
        "tag": ["tender"],
        "initiationType": "tender",
        "buyer": {
            "id": buyer_name.replace(" ", "_").lower(),
            "name": buyer_name
        },
        "tender": {
            "id": tender_ref,
            "title": tender_title,
            "status": "active",
            "value": {
                "amount": est_value if est_value else 0.0,
                "currency": "ZAR"
            },
            "tenderPeriod": {
                "endDate": closing_date if closing_date else ""
            }
        }
    }
    
    if briefing_date:
        release["tender"]["enquiryPeriod"] = {
            "endDate": briefing_date
        }
        
    if province:
        release["tender"]["deliveryLocations"] = [
            {
                "id": province.replace(" ", "_").lower(),
                "description": province
            }
        ]
        
    if cidb_grade:
        release["tender"]["eligibilityCriteria"] = f"Minimum CIDB Grade Requirement: {cidb_grade}"
        
    if score_system:
        release["tender"]["milestones"] = [
            {
                "id": "scoring-system",
                "title": "Preferential Procurement Scoring System",
                "description": f"Applicable preference scoring system: {score_system}",
                "status": "notMet"
            }
        ]
        
    return release
