# Entitlements ICF Connector — Ping IDM → Entitlements Accelerator Service

Groovy Scripted REST (ICF) connector that synchronises IDM managed objects, one way, to the Entitlements Accelerator Service, plus the mapping-side payload library and the discovery tool that generates its configuration.

- **Design:** [`idm-entitlements-accelerator-connector-design.md`](idm-entitlements-accelerator-connector-design.md)
- **Delivery plan and test strategy:** [`docs/delivery-plan.md`](docs/delivery-plan.md)
- **Service contract (WS0 questions, answers, the real API's shape and how to run the probe):** [`docs/service-contract.md`](docs/service-contract.md)
- **Design decisions for the real API (P17–P22):** [`docs/design-decisions-2026-11-21.md`](docs/design-decisions-2026-11-21.md)

## State of this repository

The build is test-first. The complete unit test suite is in place and the production code is thin stubs that throw `UnsupportedOperationException("TODO WSn: …")`. Implementation proceeds workstream by workstream (see the plan, §4) by turning specs green. WS1–WS6 are done: the connector (configuration and schema, model and payload, transport and write operations, search), the mapping-side payload library and the discovery tool. WS8 runs them inside a real IDM 7.5 container, and every suite passes. WS7 (packaging) is done except the ForgeRock-flavour CI job, which needs repository secrets; WS9's runbook, monitoring, schedules and ID-change guard are done, and its security review, dry run and sign-off need people and the real service. Drift detection (WS10) comes last.

| Area | Code | Tests | Run |
|---|---|---|---|
| Connector library + 7 ICF scripts | `connector/src/main/{groovy,scripts}` | `connector/src/test/groovy` — 31 Spock specs, 696 executions, all passing | `./gradlew :connector:test` |
| Discovery / generation tool | `discovery/src/main/groovy` | `discovery/src/test/groovy` — 7 specs, 77 executions, all passing | `./gradlew :discovery:test` |
| Mapping payload library (ES5, runs in IDM's Rhino) | `mapping-lib/src/lib/entitlementsAcceleratorPayload.js` | `mapping-lib/test` — 116 tests, all passing; also run under Rhino by `EntitlementsAcceleratorPayloadRhinoSpec` (see `mapping-lib/README.md`) | `cd mapping-lib && npm test` |
| Integration in IDM 7.5 (WS8) | `integration/src/test/groovy` | 4 specs, 18 tests, plus an opt-in scale spec; needs Docker | `./gradlew :integration:test -Pidm` |
| Contract probe (WS0) | `contract-probe/src/main/groovy` | `contract-probe/src/test/groovy` — 5 specs, 126 tests, all passing | `./gradlew :contract-probe:test` |
| Configuration samples | `config/` | — | — |

## Prerequisites

- **Java:** the build is pinned to JDK 21.
  - **Compiling and testing:** every module compiles and tests on a JDK 21 toolchain (`build.gradle`).
  - **Gradle itself** runs on JDK 21 whatever `JAVA_HOME` says (`gradle/gradle-daemon-jvm.properties`).
  - **`compileWithIdmGroovy`** also needs a JDK 17, because IDM 7.5's Groovy 3.0.9 can't run on 21.
  - **A missing JDK** (21 or 17) is downloaded by Gradle through the Foojay resolver (`settings.gradle`), where the network allows it. Otherwise, install them.
  - **What it produces:** class files stay at Java 17, so the library loads in IDM 7.5's JDK 17, and the tools run on Java 17 or later.
- Node 20 or later for the mapping library tests (no npm dependencies)
- Internet access to Maven Central. Access to `maven.forgerock.org` is only needed for `-PicfFlavour=forgerock`.

## Running the tests

```bash
./gradlew test                      # both Gradle subprojects, ConnId ICF flavour (default)
./gradlew test -PicfFlavour=forgerock
cd mapping-lib && npm test
```

## Probing the real service (WS0)

```bash
./gradlew :contract-probe:run --args="--base-url https://accelerator.test.example --api-key KEY"                 # read-only
./gradlew :contract-probe:run --args="--base-url https://accelerator.test.example --api-key KEY --allow-writes"  # test instance only
```

The probe sends the service's own shapes (EntityDTO writes, `POST /admin/entities/search` with a JSON body) and reads back by search where GET by ID fails. The write run is the one that confirms the base-type numbering (P18) before anything else creates entities; it stamps two Resource archetypes on a probe entity (from `--archetypes A,B`, the manifests, or its own). Read-backs wait up to `--settle-seconds` (default 10) for each write to show, because the service applies writes with a short delay. Add `--remove-leftovers` to delete entities earlier probe runs left behind. The key is sent raw in the `X-API-KEY` header. `--api-key` keeps it in your shell history and shows it in the process list. To avoid that, leave the option out and set `ACCELERATOR_PROBE_API_KEY` in the same terminal instead.

To run without Gradle at run time, build the launcher once with `./gradlew :contract-probe:installDist`, then run `contract-probe/build/install/contract-probe/bin/contract-probe --base-url URL --api-key KEY`.

The report lands in `build/contract-probe/<run>/report.md`. See `docs/service-contract.md` §1.

### Operating it

- **Release artefact:** `./gradlew :connector:distZip` builds `connector/build/distributions/entitlementsaccelerator-connector-<version>.zip`, laid out like an IDM installation.
- **Runbook:** `docs/runbook.md` covers install, onboarding, schedules, monitoring, the logs, key rotation, troubleshooting and the dry run.
- **Health:** `ops/entitlementsaccelerator_health.py` summarises the monitoring signals.
- **Before go-live:** `docs/security-review.md` and `docs/go-live-checklist.md`.
- **Local stack:** IDM 7.5 plus a service stand-in, `dev/README.md`.

### Integration tests in IDM 7.5

```bash
./gradlew :integration:test -Pidm                    # Docker pulls gcr.io/forgerock-io/idm:7.5.0 on first use
./gradlew :integration:test -Pidm -PidmScale=2000     # adds the volume and concurrency spec; timings in integration/build/scale-report.txt
```

The suite starts IDM 7.5 in Docker on the host network (port 8080), deploys the connector as the runbook does, and drives it through IDM's REST API against an in-memory stand-in for the service. It deploys the library as Groovy sources by default; `-PidmLibrary=jar` deploys the jar instead. Deploying by hand, mind what IDM showed (`docs/service-contract.md` §4.1, `script_vs_jar.md`): `./gradlew :connector:connectorScripts` builds what goes into `<idm>/connectors/entitlementsaccelerator` (scripts and the library sources; `-PlibraryAs=jar` for the jar); the provisioner's object types come from `POST system?_action=createFullConfig`, called *before* the provisioner is saved; and the two library transforms need `"source": ""`.

### Load test, end to end

```bash
./gradlew :loadtest:installDist
LOADTEST_API_KEY=… LOADTEST_IDM_PASSWORD=… loadtest/build/install/entitlementsaccelerator-loadtest/bin/entitlementsaccelerator-loadtest \
    --idm-url http://localhost:8080/openidm --service-url http://localhost:5276 --allow-writes --configure --users 2000
```

It drives a real IDM against the service, a real one or the stand-in: seeding, the first load, steady-state reconciliation, implicit sync at a set rate, and cleanup. It checks the service over HTTP after each phase, and writes `report.md` and `report.json`. It refuses to run without `--allow-writes` or against hosts not on its allow-list, and only ever touches its own `lt-<run>-` entities. See `docs/load-testing.md`, including how to point it at a docker-compose stack.

### Discovery tool

Generates `objectTypes`, the `customConfiguration` value and a mapping skeleton per type from managed.json (design §12):

```bash
./gradlew :discovery:run --args="--managed /path/to/managed.json --types user,account --answers answers.json --current config/provisioner.openicf-entitlementsaccelerator.json --out build/discovery"
# or read managed.json from IDM, read-only, with an IDM account of its own:
IDM_DISCOVERY_PASSWORD=… ./gradlew :discovery:run --args="--idm-url https://idm.example.com --idm-user discovery-reader --types user --answers answers.json --out build/discovery"
# check mappings that target the connector (also a CI step):
./gradlew :discovery:lintMappings
```

`answers.json` holds the operator's answers: `entityKinds` (required per type), and optionally `idProperties`, `archetypes`, `relationshipOverrides` (for pairs the §7 convention cannot resolve) and `connectorName`. The tool prints warnings, errors and the diff against `--current`, and writes nothing if there are errors.

Reports: `connector/build/reports/tests/test/index.html`, `discovery/build/reports/tests/test/index.html`.

## Layout

```
connector/
  src/main/groovy/idm/entitlementsaccelerator/
    config/      objectTypes model + parser, ConnectorConfig            (design §6)
    convention/  RelationshipConvention                                  (§7)
    model/       Edge, EntityDocument                                    (§2)
    codec/       EdgeCodec (entityRelationships encoding)                (§8.3)
    payload/     EntityPayloadBuilder, EntityDecoder, EdgeVerifier       (§8, §9.1, §9.4)
    http/        EntityApi, RestClientEntityApi, HttpErrorMapper         (§2, §13)
    schema/      SchemaFactory                                           (§5.2, §9.5)
    search/      SearchPlanner, TypeScoper, PageCursor                   (§9.4)
    ops/         one class per ICF operation + ScriptSupport             (§4, §9)
  src/main/scripts/   CustomizerScript, TestScript, SchemaScript, SearchScript,
                      CreateScript, UpdateScript, DeleteScript           (§4)
  src/test/groovy/…/support/  Fixtures, CapturingLog, ScriptHarness
  src/testFixtures/groovy/…/support/  FakeAcceleratorService (in-memory §2 semantics), FakeAcceleratorHttpServer
discovery/        ManagedSchemaReader, ObjectTypesGenerator, MappingSkeletonGenerator, DiffSummary,
                  DiscoveryTool (CLI), IdmManagedSource, MappingLint     (§12)
fixtures/         convention-cases.json, shared by the connector and mapping-lib tests (§7)
dev/              docker-compose IDM 7.5 for development, load-config.sh (WS7.06)
ops/              entitlementsaccelerator_health.py: the monitoring summary (WS9.03)
script_vs_jar.md  deploying the library as Groovy scripts (the default) or as a jar
integration/      WS8: IdmStack (IDM 7.5 in Docker + the fake service), IDM specs, fixture managed.json
contract-probe/   WS0 probe: ContractProbe, ProbeReport, Scrubber, ProbeMain
mapping-lib/      entitlementsAcceleratorPayload.js + Node tests                     (§8.2)
config/           provisioner.openicf-entitlementsaccelerator.json, sync-mapping-user.sample.json
docs/             delivery-plan.md
```

## Packaging and deploying to IDM

```bash
./gradlew :connector:distZip :discovery:distZip
# connector/build/distributions/entitlementsaccelerator-connector-<version>.zip   for the IDM servers
# discovery/build/distributions/entitlementsaccelerator-discovery-<version>.zip   for an operator's workstation
```

**`docs/deployment-guide.md`** is the installation guide. It goes from an IDM that has never seen the connector to a configured one, then covers upgrades, rollback and removal. Its worked example is `dev/deploy-walkthrough.sh`, which deploys the two zips into a brand-new IDM 7.5. `docs/runbook.md` covers operation from there.
