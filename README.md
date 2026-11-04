# Entitlements ICF Connector — Ping IDM → Graph Entity Service

Groovy Scripted REST (ICF) connector that synchronises IDM managed objects, one way, to a graph entity service, plus the mapping-side payload library and the discovery tool that generates its configuration.

- **Design:** [`idm-graph-entity-connector-design.md`](idm-graph-entity-connector-design.md)
- **Delivery plan and test strategy:** [`docs/delivery-plan.md`](docs/delivery-plan.md)
- **Service contract (WS0 questions, answers and how to run the probe):** [`docs/service-contract.md`](docs/service-contract.md)

## State of this repository

The build is test-first. The complete unit test suite is in place and the production code is thin stubs that throw `UnsupportedOperationException("TODO WSn: …")`. Implementation proceeds workstream by workstream (see the plan, §4) by turning specs green.

| Area | Code | Tests | Run |
|---|---|---|---|
| Connector library + 7 ICF scripts | `connector/src/main/{groovy,scripts}` | `connector/src/test/groovy` — 29 Spock specs, 517 executions | `./gradlew :connector:test` |
| Discovery / generation tool | `discovery/src/main/groovy` | `discovery/src/test/groovy` — 4 specs, 41 executions | `./gradlew :discovery:test` |
| Mapping payload library (ES5, runs in IDM's Rhino) | `mapping-lib/src/lib/graphEntityPayload.js` | `mapping-lib/test` — 67 tests | `cd mapping-lib && npm test` |
| Contract probe (WS0) | `contract-probe/src/main/groovy` | `contract-probe/src/test/groovy` — 4 specs, 47 tests, all passing | `./gradlew :contract-probe:test` |
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
export GRAPH_PROBE_PASSWORD='…'
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --username svc-probe"                 # read-only
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --username svc-probe --allow-writes"  # test instance only
```

The report lands in `build/contract-probe/<run>/report.md`. See `docs/service-contract.md` §1.

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
discovery/        ManagedSchemaReader, ObjectTypesGenerator, MappingSkeletonGenerator, DiffSummary (§12)
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
