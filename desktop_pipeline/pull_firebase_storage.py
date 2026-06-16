import argparse
import json
import os
import re
import sys

import firebase_admin
from firebase_admin import credentials, storage

DEFAULT_BUCKET = 'taskme-478416.firebasestorage.app'
DEFAULT_DOWNLOAD_DIR = 'firebase_tenders'


def init_firebase():
    if not firebase_admin._apps:
        cred_path = os.environ.get('FIREBASE_SERVICE_ACCOUNT_JSON', 'serviceAccountKey.json')
        if os.path.exists(cred_path):
            cred = credentials.Certificate(cred_path)
            firebase_admin.initialize_app(cred, {
                'storageBucket': DEFAULT_BUCKET
            })
        else:
            firebase_admin.initialize_app(options={
                'storageBucket': DEFAULT_BUCKET
            })


def safe_path(path):
    return re.sub(r'[<>:"|?*]', '_', path)


def build_download_dir(output_dir=None):
    base_dir = os.path.dirname(os.path.abspath(__file__))
    download_dir = os.path.join(base_dir, output_dir or DEFAULT_DOWNLOAD_DIR)
    if not os.path.exists(download_dir):
        os.makedirs(download_dir)
    return download_dir


def list_firebase_tender_ids():
    init_firebase()
    bucket = storage.bucket()
    blobs = bucket.list_blobs(prefix='tenders/')
    tender_ids = set()
    for blob in blobs:
        if blob.name.endswith('/'):
            continue
        rel_path = blob.name.replace('tenders/', '', 1)
        parts = rel_path.split('/')
        if parts and parts[0]:
            tender_ids.add(parts[0])
    return sorted(tender_ids)


def pull_tenders_from_storage(tender_ids=None, output_dir=None):
    init_firebase()
    bucket = storage.bucket()
    download_dir = build_download_dir(output_dir)
    if tender_ids:
        tender_ids = [str(t).strip() for t in tender_ids if str(t).strip()]
    blobs = bucket.list_blobs(prefix='tenders/')
    count = 0

    for blob in blobs:
        if blob.name.endswith('/'):
            continue

        rel_path = blob.name.replace('tenders/', '', 1)
        parts = rel_path.split('/')
        if not parts or not parts[0]:
            continue

        if tender_ids and parts[0] not in tender_ids:
            continue

        safe_rel_path = safe_path(rel_path)
        local_path = os.path.join(download_dir, safe_rel_path)
        local_dir = os.path.dirname(local_path)
        if not os.path.exists(local_dir):
            os.makedirs(local_dir)

        print(f'Downloading {blob.name} to {local_path}...')
        blob.download_to_filename(local_path)
        count += 1

    if tender_ids:
        missing = [tid for tid in tender_ids if not os.path.isdir(os.path.join(download_dir, safe_path(tid)))]
        if missing:
            print(f'Warning: no files found for tender folder(s): {", ".join(missing)}')

    print(f'\nSuccessfully downloaded {count} file(s) to {download_dir}.')
    if tender_ids:
        print(f'Downloaded tender folders: {", ".join(tender_ids)}')
    else:
        print('You can now select this folder in the Batch Processing tab to run the enrichment!')
    return download_dir


def main():
    parser = argparse.ArgumentParser(description='Pull tenders from Firebase Storage.')
    parser.add_argument('--list', action='store_true', help='List tender folder IDs in Firebase Storage.')
    parser.add_argument('--tender-id', action='append', help='Tender folder ID to download. Repeat for multiple folders.')
    parser.add_argument('--output-dir', default=None, help='Local output directory under desktop_pipeline.')
    args = parser.parse_args()

    if args.list:
        try:
            ids = list_firebase_tender_ids()
            print(json.dumps(ids))
            return 0
        except Exception as e:
            print(f'Failed to list Firebase folders: {e}', file=sys.stderr)
            return 1

    try:
        pull_tenders_from_storage(tender_ids=args.tender_id, output_dir=args.output_dir)
        return 0
    except Exception as e:
        print(f'Failed to pull Firebase storage: {e}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
