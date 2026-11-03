# Delivery Plan — Ping IDM Scripted REST Connector for the Graph Entity Service

**Source design:** `idm-graph-entity-connector-design.md` (Draft for review, 2026-07-06)
**Plan status:** Proposed, v1 — 2026-11-03
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
| D7 | Discovery tool tests | §12 | `discovery/src/test/groovy/…` | **Complete: 4 specs, 40 test executions** |
| D8 | Provisioner and mapping configuration samples | §6, §11, §12 | `config/` | Written |
| D9 | Build, CI, packaging task (`connectorScripts`) | — | `build.gradle`, `.github/workflows/ci.yml` | Written |
| D10 | Integration test harness against IDM 7.5 in Docker | this plan §6.4 | `integration/` (to be created) | Not started |
| D11 | Operator runbook (deployment, recon cadence, REPORT→DELETE promotion, log interpretation) | §9–§11, §14 | `docs/runbook.md` (to be created) | Not started |
| D12 | Service-contract confirmation record (answers to design §15 open items) | §15 | `docs/service-contract.md` (to be created) | Not started |

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

Each workstream names its acceptance criterion as the specs that must be green. Estimates are indicative engineer-days for one engineer familiar with IDM and Groovy, excluding review.

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
**Estimate:** 3 days.

### WS2 — Model, codec and payload

**Goal:** pure functions from ICF attributes to the wire document and back.
**Implement:** `Edge`, `EntityDocument`, `EdgeCodec`, `EntityPayloadBuilder`, `EntityDecoder`.
**Acceptance:** `EdgeSpec`, `EntityDocumentSpec`, `EdgeCodecSpec`, `EntityPayloadBuilderSpec`, `EntityDecoderSpec`.
**Estimate:** 2 days. Depends on WS1 types only (compiles already).

### WS3 — Transport, error mapping and write operations

**Goal:** create/update/delete/test work end to end through the real HTTP Builder client.
**Implement:** `HttpErrorMapper`, `RestClientEntityApi`, `ScriptSupport`, `EdgeVerifier`, `CreateOperation`, `UpdateOperation`, `DeleteOperation`, `TestOperation`, `CustomizerScript` body.
**Acceptance:** `HttpErrorMapperSpec`, `RestClientEntityApiSpec`, `ScriptSupportSpec`, `EdgeVerifierSpec`, `CreateOperationSpec`, `UpdateOperationSpec`, `DeleteOperationSpec`, `TestAndSchemaOperationSpec`, `CustomizerScriptSpec`, and the create/update/delete/test cases of `OperationScriptsSpec`.
**Estimate:** 4 days. Depends on WS1, WS2.

### WS4 — Search, scoping and paging

**Goal:** get-by-id, timestamp search and scoped enumeration with ICF paging cookies.
**Implement:** `SearchPlanner`, `PageCursor`, `TypeScoper`, `SearchOperation`.
**Acceptance:** `SearchPlannerSpec`, `PageCursorSpec`, `TypeScoperSpec`, `SearchOperationSpec`, search cases of `OperationScriptsSpec`, then `WorkedExampleSpec` (the whole Appendix A scenario) goes green as the WS1–WS4 milestone check.
**Estimate:** 3 days. Depends on WS0 for the final paging protocol (can be built against P6 and adjusted).

### WS5 — Mapping-side payload library

**Goal:** `buildRelationships`/`buildArchetypes` produce the complete edge set from IDM state, resolving remote IDs through the related type's `idProperty`.
**Implement:** all functions in `graphEntityPayload.js`, ES5 only.
**Acceptance:** `npm test` in `mapping-lib/` green (67 tests), plus a manual check in a real IDM that `require('lib/graphEntityPayload')` loads under Rhino (WS8 covers this formally).
**Estimate:** 3 days. Independent of WS1–WS4 (shares the §7 rules, which the Groovy and JS suites pin identically).

### WS6 — Discovery and generation tool

**Goal:** generate `objectTypes` and mapping skeletons from `managed.json`, with the same validation the connector runs.
**Implement:** `ManagedSchemaReader`, `ObjectTypesGenerator`, `MappingSkeletonGenerator`, `DiffSummary`, `DiscoveryTool.main` (file IO and prompts).
**Acceptance:** all four discovery specs green; `ObjectTypesGeneratorSpec` "parses and validates with the connector's own parser" proves generator and connector agree.
**Estimate:** 4 days. Depends on WS1 (reuses parser and convention).

### WS7 — Packaging and deployment artefacts

**Goal:** one command produces what is copied into IDM.
**Tasks:** finish `connectorScripts` task (scripts, library sources, `graphEntityPayload.js` to `script/lib/`, provisioner sample); version stamping; `docs/runbook.md` first draft (install steps, provisioner encryption of the password, refreshing the connector after an `objectTypes` change).
**Acceptance:** fresh IDM 7.5 container + artefact → `TestScript` passes via `system/graphentity?_action=test`, `SchemaScript` exposes `user` and `account`.
**Estimate:** 2 days. Depends on WS3.

### WS8 — Integration tests against IDM 7.5 (Docker)

**Goal:** prove the scripts run inside the real Scripted REST harness and the mappings drive the connector as designed.
**Tasks:** `integration/` with docker-compose (IDM 7.5 + WireMock seeded from WS0 recordings, or the real service in a test tenant); seed `managed/user` and `managed/account` objects with relationships; run the user and account mappings' recon; assert the remote documents (Appendix A sequence); assert the dropped-edge WARN appears in IDM's log; run a target-phase recon and assert TARGET-only reports; incremental drift check script (§11).
**Acceptance:** a scripted, repeatable run; results archived in CI.
**Estimate:** 5 days. Depends on WS5, WS7, WS0.

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
| `discovery/MappingSkeletonGeneratorSpec` | §12, §8.2, §11 | 9 | 9 |
| `discovery/DiffSummarySpec` | §12 | 7 | 7 |
| **Discovery total** | | **40** | **40** |
| `mapping-lib/test/graphEntityPayload.test.js` | §7, §8.2, §6 | — | **67** |
| **Grand total** | | | **624** |

Current result (2026-11-03): connector 7 pass / 505 fail / 5 skipped (the `@Stepwise` scenario stops after its first step); discovery 0 / 40; mapping library 7 pass / 60 fail. Every failure is a `TODO WSn` stub, as intended.

### 6.4 Tests still to write (WS8/WS9)

- Integration: Appendix A inside IDM via recon; dropped-edge WARN visible in IDM logs; target-phase recon REPORTs an orphan; incremental drift script corrects a remote-side edge removal; `require('lib/graphEntityPayload')` loads under Rhino; provisioner refresh after an `objectTypes` change exposes a new class.
- Non-functional: enumeration of 50k entities with page size 200 completes within the recon window; memory stays flat across pages (results are streamed, not buffered — `SearchOperationSpec` already asserts streaming behaviour at unit level).
- Negative operational: `deleteMissingTolerated=false` end to end; 429 with `Retry-After` under queued sync shows retries in IDM's queue.

---

## 7. Environments, build and CI

- **Build:** Gradle 8.14 wrapper, Groovy 3.0.21 (IDM 7.5's Groovy line), Spock 2.3, WireMock 3.9 (standalone, shaded), HTTP Builder 0.7.1 (the `RESTClient` the harness binds). JDK 17+ (tests were run on 21).
- **ICF flavour:** `./gradlew test` uses ConnId from Maven Central; `./gradlew test -PicfFlavour=forgerock` uses `org.forgerock.openicf.framework:connector-framework:1.5.20.21` from `maven.forgerock.org`. The release artefact is built with the ForgeRock flavour.
- **Mapping library:** `cd mapping-lib && npm test` (Node 20+, no dependencies).
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

The project is done when M5 is reached: all 624 unit-level tests green under both ICF flavours, WS8 integration run green and archived, runbook signed off, and a dry-run recon reviewed with `REPORT` policies against the real service.

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
