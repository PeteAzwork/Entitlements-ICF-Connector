#!/usr/bin/env bash
# Deployment walkthrough (docs/deployment-guide.md, appendix A). Deploys the two release artefacts into a brand-new
# IDM 7.5 container step by step, exactly as the guide describes, against the in-memory service stand-in; runs a first
# reconciliation; restarts IDM; then uninstalls. Its output is the guide's worked example.
#
#   ./gradlew :connector:distZip :discovery:distZip
#   ./gradlew :connector:runFakeService &          # the stand-in on 127.0.0.1:5276, key dev-api-key
#   dev/deploy-walkthrough.sh connector/build/distributions/graphentity-connector-*.zip \
#                             discovery/build/distributions/graphentity-discovery-*.zip
#
# Needs docker, curl, python3, unzip and Java 17+ (for the discovery tool). IDM runs on the host network, on port 8080,
# so nothing else may use that port. KEEP=1 leaves the container running at the end.
#   SERVICE_URL  default http://127.0.0.1:5276      API_KEY  default dev-api-key (the stand-in's; never a real key)
#   IDM_IMAGE    default gcr.io/forgerock-io/idm:7.5.0
set -euo pipefail
[ $# -eq 2 ] || { sed -n '2,15p' "$0"; exit 2; }
CONNECTOR_ZIP=$(realpath "$1"); DISCOVERY_ZIP=$(realpath "$2")
here="$(cd "$(dirname "$0")" && pwd)"; fixtures="$here/../integration/src/test/resources/idm"
IMAGE="${IDM_IMAGE:-gcr.io/forgerock-io/idm:7.5.0}"
NAME=graphentity-walkthrough
SERVICE_URL="${SERVICE_URL:-http://127.0.0.1:5276}"
API_KEY="${API_KEY:-dev-api-key}"
IDM=http://localhost:8080/openidm
work="$(mktemp -d)"; cd "$work"

step() { printf '\n== %s\n' "$*"; }
run()  { printf '$ %s\n' "$*"; "$@"; }
idm()  {   # idm METHOD PATH [curl args...]: the admin REST API, as the image's default admin; stops on an HTTP error
  local method=$1 path=$2; shift 2
  curl -sS --fail-with-body -H 'X-OpenIDM-Username: openidm-admin' -H 'X-OpenIDM-Password: openidm-admin' \
       -H 'X-OpenIDM-NoSession: true' -H 'Content-Type: application/json' -X "$method" "$IDM/$path" "$@"
}
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
wait_ready() {
  for _ in $(seq 1 150); do
    [ "$(idm GET info/ping 2>/dev/null | json 'd.get("state","")' 2>/dev/null || true)" = ACTIVE_READY ] && return 0; sleep 2
  done
  echo "IDM did not become ready" >&2; exit 1
}
idm_log() { docker exec "$NAME" cat /opt/openidm/logs/openidm0.log.0; }
wait_activated() {   # after a provisioner save: wait for the connector to be reactivated, then for quiet (H21)
  local before=$1
  for _ in $(seq 1 120); do
    [ "$(idm_log | grep -c 'component graphentity is activated')" -gt "$before" ] && { sleep 3; return 0; }; sleep 1
  done
  echo "the connector was not reactivated" >&2; exit 1
}

step "1. Start an empty IDM 7.5 ($IMAGE)"
docker rm -f "$NAME" >/dev/null 2>&1 || true
docker run -d --name "$NAME" --network host "$IMAGE" >/dev/null
wait_ready
idm GET info/version | json '"IDM", d["productVersion"]'
run docker exec "$NAME" ls /opt/openidm/connectors

step "2. Unpack the connector release over the IDM installation (guide §4)"
sha256sum "$CONNECTOR_ZIP" | sed "s|$CONNECTOR_ZIP|$(basename "$CONNECTOR_ZIP")|"
mkdir release && unzip -q "$CONNECTOR_ZIP" -d release
run cat release/VERSION
# Owned by IDM's user (uid 11111 in the image), as the guide requires; samples/ stays on the workstation.
tar --owner=11111 --group=0 -C release -cf - connectors script | docker cp - "$NAME":/opt/openidm/
run docker exec "$NAME" find /opt/openidm/connectors/graphentity /opt/openidm/script/lib -maxdepth 2

step "3. Managed objects: the deployment's managed.json, with reverse properties and the ID-change guard (guide §8)"
idm PUT config/managed --data @"$fixtures/managed.json" | json '[o["name"] for o in d["objects"]]'

step "4. Run the discovery tool offline, on an export of managed.json (guide §6.1)"
mkdir tool && unzip -q "$DISCOVERY_ZIP" -d tool
idm GET config/managed > managed.json
cp "$fixtures/answers.json" answers.json
run tool/graphentity-discovery-*/bin/discovery --managed managed.json --types user,account --answers answers.json \
    --current release/samples/provisioner.openicf-graphentity.json --out generated
run ls generated

step "5. Set the service address and the key in the generated provisioner (guide §6.2)"
SERVICE_URL="$SERVICE_URL" API_KEY="$API_KEY" python3 - <<'PY'
import json, os
p = json.load(open('generated/provisioner.openicf-graphentity.json'))
p.pop('_comment', None); p.get('objectTypes', {}).pop('_comment', None)
p['configurationProperties']['serviceAddress'] = os.environ['SERVICE_URL']
p['configurationProperties']['password'] = os.environ['API_KEY']
json.dump(p, open('provisioner.json', 'w'), indent=2)
print('serviceAddress', p['configurationProperties']['serviceAddress'], '| password set (not shown)')
PY

step "6. createFullConfig, then save its result once; wait for the connector; check the save is the one stored (guide §6.3)"
idm POST 'system?_action=createFullConfig' --data @provisioner.json > full.json
json '"objectTypes:", sorted(d["objectTypes"])' < full.json
before=$(idm_log | grep -c 'component graphentity is activated' || true)
idm PUT config/provisioner.openicf/graphentity --data @full.json >/dev/null
wait_activated "$before"
idm GET config/provisioner.openicf/graphentity | json '"stored serviceAddress:", d["configurationProperties"]["serviceAddress"], "| password stored encrypted:", isinstance(d["configurationProperties"]["password"], dict)'

step "7. Check the connector (guide §6.4)"
idm POST 'system/graphentity?_action=test' | json '"test ok:", d["ok"]'
idm GET 'system/graphentity/user?_queryFilter=true&_pageSize=1' | json '"user search answers, results:", len(d["result"])'

step "8. Mappings: lint, then load with implicit sync off (guide §9)"
run tool/graphentity-discovery-*/bin/discovery --lint generated/sync-user.json generated/sync-account.json
python3 - <<'PY'
import json
maps = []
for t in ('account', 'user'):
    m = json.load(open(f'generated/sync-{t}.json'))
    for one in (m.get('mappings') if 'mappings' in m else [m]):
        one['enableSync'] = False; maps.append(one)
json.dump({'mappings': maps}, open('sync.json', 'w'), indent=2)
print('mappings:', [m['name'] for m in maps])
PY
idm PUT config/sync --data @sync.json >/dev/null
sleep 5

step "9. Initial load: accounts first, then users (runbook §3)"
idm POST 'managed/user?_action=create' --data '{"userName":"walk-1","givenName":"Walk","sn":"Through","mail":"walk-1@example.com"}' | json '"created user", d["userName"]'
uid=$(idm GET 'managed/user?_queryFilter=userName+eq+"walk-1"&_fields=_id' | json 'd["result"][0]["_id"]')
idm POST 'managed/account?_action=create' --data "{\"accountId\":\"walk-acct-1\",\"name\":\"Walk account\",\"productCode\":\"P1\",\"ownedBy\":{\"_ref\":\"managed/user/$uid\"}}" | json '"created account", d["accountId"]'
for m in managedAccount_systemGraphentityAccount managedUser_systemGraphentityUser; do
  id=$(idm POST "recon?_action=recon&mapping=$m&waitForCompletion=true" | json 'd["_id"]')
  idm GET "recon/$id" | json '"'"$m"'", d["state"], {k: v for k, v in d["situationSummary"].items() if v}'
done
curl -s -H "X-API-KEY: $API_KEY" -H 'Content-Type: application/json' -X POST "$SERVICE_URL/admin/entities/search" --data '{}' \
  | json '"in the service:", sorted(e["uid"] for e in d["results"])'
curl -s -H "X-API-KEY: $API_KEY" "$SERVICE_URL/admin/entities/walk-1" \
  | json '"walk-1 relationships:", [(r["source"], r["type"], r["target"]) for r in d["relationships"]]'

step "10. Implicit sync on, and the nightly schedules (guide §10, runbook §4)"
python3 -c "import json; s=json.load(open('sync.json')); [m.update(enableSync=True) for m in s['mappings']]; json.dump(s, open('sync.json','w'))"
idm PUT config/sync --data @sync.json >/dev/null
for t in account user; do
  python3 -c "import json,sys; s=json.load(open(sys.argv[1])); s.pop('_comment',None); json.dump(s, open('schedule.json','w'))" \
    release/samples/schedule-recon-graphentity-$t.json
  idm PUT config/schedule/recon-graphentity-$t --data @schedule.json | json '"schedule", d["_id"], d["schedule"], "enabled:", d["enabled"]'
done

step "11. Restart IDM: the connector comes back by itself"
docker restart "$NAME" >/dev/null; wait_ready
idm POST 'system/graphentity?_action=test' | json '"test ok after restart:", d["ok"]'

step "12. Uninstall (guide §13), in reverse order"
for t in account user; do idm DELETE config/schedule/recon-graphentity-$t | json '"deleted", d["_id"]'; done
idm PUT config/sync --data '{"mappings": []}' >/dev/null && echo "mappings removed"
idm GET config/managed | python3 -c "
import json,sys
m=json.load(sys.stdin)
for o in m['objects']:
    if 'onIdPropertyChange' in json.dumps(o.get('onUpdate', {})): o.pop('onUpdate')
json.dump(m, open('managed-without-guard.json','w'))"
idm PUT config/managed --data @managed-without-guard.json >/dev/null && echo "ID-change guards removed"
idm DELETE config/provisioner.openicf/graphentity | json '"deleted", d["_id"]'
sleep 5
printf 'system/graphentity now answers HTTP %s\n' "$(idm POST 'system/graphentity?_action=test' -o /dev/null -w '%{http_code}' 2>/dev/null || true)"
run docker exec "$NAME" rm -rf /opt/openidm/connectors/graphentity /opt/openidm/script/lib/graphEntityPayload.js
echo "The entities written to the service stay there: tell the service team (guide §13)."

[ "${KEEP:-0}" = 1 ] || docker rm -f "$NAME" >/dev/null
step "Done"
