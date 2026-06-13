import os
import time
import json
import firebase_admin
from firebase_admin import credentials, firestore

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

def cleanup_database():
    print("Initializing Firebase...")
    if not firebase_admin._apps:
        cred_path = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "serviceAccountKey.json")
        if os.path.exists(cred_path):
            cred = credentials.Certificate(cred_path)
            firebase_admin.initialize_app(cred)
        else:
            firebase_admin.initialize_app()
            
    db = firestore.client()
    tenders_ref = db.collection("tenders")
    print("Fetching documents... (this might take a moment depending on database size)")
    docs = list(tenders_ref.stream())
    print(f"Found {len(docs)} documents. Beginning cleanup...")
    
    count = 0
    for doc in docs:
        tender = doc.to_dict()
        doc_id = doc.id
        
        updated = False
        
        # 1. Clean old gemmaEnrichment nested structures
        if "gemmaEnrichment" in tender:
            del tender["gemmaEnrichment"]
            updated = True
            
        # 2. Delete empty arrays and specific junk drawer items
        junk_keys = ["turnoverRequirements", "siteCoverageTags", "sectorTags", "contractTerms"]
        for jk in junk_keys:
            if jk in tender:
                del tender[jk]
                updated = True

        keys_to_delete = []
        for k, v in tender.items():
            if isinstance(v, list) and len(v) == 0:
                keys_to_delete.append(k)
        for k in keys_to_delete:
            del tender[k]
            updated = True
            
        # 3. Handle MBD/SBD logic
        inst_type = tender.get("organ_of_State", "") or tender.get("institution_type", "")
        if inst_type:
            is_muni = "municipality" in inst_type.lower() or "municipal" in inst_type.lower()
            is_national = "national" in inst_type.lower() or "entity" in inst_type.lower() or "department" in inst_type.lower() or "enterprise" in inst_type.lower()
            
            if is_national and not is_muni:
                # Top level if present
                for k in ["mbd_1_required", "mbd_4_required", "mbd_6_1_required", "mbd_15_required"]:
                    if str(tender.get(k, "")).lower() == "true":
                        tender[k] = "false"
                        updated = True
                
                # Inside ai_enrichment
                if "ai_enrichment" in tender and "fields" in tender["ai_enrichment"]:
                    for field in tender["ai_enrichment"]["fields"]:
                        if field.get("field") in ["statutory_forms_mbd_forms_mbd_1_required", "statutory_forms_mbd_forms_mbd_4_required", "statutory_forms_mbd_forms_mbd_6_1_required", "statutory_forms_mbd_forms_mbd_15_required"]:
                            if str(field.get("value", "")).lower() == "true":
                                field["value"] = "False"
                                updated = True
                                
            elif is_muni:
                for k in ["sbd_1_required", "sbd_4_required", "sbd_6_1_required"]:
                    if str(tender.get(k, "")).lower() == "true":
                        tender[k] = "false"
                        updated = True
                
                # Inside ai_enrichment
                if "ai_enrichment" in tender and "fields" in tender["ai_enrichment"]:
                    for field in tender["ai_enrichment"]["fields"]:
                        if field.get("field") in ["statutory_forms_sbd_forms_sbd_1_required", "statutory_forms_sbd_forms_sbd_4_required", "statutory_forms_sbd_forms_sbd_6_1_required"]:
                            if str(field.get("value", "")).lower() == "true":
                                field["value"] = "False"
                                updated = True
                        
        # 4. Status and Closing Date Fix
        closing_date_str = tender.get("closing_Date") or tender.get("closingDate")
        if closing_date_str and isinstance(closing_date_str, str):
            try:
                import datetime
                dt = None
                try:
                    dt = datetime.datetime.strptime(closing_date_str, "%Y-%m-%dT%H:%M:%S")
                except ValueError:
                    try:
                        dt = datetime.datetime.strptime(closing_date_str.replace("h", "H"), "%d %B %Y %HH%M")
                    except ValueError:
                        pass
                        
                if dt and dt < datetime.datetime.now():
                    if tender.get("status") != "Closed":
                        tender["status"] = "Closed"
                        updated = True
            except Exception:
                pass
                
            # Unify closingDate to closing_Date
            if "closingDate" in tender:
                tender["closing_Date"] = tender["closingDate"]
                del tender["closingDate"]
                updated = True
                
            # Date Fallback in ai_enrichment
            if closing_date_str and "ai_enrichment" in tender and "fields" in tender["ai_enrichment"]:
                for field in tender["ai_enrichment"]["fields"]:
                    if field.get("field") == "critical_dates_closing_date_time" and str(field.get("value", "")).lower() in ["not found", "none", "", "null"]:
                        field["value"] = closing_date_str
                        field["status"] = "DONE"
                        field["evidence"] = "Recovered from portal manifest"
                        field["evidenceScore"] = 100
                        field["evidenceConfidence"] = "high"
                        updated = True
                        
                        # Remove from critical list if present
                        if "criticalFieldsNeedingReview" in tender["ai_enrichment"]:
                            if "critical_dates_closing_date_time" in tender["ai_enrichment"]["criticalFieldsNeedingReview"]:
                                tender["ai_enrichment"]["criticalFieldsNeedingReview"].remove("critical_dates_closing_date_time")

        # 4b. Taxonomy Override (Solar vs Mechanical)
        classified_industry = str(tender.get("classified_industry", "")).lower()
        if "solar" in classified_industry:
            title_desc = str(tender.get("title", "")) + " " + str(tender.get("description", ""))
            title_desc = title_desc.lower()
            if any(phrase in title_desc for phrase in ["boiler", "chassis", "structural steel", "turbine"]):
                tender["classified_industry"] = "Manufacturing & Industrial"
                tender["industry_id"] = "manufacturing"
                tender["specializations"] = ["Metal Fabrication, Machining & Welding"]
                updated = True
                
                if "ai_enrichment" in tender and "fields" in tender["ai_enrichment"]:
                    for field in tender["ai_enrichment"]["fields"]:
                        if field.get("field") == "classified_industry":
                            field["value"] = "Manufacturing & Industrial"
                        elif field.get("field") == "industry_id":
                            field["value"] = "manufacturing"
                        elif field.get("field") == "matched_specializations":
                            field["value"] = "Metal Fabrication, Machining & Welding"
                        elif field.get("field") == "classification_reasoning":
                            field["value"] = "Overridden by validation layer: heavy mechanical phrases detected."
                
        # 5. Sanitize Strings (Deep check for "null", "NaN", "LOOK_DEEPER")
        import copy
        before_sanitize = copy.deepcopy(tender)
        sanitize(tender)
        if tender != before_sanitize:
            updated = True
            
        # Specific fix for addressLines literal 'null' strings
        if "ai_enrichment" in tender and "contactDetails" in tender["ai_enrichment"]:
            contact_details = tender["ai_enrichment"]["contactDetails"]
            if "addressLines" in contact_details:
                valid_addresses = []
                for addr in contact_details["addressLines"]:
                    text_val = addr.get("text")
                    if text_val is not None:
                        text_str = str(text_val).strip().lower()
                        if text_str not in ["null", "not found", "none", ""]:
                            valid_addresses.append(addr)
                if len(valid_addresses) != len(contact_details["addressLines"]):
                    contact_details["addressLines"] = valid_addresses
                    updated = True
            
            # Specific fix for contactLines HTML tag pollution
            if "contactLines" in contact_details:
                import re
                for line in contact_details["contactLines"]:
                    text_val = line.get("text", "")
                    if text_val and "<" in text_val and ">" in text_val:
                        line["text"] = re.sub(r'<[^>]+>', '', text_val).strip()
                        updated = True

        # 6. Group unstructured fields into ai_enrichment if needed
        # (Assuming the main structural fields like 'fields', 'criticalFieldsNeedingReview', etc. might be loose)
        ai_enrichment_keys = ["fields", "criticalFieldsNeedingReview", "contactDetails", "confidenceCounts", "summary"]
        needs_grouping = any(k in tender for k in ai_enrichment_keys)
        if needs_grouping:
            if "ai_enrichment" not in tender:
                tender["ai_enrichment"] = {}
            for k in ai_enrichment_keys:
                if k in tender:
                    tender["ai_enrichment"][k] = tender.pop(k)
            updated = True
            
        if updated:
            print(f"Updating document: {doc_id}...")
            # We must use set instead of update because we deleted fields
            db.collection("tenders").document(doc_id).set(tender)
            count += 1
            
    print(f"\nCleanup complete! Sanitized and restructured {count} historical documents.")

if __name__ == "__main__":
    cleanup_database()
