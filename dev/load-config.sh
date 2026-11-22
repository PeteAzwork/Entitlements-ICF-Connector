#!/usr/bin/env bash
# WS7.06: load the provisioner into the dev IDM the way the runbook does (§2.3): createFullConfig first, then save its
# result once, then run the Test operation. Needs curl and python3.
#   SERVICE_URL  the service the connector talks to (default: the fake on the host, http://host.docker.internal:5276)
#   API_KEY      its key (default: the fake's dev-api-key). Never commit a real key.
#   IDM_URL      default http://localhost:8080/openidm; IDM_USER / IDM_PASSWORD default to the image's openidm-admin.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
SERVICE_URL="${SERVICE_URL:-http://host.docker.internal:5276}"
API_KEY="${API_KEY:-dev-api-key}"
IDM_URL="${IDM_URL:-http://localhost:8080/openidm}"
IDM_USER="${IDM_USER:-openidm-admin}"
IDM_PASSWORD="${IDM_PASSWORD:-openidm-admin}"
auth=(-H "X-OpenIDM-Username: ${IDM_USER}" -H "X-OpenIDM-Password: ${IDM_PASSWORD}" -H "X-OpenIDM-NoSession: true" -H "Content-Type: application/json")
work="$(mktemp -d)"; trap 'rm -rf "$work"' EXIT

echo "Waiting for IDM at ${IDM_URL} ..."
for _ in $(seq 1 120); do
  state=$(curl -s "${auth[@]}" "${IDM_URL}/info/ping" | python3 -c 'import sys,json;print(json.load(sys.stdin).get("state",""))' 2>/dev/null || true)
  [ "$state" = "ACTIVE_READY" ] && break; sleep 2
done
[ "$state" = "ACTIVE_READY" ] || { echo "IDM did not become ready" >&2; exit 1; }

SERVICE_URL="$SERVICE_URL" API_KEY="$API_KEY" python3 - "$here/../config/provisioner.openicf-entitlementsaccelerator.json" "$work/provisioner.json" <<'PY'
import json, os, sys
p = json.load(open(sys.argv[1]))
p.pop('_comment', None)
p['objectTypes'] = {}
p['configurationProperties']['serviceAddress'] = os.environ['SERVICE_URL']
p['configurationProperties']['password'] = os.environ['API_KEY']
json.dump(p, open(sys.argv[2], 'w'))
PY

echo "createFullConfig (adds the object types; runs the Test operation) ..."
code=$(curl -s -o "$work/full.json" -w '%{http_code}' "${auth[@]}" -X POST "${IDM_URL}/system?_action=createFullConfig" --data @"$work/provisioner.json")
[ "$code" = 200 ] || { echo "createFullConfig failed (HTTP $code): $(head -c 400 "$work/full.json")" >&2; exit 1; }
echo "Saving the provisioner ..."
curl -s -o /dev/null -w 'HTTP %{http_code}\n' "${auth[@]}" -X PUT "${IDM_URL}/config/provisioner.openicf/entitlementsaccelerator" --data @"$work/full.json"
sleep 3
echo "Test operation:"
curl -s "${auth[@]}" -X POST "${IDM_URL}/system/entitlementsaccelerator?_action=test"; echo
