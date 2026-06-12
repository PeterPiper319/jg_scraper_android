# Sync function repomix

This file summarizes the admin Firebase sync flow used by the Sync button on the admin page.

## Purpose
The Sync button imports JSON tender files from Firebase Storage into Firestore so the app can serve the latest tender data.

## Main flow
1. The admin clicks the Sync button in the admin dashboard.
2. The UI confirms the action and requests a fresh Firebase ID token.
3. The frontend sends a POST request to the admin sync endpoint.
4. The Netlify function validates the admin token, reads Storage JSON files, merges the required manifest files, and upserts the result into Firestore.
5. The UI displays a summary of imported, skipped, and failed tender records.

## Key files involved
- src/components/admin/AdminDashboard.tsx
  - Contains the `handleSyncTenders` function.
  - Shows the confirmation dialog.
  - Calls the sync endpoint with the current storage prefix.
  - Converts the backend result into the visible success/error message.

- src/lib/adminApi.ts
  - Builds the correct admin sync endpoint:
    - local/dev: `/api/admin/sync-tenders`
    - production: `/.netlify/functions/sync-tenders`

- netlify/functions/sync-tenders.js
  - Backend handler for the sync operation.
  - Verifies the Firebase admin token.
  - Reads files from the configured Storage prefix.
  - Filters for the expected JSON file types.
  - Merges `manifest.json` + `concept-manifest-enrichment.json` pairs.
  - Writes tidy tender documents to Firestore (`tenders` collection).

## What the backend does
- Validates the Authorization header and requires a global admin email.
- Initializes Firebase Admin SDK using `FIREBASE_SERVICE_ACCOUNT_JSON`.
- Normalizes the storage prefix (`tenders/` by default).
- Lists files under that prefix in Firebase Storage.
- Only processes JSON files that match:
  - `manifest.json`
  - `concept-manifest-enrichment.json`
- Groups files by directory and pairs manifest + concept files.
- Builds a tender object from the merged content.
- Uses Firestore batch writes and upserts each tender document.
- Returns a JSON summary with:
  - `candidateFiles`
  - `imported`
  - `skipped`
  - `failed`
  - `tenderIds`
  - `errors`

## Important behavior notes
- The sync uses the storage prefix entered in the admin UI, defaulting to `tenders/`.
- The function is intentionally strict about which JSON files it processes.
- It skips incomplete pairs (missing manifest or concept manifest).
- It reports diagnostics if the Firebase ID token is invalid or if the backend project does not match the token audience.

## Summary
The admin Sync button is a two-part operation:
- frontend: collect the admin token and trigger the sync request
- backend: validate admin access, import Storage JSON tender data, and upsert it into Firestore
