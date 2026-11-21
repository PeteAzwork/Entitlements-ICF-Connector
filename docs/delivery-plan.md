# Delivery Plan — Ping IDM Scripted REST Connector for the Graph Entity Service

**Source design:** `idm-graph-entity-connector-design.md` (Draft for review, 2026-07-06)
**Plan status:** Proposed, v1.2 — 2026-11-04 (WS0 started: question register and contract probe)
**Target platform:** Self-managed Ping IDM 7.5, Groovy Connector Toolkit 1.5.20.x (Scripted REST)
**Delivery approach:** Test-first. This repository already contains the complete unit test suite and thin stubs; implementation work turns specs green workstream by workstream.

---

## 1. How to read this plan

The design document says *what* the connector is. This plan says *in what order* it gets built, *how each piece is proven*, and *what is still unknown*. It is organised as:

- §2 what is delivered, mapped to the design sections and to code that already exists in this repository;
- §3 decisions this plan makes where the design was silent or underspecified (please review these first — they are the cheapest things to change now);
- §4 the workstreams, each with its acceptance criterion expressed as "these specs are green";
- §5 the schedule and critical path;
- §6 the test strategy, including the full catalogue of tests already written;
- §7 environments, build and CI;
- §8 deployment and rollout to IDM;
- §9 risks, §10 open questions, §11 definition of done.
- §12 the task list for every workstream: the working checklist, with sizes, the specs each task turns green, and known gotchas.

Every test name in the suite cites the design section it proves (for example `§9.1`), so a reviewer can trace design → test → code in either direction.

---

## 2. Scope and deliverables

### 2.1 Deliverables

| # | Deliverable | Design ref | Location in repo | State today |
|---|---|---|---|---|
| D1 | Connector library (Groovy classes the scripts delegate to) | §4–§9, §13 | `connector/src/main/groovy/au/com/azwork/idm/graphentity/` | API fixed, bodies are stubs |
| D2 | Seven ICF operation scripts | §4 | `connector/src/main/scripts/*.groovy` | Written (thin delegation) |
| D3 | Connector unit + script-harness + contract + scenario tests | §6 of this plan | `connector/src/test/groovy/…` | **Complete: 29 specs, 557 test executions** |
| D4 | Mapping-side payload library (ES5 JavaScript for IDM's Rhino) | §8.2 | `mapping-lib/src/lib/graphEntityPayload.js` | API fixed, bodies are stubs |
| D5 | Mapping library tests (Node test runner, Rhino-like isolation) | §8.2 | `mapping-lib/test/` | **Complete: 67 tests** |
| D6 | Discovery and generation tool | §12 | `discovery/src/main/groovy/…` | API fixed, bodies are stubs |
| D7 | Discovery tool tests | §12 | `discovery/src/test/groovy/…` | **Complete: 4 specs, 41 test executions** |
| D8 | Provisioner and mapping configuration samples | §6, §11, §12 | `config/` | Written |
| D9 | Build, CI, packaging task (`connectorScripts`) | — | `build.gradle`, `.github/workflows/ci.yml` | Written |
| D10 | Integration test harness against IDM 7.5 in Docker | this plan §6.4 | `integration/` (to be created) | Not started |
| D11 | Operator runbook (deployment, recon cadence, REPORT→DELETE promotion, log interpretation) | §9–§11, §14 | `docs/runbook.md` (to be created) | Not started |
| D12 | Service-contract confirmation record (answers to design §15 open items) | §15 | `docs/service-contract.md` | Question register written; no answers confirmed yet |
| D13 | Contract probe: answers the observable WS0 questions and records the evidence | §15 | `contract-probe/` | **Built and tested against the in-memory fake (47 tests); not yet run against the real service** |

### 2.2 Out of scope (unchanged from the design)

Inbound provisioning, password synchronisation, LiveSync/SyncScript, remote-originated relationship types, read-merge-write mode (specified in §15 as a future evolution, not built now).

### 2.3 Design → code map

| Design section | Library class(es) | Spec(s) |
|---|---|---|
| §2.1 document shape | `model.EntityDocument` | `EntityDocumentSpec` |
| §2.2 edge rules | `model.Edge` | `EdgeSpec` |
| §3 A3/A4 identity and kind | `config.EntityKind`, `payload.EntityPayloadBuilder` | `EntityKindSpec`, `EntityPayloadBuilderSpec` |
| §5.2/§6 per-type dynamic schema | `config.ObjectTypesParser`, `config.ConnectorConfig`, `schema.SchemaFactory` | `ObjectTypesParserSpec`, `ConnectorConfigSpec`, `SchemaFactorySpec` |
| §7 convention (normative) | `convention.RelationshipConvention` | `RelationshipConventionSpec` (JS: `resolvePair`/`resolveRelationship` tests) |
| §8.2 mapping-side assembly | `graphEntityPayload.js` | `graphEntityPayload.test.js` |
| §8.3 ICF encoding | `codec.EdgeCodec` | `EdgeCodecSpec` |
| §8.4/§9.2 update | `ops.UpdateOperation` | `UpdateOperationSpec` |
| §9.1 create + verification | `ops.CreateOperation`, `payload.EdgeVerifier` | `CreateOperationSpec`, `EdgeVerifierSpec` |
| §9.3 delete | `ops.DeleteOperation` | `DeleteOperationSpec` |
| §9.4 search/get, scoping, paging | `search.SearchPlanner`, `search.TypeScoper`, `search.PageCursor`, `ops.SearchOperation`, `payload.EntityDecoder` | `SearchPlannerSpec`, `TypeScoperSpec`, `PageCursorSpec`, `SearchOperationSpec`, `EntityDecoderSpec` |
| §9.5 test/schema | `ops.TestOperation`, `ops.SchemaOperation` | `TestAndSchemaOperationSpec` |
| §13 exception mapping | `http.HttpErrorMapper` | `HttpErrorMapperSpec` |
| §2/§14 transport and auth | `http.RestClientEntityApi`, `CustomizerScript` | `RestClientEntityApiSpec`, `CustomizerScriptSpec` |
| §4 scripts as the harness runs them | `ops.ScriptSupport`, scripts | `OperationScriptsSpec`, `ScriptSupportSpec` |
| §12 discovery | `discovery.*` | `ManagedSchemaReaderSpec`, `ObjectTypesGeneratorSpec`, `MappingSkeletonGeneratorSpec`, `DiffSummarySpec` |
| Appendix A | all operations against `FakeGraphService` | `WorkedExampleSpec` |

---

## 3. Decisions made in this plan (review these first)

These are places where turning the design into testable code forced a concrete choice. Each is reversible now at low cost; each is pinned by a test so a change is deliberate.

| # | Decision | Why | Where pinned |
|---|---|---|---|
| P1 | **Connector-specific configuration travels in `customConfiguration`** (a ConfigSlurper script surfaced as `configuration.propertyBag`), not as top-level `configurationProperties`. `objectTypes` is a JSON string inside it. | The Scripted REST connector has a fixed configuration class; arbitrary top-level properties are not available to scripts. The design's §6 example is kept as the *logical* model. | `config/provisioner.openicf-graphentity.json`, `ScriptSupportSpec` |
| P2 | **Relationship entries carry `relatedType` and `reverseProperty` hints**, emitted by the discovery tool. | The connector cannot run the §7 consistency validation at load time without knowing which entry is the counterpart of which; `managed.json` is not available to it. Hand-written config may omit them (validation then warns instead of checking). | `ObjectTypesParserSpec`, `RelationshipConventionSpec` "validate" cases, `ObjectTypesGeneratorSpec` |
| P3 | **The "passive form" of the §7 convention is defined as a trailing `By`** (`ownedBy`, `managedBy`). Exactly one passive side is required; otherwise the pair is unresolved and needs an override (the design's `manager`/`reports` case). | The design names the passive form by example only; a deterministic rule is needed for both the generator and the mapping library. | `RelationshipConventionSpec` normalisation/passive tables, JS equivalents |
| P4 | **`UpdateOperation` refuses an update whose attribute set omits `entityRelationships` or `directArchetypes`** (`InvalidAttributeValueException`) instead of sending a PUT that would delete edges. | §8.4/§15 call the partial-update risk out as the way routine updates turn into mass edge deletions and rely on code review. A connector-side guard makes it structurally impossible. An empty attribute (present, no values) is still a valid "no edges". | `UpdateOperationSpec` |
| P5 | **Type scoping on enumeration checks `BaseType` as well as archetypes.** | Archetype ambiguity is an open item (§15); BaseType is a cheap second discriminator that breaks ties between subject and resource types. An `idPattern` regex fallback is reserved but not implemented. | `TypeScoperSpec` |
| P6 | **Paging protocol is assumed to be `offset`/`pageSize` request parameters with `items` + optional `totalCount` or `nextCursor` in the response.** All paging knowledge is confined to `PageCursor`. | §15 says the protocol is unconfirmed; isolating it means the confirmation changes one class and one spec. | `PageCursorSpec`, `FakeGraphService` |
| P7 | **Unsupported ICF filters raise `UnsupportedOperationException`** rather than silently enumerating. | A silent fallback would turn a mistaken `_queryFilter` into a full scan of the remote service. | `SearchPlannerSpec` |
| P8 | **Build compiles against ConnId's `connector-framework` by default** (`icfFlavour=connid`), switchable to ForgeRock's artefact (`icfFlavour=forgerock`). Both expose the same `org.identityconnectors.framework` API the scripts use. | ForgeRock's Maven repository is not reachable from every build environment (it was not from the one this suite was authored in). The release build must use the ForgeRock flavour; CI runs both when it can. | `gradle.properties`, `build.gradle` |
| P9 | **Library classes are deployed as Groovy sources under a `classpath` root** of the provisioner, scripts under `scriptRoots`. | Keeps a single-artefact deployment with no custom bundle (the design's "Scripted REST over custom Java bundle" decision). | `config/provisioner…json`, `connectorScripts` Gradle task |
| P10 | **The mapping library reads `config/managed` (cached) only as a fallback** for relationship properties not listed in `objectTypes`. | §6 says listing every property is *should*, not *must*; the fallback needs the reverse property name, which only `managed.json` has. | `graphEntityPayload.test.js` "falls back to managed.json" cases |
| P11 | **Verification GET failure after a successful POST does not fail the create**; it is logged at WARN. | §9.1 makes verification an audit aid; failing the create would create a retry loop for an entity that already exists (409 on retry). | `CreateOperationSpec` |
| P12 | **Package namespace `au.com.azwork.idm.graphentity`.** | Derived from the organisation domain; rename is a find-and-replace before WS1 starts if a different namespace is preferred. | everywhere |

---

## 4. Workstreams

Each workstream names its acceptance criterion as the specs that must be green. Estimates are indicative engineer-days for one engineer familiar with IDM and Groovy, excluding review. The task-level breakdown of each workstream, with revised estimates, is in §12.

### WS0 — Service contract spike (blocks WS4 and WS8; can run in parallel with WS1–WS3)

**Goal:** close the §15 open items against the real service.
**Tasks:** confirm search parameter name and datetime format; confirm paging protocol (cursor vs offset, response envelope, page-size limit); confirm duplicate `POST` behaviour (409 or otherwise); capture error body shape; confirm whether archetypes discriminate types reliably; capture one real document of each type.
**Outputs:** `docs/service-contract.md`; updated `PageCursor`/`PageCursorSpec` and `FakeGraphService` if the assumption in P6 is wrong; `timestampSearchParam` default; recorded WireMock stubs from real responses (used by WS8).
**Acceptance:** every "assumed" marker in the suite replaced by a confirmed value or an explicit, agreed assumption.
**Estimate:** 2 days, elapsed time depends on service-team availability.

### WS1 — Configuration, convention and schema

**Goal:** the connector can load, validate and expose its schema.
**Implement:** `EntityKind`, `Direction`, `ObjectTypesParser`, `ConnectorConfig`, `ObjectTypeConfig.syncedRelationships`, `RelationshipConvention` (all four functions), `ObjectClassResolver`, `SchemaFactory`, `SchemaOperation`.
**Acceptance (green):** `EntityKindSpec`, `DirectionSpec`, `ObjectTypesParserSpec`, `ConnectorConfigSpec`, `RelationshipConventionSpec`, `ObjectClassResolverSpec`, `SchemaFactorySpec`, schema cases in `TestAndSchemaOperationSpec`.
**Estimate:** 3.5 days (revised in §12).

### WS2 — Model, codec and payload

**Goal:** pure functions from ICF attributes to the wire document and back.
**Implement:** `Edge`, `EntityDocument`, `EdgeCodec`, `EntityPayloadBuilder`, `EntityDecoder`.
**Acceptance:** `EdgeSpec`, `EntityDocumentSpec`, `EdgeCodecSpec`, `EntityPayloadBuilderSpec`, `EntityDecoderSpec`.
**Estimate:** 2 days. Depends on WS1 types only (compiles already).

### WS3 — Transport, error mapping and write operations

**Goal:** create/update/delete/test work end to end through the real HTTP Builder client.
**Implement:** `HttpErrorMapper`, `RestClientEntityApi`, `ScriptSupport`, `EdgeVerifier`, `CreateOperation`, `UpdateOperation`, `DeleteOperation`, `TestOperation`, `CustomizerScript` body.
**Acceptance:** `HttpErrorMapperSpec`, `RestClientEntityApiSpec`, `ScriptSupportSpec`, `EdgeVerifierSpec`, `CreateOperationSpec`, `UpdateOperationSpec`, `DeleteOperationSpec`, `TestAndSchemaOperationSpec`, `CustomizerScriptSpec`, and the create/update/delete/test cases of `OperationScriptsSpec`.
**Estimate:** 5 days (revised in §12). Depends on WS1, WS2.

### WS4 — Search, scoping and paging

**Goal:** get-by-id, timestamp search and scoped enumeration with ICF paging cookies.
**Implement:** `SearchPlanner`, `PageCursor`, `TypeScoper`, `SearchOperation`.
**Acceptance:** `SearchPlannerSpec`, `PageCursorSpec`, `TypeScoperSpec`, `SearchOperationSpec`, search cases of `OperationScriptsSpec`, then `WorkedExampleSpec` (the whole Appendix A scenario) goes green as the WS1–WS4 milestone check.
**Estimate:** 3 days. Depends on WS0 for the final paging protocol (can be built against P6 and adjusted).

### WS5 — Mapping-side payload library

**Goal:** `buildRelationships`/`buildArchetypes` produce the complete edge set from IDM state, resolving remote IDs through the related type's `idProperty`.
**Implement:** all functions in `graphEntityPayload.js`, ES5 only.
**Acceptance:** `npm test` in `mapping-lib/` green (67 tests), plus a manual check in a real IDM that `require('lib/graphEntityPayload')` loads under Rhino (WS8 covers this formally).
**Estimate:** 5 days (revised in §12: drift-check script and Rhino test added). Independent of WS1–WS4 (shares the §7 rules, which the Groovy and JS suites pin identically).

### WS6 — Discovery and generation tool

**Goal:** generate `objectTypes` and mapping skeletons from `managed.json`, with the same validation the connector runs.
**Implement:** `ManagedSchemaReader`, `ObjectTypesGenerator`, `MappingSkeletonGenerator`, `DiffSummary`, `DiscoveryTool.main` (file IO and prompts).
**Acceptance:** all four discovery specs green; `ObjectTypesGeneratorSpec` "parses and validates with the connector's own parser" proves generator and connector agree.
**Estimate:** 6 days (revised in §12: CLI, read-only REST mode and mapping lint added). Depends on WS1 (reuses parser and convention).

### WS7 — Packaging and deployment artefacts

**Goal:** one command produces what is copied into IDM.
**Tasks:** finish `connectorScripts` task (scripts, library sources, `graphEntityPayload.js` to `script/lib/`, provisioner sample); version stamping; `docs/runbook.md` first draft (install steps, provisioner encryption of the password, refreshing the connector after an `objectTypes` change).
**Acceptance:** fresh IDM 7.5 container + artefact → `TestScript` passes via `system/graphentity?_action=test`, `SchemaScript` exposes `user` and `account`.
**Estimate:** 2.5 days (revised in §12). Depends on WS3.

### WS8 — Integration tests against IDM 7.5 (Docker)

**Goal:** prove the scripts run inside the real Scripted REST harness and the mappings drive the connector as designed.
**Tasks:** `integration/` with docker-compose (IDM 7.5 + WireMock seeded from WS0 recordings, or the real service in a test tenant); seed `managed/user` and `managed/account` objects with relationships; run the user and account mappings' recon; assert the remote documents (Appendix A sequence); assert the dropped-edge WARN appears in IDM's log; run a target-phase recon and assert TARGET-only reports; incremental drift check script (§11).
**Acceptance:** a scripted, repeatable run; results archived in CI.
**Estimate:** 6 days (revised in §12). Depends on WS5, WS7, WS0.

### WS9 — Operational readiness and rollout

**Goal:** the connector is safe to run against production data.
**Tasks:** runbook completion (recon cadence per §11, REPORT→DELETE promotion checklist per type, log interpretation incl. the doubled write of §9.2, concurrency notes of §10, A1 erosion watch); recon `taskThreads` and queued-sync recommendations; monitoring queries for `dropped edge` WARN volume; security review of the service account scope and TLS (§14); production onboarding order (resources first, then subjects).
**Acceptance:** runbook reviewed by operations; dry-run recon in a non-production IDM against the real service with `REPORT` everywhere; sign-off.
**Estimate:** 3 days plus review cycles.

---

## 5. Schedule and critical path

Assumes one engineer on the connector and one on the mapping library/discovery, two-week sprints. Elapsed time is dominated by WS0 (external) and WS8 (environment).

| Milestone | Contents | Exit check |
|---|---|---|
| **M0 — Suite accepted** (now) | This plan, the test suite and stubs reviewed; P1–P12 decisions confirmed or changed | Build compiles; suite runs red; decisions logged |
| **M1 — Core library** (end of sprint 1) | WS1, WS2, WS3 | All non-search connector specs green |
| **M2 — Full connector** (mid sprint 2) | WS4, WS0 results folded in | `WorkedExampleSpec` and every connector spec green; `OperationScriptsSpec` green through WireMock |
| **M3 — Mapping + discovery** (end of sprint 2) | WS5, WS6 | `npm test` green; discovery specs green; generated config validates against connector parser |
| **M4 — Deployed in test IDM** (sprint 3) | WS7, WS8 | Integration run green; Appendix A reproduced inside IDM |
| **M5 — Production ready** (sprint 4) | WS9 | Runbook signed off; dry-run recon reviewed |

**Critical path:** WS0 → WS4 → WS8 → WS9. Start WS0 on day one. WS5 and WS6 are off the critical path and can absorb slack.

**Parallelism:** WS1→WS2→WS3 are sequential for one engineer; WS5 can start immediately in parallel; WS6 starts after WS1.

---

## 6. Test strategy

### 6.1 Levels

| Level | What it proves | Tooling | Where |
|---|---|---|---|
| L1 Library unit | Pure functions: config, convention, model, codec, payload, error mapping, planning, scoping, paging | Spock data tables | `connector/src/test/groovy/…/{config,convention,model,codec,payload,http,schema,search}` |
| L2 Operation unit | Each ICF operation's behaviour against the documented service semantics (drop on POST, destructive PUT, 404 handling), including every §13 status and the PII logging rule | Spock + `FakeGraphService` (in-memory model of §2) + `CapturingLog` | `…/ops` |
| L3 Transport contract | Real HTTP Builder `RESTClient` → WireMock: paths, verbs, headers, Basic auth, JSON bodies, URL encoding, non-2xx passthrough, IO failures | WireMock 3 | `RestClientEntityApiSpec` |
| L4 Script harness | The seven scripts evaluated through `GroovyShell` with the harness's bindings, against WireMock — catches wrong binding names, wrong return types, missing returns | `ScriptHarness` | `OperationScriptsSpec`, `CustomizerScriptSpec` |
| L5 Scenario | Appendix A end to end, ordered | `@Stepwise` + `FakeGraphService` | `WorkedExampleSpec` |
| L6 Mapping library | JS library under Rhino-like isolation (`vm` context, `openidm`/`logger` globals, ES5 lint) with an in-memory IDM | Node 20+ built-in test runner, no dependencies | `mapping-lib/test` |
| L7 Discovery | Generator correctness and agreement with the connector's own validation | Spock | `discovery/src/test` |
| L7b Contract probe | The probe reports exactly the documented semantics when run against them, makes no writes in read-only mode, cleans up, and never persists credentials or foreign data | Spock + `FakeGraphHttpServer` + WireMock replay | `contract-probe/src/test` |
| L8 Integration (WS8) | Real IDM 7.5 harness, real Rhino, real recon engine | Docker, WireMock recordings or test tenant | `integration/` (to build) |
| L9 Operational dry run (WS9) | Real service, `REPORT`-only recon | IDM non-prod | runbook |

### 6.2 Conventions enforced by the suite

- **Red by construction, not by accident.** Stubs throw `UnsupportedOperationException("TODO WSn: …")`. Tests that expect `UnsupportedOperationException` for legitimate reasons (unsupported filters) also assert the message, so a stub cannot pass them.
- **Every test cites its design section** in the spec Javadoc or feature name.
- **No PII above DEBUG**: `CreateOperationSpec` and `OperationScriptsSpec` assert attribute values and the password never reach WARN/ERROR/INFO output (§13, §14).
- **Fixtures are the design's own examples**: `Fixtures` reproduces the §6 configuration and Appendix A documents verbatim so the suite doubles as executable documentation.
- **Service semantics live in one place**: `FakeGraphService` encodes §2 (drop-on-POST, destructive PUT, GET returns both directions, DELETE cascades, paging). When WS0 changes an assumption, change the fake and the affected spec together.
- **The paging assumption is quarantined** in `PageCursor`/`PageCursorSpec` (P6).

### 6.3 Catalogue of tests already written

| Spec | Design refs | Feature methods | Executions (incl. data-table rows) |
|---|---|---|---|
| `config/EntityKindSpec` | §3 A4, §6 | 4 | 17 |
| `config/DirectionSpec` | §7 | 3 | 10 |
| `config/ObjectTypesParserSpec` | §6 | 18 | 32 |
| `config/ConnectorConfigSpec` | §6, §15 | 13 | 29 |
| `convention/RelationshipConventionSpec` | §7 | 24 | 54 |
| `model/EdgeSpec` | §2.2 | 9 | 17 |
| `model/EntityDocumentSpec` | §2.1 | 8 | 8 |
| `codec/EdgeCodecSpec` | §8.3 | 10 | 22 |
| `payload/EntityPayloadBuilderSpec` | §8.3, §8.4, A3 | 21 | 23 |
| `payload/EntityDecoderSpec` | §9.4 | 10 | 10 |
| `payload/EdgeVerifierSpec` | §9.1 | 8 | 8 |
| `http/HttpErrorMapperSpec` | §13 | 20 | 102 |
| `http/HttpResponseSpec` | — | 2 | 2 |
| `http/RestClientEntityApiSpec` | §2, §14 | 12 | 24 |
| `schema/SchemaFactorySpec` | §5.2, §6, §9.5 | 10 | 13 |
| `search/SearchPlannerSpec` | §9.4 | 11 | 19 |
| `search/PageCursorSpec` | §9.4, §15 | 12 | 14 |
| `search/TypeScoperSpec` | §9.4 | 4 | 11 |
| `ops/ObjectClassResolverSpec` | §5.2 | 4 | 4 |
| `ops/CreateOperationSpec` | §9.1, §13 | 18 | 18 |
| `ops/UpdateOperationSpec` | §8.4, §9.2 | 14 | 14 |
| `ops/DeleteOperationSpec` | §9.3 | 8 | 8 |
| `ops/SearchOperationSpec` | §9.4 | 19 | 19 |
| `ops/TestAndSchemaOperationSpec` | §9.5 | 9 | 9 |
| `ops/ScriptSupportSpec` | §4 | 7 | 7 |
| `scripts/OperationScriptsSpec` | §4 | 13 | 13 |
| `scripts/CustomizerScriptSpec` | §4, §14 | 4 | 4 |
| `scenario/WorkedExampleSpec` | Appendix A | 6 | 6 |
| **Connector total** | | **301** | **517** |
| `discovery/ManagedSchemaReaderSpec` | §12 | 8 | 8 |
| `discovery/ObjectTypesGeneratorSpec` | §12, §7 | 16 | 16 |
| `discovery/MappingSkeletonGeneratorSpec` | §12, §8.2, §11 | 10 | 10 |
| `discovery/DiffSummarySpec` | §12 | 7 | 7 |
| **Discovery total** | | **41** | **41** |
| `mapping-lib/test/graphEntityPayload.test.js` | §7, §8.2, §6 | — | **67** |
| `contract-probe/ContractProbeSpec` | WS0, §2 | 18 | 18 |
| `contract-probe/ProbeOutputSpec` | WS0.11, §13, §14 | 9 | 9 |
| `contract-probe/ScrubberSpec` | §13 | 6 | 6 |
| `contract-probe/ProbeMainSpec` | WS0 | 7 | 14 |
| **Contract probe total** | | **40** | **47** |
| **Grand total** | | | **672** |

Current result (2026-11-04, after the harness fixes in §12.3): connector 9 pass / 503 fail / 5 skipped (the `@Stepwise` scenario stops after its first step); discovery 0 / 41; mapping library 3 pass / 64 fail. Every failure is a `TODO WSn` stub, with no failures caused by the test harness. The contract probe's 47 tests all pass, because the probe is finished code rather than a stub. The passing tests are structural checks: the scripts exist, there is no SyncScript, the customizer holds no credentials and its release closure is safe, the builder takes no API dependency, response helpers, edge equality, the library's exports and ES5 syntax.

### 6.4 Tests still to write (WS8/WS9)

- Integration: Appendix A inside IDM via recon; dropped-edge WARN visible in IDM logs; target-phase recon REPORTs an orphan; incremental drift script corrects a remote-side edge removal; `require('lib/graphEntityPayload')` loads under Rhino; provisioner refresh after an `objectTypes` change exposes a new class.
- Non-functional: enumeration of 50k entities with page size 200 completes within the recon window; memory stays flat across pages (results are streamed, not buffered — `SearchOperationSpec` already asserts streaming behaviour at unit level).
- Negative operational: `deleteMissingTolerated=false` end to end; 429 with `Retry-After` under queued sync shows retries in IDM's queue.

---

## 7. Environments, build and CI

- **Build:** Gradle 8.14 wrapper, Groovy 3.0.21 (IDM 7.5's Groovy line), Spock 2.3, WireMock 3.9 (standalone, shaded), HTTP Builder 0.7.1 (the `RESTClient` the harness binds). JDK 17+ (tests were run on 21).
- **ICF flavour:** `./gradlew test` uses ConnId from Maven Central; `./gradlew test -PicfFlavour=forgerock` uses `org.forgerock.openicf.framework:connector-framework:1.5.20.21` from `maven.forgerock.org`. The release artefact is built with the ForgeRock flavour.
- **Mapping library:** `cd mapping-lib && npm test` (Node 20+, no dependencies).
- **Contract probe:** `./gradlew :contract-probe:run --args="--base-url URL --username USER"`, with the password in `GRAPH_PROBE_PASSWORD`. Add `--allow-writes` only against a test instance. Usage is in `docs/service-contract.md` §1. Its tests run in CI with the rest of the Gradle suite, entirely on loopback.
- **Shared test fixtures:** `FakeGraphService` and its HTTP front-end `FakeGraphHttpServer` live in `connector/src/testFixtures`, so any subproject can test against the documented semantics.
- **CI:** `.github/workflows/ci.yml` runs both Gradle subprojects (ConnId flavour) and the Node suite on every push and pull request, publishing JUnit reports. Add a second job with the ForgeRock flavour once the runner has repository access (credentials via secrets, never in `gradle.properties`).
- **Packaging:** `./gradlew :connector:connectorScripts` → `connector/build/connector-scripts/{scripts,lib,provisioner…json}`.

---

## 8. Deployment and rollout to IDM

1. Copy `scripts/` to `&{idm.instance.dir}/connectors/graphentity/scripts`, `lib/` to `…/graphentity/lib`, `graphEntityPayload.js` to `&{idm.instance.dir}/script/lib/`.
2. Install `provisioner.openicf-graphentity.json` with the password encrypted; `objectTypes` generated by the discovery tool and reviewed.
3. `system/graphentity?_action=test`, then confirm `system/graphentity/user` and `/account` appear in the schema.
4. Install the generated mappings with **all target-phase situations on `REPORT`** (§11).
5. Initial onboarding order: resource types first, then subject types (reduces but does not eliminate dropped-then-healed edges, §9.1). Run one full recon per mapping; review `dropped edge` WARN counts, which should fall to zero after the second mapping's recon.
6. Schedule: incremental drift check hourly, full recon per type daily during onboarding, weekly afterwards.
7. Promote `TARGET_IGNORED`/`UNQUALIFIED` → `DELETE` per type only after at least two full recon cycles with zero unexplained TARGET-only reports.

---

## 9. Risk register

| Risk | Impact | Likelihood | Mitigation / owner |
|---|---|---|---|
| Service paging/search contract differs from P6 | WS4 rework, delayed M2 | Medium | WS0 first; all paging knowledge in `PageCursor`; adjust fake + spec together |
| `POST` does not return 409 on duplicates | Create of an existing entity may overwrite silently | Medium | WS0 to confirm; fallback: HEAD/GET-before-POST in `CreateOperation` behind a flag |
| Archetypes do not discriminate types | Target recon scope leaks across types | Low–Medium | P5 adds BaseType; `idPattern` fallback reserved in `TypeScoper` |
| A1 erosion (another writer) | Connector deletes foreign edges | Low now | Runbook watch item; read-merge-write evolution path per §15 |
| Mutable `idProperty` | Orphans on rename | Medium | Discovery tool prompts for natural keys; runbook documents orphan behaviour; optional `onUpdate` guard mapping snippet in WS9 |
| Partial-attribute updates | Mass edge deletion | Low with P4 | Connector-side guard (P4) + generated mappings + drift recon |
| ForgeRock Maven access from CI | Release build cannot run in CI | Medium | Flavour switch (P8); add credentials as secrets; fallback manual release build |
| Rhino incompatibility in the JS library | Mapping transformations fail at runtime | Low | ES5 lint test; WS8 loads the library in real IDM |
| ICF API difference between ConnId and ForgeRock | Compiles in CI, fails in IDM | Low | Only `org.identityconnectors.framework.common.*` is used; WS7 deploys to real IDM early |
| Recon parallelism and live sync overlap during onboarding | Transient wrong intermediate states (§10) | Medium | Runbook: do not run clique recon and live sync concurrently during onboarding; conservative `taskThreads` |

---

## 10. Open questions (owners to confirm)

1. **Service team:** all §15 items (search param, datetime format, paging, 409, error body, archetype discrimination), plus page-size upper limit and rate limits (429 policy).
2. **Platform team:** confirm IDM 7.5 Groovy version (3.0.x assumed), whether `maven.forgerock.org` credentials can be provided to CI, and the IDM instance directory layout for `scriptRoots`/`classpath`.
3. **Design owner:** accept or amend P1–P12, in particular P3 (passive-form rule), P4 (connector-side guard) and P12 (package name).
4. **Operations:** desired recon cadence and the criteria for promoting `REPORT` → `DELETE` per type.
5. **Security:** service account scoping on the admin API and the TLS/mTLS target for non-localhost deployment (§14).

---

## 11. Definition of done

A workstream is done when its named specs are green with no test modified except to reflect a WS0-confirmed contract change or an accepted plan decision, code review has checked the §8.4 invariant (every update payload carries the full edge set), and the design → code map in §2.3 is still accurate.

The project is done when M5 is reached: all 672 unit-level tests green under both ICF flavours, WS8 integration run green and archived, runbook signed off, and a dry-run recon reviewed with `REPORT` policies against the real service.

---

## 12. Workstream task lists

This section breaks every workstream in §4 into tasks small enough to pick up, review and tick off. It is the working checklist for the build; §4 remains the summary.

### 12.1 Conventions

- **IDs** are `WSn.mm`. Cite the ID in commit messages and pull request titles so progress can be traced to this list.
- **Size:** **S** is up to half a day, **M** is about a day, **L** is two days or more. Sizes include writing or adjusting tests, not review.
- **Green** names the specs, or the parts of specs, that must pass for the task to be done. Spec execution counts are in §6.3.
- **Gotcha** records something already known to be tricky, found while writing the suite. Read these before starting the task.
- A task that changes a test's expectation must say why in the pull request. Acceptable reasons are a WS0-confirmed service fact or an accepted plan decision. "The test was inconvenient" is not one.
- Tick a box only when its **Green** criterion passes on CI.

### 12.2 Summary

| Workstream | Tasks | Revised estimate (days) | Change from §4 |
|---|---|---|---|
| WS0 Service contract spike | 13 | 2 + elapsed | +1 task (probe, done) |
| WS1 Configuration, convention, schema | 17 | 3.5 | +0.5, schema operation-set gotcha |
| WS2 Model, codec, payload | 9 | 2 | none |
| WS3 Transport, errors, write operations | 15 | 5 | +1, harness binding verification and partial-update guard |
| WS4 Search, scoping, paging | 9 | 3 | none |
| WS5 Mapping library and drift check | 9 | 5 | +2, drift-check script and Rhino test added |
| WS6 Discovery tool | 9 | 6 | +2, CLI, read-only REST mode and mapping lint added |
| WS7 Packaging and deployment | 8 | 2.5 | +0.5 |
| WS8 Integration in IDM 7.5 | 13 | 6 | +1 |
| WS9 Operational readiness | 9 | 3 + review | none |
| Cross-cutting | 6 | ongoing | new |

The additions in WS3, WS5 and WS6 come from gaps found while writing these lists. They are named in the tasks themselves. WS5 and WS6 are off the critical path, so §5's milestones still hold. WS3 gains a day on the path to M1.

### 12.3 Already done (2026-11-04)

These were found and fixed while preparing this section. They are recorded so nobody repeats the diagnosis.

- [x] **H.01 Restore HTTP Builder's runtime dependencies.** `json-lib` (with its `jdk15` classifier), `xml-resolver` and `groovy-xml` had been excluded, so every WireMock-based spec failed with `NoClassDefFoundError` before reaching the code under test.
- [x] **H.02 Fix the customizer DSL stand-in.** `CustomizerScriptSpec` used a Map as the closure delegate, so `init { }` never resolved. It now uses a delegate class that also records `decorate`.
- [x] **H.03 Fix a null paging cookie in a test.** The cookie-following loop in `SearchOperationSpec` passed `null` to `setPagedResultsCookie`, which ICF rejects.
- [x] **H.04 Make the ES5 check ignore comments.** Backticks in the library's comments made the check fail against the stub.
- [x] **H.05 Remove accidental passes.** Two JavaScript assertions were satisfied by the stub's own error message, and two discovery assertions were vacuous (one ended in `|| true`). All four now assert something a stub cannot satisfy. A new test requires an explicit policy for every recon situation.
- [x] **H.06 Verify the red baseline.** Every failing test in all three suites now fails on a `TODO WSn` stub, with no failures caused by the harness. The structural tests that pass are listed in §6.3.

---

### WS0 — Service contract spike

Owner: connector lead with the service team. Output: `docs/service-contract.md`. Every answer is recorded as *confirmed* (with evidence) or *agreed assumption* (with who agreed).

**Progress, 2026-11-04.** The question register is written and the contract probe (WS0.13) is built and tested. No question is answered yet: no instance of the service was reachable from the environment that did this work. The next step is a person running the probe:

1. Run it read-only against any instance.
2. Run it with `--allow-writes` against a test instance.
3. Bring `report.md` and section 3 of the register to the WS0.01 session.

The probe covers WS0.02–WS0.10 and produces the WS0.11 recordings. Its finding keys are listed against each question in the register.

- [ ] **WS0.01 Book the session and name the service-side contact (S).** Send the question list below in advance so answers can be checked against a running instance, not memory.
  *Progress:* the question pack is ready. It is `docs/service-contract.md`, sections 2 and 3, with the service-team-only questions in section 3, led by T1, design invariant A1. Booking the session and naming a contact need a person.
- [ ] **WS0.02 Search endpoint (S).** Confirm the exact path, with or without a trailing slash. Confirm the timestamp parameter name and datetime format: ISO-8601 with `Z`, an offset, or milliseconds, and the precision. Confirm whether the filter is strictly after or inclusive.
  *Impacts:* `ConnectorConfig.DEFAULT_TIMESTAMP_PARAM`, and whether `SearchPlanner` maps `GreaterThanOrEqual` differently from `GreaterThan` (WS4.04).
- [ ] **WS0.03 Paging protocol (M).** Confirm offset or cursor, the parameter names, and the response envelope: a top-level array or `items`, with or without `totalCount` or `nextCursor`. Confirm the maximum page size and whether ordering is stable across pages.
  *Ask specifically:* if an entity is updated mid-enumeration, can it be skipped or returned twice? Being returned twice is harmless for recon. Being skipped is not.
  *Impacts:* `PageCursor`, `PageCursorSpec`, `FakeGraphService.search`, `ConnectorConfig.MAX_PAGE_SIZE`.
- [ ] **WS0.04 Duplicate `POST` (S).** Ask whether it returns 409, silently upserts, or returns 400. If it upserts, raise the risk in §9 and decide whether `CreateOperation` should `GET` first behind a flag.
- [ ] **WS0.05 Error bodies (S).** Capture the shape for 400, 401, 403, 404, 409, 429 and 5xx. Ask whether any body can echo request content, which matters for PII and redaction.
- [ ] **WS0.06 Read-only fields (S).** Confirm the field name and format of `lastUpdated`, whether `GET` and search both return it, and whether the service rejects it on `PUT`.
- [ ] **WS0.07 Edge semantics (M).** Verify against a running instance:
  - `POST` silently drops edges to missing endpoints (A7).
  - `GET` returns inbound edges as `SourceID`.
  - A `PUT` asserting an inbound edge is accepted.
  - A `PUT` with an edge to a missing entity is dropped silently, rejected with 400, or kept dangling.
  - `DELETE` cascades edges.
  *Impacts:* `FakeGraphService` (it currently drops on `PUT` as well), and possibly `UpdateOperation` verification.
- [ ] **WS0.08 Type discrimination (S).** Confirm that `BaseType` is only ever 2 or 4. Confirm whether archetypes are unique per IDM type in practice. Decide whether the `idPattern` fallback is needed (P5).
- [ ] **WS0.09 ID constraints (S).** Confirm allowed characters, maximum length, case sensitivity, and how an ID containing `/`, `?`, `#` or a space must be encoded in the path.
  *Impacts:* `RestClientEntityApi.encodeSegment` and `RestClientEntityApiSpec`'s encoding table.
- [ ] **WS0.10 Auth and limits (S).** Confirm whether Basic auth is preemptive and the semantics of 401 versus 403. Confirm rate limits, and whether 429 carries `Retry-After` as seconds or as an HTTP-date. Confirm the service account's scoping options (§14).
- [ ] **WS0.11 Record real traffic (M).** *Progress:* the probe writes every exchange as a scrubbed WireMock stub. What remains is a run against the real service, then choosing which stubs to keep. Capture one real document of each type plus a search page as WireMock JSON under `connector/src/test/resources/wiremock/`. Scrub PII and replace names with Appendix A values. These recordings feed WS8 and a contract spec in WS4.09.
- [ ] **WS0.12 Fold the answers into the code and plan (M).** Update `FakeGraphService`, `PageCursor`, `PageCursorSpec`, `Fixtures` and the default `timestampSearchParam`. Update `HttpErrorMapper` and its spec if 409 differs. Add a decision-log entry in §3 for each changed assumption, and close the matching items in §10.
  *Green:* all suites still compile, and changed specs fail only on stubs.
- [x] **WS0.13 Contract probe (L). Done 2026-11-04.**
  - Built `contract-probe/`, a command-line tool that observes the service and answers the observable WS0 questions, citing numbered exchanges as evidence.
  - It is read-only by default. `--allow-writes` limits writes to `ws0-probe-<run>-` IDs and always cleans up.
  - Outputs never contain credentials or data from entities it did not create.
  - It was tested against `FakeGraphHttpServer`, an HTTP front-end over the in-memory model of design §2, so a difference in a real run's report is a real difference in the service.
  *Green:* `ContractProbeSpec`, `ProbeOutputSpec`, `ScrubberSpec`, `ProbeMainSpec` (47 tests).

### WS1 — Configuration, convention and schema

Do these in order; later tasks reuse earlier ones.

- [ ] **WS1.01 Settle the package name (S).** If P12 changes, rename `au.com.azwork.idm.graphentity` across all three projects now, before any implementation lands.
- [ ] **WS1.02 `EntityKind` (S).** `fromConfig` trims the value and ignores case. A null, blank or unknown value throws `ConfigurationException`, with "entityKind" and the value in the message. `fromBaseType` returns null for an unknown or null BaseType, so a strange remote document never fails a read.
  *Green:* `EntityKindSpec`.
- [ ] **WS1.03 `Direction` (S).** `fromConfig` ignores case and rejects null, blank or unknown values with `ConfigurationException`. Implement `opposite`.
  *Green:* `DirectionSpec`.
- [ ] **WS1.04 `ObjectTypesParser.parse(String)` (S).** Parse with `JsonSlurper` and convert `JsonException` to `ConfigurationException`. Reject null, blank, a non-object root, a type whose value is not an object, and an empty document.
- [ ] **WS1.05 `ObjectTypesParser.parse(Map)` (M).**
  - The type name comes from the map key and the relationship property from its key.
  - `idmResource`, `entityKind` and `idProperty` are required. A missing one is reported with the type and field named.
  - `attributes` and `archetypes` must be lists of non-blank strings. Missing optional blocks default to empty.
  - Reserved attribute names are rejected: `directArchetypes`, `entityRelationships`, `lastUpdated`, `__UID__`, `__NAME__` and `ID`.
  - A relationship entry is *explicit* only when it has both `remoteType` and `direction`. Having only one of them is rejected as a half override, naming the property. `sync` defaults to true. `relatedType` and `reverseProperty` are carried as hints (P2).
  *Gotcha:* `ObjectTypeConfig` and `RelationshipConfig` use `@Canonical` equality. Normalise every list to `ArrayList` and every map to `LinkedHashMap`, or "map input equals JSON input" fails on collection type alone.
  *Green:* `ObjectTypesParserSpec`.
- [ ] **WS1.06 `ObjectTypeConfig.syncedRelationships` (S).** Return the entries whose `sync` is not false, in configuration order.
- [ ] **WS1.07 `RelationshipConvention.normalise` (S).** Upper-case the first letter. Strip a trailing `Set` or `List` only when it is a capitalised suffix with something before it, so `ownsSet` becomes `Owns` but `settings`, `set` and `list` keep their letters. A blank value throws `IllegalArgumentException`.
- [ ] **WS1.08 `RelationshipConvention.isPassive` (S).** Strip any `Set`/`List` suffix first. The name is passive when it then ends in a capital `By` with at least one character before it. So `ownedBy` and `ownedBySet` are passive, and `by` and `standby` are not.
- [ ] **WS1.09 `RelationshipConvention.resolvePair` (S).** The result must not depend on argument order. Exactly one side must be passive, and the remote type is `normalise` of the active side. Report the pair unresolved, with a reason, when neither or both sides are passive, the reverse is null or blank, or the pair is self-referential.
- [ ] **WS1.10 `RelationshipConvention.resolve` (S).**
  1. `sync: false` returns null.
  2. An explicit override is used as-is.
  3. Otherwise apply the convention, using the reverse property from the argument or from the entry's hint.
  4. If none of these resolve it, throw `ConfigurationException` naming both properties.
- [ ] **WS1.11 `RelationshipConvention.validate` (L).**
  - Resolve every synced entry, explicit or convention-derived.
  - Find each entry's counterpart through `relatedType` plus `reverseProperty`. Same-type pairs such as `manager`/`reports` on `user` must work.
  - Require opposite directions and the same `remoteType` on each pair.
  - Report collisions: two entries on one type with the same `remoteType`, direction and related type.
  - Collect *all* violations into one `ConfigurationException`.
  - Return warnings for a counterpart type that isn't configured and for entries without hints.
  *Green:* `RelationshipConventionSpec`.
- [ ] **WS1.12 `ConnectorConfig.from` (M).**
  - `serviceAddress` must be an http or https URI with a host. Strip a trailing slash.
  - `username` and `password` are required, and `password` may be a String or `char[]`.
  - `objectTypes` may be a JSON string or a Map. Parse it through WS1.04/05, then run WS1.11.
  - Coerce string forms of booleans and integers, because `customConfiguration` delivers them that way.
  - `pageSizeDefault` must be between 1 and `MAX_PAGE_SIZE`.
  - `typeFor` is null-safe.
  - Validation warnings need a way out. Add a `warnings` property on the config for `ScriptSupport` to log at WARN.
  *Green:* `ConnectorConfigSpec`.
- [ ] **WS1.13 `ObjectClassResolver` (S).** A null or unknown class throws `ConnectorException`, naming it and the configured classes. There is no implicit `__ACCOUNT__` alias.
  *Green:* `ObjectClassResolverSpec`.
- [ ] **WS1.14 `SchemaFactory.objectClassInfo` (S).** `__NAME__` is required. Domain attributes are single-valued strings. `directArchetypes` and `entityRelationships` are multivalued strings. `lastUpdated` is readable but neither creatable nor updatable.
  *Decide:* whether `lastUpdated` should be returned by default. The spec title says it is not, but no assertion checks this, because mappings don't map it. Pick one, then make the spec assert it.
- [ ] **WS1.15 `SchemaFactory.build` (M).** Use one `SchemaBuilder` for all types. Support Schema, Test, Search, Get, Create, Update and Delete. Do not support Sync or Authenticate.
  *Gotcha:* `SchemaBuilder` derives its default operation set from the SPI interfaces the connector class implements. The spec passes the bare `Connector` interface, which implements none. In IDM the class is `ScriptedRESTConnector`, which implements all of them, including Sync. So the implementation must add and remove support per operation explicitly. Alternatively, change the spec to pass a test class implementing exactly the seven SPI interfaces. Record the decision in the PR.
  *Green:* `SchemaFactorySpec`.
- [ ] **WS1.16 `SchemaOperation` (S).** Delegate to `SchemaFactory` and make no HTTP calls.
  *Green:* the three schema cases in `TestAndSchemaOperationSpec`.
- [ ] **WS1.17 WS1 review pass (S).** Check that every exception message names the type and property involved, that no message contains configuration secrets, and that the §2.3 map still matches the code.

### WS2 — Model, codec and payload

- [ ] **WS2.01 `Edge` (S).**
  - Implement `getDirection` and `getOtherId`.
  - `toWire` emits exactly two keys, endpoint first then `type`, and never a null key.
  - `fromWire` accepts only `TargetID` and `SourceID` in that exact casing. It rejects both endpoints together, neither endpoint, blank values and non-string values with `IllegalArgumentException`.
  - Implement `flip`.
  *Green:* `EdgeSpec`.
- [ ] **WS2.02 `EntityDocument` (S).**
  - `toWire` emits `ID`, `BaseType`, `attributes`, `Relationships` and `directArchetypes`. It always emits the two collections, even when empty, so a `PUT` is explicit, and never emits `lastUpdated`.
  - `fromWire` requires `ID`, coerces `BaseType` from a String or Long, defaults missing collections to empty, and reads `lastUpdated`.
  *Green:* `EntityDocumentSpec`.
- [ ] **WS2.03 `EdgeCodec.serialise` and `serialiseAll` (S).** Produce canonical JSON: endpoint key, then `type`, with no whitespace. `serialiseAll` sorts its output by the serialised string.
  *Gotcha:* Groovy's `JsonOutput` escapes non-ASCII as `\uXXXX`. That is correct, but log lines become unreadable. Prefer a `JsonGenerator` with unicode escaping disabled.
- [ ] **WS2.04 `EdgeCodec.parse` (M).** Reject malformed input with `InvalidAttributeValueException` naming the offending value. That covers a non-object, a non-string endpoint, both endpoints, no type, and trailing garbage.
  *Gotcha:* `JsonSlurper`'s default parser can be lenient about trailing characters. Check the `'{…} trailing'` row first. If it passes when it should fail, add an end-of-input check or use `JsonSlurperClassic`.
- [ ] **WS2.05 `EdgeCodec.parseAll` (S).** Null or empty input returns an empty list. Remove duplicates, keeping first-seen order. Name the offending value in the exception.
  *Green:* `EdgeCodecSpec`.
- [ ] **WS2.06 `EntityPayloadBuilder.idForCreate` (S).** Prefer `__NAME__`, fall back to `__UID__`, and throw `InvalidAttributeValueException` if neither is present.
- [ ] **WS2.07 `EntityPayloadBuilder.build` (M).**
  - Validate the id: non-blank, and equal to `__NAME__` when present (A3).
  - Skip `__NAME__` and `__UID__`. Reject `ID` and any attribute not configured, naming them.
  - Map domain values: one value becomes a scalar, several become a list, and a present-but-empty attribute becomes null so the `PUT` clears it. An absent attribute is omitted.
  - Set `attributes.ID` to the id.
  - Build `directArchetypes` as the static archetypes first, then delivered values, without duplicates.
  - Build `Relationships` from `EdgeCodec.parseAll`. Wrap errors so the message names `entityRelationships`.
  *Gotcha:* the decode-then-build round trip in `EntityDecoderSpec` feeds a decoded object's attributes back into `build`. Those attributes include `__UID__` and `lastUpdated`. `build` must **ignore** `lastUpdated`, which is read-only, rather than reject it as unknown.
  *Green:* `EntityPayloadBuilderSpec`.
- [ ] **WS2.08 `EntityDecoder.decode` (M).**
  - Set `__UID__` and `__NAME__` from the document ID.
  - Include only configured domain attributes that are present remotely.
  - Convert non-string scalars to strings, and lists to multivalued strings.
  - Never surface `attributes.ID`.
  - Always include `entityRelationships`, even when empty, through `serialiseAll`.
  - Include `lastUpdated` only when the service returned it.
  *Green:* `EntityDecoderSpec`.
- [ ] **WS2.09 WS2 review pass (S).** Confirm that `build` and `decode` remain pure functions with no API, log or clock dependencies (§8.1).

### WS3 — Transport, error mapping and write operations

- [ ] **WS3.01 Verify the Scripted REST harness contract for toolkit 1.5.20.x (M). Do this first.**
  Ping's connector reference says the customizer script implements `init` and `decorate` closures. Operation scripts receive the *output of `decorate`* as their connection, and ForgeRock's samples use `customizedConnection as RESTClient`. Our scripts currently read `connection` and our customizer has no `decorate` closure. Settle the following from the connector bundle's own sample scripts or the vendor docs:
  - the binding name for the decorated client;
  - the signatures of `init`, `decorate` and `release`;
  - whether create receives an `id` binding;
  - whether a Search script may return a `SearchResult` to pass the paging cookie;
  - whether the Schema script returns a `Schema` or must call `builder.schema { }`;
  - the `operation` values Update receives (WS3.10).
  Record the results in `docs/service-contract.md` under "Harness contract". Then:
  - add a `decorate` closure that returns a `RESTClient` configured for JSON, whose failure handler does not throw;
  - make the scripts and `ScriptSupport` read the confirmed binding;
  - update `ScriptHarness`, `CustomizerScriptSpec` and `OperationScriptsSpec` to match.
  *Note:* `docs.pingidentity.com` was outside the network allow-list of the session that wrote this plan, so the vendor page could not be read in full.
- [ ] **WS3.02 `HttpErrorMapper.raise` (M).** Implement the §13 table exactly as `HttpErrorMapperSpec` encodes it. Messages read like "HTTP 400 during CREATE: <redacted body>". A 429 message carries the `Retry-After` value. A tolerated 404 returns true.
  *Gotcha:* `CreateOperationSpec` expects the 400 body to appear in an ERROR log line, but `raise` has no logger. Either the operations log before calling `raise`, or `raise` takes a `Log`. Choose one approach for all operations.
- [ ] **WS3.03 `HttpErrorMapper.retryAfterSeconds` (S).** Accept non-negative delta-seconds, or an RFC 1123 HTTP-date converted to seconds from now and clamped at zero. Anything else returns null. Header lookup ignores case.
- [ ] **WS3.04 `HttpErrorMapper.redact` (S).** Mask `Authorization` header values (Basic and Bearer), JSON `password` fields in any case, and `authorization=` parameters. Leave other text untouched and accept null.
  *Green:* `HttpErrorMapperSpec`.
- [ ] **WS3.05 `RestClientEntityApi` (L).** Implement the five calls over `RESTClient`. Convert responses to `HttpResponse`, carrying the status, the parsed body (Map or List, or null for a non-JSON body), the raw body and the headers. Never throw for a non-2xx status. Let transport failures propagate as `IOException`.
  *Gotcha:* `RESTClient` parses bodies by content type and consumes the stream, so the raw body is lost unless you take control. Request text and parse JSON with `JsonSlurper` yourself, or register a buffering parser.
  *Gotcha:* the path encoding. `RESTClient` and `URIBuilder` can re-encode `%` as `%25`. The spec requires `GET` of `a/b` to hit `/admin/entities/a%2Fb` exactly, so build the request URI from the already-encoded segment.
  *Gotcha:* `URLEncoder` encodes a space as `+`, but a path segment needs `%20`.
  *Green:* `RestClientEntityApiSpec`.
- [ ] **WS3.06 `ScriptSupport` (M).**
  - `propertiesFrom` converts the `serviceAddress` URI to a string and the `GuardedString` password through `access()`, without mutating the configuration.
  - It copies the property bag keys. When the bag lacks `objectTypes`, it parses `customConfiguration` with `ConfigSlurper`.
  - `contextFrom` builds the `OperationContext`. A null connection is allowed for Schema.
  - It logs the config's validation warnings at WARN.
  - Cache the parsed `ConnectorConfig`, keyed by a hash of the `objectTypes` text. Recon calls the scripts thousands of times, and re-parsing and re-validating each time is waste.
  *Green:* `ScriptSupportSpec`.
- [ ] **WS3.07 `EdgeVerifier` (S).** `dropped` compares as sets, treating null as empty. `warnMessage` uses `-T->` for outbound and `<-T-` for inbound edges, with IDs and type only.
  *Green:* `EdgeVerifierSpec`.
- [ ] **WS3.08 `CreateOperation` (M).**
  1. Resolve the type, get the id from `idForCreate`, and build the document. Malformed input fails here, before any HTTP call.
  2. `POST`, then map the outcome, logging a 400 body at ERROR.
  3. Run the verification `GET`. If it fails, log WARN "verification GET failed for <id>" and still return the Uid (P11).
  4. Log each dropped edge at WARN.
  5. If `rePutOnDroppedEdges` is on and edges were dropped, `PUT` the same document.
  6. Convert any `IOException` to `ConnectorIOException`.
  7. Return `new Uid(id)`.
  Never log attribute values above `OK`.
  *Green:* `CreateOperationSpec`.
- [ ] **WS3.09 `UpdateOperation` (M).** Resolve the type. Require `entityRelationships` and `directArchetypes` to be present (P4), or throw `InvalidAttributeValueException` naming the missing one. Build from `uid.uidValue`, `PUT`, and map the outcome. Never `GET`.
  *Green:* `UpdateOperationSpec`.
- [ ] **WS3.10 Reject partial-value updates in `UpdateScript` (S). New test needed.** The Groovy toolkit routes `ADD_ATTRIBUTE_VALUES` and `REMOVE_ATTRIBUTE_VALUES` through the update script with a different `operation` binding. Those carry deltas, not the full state, so turning them into a `PUT` would delete edges. Throw `UnsupportedOperationException` for them, and add an `OperationScriptsSpec` case for each.
- [ ] **WS3.11 `DeleteOperation` (S).** `DELETE`, then map the outcome. A tolerated 404 logs INFO with the id. Never `GET`.
  *Green:* `DeleteOperationSpec`.
- [ ] **WS3.12 `TestOperation` (S).** Run a search from epoch with page size 1 using `PageCursor`'s first page, then map with `TEST` context. Convert `IOException` to `ConnectorIOException`.
  *Green:* the test cases in `TestAndSchemaOperationSpec`.
- [ ] **WS3.13 `CustomizerScript` (M).**
  - `init` adds a preemptive Basic `Authorization` default header from `configuration.username` and the `GuardedString` password.
  - It sets connect, socket and connection-request timeouts on a `RequestConfig`. Defaults come from constants and can be overridden through property bag keys `connectTimeoutMs` and `socketTimeoutMs`. Document both keys in the provisioner sample.
  - It uses the system trust store for https, and logs WARN when the address is http on a host other than localhost (§14).
  - `release` stays safe to call.
  - Add `decorate` as decided in WS3.01.
  *Green:* `CustomizerScriptSpec`.
- [ ] **WS3.14 Scripts end to end through WireMock (S).** The create, update, delete and test cases of `OperationScriptsSpec` pass, including the new WS3.10 cases.
- [ ] **WS3.15 Logging review (S).** Grep every `log.` call. Above `OK` level, a message may contain IDs, edge shapes, statuses and redacted bodies, never attribute values or credentials (§13, §14).

### WS4 — Search, scoping and paging

Build against assumption P6. Adjust after WS0.03.

- [ ] **WS4.01 `PageCursor.encode` and `decode` (S).** Encode as URL-safe base64 of compact JSON, such as `{"o":400,"c":"tok"}`. A blank cookie means the first page. Anything undecodable, a negative offset or a non-integer offset throws `IllegalArgumentException`. Never silently restart from the first page.
- [ ] **WS4.02 `PageCursor.toQuery` (S).** With an opaque cursor, return `[cursor:, pageSize:]`. Otherwise return `[offset:, pageSize:]`. The parameter names come from WS0.03.
- [ ] **WS4.03 `PageCursor.next` (S).**
  - An empty page is the end.
  - A `nextCursor` value becomes the opaque cursor.
  - With `totalCount`, continue while offset + page size is below the total.
  - Without either, a full page continues and a short page ends.
  *Green:* `PageCursorSpec`.
- [ ] **WS4.04 `SearchPlanner.plan` (M).**
  - No filter means `ENUMERATE` from epoch.
  - An `EqualsFilter` on `__UID__` or `__NAME__` means `BY_ID`.
  - `GreaterThan` or `GreaterThanOrEqual` on `lastUpdated` means `SINCE`. Validate the value with `Instant.parse` and throw `IllegalArgumentException` if it fails.
  - Every other filter throws `UnsupportedOperationException` with "filter" in the message (P7).
  - Page size comes from the options (which may be null), capped at `MAX_PAGE_SIZE`, defaulting to `pageSizeDefault`.
  - Decode the cookie into the cursor. Ignore it for `BY_ID`.
  *Decide after WS0.02:* if the service filter is strictly-after, `GreaterThanOrEqual` must subtract the smallest time unit the service resolves.
  *Green:* `SearchPlannerSpec`.
- [ ] **WS4.05 `TypeScoper.matches` (S).** The document matches when its `BaseType` equals the type's kind and its `directArchetypes` contain all of the type's static archetypes. The comparison is case-sensitive and null-safe. Add the `idPattern` fallback only if WS0.08 calls for it.
  *Green:* `TypeScoperSpec`.
- [ ] **WS4.06 `SearchOperation` by id (S).** `GET`. A 404 returns an empty `SearchResult`. Otherwise decode, check type scope, and call the handler.
- [ ] **WS4.07 `SearchOperation` since/enumerate (L).**
  - Loop over pages. The query is the timestamp parameter plus `cursor.toQuery(pageSize)`.
  - Map each response with `SEARCH` context. Read items from `items`, or from a top-level array if WS0.03 says so.
  - Decode each document and skip any that fail, with exactly one WARN carrying no attribute values. Scope each document and call the handler, stopping when it returns false.
  - If the caller set a page size, return after one page with `SearchResult(cookie, remaining)`. Remaining is `totalCount − consumed`, or −1 when unknown. Otherwise stream every page and return a result without a cookie.
  - Results are never buffered across pages.
  *Gotcha:* the toolkit's `handler` binding is a `Closure` in Groovy scripts but may be a `ResultsHandler` elsewhere. Accept both.
  *Gotcha:* scoping happens after paging, so a page can hand fewer objects to the handler than the page size. Per §9.4, that is expected and correct. The cookie and remaining count still reflect the remote page.
  *Green:* `SearchOperationSpec`, the search cases of `OperationScriptsSpec`, and `WorkedExampleSpec`. **WorkedExampleSpec green is milestone M2's headline check.**
- [ ] **WS4.08 Remote document drift (S).** If WS0.06 shows `GET` and search returning different document shapes, cover both in `EntityDecoder`, with a test per shape.
- [ ] **WS4.09 Contract spec from recordings (M). New test.** Add a `RecordedContractSpec` that replays the WS0.11 WireMock recordings through `RestClientEntityApi`, `EntityDocument.fromWire` and `EntityDecoder`. If the real service drifts from the fake, this spec fails first.

### WS5 — Mapping-side payload library and drift check

The library must stay ES5. Its §7 rules must match the Groovy ones exactly.

- [ ] **WS5.01 One source for convention cases (S).** Move the normalisation, passive-detection and pair cases into `fixtures/convention-cases.json` at the repository root. Have both `RelationshipConventionSpec` and the JavaScript tests read that file, so the two implementations cannot drift apart.
- [ ] **WS5.02 `normalisePropertyName`, `isPassive` and `resolvePair` (S).** Port WS1.07–WS1.09. Errors for blank input must say the name is blank, empty or required.
- [ ] **WS5.03 `resolveRelationship` (M).** An explicit override wins, then the convention using the entry's `reverseProperty` hint. Failing both, look up `reversePropertyName` in `config/managed`, handling both a direct relationship property and array `items`. `sync: false` returns null. An unresolvable property throws, naming it.
- [ ] **WS5.04 `loadTypeConfig`, `clearCache` and the config cache (M).**
  - Read `config/provisioner.openicf/graphentity`.
  - Take `objectTypes` from `configurationProperties.objectTypes` if present. Otherwise extract it from `customConfiguration`'s `objectTypes='…'` with a minimal parser that handles the `\'` escape.
  - Cache it, with `reload` and `clearCache`. Attach `name` to the returned type.
  - An unknown type throws, listing the configured ones.
  *Gotcha:* a module-level cache lives as long as IDM's script engine keeps the module, which is not documented to survive or expire. Add an optional TTL, defaulting to five minutes, so a provisioner change is picked up without a restart. Have WS8.09 confirm the actual lifetime.
- [ ] **WS5.05 `buildRelationships` (L).**
  - Read the source with `["*", "*_ref"]`. A missing source throws, naming it.
  - Walk the configured synced relationship properties and any relationship-valued property found on the object. Resolve unlisted ones through WS5.03.
  - For each reference, find the configured type whose `idmResource` matches `_refResourceCollection`. If none matches, log WARN and skip.
  - Read the related object. If it is missing or lacks the related type's `idProperty`, log WARN and skip.
  - Emit canonical JSON, remove duplicates and sort.
  *Gotcha:* some IDM versions return only `_ref` and not `_refResourceCollection`/`_refResourceId`. Fall back to splitting `_ref`.
  *Gotcha:* this is one read per related object, so recon of a well-connected type is expensive. Cache related reads within one call now. WS8.11 then evaluates field expansion (`fields: ["owns/accountId"]`) to collapse them into a single read.
- [ ] **WS5.06 `buildArchetypes` (S).** Static archetypes first, then a single or multi-valued `archetypeProperty`, without duplicates. Non-string values throw, naming the property. A null source throws, mentioning the source. Never mutate the type configuration.
  *Green for WS5.02–WS5.06:* `npm test` in `mapping-lib`.
- [ ] **WS5.07 Rhino smoke test (M). New test.** Add a Gradle test that evaluates `graphEntityPayload.js` under Mozilla Rhino 1.7.x, with a fake `openidm`, and calls each function. Node's `vm` is close to Rhino but not identical. This catches Rhino-only failures before WS8.
- [ ] **WS5.08 Incremental drift check script (L). New deliverable, design §11.**
  - Write `mapping-lib/src/script/graphEntityDriftCheck.js` and a schedule sample `config/schedule-graphentity-drift.json`.
  - For each mapping, query `system/graphentity/<type>` with `lastUpdated gt "<checkpoint>"`. For each hit, run `recon?_action=reconById` so the mapping's own policies decide whether to `UPDATE` or `REPORT`.
  - Store the checkpoint, for example in a small managed or config object. Only advance it after a whole page succeeds.
  - Log counts only.
  - Write its tests first in `mapping-lib/test/driftCheck.test.js`. Cover: a checkpoint advances only on success; a failure mid-page re-processes that page; and a remote deletion is not seen, which documents the §11 blind spot.
- [ ] **WS5.09 Library README (S).** Explain how mappings `require` it, its functions, ES5 constraints, and the cache and TTL behaviour.

### WS6 — Discovery and generation tool

- [ ] **WS6.01 Make the natural-key signal real (S).** The `indices` key in `managed-sample.json` is a placeholder; real `managed.json` has no such key. Agree with the platform team which real signal marks a natural key, for example a uniqueness policy on the property. Update the sample, `ManagedSchemaReader` and `ManagedSchemaReaderSpec` together.
- [ ] **WS6.02 `ManagedSchemaReader.read` (M).**
  - Require an `objects` array, or throw `IllegalArgumentException`.
  - Domain properties exclude `_*`, `isVirtual`, `isProtected` and `password`, and keep the declared order.
  - Relationships are detected whether declared directly or as array `items`. Each records the reverse name, resource collection paths and whether it is multi-valued.
  - Report natural-key candidates per WS6.01.
  *Gotcha:* the spec relies on property order. Check that `JsonSlurper`'s map preserves insertion order for the parser in use.
  *Green:* `ManagedSchemaReaderSpec`.
- [ ] **WS6.03 `ObjectTypesGenerator.defaultIdProperty` (S).** Use the first natural-key candidate. Otherwise use a `userName`-like name. Otherwise return null, which forces a prompt (A3).
- [ ] **WS6.04 `ObjectTypesGenerator.generate` (L).**
  - Only selected types are generated. A selected type missing from `managed.json` is an error.
  - A missing `entityKind` is an error naming the type. A missing `idProperty` with no default is an error.
  - `attributes` are the domain properties minus the `idProperty`.
  - `archetypes` default to the capitalised type name.
  - Relationship entries take `relatedType` from the resource collection. They carry exactly `remoteType`, `direction`, `relatedType` and `reverseProperty`, with `sync` omitted when true.
  - With no reverse property, set `sync: false` and add a warning.
  - Apply overrides verbatim. Otherwise use `resolvePair`. An unresolved pair is an error naming both properties.
  - A related type that wasn't selected gives a warning.
  - Finally run `ObjectTypesParser` and `RelationshipConvention.validate` on the output and append their errors. The output must be deterministic.
  *Green:* `ObjectTypesGeneratorSpec`.
- [ ] **WS6.05 `MappingSkeletonGenerator.mapping` (M).** Follow the shape of `config/sync-mapping-user.sample.json`:
  - name it `managed<Type>_system<Connector><Type>`;
  - map `__NAME__` from the `idProperty`, and map every attribute directly;
  - bind library transforms with no `condition`;
  - use a JavaScript correlation query on `__NAME__`;
  - give all 13 situations an explicit policy;
  - set `enableSync: true` and no queued sync.
  *Green:* `MappingSkeletonGeneratorSpec`.
- [ ] **WS6.06 `DiffSummary.summarise` (M).** Compare per type, at item level for attribute lists and per property for relationships. Mark lines `+` for added, `-` for removed and `~` for changed, showing old and new values. A null `current` means everything is added.
  *Green:* `DiffSummarySpec`.
- [ ] **WS6.07 `DiscoveryTool` command line (M). New tests.**
  - Inputs: `--managed <file>`, `--types`, `--answers <answers.json>` (GeneratorOptions as JSON), `--current <provisioner.json>` and `--out <dir>`.
  - Outputs: `objectTypes.json`, the escaped `customConfiguration` string ready to paste into the provisioner, and one `sync-<type>.json` per type.
  - Print the diff. Exit non-zero on errors.
  - Wire it up with Gradle's `application` plugin.
  - Add `DiscoveryToolSpec` using a temporary directory.
- [ ] **WS6.08 Read-only IDM REST source (M). New tests.** Add `--idm-url` and `--idm-user`, with the password from an environment variable, never from an argument. It reads only `config/managed`, with credentials separate from the connector's (§14). Add a WireMock-backed spec.
- [ ] **WS6.09 Mapping lint (M). New tests.** This is the code-review check §8.4 and §15 ask for. Add `--lint <sync.json>`, which fails when a mapping targeting `system/graphentity/*` is missing the `entityRelationships` or `directArchetypes` property mapping, or puts a `condition` on either. Run it in CI against every mapping under `config/`.

### WS7 — Packaging and deployment artefacts

- [ ] **WS7.01 Complete `connectorScripts` (S).** Also copy `graphEntityPayload.js` into `script/lib/`, the drift-check script and the schedule sample. Write a `VERSION` file containing the git SHA. Add a `distZip` task.
- [ ] **WS7.02 Confirm the library deployment model (M).** Verify in IDM 7.5 that the provisioner's `classpath` accepts a directory of Groovy *sources* in packages (P9). If it does not, package the library as a jar and update the task and §8.
- [ ] **WS7.03 Provisioner generation (S).** Generate the escaped `customConfiguration` string with the WS6.07 tool rather than by hand. Document how the password gets encrypted on write.
- [ ] **WS7.04 Confirm versions (S).** Confirm the Groovy version bundled with IDM 7.5 and the exact Scripted REST bundle version. Update `gradle.properties` and the provisioner's `connectorRef` range.
- [ ] **WS7.05 ForgeRock flavour in CI (S).** Add a CI job with `-PicfFlavour=forgerock`, with repository credentials as secrets. Fail the build when the two flavours disagree.
- [ ] **WS7.06 Development IDM container (M).** Build a docker-compose stack with IDM 7.5 and its repository, mounting the build output. Image access needs vendor credentials, so document how to get them.
- [ ] **WS7.07 Smoke check (S).** Deploy, then check that `system/graphentity?_action=test` succeeds and `system/graphentity?_action=schema` lists `user` and `account`.
- [ ] **WS7.08 Install section of the runbook (S).** Write the first section of `docs/runbook.md`, following §8 steps 1–3.

### WS8 — Integration tests against IDM 7.5

- [ ] **WS8.01 Integration project (M).** Create an `integration/` Gradle subproject, with Spock driving IDM's REST API, on the WS7.06 stack. Back it with WireMock seeded from WS0.11, or a test tenant of the real service. It runs on demand and nightly, not on every push.
- [ ] **WS8.02 Managed schema fixture (S).** Add `managed.json` with `account` and the bidirectional `owns`/`ownedBy` pair. Generate the provisioner and mappings with the discovery tool, not by hand.
- [ ] **WS8.03 Appendix A through recon (M).** Recon the user mapping, then the account mapping. Assert the remote documents at each step and the dropped-edge WARN in IDM's log.
- [ ] **WS8.04 Scalar-only update keeps the edge (S).** Change only Sanne's name in IDM. Assert the update script received `entityRelationships`, so the P4 guard did not fire, and that the edge survives. This is the real-world check behind the §8.4 risk.
- [ ] **WS8.05 Relationship removal (S).** Remove the relationship in IDM. Assert two `PUT`s arrive and the final state has no edge (§9.2).
- [ ] **WS8.06 Delete (S).** Delete the account in IDM. Assert a `DELETE` followed by Sanne's edge-free `PUT`.
- [ ] **WS8.07 Target-phase orphan (S).** Seed an entity remotely with no IDM source. Assert the recon REPORTs it and does not delete it.
- [ ] **WS8.08 Drift repair (M).** Remove an edge remotely. Assert a full recon repairs it, and the WS5.08 script repairs it too. Then delete an entity remotely and assert that only a full recon re-creates it.
- [ ] **WS8.09 Library under real Rhino (S).** `require` the library in IDM and call `loadTypeConfig` against the real provisioner. Record how long the module cache actually lives (WS5.04).
- [ ] **WS8.10 Error paths (M).**
  - A 401 on test.
  - A 503 during recon is reported against the right object.
  - A 429 with `Retry-After` under queued sync shows retries.
  - `deleteMissingTolerated=false` end to end.
- [ ] **WS8.11 Volume (L).** Enumerate 50,000 entities with page size 200 inside the recon window, with flat memory. Measure `buildRelationships` cost per object and decide on field expansion (WS5.05).
- [ ] **WS8.12 Concurrency (M).** Run live sync during a recon with `taskThreads` > 1. Once activity quiesces, assert remote state equals IDM state (§10).
- [ ] **WS8.13 Nightly job (S).** Run the suite nightly, archive results, and alert on failure.

### WS9 — Operational readiness and rollout

- [ ] **WS9.01 Complete the runbook (M).** Cover:
  - recon and drift-check cadence (§11);
  - onboarding order (§8);
  - reading the dropped-edge WARN;
  - the expected doubled write (§9.2) and self-correcting intermediate states (§10);
  - never running clique recon and live sync together during onboarding;
  - the A1 erosion watch item.
- [ ] **WS9.02 Schedules (S).** Write schedule configurations for full recon per type and the hourly drift check. Use conservative `taskThreads`.
- [ ] **WS9.03 Monitoring (M).** Write queries or alerts for dropped-edge WARN volume, the `ConnectorIOException` rate, recon REPORT counts per situation and drift-check lag.
- [ ] **WS9.04 REPORT to DELETE checklist (S).** Write the per-type promotion criteria (§8 step 7), with who signs off and how to roll back.
- [ ] **WS9.05 Mutable `idProperty` guard (M). New tests.** Write an `onUpdate` mapping snippet that detects an `idProperty` change and does an explicit delete then create (§15). Add an integration case to WS8.
- [ ] **WS9.06 Security review (M).** Cover service account scope, TLS in every non-localhost environment, credentials held only in the encrypted provisioner, the read-only discovery credentials, and a PII sample audit of real logs.
- [ ] **WS9.07 Non-production dry run (M).** Run against the real service with `REPORT` everywhere. Review the counts with operations.
- [ ] **WS9.08 Sign-off (S).** Get sign-off from operations, security and the design owner, then record the decision.
- [ ] **WS9.09 Handover (S).** Walk support through the runbook and the log signals.

### Cross-cutting (every workstream)

- [ ] **X.01 Pull request checklist.** Cite the task ID. Name the specs turned green. Confirm no test expectation changed without a stated reason. Confirm the §8.4 invariant: every update payload carries the full edge set.
- [ ] **X.02 Keep §2.3 and §6.3 current.** Update the design-to-code map and the test catalogue when classes or specs are added.
- [ ] **X.03 Decision log.** Record any change to P1–P12, or any new decision, in §3 with the date.
- [ ] **X.04 Close open questions.** When an answer arrives, remove the item from §10 and link the evidence.
- [ ] **X.05 Convention parity.** Change a §7 rule only together with `fixtures/convention-cases.json` (WS5.01), so both implementations move together.
- [ ] **X.06 CI stays meaningful.** Until a workstream completes, its specs are expected to fail. Track per-workstream pass counts in CI output rather than marking specs `@Ignore` or `@PendingFeature`, which would hide regressions in finished workstreams.

---

## Appendix — Commands

```bash
# full Groovy suite (ConnId flavour, works without ForgeRock repository access)
./gradlew test

# ForgeRock flavour (release build; needs maven.forgerock.org access)
./gradlew test -PicfFlavour=forgerock

# one spec
./gradlew :connector:test --tests 'au.com.azwork.idm.graphentity.ops.CreateOperationSpec'

# mapping library
cd mapping-lib && npm test

# packaging
./gradlew :connector:connectorScripts
```
