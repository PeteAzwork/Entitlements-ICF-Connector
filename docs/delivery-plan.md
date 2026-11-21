# Delivery Plan — Ping IDM Scripted REST Connector for the Graph Entity Service

**Source design:** `idm-graph-entity-connector-design.md` (Draft for review, 2026-07-06, amended 2026-11-21)
**Service contract:** `docs/service-contract.md` (the real API, as far as it is known)
**Plan status:** Proposed, **v2.0 — 2026-11-21.** Rewritten after the service's OpenAPI definition arrived. Relationship writes are confirmed as full-state (P15), and drift detection is scheduled last (P16).
**Target platform:** Self-managed Ping IDM 7.5, Groovy Connector Toolkit 1.5.20.x (Scripted REST)
**Delivery approach:** Test-first. The repository holds the unit test suite and thin stubs; implementation turns specs green workstream by workstream.

---

## 1. How to read this plan

The design document says *what* the connector is. This plan says *in what order* it gets built, *how each piece is proven*, and *what is still unknown*.

- §2 what is delivered, and where it lives;
- §3 the decisions this plan makes, P1–P16 (review these first);
- §4 the workstreams and the order they run in;
- §5 milestones and the critical path;
- §6 the test strategy and the catalogue of tests already written;
- §7 environments, build and CI;
- §8 deployment and rollout;
- §9 risks, §10 open questions, §11 definition of done;
- §12 the task list for every workstream, the working checklist.

### What changed in v2

- **The real API is known.** The service's OpenAPI definition (IDP Entitlements API v1) arrived on 2026-11-21. It differs from the design in document shapes, relationship representation, base-type numbering and search. `docs/service-contract.md` sections 5 and 6 record the differences. Revising the design to match (WS0.14) now comes before most implementation.
- **Relationship writes are full-state (P15).** An entity's `relationships` collection replaces its relationships, and the service removes any that are missing. This confirms the design's core model and makes the update guard (P4) essential.
- **Drift detection is last (P16).** Everything built specifically to detect and repair changes made outside IDM moves into a new final workstream, WS10. The core connector, mappings, integration tests and rollout (WS1–WS9) proceed without it.
- **Numbering is unchanged.** Workstreams WS1–WS9 keep their numbers, because the stubs' `TODO WSn` markers refer to them. WS10 is new.

---

## 2. Scope and deliverables

### 2.1 Deliverables

| # | Deliverable | Design ref | Location | State today |
|---|---|---|---|---|
| D1 | Connector library (Groovy classes the scripts delegate to) | §4–§9, §13 | `connector/src/main/groovy/au/com/azwork/idm/graphentity/` | API fixed, bodies are stubs. Several classes encode the old API model and change in WS0.14 |
| D2 | Seven ICF operation scripts | §4 | `connector/src/main/scripts/*.groovy` | Written (thin delegation) |
| D3 | Connector tests: unit, script harness, contract, scenario | §6 of this plan | `connector/src/test/groovy/…` | **29 specs, 537 executions**, red by design |
| D4 | Mapping-side payload library (ES5 JavaScript for IDM's Rhino) | §8.2 | `mapping-lib/src/lib/graphEntityPayload.js` | API fixed, bodies are stubs |
| D5 | Mapping library tests | §8.2 | `mapping-lib/test/` | **67 tests**, red by design |
| D6 | Discovery and generation tool | §12 | `discovery/src/main/groovy/…` | API fixed, bodies are stubs |
| D7 | Discovery tool tests | §12 | `discovery/src/test/groovy/…` | **4 specs, 41 executions**, red by design |
| D8 | Provisioner and mapping configuration samples | §6, §12 | `config/` | Written |
| D9 | Build, CI, packaging task | — | `build.gradle`, `.github/workflows/ci.yml` | Written |
| D10 | Integration tests against IDM 7.5 in Docker | this plan §6 | `integration/` | Not started (WS8) |
| D11 | Operator runbook | §9, §10, §14 | `docs/runbook.md` | Not started (WS7, WS9) |
| D12 | Service contract | §15 | `docs/service-contract.md` | Register written; API definition recorded; contradictions C1–C7 and decisions D1–D9 listed |
| D13 | Contract probe | §15 | `contract-probe/` | **81 tests passing.** Read checks work against the real API. Write checks need realigning (WS0.15) |
| D14 | Drift detection: design, implementation, tests and operations | §11 | design §11, `mapping-lib`, `integration/`, runbook | Not started. **Scheduled last (WS10)** |

### 2.2 Scope boundaries

- **Out of scope** (unchanged from the design): inbound provisioning, password synchronisation, LiveSync and a SyncScript, remote-originated relationship types, and the read-merge-write mode of design §15.
- **In scope but last** (P16): drift detection, as defined in WS10.
- **Not used by the connector:** the service's `/entitlements`, `/policy-information`, `/admin/manifests` and delegation endpoints.

### 2.3 Design → code map

The map below is the current one. WS0.14 revises the classes marked †, because they encode the design's original model of the API.

| Design section | Library class(es) | Spec(s) |
|---|---|---|
| §2.1 document shape | `model.EntityDocument` † | `EntityDocumentSpec` |
| §2.2 edge rules | `model.Edge` † | `EdgeSpec` |
| §3 A3/A4 identity and kind | `config.EntityKind` †, `payload.EntityPayloadBuilder` † | `EntityKindSpec`, `EntityPayloadBuilderSpec` |
| §5.2/§6 per-type dynamic schema | `config.ObjectTypesParser`, `config.ConnectorConfig`, `schema.SchemaFactory` | `ObjectTypesParserSpec`, `ConnectorConfigSpec`, `SchemaFactorySpec` |
| §7 convention (normative) | `convention.RelationshipConvention` | `RelationshipConventionSpec`, and the JavaScript `resolvePair`/`resolveRelationship` tests |
| §8.2 mapping-side assembly | `graphEntityPayload.js` | `graphEntityPayload.test.js` |
| §8.3 ICF encoding | `codec.EdgeCodec` † | `EdgeCodecSpec` |
| §8.4/§9.2 update | `ops.UpdateOperation` | `UpdateOperationSpec` |
| §9.1 create and verification | `ops.CreateOperation`, `payload.EdgeVerifier` † | `CreateOperationSpec`, `EdgeVerifierSpec` |
| §9.3 delete | `ops.DeleteOperation` | `DeleteOperationSpec` |
| §9.4 search, get, scoping, paging | `search.SearchPlanner` †, `search.TypeScoper` †, `search.PageCursor` †, `ops.SearchOperation`, `payload.EntityDecoder` † | `SearchPlannerSpec`, `TypeScoperSpec`, `PageCursorSpec`, `SearchOperationSpec`, `EntityDecoderSpec` |
| §9.5 test and schema | `ops.TestOperation`, `ops.SchemaOperation` | `TestAndSchemaOperationSpec` |
| §13 exception mapping | `http.HttpErrorMapper` | `HttpErrorMapperSpec` |
| §2/§14 transport and auth | `http.RestClientEntityApi`, `CustomizerScript` | `RestClientEntityApiSpec`, `CustomizerScriptSpec` |
| §4 scripts as the harness runs them | `ops.ScriptSupport`, scripts | `OperationScriptsSpec`, `ScriptSupportSpec` |
| §12 discovery | `discovery.*` | `ManagedSchemaReaderSpec`, `ObjectTypesGeneratorSpec`, `MappingSkeletonGeneratorSpec`, `DiffSummarySpec` |
| Appendix A | all operations against `FakeGraphService` † | `WorkedExampleSpec` |

---

## 3. Decisions (review these first)

Each decision is pinned by a test, so changing one is deliberate. "Status" says whether the real API has confirmed, changed or superseded it.

| # | Decision | Status | Where pinned |
|---|---|---|---|
| P1 | **Connector-specific configuration travels in `customConfiguration`**, surfaced to scripts as `configuration.propertyBag`. `objectTypes` is a JSON string inside it. The Scripted REST connector has a fixed configuration class, so arbitrary top-level properties don't reach scripts. | Holds | `config/provisioner…json`, `ScriptSupportSpec` |
| P2 | **Relationship entries carry `relatedType` and `reverseProperty` hints**, emitted by the discovery tool, so the connector can validate §7 consistency without `managed.json`. | Holds | `ObjectTypesParserSpec`, `RelationshipConventionSpec`, `ObjectTypesGeneratorSpec` |
| P3 | **The passive form in the §7 convention is a trailing `By`** (`ownedBy`). Exactly one side must be passive, or the pair needs an override. | Holds | `RelationshipConventionSpec`, JavaScript equivalents |
| P4 | **`UpdateOperation` refuses an update that omits `entityRelationships` or `directArchetypes`.** A present-but-empty attribute is still a valid "none". | **Essential, given P15**: the service deletes any relationship missing from an update | `UpdateOperationSpec` |
| P5 | **Type scoping on enumeration checks the base type as well as archetypes.** | Revisit in WS0.14 (D6): search can scope by `objectClass` directly | `TypeScoperSpec` |
| P6 | ~~Paging uses `offset`/`pageSize` with `items` and `totalCount` or `nextCursor`.~~ | **Superseded.** The real protocol is `pageSize` plus an opaque `pagedResultsCookie`, with `results`, `totalResults` and `hasMore` in the response. All paging knowledge stays in `PageCursor` | `PageCursorSpec` (revise in WS0.14) |
| P7 | **Unsupported ICF filters raise `UnsupportedOperationException`** rather than silently enumerating. | Holds, but more filters become supported (D8) | `SearchPlannerSpec` |
| P8 | **The build compiles against ConnId's `connector-framework` by default** (`icfFlavour=connid`), switchable to ForgeRock's (`icfFlavour=forgerock`). The release build uses ForgeRock's. | Holds | `gradle.properties`, `build.gradle` |
| P9 | **Library classes deploy as Groovy sources under the provisioner's `classpath`.** | Holds; verify in WS7.02 | `connectorScripts` task |
| P10 | **The mapping library reads `config/managed` only as a fallback** for relationship properties not listed in `objectTypes`. | Holds | `graphEntityPayload.test.js` |
| P11 | **A failed verification GET after a successful create does not fail the create**; it is logged at WARN. | Holds. Matters now: GET by ID returned 500 in the first real run (C1) | `CreateOperationSpec` |
| P12 | **Package namespace `au.com.azwork.idm.graphentity`.** Renaming is a find-and-replace before WS1 starts. | Holds | everywhere |
| P13 | **Authentication is one shared API key, sent raw in the `X-API-KEY` header.** Stored in the encrypted `password` property; `defaultAuthMethod` is `NONE`; no username. *2026-11-21.* | Confirmed by the owner and the definition | `ConnectorConfigSpec`, `CustomizerScriptSpec`, `RestClientEntityApiSpec`, `HttpErrorMapperSpec`, `ScriptSupportSpec`, `ContractProbeSpec` |
| P14 | **Search is `POST /admin/entities/search` with a JSON body; `{}` is the unfiltered search.** *2026-11-21.* | Confirmed by the owner and the definition | `RestClientEntityApiSpec`, `OperationScriptsSpec`, `ContractProbeSpec` |
| P15 | **Relationship writes are full-state, through the entity's `relationships` collection on create and update.** The service removes every relationship missing from the collection, so every write carries the complete set. The single-relationship endpoint (`POST /admin/entities/{id}/relationships`) also works, but the connector does not use it: one full-state write per entity keeps writes idempotent and keeps the design's §8 model. *Decided 2026-11-21 (D1).* | Confirmed by the owner. Still open: whether "missing" includes relationships where the entity is the *target* (Q07.8) | `UpdateOperationSpec`, `WorkedExampleSpec` |
| P16 | **Drift detection is built last, in WS10.** Drift detection means everything built specifically to detect and repair changes made outside IDM: the incremental drift check (design §11), comparing relationships during reconciliation, promoting orphans from `REPORT` to `DELETE`, and their tests, schedules and monitoring. Until WS10, reconciliation runs as IDM does it natively. It is used for the initial load, every target-phase situation stays on `REPORT`, and relationship drift goes undetected. *Decided 2026-11-21 (D3, D4).* | Decided by the owner | `MappingSkeletonGeneratorSpec` (REPORT policies) |

---

## 4. Workstreams and their order

Each workstream's acceptance criterion is the set of specs that must be green. Estimates are engineer-days for one engineer who knows IDM and Groovy, excluding review. §12 breaks each one into tasks.

### 4.1 Order at a glance

| Order | Workstream | Depends on | Can start |
|---|---|---|---|
| 1 | **WS0 Service contract and design revision** | Service team | Now |
| 2 | WS1 Configuration, convention and schema | WS0.14 for D5 and D7 only | Now, except `EntityKind` and attribute cardinality |
| 2 | WS6 Discovery tool | WS1 | After WS1's parser and convention |
| 3 | WS2 Model, codec and payload | **WS0.14** (D2, document shapes) | After WS0.14 |
| 3 | WS5 Mapping-side payload library | WS0.14 for the edge format (D2) | Convention parts now; edge output after WS0.14 |
| 4 | WS3 Transport, errors and write operations | WS1, WS2; C1 for verification | After WS2 |
| 5 | WS4 Search, scoping and paging | WS3, WS0.14 (D6, D8) | After WS3 |
| 6 | WS7 Packaging and deployment | WS3 | After WS3 |
| 7 | WS8 Integration in IDM 7.5 | WS4, WS5, WS7 | After WS7 |
| 8 | WS9 Operational readiness and rollout | WS8 | After WS8 |
| **9 (last)** | **WS10 Drift detection** | WS9; service answers on D3 and D4 | After go-live readiness (M5) |

### WS0 — Service contract and design revision (first)

**Goal:** know the real API, and revise the design and the code's model of it before building on it.
**Part A, the contract:** largely done. The register is written, the API definition recorded, authentication and search confirmed (P13, P14), relationship writes confirmed (P15), and the probe built (WS0.13, WS0.16).
**Part B, the design revision (WS0.14):** decide D2 and D5–D9, raise C1–C7 with the service team, amend design §2, §6–§9, and revise the stubs, fakes and specs that encode the old model.
**Acceptance:** the design matches the real API. The specs that encode the API's shape describe the real one and still fail only on stubs. C1 (GET by ID returns 500) is understood.
**Estimate:** 5 days plus service-team elapsed time.

### WS1 — Configuration, convention and schema

**Goal:** the connector loads, validates and exposes its schema.
**Implement:** `EntityKind`, `Direction`, `ObjectTypesParser`, `ConnectorConfig`, `ObjectTypeConfig.syncedRelationships`, `RelationshipConvention`, `ObjectClassResolver`, `SchemaFactory`, `SchemaOperation`.
**Acceptance:** `EntityKindSpec`, `DirectionSpec`, `ObjectTypesParserSpec`, `ConnectorConfigSpec`, `RelationshipConventionSpec`, `ObjectClassResolverSpec`, `SchemaFactorySpec`, and the schema cases of `TestAndSchemaOperationSpec`.
**Estimate:** 3.5 days.

### WS2 — Model, codec and payload

**Goal:** pure functions from ICF attributes to the service's write model, and from its read and search models back.
**Implement:** `Edge`, `EntityDocument`, `EdgeCodec`, `EntityPayloadBuilder`, `EntityDecoder`, as revised by WS0.14.
**Acceptance:** `EdgeSpec`, `EntityDocumentSpec`, `EdgeCodecSpec`, `EntityPayloadBuilderSpec`, `EntityDecoderSpec`.
**Estimate:** 3 days (up from 2: three document models instead of one).

### WS3 — Transport, error mapping and write operations

**Goal:** create, update, delete and test work end to end through the real HTTP client.
**Implement:** `HttpErrorMapper`, `RestClientEntityApi`, `ScriptSupport`, `EdgeVerifier`, `CreateOperation`, `UpdateOperation`, `DeleteOperation`, `TestOperation`, and the `CustomizerScript` body.
**Acceptance:** `HttpErrorMapperSpec`, `RestClientEntityApiSpec`, `ScriptSupportSpec`, `EdgeVerifierSpec`, `CreateOperationSpec`, `UpdateOperationSpec`, `DeleteOperationSpec`, `TestAndSchemaOperationSpec`, `CustomizerScriptSpec`, and the create, update, delete and test cases of `OperationScriptsSpec`.
**Estimate:** 5 days.

### WS4 — Search, scoping and paging

**Goal:** get by ID, translation of ICF filters into the service's filter tree, and scoped enumeration with ICF paging cookies.
**Implement:** `SearchPlanner`, `PageCursor`, `TypeScoper`, `SearchOperation`, as revised by WS0.14. Timestamp ("since") searches are **not** part of WS4; they belong to WS10 if the service ever offers one.
**Acceptance:** `SearchPlannerSpec`, `PageCursorSpec`, `TypeScoperSpec`, `SearchOperationSpec`, the search cases of `OperationScriptsSpec`, then `WorkedExampleSpec` (Appendix A end to end) as the milestone check.
**Estimate:** 3.5 days (filter-tree translation replaces the timestamp search).

### WS5 — Mapping-side payload library

**Goal:** `buildRelationships` and `buildArchetypes` produce the complete relationship set and archetypes from IDM state.
**Implement:** every function in `graphEntityPayload.js`, ES5 only, emitting edges in the format WS0.14 settles (D2).
**Acceptance:** `npm test` in `mapping-lib/` green, and the Rhino smoke test (WS5.07).
**Estimate:** 4 days (the drift-check script moved to WS10).

### WS6 — Discovery and generation tool

**Goal:** generate `objectTypes` and mapping skeletons from `managed.json`, with the same validation the connector runs.
**Implement:** `ManagedSchemaReader`, `ObjectTypesGenerator`, `MappingSkeletonGenerator`, `DiffSummary`, `DiscoveryTool`.
**Acceptance:** all four discovery specs green, plus the new CLI, REST and lint specs.
**Estimate:** 6 days.

### WS7 — Packaging and deployment artefacts

**Goal:** one command produces what is copied into IDM.
**Acceptance:** a fresh IDM 7.5 container plus the artefact passes `system/graphentity?_action=test`, and the schema exposes `user` and `account`.
**Estimate:** 2.5 days.

### WS8 — Integration tests against IDM 7.5

**Goal:** prove the scripts run inside the real Scripted REST harness and the mappings drive the connector as designed: create, update, relationship changes, delete, initial-load reconciliation, and orphans reported but never deleted.
**Acceptance:** a scripted, repeatable run, archived in CI. Drift-repair scenarios are WS10's.
**Estimate:** 5.5 days.

### WS9 — Operational readiness and rollout

**Goal:** the core connector is safe to run against production data.
**Acceptance:** the runbook is reviewed by operations, a dry-run reconciliation against the real service with `REPORT` everywhere is reviewed, and sign-off is recorded.
**Estimate:** 3 days plus review.

### WS10 — Drift detection (last)

**Goal:** detect and repair changes made to the service outside IDM.
**Scope (P16):** a design for drift detection against the real API; comparing relationships during reconciliation; an incremental check if the service can support one; promoting orphans from `REPORT` to `DELETE`; the integration tests, schedules, monitoring and runbook sections for all of these.
**Prerequisites:** the service team's answers on a last-modified signal (D3) and on reading relationships for many entities (D4), and a working GET by ID (C1).
**Acceptance:** an integration run in which a relationship removed directly in the service is restored by the chosen mechanism, and an orphan is deleted only after the promotion checklist is met.
**Estimate:** 6–10 days, depending on what the service offers.

---

## 5. Milestones and critical path

| Milestone | Contents | Exit check |
|---|---|---|
| **M0 — Suite accepted** (done) | Plan, test suite and stubs reviewed | The build compiles; suites run red; decisions logged |
| **M1 — Contract and design settled** | WS0, including WS0.14 and WS0.15 | Design amended; specs describe the real API; C1 explained; a probe write run gives trustworthy answers |
| **M2 — Core library** | WS1, WS2, WS3 | All non-search connector specs green |
| **M3 — Full connector** | WS4 | `WorkedExampleSpec` and every connector spec green |
| **M4 — Mapping and discovery** | WS5, WS6 | `npm test` green; discovery specs green; generated configuration validates against the connector's parser |
| **M5 — Ready for go-live** | WS7, WS8, WS9 | Integration run green; runbook signed off; dry run reviewed. Orphans are reported, not deleted; relationship drift is not yet detected |
| **M6 — Drift detection** (last) | WS10 | The WS10 acceptance run passes; orphan promotion approved per type |

**Critical path:** WS0.14 → WS2 → WS3 → WS4 → WS7 → WS8 → WS9 → WS10. WS0.14 is the new front of the path: WS2 and much of WS4 cannot start until the design matches the API.
**Parallel work:** WS1 (except `EntityKind`), WS6, and the convention parts of WS5 can start now.
**Going live at M5:** the core connector does not need drift detection to be correct. Every write restates IDM's full state, so any IDM-side change repairs the entity it touches. What M5 lacks is detecting changes made *directly in the service*. Whether that is acceptable for go-live is a decision for the design owner and operations (§10).

---

## 6. Test strategy

### 6.1 Levels

| Level | What it proves | Tooling | Where |
|---|---|---|---|
| L1 Library unit | Pure functions: configuration, convention, model, codec, payload, error mapping, planning, scoping, paging | Spock data tables | `connector/src/test/groovy/…` |
| L2 Operation unit | Each ICF operation against the service's semantics, including every §13 status and the no-PII logging rule | Spock + `FakeGraphService` + `CapturingLog` | `…/ops` |
| L3 Transport contract | The real HTTP client against WireMock: paths, methods, headers, the API key, JSON bodies, encoding, non-2xx handling | WireMock 3 | `RestClientEntityApiSpec` |
| L4 Script harness | The seven scripts evaluated as the harness does, against WireMock | `ScriptHarness` | `OperationScriptsSpec`, `CustomizerScriptSpec` |
| L5 Scenario | Appendix A end to end | `@Stepwise` + `FakeGraphService` | `WorkedExampleSpec` |
| L6 Mapping library | The JavaScript library under Rhino-like isolation | Node test runner | `mapping-lib/test` |
| L7 Discovery | Generator correctness and agreement with the connector's validation | Spock | `discovery/src/test` |
| L7b Contract probe | The probe reports the service's semantics faithfully, never writes in read-only mode, cleans up, and never persists credentials or foreign data | Spock + `FakeGraphHttpServer` + WireMock replay | `contract-probe/src/test` |
| L8 Integration (WS8) | The real IDM harness, Rhino and reconciliation engine | Docker; WireMock recordings or a test tenant | `integration/` |
| L9 Operational dry run (WS9) | The real service, `REPORT`-only reconciliation | Non-production IDM | runbook |
| L10 Drift (WS10) | Remote-side changes are detected and repaired | Integration suite | `integration/` |

### 6.2 Conventions enforced by the suite

- **Red by construction.** Stubs throw `UnsupportedOperationException("TODO WSn: …")`. Tests that expect that exception for legitimate reasons also assert its message, so a stub cannot pass them.
- **Every test cites its design section.**
- **No PII above DEBUG**, and never the API key, in any log line or error message.
- **Service semantics live in one place.** `FakeGraphService` models the service. When the contract changes, change the fake and the affected specs together. WS0.14 brings the fake in line with the real API's shapes.
- **Drift-only cases are labelled.** Spec cases that exist only for drift detection (the timestamp searches in `SearchPlannerSpec` and `SearchOperationSpec`) move to WS10 in WS0.14, so WS4 can go green without them.

### 6.3 Catalogue of tests already written

| Spec | Design refs | Feature methods | Executions (incl. data-table rows) |
|---|---|---|---|
| `config/EntityKindSpec` | §3 A4, §6 | 4 | 17 |
| `config/DirectionSpec` | §7 | 3 | 10 |
| `config/ObjectTypesParserSpec` | §6 | 18 | 32 |
| `config/ConnectorConfigSpec` | §6, §15, P13 | 17 | 39 |
| `convention/RelationshipConventionSpec` | §7 | 24 | 54 |
| `model/EdgeSpec` | §2.2 | 9 | 17 |
| `model/EntityDocumentSpec` | §2.1 | 8 | 8 |
| `codec/EdgeCodecSpec` | §8.3 | 10 | 22 |
| `payload/EntityPayloadBuilderSpec` | §8.3, §8.4, A3 | 21 | 23 |
| `payload/EntityDecoderSpec` | §9.4 | 10 | 10 |
| `payload/EdgeVerifierSpec` | §9.1 | 8 | 8 |
| `http/HttpErrorMapperSpec` | §13, P13 | 20 | 108 |
| `http/HttpResponseSpec` | — | 2 | 2 |
| `http/RestClientEntityApiSpec` | §2, §14, P14 | 14 | 26 |
| `schema/SchemaFactorySpec` | §5.2, §6, §9.5 | 10 | 13 |
| `search/SearchPlannerSpec` | §9.4 (timestamp cases → WS10) | 11 | 19 |
| `search/PageCursorSpec` | §9.4, §15 | 12 | 14 |
| `search/TypeScoperSpec` | §9.4 | 4 | 11 |
| `ops/ObjectClassResolverSpec` | §5.2 | 4 | 4 |
| `ops/CreateOperationSpec` | §9.1, §13 | 18 | 18 |
| `ops/UpdateOperationSpec` | §8.4, §9.2 | 14 | 14 |
| `ops/DeleteOperationSpec` | §9.3 | 8 | 8 |
| `ops/SearchOperationSpec` | §9.4 | 19 | 19 |
| `ops/TestAndSchemaOperationSpec` | §9.5 | 9 | 9 |
| `ops/ScriptSupportSpec` | §4, P13 | 8 | 8 |
| `scripts/OperationScriptsSpec` | §4 | 13 | 13 |
| `scripts/CustomizerScriptSpec` | §4, §14, P13 | 5 | 5 |
| `scenario/WorkedExampleSpec` | Appendix A | 6 | 6 |
| **Connector total** | | **309** | **537** |
| `discovery/ManagedSchemaReaderSpec` | §12 | 8 | 8 |
| `discovery/ObjectTypesGeneratorSpec` | §12, §7 | 16 | 16 |
| `discovery/MappingSkeletonGeneratorSpec` | §12, §8.2 | 10 | 10 |
| `discovery/DiffSummarySpec` | §12 | 7 | 7 |
| **Discovery total** | | **41** | **41** |
| `mapping-lib/test/graphEntityPayload.test.js` | §7, §8.2, §6 | — | **67** |
| `contract-probe/ContractProbeSpec` | WS0, §2 | 22 | 22 |
| `contract-probe/ProbeOutputSpec` | WS0.11, §13, §14 | 10 | 10 |
| `contract-probe/ScrubberSpec` | §13 | 13 | 13 |
| `contract-probe/RealShapedServiceSpec` | WS0.16 | 8 | 8 |
| `contract-probe/ProbeMainSpec` | WS0 | 11 | 28 |
| **Contract probe total** | | **64** | **81** |
| **Grand total** | | | **726** |

Current result (2026-11-21): connector 9 pass / 523 fail / 5 skipped (the `@Stepwise` scenario stops after its first step); discovery 0 / 41; mapping library 3 pass / 64 fail. Every failure is a `TODO WSn` stub, with no failures caused by the test harness. The contract probe's 81 tests all pass, because the probe is finished code rather than a stub. The passing connector tests are structural checks: the scripts exist, there is no SyncScript, the customizer holds no credentials and its release closure is safe, the builder takes no API dependency, response helpers and edge equality. The passing mapping-library tests check the library's exports and ES5 syntax.

### 6.4 Tests still to write

- **WS8 (core integration):** Appendix A inside IDM through reconciliation; relationship add, change and removal; delete; an orphan reported and not deleted; the library loading under Rhino; a provisioner refresh exposing a new class; 50,000-entity enumeration within the reconciliation window with flat memory; error paths (401, 5xx, 429 under queued sync).
- **WS10 (drift):** a relationship removed directly in the service is restored; an entity deleted directly in the service is re-created by full reconciliation; orphan deletion after promotion; the incremental check, if built.

---

## 7. Environments, build and CI

- **Build:** Gradle 8.14 wrapper, Groovy 3.0.21, Spock 2.3, WireMock 3.9, HTTP Builder 0.7.1. **Run Gradle on JDK 17 or 21**; Gradle 8.14 cannot run on Java 25.
- **ICF flavour:** `./gradlew test` uses ConnId from Maven Central. `./gradlew test -PicfFlavour=forgerock` uses ForgeRock's `connector-framework` 1.5.20.21 from `maven.forgerock.org`. The release artefact uses the ForgeRock flavour.
- **Mapping library:** `cd mapping-lib && npm test` (Node 20+, no dependencies).
- **Contract probe:** `./gradlew :contract-probe:run --args="--base-url URL --api-key KEY"`. Everything for the probe goes inside `--args="…"`. Read-only by default; `--allow-writes` only against a test instance, and not until WS0.15. Usage: `docs/service-contract.md` §1.
- **Shared fixtures:** `FakeGraphService` and `FakeGraphHttpServer` in `connector/src/testFixtures`.
- **CI:** `.github/workflows/ci.yml` runs the Gradle suites (ConnId flavour) and the Node suite on every push and pull request. Add a ForgeRock-flavour job when the runner can reach its repository.
- **Packaging:** `./gradlew :connector:connectorScripts`.

---

## 8. Deployment and rollout

### 8.1 Core rollout (M5)

1. Copy `scripts/` to `&{idm.instance.dir}/connectors/graphentity/scripts`, `lib/` to `…/graphentity/lib`, and `graphEntityPayload.js` to `&{idm.instance.dir}/script/lib/`.
2. Install `provisioner.openicf-graphentity.json` with the API key in `password` and `defaultAuthMethod` set to `NONE` (P13). Generate `objectTypes` with the discovery tool and review it.
3. Check `system/graphentity?_action=test`, then that `system/graphentity/user` and `/account` appear in the schema.
4. Install the generated mappings with **every target-phase situation on `REPORT`** (P16).
5. Initial load: resource types first, then subject types. Run one full reconciliation per mapping, and review the dropped-relationship WARN counts.
6. Schedule full reconciliation per type, for initial load and periodic restatement. Implicit sync handles day-to-day changes.

### 8.2 Added by WS10 (M6)

7. The drift mechanism chosen in WS10, with its schedule.
8. Promotion of orphans from `REPORT` to `DELETE`, per type, after the promotion checklist is met.

---

## 9. Risk register

| Risk | Impact | Likelihood | Mitigation |
|---|---|---|---|
| **GET by ID fails (C1)** | Create verification, reconciliation reads by ID and WS10 drift detection don't work | High until explained | Raise with the service team first (WS0.14). Verification already degrades to a WARN (P11) |
| **Design built on an assumed API** | Stubs and specs encode the wrong shapes; work done before WS0.14 may be redone | Certain, if WS0.14 is skipped | WS0.14 before WS2 and WS4; WS1 and WS6 limited to API-independent parts |
| **Inbound relationships and full-state writes (Q07.8)** | If writing entity A removes edges where A is the *target*, every write must list them, or it deletes other entities' edges | Medium | Confirm Q07.8 in WS0.14. The design's A2 already gathers both directions from IDM |
| **Reconciliation can't see relationships** | Search results carry none, so every reconciliation re-sends unchanged relationships (extra writes), and relationship drift is invisible until WS10 | Certain | Accept the extra writes, which are idempotent, until WS10. Measure their cost in WS8.11 |
| **Base-type numbering (C6)** | Entities created as the wrong base type | Medium | Confirm D5 before WS1.02 |
| **Drift undetected until M6** | Changes made directly in the service persist until an IDM change touches the entity | Certain before WS10 | Decide whether go-live waits for M6 (§10); full reconciliation restates IDM's view of every entity it touches |
| A1 erosion (another writer) | The connector deletes relationships another writer created | Low now; the shared key raises it | Runbook watch item; confirm T1 before go-live |
| Mutable `idProperty` | Orphans on rename | Medium | Natural keys from discovery; orphans reported, not deleted, until WS10 |
| Partial-attribute updates | Mass relationship deletion | Low with P4 | P4 guard, generated mappings, mapping lint (WS6.09) |
| One shared API key (P13) | A leaked key gives full access for every consumer | Medium | TLS everywhere; key only in encrypted configuration; redaction by name and value (WS3.04); ask for per-client keys and a rotation process |
| ForgeRock Maven access from CI | The release build cannot run in CI | Medium | Flavour switch (P8); credentials as secrets |
| Rhino incompatibility | Mapping transforms fail at runtime | Low | ES5 lint; Rhino smoke test (WS5.07); real IDM in WS8 |
| Reconciliation and live sync overlapping during onboarding | Transient wrong intermediate states (§10) | Medium | Runbook: don't run them together during onboarding |

---

## 10. Open questions

1. **Service team:** contradictions C1–C7 in `docs/service-contract.md` §5.5, especially GET by ID (C1) and base-type numbering (C6). Plus Q07.8 (does a write remove edges where the entity is the target?), the filter operation names, the maximum page size, ID constraints and the rate-limit policy.
2. **Design owner:** D2 and D5–D9 (WS0.14); P12, the package name; and **whether go-live waits for drift detection (M6) or happens at M5**.
3. **Platform team:** the Groovy version in IDM 7.5, ForgeRock repository access for CI, and the IDM directory layout.
4. **Operations:** reconciliation cadence, and (for WS10) the criteria for promoting orphans to `DELETE`.
5. **Security:** key rotation (T10), whether test and production share the key (T11), and the TLS target (§14).

---

## 11. Definition of done

A **workstream** is done when its named specs are green, no test expectation changed except for a confirmed contract fact or an accepted decision, code review has checked the full-state write invariant (P4, P15), and §2.3 is still accurate.

The **core connector** is done at M5: all core unit-level tests green under both ICF flavours, the WS8 integration run green and archived, the runbook signed off, and a dry-run reconciliation reviewed with `REPORT` policies against the real service.

The **project** is done at M6: WS10's acceptance run green, and orphan promotion approved per type.

---

## 12. Workstream task lists

### 12.1 Conventions

- **IDs** are `WSn.mm`. Cite the ID in commit messages and pull request titles.
- **Size:** **S** is up to half a day, **M** about a day, **L** two days or more, including tests.
- **Green** names the specs that must pass for the task to be done.
- **Gotcha** records something known to be tricky. Read these before starting.
- A task that changes a test expectation must say why in the pull request: a confirmed contract fact or an accepted decision.
- Tick a box only when its **Green** criterion passes on CI.

### 12.2 Summary

| Workstream | Order | Tasks open | Estimate (days) |
|---|---|---|---|
| WS0 Service contract and design revision | 1 | 12 | 5 + elapsed |
| WS1 Configuration, convention, schema | 2 | 17 | 3.5 |
| WS6 Discovery tool | 2 | 9 | 6 |
| WS2 Model, codec, payload | 3 | 9 | 3 |
| WS5 Mapping library | 3 | 8 | 4 |
| WS3 Transport, errors, write operations | 4 | 15 | 5 |
| WS4 Search, scoping, paging | 5 | 9 | 3.5 |
| WS7 Packaging and deployment | 6 | 8 | 2.5 |
| WS8 Integration in IDM 7.5 | 7 | 12 | 5.5 |
| WS9 Operational readiness and rollout | 8 | 8 | 3 + review |
| **WS10 Drift detection** | **9 (last)** | **9** | **6–10** |
| Cross-cutting | — | 6 | ongoing |

### 12.3 Already done

These were found and fixed while preparing this section. They are recorded so nobody repeats the diagnosis.

- [x] **H.01 Restore HTTP Builder's runtime dependencies.** `json-lib` (with its `jdk15` classifier), `xml-resolver` and `groovy-xml` had been excluded, so every WireMock-based spec failed with `NoClassDefFoundError` before reaching the code under test.
- [x] **H.02 Fix the customizer DSL stand-in.** `CustomizerScriptSpec` used a Map as the closure delegate, so `init { }` never resolved. It now uses a delegate class that also records `decorate`.
- [x] **H.03 Fix a null paging cookie in a test.** The cookie-following loop in `SearchOperationSpec` passed `null` to `setPagedResultsCookie`, which ICF rejects.
- [x] **H.04 Make the ES5 check ignore comments.** Backticks in the library's comments made the check fail against the stub.
- [x] **H.05 Remove accidental passes.** Two JavaScript assertions were satisfied by the stub's own error message, and two discovery assertions were vacuous (one ended in `|| true`). All four now assert something a stub cannot satisfy. A new test requires an explicit policy for every recon situation.
- [x] **H.06 Verify the red baseline.** Every failing test in all three suites now fails on a `TODO WSn` stub, with no failures caused by the harness. The structural tests that pass are listed in §6.3.

- [x] **WS0.13 Contract probe (L).** 2026-11-04. A command-line tool that observes the service and answers the observable contract questions, citing numbered exchanges as evidence. Read-only by default.
- [x] **WS0.16 Fix the probe's scrubbing and read handling (M).** 2026-11-21. The first real run leaked unscrubbed entity data and reported answers built on failed reads as observed. Both are fixed and covered by `RealShapedServiceSpec`.
- [x] **P13, P14, P15 folded in.** 2026-11-21. Authentication, the search endpoint and full-state relationship writes are reflected in the code, fakes, specs and documents.

---

### WS0 — Service contract and design revision

Owner: connector lead with the service team and the design owner. Outputs: `docs/service-contract.md`, the amended design, and revised stubs and specs.

#### Part A — Contract questions still open

- [ ] **WS0.01 Hold the service-team session (S).** The question pack is `docs/service-contract.md` §2, §3 and §5.5. Lead with C1 (GET by ID returns 500), C6 (base-type numbering), Q07.8 and T1 (another relationship writer). Booking it needs a person.
- [ ] **WS0.07 Relationship semantics (M).** Confirm with the service team, then observe once WS0.15 lands:
  - Q07.8: does a write of entity A remove relationships where A is the target and that are missing from A's collection? The answer decides whether each write must list inbound relationships too (design A2 says yes).
  - Does a create keep, drop or reject a relationship to an entity that doesn't exist yet (design A7)?
  - Does GET return relationships in both directions?
  - Does DELETE remove every relationship touching the entity?
- [ ] **WS0.09 ID constraints (S).** Allowed characters, maximum length, case sensitivity, and percent-encoding in paths. The first run could not answer this, because GET failed.
- [ ] **WS0.10 Rate limits (S).** The rate-limit policy, and whether 429 carries `Retry-After`. Authentication is settled (P13).
- [ ] **WS0.11 Keep real recordings (M).** From a scrubbed probe run, keep one document of each model (create body, GET, search page) under `connector/src/test/resources/wiremock/`. These feed WS4.09 and WS8.
- [ ] **WS0.12 Fold answers in as they arrive (M, ongoing).** For each answer: change the code, fake and specs it names, add a decision to §3, and close the item in §10 and in the contract.

#### Part B — Revise the design to the real API

- [ ] **WS0.14a Decide the open design questions (M).** With the design owner, decide D2 and D5–D9 from `docs/service-contract.md` §6:
  - D2, the format of `entityRelationships` values (likely `{sourceID, targetID, type}`);
  - D5, base-type numbering;
  - D6, type scoping by `objectClass` and archetypes;
  - D7, single-valued attributes;
  - D8, which ICF filters to translate;
  - D9, archetype assignment and whether archetypes must already exist in a manifest.
  D1 is decided (P15). D3 and D4 are deferred to WS10 (P16).
- [ ] **WS0.14b Amend the design document (M).** Rewrite §2 (endpoints and three document models), §2.2 and §7 (explicit source and target), §6 (configuration), §8.3 (edge encoding), §9.4 (search with filter trees and the paging cookie) and §9.5. Move §11 under a "deferred to WS10" heading.
- [ ] **WS0.14c Revise the model and its specs (L).** Bring `EntityDocument`, `Edge`, `EdgeCodec`, `EntityKind`, `EntityPayloadBuilder`, `EntityDecoder` and their specs in line with the decisions. Keep them as stubs. They must still fail only on `TODO` markers.
- [ ] **WS0.14d Revise search and paging specs (M).** Rewrite `SearchPlannerSpec`, `PageCursorSpec`, `TypeScoperSpec` and `SearchOperationSpec` for filter trees, `objectClass` and `pagedResultsCookie`. Move the timestamp ("since") cases to a WS10 spec, so WS4 can go green without them.
- [ ] **WS0.14e Bring the fakes in line (M).** `FakeGraphService` and `FakeGraphHttpServer` serve the real shapes: `id` on write, `uid` in search, `source`/`target` on read, the paging cookie, and the documented status codes. Update `Fixtures` and the Appendix A scenario.
- [ ] **WS0.15 Realign the probe's write checks (M).** Send EntityDTO documents and use the relationship endpoints. Read the Entity model on GET. Re-run read and write modes against a test instance, and fold the answers in through WS0.12.
  *Green:* `ContractProbeSpec` against the revised fake; a real write run whose answers are all "observed" or explained.

### WS1 — Configuration, convention and schema

Do these in order; later tasks reuse earlier ones.

- [ ] **WS1.01 Settle the package name (S).** If P12 changes, rename `au.com.azwork.idm.graphentity` across all three projects now, before any implementation lands.
- [ ] **WS1.02 `EntityKind` (S).** *Blocked by D5 (base-type numbering, WS0.14a).* The definition's samples say 2 = Subject and 4 = Resource, the reverse of today's enum and `EntityKindSpec`. WS0.14c fixes the numbering first. `fromConfig` trims the value and ignores case. A null, blank or unknown value throws `ConfigurationException`, with "entityKind" and the value in the message. `fromBaseType` returns null for an unknown or null BaseType, so a strange remote document never fails a read.
  *Green:* `EntityKindSpec`.
- [ ] **WS1.03 `Direction` (S).** `fromConfig` ignores case and rejects null, blank or unknown values with `ConfigurationException`. Implement `opposite`.
  *Green:* `DirectionSpec`.
- [ ] **WS1.04 `ObjectTypesParser.parse(String)` (S).** Parse with `JsonSlurper` and convert `JsonException` to `ConfigurationException`. Reject null, blank, a non-object root, a type whose value is not an object, and an empty document.
- [ ] **WS1.05 `ObjectTypesParser.parse(Map)` (M).**
  - The type name comes from the map key and the relationship property from its key.
  - `idmResource`, `entityKind` and `idProperty` are required. A missing one is reported with the type and field named.
  - `attributes` and `archetypes` must be lists of non-blank strings. Missing optional blocks default to empty.
  - Reserved attribute names are rejected: `directArchetypes`, `entityRelationships`, `lastUpdated`, `__UID__`, `__NAME__` and `ID`. WS0.14 may rename `lastUpdated`, since the service has `lastEvaluated`, which is not a modification time.
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
  - `apiKey` is required, as a String or `char[]`, and must contain no control characters, since it becomes a header value. Never echo it in a message. There is no username (P13).
  - `apiKeyHeader` defaults to `X-API-KEY` and must be a header name of letters, digits and dashes.
  - `objectTypes` may be a JSON string or a Map. Parse it through WS1.04/05, then run WS1.11.
  - Coerce string forms of booleans and integers, because `customConfiguration` delivers them that way.
  - `pageSizeDefault` must be between 1 and `MAX_PAGE_SIZE`.
  - `typeFor` is null-safe.
  - Validation warnings need a way out. Add a `warnings` property on the config for `ScriptSupport` to log at WARN.
  *Green:* `ConnectorConfigSpec`.
- [ ] **WS1.13 `ObjectClassResolver` (S).** A null or unknown class throws `ConnectorException`, naming it and the configured classes. There is no implicit `__ACCOUNT__` alias.
  *Green:* `ObjectClassResolverSpec`.
- [ ] **WS1.14 `SchemaFactory.objectClassInfo` (S).** *Depends on D7 (WS0.14a):* the write model's attributes are string to string, so multi-valued domain attributes may not be possible. `__NAME__` is required. Domain attributes are single-valued strings. `directArchetypes` and `entityRelationships` are multivalued strings. `lastUpdated` is readable but neither creatable nor updatable.
  *Decide:* whether `lastUpdated` should be returned by default. The spec title says it is not, but no assertion checks this, because mappings don't map it. Pick one, then make the spec assert it.
- [ ] **WS1.15 `SchemaFactory.build` (M).** Use one `SchemaBuilder` for all types. Support Schema, Test, Search, Get, Create, Update and Delete. Do not support Sync or Authenticate.
  *Gotcha:* `SchemaBuilder` derives its default operation set from the SPI interfaces the connector class implements. The spec passes the bare `Connector` interface, which implements none. In IDM the class is `ScriptedRESTConnector`, which implements all of them, including Sync. So the implementation must add and remove support per operation explicitly. Alternatively, change the spec to pass a test class implementing exactly the seven SPI interfaces. Record the decision in the PR.
  *Green:* `SchemaFactorySpec`.
- [ ] **WS1.16 `SchemaOperation` (S).** Delegate to `SchemaFactory` and make no HTTP calls.
  *Green:* the three schema cases in `TestAndSchemaOperationSpec`.
- [ ] **WS1.17 WS1 review pass (S).** Check that every exception message names the type and property involved, that no message contains configuration secrets, and that the §2.3 map still matches the code.

### WS2 — Model, codec and payload

*Starts after WS0.14c.* The task list below is the shape of the work. WS0.14c fixes the exact field names.

- [ ] **WS2.01 `Edge` (S).** One relationship with explicit `sourceID`, `targetID` and `type` (D2). Validate that both ends and the type are non-blank. Provide the read-model mapping from `source`/`target`/`type`/`sourceType`.
  *Green:* `EdgeSpec` as revised.
- [ ] **WS2.02 `EntityDocument` (M).** Three models:
  - the write model (EntityDTO: `id`, `baseType`, `attributes` as string to string, `relationships`, `directArchetypes`), always emitting the two collections so a write is explicit (P15);
  - the read model (Entity: `id`, `baseType`, `Attributes` as `{name, type, value}`, `compoundAttributes`, `relationships`, `lastEvaluated`, `reconciliationStarted`);
  - the search item (`uid`, `name`, `baseType` as a name, `attributes`, `isArchetypes`, `directArchetypes`, `lastEvaluated`).
  *Green:* `EntityDocumentSpec` as revised.
- [ ] **WS2.03 `EdgeCodec.serialise` and `serialiseAll` (S).** Canonical JSON in the D2 format, no whitespace, sorted.
  *Gotcha:* Groovy's `JsonOutput` escapes non-ASCII as `\uXXXX`. Prefer a `JsonGenerator` with unicode escaping disabled.
- [ ] **WS2.04 `EdgeCodec.parse` (M).** Reject malformed input with `InvalidAttributeValueException` naming the offending value.
  *Gotcha:* `JsonSlurper` can be lenient about trailing characters. Add an end-of-input check if needed.
- [ ] **WS2.05 `EdgeCodec.parseAll` (S).** Null or empty gives an empty list. Remove duplicates, keeping first-seen order.
  *Green:* `EdgeCodecSpec`.
- [ ] **WS2.06 `EntityPayloadBuilder.idForCreate` (S).** Prefer `__NAME__`, fall back to `__UID__`, otherwise throw `InvalidAttributeValueException`.
- [ ] **WS2.07 `EntityPayloadBuilder.build` (M).** Build the write model:
  - `id` from the uid (A3) and `baseType` from the type's kind (D5);
  - configured attributes as strings (D7);
  - `directArchetypes` as the static archetypes plus delivered ones (D9);
  - `relationships` from `entityRelationships`, complete (P15).
  Ignore read-only attributes such as `lastEvaluated` rather than rejecting them.
  *Green:* `EntityPayloadBuilderSpec`.
- [ ] **WS2.08 `EntityDecoder.decode` (M).** Decode both the search item and the read model into a `ConnectorObject`: `__UID__` and `__NAME__` from `uid` or `id`, configured attributes, and archetypes. Include `entityRelationships` only from the read model; search items carry none (D4, deferred to WS10).
  *Green:* `EntityDecoderSpec`.
- [ ] **WS2.09 Review (S).** `build` and `decode` stay pure functions with no API, log or clock dependencies.

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
- [ ] **WS3.04 `HttpErrorMapper.redact` (S).** Mask `X-API-KEY` values as a header, JSON field or query parameter, in any case, along with `apiKey` and `api_key` fields, `Authorization` values (Basic and Bearer) and JSON `password` fields. Leave other text untouched and accept null. Separately, `raise` must strip the configured key's literal value from any message, because the service may echo the key without a recognisable label, and one leaked key exposes every consumer (P13).
  *Green:* `HttpErrorMapperSpec`.
- [ ] **WS3.05 `RestClientEntityApi` (L).** Implement the five calls over `RESTClient`. `search` is a POST to `SEARCH_PATH` with the fields as a JSON body. An empty map sends `{}` (P14). Convert responses to `HttpResponse`, carrying the status, the parsed body (Map or List, or null for a non-JSON body), the raw body and the headers. Never throw for a non-2xx status. Let transport failures propagate as `IOException`.
  *Gotcha:* `RESTClient` parses bodies by content type and consumes the stream, so the raw body is lost unless you take control. Request text and parse JSON with `JsonSlurper` yourself, or register a buffering parser.
  *Gotcha:* the path encoding. `RESTClient` and `URIBuilder` can re-encode `%` as `%25`. The spec requires `GET` of `a/b` to hit `/admin/entities/a%2Fb` exactly, so build the request URI from the already-encoded segment.
  *Gotcha:* `URLEncoder` encodes a space as `+`, but a path segment needs `%20`.
  *Green:* `RestClientEntityApiSpec`.
- [ ] **WS3.06 `ScriptSupport` (M).**
  - `propertiesFrom` converts the `serviceAddress` URI to a string. It reads the `GuardedString` `password`, which holds the API key (P13), through `access()`, returns it as `apiKey` and does not mutate the configuration. It never returns `password` or `username`.
  - It copies the property bag keys. When the bag lacks `objectTypes`, it parses `customConfiguration` with `ConfigSlurper`.
  - `contextFrom` builds the `OperationContext`. A null connection is allowed for Schema.
  - It logs the config's validation warnings at WARN.
  - Cache the parsed `ConnectorConfig`, keyed by a hash of the `objectTypes` text. Recon calls the scripts thousands of times, and re-parsing and re-validating each time is waste.
  *Green:* `ScriptSupportSpec`.
- [ ] **WS3.07 `EdgeVerifier` (S).** *Depends on D2 (WS0.14a)* for the edge shape, and on C1 (GET by ID) to be useful against the real service. If GET stays broken, verification logs a WARN and the create still succeeds (P11). `dropped` compares as sets, treating null as empty. `warnMessage` uses `-T->` for outbound and `<-T-` for inbound edges, with IDs and type only.
  *Green:* `EdgeVerifierSpec`.
- [ ] **WS3.08 `CreateOperation` (M).** Create is `POST /admin/entities` with an EntityDTO body (`id`, `baseType`, `attributes`, `relationships`, `directArchetypes`), carrying the full relationship set (P15).
  1. Resolve the type, get the id from `idForCreate`, and build the document. Malformed input fails here, before any HTTP call.
  2. `POST`, then map the outcome, logging a 400 body at ERROR.
  3. Run the verification `GET`. If it fails, log WARN "verification GET failed for <id>" and still return the Uid (P11).
  4. Log each dropped edge at WARN.
  5. If `rePutOnDroppedEdges` is on and edges were dropped, `PUT` the same document.
  6. Convert any `IOException` to `ConnectorIOException`.
  7. Return `new Uid(id)`.
  Never log attribute values above `OK`.
  *Green:* `CreateOperationSpec`.
- [ ] **WS3.09 `UpdateOperation` (M).** The service removes every relationship missing from the collection (P15), so the P4 guard is essential, not defensive. Resolve the type. Require `entityRelationships` and `directArchetypes` to be present (P4), or throw `InvalidAttributeValueException` naming the missing one. Build from `uid.uidValue`, `PUT`, and map the outcome. Never `GET`.
  *Green:* `UpdateOperationSpec`.
- [ ] **WS3.10 Reject partial-value updates in `UpdateScript` (S). New test needed.** The Groovy toolkit routes `ADD_ATTRIBUTE_VALUES` and `REMOVE_ATTRIBUTE_VALUES` through the update script with a different `operation` binding. Those carry deltas, not the full state, so turning them into a `PUT` would delete edges. Throw `UnsupportedOperationException` for them, and add an `OperationScriptsSpec` case for each.
- [ ] **WS3.11 `DeleteOperation` (S).** `DELETE`, then map the outcome. The definition documents 404 for a missing entity, but the probe saw 200 (C3); either way the outcome is "gone". A tolerated 404 logs INFO with the id. Never `GET`.
  *Green:* `DeleteOperationSpec`.
- [ ] **WS3.12 `TestOperation` (S).** Run a search POST with `pageSize: 1` using `PageCursor`'s first page, then map with `TEST` context. Convert `IOException` to `ConnectorIOException`.
  *Green:* the test cases in `TestAndSchemaOperationSpec`.
- [ ] **WS3.13 `CustomizerScript` (M).**
  - `init` reads the API key from the `GuardedString` `password` property and adds it, raw, as a default `X-API-KEY` header, or under the `apiKeyHeader` override from the property bag. It never sends an `Authorization` header (P13).
  - It sets connect, socket and connection-request timeouts on a `RequestConfig`. Defaults come from constants and can be overridden through property bag keys `connectTimeoutMs` and `socketTimeoutMs`. Document both keys in the provisioner sample.
  - It uses the system trust store for https, and logs WARN when the address is http on a host other than localhost (§14).
  - `release` stays safe to call.
  - Add `decorate` as decided in WS3.01.
  *Green:* `CustomizerScriptSpec`.
- [ ] **WS3.14 Scripts end to end through WireMock (S).** The create, update, delete and test cases of `OperationScriptsSpec` pass, including the new WS3.10 cases.
- [ ] **WS3.15 Logging review (S).** Grep every `log.` call. Above `OK` level, a message may contain IDs, edge shapes, statuses and redacted bodies, never attribute values or credentials (§13, §14).

### WS4 — Search, scoping and paging

*Starts after WS3 and WS0.14d.* Search is `POST /admin/entities/search` (P14).

- [ ] **WS4.01 `PageCursor` encode and decode (S).** The ICF cookie wraps the service's opaque `pagedResultsCookie`. A blank ICF cookie is the first page. A corrupt one throws `IllegalArgumentException`; never restart from the first page silently.
- [ ] **WS4.02 `PageCursor.toQuery` (S).** Produce `{pageSize, pagedResultsCookie}` request-body fields; numbers stay numbers.
- [ ] **WS4.03 `PageCursor.next` (S).** The next page exists when the response carries a non-null `pagedResultsCookie` (`hasMore` true). `totalResults` of −1 means unknown.
  *Green:* `PageCursorSpec` as revised.
- [ ] **WS4.04 `SearchPlanner.plan` (M).**
  - No filter: enumerate the type's `objectClass`.
  - `EqualsFilter` on `__UID__` or `__NAME__`: read by ID.
  - Equals, contains, starts-with, and, or and not on configured attributes: translate into the service's filter tree, if D8 approves them.
  - Anything else: `UnsupportedOperationException` with "filter" in the message (P7).
  - Page size from options, capped, defaulting to `pageSizeDefault` (the service's default is 100).
  *Green:* `SearchPlannerSpec` as revised.
- [ ] **WS4.05 `TypeScoper` (S).** Scope by `objectClass` in the request, then by the type's archetypes in the results (D6).
  *Green:* `TypeScoperSpec`.
- [ ] **WS4.06 `SearchOperation` by ID (S).** GET; a 404 is an empty result. Decode the read model, check scope, call the handler.
  *Gotcha:* GET returned 500 in the first real run (C1). Map a 500 to `ConnectorIOException`, never to "not found".
- [ ] **WS4.07 `SearchOperation` enumerate (L).** Loop over pages with the cookie. Decode each search item, skip undecodable ones with one WARN and no attribute values, scope, and call the handler, stopping when it returns false. With a caller page size, return one page with the ICF cookie and remaining count. Never buffer across pages.
  *Gotcha:* the toolkit's `handler` may be a `Closure` or a `ResultsHandler`. Accept both.
  *Green:* `SearchOperationSpec`, the search cases of `OperationScriptsSpec`, and **`WorkedExampleSpec` (milestone M3)**.
- [ ] **WS4.08 Reconciliation without relationships (S).** Search items carry no relationships, so IDM sees `entityRelationships` as changed on every reconciliation and re-sends it. The write is idempotent (P15), so this is correct but costly. Document it, and decide with WS8.11's measurements whether to stop returning `entityRelationships` from search. The real fix is WS10.
- [ ] **WS4.09 Contract spec from real recordings (M).** Replay the WS0.11 recordings through `RestClientEntityApi` and the decoders. If the service drifts from the fake, this spec fails first.

### WS5 — Mapping-side payload library

The library must stay ES5, and its §7 rules must match the Groovy ones exactly.

- [ ] **WS5.01 One source for convention cases (S).** Move the normalisation, passive-detection and pair cases into `fixtures/convention-cases.json`, read by both `RelationshipConventionSpec` and the JavaScript tests.
- [ ] **WS5.02 `normalisePropertyName`, `isPassive`, `resolvePair` (S).** Port WS1.07–WS1.09. Can start now.
- [ ] **WS5.03 `resolveRelationship` (M).** Override first, then the convention with the entry's hint, then `config/managed`. `sync: false` returns null. Unresolvable throws, naming the property. Can start now.
- [ ] **WS5.04 `loadTypeConfig`, `clearCache` and the cache (M).** Read the provisioner configuration, take `objectTypes` from `configurationProperties` or from `customConfiguration`, and cache with an optional TTL (default five minutes).
- [ ] **WS5.05 `buildRelationships` (L).** *After WS0.14a (D2).* Emit the complete set of relationships touching the source entity, as explicit source and target pairs in the D2 format. The §7 convention decides which IDM side is the source. Inbound relationships are included, per Q07.8.
  *Gotcha:* some IDM versions return only `_ref`; fall back to splitting it.
  *Gotcha:* one read per related object. Cache within a call; WS8.11 evaluates field expansion.
- [ ] **WS5.06 `buildArchetypes` (S).** Static archetypes, then a single or multi-valued `archetypeProperty`, without duplicates.
  *Green for WS5.02–WS5.06:* `npm test` in `mapping-lib`.
- [ ] **WS5.07 Rhino smoke test (M).** A Gradle test that evaluates the library under Mozilla Rhino 1.7.x with a fake `openidm`.
- [ ] **WS5.08 Library README (S).** How mappings `require` it, its functions, ES5 limits, and the cache.

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
- [ ] **WS6.05 `MappingSkeletonGenerator.mapping` (M).** Target-phase situations stay on `REPORT` (P16); promotion to `DELETE` is WS10. Follow the shape of `config/sync-mapping-user.sample.json`:
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

- [ ] **WS7.01 Complete `connectorScripts` (S).** Include `graphEntityPayload.js` under `script/lib/`, a `VERSION` file with the git SHA, and a `distZip` task.
- [ ] **WS7.02 Confirm the library deployment model (M).** Verify in IDM 7.5 that the provisioner's `classpath` accepts Groovy sources (P9); otherwise package a jar.
- [ ] **WS7.03 Provisioner generation (S).** Generate `customConfiguration` with the WS6.07 tool. Document the API key in `password` and `defaultAuthMethod: NONE` (P13).
- [ ] **WS7.04 Confirm versions (S).** IDM 7.5's Groovy version and the Scripted REST bundle version.
- [ ] **WS7.05 ForgeRock flavour in CI (S).** A CI job with `-PicfFlavour=forgerock`, credentials as secrets.
- [ ] **WS7.06 Development IDM container (M).** docker-compose with IDM 7.5 and its repository, mounting the build output.
- [ ] **WS7.07 Smoke check (S).** `system/graphentity?_action=test` succeeds and the schema lists `user` and `account`.
- [ ] **WS7.08 Install section of the runbook (S).** §8.1 steps 1–3.

### WS8 — Integration tests against IDM 7.5

- [ ] **WS8.01 Integration project (M).** An `integration/` subproject with Spock driving IDM's REST API on the WS7.06 stack, backed by WireMock recordings or a test tenant. Runs on demand and nightly.
- [ ] **WS8.02 Managed schema fixture (S).** `managed.json` with `account` and the `owns`/`ownedBy` pair; provisioner and mappings generated by the discovery tool.
- [ ] **WS8.03 Appendix A through reconciliation (M).** Reconcile users, then accounts. Assert the service's documents and relationships at each step, and the dropped-relationship WARN if the service drops them (WS0.07).
- [ ] **WS8.04 Scalar-only update keeps relationships (S).** Change only a name in IDM; assert the update carried the full relationship set (P4, P15) and the relationship survives.
- [ ] **WS8.05 Relationship removal (S).** Remove a relationship in IDM; assert both entities' writes omit it and the final state has none.
- [ ] **WS8.06 Delete (S).** Delete the account in IDM; assert the delete and the user's relationship-free write.
- [ ] **WS8.07 Orphans are reported, not deleted (S).** Seed an entity directly in the service; assert reconciliation reports it and does not delete it (P16).
- [ ] **WS8.08 Library under real Rhino (S).** `require` the library in IDM; record the module cache's actual lifetime.
- [ ] **WS8.09 Error paths (M).** 401 on test; 5xx during reconciliation reported against the right object; 429 under queued sync; `deleteMissingTolerated=false`.
- [ ] **WS8.10 Concurrency (M).** Live sync during reconciliation with `taskThreads` > 1; once quiet, the service's state equals IDM's.
- [ ] **WS8.11 Volume and write cost (L).** Enumerate 50,000 entities within the reconciliation window with flat memory. Measure `buildRelationships` cost per entity, and the cost of re-sending relationships on every reconciliation (WS4.08).
- [ ] **WS8.12 Nightly job (S).** Run nightly, archive results, alert on failure.

### WS9 — Operational readiness and rollout (core)

- [ ] **WS9.01 Runbook (M).** Onboarding order (§8.1); reading the dropped-relationship WARN; the doubled write when a relationship changes (§9.2) and self-correcting intermediate states (§10); never running reconciliation and live sync together during onboarding; the A1 watch item; what M5 does *not* detect (P16).
- [ ] **WS9.02 Reconciliation schedules (S).** Full reconciliation per type, with conservative `taskThreads`.
- [ ] **WS9.03 Monitoring (M).** Dropped-relationship WARN volume, the `ConnectorIOException` rate, and reconciliation REPORT counts per situation.
- [ ] **WS9.04 Mutable `idProperty` guard (M).** An `onUpdate` mapping snippet that turns an `idProperty` change into delete then create, with an integration case in WS8.
- [ ] **WS9.05 Security review (M).** The shared API key (storage, who can read the configuration, rotation with every consumer), TLS everywhere, the discovery tool's read-only credentials, and a log audit for keys and PII.
- [ ] **WS9.06 Non-production dry run (M).** Against the real service with `REPORT` everywhere; review counts with operations.
- [ ] **WS9.07 Go-live decision (S).** Sign-off from operations, security and the design owner, including the explicit decision to go live at M5 or wait for M6 (§10).
- [ ] **WS9.08 Handover (S).** Walk support through the runbook and the log signals.

### WS10 — Drift detection (last)

*Starts after M5.* Scope and prerequisites are in §4 and P16.

- [ ] **WS10.01 Ask the service for what drift detection needs (S).** A last-modified time on entities, and a filter or field for "modified since" (D3). A way to read relationships for many entities at once, or relationships in search results (D4). A working GET by ID (C1).
- [ ] **WS10.02 Design drift detection against the real API (M).** Rewrite design §11 around the answers. Choose between: comparing relationships in reconciliation through per-entity reads, an incremental "modified since" check, periodic full restatement, or a combination. Record the choice as a decision in §3.
- [ ] **WS10.03 Relationship comparison in reconciliation (L).** If chosen: return `entityRelationships` from the read model so reconciliation compares them, within the WS8.11 performance budget. This also removes WS4.08's redundant writes.
- [ ] **WS10.04 Incremental drift check (L).** Only if WS10.01 yields a "modified since" capability. A scheduled script that queries changed entities and runs `recon?_action=reconById` for each, advancing a checkpoint only after a whole page succeeds. Tests first, including the blind spot for entities deleted directly in the service. The timestamp search cases moved out of WS4 (WS0.14d) return here.
- [ ] **WS10.05 Orphan promotion checklist (S).** Per-type criteria for moving target-phase situations from `REPORT` to `DELETE`, with sign-off and rollback.
- [ ] **WS10.06 Drift integration tests (M).** A relationship removed directly in the service is restored; an entity deleted directly in the service is re-created by full reconciliation; an orphan is deleted only after promotion; the incremental check, if built.
- [ ] **WS10.07 Schedules and monitoring (S).** The drift mechanism's schedule, and alerts on its lag and failures.
- [ ] **WS10.08 Runbook and handover (S).** Drift cadence, reading drift reports, and the promotion process.
- [ ] **WS10.09 Sign-off (S).** Operations, security and the design owner approve drift detection and each type's promotion.

### Cross-cutting (every workstream)

- [ ] **X.01 Pull request checklist.** Cite the task ID. Name the specs turned green. Confirm no test expectation changed without a stated reason. Confirm the full-state write invariant (P4, P15).
- [ ] **X.02 Keep §2.3 and §6.3 current.**
- [ ] **X.03 Decision log.** Record new or changed decisions in §3 with the date.
- [ ] **X.04 Close open questions** in §10 and the service contract when answers arrive, linking the evidence.
- [ ] **X.05 Convention parity.** Change a §7 rule only together with `fixtures/convention-cases.json` (WS5.01).
- [ ] **X.06 CI stays meaningful.** Track per-workstream pass counts rather than marking specs `@Ignore`, which would hide regressions.

---

## Appendix — Commands

```bash
# full Groovy suite (ConnId flavour); run Gradle on JDK 17 or 21
./gradlew test

# ForgeRock flavour (release build; needs maven.forgerock.org access)
./gradlew test -PicfFlavour=forgerock

# one spec
./gradlew :connector:test --tests 'au.com.azwork.idm.graphentity.ops.CreateOperationSpec'

# mapping library
cd mapping-lib && npm test

# contract probe, read-only (everything for the probe goes inside --args)
./gradlew :contract-probe:run --args="--base-url URL --api-key KEY"

# packaging
./gradlew :connector:connectorScripts
```
