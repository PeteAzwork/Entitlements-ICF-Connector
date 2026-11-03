# Design Document — Ping IDM Scripted REST Connector for Graph Entity Service

**Status:** Draft for review
**Connector type:** Groovy Scripted REST (ICF), self-managed Ping IDM (formerly ForgeRock IDM)
**Sync direction:** One-way, IDM → remote graph entity service
**Date:** 2026-07-06

---

## 1. Purpose and scope

This document specifies the design of an ICF connector, built on the Groovy Scripted REST connector toolkit, that synchronises multiple IDM managed object types to a single generic entity endpoint on a remote graph-based service. The remote service models everything as an *Entity* carrying a flat attribute map, a set of directly assigned *archetypes*, a legacy *BaseType* discriminator, and a set of directed *relationships* to other entities.

Synchronisation is strictly one-way: IDM is the authoritative source for all entities and all relationships in the remote service. The remote service's timestamp-based search endpoint is used only for drift detection (target reconciliation), never for LiveSync into IDM.

Out of scope: inbound provisioning from the remote service to IDM, password synchronisation, and any remote-originated relationship types (see the authoritative-source assumption in §3, and the risk register in §15 if that assumption is ever relaxed).

---

## 2. Remote service API contract

The connector consumes the following administrative API. Base URL and credentials are connector configuration; `http://localhost:5276` is used illustratively throughout.

| Operation | Method & path | Notes |
|---|---|---|
| Create entity | `POST /admin/entities/` | Full entity document in body. Relationships referencing a non-existent target/source are **silently dropped** (§9.1). |
| Read entity | `GET /admin/entities/{ID}` | Returns the full document including **all** relationships touching the entity, inbound and outbound. |
| Replace entity | `PUT /admin/entities/{ID}` | **Full replacement.** Any relationship touching this entity — inbound or outbound — that is omitted from the payload is **deleted** by the service. |
| Delete entity | `DELETE /admin/entities/{ID}` | Deletes the entity and all relationships touching it. |
| Search | `GET /admin/entities?updatedAfter={datetime}` *(parameter name to be confirmed)* | Returns full entity documents for entities created or updated after the given datetime. Supports paging. Used for drift detection. |

Authentication is HTTP Basic via headers. Parameter names for the search endpoint and its paging mechanism are placeholders pending API documentation and must be confirmed before implementation (§15).

### 2.1 Entity document shape

```json
{
  "ID": "Sanne",
  "BaseType": 4,
  "attributes": {
    "ID": "Sanne",
    "name": "Sanne Groen"
  },
  "Relationships": [
    { "TargetID": "Current-Account-1-Sanne", "type": "Owns" }
  ],
  "directArchetypes": [ "Customer" ]
}
```

`BaseType` is a legacy typing discriminator: `4` denotes a **Subject**, `2` a **Resource**. The `attributes.ID` field mirrors the top-level `ID` and is populated automatically by the connector.

### 2.2 Edge representation — normative rules

Relationships are directed edges. The wire representation distinguishes direction by which endpoint field is present:

| Field present | Meaning, from the perspective of the entity being read/written |
|---|---|
| `TargetID` | **Outbound** edge: *this entity* → target. |
| `SourceID` | **Inbound** edge: source → *this entity*. |

Both representations are accepted on `POST` and `PUT`: an edge may be asserted from either of its endpoints. On `GET`, an entity's `Relationships` array contains every edge touching it, with inbound edges expressed via `SourceID` and outbound via `TargetID`.

The destructive-omission rule of `PUT` applies to the full set of edges touching the entity, in both directions. **Every `PUT` must therefore carry the complete edge set for that entity.** This single fact drives most of the design in §8.

---

## 3. Established assumptions and design constraints

The following were confirmed during requirements elicitation and are treated as design invariants. Each carries a consequence the design depends on; if any invariant is relaxed, revisit the sections cited.

**A1 — IDM is the sole author of remote relationships.** No relationship exists in the remote service that did not originate from IDM. Consequence: IDM state alone is sufficient to construct the complete edge set for any entity's `PUT`; no read-merge-write against the remote `GET` is required (§8). If third parties ever write edges directly to the service, the connector would silently destroy them on the next sync of either endpoint — see §15 for the defensive fallback.

**A2 — All IDM relationships in scope are bidirectional.** Every relationship property participating in sync has a `reversePropertyName`, so each edge is visible from *both* of its IDM endpoints. Consequence: when entity E syncs, IDM can enumerate not only E's outbound edges but also every inbound edge (via E's reverse relationship properties), satisfying the full-edge-set requirement of `PUT` from E's own IDM record.

**A3 — Identity mapping.** `__UID__` = `__NAME__` = remote `ID`. The IDM property supplying the value differs per managed object type (e.g., `userName` for users, `accountId` for accounts) and is declared per type in connector/mapping configuration (§6). The chosen property should be immutable in practice; see §15 for rename risk.

**A4 — Entity kind.** Each managed object type is configured as either a `subject` (`BaseType` 4) or a `resource` (`BaseType` 2). This is static per type.

**A5 — Relationship type naming convention.** The remote relationship `type` defaults to a normalised form of the IDM relationship property name (e.g., property `owns` → type `Owns`), overridable per property in configuration (§7).

**A6 — Concurrency model.** The remote service offers no ETag / `If-Match`; writes are last-writer-wins. Mitigations in §10.

**A7 — Create-time edge dropping.** `POST` silently drops edges whose other endpoint does not yet exist. Handled in §9.1.

---

## 4. Connector architecture overview

The connector is built on the standard Groovy Connector Toolkit's Scripted REST connector (`org.forgerock.openicf.connectors.scriptedrest-connector`), deployed to self-managed IDM. The scripted REST harness provides an Apache HTTP client configured by a customizer script; each ICF operation is implemented as a Groovy script.

| Script | ICF operation | Responsibility |
|---|---|---|
| `CustomizerScript.groovy` | — | HTTP client construction: base URI, Basic-auth credentials from encrypted configuration, timeouts, TLS. |
| `TestScript.groovy` | Test | Lightweight authenticated call (e.g., search with page size 1) to validate connectivity and credentials. |
| `SchemaScript.groovy` | Schema | Builds one ICF object class per configured entity type from the `objectTypes` configuration (§5, §6). |
| `SearchScript.groovy` | Search / Get | `EqualsFilter` on `__UID__`/`__NAME__` → `GET /admin/entities/{id}`; `GreaterThan` on the timestamp attribute → search endpoint; no filter → full enumeration via search endpoint from epoch, with paging. |
| `CreateScript.groovy` | Create | `POST` full document; post-create edge verification (§9.1). |
| `UpdateScript.groovy` | Update | `PUT` full document with complete edge set (§8, §9.2). |
| `DeleteScript.groovy` | Delete | `DELETE /admin/entities/{id}`; 404 tolerated as already-deleted per policy flag. |

There is deliberately **no SyncScript**: the timestamp search exists for drift detection via reconciliation (§11), not LiveSync, and the sync direction precludes remote-sourced change events.

Two supporting artefacts sit outside the connector proper and are specified in §8 and §12: a shared **payload assembly library** used by IDM mappings, and a **discovery script** that generates per-type configuration and mapping skeletons from `managed.json`.

---

## 5. Object class strategy: single generic class vs. per-type dynamic schema

Two viable patterns exist for exposing "many managed object types → one generic entities endpoint" through ICF. Both were evaluated; the arguments are given in full because the choice shapes configuration, mappings, and reconciliation.

### 5.1 Option A — single generic `entity` object class

The connector exposes exactly one object class. Its schema is the generic entity envelope: `ID`, a JSON-encoded `attributes` blob (or a small set of fixed attributes), `directArchetypes`, `entityRelationships`, `baseType`. Every IDM mapping — `managed/user → system/graph/entity`, `managed/account → system/graph/entity` — targets the same class, and all per-type shaping happens in mapping transformation scripts.

*The case for A.* The connector is maximally simple and genuinely generic: it never changes when a new managed object type is onboarded — only a new mapping is added. Schema drift in IDM cannot break the connector, because the connector has no per-type schema to drift from. The connector codebase stays small and testable, and the remote service's own view of the world (everything is an Entity) is mirrored honestly rather than papered over.

*The case against A.* The costs land in IDM, where they are operationally expensive. First, property-level mapping degrades: if domain attributes travel inside an opaque JSON `attributes` blob, the mapping UI cannot show per-attribute mappings, per-attribute change detection is lost, and every attribute change forces a full-blob rewrite and comparison. Second, target reconciliation degenerates: several mappings share one target object class, so a target-phase recon for the *user* mapping enumerates *every* entity of *every* type, forcing source-correlation filters or archetype-based situational scripting to avoid mass `TARGET-only` false positives — a classic source of accidental deprovisioning. Third, ICF diagnostics (`system/graph/entity?_queryFilter=...`) become type-blind, complicating operations. Fourth, all validation moves into mapping scripts, which are the least testable layer of the stack.

### 5.2 Option B — per-type object classes with configuration-driven dynamic schema

The connector's configuration declares a set of entity types (§6). `SchemaScript.groovy` iterates that configuration and emits one ICF object class per type — `user`, `account`, … — each with its own declared attributes, all mapped by the operation scripts onto the same generic remote endpoint. A discovery script (§12) generates the configuration blocks from IDM's `managed.json`, so per-type schema does not have to be hand-written.

*The case for B.* Each mapping targets its own object class, so property-level mappings, per-attribute delta detection, and the mapping UI all work as designed. Target reconciliation is naturally scoped: the search script filters enumeration results by the type's configured archetype/ID pattern, so a recon of the user mapping sees only user entities, and `TARGET-only` situations mean what they appear to mean. Per-type concerns — `idProperty`, `entityKind`, relationship overrides — have a natural home in the per-class configuration rather than being smuggled through mapping scripts. Operationally, `system/graph/user` and `system/graph/account` are separately queryable and separately reconcilable.

*The case against B.* Configuration is heavier and must be kept in step with `managed.json`: adding a managed object type means adding a configuration block and refreshing the connector so the new schema is read (an IDM connector configuration update triggers this). Schema evolution is a two-step change (managed schema, then connector config), which is a coordination cost — though the discovery script reduces it to regeneration plus review. The operation scripts must be written config-driven from the outset, which is modestly more code than Option A.

### 5.3 Recommendation

**Option B — per-type object classes with configuration-driven dynamic schema, plus the discovery script to generate configuration.**

The decisive considerations are reconciliation scoping and property-level mapping fidelity. Option A's savings are all in the connector — the cheapest, most testable component — while its costs land in reconciliation correctness, where mistakes deprovision the wrong objects. The scenario that settles it: a routine target-phase recon under Option A enumerates all entities of all types, and any correlation misconfiguration in one mapping can act on entities belonging to another type. Option B makes that failure structurally impossible. The onboarding overhead Option B introduces is precisely the overhead the requested discovery tooling (§12) exists to eliminate.

The remainder of this document assumes Option B.

---

## 6. Configuration model

Connector configuration (in `provisioner.openicf-graphentity.json`, `configurationProperties`) carries the base URL, credentials, and a JSON-encoded `objectTypes` structure:

```json
{
  "serviceAddress": "http://localhost:5276",
  "username": "svc-idm-sync",
  "password": {"$crypto": "..."},
  "objectTypes": {
    "user": {
      "idmResource": "managed/user",
      "entityKind": "subject",
      "idProperty": "userName",
      "attributes": ["name", "mail", "telephoneNumber"],
      "archetypes": ["Customer"],
      "archetypeProperty": null,
      "relationships": {
        "owns": { "remoteType": "Owns", "direction": "outbound" }
      }
    },
    "account": {
      "idmResource": "managed/account",
      "entityKind": "resource",
      "idProperty": "accountId",
      "attributes": ["name", "productCode"],
      "archetypes": ["Account"],
      "relationships": {
        "ownedBy": { "remoteType": "Owns", "direction": "inbound" }
      }
    }
  },
  "timestampSearchParam": "updatedAfter",
  "pageSizeDefault": 200,
  "deleteMissingTolerated": true
}
```

Field semantics: `entityKind` maps `subject → BaseType 4`, `resource → BaseType 2`, applied automatically by the create/update scripts. `idProperty` names the IDM property whose value becomes the remote `ID` (and ICF `__UID__`/`__NAME__`), per invariant A3. `attributes` enumerates the domain attributes exposed on the ICF object class and written into the remote `attributes` map. `archetypes` is the static archetype set stamped on every entity of the type; `archetypeProperty`, if set, names an IDM property whose (possibly multivalued) value is appended, supporting dynamic archetypes without a schema change. The `relationships` block is the override mechanism for the naming convention defined next — entries are only required where the convention is insufficient or where explicitness is preferred for auditability. Every relationship property intended for sync **should** appear here after discovery-script generation, with convention-derived values, so the configuration is self-documenting; the convention then serves as the rule the generator applies and the fallback for anything unlisted.

In addition to the configured domain attributes, every object class carries the connector-managed attributes `directArchetypes` (multivalued string), `entityRelationships` (multivalued string; each value a JSON-encoded edge object, see §8.3), and `lastUpdated` (read-only, surfaced from the remote document for drift comparison).

---

## 7. Relationship direction and type convention (normative)

Each in-scope IDM relationship is a bidirectional property pair: a property `P` on one type and its `reversePropertyName` `Q` on the other. The connector must decide, for each side, whether edges seen through that property are outbound (`TargetID`) or inbound (`SourceID`), and what the remote `type` string is.

**Convention.** Normalise a property name by upper-casing its first letter and stripping any `Set`/`List` suffix (e.g., `owns` → `Owns`). Then, for a property pair (`P`, `Q`): the side whose normalised name **equals the remote relationship type** is the **outbound** side; the opposite side — conventionally named as the passive form, e.g., `ownedBy` — is the **inbound** side, and its edges are expressed with `SourceID`. Concretely: `managed/user.owns` ↔ `managed/account.ownedBy` yields, on the user, `{"TargetID": "<account ID>", "type": "Owns"}` and, on the account, `{"SourceID": "<user ID>", "type": "Owns"}`. Note the `type` string is identical from both sides — direction is carried entirely by the choice of endpoint field, never by inventing an inverse type name such as `OwnedBy`.

**Override.** Any property may declare `remoteType` and `direction` explicitly in its `relationships` configuration entry (§6), which takes absolute precedence over the convention. Overrides are required whenever the property pair's names do not follow the active/passive pattern (e.g., `manager`/`reports`), whenever two different property pairs would collide onto the same remote type between the same two types, or whenever a property should be excluded from sync entirely (`"sync": false`).

**Consistency requirement.** For any property pair, exactly one side must resolve to `outbound` and the other to `inbound`, and both must resolve to the same `remoteType`. The discovery script (§12) validates this at generation time and the connector's schema script re-validates at load time, failing fast with a configuration error rather than silently producing asymmetric edges.

---

## 8. Payload construction

### 8.1 The core property and its consequence

A `PUT` must contain every edge touching the entity (§2.2). By invariants A1 and A2, IDM's record of the entity — its forward relationship properties *and* its reverse relationship properties — is exactly that edge set. The connector therefore never needs to `GET` the remote entity and merge unknown edges: **the payload is a pure function of IDM state at sync time.** This eliminates the read-merge-write pattern and, with it, the widest concurrency window (§10). It is the single most important structural decision in this design, and it is entirely dependent on A1/A2 holding.

### 8.2 Where assembly happens: mapping-side, via a shared library

In a self-managed deployment with a local connector, the Groovy operation scripts run inside the ICF layer and do **not** have the `openidm` binding; they cannot conveniently expand the source object's relationship fields themselves. Two placements were considered. The connector scripts could call back into IDM over REST (`http://localhost:8080/openidm/...`) using the same HTTP client — workable, but it couples the connector to IDM's REST surface and a second credential, and it re-reads state that the sync engine already holds. The recommended placement is instead **in the mapping layer**, where `openidm` is available and the sync event already carries the source object: a shared library script (e.g., `script/lib/graphEntityPayload.js`, referenced via `source`/`file` from each mapping) assembles the relationship set and archetypes, and the mapping's property transformations deliver them to the connector as ordinary ICF attributes.

The library exposes, per mapping, `buildRelationships(sourceId, typeConfig)`, which reads the source object with all configured relationship fields (`openidm.read(idmResource + "/" + sourceId, null, ["*", "*_ref"])`, expanding each related object far enough to resolve its **remote ID** via the *related type's* `idProperty` — not the IDM `_id`), applies the direction convention and overrides of §7, and returns an array of `{TargetID|SourceID, type}` objects serialised as JSON strings. The mapping binds this to the target property `entityRelationships`. A companion `buildArchetypes(sourceObject, typeConfig)` merges static and property-derived archetypes. The type configuration consumed by the library is the same `objectTypes` document as the connector's (read once from the provisioner config via `openidm.read("config/provisioner.openicf/graphentity")`), so there is a single source of truth for conventions and overrides.

### 8.3 ICF attribute encoding

ICF's attribute model does not carry structured objects portably, so edges cross the ICF boundary as a **multivalued string attribute** `entityRelationships`, each value a compact JSON object, e.g. `{"TargetID":"Current-Account-1-Sanne","type":"Owns"}`. The create/update scripts parse each value and place the resulting objects into the payload's `Relationships` array verbatim. This keeps the connector agnostic of IDM's relationship model — it receives fully resolved edges — while remaining inspectable in logs and recon diagnostics. `directArchetypes` travels as a plain multivalued string attribute; `BaseType` is injected by the script from `entityKind`; `attributes.ID` is injected to mirror `ID`.

### 8.4 Update payload algorithm (UpdateScript)

Given `uid` and the attribute set delivered by sync, the script resolves the object type's configuration from the object class, then builds the document: `ID` from `uid`; `BaseType` from `entityKind`; `attributes` from the configured attribute list plus the mirrored `ID`; `directArchetypes` from the attribute; `Relationships` from parsing `entityRelationships`. It then issues `PUT /admin/entities/{id}`. Because the payload is total, the operation is idempotent: replaying it converges to the same remote state, which underpins both the concurrency posture (§10) and drift repair (§11). One correctness note for implementers: the mapping **must not** be configured to send partial attribute sets on update — `entityRelationships` and `directArchetypes` must be recomputed and included on *every* update event, even when only a scalar attribute changed, because the `PUT` is destructive to anything omitted. The discovery script emits mappings with this property, and it should be protected by a code-review check rather than left to convention.

---

## 9. Operation designs

### 9.1 Create — and the silently-dropped-edge problem

`CreateScript` issues `POST /admin/entities/` with the same total document as §8.4. Invariant A7 says any edge whose other endpoint is absent is silently dropped, which arises whenever related entities are created out of order — the common case during initial onboarding, where the user mapping may run before the account mapping.

The design leans on a structural property before adding machinery: **the model is self-healing.** Every edge is recorded on both IDM endpoints (A2), every entity's create/update payload carries all edges touching it (§8), and edges are writable from either endpoint (§2.2). So if Sanne is created while `Current-Account-1-Sanne` does not yet exist — dropping the `Owns` edge — the edge is re-asserted the moment the account is created, because the account's own `POST` carries `{"SourceID":"Sanne","type":"Owns"}` and Sanne now exists. Within a full initial reconciliation across all mappings, every edge is asserted at least twice (once per endpoint), and the second assertion always succeeds if the first ran too early. Ordering mappings resource-types-first reduces the number of dropped-then-healed edges but is not required for correctness, and cannot be relied on anyway once cross-type and cyclic relationships exist.

Two residual gaps need handling. First, *visibility*: silent dropping is invisible, and invisible partial failures are how graph stores rot. `CreateScript` therefore performs a verification `GET` immediately after `POST`, compares the returned edge count/set against the submitted set, and logs each dropped edge at `WARN` with both endpoint IDs and the type — cheap (one read per create) and it turns a silent behaviour into an audited one. Second, the *never-healed* case: an edge drops and the other endpoint is *not* subsequently created in the same run (e.g., the related IDM object is out of mapping scope, or its create failed). The verification log identifies these, and the drift-detection recon (§11) repairs them on its next pass, because the source-phase comparison will find the entity's remote `Relationships` differing from the IDM-derived set and trigger a corrective `PUT`. An optional aggressive mode — re-`PUT`-ing the entity at the end of recon if verification found drops — is specified as a post-recon script hook but disabled by default, since the standard heal-plus-drift path covers it with less write amplification.

### 9.2 Update

Per §8.4. One interaction deserves explicit note: when a relationship is removed in IDM, IDM's bidirectional relationship handling fires change events on **both** endpoints. Both will `PUT`, both payloads will omit the edge, and whichever lands first deletes it; the second `PUT` is a harmless idempotent restatement. No special handling is needed — but implementers should expect the doubled write in logs and not mistake it for a defect.

### 9.3 Delete

`DELETE /admin/entities/{id}`. The service removes the entity and all edges touching it, which matches IDM semantics, since IDM will simultaneously remove the relationship references from related objects — those objects then sync and `PUT` payloads that no longer contain the edge, again idempotently consistent with what the delete already did. A `404` on delete is treated as success when `deleteMissingTolerated` is true (the default), since the desired end-state already holds; otherwise it maps to `UnknownUidException`.

### 9.4 Search / Get

`EqualsFilter` on `__UID__` or `__NAME__` translates to `GET /admin/entities/{id}`, mapping `404` to an empty result. A `GreaterThanFilter` (or `GreaterThanOrEqual`) on `lastUpdated` translates to the timestamp search endpoint. An unfiltered search — used by target-phase reconciliation — enumerates via the timestamp search from epoch, following the service's paging protocol and emitting results page-by-page through the ICF result handler with paged-results cookies. Because all types share one remote endpoint, the search script **filters enumeration results to the requesting object class** by matching the entity's `directArchetypes` against the type's configured archetype set (falling back to an ID-pattern predicate if archetypes prove ambiguous across types — flagged as an open item in §15). Returned documents are decomposed back into ICF attributes, including re-serialising `Relationships` into `entityRelationships` values, so recon can compare edge state.

### 9.5 Test and Schema

`TestScript` performs an authenticated minimal search (page size 1) and treats any 2xx as healthy, mapping 401/403 to `InvalidCredentialException`. `SchemaScript` builds object classes from `objectTypes` as specified in §5.2/§6 and performs the §7 consistency validation, refusing to load an asymmetric or colliding relationship configuration.

---

## 10. Concurrency: last-writer-wins analysis and mitigations

The service offers no optimistic concurrency (A6), and every write is a full-state replacement. The exposure is therefore: two writers `PUT` the same entity with payloads assembled from IDM state read at different moments; the later write wins with the staler payload. The design already narrows this considerably — because payloads are pure functions of IDM state (§8.1) rather than read-merge-write compositions, there is no remote-read staleness, only *IDM-read* staleness between assembly and write.

Within that narrowed exposure, the mitigations are, in order of importance. **Convergence by construction:** since every payload restates IDM truth in full, any transiently lost update is restored by the *next* sync event or recon pass touching the entity — the system is eventually consistent with IDM by design, and the drift recon (§11) bounds "eventually". **Per-object serialisation in IDM:** keep the mappings' sync synchronous (the default `onSync` behaviour) so that changes to a single managed object are propagated in order; where high-churn objects make implicit event pile-ups likely, enable IDM's queued synchronization for these mappings, which serialises per-object delivery and provides retry, at the cost of asynchrony. **Bounded recon parallelism:** set recon `taskThreads` conservatively; the correctness risk of parallel recon is low (distinct entities are independent; the same entity is not processed twice within one recon), but running *clique reconciliation and live sync simultaneously* during initial onboarding should be avoided operationally. **The cross-endpoint edge case:** an edge's state is asserted by writes to *either* endpoint, so a stale `PUT` on entity A can momentarily resurrect or remove an edge that a fresh `PUT` on entity B just settled — but both writers derive from the same IDM truth, so once events quiesce, the final writes on both endpoints agree. This is worth documenting for operators because the *intermediate* remote states can look wrong in a way that self-corrects.

What is consciously **not** built: client-side locking or a serialising write proxy. Given one-way sync, full-state idempotent writes, and drift recon as a backstop, the added machinery buys little beyond shrinking a window that convergence already closes.

---

## 11. Drift detection reconciliation

Although IDM is authoritative, drift arises from dropped create-time edges that never healed (§9.1), manual intervention in the remote service, remote-side data loss, and any historical writes predating this connector. Drift detection uses standard IDM reconciliation over the connector, per mapping (one per object type, which Option B makes clean):

The **source phase** iterates IDM objects, reads the corresponding remote entity through the connector, and compares — attribute map, archetypes, and the edge set (comparing the recomputed `entityRelationships` against the remote document's, as sets). Differences yield `CONFIRMED`-with-diff situations whose default action is `UPDATE`, i.e., a corrective full-state `PUT`. Absent remote entities (`SOURCE_MISSING` from the target's perspective — the entity exists in IDM but not remotely) trigger `CREATE`. The **target phase** enumerates the remote service via the unfiltered/epoch search (§9.4), scoped to the type by archetype filtering; entities with no correlated IDM source are `TARGET-only` and the recommended default action is **`REPORT` initially, promoted to `DELETE` per type once the deployment has confidence in correlation** — deleting orphans is the correct steady-state for an authoritative one-way sync, but it should be earned, not assumed on day one.

For efficiency between full recons, a **scheduled incremental drift check** uses the timestamp search directly: a scheduled script queries entities updated since the last check, and for each, compares against IDM and issues corrective `PUT`s (or reports). This catches *remote-side* mutations quickly. Note its blind spot: it cannot see remote *deletions* (a deleted entity does not appear in an updated-after search), so periodic full source-phase recon remains necessary to detect and re-create remotely-deleted entities. Recommended cadence: incremental check hourly, full recon per type daily or weekly depending on volume, tightened during initial onboarding.

---

## 12. Discovery and generation tooling

Option B's configuration burden is met with a discovery script rather than hand-authoring. The script (Groovy or JS, runnable via IDM's script endpoint or as an offline tool against IDM's REST interface) reads `config/managed` and, for each managed object type selected by the operator, generates two artefacts.

First, the **connector `objectTypes` block**: domain attributes from the managed schema (with a proposed inclusion list — relationship properties, virtual properties, and password-class properties excluded by default), a prompted-for `idProperty` (defaulting to any property marked as a natural key or to `userName`-like conventions, but always surfaced for human confirmation given the A3 immutability requirement), a prompted `entityKind`, and a `relationships` block enumerating every relationship property with its `reversePropertyName`, the convention-derived `remoteType` and `direction` per §7, and explicit flags where the convention could not resolve — name pairs that don't follow the active/passive pattern, type collisions, or missing reverse properties (which violate A2 and must be fixed in the managed schema or excluded). The generator's validation is the same consistency check the schema script runs (§7), applied at authoring time where it is cheap to fix.

Second, a **mapping skeleton** per type: a `sync.json` mapping entry from `managed/<type>` to `system/graphentity/<type>` with property mappings for each configured attribute, the `entityRelationships` and `directArchetypes` properties bound to the shared library's `buildRelationships`/`buildArchetypes` transformations (§8.2), correlation on the `idProperty`, and conservative default situational policies (target-phase `REPORT` per §11). Generated artefacts are proposals for review, not deployments — the script writes them to files and prints a diff-style summary, and re-running it against an evolved `managed.json` produces an updated proposal whose diff against the live configuration is the schema-evolution workflow (§5.2's coordination cost, operationalised).

---

## 13. Error handling and ICF exception mapping

Operation scripts map HTTP outcomes to ICF exceptions so that IDM's sync engine and recon situations behave correctly:

| HTTP response | Context | ICF exception / behaviour |
|---|---|---|
| 200 / 201 / 204 | any | Success. |
| 400 | any | `InvalidAttributeValueException`, with response body in the log at `ERROR`. |
| 401 / 403 | any | `InvalidCredentialException` (test) / `ConnectorSecurityException` (operations). |
| 404 | GET / search-by-id | Empty result (recon sees absent target). |
| 404 | PUT | `UnknownUidException` — lets sync retry-policy or recon `ABSENT` handling re-create. |
| 404 | DELETE | Success if `deleteMissingTolerated`, else `UnknownUidException`. |
| 409 | POST | `AlreadyExistsException` — recon can link-and-update instead. |
| 408 / 429 / 5xx / IO | any | `ConnectorIOException` (retryable) — pair with mapping/queued-sync retry configuration; honour `Retry-After` on 429 if present. |

Structured error bodies, if the service provides them, are logged verbatim (minus credentials) but not parsed for control flow until their schema is documented (§15). All logging must treat entity attribute values as potentially personal data and log IDs and edge shapes rather than full attribute maps at levels above `DEBUG`.

---

## 14. Security considerations

Credentials for HTTP Basic are held in the connector configuration using IDM's encrypted property handling and injected by the customizer script; they must never appear in operation scripts or logs. The admin API is powerful (full entity CRUD), so the service account should be scoped to the entities API only, and TLS is required for any non-localhost deployment — the current `http://localhost:5276` posture is acceptable only for co-located development. Basic auth is noted as "at the moment"; the customizer script is the single point of change for a future move to OAuth2 client-credentials or mTLS, and the design intentionally confines auth knowledge there. The discovery script's IDM credentials are separate and read-only (`config/managed` access suffices).

---

## 15. Risks, open issues, and decision log

**Open items requiring service-side confirmation.** The search endpoint's exact parameter names, datetime format, and paging protocol (cursor vs. offset) are placeholders (§2). Whether `409` is actually returned on duplicate `POST`, and the structure of error bodies (§13). Whether archetypes are reliably discriminating for type-scoping enumeration (§9.4) or an ID-pattern fallback is needed.

**Risk: A1 erosion.** If any other system ever writes edges to the remote service, this connector will destroy them on the next sync of either endpoint. The defensive fallback — a read-merge-write mode that `GET`s before `PUT` and preserves edges whose `type` is not in the connector's managed set — is deliberately *not* implemented now (it reintroduces the §10 remote-read race and complicates every write) but is specified here as the designated evolution path, gated on a per-type `managedRelationshipTypes` list. Revisit before admitting any second writer.

**Risk: mutable `idProperty`.** Since remote `ID` is the `PUT` addressing key, renaming the IDM property value orphans the old remote entity and creates a new one on next sync (the old becomes `TARGET-only`, cleaned by recon only once `DELETE` is enabled per §11). Mitigation: choose immutable natural keys per type; where impossible, add an `onUpdate` guard in the mapping that detects `idProperty` change and performs explicit delete-then-create, and document the recon-orphan behaviour for operators.

**Risk: partial-attribute updates.** Any mapping change that stops recomputing `entityRelationships` on every update silently converts routine attribute updates into relationship mass-deletions (§8.4). Mitigation: generator-emitted mappings, code review rule, and the drift recon as detection.

**Decision log.** Scripted REST over custom Java bundle (confirmed requirement). Per-type dynamic schema over single generic class (§5.3). Mapping-side payload assembly over connector callback to IDM (§8.2). Pure IDM-derived full-state `PUT` over read-merge-write (§8.1), contingent on A1/A2. Self-heal plus verification logging over synchronous create-time repair (§9.1). No client-side locking; convergence plus serialisation plus drift recon (§10). Target-phase `REPORT`-then-`DELETE` rollout (§11).

---

## Appendix A — Worked example

Initial recon runs the user mapping first. Sanne is created: `POST` with `{"ID":"Sanne","BaseType":4,"attributes":{"ID":"Sanne","name":"Sanne Groen"},"Relationships":[{"TargetID":"Current-Account-1-Sanne","type":"Owns"}],"directArchetypes":["Customer"]}`. The account does not yet exist; the service silently drops the edge; the create script's verification `GET` sees zero edges against one submitted and logs `WARN dropped edge Sanne -Owns-> Current-Account-1-Sanne`. The account mapping then creates the account: `POST` with `"Relationships":[{"SourceID":"Sanne","type":"Owns"}]` — Sanne exists, the edge is established, and the earlier drop is healed with no further action. Later, Sanne's name changes in IDM: the update event recomputes the full payload (name `Sanne Smith`, the same single edge, same archetypes) and `PUT`s it — idempotent full replacement, edge preserved because it was included. Finally the account is deleted in IDM: `DELETE /admin/entities/Current-Account-1-Sanne` removes entity and edge; IDM's removal of the reverse reference fires an update on Sanne, whose subsequent `PUT` contains no edges — consistent restatement of the new truth.
