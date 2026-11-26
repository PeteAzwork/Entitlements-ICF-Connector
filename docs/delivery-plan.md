# Delivery Plan — Ping IDM Scripted REST Connector for the Entitlements Accelerator Service

**Source design:** `idm-entitlements-accelerator-connector-design.md` (Draft for review, 2026-07-06, amended 2026-11-21)
**Service contract:** `docs/service-contract.md` (the real API, as far as it is known)
**Plan status:** Proposed, **v2.25 — 2026-11-26** (renamed: package `idm.entitlementsaccelerator`, connector `entitlementsaccelerator`, the service the Entitlements Accelerator Service; the build pinned to JDK 21 with Java 17 class files). v2.24, 2026-11-22: (WS8.13: the end-to-end load test, `entitlementsaccelerator-loadtest`, for a real IDM against a real service or the stand-in; runbook §3 corrected on dropped-edge noise). v2.23: (the deployment guide, `docs/deployment-guide.md`, with a walkthrough on a brand-new IDM; the discovery tool as a release artefact; proxy settings honoured, which they were not; TLS with a private CA proven in IDM; WS8.11 at 50,000 users; H21, a provisioner save discarded by IDM, found by the first GitHub run). v2.22: (WS7 and WS9 work: the IDM-layout distribution, a development stack, the ID-change guard and schedules proven in IDM, the runbook, the security review pack and the go-live checklist; what needs people or a real environment is marked). v2.21: (P9 restored: the library deploys as scripts after all, the earlier compile failure being the `createFullConfig` race; the jar stays an option; `script_vs_jar.md`). v2.20: (WS8: the connector runs inside a real IDM 7.5 container; Appendix A, updates, relationship removal, delete, orphans, error paths and a first scale run pass; IDM showed defects the unit suites could not, all fixed: `createFullConfig` before saving, `"source": ""`, and the `SOURCE_MISSING` policy). v2.19: (WS6 done: the discovery tool, its command line, a read-only IDM source and the mapping lint; every unit suite green, M4 reached; the natural-key signal is a proposal for the platform team). v2.18: (WS5 done: the mapping library implemented, its §7 cases shared with the connector through `fixtures/convention-cases.json`, and a Rhino smoke test). v2.17: (WS4 done: search, scoping and paging; every connector spec green, milestone M3; `RecordedContractSpec` replays the real recordings through the connector). v2.16: (WS3 done: transport, error mapping, write and test operations, the customizer; the harness contract settled from Ping's documentation, which showed the schema script must use the `builder` DSL). v2.15: (WS2 done: the three document models, the relationship codec, the payload builder and the decoder). v2.14: (WS1 done: configuration, convention and schema implemented; `lastEvaluated` returned by default; the schema built from a fixed operation list). v2.13: (WS0.11: real recordings kept, and the fake checked against them). v2.12: (P20 amended: `""` clears and reads as absent; the empty-list purge is a service bug; WS0.15h starting-state checks). v2.11: (write run `215954`: an empty relationship list removed nothing even after waiting, a replaced relationship resurfaced (C18), and `""` stores an empty value). v2.10: (WS0.15g: the probe waits for relationship removal and checks replacement and clearing; the owner confirms an empty relationship list purges all relationships). v2.9: write run `214027` folded in. v2.8: (WS0.15f: settled writes, read on the written entity, leftover removal; P23: IDs containing `/` rejected; P22 amended: the manifest check warns). v2.7: write run `211859` folded in. v2.6: WS0.15e, the probe waits for writes to settle. v2.5: (P19 amended to scope on `isArchetypes`; C8 and C9 folded into the specs, fakes and probe). v2.4: write run `190350` folded in. The service owner confirmed C8, `attributes.ID` required, and C9, direct archetypes reported in `isArchetypes`, as by design, and reports that deletes work. Base type 4 is a Subject (P18)). v2.3: first realigned runs; P19 challenged (C9). v2.2: WS0.14 and WS0.15 done. v2.1: design decisions P17–P22 recorded. v2.0, 2026-11-21: Rewritten after the service's OpenAPI definition arrived. Relationship writes are confirmed as full-state (P15), and drift detection is scheduled last (P16).
**Target platform:** Self-managed Ping IDM 7.5, Groovy Connector Toolkit 1.5.20.x (Scripted REST)
**Delivery approach:** Test-first. The repository holds the unit test suite and thin stubs; implementation turns specs green workstream by workstream.

---

## 1. How to read this plan

The design document says *what* the connector is. This plan says *in what order* it gets built, *how each piece is proven*, and *what is still unknown*.

- §2 what is delivered, and where it lives;
- §3 the decisions this plan makes, P1–P23 (review these first);
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
| D1 | Connector library (Groovy classes the scripts delegate to) | §4–§9, §13 | `connector/src/main/groovy/idm/entitlementsaccelerator/` | API fixed, bodies are stubs. Several classes encode the old API model and change in WS0.14 |
| D2 | Seven ICF operation scripts | §4 | `connector/src/main/scripts/*.groovy` | Written (thin delegation) |
| D3 | Connector tests: unit, script harness, contract, scenario | §6 of this plan | `connector/src/test/groovy/…` | **29 specs, 537 executions**, red by design |
| D4 | Mapping-side payload library (ES5 JavaScript for IDM's Rhino) | §8.2 | `mapping-lib/src/lib/entitlementsAcceleratorPayload.js`, `mapping-lib/README.md` | **Implemented (WS5)** |
| D5 | Mapping library tests | §8.2 | `mapping-lib/test/`, `fixtures/convention-cases.json`, `EntitlementsAcceleratorPayloadRhinoSpec` | **109 Node tests and 9 Rhino tests, all passing** |
| D6 | Discovery and generation tool | §12 | `discovery/src/main/groovy/…`, `./gradlew :discovery:run` | **Implemented (WS6)** |
| D7 | Discovery tool tests | §12 | `discovery/src/test/groovy/…` | **7 specs, 71 executions, all passing**; `lintMappings` runs in CI |
| D8 | Provisioner and mapping configuration samples | §6, §12 | `config/` | Written |
| D9 | Build, CI, packaging task | — | `build.gradle`, `.github/workflows/ci.yml` | Written |
| D10 | Integration tests against IDM 7.5 in Docker | this plan §6 | `integration/` | Not started (WS8) |
| D11 | Operator runbook | §9, §10, §14 | `docs/runbook.md` | Not started (WS7, WS9) |
| D12 | Service contract | §15 | `docs/service-contract.md` | Register written; API definition recorded; contradictions C1–C7 listed; design questions D1–D9 decided or deferred |
| D15 | Design decision record for the real API | §2–§9 | `docs/design-decisions-2026-11-21.md` | Decisions P17–P22 recorded, with the conditions that gate implementation |
| D13 | Contract probe | §15 | `contract-probe/` | **126 tests passing.** Settled writes, outcomes read on the written entity, starting states checked (WS0.15h). Next: a write run once the service fixes the empty-list purge |
| D14 | Drift detection: design, implementation, tests and operations | §11 | design §11, `mapping-lib`, `integration/`, runbook | Not started. **Scheduled last (WS10)** |

### 2.2 Scope boundaries

- **Out of scope** (unchanged from the design): inbound provisioning, password synchronisation, LiveSync and a SyncScript, remote-originated relationship types, and the read-merge-write mode of design §15.
- **In scope but last** (P16): drift detection, as defined in WS10.
- **Not used by the connector:** the service's `/entitlements`, `/policy-information`, `/admin/manifests` and delegation endpoints.

### 2.3 Design → code map

The map below is the current one. Every connector class in it is implemented (WS1–WS4); the connector library has no stubs left.

| Design section | Library class(es) | Spec(s) |
|---|---|---|
| §2.1 document shape | `model.EntityDocument` | `EntityDocumentSpec` |
| §2.2 edge rules | `model.Edge` | `EdgeSpec` |
| §3 A3/A4 identity and kind | `config.EntityKind`, `payload.EntityPayloadBuilder` | `EntityKindSpec`, `EntityPayloadBuilderSpec` |
| §5.2/§6 per-type dynamic schema | `config.ObjectTypesParser`, `config.ObjectTypeConfig`, `config.ConnectorConfig`, `schema.SchemaFactory`, `ops.ObjectClassResolver` | `ObjectTypesParserSpec`, `ConnectorConfigSpec`, `SchemaFactorySpec`, `ObjectClassResolverSpec` |
| §7 convention (normative) | `convention.RelationshipConvention`, `config.Direction` | `RelationshipConventionSpec`, `DirectionSpec`, and the JavaScript `resolvePair`/`resolveRelationship` tests |
| §8.2 mapping-side assembly | `entitlementsAcceleratorPayload.js` | `entitlementsAcceleratorPayload.test.js`, `EntitlementsAcceleratorPayloadRhinoSpec` (Rhino) |
| §7 shared cases | `fixtures/convention-cases.json` | `RelationshipConventionSpec` and `entitlementsAcceleratorPayload.test.js` both read it |
| §8.3 ICF encoding | `codec.EdgeCodec` | `EdgeCodecSpec` |
| §8.4/§9.2 update | `ops.UpdateOperation` | `UpdateOperationSpec` |
| §9.1 create and verification | `ops.CreateOperation`, `payload.EdgeVerifier` | `CreateOperationSpec`, `EdgeVerifierSpec` |
| §9.3 delete | `ops.DeleteOperation` | `DeleteOperationSpec` |
| §9.4 search, get, scoping, paging | `search.SearchPlanner`, `search.TypeScoper`, `search.PageCursor`, `ops.SearchOperation`, `payload.EntityDecoder` | `SearchPlannerSpec`, `TypeScoperSpec`, `PageCursorSpec`, `SearchOperationSpec`, `EntityDecoderSpec` |
| §9.5 test and schema | `ops.TestOperation`, `ops.SchemaOperation` | `TestAndSchemaOperationSpec` |
| §13 exception mapping | `http.HttpErrorMapper` | `HttpErrorMapperSpec` |
| §2/§14 transport and auth | `http.RestClientEntityApi`, `ops.ClientCustomizer`, `CustomizerScript` | `RestClientEntityApiSpec`, `CustomizerScriptSpec` |
| §4 scripts as the harness runs them | `ops.ScriptSupport`, `schema.SchemaDsl`, scripts | `OperationScriptsSpec`, `ScriptSupportSpec` |
| §12 discovery | `discovery.*` | `ManagedSchemaReaderSpec`, `ObjectTypesGeneratorSpec`, `MappingSkeletonGeneratorSpec`, `DiffSummarySpec` |
| Appendix A | all operations against `FakeAcceleratorService` | `WorkedExampleSpec` |
| WS0.11 recordings | the connector against the real service's recorded exchanges | `RecordedShapesSpec`, `RecordedContractSpec` |

---

## 3. Decisions (review these first)

Each decision is pinned by a test, so changing one is deliberate. "Status" says whether the real API has confirmed, changed or superseded it.

| # | Decision | Status | Where pinned |
|---|---|---|---|
| P1 | **Connector-specific configuration travels in `customConfiguration`**, surfaced to scripts as `configuration.propertyBag`. `objectTypes` is a JSON string inside it. The Scripted REST connector has a fixed configuration class, so arbitrary top-level properties don't reach scripts. | Holds | `config/provisioner…json`, `ScriptSupportSpec` |
| P2 | **Relationship entries carry `relatedType` and `reverseProperty` hints**, emitted by the discovery tool, so the connector can validate §7 consistency without `managed.json`. | Holds | `ObjectTypesParserSpec`, `RelationshipConventionSpec`, `ObjectTypesGeneratorSpec` |
| P3 | **The passive form in the §7 convention is a trailing `By`** (`ownedBy`). Exactly one side must be passive, or the pair needs an override. | Holds | `RelationshipConventionSpec`, JavaScript equivalents |
| P4 | **`UpdateOperation` refuses an update that omits `entityRelationships` or `directArchetypes`.** A present-but-empty attribute is still a valid "none". | **Essential, given P15**: the service deletes any relationship missing from an update | `UpdateOperationSpec` |
| P5 | ~~Type scoping on enumeration checks the base type as well as archetypes.~~ | **Superseded by P19** | `TypeScoperSpec` |
| P6 | ~~Paging uses `offset`/`pageSize` with `items` and `totalCount` or `nextCursor`.~~ | **Superseded.** The real protocol is `pageSize` plus an opaque `pagedResultsCookie`, with `results`, `totalResults` and `hasMore` in the response. All paging knowledge stays in `PageCursor` | `PageCursorSpec` (revise in WS0.14) |
| P7 | **Unsupported ICF filters raise `UnsupportedOperationException`** rather than silently enumerating. | Holds, but more filters become supported (D8) | `SearchPlannerSpec` |
| P8 | **The build compiles against ConnId's `connector-framework` by default** (`icfFlavour=connid`), switchable to ForgeRock's (`icfFlavour=forgerock`). The release build uses ForgeRock's. | Holds | `gradle.properties`, `build.gradle` |
| P9 | **Library classes deploy as Groovy sources under the provisioner's `classpath`.** | **Holds, confirmed in IDM 7.5 (WS8, 2026-11-21)**, provided `createFullConfig` is called before the provisioner is saved (H11). A jar is supported as an alternative (`-PlibraryAs=jar`). See `script_vs_jar.md` | `connectorScripts`, `compileWithIdmGroovy`, `ConnectorInIdmSpec` (both modes) |
| P10 | **The mapping library reads `config/managed` only as a fallback** for relationship properties not listed in `objectTypes`. | Holds | `entitlementsAcceleratorPayload.test.js` |
| P11 | **A failed verification GET after a successful create does not fail the create**; it is logged at WARN. | Holds. Matters now: GET by ID returned 500 in the first real run (C1) | `CreateOperationSpec` |
| P12 | **Package namespace `idm.entitlementsaccelerator`.** Renaming is a find-and-replace before WS1 starts. | Holds | everywhere |
| P13 | **Authentication is one shared API key, sent raw in the `X-API-KEY` header.** Stored in the encrypted `password` property; `defaultAuthMethod` is `NONE`; no username. *2026-11-21.* | Confirmed by the owner and the definition | `ConnectorConfigSpec`, `CustomizerScriptSpec`, `RestClientEntityApiSpec`, `HttpErrorMapperSpec`, `ScriptSupportSpec`, `ContractProbeSpec` |
| P14 | **Search is `POST /admin/entities/search` with a JSON body; `{}` is the unfiltered search.** *2026-11-21.* | Confirmed by the owner and the definition | `RestClientEntityApiSpec`, `OperationScriptsSpec`, `ContractProbeSpec` |
| P15 | **Relationship writes are full-state, through the entity's `relationships` collection on create and update.** The service removes every relationship missing from the collection, so every write carries the complete set. The single-relationship endpoint (`POST /admin/entities/{id}/relationships`) also works, but the connector does not use it: one full-state write per entity keeps writes idempotent and keeps the design's §8 model. *Decided 2026-11-21 (D1).* | Confirmed by the owner. Still open: whether "missing" includes relationships where the entity is the *target* (Q07.8). **Re-confirmed by the owner 2026-11-21:** an empty `relationships` list purges all of the entity's relationships. Run `215954` showed the service does not yet purge (C17); the owner treats this as a service bug to be fixed (WS0.23). Rewriting with the same relationship changes nothing | `UpdateOperationSpec`, `WorkedExampleSpec` |
| P16 | **Drift detection is built last, in WS10.** Drift detection means everything built specifically to detect and repair changes made outside IDM: the incremental drift check (design §11), comparing relationships during reconciliation, promoting orphans from `REPORT` to `DELETE`, and their tests, schedules and monitoring. Until WS10, reconciliation runs as IDM does it natively. It is used for the initial load, every target-phase situation stays on `REPORT`, and relationship drift goes undetected. *Decided 2026-11-21 (D3, D4).* | Decided by the owner | `MappingSkeletonGeneratorSpec` (REPORT policies) |
| P17 | **Relationships are encoded in the service's write shape, `{"sourceID","targetID","type"}`** (D2). Values pass straight into the create or update body; the read model's `source`/`target` is converted to the same shape. The §7 convention decides which IDM side is the source. Each value must name the entity itself as source or target. *Decided 2026-11-21.* | Decided by the owner. Q07.8 still open | `EdgeSpec`, `EdgeCodecSpec` (revised in WS0.14c) |
| P18 | **The base type is sent as a number, with the design's numbering: 4 = Subject, 2 = Resource** (D5). `objectClass` in searches uses the same numbers; reads accept numbers or names. *Decided 2026-11-21.* | **Confirmed 2026-11-21:** an entity created with base type 4 is a Subject (run `20261121-190350` #41, #45) | `EntityKindSpec` (unchanged) |
| P19 | **Each type has a scope archetype, its first static archetype, stamped as a direct archetype on every write** (D6). Enumeration sends `objectClass` and keeps entities that carry the scope archetype in `isArchetypes`, where search reports directly assigned archetypes (C9). Types sharing a base type need distinct scope archetypes, and scope archetypes must be ones no policy assigns. Reads by ID check only the base type. *Decided 2026-11-21; amended the same day.* | Decided by the owner; replaces P5. **Amended 2026-11-21:** scope on `isArchetypes`, not `directArchetypes` | `TypeScoperSpec`, `ObjectTypesParserSpec`, `EntityDecoderSpec` |
| P20 | **Domain attributes are single-valued strings** (D7). Numbers and booleans are sent as text; multi-valued IDM properties are excluded by the discovery tool. **Every configured attribute is sent on every write; one empty in IDM is sent as `""`, and `""` reads back as absent.** *Decided 2026-11-21; amended the same day (option a).* | Amended by the owner: the service merges attributes and stores `""` as an empty value (C17), so omission cannot clear one | `EntityPayloadBuilderSpec`, `EntityDecoderSpec`, `UpdateOperationSpec`, `SchemaFactorySpec` |
| P21 | **Supported searches: by ID, plus equals, contains and starts-with on configured attributes, combined with and, or and not** (D8), sent by operation name. Ends-with and comparisons follow once the service names its operations. A by-ID read falls back to a search on the ID while GET by ID fails (C1). *Decided 2026-11-21.* | Decided by the owner; extends P7 | `SearchPlannerSpec` (rewritten in WS0.14d) |
| P22 | **Archetypes are set through `directArchetypes` in the entity body, never the assignment endpoint** (D9). The Test operation reads the manifests and **warns** about any configured archetype they do not define. *Decided 2026-11-21; amended the same day.* | **Confirmed 2026-11-21:** an update replaces `directArchetypes` in full (run `20261121-211859` #200–#205). **Amended 2026-11-21 (C16):** the manifest check warns rather than fails, because the service accepts undefined names and existing data uses them | `TestAndSchemaOperationSpec` |
| P23 | **An ID containing `/` is rejected** with `InvalidAttributeValueException` before any request, on create, update and delete. *Decided 2026-11-21.* | Decided by the owner: the service stores such IDs but cannot address them by path (Q09.1) | `EntityPayloadBuilderSpec`, `UpdateOperationSpec`, `DeleteOperationSpec` |

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
**Part B, the design revision (WS0.14, WS0.15):** done 2026-11-21. D2 and D5–D9 are decided (P17–P22, `docs/design-decisions-2026-11-21.md`); design §2, §6–§9, §13 and Appendix A are rewritten; the stubs, fakes, specs, mapping-library tests and probe use the real shapes. Still open: C1–C7 with the service team, and a probe write run against a test instance (WS0.15b).
**Acceptance:** the design matches the real API. The specs that encode the API's shape describe the real one and still fail only on stubs. C1 (GET by ID returns 500) is understood.
**Estimate:** 5 days plus service-team elapsed time.

### WS1 — Configuration, convention and schema

**Goal:** the connector loads, validates and exposes its schema.
**Implement:** `EntityKind`, `Direction`, `ObjectTypesParser`, `ConnectorConfig`, `ObjectTypeConfig.syncedRelationships`, `RelationshipConvention`, `ObjectClassResolver`, `SchemaFactory`, `SchemaOperation`.
**Acceptance:** `EntityKindSpec`, `DirectionSpec`, `ObjectTypesParserSpec`, `ConnectorConfigSpec`, `RelationshipConventionSpec`, `ObjectClassResolverSpec`, `SchemaFactorySpec`, and the schema cases of `TestAndSchemaOperationSpec`.
**Estimate:** 3.5 days.
**Status:** Done 2026-11-21. All acceptance specs green.

### WS2 — Model, codec and payload

**Goal:** pure functions from ICF attributes to the service's write model, and from its read and search models back.
**Implement:** `Edge`, `EntityDocument`, `EdgeCodec`, `EntityPayloadBuilder`, `EntityDecoder`, as revised by WS0.14.
**Acceptance:** `EdgeSpec`, `EntityDocumentSpec`, `EdgeCodecSpec`, `EntityPayloadBuilderSpec`, `EntityDecoderSpec`.
**Estimate:** 3 days (up from 2: three document models instead of one).
**Status:** Done 2026-11-21. All acceptance specs green.

### WS3 — Transport, error mapping and write operations

**Goal:** create, update, delete and test work end to end through the real HTTP client.
**Implement:** `HttpErrorMapper`, `RestClientEntityApi`, `ScriptSupport`, `EdgeVerifier`, `CreateOperation`, `UpdateOperation`, `DeleteOperation`, `TestOperation`, and the `CustomizerScript` body.
**Acceptance:** `HttpErrorMapperSpec`, `RestClientEntityApiSpec`, `ScriptSupportSpec`, `EdgeVerifierSpec`, `CreateOperationSpec`, `UpdateOperationSpec`, `DeleteOperationSpec`, `TestAndSchemaOperationSpec`, `CustomizerScriptSpec`, and the create, update, delete and test cases of `OperationScriptsSpec`.
**Estimate:** 5 days.
**Status:** Done 2026-11-21. All acceptance specs green; only `OperationScriptsSpec`'s two search cases remain, for WS4.

### WS4 — Search, scoping and paging

**Goal:** get by ID, translation of ICF filters into the service's filter tree, and scoped enumeration with ICF paging cookies.
**Implement:** `SearchPlanner`, `PageCursor`, `TypeScoper`, `SearchOperation`, as revised by WS0.14. Timestamp ("since") searches are **not** part of WS4; they belong to WS10 if the service ever offers one.
**Acceptance:** `SearchPlannerSpec`, `PageCursorSpec`, `TypeScoperSpec`, `SearchOperationSpec`, the search cases of `OperationScriptsSpec`, then `WorkedExampleSpec` (Appendix A end to end) as the milestone check.
**Estimate:** 3.5 days (filter-tree translation replaces the timestamp search).
**Status:** Done 2026-11-21. All acceptance specs green, `WorkedExampleSpec` included: **M3 reached**.

### WS5 — Mapping-side payload library

**Goal:** `buildRelationships` and `buildArchetypes` produce the complete relationship set and archetypes from IDM state.
**Implement:** every function in `entitlementsAcceleratorPayload.js`, ES5 only, emitting edges in the format WS0.14 settles (D2).
**Acceptance:** `npm test` in `mapping-lib/` green, and the Rhino smoke test (WS5.07).
**Estimate:** 4 days (the drift-check script moved to WS10).
**Status:** Done 2026-11-21. `npm test` green (109) and the Rhino smoke test green (9).

### WS6 — Discovery and generation tool

**Goal:** generate `objectTypes` and mapping skeletons from `managed.json`, with the same validation the connector runs.
**Implement:** `ManagedSchemaReader`, `ObjectTypesGenerator`, `MappingSkeletonGenerator`, `DiffSummary`, `DiscoveryTool`.
**Acceptance:** all four discovery specs green, plus the new CLI, REST and lint specs.
**Estimate:** 6 days.
**Status:** Done 2026-11-21. All discovery specs green, including the new CLI, REST and lint specs. WS6.01's natural-key signal is a proposal awaiting the platform team.

### WS7 — Packaging and deployment artefacts

**Goal:** one command produces what is copied into IDM.
**Acceptance:** a fresh IDM 7.5 container plus the artefact passes `system/entitlementsaccelerator?_action=test`, and the schema exposes `user` and `account`.
**Estimate:** 2.5 days.
**Status:** 2026-11-21: done except WS7.05, which waits for ForgeRock repository secrets. WS7.09 (the deployment guide) and WS7.10 (proxy settings) added and done. The acceptance check (a fresh IDM 7.5 plus the artefact passes Test, with `user` and `account` in the schema) passes with the development stack.

### WS8 — Integration tests against IDM 7.5

**Goal:** prove the scripts run inside the real Scripted REST harness and the mappings drive the connector as designed: create, update, relationship changes, delete, initial-load reconciliation, and orphans reported but never deleted.
**Acceptance:** a scripted, repeatable run, archived in CI. Drift-repair scenarios are WS10's.
**Estimate:** 5.5 days.
**Status:** 2026-11-22: WS8.01–8.13 done (8.13, the end-to-end load test, still to run against the real service's test tenant); 8.10 and 8.11 at 50,000 users. The first GitHub run found H21, fixed in the harness; a green GitHub run on the fix is still to come. Defects found and fixed are recorded in `docs/service-contract.md` §4.1.

### WS9 — Operational readiness and rollout

**Goal:** the core connector is safe to run against production data.
**Acceptance:** the runbook is reviewed by operations, a dry-run reconciliation against the real service with `REPORT` everywhere is reviewed, and sign-off is recorded.
**Estimate:** 3 days plus review.
**Status:** 2026-11-21: WS9.01–9.04 done (9.01 awaits operations review); 9.05–9.08 are prepared and need the security review, the real service, and sign-off.

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
| L2 Operation unit | Each ICF operation against the service's semantics, including every §13 status and the no-PII logging rule | Spock + `FakeAcceleratorService` + `CapturingLog` | `…/ops` |
| L3 Transport contract | The real HTTP client against WireMock: paths, methods, headers, the API key, JSON bodies, encoding, non-2xx handling | WireMock 3 | `RestClientEntityApiSpec` |
| L4 Script harness | The seven scripts evaluated as the harness does, against WireMock | `ScriptHarness` | `OperationScriptsSpec`, `CustomizerScriptSpec` |
| L5 Scenario | Appendix A end to end | `@Stepwise` + `FakeAcceleratorService` | `WorkedExampleSpec` |
| L6 Mapping library | The JavaScript library under Rhino-like isolation, and under Rhino itself | Node test runner; Spock with Rhino 1.7.14 | `mapping-lib/test`, `EntitlementsAcceleratorPayloadRhinoSpec` |
| L7 Discovery | Generator correctness and agreement with the connector's validation | Spock | `discovery/src/test` |
| L7b Contract probe | The probe reports the service's semantics faithfully, never writes in read-only mode, cleans up, and never persists credentials or foreign data | Spock + `FakeAcceleratorHttpServer` + WireMock replay | `contract-probe/src/test` |
| L8 Integration (WS8) | The real IDM harness, Rhino and reconciliation engine | Docker, `gcr.io/forgerock-io/idm:7.5.0`, and `FakeAcceleratorService` behind `FakeAcceleratorHttpServer` (a test tenant can replace it) | `integration/` |
| L9 Operational dry run (WS9) | The real service, `REPORT`-only reconciliation | Non-production IDM | runbook |
| L10 Drift (WS10) | Remote-side changes are detected and repaired | Integration suite | `integration/` |

### 6.2 Conventions enforced by the suite

- **Red by construction.** Stubs throw `UnsupportedOperationException("TODO WSn: …")`. Tests that expect that exception for legitimate reasons also assert its message, so a stub cannot pass them.
- **Every test cites its design section.**
- **No PII above DEBUG**, and never the API key, in any log line or error message.
- **Service semantics live in one place.** `FakeAcceleratorService` models the service. When the contract changes, change the fake and the affected specs together. WS0.14 brought the fake in line with the real API's shapes; `FakeAcceleratorService`'s class comment lists which behaviours are documented and which are still assumptions.
- **Drift-only cases are labelled.** The service has no timestamp search, so WS0.14 replaced the timestamp-search cases with one that pins their refusal (`SearchPlannerSpec`, P16). WS4 can go green without them; the behaviour returns in WS10.04 if the service ever offers it.

### 6.3 Catalogue of tests already written

| Spec | Design refs | Feature methods | Executions (incl. data-table rows) |
|---|---|---|---|
| `config/EntityKindSpec` | §3 A4, §6 | 5 | 29 |
| `config/DirectionSpec` | §7 | 3 | 10 |
| `config/ObjectTypesParserSpec` | §6 | 22 | 38 |
| `config/ConnectorConfigSpec` | §6, §15, P13 | 21 | 45 |
| `convention/RelationshipConventionSpec` | §7, WS5.01 | 29 | 94 |
| `model/EdgeSpec` | §2.2 | 8 | 17 |
| `model/EntityDocumentSpec` | §2.1 | 18 | 18 |
| `codec/EdgeCodecSpec` | §8.3 | 9 | 21 |
| `payload/EntityPayloadBuilderSpec` | §8.3, §8.4, A3 | 28 | 38 |
| `payload/EntityDecoderSpec` | §9.4 | 13 | 13 |
| `payload/EdgeVerifierSpec` | §9.1 | 8 | 8 |
| `http/HttpErrorMapperSpec` | §13, P13 | 20 | 108 |
| `http/HttpResponseSpec` | — | 2 | 2 |
| `http/RestClientEntityApiSpec` | §2, §14, P14 | 15 | 27 |
| `schema/SchemaFactorySpec` | §5.2, §6, §9.5 | 10 | 13 |
| `search/SearchPlannerSpec` | §9.4, P21 (timestamp searches refused, P16) | 12 | 25 |
| `search/PageCursorSpec` | §9.4, §15 | 10 | 16 |
| `search/TypeScoperSpec` | §9.4 | 5 | 20 |
| `ops/ObjectClassResolverSpec` | §5.2 | 4 | 4 |
| `ops/CreateOperationSpec` | §9.1, §13 | 20 | 20 |
| `ops/UpdateOperationSpec` | §8.4, §9.2 | 17 | 17 |
| `ops/DeleteOperationSpec` | §9.3 | 11 | 11 |
| `ops/SearchOperationSpec` | §9.4 | 22 | 22 |
| `ops/TestAndSchemaOperationSpec` | §9.5 | 15 | 15 |
| `ops/ScriptSupportSpec` | §4, P13 | 8 | 8 |
| `scripts/OperationScriptsSpec` | §4, WS3.01, WS3.10 | 18 | 19 |
| `scripts/CustomizerScriptSpec` | §4, §14, P13, WS3.01, WS7.10 | 8 | 13 |
| `support/RecordedShapesSpec` | WS0.11, §2.1 | 10 | 10 |
| `scenario/WorkedExampleSpec` | Appendix A | 6 | 6 |
| `scenario/RecordedContractSpec` | WS4.09, WS0.11 | 5 | 5 |
| `mapping/EntitlementsAcceleratorPayloadRhinoSpec` | WS5.07, §8.2 | 6 | 9 |
| **Connector total** | | **388** | **701** |
| `discovery/ManagedSchemaReaderSpec` | §12, WS6.01 | 11 | 11 |
| `discovery/ObjectTypesGeneratorSpec` | §12, §7 | 20 | 20 |
| `discovery/MappingSkeletonGeneratorSpec` | §12, §8.2, H12, H13 | 11 | 11 |
| `discovery/DiffSummarySpec` | §12 | 7 | 7 |
| `discovery/DiscoveryToolSpec` | WS6.07, WS7.03, WS9.06 | 13 | 17 |
| `discovery/IdmManagedSourceSpec` | WS6.08, §14 | 4 | 4 |
| `discovery/MappingLintSpec` | WS6.09, §8.4, H12 | 6 | 7 |
| **Discovery total** | | **72** | **77** |
| `mapping-lib/test/entitlementsAcceleratorPayload.test.js` | §7, §8.2, §6, WS5.01, WS9.04, WS8.08 | — | **117** |
| `contract-probe/ContractProbeSpec` | WS0, §2 | 56 | 56 |
| `contract-probe/ProbeOutputSpec` | WS0.11, §13, §14 | 10 | 10 |
| `contract-probe/ScrubberSpec` | §13 | 16 | 16 |
| `contract-probe/RealShapedServiceSpec` | WS0.16 | 13 | 13 |
| `contract-probe/ProbeMainSpec` | WS0 | 11 | 31 |
| **Contract probe total** | | **106** | **126** |
| `integration/ConnectorInIdmSpec` | WS7.07, WS8.08, H5, H9 | 5 | 5 |
| `integration/AppendixAInIdmSpec` | WS8.03–8.07, Appendix A | 8 | 8 |
| `integration/ErrorPathsInIdmSpec` | WS8.09 | 3 | 3 |
| `integration/OperationsInIdmSpec` | WS9.02, WS9.04 | 2 | 2 |
| `integration/RuntimeInIdmSpec` | WS8.08, WS8.09 | 3 | 3 |
| `integration/NetworkInIdmSpec` | WS7.09, WS7.10 (TLS with a private CA, proxy) | 4 | 4 |
| `integration/LoadTestInIdmSpec` | WS8.13 | 1 | 1 |
| `integration/ScaleInIdmSpec` (opt-in, `-PidmScale=N`) | WS8.10, WS8.11 | 3 | 3 |
| **Integration total** (needs Docker; `./gradlew :integration:test -Pidm`) | | **26 (+3)** | **26 (+3)** |
| `loadtest/LoadTestOptionsSpec`, `LatenciesSpec`, `LoadReportSpec`, `ServiceClientSpec` | WS8.13 | 30 | 30 |
| **Grand total** | | | **1077 (+3 opt-in)** |

Current result (2026-11-22, after the load test): **every suite is green**: connector 701 / 701, discovery 77 / 77, load test 30 / 30, mapping library 117 / 117, contract probe 126 / 126, `:discovery:lintMappings`, `:connector:compileWithIdmGroovy`, and **integration 26 / 26 inside IDM 7.5** (25 in both library modes and at 1 CPU on 2026-11-21; `LoadTestInIdmSpec` added and green); the opt-in scale spec passes at 50,000 users. `dev/deploy-walkthrough.sh` and `entitlementsaccelerator-loadtest` run end to end on a brand-new IDM 7.5. No `TODO WSn` stub remains in the code.

Before WS1 (after WS0.11) the connector stood at 18 pass / 606 fail; after WS1, 212 / 413; after WS2, 320 / 305; after WS3, 554 / 83. The mapping library stood at 3 pass / 66 fail before WS5, and discovery at 0 / 45 before WS6.

### 6.4 Tests still to write

- **WS8 (core integration):** Appendix A inside IDM through reconciliation; relationship add, change and removal; delete; an orphan reported and not deleted; the library loading under Rhino; a provisioner refresh exposing a new class; 50,000-entity enumeration within the reconciliation window with flat memory; error paths (401, 5xx, 429 under queued sync).
- **WS10 (drift):** a relationship removed directly in the service is restored; an entity deleted directly in the service is re-created by full reconciliation; orphan deletion after promotion; the incremental check, if built.

---

## 7. Environments, build and CI

- **Build:** Gradle 8.14 wrapper, Groovy 3.0.21, Spock 2.3, WireMock 3.9, HTTP Builder 0.7.1. **Run Gradle on JDK 17 or 21**; Gradle 8.14 cannot run on Java 25.
- **ICF flavour:** `./gradlew test` uses ConnId from Maven Central. `./gradlew test -PicfFlavour=forgerock` uses ForgeRock's `connector-framework` 1.5.20.21 from `maven.forgerock.org`. The release artefact uses the ForgeRock flavour.
- **Mapping library:** `cd mapping-lib && npm test` (Node 20+, no dependencies).
- **Contract probe:** `./gradlew :contract-probe:run --args="--base-url URL --api-key KEY"`. Everything for the probe goes inside `--args="…"`. Read-only by default; `--allow-writes` only against a test instance. The write run is realigned (WS0.15) and is next (WS0.15b). Usage: `docs/service-contract.md` §1.
- **Shared fixtures:** `FakeAcceleratorService` and `FakeAcceleratorHttpServer` in `connector/src/testFixtures`.
- **CI:** `.github/workflows/ci.yml` runs the Gradle suites (ConnId flavour) and the Node suite on every push and pull request. Add a ForgeRock-flavour job when the runner can reach its repository.
- **Packaging:** `./gradlew :connector:connectorScripts`.

---

## 8. Deployment and rollout

### 8.1 Core rollout (M5)

1. Copy `scripts/` to `&{idm.instance.dir}/connectors/entitlementsaccelerator/scripts`, `lib/` to `…/entitlementsaccelerator/lib`, and `entitlementsAcceleratorPayload.js` to `&{idm.instance.dir}/script/lib/`.
2. Install `provisioner.openicf-entitlementsaccelerator.json` with the API key in `password` and `defaultAuthMethod` set to `NONE` (P13). Generate `objectTypes` with the discovery tool and review it.
3. Check `system/entitlementsaccelerator?_action=test`, then that `system/entitlementsaccelerator/user` and `/account` appear in the schema.
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
| **Base-type numbering (C6, P18)** | P18 keeps 4 = Subject, which the API's create samples contradict. If the samples are right, every entity is created as the wrong type | Medium | WS0.15 creates one entity with base type 4 and checks a Subject search finds it, before WS1.02 ships and before any create against a shared service |
| **Create body rejected (C8)** | Without `attributes.ID` every create and update gets 500 | Resolved by WS0.19 | Mirror the ID into `attributes.ID` on every write |
| **Omitted attributes are kept (C17)** | An attribute cleared in IDM would stay in the service | Resolved by P20 (amended) | Empty attributes are sent as `""` and read as absent |
| **Relationships not removed (C17, C18)** | Until the service fixes the empty-list purge, a relationship removed in IDM may stay in the service, keeping entitlements IDM has removed | **High until fixed** | Service bug acknowledged by the owner (WS0.23). The probe checks it on every write run; drift detection (WS10) is the backstop |
| **Lost updates on rapid writes (C13)** | A write sent while the previous write to the same entity is still being applied can be lost. IDM may create an entity and update it straight away, for example when a relationship arrives just after the create | Medium | Raise with the service team. Consider having the connector wait until a create is visible before updating the same entity, or IDM's queued sync with a short delay. Drift detection (WS10) is the backstop |
| **Policy-assigned scope archetypes (C9, P19 amended)** | Scoping reads `isArchetypes`, which policy rules can also add to. A policy that assigns a type's scope archetype elsewhere would widen that type's scope | Low | Choose scope archetypes that no policy assigns; check them against the manifests at deployment |
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
2. **Design owner:** P12, the package name; and **whether go-live waits for drift detection (M6) or happens at M5**. D2 and D5–D9 are decided (P17–P22).
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
| WS0 Service contract and design revision | 1 | 6 | 0.5 + elapsed |
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

- [ ] **WS0.01 Hold the service-team session (S).** The question pack is `docs/service-contract.md` §2, §3 and §5.5. Lead with C1 (GET by ID returns 500), C6 (base-type numbering), Q07.8 and T1 (another relationship writer). Booking it needs a person. The C1 questions, with evidence, are in [`C1-GET-BY-ID-NOTE.md`](../C1-GET-BY-ID-NOTE.md).
- [ ] **WS0.07 Relationship semantics (M).** Mostly answered by runs `211859`–`215954`:
  - inbound links on create and update work;
  - a relationship to a missing entity is dropped on PUT;
  - Q07.8 is answered;
  - DELETE removes the relationships touching the entity (confirmed once the starting-state check holds);
  - the DELETE endpoint signature is known;
  - the empty-list purge is a service bug (WS0.23).

  Left:
  - **A7 on create:** is a relationship to a missing entity dropped on POST? GET of the new entity failed throughout the window in every run.
  - **The single-relationship POST:** did not add within 10 s in run `215954`.

  The next write run re-checks both.
- [x] **WS0.09 ID constraints (S).** Done 2026-11-21 (runs `211859`, `214027`, `215954`).
  - A space, `?`, `#`, `+`, `%`, dots and non-ASCII letters are stored verbatim and addressable when percent-encoded. Lengths up to 1,024 work.
  - `/` is stored but cannot be addressed, so the connector rejects it (P23).
  - IDs are case-sensitive.
  - The service team should still state any documented maximum length.
- [ ] **WS0.10 Rate limits (S).** The rate-limit policy, and whether 429 carries `Retry-After`. Authentication is settled (P13).
- [x] **WS0.11 Keep real recordings (M).** Done 2026-11-21. Five recordings from run `20261121-215954` (scrubbed) are kept as WireMock mappings under `connector/src/test/resources/wiremock/recorded/`, with a README:
  - the create body;
  - the read model;
  - a search page;
  - a 400 with problem details;
  - the manifests bundle.

  `RecordedShapesSpec` holds the fake to those shapes: read model, search envelope and items, archetypes in `isArchetypes`, the 400's `errors` map, the create body's fields, and the bundle. It checks that no recording holds another entity's data, and runs `EntityDocument` on the real documents (WS2). The fake's 400s now carry `errors`, as the service's do.
- [ ] **WS0.12 Fold answers in as they arrive (M, ongoing).** For each answer: change the code, fake and specs it names, add a decision to §3, and close the item in §10 and in the contract.

#### Part B — Revise the design to the real API

- [x] **WS0.14a Decide the open design questions (M).** Done 2026-11-21. The owner decided D2 and D5–D9 one at a time: P17–P22, recorded in `docs/design-decisions-2026-11-21.md` with the conditions that gate implementation. D1 is decided (P15). D3 and D4 are deferred to WS10 (P16).
- [x] **WS0.14b Amend the design document (M).** Done 2026-11-21. §2 now describes the real endpoints and the three document models (write model, read model, search item); §2.2 and §7 use explicit source and target; §6 drops the timestamp setting and adds `byIdLookup` and the scope archetype; §8.3 the `{sourceID, targetID, type}` encoding; §9.1 the best-effort verification; §9.3 C3; §9.4 filter trees, cookie paging and P19 scoping; §9.5 the manifest check; §13 the contradictions; Appendix A the real shapes. §11 stays under its "deferred to WS10" note.
- [x] **WS0.14c Revise the model and its specs (L).** Done 2026-11-21. `EntityDocument` (three factories, one per model), `Edge` (`sourceId`, `targetId`, `type`), `EdgeCodec`, `EntityKind` (numbers and names), `EntityPayloadBuilder`, `EntityDecoder`, `EdgeVerifier`, `ConnectorConfig` (`byIdLookup`; timestamp setting removed), `TestOperation` (manifests) and their specs, plus the operation, script and scenario specs. Still stubs; every failure is a `TODO` marker. Also: the mapping-library tests emit the P17 string from both ends, discovery excludes multi-valued properties with a warning (P20), and the sample provisioner configuration is updated.
- [x] **WS0.14d Revise search and paging specs (M).** Done 2026-11-21. `SearchPlannerSpec` (filter trees, `objectClass`, unsupported-filter table), `PageCursorSpec` (`pagedResultsCookie`, `totalResults`, `hasMore`), `TypeScoperSpec` (P19) and `SearchOperationSpec` (read model on GET, GET 500 as an I/O error, the search fallback, scoping after paging). The timestamp cases became one refusal case (P16) rather than a separate WS10 spec, because the service has no timestamp search to specify.
- [x] **WS0.14e Bring the fakes in line (M).** Done 2026-11-21. `FakeAcceleratorService` and `FakeAcceleratorHttpServer` serve the write model, read model and search items, the paging cookie, manifests, the relationship endpoints, problem-details errors and the documented status codes. `Fixtures` and the Appendix A scenario use the real shapes.
- [x] **WS0.15 Realign the probe's write checks (M).** Done 2026-11-21. The probe writes EntityDTO documents in the P17 shape with base type 4 for Subjects (P18), reads the read model on GET, and reads back by an `EQUALS` filter on the ID whenever GET fails (C1). New checks: `baseType.objectClass` (read-only P18 evidence), `baseType.create` (P18), `search.idFilter` (P21), `search.createdVisible` (C5), `get.existingEntity` (C1), `edges.targetOmission` (Q07.8), `edges.relationshipEndpoints`, `attributes.omission` (P20), `archetypes.replace` (P22), `manifests.archetypes` (P22) and `errors.nonStringAttribute`. The timestamp checks and `--timestamp-field` are gone (P16); `--archetypes` is new. The scrubber also aliases filter values and reduces manifests to archetype names. `ContractProbeSpec`, `RealShapedServiceSpec`, `ProbeOutputSpec`, `ProbeMainSpec` and `ScrubberSpec` cover it: 97 tests, all passing.
- [x] **WS0.15b Run the realigned probe (S).** Done 2026-11-21: runs `20261121-173141` (read-only) and `20261121-173153` (write). Results are folded into the contract (section 5.5, section 7, register). The read side is answered. Every EntityDTO create got 500 (C8), so no write check could answer. The runs also surfaced C9 (no direct archetypes in search), C10 (deleted entities still found by search), C11 (manifests shape) and C12.
- [x] **WS0.15c Diagnose the create shape and tighten cleanup (M).** Done 2026-11-21. New checks: `create.shape` (tries each difference between the EntityDTO and the design document, then writes the rest of the run in the first accepted shape), `create.relationshipShape`, `search.reflectsDelete` and `probe.leftovers`. A search miss no longer counts as proof of deletion unless search has been shown to drop deleted entities. Earlier runs' probe entities count as probe data, and an undocumented manifests response is outlined. 108 tests. The fake's helpers became `protected` so that specs can subclass it.
- [x] **WS0.15d Run the write mode again (S).** Done 2026-11-21: run `20261121-190350`, scanned clean. Answered:
  - C8: creates need `attributes.ID` equal to `id`.
  - P18: base type 4 is a Subject on create.
  - Q07.8: writing the target without a relationship leaves it.
  - C2: a PUT to a missing entity creates it.
  - Q07.6: a relationship to a missing entity is dropped silently on PUT.
  - Paging stays stable while an entity is updated.
  It also showed that search reports directly assigned archetypes in `isArchetypes` (C9). The service owner confirmed this, and C8, as by design. The probe's readings that DELETE removes nothing (C10) conflict with the owner's experience that deletes work, and are being re-checked. P15 (removing a relationship by writing its source without it), P20 and P22 are still unobserved, because the probe read back before the service had caught up.
- [x] **WS0.15e Let writes settle before reading back (M).** Done 2026-11-21. Every read-back waits within a settle window (`--settle-seconds`, default 10) for the write it checks to become visible:
  - search is polled until the entity appears, disappears, or shows the update's marker value;
  - GET is retried while it answers 500 during re-evaluation (C1);
  - relationship checks retry GET until the outcome they test for appears, so a "No" means "not within the window";
  - the attribute and archetype checks wait for a marker value from the update before comparing;
  - the case-sensitivity check first waits for the entity itself;
  - paging stability waits for all four test entities;
  - the delete check and cleanup wait for the entity to disappear (C10);
  - `search.createdVisible` reports the observed lag.
  Archetypes are read from either field (C9). When neither `--archetypes` nor the manifests give two, the check uses probe archetypes. Archetype names are found anywhere in the manifests bundle (C11), and recordings keep only manifest and archetype names. A lagging fake service shows the same checks answering with a window and "cannot tell" without one. 114 tests.
- [x] **WS0.19 Fold C8 and C9 into the specs and fakes (S).** Done 2026-11-21. C8: `EntityDocument.toWire` adds `attributes.ID` equal to `id`, and reads drop it. `Fixtures` write models carry it, and the fake refuses a write without it with a bodyless 500, as the service does. C9: search items report archetypes in `isArchetypes`, which `TypeScoper` matches and `EntityDecoder` surfaces as `directArchetypes`; the fake renders search items that way. The probe's designed write body carries `attributes.ID`, and it reads archetypes from either field. The production code is still placeholders; WS2 and WS4 implement it against these specs.
- [x] **WS0.15f Wait for each write to apply, and read relationships on the written entity (M).** Done 2026-11-21.
  - Writes in the relationship, attribute, duplicate and archetype checks carry an `attributes.rev` marker, and the next step waits until search shows it (C13).
  - Relationship outcomes (P15, Q07.8, A7 on POST and PUT, inbound links) are read on the entity that was written, once its read model shows that write (C15).
  - A new `edges.otherEndRefreshed` records whether the other end picks a relationship up.
  - The delete-cascade check rewrites A after deleting B, because A's read model is not refreshed by B's deletion.
  - `--remove-leftovers` (with `--allow-writes`) deletes earlier runs' probe entities. It skips IDs containing `/`, which cannot be addressed (Q09.1).
  - New fakes model C13 (a write sent too soon is lost) and C15 (only the written entity refreshes). With them, the old approach would answer wrongly and the new one answers correctly.
  - 120 tests.
- [x] **WS0.20 IDs containing `/` (S, owner).** Done 2026-11-21, decision P23: the connector rejects them with `InvalidAttributeValueException` before any request, on create, update and delete. `EntityPayloadBuilderSpec`, `UpdateOperationSpec`, `DeleteOperationSpec`, design A3, §6 and §13 updated. The discovery tool cannot see values, so the runbook asks that every `idProperty` hold values without `/`.
- [x] **WS0.21 The Test operation's manifest check (S, owner).** Done 2026-11-21, P22 amended. A configured archetype missing from the manifests, or a service with no manifests, is a `WARN` naming it, not a failure. Archetype names are read from anywhere in the export bundle (C11). `TestAndSchemaOperationSpec`, `TestOperation` and design §9.5 updated.
- [x] **WS0.15g Pin down replacement and clearing (M).** Done 2026-11-21. The owner confirms that an empty `relationships` list purges all of the entity's relationships.
  - **Waiting for the effect:** relationship checks now wait, within the window, for the outcome itself (`readWrittenUntil`), not only for the write's marker. Removal may be applied after the attribute change.
  - **Non-empty replacement:** `edges.replaceNonEmpty` writes R with only R → T2 after R → T1.
  - **Endpoints and cascade:** the single-relationship endpoints and the delete cascade run on entities of their own, with no rewrite before reading.
  - **Clearing:** `attributes.clear` writes an attribute as `""` and reports whether it is removed, stored empty or rejected.
  - **Tests:** a delayed-removal fake shows the waiting answer "Yes" where a single read answers "No", as run `214027` did. A merging-attributes fake shows omission versus clearing. 123 tests.
- [x] **WS0.22 How to clear an attribute (S, owner).** Done 2026-11-21: option (a), P20 amended.
  - `EntityPayloadBuilder` sends `""` for every configured attribute that is empty in IDM, and `EntityDecoder` treats `""` as absent.
  - The fake service now merges attributes on update, as the real service does.
  - Specs, fixtures, design §2.1, §8.3 and §8.4 updated.
- [ ] **WS0.23 Empty-list purge: service bug to be fixed (service team).** The owner confirms that an empty `relationships` list should purge all of the entity's relationships, and that the current behaviour is a bug (C17). C18, a replaced relationship resurfacing, is probably the same defect. The connector keeps P15 as designed. Before WS3's update ships against a shared service, a probe run must show `edges.sourceOmission` and `edges.replaceNonEmpty` answering "Yes" with no resurfacing. The probe checks this on every write run.
- [x] **WS0.15h Check each relationship test's starting state (S).** Done 2026-11-21.
  - Each relationship check confirms its starting state first and reports "cannot tell" when it does not hold.
  - A replacement is re-read after a pause, and the endpoint DELETE reports a replaced relationship that reappears (C18).
  - New `edges.reassertSame` checks the owner's rule that rewriting with the same relationship changes nothing.
  - The endpoint check uses the documented `DELETE /admin/entities/{sourceID}/relationships/{targetID}/{type}`.
  - New fakes: no relationships stored (all checks "cannot tell") and resurfacing (C18 reported). 126 tests.
- [ ] **WS0.17 Remove probe leftovers and unscrubbed reports (S, owner).** The service owner reports deletes work (C10). The next run's `probe.leftovers` will show whether the entities from runs `20261121-155100` and `20261121-190350` are gone. Remove any that remain: run `214027` removed 18 with `--remove-leftovers`. Four entities whose IDs contain `/` (one from each write run: `155100`, `190350`, `211859`, `214027`) cannot be addressed and need the service team. Runs `20261121-154839` and `20261121-155100` hold real entity data in `main`'s history; removing it needs a rewrite of `main` and a force-push. Commit only runs made with the fixed scrubber, using `git add -f` (`build/` is ignored).
- [x] **WS0.18 Amend P19 (S, owner).** Done 2026-11-21. The owner approved scoping on `isArchetypes`, because by design search reports archetypes assigned through `directArchetypes` there (C9). Scope archetypes must be ones no policy assigns. Design §9.4, the decision record, `TypeScoper` and `TypeScoperSpec` are updated.

### WS1 — Configuration, convention and schema

Do these in order; later tasks reuse earlier ones.

- [x] **WS1.01 Settle the package name (S).** If P12 changes, rename `idm.entitlementsaccelerator` across all three projects now, before any implementation lands. *Done 2026-11-21: P12 holds, so nothing was renamed.*
- [x] **WS1.02 `EntityKind` (S).** P18 keeps the design's numbering, 4 = Subject and 2 = Resource, as `EntityKindSpec` already expects. `fromBaseType` also accepts the names `"Subject"` and `"Resource"` that search returns. *Gated by WS0.15* confirming the numbering against the service. `fromConfig` trims the value and ignores case. A null, blank or unknown value throws `ConfigurationException`, with "entityKind" and the value in the message. `fromBaseType` returns null for an unknown or null BaseType, so a strange remote document never fails a read.
  *Green:* `EntityKindSpec`.
- [x] **WS1.03 `Direction` (S).** `fromConfig` ignores case and rejects null, blank or unknown values with `ConfigurationException`. Implement `opposite`.
  *Green:* `DirectionSpec`.
- [x] **WS1.04 `ObjectTypesParser.parse(String)` (S).** Parse with `JsonSlurper` and convert `JsonException` to `ConfigurationException`. Reject null, blank, a non-object root, a type whose value is not an object, and an empty document.
- [x] **WS1.05 `ObjectTypesParser.parse(Map)` (M).**
  - The type name comes from the map key and the relationship property from its key.
  - `idmResource`, `entityKind` and `idProperty` are required. A missing one is reported with the type and field named.
  - `attributes` and `archetypes` must be lists of non-blank strings. Missing optional blocks default to empty.
  - Reserved attribute names are rejected: `directArchetypes`, `entityRelationships`, `lastEvaluated`, `__UID__`, `__NAME__`, `ID` and `id` (WS0.14 renamed `lastUpdated` to `lastEvaluated`; `ID` and `id` collide with the entity ID, C8).
  - Unknown keys are rejected, at type and relationship level, so a misspelt key cannot silently drop configuration.
  - The P19 rule: when several types share an entityKind, each needs a scope archetype (its first archetype) and no two may share one.
  - A relationship entry is *explicit* only when it has both `remoteType` and `direction`. Having only one of them is rejected as a half override, naming the property. `sync` defaults to true. `relatedType` and `reverseProperty` are carried as hints (P2).
  *Gotcha:* `ObjectTypeConfig` and `RelationshipConfig` use `@Canonical` equality. Normalise every list to `ArrayList` and every map to `LinkedHashMap`, or "map input equals JSON input" fails on collection type alone.
  *Green:* `ObjectTypesParserSpec`.
- [x] **WS1.06 `ObjectTypeConfig.syncedRelationships` (S).** Return the entries whose `sync` is not false, in configuration order.
- [x] **WS1.07 `RelationshipConvention.normalise` (S).** Upper-case the first letter. Strip a trailing `Set` or `List` only when it is a capitalised suffix with something before it, so `ownsSet` becomes `Owns` but `settings`, `set` and `list` keep their letters. A blank value throws `IllegalArgumentException`.
- [x] **WS1.08 `RelationshipConvention.isPassive` (S).** Strip any `Set`/`List` suffix first. The name is passive when it then ends in a capital `By` with at least one character before it. So `ownedBy` and `ownedBySet` are passive, and `by` and `standby` are not.
- [x] **WS1.09 `RelationshipConvention.resolvePair` (S).** The result must not depend on argument order. Exactly one side must be passive, and the remote type is `normalise` of the active side. Report the pair unresolved, with a reason, when neither or both sides are passive, the reverse is null or blank, or the pair is self-referential.
- [x] **WS1.10 `RelationshipConvention.resolve` (S).**
  1. `sync: false` returns null.
  2. An explicit override is used as-is.
  3. Otherwise apply the convention, using the reverse property from the argument or from the entry's hint.
  4. If none of these resolve it, throw `ConfigurationException` naming both properties.
- [x] **WS1.11 `RelationshipConvention.validate` (L).**
  - Resolve every synced entry, explicit or convention-derived.
  - Find each entry's counterpart through `relatedType` plus `reverseProperty`. Same-type pairs such as `manager`/`reports` on `user` must work.
  - Require opposite directions and the same `remoteType` on each pair.
  - Report collisions: two entries on one type with the same `remoteType`, direction and related type.
  - Collect *all* violations into one `ConfigurationException`.
  - Return warnings for a counterpart type that isn't configured and for entries without hints.
  *Green:* `RelationshipConventionSpec`.
- [x] **WS1.12 `ConnectorConfig.from` (M).**
  - `serviceAddress` must be an http or https URI with a host. Strip a trailing slash.
  - `apiKey` is required, as a String or `char[]`, and must contain no control characters, since it becomes a header value. Never echo it in a message. There is no username (P13).
  - `apiKeyHeader` defaults to `X-API-KEY` and must be a header name of letters, digits and dashes.
  - `objectTypes` may be a JSON string or a Map. Parse it through WS1.04/05, then run WS1.11.
  - Coerce string forms of booleans and integers, because `customConfiguration` delivers them that way.
  - `pageSizeDefault` must be between 1 and `MAX_PAGE_SIZE`.
  - `typeFor` is null-safe.
  - Validation warnings need a way out. Add a `warnings` property on the config for `ScriptSupport` to log at WARN.
  *Green:* `ConnectorConfigSpec`.
- [x] **WS1.13 `ObjectClassResolver` (S).** A null or unknown class throws `ConnectorException`, naming it and the configured classes. There is no implicit `__ACCOUNT__` alias.
  *Green:* `ObjectClassResolverSpec`.
- [x] **WS1.14 `SchemaFactory.objectClassInfo` (S).** Domain attributes are single-valued strings (P20). `__NAME__` is required. Domain attributes are single-valued strings. `directArchetypes` and `entityRelationships` are multivalued strings. `lastEvaluated` is readable but neither creatable nor updatable.
  *Decided 2026-11-21:* `lastEvaluated` **is** returned by default. Every read carries it at no cost and mappings ignore it; `SchemaFactorySpec` now asserts it. The spec also asked for `creatable`, which ConnId spells `createable`; corrected in the spec, with the expectation unchanged.
- [x] **WS1.15 `SchemaFactory.build` (M).** Use one `SchemaBuilder` for all types. Support Schema, Test, Search, Get, Create, Update and Delete. Do not support Sync or Authenticate.
  *Gotcha:* `SchemaBuilder` derives its default operation set from the SPI interfaces the connector class implements, and even with explicit operations it drops any the class does not implement. The spec passes the bare `Connector` interface, which implements none. In IDM the class is `ScriptedRESTConnector`, which implements all of them, including Sync.
  *Decided 2026-11-21:* `SchemaFactory` assembles the `Schema` directly, mapping its fixed list of SPI operations (Schema, Test, Search, Create, Update, Delete) to API operations with `FrameworkUtil.spi2apis`, so Search also gives Get. The result does not depend on the connector class; the spec is unchanged.
  *Green:* `SchemaFactorySpec`.
- [x] **WS1.16 `SchemaOperation` (S).** Delegate to `SchemaFactory` and make no HTTP calls.
  *Green:* the three schema cases in `TestAndSchemaOperationSpec`.
- [x] **WS1.17 WS1 review pass (S).** Check that every exception message names the type and property involved, that no message contains configuration secrets, and that the §2.3 map still matches the code.
  *Done 2026-11-21.* Configuration messages name `objectTypes.<type>.<field>` or `<type>.<property>`; the convention collects every violation into one exception. No message carries `apiKey`'s value. The review found one leak path: a `serviceAddress` with credentials in it (`https://user:pass@host`) would have been echoed. It is now rejected, and an address containing `@` is never repeated (new `ConnectorConfigSpec` case). Validation warnings (a counterpart type not configured, an entry without hints) are on `ConnectorConfig.warnings` for `ScriptSupport` to log at WARN in WS3. §2.3 updated.

### WS2 — Model, codec and payload

*Starts after WS0.14c.* The task list below is the shape of the work. WS0.14c fixes the exact field names.

- [x] **WS2.01 `Edge` (S).** One relationship with explicit `sourceID`, `targetID` and `type` (D2). Validate that both ends and the type are non-blank. Provide the read-model mapping from `source`/`target`/`type`/`sourceType`.
  *Green:* `EdgeSpec` as revised.
- [x] **WS2.02 `EntityDocument` (M).** Three models:
  - the write model (EntityDTO: `id`, `baseType`, `attributes` as string to string, `relationships`, `directArchetypes`), always emitting the two collections so a write is explicit (P15);
  - the read model (Entity: `id`, `baseType`, `Attributes` as `{name, type, value}`, `compoundAttributes`, `relationships`, `lastEvaluated`, `reconciliationStarted`);
  - the search item (`uid`, `name`, `baseType` as a name, `attributes`, `isArchetypes`, `directArchetypes`, `lastEvaluated`).
  *Green:* `EntityDocumentSpec` as revised.
- [x] **WS2.03 `EdgeCodec.serialise` and `serialiseAll` (S).** Canonical JSON in the D2 format, no whitespace, sorted.
  *Gotcha:* Groovy's `JsonOutput` escapes non-ASCII as `\uXXXX`. Prefer a `JsonGenerator` with unicode escaping disabled.
- [x] **WS2.04 `EdgeCodec.parse` (M).** Reject malformed input with `InvalidAttributeValueException` naming the offending value.
  *Gotcha:* `JsonSlurper` can be lenient about trailing characters. Add an end-of-input check if needed.
- [x] **WS2.05 `EdgeCodec.parseAll` (S).** Null or empty gives an empty list. Remove duplicates, keeping first-seen order.
  *Green:* `EdgeCodecSpec`.
- [x] **WS2.06 `EntityPayloadBuilder.idForCreate` (S).** Prefer `__NAME__`, fall back to `__UID__`, otherwise throw `InvalidAttributeValueException`.
- [x] **WS2.07 `EntityPayloadBuilder.build` (M).** Build the write model:
  - `id` from the uid (A3) and `baseType` from the type's kind (D5);
  - configured attributes as strings (D7);
  - `directArchetypes` as the static archetypes plus delivered ones (D9);
  - `relationships` from `entityRelationships`, complete (P15).
  Ignore read-only attributes such as `lastEvaluated` rather than rejecting them.
  *Green:* `EntityPayloadBuilderSpec`.
- [x] **WS2.08 `EntityDecoder.decode` (M).** Decode both the search item and the read model into a `ConnectorObject`: `__UID__` and `__NAME__` from `uid` or `id`, configured attributes, and archetypes. Include `entityRelationships` only from the read model; search items carry none (D4, deferred to WS10).
  *Green:* `EntityDecoderSpec`.
- [x] **WS2.09 Review (S).** `build` and `decode` stay pure functions with no API, log or clock dependencies.
  *Done 2026-11-21.* `build`, `decode` and the codec take only their inputs; no API, log or clock. Notes on what was built:
  - `EdgeCodec.parse` rejects text after the JSON object with its own brace scan, because `JsonSlurper` stops at the first value. Serialisation uses a `JsonGenerator` with unicode escaping off, so `ü` stays `ü`. Messages name the offending value, cut to 200 characters.
  - `EntityPayloadBuilder.build` also rejects a `__UID__` that disagrees with the id, as it does `__NAME__` (A3). An `entityRelationships` attribute that is absent builds an empty list; refusing an update that omits it is `UpdateOperation`'s job (P4, WS3).
  - `EntityDocument` keeps `""` values as read; `EntityDecoder` drops them (P20 amended). A numeric `baseType` the connector does not know is kept as the number; an unknown name reads as null.
  - `EntityDecoder` emits `directArchetypes` from a search item's `isArchetypes` (C9), otherwise from `directArchetypes` when the document carries it. As C9 implies, for a search item this includes archetypes assigned by policy, so a mapping that writes `directArchetypes` straight back would assign those directly. WS7's mappings must take that into account.

### WS3 — Transport, error mapping and write operations

- [x] **WS3.01 Verify the Scripted REST harness contract for toolkit 1.5.20.x (M). Do this first.**
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
  *Done 2026-11-21* from Ping's ICF documentation (the vendor host was allowed for this session; the bundle's samples are on customer-only Stash). Results in `docs/service-contract.md` §4:
  - **Bindings.** `connection` is the Apache `HttpClient` built from `init`; `customizedConnection` is what `decorate` returns. The scripts take `ScriptSupport.connectionOf(binding)`, which prefers `customizedConnection`, and `ScriptSupport` wraps a bare `HttpClient` in a `RESTClient` itself, so the key header from `init` is sent either way.
  - **Customizer.** `init`'s configuration is its closure delegate. `decorate` wraps the built client with `HTTPBuilder.setClient` and returns non-2xx rather than throwing. The work is in `ops.ClientCustomizer`.
  - **Create** receives `id` (the `__NAME__`), restored into the attributes when absent. **Search** may return a `SearchResult` or a cookie string. **Update** receives `operation`.
  - **Schema: a real defect fixed.** The script receives `builder` (an `ICFObjectBuilder`), must call `builder.schema { }` and returns nothing; there is no `connectorClass` binding, which the previous script read. `schema.SchemaDsl` hands `SchemaFactory`'s schema to the DSL and disables Sync, Authenticate and ResolveUsername per class.
  - **Still assumed, with fallbacks:** `decorate`'s argument type and `disable`'s parameter type. Confirm both on a real IDM (WS8).
  - `ScriptHarness` now runs the real `CustomizerScript` as the harness does and binds both clients; `FakeSchemaBuilder` stands in for the DSL.
  *Note:* `docs.pingidentity.com` was outside the network allow-list of the session that wrote this plan, so the vendor page could not be read in full.
- [x] **WS3.02 `HttpErrorMapper.raise` (M).** Implement the §13 table exactly as `HttpErrorMapperSpec` encodes it. Messages read like "HTTP 400 during CREATE: <redacted body>". A 429 message carries the `Retry-After` value. A tolerated 404 returns true.
  *Gotcha:* `CreateOperationSpec` expects the 400 body to appear in an ERROR log line, but `raise` has no logger. Either the operations log before calling `raise`, or `raise` takes a `Log`. Choose one approach for all operations.
- [x] **WS3.03 `HttpErrorMapper.retryAfterSeconds` (S).** Accept non-negative delta-seconds, or an RFC 1123 HTTP-date converted to seconds from now and clamped at zero. Anything else returns null. Header lookup ignores case.
- [x] **WS3.04 `HttpErrorMapper.redact` (S).** Mask `X-API-KEY` values as a header, JSON field or query parameter, in any case, along with `apiKey` and `api_key` fields, `Authorization` values (Basic and Bearer) and JSON `password` fields. Leave other text untouched and accept null. Separately, `raise` must strip the configured key's literal value from any message, because the service may echo the key without a recognisable label, and one leaked key exposes every consumer (P13).
  *Green:* `HttpErrorMapperSpec`. *Done 2026-11-21.* `raise` takes no logger: the operations log a 400's redacted body at ERROR themselves, through `HttpErrorMapper.message`. Redaction covers JSON fields, headers and query parameters; `raise` also strips the configured key's literal value.
- [x] **WS3.05 `RestClientEntityApi` (L).** Implement the five calls over `RESTClient`. `search` is a POST to `SEARCH_PATH` with the fields as a JSON body. An empty map sends `{}` (P14). Convert responses to `HttpResponse`, carrying the status, the parsed body (Map or List, or null for a non-JSON body), the raw body and the headers. Never throw for a non-2xx status. Let transport failures propagate as `IOException`.
  *Gotcha:* `RESTClient` parses bodies by content type and consumes the stream, so the raw body is lost unless you take control. Request text and parse JSON with `JsonSlurper` yourself, or register a buffering parser.
  *Gotcha:* the path encoding. `RESTClient` and `URIBuilder` can re-encode `%` as `%25`. The spec requires `GET` of `a/b` to hit `/admin/entities/a%2Fb` exactly, so build the request URI from the already-encoded segment.
  *Gotcha:* `URLEncoder` encodes a space as `+`, but a path segment needs `%20`.
  *Green:* `RestClientEntityApiSpec`. *Done 2026-11-21.* Every request asks HTTP Builder for text and parses JSON itself, with one handler for success and failure, and the request URI is built from the already-encoded path. `EntityApi` now declares `throws IOException`, as its documentation said, so transport failures are not wrapped in `UndeclaredThrowableException`.
- [x] **WS3.06 `ScriptSupport` (M).**
  - `propertiesFrom` converts the `serviceAddress` URI to a string. It reads the `GuardedString` `password`, which holds the API key (P13), through `access()`, returns it as `apiKey` and does not mutate the configuration. It never returns `password` or `username`.
  - It copies the property bag keys. When the bag lacks `objectTypes`, it parses `customConfiguration` with `ConfigSlurper`.
  - `contextFrom` builds the `OperationContext`. A null connection is allowed for Schema.
  - It logs the config's validation warnings at WARN.
  - Cache the parsed `ConnectorConfig`, keyed by a hash of the `objectTypes` text. Recon calls the scripts thousands of times, and re-parsing and re-validating each time is waste.
  *Green:* `ScriptSupportSpec`. *Done 2026-11-21.* The cache is keyed by a SHA-256 of the whole property set, so it never holds the key readably; warnings are logged once, when a configuration is first built.
- [x] **WS3.07 `EdgeVerifier` (S).** Edges use the P17 shape. It depends and on C1 (GET by ID) to be useful against the real service. If GET stays broken, verification logs a WARN and the create still succeeds (P11). `dropped` compares as sets, treating null as empty. `warnMessage` uses `-T->` for outbound and `<-T-` for inbound edges, with IDs and type only. *As built (2026-11-21), following `EdgeVerifierSpec`:* always `source -T-> target`, which reads the same whichever end is written.
  *Green:* `EdgeVerifierSpec`.
- [x] **WS3.08 `CreateOperation` (M).** Create is `POST /admin/entities` with an EntityDTO body (`id`, `baseType`, `attributes`, `relationships`, `directArchetypes`), carrying the full relationship set (P15).
  1. Resolve the type, get the id from `idForCreate`, and build the document. Malformed input fails here, before any HTTP call.
  2. `POST`, then map the outcome, logging a 400 body at ERROR.
  3. Run the verification `GET`. If it fails, log WARN "verification GET failed for <id>" and still return the Uid (P11).
  4. Log each dropped edge at WARN.
  5. If `rePutOnDroppedEdges` is on and edges were dropped, `PUT` the same document.
  6. Convert any `IOException` to `ConnectorIOException`.
  7. Return `new Uid(id)`.
  Never log attribute values above `OK`.
  *Green:* `CreateOperationSpec`. *Done 2026-11-21.* Verification is skipped when `byIdLookup` is `search` or there are no relationships. A failed re-PUT in aggressive mode is a WARN; it does not undo the create.
- [x] **WS3.09 `UpdateOperation` (M).** The service removes every relationship missing from the collection (P15), so the P4 guard is essential, not defensive. Resolve the type. Require `entityRelationships` and `directArchetypes` to be present (P4), or throw `InvalidAttributeValueException` naming the missing one. Build from `uid.uidValue`, `PUT`, and map the outcome. Never `GET`.
  *Green:* `UpdateOperationSpec`.
  *Spec correction (2026-11-21):* the first case expected `attributes` without `ID` and without the `""` values; it now expects every configured attribute, `""` when empty, and `attributes.ID` (C8 and P20 amended, both confirmed). `CreateOperationSpec`'s account case gained `attributes.ID` for the same reason.
- [x] **WS3.10 Reject partial-value updates in `UpdateScript` (S). New test needed.** The Groovy toolkit routes `ADD_ATTRIBUTE_VALUES` and `REMOVE_ATTRIBUTE_VALUES` through the update script with a different `operation` binding. Those carry deltas, not the full state, so turning them into a `PUT` would delete edges. Throw `UnsupportedOperationException` for them, and add an `OperationScriptsSpec` case for each.
- [x] **WS3.11 `DeleteOperation` (S).** `DELETE`, then map the outcome. The definition documents 404 for a missing entity, but the probe saw 200 (C3); either way the outcome is "gone". A tolerated 404 logs INFO with the id. Never `GET`.
  *Green:* `DeleteOperationSpec`.
- [x] **WS3.12 `TestOperation` (S).** Run a search POST with `pageSize: 1` using `PageCursor`'s first page, then map with `TEST` context. Convert `IOException` to `ConnectorIOException`.
  *Green:* the test cases in `TestAndSchemaOperationSpec`. *Done 2026-11-21.* The search sends `{pageSize: 1}` directly, because `PageCursor` is WS4's. Archetype names are collected from any `subjectArchetypes`/`resourceArchetypes` list, as strings or `{name}` objects, in the list or the export bundle (C11). *Spec correction:* `service.entities.empty` read a map key named `empty`; it is now `isEmpty()`.
- [x] **WS3.13 `CustomizerScript` (M).**
  - `init` reads the API key from the `GuardedString` `password` property and adds it, raw, as a default `X-API-KEY` header, or under the `apiKeyHeader` override from the property bag. It never sends an `Authorization` header (P13).
  - It sets connect, socket and connection-request timeouts on a `RequestConfig`. Defaults come from constants and can be overridden through property bag keys `connectTimeoutMs` and `socketTimeoutMs`. Document both keys in the provisioner sample.
  - It uses the system trust store for https, and logs WARN when the address is http on a host other than localhost (§14).
  - `release` stays safe to call.
  - Add `decorate` as decided in WS3.01.
  *Green:* `CustomizerScriptSpec`.
- [x] **WS3.14 Scripts end to end through WireMock (S).** The create, update, delete and test cases of `OperationScriptsSpec` pass, including the new WS3.10 cases.
- [x] **WS3.15 Logging review (S).** Grep every `log.` call. Above `OK` level, a message may contain IDs, edge shapes, statuses and redacted bodies, never attribute values or credentials (§13, §14).
  *Done 2026-11-21.* Every message above `OK` carries IDs, type and archetype names, edges, statuses, exception class names or a redacted, key-stripped body. ICF's `Log` treats its first argument as a `MessageFormat` pattern, so a JSON body (braces) threw and a quoted ID lost its quotes; every call now passes the message as the argument to `'{0}'` (`ops.Logs`).

### WS4 — Search, scoping and paging

*Starts after WS3 and WS0.14d.* Search is `POST /admin/entities/search` (P14).

- [x] **WS4.01 `PageCursor` encode and decode (S).** The ICF cookie wraps the service's opaque `pagedResultsCookie`. A blank ICF cookie is the first page. A corrupt one throws `IllegalArgumentException`; never restart from the first page silently.
- [x] **WS4.02 `PageCursor.toQuery` (S).** Produce `{pageSize, pagedResultsCookie}` request-body fields; numbers stay numbers.
- [x] **WS4.03 `PageCursor.next` (S).** The next page exists when the response carries a non-null `pagedResultsCookie` (`hasMore` true). `totalResults` of −1 means unknown.
  *Green:* `PageCursorSpec` as revised. *Done 2026-11-21.* The ICF cookie is URL-safe base64 of `{"c": <service cookie>, "n": <results before it>}`. `n` lets a paged search report the remaining count against `totalResults`, which the opaque service cookie cannot. `next` also stops on `hasMore: false` and on an empty page, so a cookie on an empty page cannot loop.
- [x] **WS4.04 `SearchPlanner.plan` (M).**
  - No filter: enumerate the type's `objectClass`.
  - `EqualsFilter` on `__UID__` or `__NAME__`: read by ID.
  - Equals, contains, starts-with, and, or and not on configured attributes: translate into the service's filter tree, if D8 approves them.
  - Anything else: `UnsupportedOperationException` with "filter" in the message (P7).
  - Page size from options, capped, defaulting to `pageSizeDefault` (the service's default is 100).
  *Green:* `SearchPlannerSpec` as revised. *Done 2026-11-21.* D8 approved the filters (P21). AND and OR with more than two operands fold into a nested tree; NOT toggles `not`. Only a top-level equals on `__UID__`/`__NAME__` reads by ID.
- [x] **WS4.05 `TypeScoper` (S).** Scope by `objectClass` in the request, then by the type's archetypes in the results (D6).
  *Green:* `TypeScoperSpec`. *Done 2026-11-21.* Enumeration matches the base type, then the scope archetype in `isArchetypes` or `directArchetypes` (P19 amended); a read by ID checks the base type only.
- [x] **WS4.06 `SearchOperation` by ID (S).** GET; a 404 is an empty result. Decode the read model, check scope, call the handler.
  *Gotcha:* GET returned 500 in the first real run (C1). Map a 500 to `ConnectorIOException`, never to "not found".
- [x] **WS4.07 `SearchOperation` enumerate (L).** Loop over pages with the cookie. Decode each search item, skip undecodable ones with one WARN and no attribute values, scope, and call the handler, stopping when it returns false. With a caller page size, return one page with the ICF cookie and remaining count. Never buffer across pages.
  *Gotcha:* the toolkit's `handler` may be a `Closure` or a `ResultsHandler`. Accept both.
  *Green:* `SearchOperationSpec`, the search cases of `OperationScriptsSpec`, and **`WorkedExampleSpec` (milestone M3)**. *Done 2026-11-21.*
  - By ID: GET, or with `byIdLookup: search` an `EQUALS` on `ID` whose result must match the id exactly. A 404 is an empty result; a 500 is `ConnectorIOException` (C1).
  - Enumeration holds one page at a time. Items repeated within a page (C14) are delivered once. A service that returns the same cookie twice stops the loop with a WARN rather than spinning.
  - Only an explicit `false` from the handler stops the search, so a handler closure that returns nothing does not end it early.
- [x] **WS4.08 Reconciliation without relationships (S).** Search items carry no relationships, so IDM sees `entityRelationships` as changed on every reconciliation and re-sends it. The write is idempotent (P15), so this is correct but costly. Document it, and decide with WS8.11's measurements whether to stop returning `entityRelationships` from search. The real fix is WS10.
  *Documented 2026-11-21.* As built, an enumerated object has no `entityRelationships` attribute at all (absent, not empty), while a read by ID has it. In a reconciliation, IDM compares the mapped target value with the absent one and updates every linked entity whose relationships are non-empty, restating the same relationships; the service treats a restatement as a no-op (owner, 2026-11-21). Costs: one PUT per such entity per reconciliation, and the PUT also restates attributes and archetypes. Options for WS8.11 to measure: (a) keep it; (b) give the mapping a condition that skips `entityRelationships` during reconciliation (relationship changes then flow only through implicit sync); (c) return `entityRelationships` from search by reading each entity by ID, an extra GET per entity that C1 makes unreliable today. The decision waits for WS8.11's numbers; the real fix (comparing relationships) is WS10.
  *Decided 2026-11-21 from WS8.11 (50,000 users):* option (a). A steady-state reconciliation cost about the same as the initial load; see WS8.11.
- [x] **WS4.09 Contract spec from real recordings (M).** Replay the WS0.11 recordings through `RestClientEntityApi` and the decoders. If the service drifts from the fake, this spec fails first.
  *Done 2026-11-21:* `RecordedContractSpec`. Through WireMock and the real HTTP client: the create body the connector builds matches, byte for byte as JSON, the body the service accepted; the recorded read model decodes with its relationship; the recorded search page is scoped and paged; the recorded 400 maps to `InvalidAttributeValueException` with the service's message; and the Test operation reads archetype names from the recorded export bundle.

### WS5 — Mapping-side payload library

The library must stay ES5, and its §7 rules must match the Groovy ones exactly.

- [x] **WS5.01 One source for convention cases (S).** Move the normalisation, passive-detection and pair cases into `fixtures/convention-cases.json`, read by both `RelationshipConventionSpec` and the JavaScript tests.
- [x] **WS5.02 `normalisePropertyName`, `isPassive`, `resolvePair` (S).** Port WS1.07–WS1.09. Can start now.
- [x] **WS5.03 `resolveRelationship` (M).** Override first, then the convention with the entry's hint, then `config/managed`. `sync: false` returns null. Unresolvable throws, naming the property. Can start now.
- [x] **WS5.04 `loadTypeConfig`, `clearCache` and the cache (M).** Read the provisioner configuration, take `objectTypes` from `configurationProperties` or from `customConfiguration`, and cache with an optional TTL (default five minutes).
- [x] **WS5.05 `buildRelationships` (L).** Emit the complete set of relationships touching the source entity in the P17 format, `{"sourceID","targetID","type"}`. The §7 convention decides which IDM side is the source. Inbound relationships are included, per Q07.8.
  *Gotcha:* some IDM versions return only `_ref`; fall back to splitting it.
  *Gotcha:* one read per related object. Cache within a call; WS8.11 evaluates field expansion.
- [x] **WS5.06 `buildArchetypes` (S).** Static archetypes, then a single or multi-valued `archetypeProperty`, without duplicates.
  *Green for WS5.02–WS5.06:* `npm test` in `mapping-lib`.
- [x] **WS5.07 Rhino smoke test (M).** A Gradle test that evaluates the library under Mozilla Rhino 1.7.x with a fake `openidm`.
- [x] **WS5.08 Library README (S).** How mappings `require` it, its functions, ES5 limits, and the cache.
  *Done 2026-11-21 (WS5.01–08).* Notes on what was built:
  - **WS5.01.** `fixtures/convention-cases.json` holds 40 §7 cases (normalise, rejects, passive, resolved and unresolved pairs). `RelationshipConventionSpec` and the Node suite each run all of them, so the Groovy and JavaScript conventions cannot drift apart. The existing per-language cases stay.
  - **`buildRelationships`.** A property listed in `objectTypes` is always processed. An unlisted one is resolved by convention only when one of its references points at a configured type; others (IDM's `roles`, for example) are ignored at debug rather than failing the mapping. Related objects are read once per call.
  - **IDM's collections.** Inside IDM, `openidm.read` returns wrapped Java lists, for which `Array.isArray` is false; the library recognises lists by `length` and reads them by index. WS8.08 confirms this against real IDM wrappers.
  - **`loadTypeConfig`** reads `configurationProperties.objectTypes` (string or object) or the `objectTypes='…'` entry of `customConfiguration`, undoing Groovy string escapes. The cache, managed.json included, lasts five minutes unless `options.ttlMs` says otherwise.
  - **Test harness correction.** Objects created inside Node's `vm` context carry that context's prototypes, so `node:assert`'s strict `deepEqual` called equal structures "not reference-equal". `harness.js` now hands results back through JSON into the test realm; no expectation changed.
  - **WS5.07.** `EntitlementsAcceleratorPayloadRhinoSpec` evaluates the library under Rhino 1.7.14 in ES5 mode (language version 1.8, interpreted) with a JavaScript fake `openidm`. It checks the exports, configuration loading, Appendix A from both ends, archetypes and errors, and that Rhino's §7 answers match `RelationshipConvention`'s.

### WS6 — Discovery and generation tool

- [x] **WS6.01 Make the natural-key signal real (S).** The `indices` key in `managed-sample.json` is a placeholder; real `managed.json` has no such key. Agree with the platform team which real signal marks a natural key, for example a uniqueness policy on the property. Update the sample, `ManagedSchemaReader` and `ManagedSchemaReaderSpec` together.
  *Proposed 2026-11-21, to agree with the platform team:* a property is a natural-key candidate when its `policies` include IDM's `unique` or `valid-username` policy (`userName` carries `valid-username` in IDM's default managed.json). The sample, reader (`NATURAL_KEY_POLICIES`) and spec are updated together; the placeholder `indices` key is gone. Another signal is a one-line change.
- [x] **WS6.02 `ManagedSchemaReader.read` (M).**
  - Require an `objects` array, or throw `IllegalArgumentException`.
  - Domain properties exclude `_*`, `isVirtual`, `isProtected` and `password`, and keep the declared order.
  - Relationships are detected whether declared directly or as array `items`. Each records the reverse name, resource collection paths and whether it is multi-valued.
  - Report natural-key candidates per WS6.01.
  *Gotcha:* the spec relies on property order. Check that `JsonSlurper`'s map preserves insertion order for the parser in use.
  *Green:* `ManagedSchemaReaderSpec`. *Done 2026-11-21.* Object-typed properties are excluded with a reason as well as arrays; virtual properties, including virtual relationships, are dropped silently.
- [x] **WS6.03 `ObjectTypesGenerator.defaultIdProperty` (S).** Use the first natural-key candidate. Otherwise use a `userName`-like name. Otherwise return null, which forces a prompt (A3).
- [x] **WS6.04 `ObjectTypesGenerator.generate` (L).**
  - Only selected types are generated. A selected type missing from `managed.json` is an error.
  - A missing `entityKind` is an error naming the type. A missing `idProperty` with no default is an error.
  - `attributes` are the domain properties minus the `idProperty`.
  - `archetypes` default to the capitalised type name.
  - Relationship entries take `relatedType` from the resource collection. They carry exactly `remoteType`, `direction`, `relatedType` and `reverseProperty`, with `sync` omitted when true.
  - With no reverse property, set `sync: false` and add a warning.
  - Apply overrides verbatim. Otherwise use `resolvePair`. An unresolved pair is an error naming both properties.
  - A related type that wasn't selected gives a warning.
  - Finally run `ObjectTypesParser` and `RelationshipConvention.validate` on the output and append their errors. The output must be deterministic.
  *Green:* `ObjectTypesGeneratorSpec`. *Done 2026-11-21.* A type missing a required answer is left out of the output (its errors stand), so the rest is still validated. An `idProperty` that is not a single-valued property of the type is an error. A relationship that may reference several collections takes `relatedType` from the first, with a warning.
- [x] **WS6.05 `MappingSkeletonGenerator.mapping` (M).** Target-phase situations stay on `REPORT` (P16); promotion to `DELETE` is WS10. Follow the shape of `config/sync-mapping-user.sample.json`:
  - name it `managed<Type>_system<Connector><Type>`;
  - map `__NAME__` from the `idProperty`, and map every attribute directly;
  - bind library transforms with no `condition`;
  - use a JavaScript correlation query on `__NAME__`;
  - give all 13 situations an explicit policy;
  - set `enableSync: true` and no queued sync.
  *Green:* `MappingSkeletonGeneratorSpec`. *Done 2026-11-21.* The correlation query escapes backslashes and quotes in the value, so an ID containing `"` cannot break the filter (checked by evaluating the generated script).
- [x] **WS6.06 `DiffSummary.summarise` (M).** Compare per type, at item level for attribute lists and per property for relationships. Mark lines `+` for added, `-` for removed and `~` for changed, showing old and new values. A null `current` means everything is added.
  *Green:* `DiffSummarySpec`. *Done 2026-11-21.* Archetype order changes are reported too, because the first archetype is the scope archetype (P19).
- [x] **WS6.07 `DiscoveryTool` command line (M). New tests.**
  - Inputs: `--managed <file>`, `--types`, `--answers <answers.json>` (GeneratorOptions as JSON), `--current <provisioner.json>` and `--out <dir>`.
  - Outputs: `objectTypes.json`, the escaped `customConfiguration` string ready to paste into the provisioner, and one `sync-<type>.json` per type.
  - Print the diff. Exit non-zero on errors.
  - Wire it up with Gradle's `application` plugin.
  - Add `DiscoveryToolSpec` using a temporary directory.
  *Done 2026-11-21.* `./gradlew :discovery:run --args="…"` or `installDist`. Outputs `objectTypes.json`, `customConfiguration.txt` (the Groovy value) and `customConfiguration.json` (the same, JSON-escaped for pasting into the provisioner), and `sync-<type>.json`. With `--current`, the provisioner's other `customConfiguration` settings are kept and only `objectTypes` is replaced; the diff is against its objectTypes. Exit 0 on success, 1 on generation or lint errors (nothing written), 2 on usage or input errors. An `answers.json` with an unknown key is refused, so a misspelt answer is not silently ignored. The spec checks the output loads through `ConnectorConfig.from` and passes the lint.
- [x] **WS6.08 Read-only IDM REST source (M). New tests.** Add `--idm-url` and `--idm-user`, with the password from an environment variable, never from an argument. It reads only `config/managed`, with credentials separate from the connector's (§14). Add a WireMock-backed spec.
  *Done 2026-11-21:* `IdmManagedSource`. One GET of `<url>/openidm/config/managed` with `X-OpenIDM-Username`/`X-OpenIDM-Password` and `X-OpenIDM-NoSession`; the password comes from `IDM_DISCOVERY_PASSWORD` and `--idm-password` is refused. No message carries the password. `IdmManagedSourceSpec`.
- [x] **WS6.09 Mapping lint (M). New tests.** This is the code-review check §8.4 and §15 ask for. Add `--lint <sync.json>`, which fails when a mapping targeting `system/entitlementsaccelerator/*` is missing the `entityRelationships` or `directArchetypes` property mapping, or puts a `condition` on either. Run it in CI against every mapping under `config/`.
  *Done 2026-11-21:* `MappingLint`, `--lint`, `MappingLintSpec`, and the Gradle task `:discovery:lintMappings` (part of `check`, and a CI step) over `config/sync*.json`.

### WS7 — Packaging and deployment artefacts

- [x] **WS7.01 Complete `connectorScripts` (S).** Include `entitlementsAcceleratorPayload.js` under `script/lib/`, a `VERSION` file with the git SHA, and a `distZip` task.
  *Done 2026-11-21:* `:connector:idmDist` lays out `connectors/entitlementsaccelerator/{scripts,lib}`, `script/lib/entitlementsAcceleratorPayload.js`, `samples/` and `VERSION` (version, commit, library mode, build time) like an IDM installation; `:connector:distZip` zips it.
- [x] **WS7.02 Confirm the library deployment model (M).** Verify in IDM 7.5 that the provisioner's `classpath` accepts Groovy sources (P9); otherwise package a jar.
  *Done 2026-11-21 (WS8):* it does. A first test suggested otherwise; the failure was the race of H11, and with the right deployment order sources deploy cleanly (`script_vs_jar.md`). Scripts are the default; `-PlibraryAs=jar` builds `lib/entitlementsaccelerator-connector-lib.jar` instead. `compileWithIdmGroovy` compiles the code with IDM's Groovy 3.0.9 in CI, and the nightly integration run covers both modes.
- [x] **WS7.03 Provisioner generation (S).** Generate `customConfiguration` with the WS6.07 tool. Document the API key in `password` and `defaultAuthMethod: NONE` (P13).
  *Done 2026-11-21:* with `--current`, the discovery tool also writes `provisioner.openicf-entitlementsaccelerator.json`, the current provisioner with the new `customConfiguration` and its `objectTypes` left for `createFullConfig`. The key and `defaultAuthMethod: NONE` are in the runbook §2.3.
- [x] **WS7.04 Confirm versions (S).** IDM 7.5's Groovy version and the Scripted REST bundle version.
  *Done 2026-11-21:* Scripted REST 1.5.20.21, Groovy 3.0.9, Rhino 1.7.14, JDK 17.0.10 (H8).
- [ ] **WS7.05 ForgeRock flavour in CI (S).** A CI job with `-PicfFlavour=forgerock`, credentials as secrets.
  *Ready 2026-11-21, not yet run:* a `forgerock-flavour` CI job runs the connector and discovery suites with `-PicfFlavour=forgerock`, the build reading `FORGEROCK_MAVEN_USERNAME`/`PASSWORD` from the environment. Until those repository secrets are set, the job reports that it was skipped.
- [x] **WS7.06 Development IDM container (M).** docker-compose with IDM 7.5 and its repository, mounting the build output.
  *Partly done 2026-11-21:* `integration/…/IdmStack` runs `gcr.io/forgerock-io/idm:7.5.0` (embedded DS repository, so no separate database) with the build output, on the host network. *Done 2026-11-21:* `dev/docker-compose.yml` (IDM 7.5 with `build/idm-dist` mounted read-only), `./gradlew :connector:runFakeService` (the service stand-in on :5276) and `dev/load-config.sh`; checked end to end, Test passing.
- [x] **WS7.07 Smoke check (S).** `system/entitlementsaccelerator?_action=test` succeeds and the schema lists `user` and `account`.
  *Done 2026-11-21:* `ConnectorInIdmSpec`. The provisioner's object types must come from `createFullConfig`, called before the provisioner is saved (H10, H11).
- [x] **WS7.08 Install section of the runbook (S).** §8.1 steps 1–3.
  *Done 2026-11-21:* `docs/runbook.md` §2, including what IDM showed (H9–H13).
- [x] **WS7.09 Deployment guide (M).** One guide from an IDM that has never seen the connector to a configured one, with upgrades, rollback and removal, and a worked example.
  *Done 2026-11-21:* `docs/deployment-guide.md`. Each section says whether it was tested.
  - **Worked example:** `dev/deploy-walkthrough.sh` deploys the release zips into a brand-new IDM 7.5, reconciles, restarts IDM and uninstalls. Its output is the guide's appendix A.
  - **The discovery tool is now a release artefact:** `./gradlew :discovery:distZip`, `entitlementsaccelerator-discovery-<version>.zip`. Operators no longer need a source checkout.
  - **TLS with a private CA, proven in IDM 7.5** (`NetworkInIdmSpec`): a CA imported into IDM's trust store is trusted; an unknown CA and a wrong host name both fail before anything reaches the service. The trust store must stay owned by IDM's user, or IDM does not start.
  - **Clustered and hosted IDM** are written up as untested, with the questions to settle.
- [x] **WS7.10 Outbound proxy (S).** Found by WS7.09: the Scripted REST harness leaves `proxyAddress`, `proxyUsername` and `proxyPassword` to the customizer script, and ours ignored them, so a configured proxy was bypassed.
  *Done 2026-11-21:* `ClientCustomizer.proxy` applies them. The credentials are scoped to the proxy, so they never reach the service. An address carrying credentials is refused without being echoed. Tested by `CustomizerScriptSpec` (+5) and in IDM (`NetworkInIdmSpec`, http). https through a proxy (`CONNECT`) is not tested.

### WS8 — Integration tests against IDM 7.5

- [x] **WS8.01 Integration project (M).** An `integration/` subproject with Spock driving IDM's REST API on the WS7.06 stack, backed by WireMock recordings or a test tenant. Runs on demand and nightly.
  *Done 2026-11-21.* `integration/` (`./gradlew :integration:test -Pidm`). One IDM container per run; the service is `FakeAcceleratorService` in-process behind `FakeAcceleratorHttpServer`, so specs read its state and requests. `IdmStack` deploys as the runbook does and waits on evidence, because IDM applies configuration asynchronously (H14).
- [x] **WS8.02 Managed schema fixture (S).** `managed.json` with `account` and the `owns`/`ownedBy` pair; provisioner and mappings generated by the discovery tool.
  *Done 2026-11-21.* `integration/src/test/resources/idm/managed.json` is IDM 7.5's own managed.json plus `account` and the `owns`/`ownedBy` pair; `answers.json` holds the operator's answers. `objectTypes` and mappings are generated by the discovery tool in-process. Running discovery on the real managed.json led to one discovery fix: relationships to unselected or non-managed types (`roles`, `authzRoles` → `internal/role`, …) are excluded with `sync: false` and a warning instead of being errors.
- [x] **WS8.03 Appendix A through reconciliation (M).** Reconcile users, then accounts. Assert the service's documents and relationships at each step, and the dropped-relationship WARN if the service drops them (WS0.07).
  *Done 2026-11-21.* `AppendixAInIdmSpec`: users reconcile with the dropped-edge WARN, accounts heal it, a second reconciliation is `CONFIRMED` and restates it, and reads through IDM return `entityRelationships` in the P17 shape.
- [x] **WS8.04 Scalar-only update keeps relationships (S).** Change only a name in IDM; assert the update carried the full relationship set (P4, P15) and the relationship survives.
  *Done 2026-11-21.*
- [x] **WS8.05 Relationship removal (S).** Remove a relationship in IDM; assert both entities' writes omit it and the final state has none.
  *Done 2026-11-21.* The changed end's write removes the relationship from the service for both entities. IDM does not re-sync the other end for a change made from this one, even with `notify`/`notifySelf` (H15); harmless, and the other end's next write is consistent.
- [x] **WS8.06 Delete (S).** Delete the account in IDM; assert the delete and the user's relationship-free write.
  *Done 2026-11-21.* **Found a policy defect:** a managed delete raises `SOURCE_MISSING`, and the generated policy was `REPORT`, so deletes never reached the service. Corrected to `SOURCE_MISSING: DELETE`, `MISSING: CREATE` in the generator, the sample and the design (H13).
- [x] **WS8.07 Orphans are reported, not deleted (S).** Seed an entity directly in the service; assert reconciliation reports it and does not delete it (P16).
  *Done 2026-11-21.* An entity seeded directly in the service is reported (`UNASSIGNED`) and never deleted.
- [x] **WS8.08 Library under real Rhino (S).** `require` the library in IDM; record the module cache's actual lifetime.
  *Done 2026-11-21.* `require('lib/entitlementsAcceleratorPayload')` works in IDM's Rhino (`ConnectorInIdmSpec`), and `buildRelationships` builds edges from IDM's real wrapped relationship objects (Appendix A). **Found:** the two library transforms need `"source": ""` (H12). **Module lifetime measured (2026-11-21, `RuntimeInIdmSpec`):** IDM loads a `require`d module afresh for each script evaluation, so the library's cache lasts one evaluation and each transform reads the configuration again (H18). The cost is in the measured times, and configuration changes apply at once.
- [x] **WS8.09 Error paths (M).** 401 on test; 5xx during reconciliation reported against the right object; 429 under queued sync; `deleteMissingTolerated=false`.
  *Done 2026-11-21.* `ErrorPathsInIdmSpec`: a wrong key fails Test without logging it; a 503 on a write during implicit sync is logged against that entity and the next reconciliation repairs it; a DELETE answered 404 is tolerated by default and a 404 error with `deleteMissingTolerated=false` (H17). Under queued sync (`RuntimeInIdmSpec`), updates answered with 429 are retried by IDM until they succeed, each refusal logged with its `Retry-After` hint (H19).
- [x] **WS8.10 Concurrency (M).** Live sync during reconciliation with `taskThreads` > 1; once quiet, the service's state equals IDM's.
  *Done 2026-11-21 at 300 and at 50,000 users* (`ScaleInIdmSpec`, opt-in): at 50,000, 500 implicit syncs racing a 4-thread reconciliation converge to IDM's state, and the reconciliation finishes `SUCCESS`.
- [x] **WS8.11 Volume and write cost (L).** Enumerate 50,000 entities within the reconciliation window with flat memory. Measure `buildRelationships` cost per entity, and the cost of re-sending relationships on every reconciliation (WS4.08).
  *Done 2026-11-21 at 50,000 users and 50,000 accounts* (`-PidmScale=50000 -PidmJavaOpts="-Xmx4g -Xms2g"`; 4 CPUs, 16 GiB; the in-memory service on the same host; `taskThreads: 4`): the initial user reconciliation took 169 s (3.4 ms per user, each building its relationships), the accounts 95 s; a steady-state user reconciliation 163 s with 50,001 PUTs for 50,000 unchanged users (WS4.08); IDM's memory stayed at about 3 GiB through every step. All three specs pass. The stand-in answers in well under a millisecond, so against the real service each reconciliation also costs about one service round trip per entity divided by `taskThreads` (runbook §4); measure it in the dry run.
  *Correction:* the 300-user note recorded 68 ms per user. That figure was mostly the stand-in's own Nagle delay (about 40 ms per exchange, H20), not IDM or the connector; with it removed, 1,000 users took 14 ms per user and 50,000 took 3.4 ms (fixed costs spread thinner).
  *WS4.08 decision, from these numbers:* keep option (a). Restating every linked entity costs about as much as the initial load (163 s against 169 s here), is idempotent and repairs drift as a side effect. Revisit with the dry run's per-PUT latency if the reconciliation window is tight; WS10 removes the cost.
- [x] **WS8.13 End-to-end load test against a real IDM and service (M).** Automate WS8.10/8.11's measurements for any environment: a real IDM, and the real service's test tenant or the stand-in, with checks over HTTP rather than in memory.
  *Done 2026-11-22:* `loadtest/`, `entitlementsaccelerator-loadtest` (`docs/load-testing.md`).
  - **Phases:** seed, initial reconciliation, steady state, implicit sync at a target rate, and cleanup, each timed and checked against the service over HTTP. The tool writes a report and exits 0 or 1.
  - **Guards:** `--allow-writes`, a host allow-list, secrets only from the environment, and `lt-<run>-` IDs for everything it creates.
  - **`--configure`** sets up a disposable IDM, such as a docker-compose stack.
  - **Tests:** `loadtest/src/test` (30), and `LoadTestInIdmSpec` in the integration suite.
  - **Runs:** passed on a brand-new IDM 7.5 with `--configure` (500 users), and again at 2,000 users: about 12 ms per entity per reconciliation, and 40 changes per second asked for, 39.9 achieved.
  - **Findings:**
    - Runbook §3 was wrong. Reconciling accounts first does not avoid the `dropped edge` warnings: either order logs one per relationship on the first load.
    - `runFakeService` had the stand-in's Nagle delay (H20). It now turns it off.
    - The harness's "settled" check assumed IDM logs the configuration-file update before the reactivation; IDM logs it either side. Fixed in both the harness and the tool.
  - **Still to run:** against the real service's test tenant at production size (go-live checklist, item 3).
- [x] **WS8.12 Nightly job (S).** Run nightly, archive results, alert on failure.
  *Done 2026-11-21:* `.github/workflows/integration.yml`, nightly and on demand (with a scale input), uploading reports; failures notify through GitHub's workflow notifications.
  *First GitHub run (run 1, on `main`):* the jar job passed. The sources job failed in `ErrorPathsInIdmSpec`.
  - **Cause:** IDM discards a provisioner save that arrives while it is still applying the previous one (H21), and in scripts mode, on a small runner, applying takes long enough to hit it.
  - **Reproduced** with IDM limited to 1 CPU (`-PidmCpus=1`).
  - **Fixed in the harness:** `IdmStack.putProvisioner` waits for IDM to settle, marks each save, waits for reactivation, checks the save landed and retries. The runbook and the deployment guide tell operators the same.

### WS9 — Operational readiness and rollout (core)

- [x] **WS9.01 Runbook (M).** Onboarding order (§8.1); reading the dropped-relationship WARN; the doubled write when a relationship changes (§9.2) and self-correcting intermediate states (§10); never running reconciliation and live sync together during onboarding; the A1 watch item; what M5 does *not* detect (P16).
  *Drafted 2026-11-21:* `docs/runbook.md` (what M5 does and does not do, install, onboarding order, schedules, monitoring, reading the logs, known issues, key rotation, troubleshooting, dry run, upgrades). Awaits operations review.
- [x] **WS9.02 Reconciliation schedules (S).** Full reconciliation per type, with conservative `taskThreads`.
  *Done 2026-11-21:* `config/schedule-recon-entitlementsaccelerator-{account,user}.json`, nightly, resources first, no concurrent execution; generated mappings carry `taskThreads: 4`. `OperationsInIdmSpec` checks IDM's scheduler accepts them.
- [x] **WS9.03 Monitoring (M).** Dropped-relationship WARN volume, the `ConnectorIOException` rate, and reconciliation REPORT counts per situation.
  *Done 2026-11-21:* runbook §5 and `ops/entitlementsaccelerator_health.py`: the connector Test, the latest reconciliation per mapping (failures, orphans reported), sync failures from the audit, and the connector's WARN counts from IDM's log. Tried against IDM 7.5.
- [x] **WS9.04 Mutable `idProperty` guard (M).** An `onUpdate` mapping snippet that turns an `idProperty` change into delete then create, with an integration case in WS8.
  *Done 2026-11-21:* `onIdPropertyChange` in the mapping library, installed as the managed object's `onUpdate`: it deletes the old entity and IDM's link, and implicit sync re-creates the entity under its new ID with its relationships. Node tests, and `OperationsInIdmSpec` in IDM 7.5 (a renamed user keeps its account relationship; a later reconciliation is clean).
- [ ] **WS9.05 Security review (M).** The shared API key (storage, who can read the configuration, rotation with every consumer), TLS everywhere, the discovery tool's read-only credentials, and a log audit for keys and PII.
  *Pack ready 2026-11-21:* `docs/security-review.md` (controls with their evidence, risks R1–R7, the log audit, decisions requested). The review itself is the security team's.
- [ ] **WS9.06 Non-production dry run (M).** Against the real service with `REPORT` everywhere; review counts with operations.
  *Ready 2026-11-21:* the procedure (runbook §10) and `--report-only` mappings (every situation `REPORT`, implicit sync off). Needs the real service and a non-production IDM.
- [ ] **WS9.07 Go-live decision (S).** Sign-off from operations, security and the design owner, including the explicit decision to go live at M5 or wait for M6 (§10).
  *Ready 2026-11-21:* `docs/go-live-checklist.md`, with the M5-or-M6 decision, twelve prerequisites (including the C17 decision) and the sign-off table.
- [ ] **WS9.08 Handover (S).** Walk support through the runbook and the log signals.
  *Ready 2026-11-21:* the handover agenda in `docs/go-live-checklist.md` §4.

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
./gradlew :connector:test --tests 'idm.entitlementsaccelerator.ops.CreateOperationSpec'

# mapping library
cd mapping-lib && npm test

# contract probe, read-only (everything for the probe goes inside --args)
./gradlew :contract-probe:run --args="--base-url URL --api-key KEY"

# packaging
./gradlew :connector:connectorScripts
```
