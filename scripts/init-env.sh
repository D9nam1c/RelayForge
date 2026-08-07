#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  echo ".env already exists; leaving it unchanged"
  exit 0
fi

umask 077
random_urlsafe() { openssl rand -base64 36 | tr -d '\n=+/' | cut -c1-40; }
master_key="$(openssl rand -base64 32 | tr -d '\n')"

{
  echo "DB_PASSWORD=$(random_urlsafe)"
  echo "RELAYFORGE_MASTER_KEY=$master_key"
  echo "INTERNAL_TOKEN=$(random_urlsafe)"
  echo "KEYCLOAK_ADMIN=relayforge-bootstrap"
  echo "KEYCLOAK_ADMIN_PASSWORD=$(random_urlsafe)"
  echo "ACME_ADMIN_PASSWORD=$(random_urlsafe)"
  echo "ACME_OPERATOR_PASSWORD=$(random_urlsafe)"
  echo "GLOBEX_ADMIN_PASSWORD=$(random_urlsafe)"
  echo "ACME_PRODUCER_SECRET=$(random_urlsafe)"
  echo "GLOBEX_PRODUCER_SECRET=$(random_urlsafe)"
  echo "GRAFANA_ADMIN=relayforge"
  echo "GRAFANA_ADMIN_PASSWORD=$(random_urlsafe)"
} > .env

echo "Generated local-only credentials in .env (mode 600; ignored by Git)"
