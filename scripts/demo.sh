#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[[ -f .env ]] || { echo "Run make up first" >&2; exit 1; }
set -a
source .env
set +a

scenario="${1:-full}"
gateway="${RELAYFORGE_GATEWAY:-http://localhost:8080}"
sink="${RELAYFORGE_SINK:-http://localhost:8090}"
run_id="$(date +%s)-$RANDOM"

json_value() { python3 -c "import json,sys; print(json.load(sys.stdin)$1)"; }
pretty() { python3 -m json.tool; }

password_token() {
  local username="$1" password="$2"
  curl --fail --silent --show-error \
    --data-urlencode "username=$username" --data-urlencode "password=$password" \
    --data-urlencode 'grant_type=password' --data-urlencode 'client_id=relayforge-cli' \
    http://localhost:8180/realms/relayforge/protocol/openid-connect/token \
    | json_value '["access_token"]'
}

producer_token() {
  curl --fail --silent --show-error \
    --data-urlencode 'grant_type=client_credentials' --data-urlencode 'client_id=acme-producer' \
    --data-urlencode "client_secret=$ACME_PRODUCER_SECRET" \
    http://localhost:8180/realms/relayforge/protocol/openid-connect/token \
    | json_value '["access_token"]'
}

admin_token="$(password_token acme-admin "$ACME_ADMIN_PASSWORD")"
operator_token="$(password_token acme-operator "$ACME_OPERATOR_PASSWORD")"
producer="$(producer_token)"

create_endpoint() {
  local sink_id="$1" event_type="$2" mode="$3" failures="$4" attempts="$5"
  local response endpoint_id secret
  response="$(curl --fail --silent --show-error --request POST "$gateway/v1/endpoints" \
    --header "Authorization: Bearer $admin_token" --header 'Content-Type: application/json' \
    --data "{\"name\":\"demo-$sink_id\",\"url\":\"http://webhook-sink:8090/webhooks/$sink_id\",\"subscriptions\":[{\"eventType\":\"$event_type\",\"filterJson\":\"{}\"}],\"policy\":{\"maxAttempts\":$attempts,\"initialDelay\":\"PT0.5S\",\"maxDelay\":\"PT3S\",\"maxRetryAfter\":\"PT5S\",\"endpointConcurrency\":1,\"preserveOrder\":true}}")"
  endpoint_id="$(json_value '["id"]' <<<"$response")"
  secret="$(json_value '["signingSecret"]' <<<"$response")"
  curl --fail --silent --show-error --output /dev/null --request PUT "$sink/test/endpoints/$sink_id" \
    --header 'Content-Type: application/json' \
    --data "{\"currentSecret\":\"$secret\",\"mode\":\"$mode\",\"failuresBeforeSuccess\":$failures,\"delayMillis\":12000,\"retryAfterSeconds\":2}"
  printf '%s|%s' "$endpoint_id" "$secret"
}

publish() {
  local event_type="$1" key="$2"
  curl --fail --silent --show-error --request POST "$gateway/v1/events" \
    --header "Authorization: Bearer $producer" --header "Idempotency-Key: $key" \
    --header "Webhook-Event-Type: $event_type" --header "Webhook-Source-Id: demo-$run_id" \
    --header 'Content-Type: application/json' \
    --data "{\"demoRun\":\"$run_id\",\"scenario\":\"$scenario\",\"amount\":4200}"
}

wait_for_state() {
  local event_id="$1" expected="$2" attempts="${3:-60}" response
  for ((i=1; i<=attempts; i++)); do
    response="$(curl --fail --silent --show-error "$gateway/v1/deliveries?eventId=$event_id" \
      --header "Authorization: Bearer $admin_token")"
    if python3 -c 'import json,sys; expected=set(sys.argv[1].split(",")); items=json.load(sys.stdin); states={item["state"] for item in items}; raise SystemExit(0 if items and states.issubset(expected) else 1)' "$expected" <<<"$response"
    then
      printf '%s' "$response"
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for states $expected" >&2
  return 1
}

run_single() {
  local mode="$1" failures="$2" attempts="$3" expected="$4"
  local event_type="demo.$scenario.$run_id" config response event_id timeline
  config="$(create_endpoint "$scenario-$run_id" "$event_type" "$mode" "$failures" "$attempts")"
  response="$(publish "$event_type" "demo-$scenario-$run_id")"
  event_id="$(json_value '["eventId"]' <<<"$response")"
  echo "Accepted event $event_id"
  timeline="$(wait_for_state "$event_id" "$expected")"
  pretty <<<"$timeline"
}

case "$scenario" in
  success) run_single SUCCESS 0 3 SUCCEEDED ;;
  retry) run_single FLAKY 2 5 SUCCEEDED ;;
  rate-limit) run_single RATE_LIMIT 1 4 SUCCEEDED ;;
  dead-letter) run_single ALWAYS_500 0 3 DEAD_LETTERED ;;
  replay)
    event_type="demo.replay.$run_id"
    config="$(create_endpoint "replay-$run_id" "$event_type" ALWAYS_500 0 3)"
    secret="${config#*|}"
    response="$(publish "$event_type" "demo-replay-$run_id")"
    event_id="$(json_value '["eventId"]' <<<"$response")"
    deliveries="$(wait_for_state "$event_id" DEAD_LETTERED)"
    delivery_id="$(json_value '[0]["deliveryId"]' <<<"$deliveries")"
    curl --fail --silent --show-error --output /dev/null --request PUT "$sink/test/endpoints/replay-$run_id" \
      --header 'Content-Type: application/json' \
      --data "{\"currentSecret\":\"$secret\",\"mode\":\"SUCCESS\",\"failuresBeforeSuccess\":0,\"delayMillis\":0,\"retryAfterSeconds\":0}"
    curl --fail --silent --show-error --output /dev/null --request POST "$gateway/v1/deliveries/$delivery_id/replay" \
      --header "Authorization: Bearer $operator_token"
    echo "Replayed delivery $delivery_id without creating a new event"
    wait_for_state "$event_id" SUCCEEDED | pretty
    ;;
  rotation)
    event_type="demo.rotation.$run_id"
    config="$(create_endpoint "rotation-$run_id" "$event_type" SUCCESS 0 3)"
    endpoint_id="${config%%|*}"; old_secret="${config#*|}"
    rotation="$(curl --fail --silent --show-error --request POST "$gateway/v1/endpoints/$endpoint_id/rotate-secret" \
      --header "Authorization: Bearer $admin_token")"
    new_secret="$(json_value '["signingSecret"]' <<<"$rotation")"
    overlap="$(json_value '["previousSecretValidUntil"]' <<<"$rotation")"
    curl --fail --silent --show-error --output /dev/null --request PUT "$sink/test/endpoints/rotation-$run_id" \
      --header 'Content-Type: application/json' \
      --data "{\"currentSecret\":\"$new_secret\",\"previousSecret\":\"$old_secret\",\"previousValidUntil\":\"$overlap\",\"mode\":\"SUCCESS\",\"failuresBeforeSuccess\":0,\"delayMillis\":0,\"retryAfterSeconds\":0}"
    response="$(publish "$event_type" "demo-rotation-$run_id")"
    event_id="$(json_value '["eventId"]' <<<"$response")"
    echo "Rotated endpoint $endpoint_id; old key valid until $overlap"
    wait_for_state "$event_id" SUCCEEDED | pretty
    ;;
  full)
    event_type="demo.full.$run_id"
    success_config="$(create_endpoint "success-$run_id" "$event_type" SUCCESS 0 3)"
    retry_config="$(create_endpoint "retry-$run_id" "$event_type" FLAKY 2 5)"
    dead_config="$(create_endpoint "dead-$run_id" "$event_type" ALWAYS_500 0 3)"
    dead_secret="${dead_config#*|}"
    response="$(publish "$event_type" "demo-full-$run_id")"
    event_id="$(json_value '["eventId"]' <<<"$response")"
    echo "Accepted one logical event $event_id for three endpoints"
    deliveries="$(wait_for_state "$event_id" SUCCEEDED,DEAD_LETTERED 90)"
    pretty <<<"$deliveries"
    dead_delivery="$(python3 -c 'import json,sys; print(next(x["deliveryId"] for x in json.load(sys.stdin) if x["state"]=="DEAD_LETTERED"))' <<<"$deliveries")"
    curl --fail --silent --show-error --output /dev/null --request PUT "$sink/test/endpoints/dead-$run_id" \
      --header 'Content-Type: application/json' \
      --data "{\"currentSecret\":\"$dead_secret\",\"mode\":\"SUCCESS\",\"failuresBeforeSuccess\":0,\"delayMillis\":0,\"retryAfterSeconds\":0}"
    curl --fail --silent --show-error --output /dev/null --request POST "$gateway/v1/deliveries/$dead_delivery/replay" \
      --header "Authorization: Bearer $operator_token"
    echo "Receiver repaired; replayed dead-lettered delivery $dead_delivery"
    wait_for_state "$event_id" SUCCEEDED 60 | pretty
    curl --fail --silent --show-error "$gateway/v1/deliveries/$dead_delivery" \
      --header "Authorization: Bearer $admin_token" | pretty
    ;;
  *) echo "Usage: $0 {full|success|retry|rate-limit|dead-letter|replay|rotation}" >&2; exit 2 ;;
esac
