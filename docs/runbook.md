# Runbook: Graph Entity Service connector for Ping IDM 7.5

**Audience:** IDM operators and support.
**Covers:** installation (plan WS7.08), onboarding, schedules, monitoring, reading the logs, known issues, key rotation, troubleshooting, the non-production dry run, and upgrades (WS9.01–9.03, 9.06).
**Status:** draft for operations review (WS9.01 acceptance). Everything here was exercised against IDM 7.5.0 in `integration/` unless it says otherwise.

## 1. What the connector does, and what it does not

It synchronises IDM managed objects **one way**, from IDM to the Graph Entity Service:
- **Each configured type** (for example `user`, `account`) is its own object class, `system/graphentity/<type>`.
- **Each write restates the whole entity** (P15): its attributes, its archetypes and every relationship touching it, assembled from IDM's state by the mapping library.

At this stage (milestone M5) it does **not**:
- **Detect changes made directly in the service (drift, P16).** A relationship or attribute changed in the service outside IDM stays changed until IDM next writes that entity. Reconciliation restates every linked entity (section 4), which repairs most of it on the next run, but nothing alerts you.
- **Delete orphans.** An entity in the service with no IDM counterpart is *reported* by reconciliation (`UNASSIGNED`, `UNQUALIFIED`, `TARGET_IGNORED`), never deleted. Deleting orphans is plan WS10, after a per-type promotion checklist.
- **Tolerate a second writer of relationships (design A1).** If any other system writes relationships to the service, this connector removes them on the next write of either entity, because IDM's state is taken as the complete truth. Treat any second writer as a change that needs design review first.

## 2. Installation (WS7.08)

**The full procedure is `docs/deployment-guide.md`.** It also covers TLS with a private CA, proxies, every setting, container images, clusters, upgrades, rollback and removal, with a worked example. This section is the short form.

### 2.1 Prerequisites
- **IDM 7.5.** The image `gcr.io/forgerock-io/idm:7.5.0` was used for testing. It ships the Scripted REST connector 1.5.20.21, Groovy 3.0.9, Rhino 1.7.14 and runs on JDK 17.
- **The service's API key, and its URL over https.** Plain http is accepted only for localhost (section 7).
- **The release artefacts:** `./gradlew :connector:distZip :discovery:distZip` produces:
  - `graphentity-connector-<version>.zip`, for the IDM servers. Its `VERSION` file records the commit and the library mode.
  - `graphentity-discovery-<version>.zip`, the discovery tool, for a workstation with Java 17 or later.

### 2.2 Copy the artefact
Unpack the zip over the IDM installation directory (`<idm>`):

```text
connectors/graphentity/scripts/*.groovy     the seven ICF operation scripts
connectors/graphentity/lib/au/…/*.groovy    the connector library, as Groovy sources (script_vs_jar.md)
script/lib/graphEntityPayload.js            the mapping library
samples/                                    provisioner, mapping and schedule samples
VERSION
```

- **Permissions:** the files must be readable by the IDM process user (uid 11111 in the image).
- **No edits on the server:** deploy only from the release artefact, so servers don't drift from it.
- **Jar instead of sources:** if the deployment uses the library as a jar (`-PlibraryAs=jar`), `lib/` holds `graphentity-connector-lib.jar`, and the provisioner's `classpath` must name that file.

### 2.3 Generate and load the configuration
1. **Generate** `objectTypes`, the provisioner and the mapping skeletons with the discovery tool, from the live `managed.json`. Write your answers to the tool's prompts (entity kinds, ID properties, archetypes, relationship overrides) in `answers.json`. Use the read-only IDM account, with its password in the environment:

   ```bash
   IDM_DISCOVERY_PASSWORD=… graphentity-discovery-<version>/bin/discovery --idm-url https://idm.example.com \
       --idm-user discovery-reader --types user,account --answers answers.json \
       --current samples/provisioner.openicf-graphentity.json --out generated
   ```

   Or offline, from an export of `managed.json`: `--managed managed.json` in place of `--idm-url` and `--idm-user` (deployment guide §6.1).

   Review the printed warnings, errors and diff. Nothing is written if there are errors.
2. **Edit `generated/provisioner.openicf-graphentity.json`:**
   - `serviceAddress`: the service's https URL;
   - `password`: the service's API key. IDM encrypts it when the configuration is saved. Never commit it.
   - Leave `username` empty and `defaultAuthMethod` as `NONE`, so IDM adds no credentials of its own (P13).
3. **Generate the object types, then save once.** Run `POST <idm>/openidm/system?_action=createFullConfig` with that file as the body, then `PUT` the response to `config/provisioner.openicf/graphentity`.
   - The response carries the `objectTypes`; IDM does not fill them in by itself (H10).
   - **Do not save the provisioner first and then call `createFullConfig`.** On a fresh IDM, two first initialisations at once fail, with a `LinkageError` or a Groovy `BUG! exception` (H11, `script_vs_jar.md`).
   - **Alternative:** put the full provisioner in `conf/` before starting IDM.
   - **One provisioner change at a time.** IDM silently discards a save that arrives while it is still applying the previous one (H21). After each save, wait for `OpenICF Provisioner Service component graphentity is activated` in IDM's log, then check that `GET config/provisioner.openicf/graphentity` shows your change.
4. **Check the connector:**
   - `POST system/graphentity?_action=test` answers `"ok": true`;
   - its `objectTypes` list `user` and `account`;
   - `GET system/graphentity/user?_queryFilter=true&_pageSize=1` answers.
5. **Managed objects.** Every relationship property you sync needs `reversePropertyName` on both sides. For each synced type, add the ID-change guard (WS9.04, section 6.4) as its `onUpdate` hook:

   ```json
   "onUpdate": { "type": "text/javascript",
                 "source": "require('lib/graphEntityPayload').onIdPropertyChange(newObject, oldObject, 'user');" }
   ```

   If the type already has an `onUpdate` script, call the guard from inside that script.
6. **Mappings.**
   - Lint the generated `sync-<type>.json`: `bin/discovery --lint generated/sync-user.json …`.
   - Merge them into `conf/sync.json` with implicit sync **off** for onboarding (section 3).
   - The lint enforces two rules on the `entityRelationships` and `directArchetypes` mappings: they carry `"source": ""` and no `condition` (H12, §8.4).
7. **Schedules:** see section 4.

## 3. Onboarding (initial load)

1. **Implicit sync off** (`"enableSync": false` on the graphentity mappings) during the initial load. Never run the onboarding reconciliation and live sync together.
2. **Reconcile both types, one straight after the other:** `managedAccount_systemGraphentityAccount`, then `managedUser_systemGraphentityUser`. The other order works the same way.
   - **Expect one `dropped edge <user> -Owns-> <account>` WARN per relationship** during the first reconciliation, whichever type it is. The first entity written finds the other end of its relationship absent, so the service drops it.
   - The second reconciliation's write heals each one (design Appendix A). That's harmless, but it's noise. Only a drop *after* both reconciliations needs attention.
   - *Corrected 2026-11-22:* this section used to say that accounts first avoided the warnings. The load test showed every warning logged during the account reconciliation (`docs/load-testing.md` §6).
3. **Review each reconciliation** (section 5): failures should be 0. Every source entity is `ABSENT` → `CREATE` the first time. Orphans (`UNASSIGNED` and so on) are entities already in the service without an IDM counterpart: review them with the service owner, never delete them by hand.
4. **Turn implicit sync on**, then enable the schedules.

## 4. Reconciliation schedules (WS9.02)

`samples/schedule-recon-graphentity-account.json` and `…-user.json` reconcile nightly:
- accounts at 01:15 and users at 02:15, server time, resources first;
- with `concurrentExecution: false`.

Load them as `config/schedule/recon-graphentity-account` and `…-user`. The integration suite checks that IDM accepts them.

- **Duration.** Measured at 50,000 users and 50,000 accounts (WS8.11), on a 4-CPU, 16 GiB test host with an in-memory stand-in for the service and `taskThreads: 4`:

  | Reconciliation | Time | Per entity |
  |---|---|---|
  | Users, first load (each building its relationships) | 2 min 49 s | 3.4 ms |
  | Accounts, first load | 1 min 35 s | 1.9 ms |
  | Users, steady state (one PUT per user, below) | 2 min 43 s | 3.3 ms |

  - **The real service adds its own latency.** The stand-in answers in well under a millisecond. Against the real service, add roughly *entities × one write's round trip ÷ `taskThreads`*. For example, at 50 ms per write, 50,000 users at 4 threads adds about 10 minutes. Measure it with the load test against the service's test tenant (`docs/load-testing.md`), and in the dry run (section 10). Size the schedules from that.
  - **Threads.** The generated mappings use `taskThreads: 4` (IDM's default is 10). That setting was shown to converge under concurrent implicit sync, at 50,000 users with 500 changes during the run (WS8.10).
  - **Memory.** IDM stayed at about 3 GiB throughout. Give IDM a heap of at least 4 GiB at this size (`-Xmx4g`).
  - Keep the two schedules from overlapping.
- **Every reconciliation restates every linked entity (WS4.08).** Search results carry no relationships, so IDM sees `entityRelationships` as changed and writes each entity again, one PUT each. That's correct (the write changes nothing), costs one write per entity, and repairs drift as a side effect.
- **IDM refuses to reconcile from an empty source** (*"Cannot reconcile from an empty data source, unless allowEmptySourceSet is true"*). Leave that safeguard on: an empty source reconciliation against a full target is how mass deletions happen.

## 5. Monitoring (WS9.03)

`ops/graphentity_health.py` summarises the signals below. It needs Python 3 and nothing else, and reads `IDM_URL`, `IDM_USER` and `IDM_PASSWORD` from the environment:

```bash
IDM_USER=… IDM_PASSWORD=… ops/graphentity_health.py --hours 24 --log <idm>/logs/openidm0.log.0
```

It exits 0 when healthy, 1 when something needs attention, and 2 when it cannot check.

| Signal | Where | Normal | Act when |
|---|---|---|---|
| Connector Test | `POST system/graphentity?_action=test` | `ok: true` | Fails: see section 8 |
| Reconciliation result | `GET recon`, `audit/recon` | `SUCCESS`, 0 failures | Any failure, or a state other than `SUCCESS` |
| Orphans reported | Reconciliation `situationSummary`: `UNASSIGNED`, `UNQUALIFIED`, `TARGET_IGNORED` | Stable, and agreed with the service owner | A rise: something else is writing to the service (A1), or correlation broke |
| Sync failures | `audit/sync` with `status eq "FAILURE"` | 0 | Any: the message names the entity and the HTTP status |
| Dropped relationships | IDM log: `WARNING: dropped edge <src> -<type>-> <tgt>` | Rare after onboarding | Persistent for the same pair: the other entity is missing from the service (section 6.1) |
| Failed verification reads | IDM log: `verification GET failed for <id>` | Present until the service fixes C1 | Not an error today (P11) |
| Service errors | IDM log and audit: `HTTP <status> during <OPERATION>` | Rare 5xx | Bursts of 5xx or 429: check the service. 401/403: the key (section 7) |

## 6. Reading the logs and the service's behaviour

The connector logs IDs, relationship shapes, statuses and redacted response bodies at WARN and above. It never logs attribute values or the API key (§13; checked by the integration suite).

### 6.1 `dropped edge Sanne -Owns-> Current-Account-1-Sanne`
After a create, the connector reads the entity back. A relationship it sent is missing, usually because the other entity doesn't exist in the service yet; the service accepts the write and leaves that relationship out.
- **It heals itself:** the other entity's create carries the same relationship (Appendix A).
- **It persists only if the other entity never reaches the service.** Look for that entity's sync failure.
- **While C1 stands**, the read-back often fails instead, logging `verification GET failed for <id>` (section 5).

### 6.2 Doubled writes when a relationship changes
Both ends of a relationship send it (P17). So a change in IDM can lead to two writes:
- one for the entity that changed;
- one later for the other end, at its next implicit sync or reconciliation.

That's expected. IDM does not re-sync the other end immediately (H15); the first write already updates the service for both entities.

### 6.3 Intermediate states that correct themselves
Every write restates IDM's state in full, so a stale write can briefly undo a newer one (design §10). For example, two near-simultaneous writes to the two ends of one relationship. Once activity stops, the last writes from both ends agree, and the next reconciliation restates everything. Investigate only what persists after a quiet period, or after a reconciliation.

### 6.4 An entity's ID changes
- **Fixed ID.** The remote ID is the type's `idProperty` (for users, `userName`) and must not change (A3). The connector refuses an update whose ID differs, with *"__NAME__ … does not match the entity id"*.
- **With the guard (section 2.3, step 5):**
  1. A change in IDM deletes the entity under its old ID in the service, and IDM's link to it.
  2. Implicit sync then creates the entity under its new ID, with its relationships.
- **If the service is down,** the rename fails in IDM and can be retried; IDM and the service never disagree about the ID.
- **Without the guard,** every rename becomes a sync failure.

### 6.5 Messages by HTTP status

| In the log or audit | Means | Do |
|---|---|---|
| `HTTP 400 during CREATE/UPDATE: …` | The service rejected the document; its message follows, redacted | Fix the data or the configuration it names |
| `HTTP 401/403 during …` | Wrong, missing or revoked API key | Section 7 |
| `HTTP 404 during UPDATE` | The entity is not in the service; IDM's next reconciliation finds it `MISSING` and re-creates it | Nothing, unless it repeats |
| `HTTP 429 during … (Retry-After: n s)` | The service is rate-limiting | Lower `taskThreads`, or move the schedules apart |
| `HTTP 5xx during …`, *"The service could not be reached"* | Service or network failure; IDM retries on the next event or reconciliation | Check the service |
| `The service has no manifests` / `Archetype 'X' … is not defined` (Test, WARN) | Configured archetypes are not in the service's manifests (P22) | Agree the archetype names with the service owner |

## 7. The API key

- **Storage.** It lives only in the provisioner's `password`, which IDM encrypts at rest. Anyone who can read IDM's configuration through the admin API can use the connector. Restrict IDM admin access accordingly (`docs/security-review.md`).
- **One key for every consumer.** The service uses one key for all its consumers, so rotation is a coordinated change with the service team:
  1. Agree the time.
  2. Change the key in the service.
  3. Set the new key in the provisioner's `password` straight away, with an IDM admin `PUT` or the admin UI.
  4. **Check that it took effect:** the Test operation (`POST system/graphentity?_action=test`) returns `ok: true`. If it still fails with 401, IDM may have discarded the save because another provisioner change was still being applied (H21): wait for the connector's `is activated` log line and save again.
- **No restart needed.** IDM re-initialises the connector when its configuration changes; the integration suite shows a changed key taking effect without a restart.
- **Writes that fail between steps 2 and 3** fail with 401 and are repaired by the next reconciliation.
- **Leaks.** A leaked key exposes every consumer of the service. Rotate immediately, and tell the service team.

## 8. Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `system/graphentity/<type>` not found; reconciliation fails at once | The provisioner has no `objectTypes` (H10) | Section 2.3, step 3 |
| First `createFullConfig` after a start fails with `LinkageError` or `BUG! exception in phase 'semantic analysis'` | Two first initialisations at once (H11) | Use the order in section 2.3, step 3. Once the connector has initialised, retrying works |
| `buildArchetypes needs the source object` / a transform receives no source | The mapping lacks `"source": ""` on the library transforms (H12) | Regenerate or edit the mapping; the lint catches it |
| A managed delete leaves the entity in the service | `SOURCE_MISSING` is not `DELETE` in the mapping (H13) | The generated policies have `SOURCE_MISSING: DELETE`; check older mappings |
| A configuration change seems ignored | IDM applies configuration asynchronously (H14) | Wait a few seconds; for mappings, check `GET sync/mappings` |
| A provisioner save was accepted (200), but `GET` shows the old configuration and the connector behaves as before | IDM discarded it: it arrived while IDM was still applying an earlier save (H21) | Wait for `component graphentity is activated` in the log, then save again and check with `GET` and the Test operation |
| A relationship removed in IDM stays in the service | The entity has no relationships left, so the write sends an empty list, which the service ignores today (C17, a service bug) | Known issue, section 9 |
| Updates sent in quick succession are lost | The service can drop a write that follows another closely (C13) | The next write or reconciliation restates it |

## 9. Known issues and watch items

| Item | Effect | Status |
|---|---|---|
| **C1:** the service's GET by ID returns 500 | Create verification degrades to a WARN; reads by ID need `byIdLookup: search` if it worsens | With the service team (`C1-GET-BY-ID-NOTE.md`) |
| **C17:** an empty `relationships` list does not purge | Removing an entity's **last** relationship in IDM does not remove it from the service until fixed. Removing one of several works | Service bug, owner confirmed (WS0.23). **A go-live decision** (`docs/go-live-checklist.md`) |
| **C13:** rapid successive writes can be lost | A quick create-then-update may keep the create's state until the next write | Reconciliation repairs it |
| **A1:** a second writer to the service | Its relationships are removed by this connector | Design review before admitting one |
| **H21:** a provisioner save during another is discarded | Two quick changes (for example a key rotation right after another edit) can leave the first in effect, with no error | IDM behaviour. Make one change at a time and verify it (section 7) |
| **H15:** the other end of a changed relationship is not re-synced at once | Its next write is consistent; nothing to do | By design |
| **No drift detection (P16)** | Out-of-band changes persist until the entity is next written | WS10 |

## 10. Non-production dry run (WS9.06)

*The dry run reads the real service and writes nothing. To measure volume and latency with writes, use the load test against a test tenant: `docs/load-testing.md`.*

The dry run shows what the first real reconciliation will do, without writing anything:
- IDM with production-like data, in a non-production environment;
- the **real** service, a test tenant or a copy agreed with the service owner;
- every situation set to `REPORT`.

1. **Install as in section 2**, but generate the mappings with `--report-only`. Every one of the 13 situations is then `REPORT` and implicit sync is off, so reconciliation classifies without writing. The lint accepts these mappings.
2. **Reconcile each mapping**, resources first. Note the duration, so you can size the schedules.
3. **Record the situation counts per type:**
   - `ABSENT`: would be created;
   - `CONFIRMED`/`FOUND`: would be updated;
   - `UNASSIGNED`/`UNQUALIFIED`/`TARGET_IGNORED`: orphans, which stay reported;
   - `AMBIGUOUS`: correlation found more than one candidate, so it must be fixed before go-live;
   - any failures.
4. **Review the counts with operations and the service owner.** Compare them with the service's own entity counts per type and archetype. Unexpected orphans mean correlation or scoping (the scope archetype, P19) needs attention.
5. **Health report.** Run `ops/graphentity_health.py` and keep its output with the counts as the dry-run record for the go-live decision.
6. **Then the real load.** Replace the mappings with the normal generated ones and follow section 3.

## 11. Upgrading IDM

- **Version check.** A new IDM version can bring a new Groovy, Rhino or Scripted REST connector.
  - Set `idmGroovyVersion` in `gradle.properties` to the new IDM's Groovy, and run `./gradlew :connector:compileWithIdmGroovy`. It compiles the library the way IDM will (`script_vs_jar.md`).
  - Run `./gradlew :integration:test -Pidm -PidmImage=<new image>` before upgrading production.
- **After the upgrade:** check section 2.3, step 4, then run a reconciliation and review it as in section 5.
