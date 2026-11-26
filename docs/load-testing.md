# Load testing: IDM, the connector and the service, end to end

**Audience:** whoever sizes the deployment, and whoever runs the test environment.
**What it answers:**
- how long reconciliations take at your volume, against your service;
- what rate of IDM changes the connector carries;
- whether every change arrives intact;
- what the service does under that load: 429s, 5xx, lost writes;
- how much memory IDM needs.

**The tool:** `entitlementsaccelerator-loadtest` (`loadtest/`).

## 1. What it does

It drives IDM through IDM's REST API, the way IDM's users and operators do. The connector carries every change to the service, and the tool then reads the service directly, over HTTP, to check the result. It runs these phases, each timed and checked:

| Phase | What happens | Passes when |
|---|---|---|
| `preflight` | IDM is ready; the service answers a search with the key | Both answer |
| `configure` (with `--configure`) | Loads the load-test model into IDM: managed objects, connector configuration (`createFullConfig`, then a save that is confirmed to have landed, H21) and the two mappings | Each step is confirmed |
| `model` | The connector's Test passes; the two mappings are loaded; `managed/account` has `accountId` and `ownedBy` | All three hold |
| `seed` | N users and N accounts, each account owned by its user, created in IDM with implicit sync off | Every create succeeds |
| `initial` | Reconciles accounts, then users (the first load) | Both reconciliations succeed with no failures. Every user and account is in the service, users with the right attributes. Each sampled user's relationship to its account reads back |
| `steady` | Reconciles users again | No failures. Every linked user is restated, one PUT each (WS4.08) |
| `sync` | Implicit sync on; M users changed at a target rate | Every change shows in the service within `--settle-seconds`, and no unchanged user differs |
| `cleanup` | The run's accounts and users deleted in IDM, which deletes them in the service | Nothing of the run remains in the service. Anything left is deleted directly, and the phase fails |

**Afterwards:**
- the mappings' implicit-sync setting is put back as it was, whatever happened;
- the tool writes `report.md` and `report.json` (§5), and exits 0 if every phase passed, 1 if not, 2 for a usage error.

**Speed:** about 2 minutes for 2,000 users against a local IDM and the in-memory stand-in (§6).

## 2. Safety

Every run writes to IDM and, through the connector, to the service. The tool's guards:
- **`--allow-writes` is required.** Without it, nothing runs.
- **An allow-list of hosts.** Every host it addresses, *and the address IDM is told to use for the service*, must be localhost or on `--allowed-hosts` / `LOADTEST_ALLOWED_HOSTS`. Add only test hosts.
- **Secrets come from the environment.** The service's key is `LOADTEST_API_KEY`, and IDM's admin password is `LOADTEST_IDM_PASSWORD`. `--api-key` and `--idm-password` are refused, so the secrets stay out of shell history and the process list. Neither secret appears in the reports; as a last guard, both are stripped from them.
- **Only its own data.** Every entity it creates has an ID starting `lt-<run-id>-`. Cleanup deletes only those, and only after the IDM objects of the same names are gone.
- **`--configure` replaces IDM's `config/managed`, the connector configuration and the two mappings.** It is for a disposable environment, such as a compose stack. Without `--configure`, the tool changes only the two mappings' implicit-sync setting, and restores it at the end.

What the guards cannot know:
- **Never point it at production,** IDM or service.
- **The service has one API key for every consumer** (security review R1). A load test on a shared test tenant writes as every consumer would, so agree it with the service team, and preferably use a tenant of your own.

## 3. Running it

Build it, or take `entitlementsaccelerator-loadtest-<version>.zip` from CI's `release-zips`:

```bash
./gradlew :loadtest:installDist         # loadtest/build/install/entitlementsaccelerator-loadtest/bin/entitlementsaccelerator-loadtest
# or: ./gradlew :loadtest:distZip       # loadtest/build/distributions/entitlementsaccelerator-loadtest-<version>.zip
```

It needs Java 17 or later on the machine that runs it. It doesn't need to run on the IDM server.

### 3.1 Against a docker-compose stack with IDM and the service

This is the usual case: IDM and the service as containers. The tool runs on the host, so it reaches both through their published ports, while IDM reaches the service by its name on the compose network. Those are two different addresses, and the tool takes both:

```bash
export LOADTEST_API_KEY=…          # the service's key, as configured in the service container
export LOADTEST_IDM_PASSWORD=…     # IDM's admin password (the image's default is openidm-admin)

entitlementsaccelerator-loadtest \
  --idm-url http://localhost:8080/openidm \
  --service-url http://localhost:<service's published port> \
  --idm-service-url http://<service's compose name>:<its port inside the network> \
  --allowed-hosts <service's compose name> \
  --idm-container <IDM's container name> \
  --configure --allow-writes \
  --users 10000 --changes 1000 --rate 50
```

- **The connector's files must be in the IDM container** before `--configure`: `connectors/entitlementsaccelerator/` and `script/lib/entitlementsAcceleratorPayload.js`, mounted or copied from the release zip, owned by IDM's user (deployment guide §4). If they aren't there, `configure` fails, saying that `createFullConfig` returned no object types.
- **`--idm-container`** lets the tool read IDM's log file through `docker exec`. It then confirms provisioner saves from IDM's activation lines (H21), and counts what the connector logged during the run: 429s, 5xx, dropped relationships, warnings. Without it, saves are confirmed by checking the stored configuration and running the Test operation, and the report has no log section.
- **Run once with `--configure`,** then without it for repeat runs; the configuration stays. Give each run a new `--run-id`, or let it default to the time.
- **TLS:** if the service uses https with a private CA, IDM must trust it (deployment guide §5.1), and so must the JVM running the tool (`-Djavax.net.ssl.trustStore=…` in `JAVA_OPTS`).

### 3.2 Against an existing test IDM

Leave out `--configure`. The IDM must already have:
- the connector, with a passing Test;
- the two mappings, by default `managedUser_systemEntitlementsacceleratorUser` and `managedAccount_systemEntitlementsacceleratorAccount` (rename them with `--user-mapping` and `--account-mapping`);
- `managed/account` with `accountId` and `ownedBy`, as in the deployment's model;
- the relationship type, if the service calls it something other than `Owns`: `--relationship-type`.

Add the IDM's host and the service's host to `--allowed-hosts`.

### 3.3 Options

`entitlementsaccelerator-loadtest --help` lists them all. The ones that shape the load:

| Option | Default | Effect |
|---|---|---|
| `--users N` | 1,000 | Users, each with one account: 2N entities and N relationships |
| `--changes M` | 200 | Users changed through implicit sync |
| `--rate R` | 20 | Changes per second to aim for. The report gives the rate achieved. If it falls short, IDM or the service is the limit |
| `--threads T` | 8 | Client threads for seeding, changes and cleanup. IDM's own reconciliation threads are the mapping's `taskThreads` (4, generated) |
| `--sample K` | 100 | Users whose relationships are read back one by one (below) |
| `--settle-seconds S` | 180 | How long the service may take to show a change before a check fails |
| `--phases` | all | For example `seed,initial` to measure only the first load, then `--keep` to leave the data for a later run with the same `--run-id` and `--phases steady,sync,cleanup` |
| `--keep` | off | Skip cleanup |

## 4. How it checks the service

The tool reads the service the same way the connector does (`docs/service-contract.md`):
- **Finding the run's entities:** a search with a `STARTSWITH` filter on `ID`, paged 1,000 at a time.
  - The real service's support for `STARTSWITH` on `ID` is **not proven**; `EQUALS` on `ID` is.
  - If the service refuses `STARTSWITH` with 400, the tool pages through every entity and filters by prefix itself. The report says it did. That costs more reads on a large tenant.
- **Attributes** come from search results, so every user is checked.
- **Relationships** are only in the read model, from GET by ID, which the real service answers unreliably (C1).
  - So relationships are checked on a sample (`--sample`). A user whose GET fails is counted as *unreadable*, not as missing.
  - Only a readable user without its relationship fails the phase.
- **The canary.** When implicit sync is turned on, IDM reports the change before it acts on it. So the tool changes a canary user of its own until the change reaches the service, and only then starts the timed changes.
- **429 from the service** on the tool's own searches is waited out, following `Retry-After`, and counted. The checks don't fail because the service was busy, but the report shows it was.

## 5. Reading the report

`report.md` (for people) and `report.json` (for machines) in `--out`, by default `build/loadtest/<run-id>/`:
- **Phases:** result, time, and the figures that matter:
  - reconciliation time and milliseconds per entity, with the situations IDM saw;
  - creates per second;
  - the change rate aimed for and achieved, and the time from the last change until the service showed all of them;
  - IDM's heap before and after each phase, from IDM's `health/memory`.
- **Response times** of IDM's REST calls: count, errors by HTTP status, mean, p50, p95, p99 and maximum.
  - With implicit sync on, a change or a delete returns only after the connector has written to the service. So these times include the service's latency, and they are the clearest measure of it.
- **The service as the tool saw it:** HTTP statuses of its own reads.
- **IDM's log during the run** (with `--idm-container`): what the connector logged, by kind. Count only, never the lines.
  - `HTTP 429` and `HTTP 5xx` are the service refusing or failing the connector's writes.
  - `dropped edge` is expected once per relationship during the first load (§6).

**Sizing from it:**
- **Reconciliation windows:** time per entity × entities, for each type, with the two schedules not overlapping (runbook §4).
- **IDM's heap:** the highest figure in the report, plus headroom.
- **The sustainable change rate:** the highest `--rate` whose achieved rate keeps up, with no 429s or 5xx in the log.

## 6. Results so far

Against the in-memory stand-in, with IDM 7.5 in Docker on a 4-CPU, 16 GiB host. These measure IDM and the connector. Against a real service, add its latency.

| Run | Users | Initial reconciliation, ms per entity | Steady state, ms per user | Changes per second, target → achieved | Result |
|---|---|---|---|---|---|
| `e2e3` (`--configure` on a brand-new IDM) | 500 | 45 | 68 | 20 → 14.7 | PASS |
| `e2e4` (the same IDM, no `--configure`) | 2,000 | accounts 10.4, users 12.2 | 12.7 | 40 → 39.9 | PASS |
| `LoadTestInIdmSpec` (CI) | 200 | accounts 11.9, users 13.2 | 10.1 | 25 → 24.9 | PASS |

- **The `e2e3` figures are the stand-in's, not IDM's.** That run used `runFakeService` before it turned off the stand-in's Nagle delay (H20), which added about 40 ms to every exchange. `runFakeService` now sets `sun.net.httpserver.nodelay`.
- **Every run logged one `dropped edge` per relationship during the account reconciliation.** An account written before its owner finds the owner absent, so its own write drops the relationship, and the user's write heals it moments later. Users first does the same from the other side. Either order gives one warning per relationship on the first load (runbook §3).
- **To do in your environment:** a run against the real service's test tenant, at production size. This is what gives the reconciliation windows their real latency (go-live checklist, item 3).

## 7. In CI

`integration/…/LoadTestInIdmSpec` runs the tool at 200 users against the suite's IDM and the stand-in, reached over HTTP as if it were remote. It runs in the nightly `integration` workflow, in both library modes, so a load run elsewhere fails only for reasons of that environment. The tool's own logic is covered by `loadtest/src/test`: options and guards, timing statistics, the service client and its fallbacks, and the report's scrubbing.

## 8. Limits

- **It loads one model:** users each owning one account. It doesn't exercise other relationship shapes, archetype scoping beyond the configured archetypes, or ID changes.
- **Its own reads add load** to the service: one paged search per check, and `--sample` GETs. On a busy tenant, keep checks infrequent by raising `--settle-seconds`, rather than polling hard.
- **The connector's own HTTP calls aren't timed directly.** IDM's response times with implicit sync on, and the reconciliation times, include them.
- **https to the service through a proxy** is untested here (deployment guide §5.2).
