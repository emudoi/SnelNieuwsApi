#!/usr/bin/env bash
# seed-vault.sh — one-time Vault KV seeding
#
# Reads a YAML file where each top-level key is a KV-v2 path under `secret/`,
# and each value is the secret's keys. Pushes to Vault via HTTP API.
#
# Usage:
#   export VAULT_ADDR=https://vault.v1.emudoi.com
#   export VAULT_TOKEN="$(ssh root@<control-plane> 'kubectl -n vault get secret vault-bootstrap -o jsonpath={.data.root-token} | base64 -d')"
#   bin/seed-vault.sh secrets.yaml
#
# Example secrets.yaml (gitignored — DO NOT commit):
#   _global/godaddy:
#     api_key: <key>
#     api_secret: <secret>
#   snelnieuws/api:
#     DB_HOST: postgresql.postgres.svc.cluster.local
#     DB_PORT: "5432"
#     DB_NAME: snelnieuws
#     DB_USER: postgres
#     DB_PASSWORD: <pw>
#     KAFKA_SUMMARIZED_IMPORT_ENABLED: "false"
#
# Idempotent: re-running with the same file overwrites the path with the
# same content (KV-v2 versioning preserves history). Add a new top-level
# key to seed a new path; remove a key from the file and Vault data stays
# (this script never deletes).

set -euo pipefail

FILE="${1:?Usage: $0 <yaml-file>}"
: "${VAULT_ADDR:?VAULT_ADDR not set}"
: "${VAULT_TOKEN:?VAULT_TOKEN not set}"

command -v yq >/dev/null || { echo "yq not installed (brew install yq)" >&2; exit 1; }
command -v jq >/dev/null || { echo "jq not installed (brew install jq)" >&2; exit 1; }
command -v curl >/dev/null || { echo "curl not installed" >&2; exit 1; }

[ -f "$FILE" ] || { echo "no such file: $FILE" >&2; exit 1; }

# Each top-level key in the YAML is a KV path (e.g. "_global/godaddy",
# "snelnieuws/api"). Iterate, build a JSON body for each, POST to Vault.
yq -r 'keys[]' "$FILE" | while read -r path; do
  echo "→ secret/data/$path"
  data=$(yq -o=json ".\"$path\"" "$FILE")
  body=$(jq -n --argjson d "$data" '{data: $d}')
  curl -sf -X POST \
    -H "X-Vault-Token: $VAULT_TOKEN" \
    -H "Content-Type: application/json" \
    --data "$body" \
    "$VAULT_ADDR/v1/secret/data/$path" >/dev/null
done

echo "Done."
