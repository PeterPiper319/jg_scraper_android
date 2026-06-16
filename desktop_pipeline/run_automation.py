import os
import sys
import subprocess
import re
import firebase_admin
from firebase_admin import credentials, storage

def run_automation_loop():
    print("[AUTOMATION] Initializing Firebase...")
    if not firebase_admin._apps:
        cred_path = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "serviceAccountKey.json")
        if os.path.exists(cred_path):
            cred = credentials.Certificate(cred_path)
            firebase_admin.initialize_app(cred, {
                'storageBucket': 'taskme-478416.firebasestorage.app'
            })
        else:
            firebase_admin.initialize_app(options={
                'storageBucket': 'taskme-478416.firebasestorage.app'
            })
            
    bucket = storage.bucket()
    print("[AUTOMATION] Fetching file list from gs://taskme-478416.firebasestorage.app/tenders/...")
    
    blobs = list(bucket.list_blobs(prefix="tenders/"))
    
    # Group blobs by folder
    folders = {}
    for blob in blobs:
        if blob.name == "tenders/": continue
        parts = blob.name.split('/')
        if len(parts) >= 2:
            folder_name = parts[1]
            if folder_name not in folders:
                folders[folder_name] = []
            folders[folder_name].append(blob)
            
    if not folders:
        print("[AUTOMATION] No tender folders found in Cloud Storage.")
        return
        
    print(f"[AUTOMATION] Found {len(folders)} tender folders in Cloud Storage.")
    
    base_dir = os.path.dirname(os.path.abspath(__file__))
    download_dir = os.path.join(base_dir, "automation_tenders")
    if not os.path.exists(download_dir):
        os.makedirs(download_dir)
        
    pipeline_script = os.path.join(base_dir, "run_enrichment.py")
    
    for idx, (folder_name, folder_blobs) in enumerate(folders.items(), 1):
        safe_folder_name = re.sub(r'[<>:"|?*]', '_', folder_name)
        local_folder = os.path.join(download_dir, safe_folder_name)
        
        print(f"\n=======================================================")
        print(f"[AUTOMATION] ({idx}/{len(folders)}) Processing: {folder_name}")
        print(f"=======================================================")
        
        if not os.path.exists(local_folder):
            os.makedirs(local_folder)
            
        print(f"  [1/3] Downloading {len(folder_blobs)} files from Storage...")
        for blob in folder_blobs:
            if blob.name.endswith('/'): continue
            rel_path = blob.name.replace(f"tenders/{folder_name}/", "", 1)
            safe_rel_path = re.sub(r'[<>:"|?*]', '_', rel_path)
            local_path = os.path.join(local_folder, safe_rel_path)
            
            local_file_dir = os.path.dirname(local_path)
            if not os.path.exists(local_file_dir):
                os.makedirs(local_file_dir)
            blob.download_to_filename(local_path)
            
        print(f"  [2/3] Running Deep Extraction Pipeline...")
        proc = subprocess.Popen(
            [sys.executable, pipeline_script, local_folder],
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            bufsize=1
        )
        for line in proc.stdout:
            # We prefix pipeline logs so they look nested
            print(f"    | {line.rstrip()}")
        proc.wait()
        
        if proc.returncode == 0:
            print(f"  [3/3] Successfully processed & synced {folder_name} to Firestore!")
        else:
            print(f"  [3/3] Pipeline failed for {folder_name} (Exit code {proc.returncode})")
            any_failure = True
            
    print("\n[AUTOMATION] *** Continuous Automation Cycle Complete! ***")
    if any_failure:
        sys.exit(1)

if __name__ == "__main__":
    run_automation_loop()
