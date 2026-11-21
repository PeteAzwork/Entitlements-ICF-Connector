# Entitlements ICF Connector — Ping IDM → Graph Entity Service

Groovy Scripted REST (ICF) connector that synchronises IDM managed objects, one way, to a graph entity service, plus the mapping-side payload library and the discovery tool that generates its configuration.

- **Design:** [`idm-graph-entity-connector-design.md`](idm-graph-entity-connector-design.md)
- **Delivery plan and test strategy:** [`docs/delivery-plan.md`](docs/delivery-plan.md)
- **Service contract (WS0 questions, answers, the real API's shape and how to run the probe):** [`docs/service-contract.md`](docs/service-contract.md)
- **Design decisions for the real API (P17–P22):** [`docs/design-decisions-2026-11-21.md`](docs/design-decisions-2026-11-21.md)

## State of this repository

The build is test-first. The complete unit test suite is in place and the production code is thin stubs that throw `UnsupportedOperationException("TODO WSn: …")`. Implementation proceeds workstream by workstream (see the plan, §4) by turning specs green. WS1–WS6 are done: the connector (configuration and schema, model and payload, transport and write operations, search), the mapping-side payload library and the discovery tool. Every unit suite passes; no stub remains. What is left is packaging, integration in a real IDM and rollout (WS7–WS9), then drift detection (WS10).

| Area | Code | Tests | Run |
|---|---|---|---|
| Connector library + 7 ICF scripts | `connector/src/main/{groovy,scripts}` | `connector/src/test/groovy` — 31 Spock specs, 696 executions, all passing | `./gradlew :connector:test` |
| Discovery / generation tool | `discovery/src/main/groovy` | `discovery/src/test/groovy` — 7 specs, 71 executions, all passing | `./gradlew :discovery:test` |
| Mapping payload library (ES5, runs in IDM's Rhino) | `mapping-lib/src/lib/graphEntityPayload.js` | `mapping-lib/test` — 109 tests, all passing; also run under Rhino by `GraphEntityPayloadRhinoSpec` (see `mapping-lib/README.md`) | `cd mapping-lib && npm test` |
| Contract probe (WS0) | `contract-probe/src/main/groovy` | `contract-probe/src/test/groovy` — 5 specs, 126 tests, all passing | `./gradlew :contract-probe:test` |
| Configuration samples | `config/` | — | — |

## Prerequisites

- JDK 17 or later (verified on 21)
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
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --api-key KEY"                 # read-only
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --api-key KEY --allow-writes"  # test instance only
```

The probe sends the service's own shapes (EntityDTO writes, `POST /admin/entities/search` with a JSON body) and reads back by search where GET by ID fails. The write run is the one that confirms the base-type numbering (P18) before anything else creates entities; it stamps two Resource archetypes on a probe entity (from `--archetypes A,B`, the manifests, or its own). Read-backs wait up to `--settle-seconds` (default 10) for each write to show, because the service applies writes with a short delay. Add `--remove-leftovers` to delete entities earlier probe runs left behind. The key is sent raw in the `X-API-KEY` header. `--api-key` keeps it in your shell history and shows it in the process list. To avoid that, leave the option out and set `GRAPH_PROBE_API_KEY` in the same terminal instead.

To run without Gradle at run time, build the launcher once with `./gradlew :contract-probe:installDist`, then run `contract-probe/build/install/contract-probe/bin/contract-probe --base-url URL --api-key KEY`.

The report lands in `build/contract-probe/<run>/report.md`. See `docs/service-contract.md` §1.

### Discovery tool

Generates `objectTypes`, the `customConfiguration` value and a mapping skeleton per type from managed.json (design §12):

```bash
./gradlew :discovery:run --args="--managed /path/to/managed.json --types user,account --answers answers.json --current config/provisioner.openicf-graphentity.json --out build/discovery"
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
  src/main/groovy/au/com/azwork/idm/graphentity/
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
  src/testFixtures/groovy/…/support/  FakeGraphService (in-memory §2 semantics), FakeGraphHttpServer
discovery/        ManagedSchemaReader, ObjectTypesGenerator, MappingSkeletonGenerator, DiffSummary,
                  DiscoveryTool (CLI), IdmManagedSource, MappingLint     (§12)
fixtures/         convention-cases.json, shared by the connector and mapping-lib tests (§7)
contract-probe/   WS0 probe: ContractProbe, ProbeReport, Scrubber, ProbeMain
mapping-lib/      graphEntityPayload.js + Node tests                     (§8.2)
config/           provisioner.openicf-graphentity.json, sync-mapping-user.sample.json
docs/             delivery-plan.md
```

## Packaging for IDM

```bash
./gradlew :connector:connectorScripts
# -> connector/build/connector-scripts/{scripts,lib,provisioner.openicf-graphentity.json}
```

Copy `scripts/` and `lib/` under `&{idm.instance.dir}/connectors/graphentity/` (they are referenced by `scriptRoots` and `classpath` in the provisioner) and `mapping-lib/src/lib/graphEntityPayload.js` to `&{idm.instance.dir}/script/lib/`.
