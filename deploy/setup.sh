#!/usr/bin/env bash
# One-time GCP infrastructure setup for CWFGW.
# Usage: ./deploy/setup.sh YOUR_GCP_PROJECT_ID
set -euo pipefail

PROJECT_ID="${1:?Usage: ./deploy/setup.sh PROJECT_ID}"
REGION="us-west1"
SQL_INSTANCE="cwfgw-sandbox"
DB_NAME="cwfgw"
DB_USER="cwfgw"
REPO="cwfgw"

echo "==> Setting project to ${PROJECT_ID}"
gcloud config set project "${PROJECT_ID}"

echo "==> Enabling required APIs"
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  secretmanager.googleapis.com

echo "==> Creating Artifact Registry repo"
if ! gcloud artifacts repositories describe "${REPO}" --location="${REGION}" &>/dev/null; then
  gcloud artifacts repositories create "${REPO}" \
    --repository-format=docker \
    --location="${REGION}" \
    --description="CWFGW Docker images"
else
  echo "    (already exists)"
fi

echo "==> Checking Cloud SQL instance"
if ! gcloud sql instances describe "${SQL_INSTANCE}" &>/dev/null; then
  echo "    Creating instance (this takes a few minutes)..."
  gcloud sql instances create "${SQL_INSTANCE}" \
    --database-version=POSTGRES_16 \
    --tier=db-f1-micro \
    --region="${REGION}" \
    --storage-auto-increase
else
  echo "    Instance ${SQL_INSTANCE} already exists"
fi

echo "==> Creating database"
if ! gcloud sql databases describe "${DB_NAME}" --instance="${SQL_INSTANCE}" &>/dev/null; then
  gcloud sql databases create "${DB_NAME}" --instance="${SQL_INSTANCE}"
else
  echo "    (already exists)"
fi

echo "==> Setting database user password"
DB_PASSWORD="$(openssl rand -base64 18)Aa1!"
if ! gcloud sql users describe "${DB_USER}" --instance="${SQL_INSTANCE}" &>/dev/null; then
  echo "    Creating user ${DB_USER}"
  gcloud sql users create "${DB_USER}" \
    --instance="${SQL_INSTANCE}" \
    --password="${DB_PASSWORD}"
else
  gcloud sql users set-password "${DB_USER}" \
    --instance="${SQL_INSTANCE}" \
    --password="${DB_PASSWORD}"
fi

echo "==> Storing password in Secret Manager"
printf "%s" "${DB_PASSWORD}" | gcloud secrets create cwfgw-db-password \
  --data-file=- \
  2>/dev/null || \
printf "%s" "${DB_PASSWORD}" | gcloud secrets versions add cwfgw-db-password \
  --data-file=-

echo "==> Granting Cloud Run access to the secret"
PROJECT_NUMBER="$(gcloud projects describe "${PROJECT_ID}" --format='value(projectNumber)')"
gcloud secrets add-iam-policy-binding cwfgw-db-password \
  --member="serviceAccount:${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --role="roles/secretmanager.secretAccessor" \
  --quiet

echo "==> Granting Cloud Build permission to deploy to Cloud Run"
gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
  --member="serviceAccount:${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com" \
  --role="roles/run.admin" \
  --quiet
gcloud iam service-accounts add-iam-policy-binding \
  "${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --member="serviceAccount:${PROJECT_NUMBER}@cloudbuild.gserviceaccount.com" \
  --role="roles/iam.serviceAccountUser" \
  --quiet

echo ""
echo "=== Setup complete ==="
echo "Next steps:"
echo "  1. Connect a Cloud Build trigger to your repo, or run manually:"
echo "     gcloud builds submit --config=cloudbuild.yaml"
echo "  2. Check your service:"
echo "     gcloud run services describe cwfgw --region=${REGION}"
