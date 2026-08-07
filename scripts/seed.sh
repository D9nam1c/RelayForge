#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
[[ -f .env ]] || { echo "Run make env first" >&2; exit 1; }
set -a
source .env
set +a

token_json="$(curl --fail --silent --show-error \
  --data-urlencode "username=$KEYCLOAK_ADMIN" \
  --data-urlencode "password=$KEYCLOAK_ADMIN_PASSWORD" \
  --data-urlencode 'grant_type=password' \
  --data-urlencode 'client_id=admin-cli' \
  http://localhost:8180/realms/master/protocol/openid-connect/token)"
admin_token="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])' <<<"$token_json")"

status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  --header "Authorization: Bearer $admin_token" http://localhost:8180/admin/realms/relayforge)"
if [[ "$status" == "200" ]]; then
  echo "Keycloak realm already seeded"
  exit 0
fi

realm_file="$(mktemp "${TMPDIR:-/tmp}/relayforge-realm.XXXXXX")"
trap 'rm -f "$realm_file"' EXIT
sed \
  -e "s|__ACME_PRODUCER_SECRET__|$ACME_PRODUCER_SECRET|g" \
  -e "s|__GLOBEX_PRODUCER_SECRET__|$GLOBEX_PRODUCER_SECRET|g" \
  -e "s|__ACME_ADMIN_PASSWORD__|$ACME_ADMIN_PASSWORD|g" \
  -e "s|__ACME_OPERATOR_PASSWORD__|$ACME_OPERATOR_PASSWORD|g" \
  -e "s|__GLOBEX_ADMIN_PASSWORD__|$GLOBEX_ADMIN_PASSWORD|g" \
  infrastructure/keycloak/realm-template.json > "$realm_file"

curl --fail --silent --show-error --output /dev/null \
  --request POST --header "Authorization: Bearer $admin_token" \
  --header 'Content-Type: application/json' --data-binary "@$realm_file" \
  http://localhost:8180/admin/realms
echo "Seeded Keycloak realm, two tenants, users, roles, and producer clients"
