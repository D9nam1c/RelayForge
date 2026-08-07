#!/usr/bin/env bash
set -euo pipefail

for database in relayforge_control relayforge_ingest relayforge_dispatch relayforge_worker; do
  exists="$(psql --username "$POSTGRES_USER" --dbname postgres --tuples-only --no-align \
    --command "select 1 from pg_database where datname = '$database'")"
  if [[ "$exists" != "1" ]]; then
    psql --username "$POSTGRES_USER" --dbname postgres --set ON_ERROR_STOP=1 \
      --command "create database $database"
  fi
done
