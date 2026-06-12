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
            
        # 2. Delete empty arrays
        keys_to_delete = []
        for k, v in tender.items():
            if isinstance(v, list) and len(v) == 0:
                keys_to_delete.append(k)
                updated = True
        for k in keys_to_delete:
            del tender[k]
            
        # 3. Handle MBD/SBD logic
        inst_type = tender.get("organ_of_State", "") or tender.get("institution_type", "")
        if inst_type:
            is_muni = "municipality" in inst_type.lower() or "municipal" in inst_type.lower()
            is_national = "national" in inst_type.lower() or "entity" in inst_type.lower() or "department" in inst_type.lower()
            
            if is_national and not is_muni:
                for k in ["mbd_1_required", "mbd_4_required", "mbd_6_1_required", "mbd_15_required"]:
                    if k in tender and tender[k] == "true":
                        tender[k] = "false"
                        updated = True
            elif is_muni:
                for k in ["sbd_1_required", "sbd_4_required", "sbd_6_1_required"]:
                    if k in tender and tender[k] == "true":
                        tender[k] = "false"
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
                
        # 5. Sanitize Strings (Deep check for "null", "NaN", "LOOK_DEEPER")
        import copy
        before_sanitize = copy.deepcopy(tender)
        sanitize(tender)
        if tender != before_sanitize:
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
