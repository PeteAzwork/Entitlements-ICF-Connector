# Design Document — Ping IDM Scripted REST Connector for Graph Entity Service

**Status:** Draft for review
**Connector type:** Groovy Scripted REST (ICF), self-managed Ping IDM (formerly ForgeRock IDM)
**Sync direction:** One-way, IDM → remote graph entity service
**Date:** 2026-07-06; revised 2026-11-21 to the service's OpenAPI definition, the probe runs and the owner's clarifications (decisions P13–P22; `attributes.ID` and archetype reporting, §2.1)

---

## 1. Purpose and scope

This document specifies the design of an ICF connector, built on the Groovy Scripted REST connector toolkit, that synchronises multiple IDM managed object types to a single generic entity endpoint on a remote graph-based service. The remote service models everything as an *Entity* carrying a flat attribute map, a set of directly assigned *archetypes*, a *base type* discriminator, and a set of directed *relationships* to other entities.

Synchronisation is strictly one-way: IDM is the authoritative source for all entities and all relationships in the remote service. The remote service's search endpoint is used for reads, enumeration and (later) drift detection, never for LiveSync into IDM.

Out of scope: inbound provisioning from the remote service to IDM, password synchronisation, and any remote-originated relationship types (see the authoritative-source assumption in §3, and the risk register in §15 if that assumption is ever relaxed).

---

## 2. Remote service API contract

*Revised 2026-11-21. The original section described an assumed API. It now follows the service's OpenAPI definition ("IDP Entitlements API v1") and the first contract-probe run, recorded in `docs/service-contract.md`. Where the definition and observed behaviour disagree, the contradiction is cited as C1–C7 from that document.*

The connector consumes the following administrative API. The base URL and the API key are connector configuration; `http://localhost:5276` is used illustratively throughout.

| Operation | Method & path | Notes |
|---|---|---|
| Create entity | `POST /admin/entities` | Body is the **write model** (§2.1). Returns `200` with no body. Relationships whose other entity does not exist are **silently dropped** (A7, §9.1). |
| Read entity | `GET /admin/entities/{id}` | Returns the **read model** (§2.1), including every relationship touching the entity, inbound and outbound. *C1: returned `500` for every ID the probe tried; reads by ID can fall back to search (§9.4).* |
| Replace entity | `PUT /admin/entities/{id}` | Body is the write model. **Full replacement.** Any relationship touching the entity that is omitted from `relationships` is **deleted** by the service (P15). *C2: returned `200` for a missing entity, where `404` is documented.* |
| Delete entity | `DELETE /admin/entities/{id}` | Deletes the entity and every relationship touching it. Returns `200` with the deleted entity. *C3: returned `200` for a missing entity, where `404` is documented.* |
| Search | `POST /admin/entities/search` | JSON body `{objectClass, filter, pageSize, pagedResultsCookie, attributesToGet, sortBy, sortAscending}`; `{}` returns everything. Returns **search items** (§2.1), paged by an opaque cookie. Never `GET` (P14). |
| Relationship endpoints | `POST /admin/entities/{id}/relationships`, `DELETE /admin/entities/{source}/relationships/{target}[/{type}]` | Add or remove one relationship. **Not used by the connector:** full-state writes through the entity body make them unnecessary (P15). |
| Manifests | `GET /admin/manifests` | The archetype definitions, per base type. Read by the Test operation (§9.5, P22). |

Authentication is by a single API key, shared by all consumers of the service, sent raw in the `X-API-KEY` header. A missing or wrong key returns `401` (P13). Errors are RFC 7807 problem details (`application/problem+json`, with `title`, `status`, `detail`).

**Search request.** `objectClass` scopes the search to one base type, sent as a number (P18). `filter` is a tree of `{operation, left, right, not}` nodes: a leaf compares attribute `left` with value `right` using `EQUALS`, `CONTAINS` or `STARTSWITH`; an `AND` or `OR` node combines its `left` and `right` sub-trees; `not: true` negates the node. Operations are sent by name, as the definition's samples do; the definition types them as an unnamed integer enum 0–9, so other operations wait until the service names them (P21). `pageSize` defaults to 100. `pagedResultsCookie` is the opaque cookie from the previous page.

**Search response.** `{results, pagedResultsCookie, totalResults, hasMore}`. A `null` cookie or `hasMore: false` ends the enumeration.

### 2.1 The three entity shapes

The service uses a different shape for each direction, and the connector must handle all three.

**Write model (EntityDTO)**, the body of `POST` and `PUT`:

```json
{
  "id": "Sanne",
  "baseType": 4,
  "attributes": { "ID": "Sanne", "name": "Sanne Groen" },
  "relationships": [
    { "sourceID": "Sanne", "targetID": "Current-Account-1-Sanne", "type": "Owns" }
  ],
  "directArchetypes": [ "Customer" ]
}
```

**Read model (Entity)**, returned by `GET` and `DELETE`. It carries no archetypes:

```json
{
  "id": "Sanne",
  "lastEvaluated": "2026-01-01T00:00:10Z",
  "reconciliationStarted": null,
  "baseType": 4,
  "Attributes": { "name": { "name": "name", "type": "string", "value": "Sanne Groen" } },
  "compoundAttributes": {},
  "relationships": [
    { "source": "Sanne", "target": "Current-Account-1-Sanne", "type": "Owns", "sourceType": "Subject" }
  ]
}
```

**Search item**, one entry of a search response's `results`. It carries archetypes but **no relationships**, and the base type is a name:

```json
{
  "uid": "Sanne",
  "name": "Sanne",
  "baseType": "Subject",
  "attributes": { "name": "Sanne Groen" },
  "isArchetypes": [ "Customer" ],
  "directArchetypes": [],
  "lastEvaluated": "2026-01-01T00:00:10Z"
}
```

**Base type.** An integer enum. The connector sends **4 for a Subject and 2 for a Resource** (P18, A4) and accepts either a number or a name on reads. *C6: the definition's create samples say the reverse (2 = Subject, 4 = Resource). The realigned probe (WS0.15) must confirm the numbering before anything is created against a shared service.*

**Attributes.** The write model's `attributes` is a map of string to string. The connector exposes single-valued string attributes only, and omits an attribute that has no value in IDM (P20). **By design, the service requires `attributes.ID` equal to `id`** on every create and update. Without it the write fails with 500 (C8, confirmed by the service owner 2026-11-21). The connector always sets it from the entity's ID; it is not a configurable domain attribute. Reads do not return it: neither the read model's `Attributes` nor the search item's `attributes` include `ID`.

**Archetypes.** The writer assigns archetypes through `directArchetypes` in the entity body (P22). The service also assigns archetypes through its own policy rules. **By design, a search item reports an entity's archetypes in `isArchetypes`, including those assigned through `directArchetypes`.** The search item's own `directArchetypes` field comes back empty (C9, confirmed by the service owner 2026-11-21). So `isArchetypes` is the only place a read can see the archetypes the connector wrote. It may also hold archetypes assigned by policy, which the connector does not control.

### 2.2 Relationship representation — normative rules

A relationship is a directed edge with a type. In the write model it names **both** ends explicitly:

```json
{ "sourceID": "Sanne", "targetID": "Current-Account-1-Sanne", "type": "Owns" }
```

The read model reports the same edge as `{"source", "target", "type", "sourceType"}`; the connector converts it to the write shape and drops `sourceType` (P17).

An edge may be written from either of its ends. When Sanne is written, the `Owns` edge appears in her `relationships`; when the account is written, the **identical** object appears in the account's `relationships`. Every relationship in an entity's body must name that entity as its source or its target.

On `GET`, an entity's `relationships` contains every edge touching it, inbound and outbound. The destructive-omission rule of `PUT` applies to the full set of edges touching the entity (P15). **Every write must therefore carry the complete edge set for that entity.** This single fact drives most of the design in §8. *Open (Q07.8): whether a write also removes an edge where the entity is the target and the edge is omitted. The design assumes it does, and sends both directions; that is correct either way.*

## 3. Established assumptions and design constraints

The following were confirmed during requirements elicitation and are treated as design invariants. Each carries a consequence the design depends on; if any invariant is relaxed, revisit the sections cited.

**A1 — IDM is the sole author of remote relationships.** No relationship exists in the remote service that did not originate from IDM. Consequence: IDM state alone is sufficient to construct the complete edge set for any entity's `PUT`; no read-merge-write against the remote `GET` is required (§8). If third parties ever write edges directly to the service, the connector would silently destroy them on the next sync of either endpoint — see §15 for the defensive fallback.

**A2 — All IDM relationships in scope are bidirectional.** Every relationship property participating in sync has a `reversePropertyName`, so each edge is visible from *both* of its IDM endpoints. Consequence: when entity E syncs, IDM can enumerate not only E's outbound edges but also every inbound edge (via E's reverse relationship properties), satisfying the full-edge-set requirement of `PUT` from E's own IDM record.

**A3 — Identity mapping.** `__UID__` = `__NAME__` = remote `id`. The IDM property supplying the value differs per managed object type (e.g., `userName` for users, `accountId` for accounts) and is declared per type in connector/mapping configuration (§6). The chosen property should be immutable in practice; see §15 for rename risk. **Its values must not contain `/`** (P23): the service stores such an ID but cannot address it by path, so the entity could never be updated or deleted (Q09.1). The connector rejects such an ID on create, update and delete with `InvalidAttributeValueException`, before any request.

**A4 — Entity kind.** Each managed object type is configured as either a `subject` (base type 4) or a `resource` (base type 2). This is static per type. *The numbering is subject to confirmation (C6, P18).*

**A5 — Relationship type naming convention.** The remote relationship `type` defaults to a normalised form of the IDM relationship property name (e.g., property `owns` → type `Owns`), overridable per property in configuration (§7).

**A6 — Concurrency model.** The remote service offers no ETag / `If-Match`; writes are last-writer-wins. Mitigations in §10.

**A7 — Create-time edge dropping.** `POST` silently drops edges whose other endpoint does not yet exist. Handled in §9.1. *Assumed from the original brief; WS0.15 re-checks it with the service's real shapes.*

---

## 4. Connector architecture overview

The connector is built on the standard Groovy Connector Toolkit's Scripted REST connector (`org.forgerock.openicf.connectors.scriptedrest-connector`), deployed to self-managed IDM. The scripted REST harness provides an Apache HTTP client configured by a customizer script; each ICF operation is implemented as a Groovy script.

| Script | ICF operation | Responsibility |
|---|---|---|
| `CustomizerScript.groovy` | — | HTTP client construction: base URI, the `X-API-KEY` header from encrypted configuration, timeouts, TLS. |
| `TestScript.groovy` | Test | Authenticated search with page size 1 to validate connectivity and the key, then a manifest check that every configured archetype is defined (§9.5). |
| `SchemaScript.groovy` | Schema | Builds one ICF object class per configured entity type from the `objectTypes` configuration (§5, §6). |
| `SearchScript.groovy` | Search / Get | `EqualsFilter` on `__UID__`/`__NAME__` → read by ID (`GET`, or a search on the ID while C1 persists); attribute filters → the service's filter tree; no filter → enumeration of the type's base type and scope archetype, with cookie paging (§9.4). |
| `CreateScript.groovy` | Create | `POST /admin/entities` with the write model; post-create edge verification (§9.1). |
| `UpdateScript.groovy` | Update | `PUT` the full write model with the complete edge set (§8, §9.2). |
| `DeleteScript.groovy` | Delete | `DELETE /admin/entities/{id}`; 404 tolerated as already-deleted per policy flag. |

There is deliberately **no SyncScript**: the sync direction precludes remote-sourced change events, and the service has no "changed since" search to drive one.

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

Connector configuration (in `provisioner.openicf-graphentity.json`) carries the base URL and the API key as standard Scripted REST properties, and the connector-specific settings in `customConfiguration`, including a JSON-encoded `objectTypes` structure:

```json
{
  "serviceAddress": "http://localhost:5276",
  "password": {"$crypto": "..."},
  "defaultAuthMethod": "NONE",
  "apiKeyHeader": "X-API-KEY",
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
  "pageSizeDefault": 200,
  "byIdLookup": "get",
  "deleteMissingTolerated": true
}
```

Field semantics:

- `password` holds the service's shared API key, encrypted by IDM and sent raw in the `apiKeyHeader` header (default `X-API-KEY`, §14). There is no username, and `defaultAuthMethod` is `NONE` so IDM adds no Basic header (P13).
- `entityKind` maps `subject` → base type 4 and `resource` → base type 2 (P18), applied automatically by the create and update scripts and used as the search `objectClass`.
- `idProperty` names the IDM property whose value becomes the remote `id` (and ICF `__UID__`/`__NAME__`), per invariant A3. Its values must not contain `/` (P23).
- `attributes` enumerates the domain attributes exposed on the ICF object class and written into the remote `attributes` map. Each is a single-valued string (P20); the discovery tool leaves multi-valued IDM properties out.
- `archetypes` is the static archetype set stamped on every entity of the type. Its **first entry is the type's scope archetype** (P19), which tells this type's entities apart from other types sharing its base type. Two types with the same `entityKind` must have different scope archetypes, or the configuration is rejected. `archetypeProperty`, if set, names an IDM property whose (possibly multivalued) value is appended, supporting dynamic archetypes without a schema change. Every archetype must be defined in the service's manifests (§9.5).
- The `relationships` block is the override mechanism for the naming convention defined next. Entries are only required where the convention is insufficient or where explicitness is preferred for auditability. Every relationship property intended for sync **should** appear here after discovery-tool generation, with convention-derived values, so the configuration is self-documenting; the convention then serves as the rule the generator applies and the fallback for anything unlisted.
- `pageSizeDefault` is the search page size when IDM sets none.
- `byIdLookup` chooses how a read by ID is made: `get` uses `GET /admin/entities/{id}`; `search` uses a search with an `EQUALS` filter on the ID, the fallback while GET by ID is unreliable (C1, P21).
- `deleteMissingTolerated` makes a `404` on delete a success (§9.3).

In addition to the configured domain attributes, every object class carries the connector-managed attributes `directArchetypes` (multivalued string), `entityRelationships` (multivalued string; each value a JSON-encoded relationship, see §8.3), and `lastEvaluated` (read-only, the service's last evaluation time of the entity). The names `id`, `ID` and `lastEvaluated` are reserved and cannot be configured as domain attributes.

## 7. Relationship direction and type convention (normative)

Each in-scope IDM relationship is a bidirectional property pair: a property `P` on one type and its `reversePropertyName` `Q` on the other. For each side, the connector must decide whether the IDM object on that side is the relationship's **source** or its **target**, and what the remote `type` string is.

**Convention.** Normalise a property name by upper-casing its first letter and stripping any `Set`/`List` suffix (e.g., `owns` → `Owns`). Then, for a property pair (`P`, `Q`): the side whose normalised name **equals the remote relationship type** is the **outbound** side, and the object on that side is the source. The opposite side — conventionally named as the passive form, e.g., `ownedBy` — is the **inbound** side, and the object on that side is the target. Concretely, `managed/user.owns` ↔ `managed/account.ownedBy` yields, from **both** sides, the same relationship: `{"sourceID": "<user id>", "targetID": "<account id>", "type": "Owns"}`. The `type` string is identical from both sides — direction is carried entirely by which object is the source, never by inventing an inverse type name such as `OwnedBy` (P17).

**Override.** Any property may declare `remoteType` and `direction` explicitly in its `relationships` configuration entry (§6), which takes absolute precedence over the convention. Overrides are required whenever the property pair's names do not follow the active/passive pattern (e.g., `manager`/`reports`), whenever two different property pairs would collide onto the same remote type between the same two types, or whenever a property should be excluded from sync entirely (`"sync": false`).

**Consistency requirement.** For any property pair, exactly one side must resolve to `outbound` and the other to `inbound`, and both must resolve to the same `remoteType`. The discovery tool (§12) validates this at generation time and the connector's schema script re-validates at load time, failing fast with a configuration error rather than silently producing asymmetric edges.

## 8. Payload construction

### 8.1 The core property and its consequence

A `PUT` must contain every edge touching the entity (§2.2, P15). By invariants A1 and A2, IDM's record of the entity — its forward relationship properties *and* its reverse relationship properties — is exactly that edge set. The connector therefore never needs to `GET` the remote entity and merge unknown edges: **the payload is a pure function of IDM state at sync time.** This eliminates the read-merge-write pattern and, with it, the widest concurrency window (§10). It is the single most important structural decision in this design, and it is entirely dependent on A1/A2 holding.

### 8.2 Where assembly happens: mapping-side, via a shared library

In a self-managed deployment with a local connector, the Groovy operation scripts run inside the ICF layer and do **not** have the `openidm` binding; they cannot conveniently expand the source object's relationship fields themselves. Two placements were considered. The connector scripts could call back into IDM over REST (`http://localhost:8080/openidm/...`) using the same HTTP client — workable, but it couples the connector to IDM's REST surface and a second credential, and it re-reads state that the sync engine already holds. The recommended placement is instead **in the mapping layer**, where `openidm` is available and the sync event already carries the source object: a shared library script (e.g., `script/lib/graphEntityPayload.js`, referenced via `source`/`file` from each mapping) assembles the relationship set and archetypes, and the mapping's property transformations deliver them to the connector as ordinary ICF attributes.

The library exposes, per mapping, `buildRelationships(sourceId, typeConfig)`, which reads the source object with all configured relationship fields (`openidm.read(idmResource + "/" + sourceId, null, ["*", "*_ref"])`, expanding each related object far enough to resolve its **remote ID** via the *related type's* `idProperty` — not the IDM `_id`), applies the direction convention and overrides of §7, and returns an array of `{sourceID, targetID, type}` objects serialised as JSON strings — the same string from both ends of a relationship (P17). The mapping binds this to the target property `entityRelationships`. A companion `buildArchetypes(sourceObject, typeConfig)` merges static and property-derived archetypes. The type configuration consumed by the library is the same `objectTypes` document as the connector's (read once from the provisioner config via `openidm.read("config/provisioner.openicf/graphentity")`), so there is a single source of truth for conventions and overrides.

### 8.3 ICF attribute encoding

ICF's attribute model does not carry structured objects portably, so edges cross the ICF boundary as a **multivalued string attribute** `entityRelationships`, each value a compact JSON object in the service's write shape, keys in the order `sourceID`, `targetID`, `type`, with no whitespace, e.g. `{"sourceID":"Sanne","targetID":"Current-Account-1-Sanne","type":"Owns"}` (P17). The create and update scripts parse each value, check that it names the entity being written as its source or target (rejecting it with `InvalidAttributeValueException` otherwise), and place it into the body's `relationships` collection. On reads, the read model's `{source, target, type}` is converted back into the same string, so reconciliation compares like with like. This keeps the connector agnostic of IDM's relationship model — it receives fully resolved edges — while remaining inspectable in logs and recon diagnostics.

`directArchetypes` travels as a plain multivalued string attribute, and the type's scope archetype is added to it if missing (P19). On reads it is filled from the search item's `isArchetypes` (§2.1). The base type is injected by the script from `entityKind`, and `attributes.ID` from the entity's ID (§2.1, C8). Domain attributes are sent as strings; numbers and booleans as their text form; attributes with no value are omitted (P20).

### 8.4 Update payload algorithm (UpdateScript)

Given `uid` and the attribute set delivered by sync, the script resolves the object type's configuration from the object class, then builds the write model: `id` from `uid`; `baseType` from `entityKind`; `attributes` from the configured attribute list, omitting empty values, plus `ID` set to the entity's ID, which the service requires; `directArchetypes` from the attribute, with the scope archetype; `relationships` from parsing `entityRelationships`. It then issues `PUT /admin/entities/{id}`. Because the payload is total, the operation is idempotent: replaying it converges to the same remote state, which underpins both the concurrency posture (§10) and drift repair (§11). One correctness note for implementers: the mapping **must not** be configured to send partial attribute sets on update — `entityRelationships` and `directArchetypes` must be recomputed and included on *every* update event, even when only a scalar attribute changed, because the `PUT` is destructive to anything omitted. The update script refuses an update that lacks either attribute rather than silently deleting every relationship. The discovery tool emits mappings with this property, and it should be protected by a code-review check rather than left to convention.

---

## 9. Operation designs

### 9.1 Create — and the silently-dropped-edge problem

`CreateScript` issues `POST /admin/entities` with the same total write model as §8.4; the service answers `200` with no body, and the script returns the `id` as the `Uid`. Invariant A7 says any edge whose other endpoint is absent is silently dropped, which arises whenever related entities are created out of order — the common case during initial onboarding, where the user mapping may run before the account mapping.

The design leans on a structural property before adding machinery: **the model is self-healing.** Every edge is recorded on both IDM endpoints (A2), every entity's create/update payload carries all edges touching it (§8), and edges are writable from either endpoint (§2.2). So if Sanne is created while `Current-Account-1-Sanne` does not yet exist — dropping the `Owns` edge — the edge is re-asserted the moment the account is created, because the account's own `POST` carries the identical `{"sourceID":"Sanne","targetID":"Current-Account-1-Sanne","type":"Owns"}` and Sanne now exists. Within a full initial reconciliation across all mappings, every edge is asserted at least twice (once per endpoint), and the second assertion always succeeds if the first ran too early. Ordering mappings resource-types-first reduces the number of dropped-then-healed edges but is not required for correctness, and cannot be relied on anyway once cross-type and cyclic relationships exist.

Two residual gaps need handling. First, *visibility*: silent dropping is invisible, and invisible partial failures are how graph stores rot. `CreateScript` therefore performs a verification `GET` immediately after `POST`, compares the read model's relationships against the submitted set, and logs each dropped edge at `WARN` as `dropped edge <source> -<type>-> <target>` — cheap (one read per create) and it turns a silent behaviour into an audited one. The verification is best-effort: if the `GET` fails (as it did in the first probe run, C1), the create still succeeds and the failure is logged at `WARN`. With `byIdLookup: search` there is no verification read, because search items carry no relationships. Second, the *never-healed* case: an edge drops and the other endpoint is *not* subsequently created in the same run (e.g., the related IDM object is out of mapping scope, or its create failed). The verification log identifies these, and the drift detection of §11 will repair them once built. An optional aggressive mode — re-`PUT`-ing the entity when verification found drops — is disabled by default, since the standard heal path covers it with less write amplification.

### 9.2 Update

Per §8.4. One interaction deserves explicit note: when a relationship is removed in IDM, IDM's bidirectional relationship handling fires change events on **both** endpoints. Both will `PUT`, both payloads will omit the edge, and whichever lands first deletes it; the second `PUT` is a harmless idempotent restatement. No special handling is needed — but implementers should expect the doubled write in logs and not mistake it for a defect.

### 9.3 Delete

`DELETE /admin/entities/{id}`. The service removes the entity and all edges touching it, which matches IDM semantics, since IDM will simultaneously remove the relationship references from related objects — those objects then sync and `PUT` payloads that no longer contain the edge, again idempotently consistent with what the delete already did. The `200` response carries the deleted entity, which the connector ignores. A `404` on delete is treated as success when `deleteMissingTolerated` is true (the default), since the desired end-state already holds, and logged at `INFO`; otherwise it maps to `UnknownUidException`. The first probe run saw `200` for a missing entity (C3), which is a success either way.

### 9.4 Search / Get

**By ID.** An `EqualsFilter` on `__UID__` or `__NAME__` is a read by ID. With `byIdLookup: get` it is `GET /admin/entities/{id}`, decoded from the read model, relationships included; a `404` is an empty result, and any `5xx` is a `ConnectorIOException`, never "not found", which would make recon create a duplicate. With `byIdLookup: search` it is a search with the type's `objectClass` and the filter `{"operation":"EQUALS","left":"ID","right":"<id>"}`, decoded from the search item — which carries archetypes but no relationships (P21). A read by ID checks only the base type, not the scope archetype, so IDM can correlate an existing entity that does not yet carry it; the archetype is stamped on its next update (P19).

**Attribute filters.** `EqualsFilter`, `ContainsFilter` and `StartsWithFilter` on configured domain attributes, combined with `AndFilter`, `OrFilter` and `NotFilter`, translate into the service's filter tree (§2). Values are sent as text (P20). Any other filter — including any filter on `entityRelationships`, `directArchetypes` or `lastEvaluated`, and any timestamp comparison — raises `UnsupportedOperationException` before any request. It is never silently turned into a full scan (P7).

**Enumeration.** An unfiltered search — used by target-phase reconciliation — posts the type's `objectClass` and enumerates page by page, following the service's `pagedResultsCookie`. Because several IDM types can share a base type, the connector keeps only results that carry the type's scope archetype (P19). Search reports archetypes assigned through `directArchetypes` in `isArchetypes` (§2.1, C9), so the scope archetype is matched there (P19, amended 2026-11-21). Policy rules can also add to `isArchetypes`. A policy that assigned one type's scope archetype to another type's entities would bring them into that type's scope, so choose scope archetypes that no policy assigns. On reads, the ICF `directArchetypes` attribute is filled from `isArchetypes` (§8.3). Entities without a scope archetype are therefore invisible to enumeration, and never reported as orphans of the wrong type. Scoping happens after paging, so a page can hand fewer objects to the handler than the page size.

**Paging.** When IDM sets a page size, the connector returns one service page per call, with an ICF cookie wrapping the service's cookie, and `remainingPagedResults` derived from `totalResults`. A corrupt ICF cookie is rejected rather than restarting from the first page, which would duplicate results. When IDM sets no page size, every page is streamed through the result handler, and enumeration stops early when the handler returns `false`. A malformed search item (no `uid` or `name`) is skipped with one `WARN` rather than aborting the whole recon.

Returned documents are decomposed back into ICF attributes. Search items carry no relationships, so enumerated objects carry no `entityRelationships`; reading a relationship set needs a read by ID with `GET`. Comparing relationship sets during reconciliation is the drift-detection problem, deferred to WS10 (§11).

### 9.5 Test and Schema

`TestScript` performs an authenticated minimal search (page size 1) and treats any 2xx as healthy, mapping 401/403 to `InvalidCredentialException`. It then reads `GET /admin/manifests` and logs a `WARN` naming any configured archetype that no manifest defines for the type's base type, or saying the service has no manifests at all (P22, amended 2026-11-21). It does not fail the test: the service accepts archetype names no manifest defines, and existing data uses such names (C16). The response is an export bundle, not the documented list (C11), so archetype names are read from any `subjectArchetypes` or `resourceArchetypes` list inside it. The connector never creates or changes manifests. `SchemaScript` builds object classes from `objectTypes` as specified in §5.2/§6 and performs the §7 consistency validation and the scope-archetype rule, refusing to load an asymmetric, colliding or ambiguous configuration. It makes no HTTP calls.

## 10. Concurrency: last-writer-wins analysis and mitigations

The service offers no optimistic concurrency (A6), and every write is a full-state replacement. The exposure is therefore: two writers `PUT` the same entity with payloads assembled from IDM state read at different moments; the later write wins with the staler payload. The design already narrows this considerably — because payloads are pure functions of IDM state (§8.1) rather than read-merge-write compositions, there is no remote-read staleness, only *IDM-read* staleness between assembly and write.

Within that narrowed exposure, the mitigations are, in order of importance. **Convergence by construction:** since every payload restates IDM truth in full, any transiently lost update is restored by the *next* sync event or recon pass touching the entity — the system is eventually consistent with IDM by design, and the drift recon (§11) bounds "eventually". **Per-object serialisation in IDM:** keep the mappings' sync synchronous (the default `onSync` behaviour) so that changes to a single managed object are propagated in order; where high-churn objects make implicit event pile-ups likely, enable IDM's queued synchronization for these mappings, which serialises per-object delivery and provides retry, at the cost of asynchrony. **Bounded recon parallelism:** set recon `taskThreads` conservatively; the correctness risk of parallel recon is low (distinct entities are independent; the same entity is not processed twice within one recon), but running *clique reconciliation and live sync simultaneously* during initial onboarding should be avoided operationally. **The cross-endpoint edge case:** an edge's state is asserted by writes to *either* endpoint, so a stale `PUT` on entity A can momentarily resurrect or remove an edge that a fresh `PUT` on entity B just settled — but both writers derive from the same IDM truth, so once events quiesce, the final writes on both endpoints agree. This is worth documenting for operators because the *intermediate* remote states can look wrong in a way that self-corrects.

What is consciously **not** built: client-side locking or a serialising write proxy. Given one-way sync, full-state idempotent writes, and drift recon as a backstop, the added machinery buys little beyond shrinking a window that convergence already closes.

---

## 11. Drift detection reconciliation

*Deferred, 2026-11-21: drift detection is built last (delivery plan P16, workstream WS10). The service has no "updated since" search and returns no relationships in search results, so this section is rewritten in WS10.02 against the real API.*

Although IDM is authoritative, drift arises from dropped create-time edges that never healed (§9.1), manual intervention in the remote service, remote-side data loss, and any historical writes predating this connector. Drift detection uses standard IDM reconciliation over the connector, per mapping (one per object type, which Option B makes clean):

The **source phase** iterates IDM objects, reads the corresponding remote entity through the connector, and compares — attribute map, archetypes, and the edge set (comparing the recomputed `entityRelationships` against the remote document's, as sets). Differences yield `CONFIRMED`-with-diff situations whose default action is `UPDATE`, i.e., a corrective full-state `PUT`. Absent remote entities (`SOURCE_MISSING` from the target's perspective — the entity exists in IDM but not remotely) trigger `CREATE`. The **target phase** enumerates the remote service via the unfiltered/epoch search (§9.4), scoped to the type by archetype filtering; entities with no correlated IDM source are `TARGET-only` and the recommended default action is **`REPORT` initially, promoted to `DELETE` per type once the deployment has confidence in correlation** — deleting orphans is the correct steady-state for an authoritative one-way sync, but it should be earned, not assumed on day one.

For efficiency between full recons, a **scheduled incremental drift check** uses the timestamp search directly: a scheduled script queries entities updated since the last check, and for each, compares against IDM and issues corrective `PUT`s (or reports). This catches *remote-side* mutations quickly. Note its blind spot: it cannot see remote *deletions* (a deleted entity does not appear in an updated-after search), so periodic full source-phase recon remains necessary to detect and re-create remotely-deleted entities. Recommended cadence: incremental check hourly, full recon per type daily or weekly depending on volume, tightened during initial onboarding.

---

## 12. Discovery and generation tooling

Option B's configuration burden is met with a discovery script rather than hand-authoring. The script (Groovy or JS, runnable via IDM's script endpoint or as an offline tool against IDM's REST interface) reads `config/managed` and, for each managed object type selected by the operator, generates two artefacts.

First, the **connector `objectTypes` block**: domain attributes from the managed schema (with a proposed inclusion list — relationship properties, virtual properties, and password-class properties excluded by default, and multi-valued properties excluded with a warning because the service's attributes are single-valued strings, P20), a prompted-for `idProperty` (defaulting to any property marked as a natural key or to `userName`-like conventions, but always surfaced for human confirmation given the A3 immutability requirement), a prompted `entityKind`, and a `relationships` block enumerating every relationship property with its `reversePropertyName`, the convention-derived `remoteType` and `direction` per §7, and explicit flags where the convention could not resolve — name pairs that don't follow the active/passive pattern, type collisions, or missing reverse properties (which violate A2 and must be fixed in the managed schema or excluded). The generator's validation is the same consistency check the schema script runs (§7), applied at authoring time where it is cheap to fix.

Second, a **mapping skeleton** per type: a `sync.json` mapping entry from `managed/<type>` to `system/graphentity/<type>` with property mappings for each configured attribute, the `entityRelationships` and `directArchetypes` properties bound to the shared library's `buildRelationships`/`buildArchetypes` transformations (§8.2), correlation on the `idProperty`, and conservative default situational policies (target-phase `REPORT` per §11). Generated artefacts are proposals for review, not deployments — the script writes them to files and prints a diff-style summary, and re-running it against an evolved `managed.json` produces an updated proposal whose diff against the live configuration is the schema-evolution workflow (§5.2's coordination cost, operationalised).

---

## 13. Error handling and ICF exception mapping

Operation scripts map HTTP outcomes to ICF exceptions so that IDM's sync engine and recon situations behave correctly:

| HTTP response | Context | ICF exception / behaviour |
|---|---|---|
| 200 / 201 / 204 | any | Success. |
| 400 | any | `InvalidAttributeValueException`, with the problem details in the log at `ERROR`. |
| 401 / 403 | Test | `InvalidCredentialException`. |
| 401 / 403 | other operations | `ConnectorSecurityException`. |
| 404 | GET / search-by-id | Empty result (recon sees absent target). |
| 404 | PUT | `UnknownUidException` — lets sync retry-policy or recon `ABSENT` handling re-create. *C2: the probe saw `200` for a missing entity, which the connector cannot tell from success.* |
| 404 | DELETE | Success if `deleteMissingTolerated`, else `UnknownUidException`. |
| 404 | `GET /admin/manifests` | Test fails: the service defines no archetypes. |
| 409 | POST | `AlreadyExistsException` — recon can link-and-update instead. *Assumed; the probe has not yet seen a duplicate create (Q04.1).* |
| 500 | GET by ID | `ConnectorIOException`, never "not found". *C1: every GET by ID returned 500 in the first probe run; `byIdLookup: search` avoids it.* |
| — | create, update or delete of an ID containing `/` | `InvalidAttributeValueException` before any request (P23): the service cannot address such an ID by path |
| 500 | POST or PUT without an `id`, or without `attributes.ID` equal to it | Treated as any 5xx. *C4, C8: the definition documents 400.* The connector always sends both. |
| 408 / 429 / 5xx / IO | any | `ConnectorIOException` (retryable) — pair with mapping/queued-sync retry configuration; honour `Retry-After` on 429 if present. |

Error bodies are RFC 7807 problem details. Their `title`, `status` and `detail` are logged (minus credentials) but not parsed for control flow. All logging must treat entity attribute values as potentially personal data and log IDs and edge shapes rather than full attribute maps or response bodies at levels above `DEBUG`.

## 14. Security considerations

The service's API key is held in the connector configuration's `password` property, using IDM's encrypted property handling, and the customizer script sends it as the `X-API-KEY` header. IDM's own Basic authentication is switched off (`defaultAuthMethod: NONE`). The key must never appear in operation scripts, logs or error messages. The service uses one constant key for all of its consumers, which shapes the security posture in three ways. First, the key cannot be scoped to this connector, and requests cannot be attributed to it, so a leak from any consumer exposes full entity CRUD. Second, rotating the key needs every consumer at once. Third, any holder of the key could write relationships, which is exactly the second writer that invariant A1 rules out (§3, §15). TLS is therefore required for any non-localhost deployment; the current `http://localhost:5276` posture is acceptable only for co-located development. The customizer script is the single point of change for a future move to per-client keys, OAuth2 client-credentials or mTLS, and the design intentionally confines auth knowledge there. *(Amended 2026-11-21: this section originally specified HTTP Basic credentials scoped to a service account.)* The discovery script's IDM credentials are separate and read-only (`config/managed` access suffices).

---

## 15. Risks, open issues, and decision log

**Open items requiring service-side confirmation.** Tracked in `docs/service-contract.md` and `docs/design-decisions-2026-11-21.md`. Before anything is created against a shared service: that base type 4 is a Subject (C6, P18). Before WS2.07: whether an update removes omitted attributes and replaces `directArchetypes` in full (P20, P22). Before WS5.05: whether an entity's write must also list edges where it is the target (Q07.8). Open with the service team: GET by ID returning 500 (C1), `200` for a missing `PUT` or `DELETE` (C2, C3), the names of the ten filter operations and the attribute name for filtering on the ID (P21), whether a duplicate `POST` returns `409` (Q04.1), and whether the search filter can match an element of `directArchetypes` (P19).

**Risk: A1 erosion.** If any other system ever writes edges to the remote service, this connector will destroy them on the next sync of either endpoint. The defensive fallback — a read-merge-write mode that `GET`s before `PUT` and preserves edges whose `type` is not in the connector's managed set — is deliberately *not* implemented now (it reintroduces the §10 remote-read race and complicates every write) but is specified here as the designated evolution path, gated on a per-type `managedRelationshipTypes` list. Revisit before admitting any second writer.

**Risk: mutable `idProperty`.** Since remote `ID` is the `PUT` addressing key, renaming the IDM property value orphans the old remote entity and creates a new one on next sync (the old becomes `TARGET-only`, cleaned by recon only once `DELETE` is enabled per §11). Mitigation: choose immutable natural keys per type; where impossible, add an `onUpdate` guard in the mapping that detects `idProperty` change and performs explicit delete-then-create, and document the recon-orphan behaviour for operators.

**Risk: partial-attribute updates.** Any mapping change that stops recomputing `entityRelationships` on every update silently converts routine attribute updates into relationship mass-deletions (§8.4). Mitigation: generator-emitted mappings, code review rule, and the drift recon as detection.

**Decision log.** Scripted REST over custom Java bundle (confirmed requirement). Per-type dynamic schema over single generic class (§5.3). Mapping-side payload assembly over connector callback to IDM (§8.2). Pure IDM-derived full-state `PUT` over read-merge-write (§8.1), contingent on A1/A2. Self-heal plus verification logging over synchronous create-time repair (§9.1). No client-side locking; convergence plus serialisation plus drift recon (§10). Target-phase `REPORT`-then-`DELETE` rollout (§11). Shared `X-API-KEY` authentication in place of HTTP Basic, confirmed 2026-11-21 (§2, §14; delivery plan P13). Search by `POST /admin/entities/search` with a JSON body, not `GET` with query parameters, confirmed 2026-11-21 (§2; delivery plan P14). Relationship writes confirmed as full-state through the entity's `relationships` collection, with missing relationships removed by the service, 2026-11-21 (§8; delivery plan P15). Drift detection (§11) deferred to the final delivery workstream, 2026-11-21 (delivery plan P16). Alignment to the real API decided 2026-11-21 (delivery plan P17–P22; `docs/design-decisions-2026-11-21.md`): relationships as `{sourceID, targetID, type}`; base type sent as a number with 4 = Subject; a scope archetype per type; single-valued string attributes; search by ID plus equals, contains and starts-with with and, or and not; archetypes set in the entity body. §2, §6–§9 and §13 were rewritten to match in WS0.14b, the same day. Clarified by the service owner, 2026-11-21: the service requires `attributes.ID` equal to `id` on every write, by design (§2.1, §8.3, §8.4; C8). Search reports archetypes assigned through `directArchetypes` in `isArchetypes`, by design (§2.1; C9), so P19 is amended to scope on `isArchetypes` (§9.4; plan WS0.18). Deletes work (§9.3), after about 0.5 s (C10). **Under review after run `214027` (C17; plan WS0.22):** an update that omits an attribute keeps it, so P20's "empty values are omitted" does not clear an attribute. Relationships stand as designed (§8.1, §8.4) on the service owner's statement that an empty `relationships` list purges all of the entity's relationships. But run `215954` saw no removal within 10 s, and saw a replaced relationship resurface (C17, C18). This is raised with the service team (plan WS0.23). Decided by the owner, 2026-11-21: IDs containing `/` are rejected (A3, §13; P23), and the Test operation only warns about archetypes missing from the manifests (§9.5; P22 amended).

---

## Appendix A — Worked example

*Revised 2026-11-21 to the service's real shapes (P17–P22).*

1. **Initial recon runs the user mapping first.** Sanne is created: `POST /admin/entities` with `{"id":"Sanne","baseType":4,"attributes":{"ID":"Sanne","name":"Sanne Groen"},"relationships":[{"sourceID":"Sanne","targetID":"Current-Account-1-Sanne","type":"Owns"}],"directArchetypes":["Customer"]}`. The service answers `200` with no body. The account does not yet exist, so the service silently drops the edge; the create script's verification `GET` sees no relationships against one submitted and logs `WARN dropped edge Sanne -Owns-> Current-Account-1-Sanne`.
2. **The account mapping creates the account.** `POST` with `"baseType":2`, `"attributes":{"ID":"Current-Account-1-Sanne",…}`, `"directArchetypes":["Account"]` and the **identical** relationship `{"sourceID":"Sanne","targetID":"Current-Account-1-Sanne","type":"Owns"}`. Sanne exists, so the edge is established, and the earlier drop is healed with no further action. A `GET` of either entity now shows `{"source":"Sanne","target":"Current-Account-1-Sanne","type":"Owns","sourceType":"Subject"}`.
3. **Sanne's name changes in IDM.** The update event recomputes the full payload (name `Sanne Smith`, the same single edge, the same archetypes) and `PUT`s it — idempotent full replacement, edge preserved because it was included.
4. **A read of Sanne by ID** decodes the read model back into ICF attributes: `entityRelationships` is `["{\"sourceID\":\"Sanne\",\"targetID\":\"Current-Account-1-Sanne\",\"type\":\"Owns\"}"]`, exactly the string the mapping library computes from either end.
5. **The account is deleted in IDM.** `DELETE /admin/entities/Current-Account-1-Sanne` removes the entity and the edge, and returns the deleted entity. IDM's removal of the reverse reference fires an update on Sanne, whose subsequent `PUT` contains no relationships — a consistent restatement of the new truth.
6. **A remote-side edge removal** (manual intervention in the service) is corrected by the next full-state `PUT` of either entity. Detecting it without waiting for an IDM change is drift detection, built last (§11, WS10).
