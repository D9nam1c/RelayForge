#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[[ -f .env ]] || { echo "Run make up first" >&2; exit 1; }
set -a; source .env; set +a
count="${COUNT:-500}"
concurrency="${CONCURRENCY:-16}"

token="$(curl --fail --silent --show-error --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=acme-producer' --data-urlencode "client_secret=$ACME_PRODUCER_SECRET" \
  http://localhost:8180/realms/relayforge/protocol/openid-connect/token \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')"

started="$(date +%s)"
export token
seq "$count" | xargs -P "$concurrency" -I '{}' sh -c '
  curl --fail --silent --output /dev/null --request POST http://localhost:8080/v1/events \
    --header "Authorization: Bearer $token" --header "Idempotency-Key: load-{}" \
    --header "Webhook-Event-Type: load.test" --header "Webhook-Source-Id: load-{}" \
    --header "Content-Type: application/json" --data "{\"sequence\":{}}"'
elapsed="$(( $(date +%s) - started ))"
(( elapsed == 0 )) && elapsed=1
echo "Accepted $count requests with concurrency $concurrency in ${elapsed}s ($((count / elapsed)) requests/s client-observed)."
echo "This measures one local machine and is not a production throughput claim."
