# Service Contract — Graph Entity Service Admin API

**Workstream:** WS0 (delivery plan §4 and §12)
**Status:** Open. The service's OpenAPI definition arrived on 2026-11-21 and answers much of the register (section 5). It also shows that the design's model of the API is wrong in several places, so the design needs revising before WS1 (section 6).
**Last updated:** 2026-11-21

This is the record of what the connector may rely on about the remote service. Each question has one of three states:

- **Open:** not yet answered. The code runs on the stated assumption.
- **Confirmed:** observed on a running instance or stated by the service team, with evidence.
- **Agreed assumption:** not verifiable, but the service team has agreed the connector may rely on it. Record who agreed and when.

When a question changes state, apply WS0.12 in the delivery plan: change the code and tests it names, and add a decision-log entry in plan §3.

---

## 1. How to collect the answers

Most questions can be answered by observation with the contract probe in `contract-probe/`. The probe was tested against an in-memory model of the documented semantics; it has not yet been run against the real service.

**Step 1. Read-only run.** This is safe against any environment, because it only reads. It sends GET requests and search POSTs to `/admin/entities/search`, which read, and it refuses a search path that would create entities. If you know the request-body field that filters by last-updated time, add `--timestamp-field NAME`:

```bash
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --api-key KEY"
```

**Step 2. Write run, against a test instance only.** It creates, changes and deletes entities whose IDs start with `ws0-probe-<run>-`, and deletes them all at the end, even after a failure. It never touches any other entity.

```bash
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --api-key KEY --allow-writes"
```

**Output** goes to `build/contract-probe/<run>/`:

| File | Contents |
|---|---|
| `report.md` | Answers grouped by WS0 task, suggested configuration values and cleanup status |
| `findings.json` | The same, machine-readable |
| `exchanges.jsonl` | Every request and response, numbered, so answers can cite evidence such as `#12` |
| `wiremock/mappings/` | Each exchange as a WireMock stub, the raw material for WS0.11 |

**Safe to share.** No output contains request headers or credentials. Every entity the probe did not create is scrubbed: its ID and attribute values are replaced, and its shape (BaseType, archetypes, field names, edge types, timestamp formats) is kept. Tests in `ProbeOutputSpec` enforce this.

**What the probe needs.** The service's API key, given with `--api-key`. That option keeps the key in shell history and shows it in the process list. To avoid that, leave it out and set the `GRAPH_PROBE_API_KEY` environment variable instead, or another variable named with `--api-key-env`. If both are given, `--api-key` wins. The key never appears in any output file. It is sent raw in the `X-API-KEY` header; `--api-key-header` overrides the name. Because every consumer shares one key, the key used for the write run can write anywhere that key is valid. Point `--allow-writes` only at a test instance, and check T11 below before assuming test and production keys differ. There is no option to disable TLS verification.

---

## 2. Question register

"Probe" names the finding key in `report.md` that answers the question. "Assumes" is what the code does today. "Changes" is what to update once the question is answered.

### WS0.02 — Search endpoint

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q02.1 | Which request serves search? | **Answered:** `POST /admin/entities/search` with a JSON body; `{}` is the unfiltered search. `GET /admin/entities` returns 405 | `search.path` | Done 2026-11-21 (P14): `EntityApi.search` takes body fields, plus the fake service, probe and specs | **Confirmed** by the project owner, 2026-11-21. Probe run `20261121-150804` #4 and #5 showed the 405 |
| Q02.2 | What is the request-body field that filters by last-updated time called? | `updatedAfter` (`ConnectorConfig.DEFAULT_TIMESTAMP_PARAM`, configurable) | `search.timestampParam` | Default value, `Fixtures`, `FakeGraphService.timestampParam` | Open |
| Q02.3 | Which datetime formats does it accept, at what precision? | ISO-8601 instant with `Z` (`SearchPlanner`, `SearchPlanner.EPOCH`) | `search.datetimeFormats` | `SearchPlanner`, `SearchPlannerSpec` | Open |
| Q02.4 | Is the filter strictly-after or inclusive? | Undecided. `GreaterThan` and `GreaterThanOrEqual` map identically | `search.boundary` (write run) | `SearchPlanner` (WS4.04) | Open |
| Q02.5 | Does the search return updated entities as well as created ones? | Yes (design §2) | `search.returnsUpdated` (write run) | Design §11 if not | Open |

### WS0.03 — Paging protocol

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q03.1 | What is the response envelope? | Object with `items`, plus optional `totalCount` or `nextCursor` (P6) | `paging.envelope` | `PageCursor.next`, `FakeGraphService.search` | Open |
| Q03.2 | Which request-body field sets the page size? | `pageSize` | `paging.pageSizeParam` | `PageCursor.toQuery`, `PageCursorSpec` | Open |
| Q03.3 | Offset, page number or cursor, and which request-body field? | `offset`, or an opaque `cursor` when the response supplies one | `paging.params` | `PageCursor`, `PageCursorSpec` | Open |
| Q03.4 | What is the maximum page size? | 1000 (`ConnectorConfig.MAX_PAGE_SIZE`) | `paging.maxPageSize` | `MAX_PAGE_SIZE`, `ConnectorConfigSpec` | Open |
| Q03.5 | Is ordering stable across pages, and what is it ordered by? | Stable | `paging.ordering` | Design §9.4 if not | Open |
| Q03.6 | Can an entity updated mid-enumeration be skipped? (Returned twice is harmless.) | Not skipped | `paging.updateDuringEnumeration` (write run) | Risk register §9. A skip would undermine target recon | Open |

### WS0.04 — Duplicate POST

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q04.1 | What does a POST of an existing ID return? | 409, mapped to `AlreadyExistsException` (§13) | `create.duplicate` (write run) | `HttpErrorMapper`. If it upserts, raise a §9 risk and decide on GET-before-POST | Open |

### WS0.05 — Error bodies

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q05.1 | What do 400, 404 and 409 bodies look like? | Unparsed, logged after redaction (§13) | `errors.*` | `HttpErrorMapper.redact` if bodies echo input | Open |
| Q05.2 | Can an error body echo request content, such as attribute values? | Possibly, so redact | `errors.postWithoutId`, `errors.malformedJson` | Logging rules (WS3.15) | Open |
| Q05.3 | What does a PUT to a missing entity return? | 404, mapped to `UnknownUidException` | `errors.putMissing` (write run) | `HttpErrorMapper`. An upsert changes recon handling | Open |

### WS0.06 — Read-only fields

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q06.1 | Which field carries last-updated, in what format? | `lastUpdated`, ISO-8601 string (`EntityDocument.fromWire`) | `document.lastUpdatedField` | `EntityDocument`, `EntityDecoder`, `Fixtures` | Open |
| Q06.2 | Do search and GET return the same document shape? | Yes | `document.searchVsGet` | `EntityDecoder` (WS4.08) | Open |
| Q06.3 | What happens if a PUT carries the last-updated field? | Never sent (`EntityDocument.toWire` omits it) | `document.lastUpdatedOnPut` (write run) | None, if ignored or rejected | Open |
| Q06.4 | Do relationship entries carry more than `TargetID`/`SourceID`/`type`? | No | `document.edgeKeys` | `Edge.fromWire` strictness | Open |

### WS0.07 — Edge semantics

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q07.1 | Does POST silently drop edges to missing entities? (A7) | Yes | `edges.postToMissing` | Design §9.1 if not | Open |
| Q07.2 | Does an inbound edge in a POST link to an existing entity? | Yes (§2.2) | `edges.inboundOnCreate` | Design §9.1 self-healing depends on it | Open |
| Q07.3 | Does GET show inbound edges as `SourceID`? | Yes | `edges.getShowsInbound` | `EntityDecoder`, recon comparison | Open |
| Q07.4 | Does a PUT that omits an edge delete it in both directions? | Yes | `edges.putOmissionDeletesInbound` | Design §8 | Open |
| Q07.5 | Does an inbound edge in a PUT link the entities? | Yes | `edges.inboundOnPut` | Design §8 | Open |
| Q07.6 | Is a PUT edge to a missing entity dropped, rejected or kept? | Dropped silently (the fake's behaviour) | `edges.putToMissing` | `FakeGraphService.store`. If dropped, consider a verification GET after PUT | Open |
| Q07.7 | Does DELETE cascade the entity's edges? | Yes | `edges.deleteCascades` | Design §9.3 | Open |

### WS0.08 — Type discrimination

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q08.1 | Is BaseType only ever 2 or 4? | Yes. Anything else decodes to null (`EntityKind.fromBaseType`) | `types.baseTypes` | `EntityKind` | Open |
| Q08.2 | Do archetypes, with BaseType, discriminate IDM types? | Yes (P5) | `types.archetypes` | `TypeScoper`; add the `idPattern` fallback if not | Open |

### WS0.09 — ID constraints

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q09.1 | Which characters are allowed, and are they addressable percent-encoded? | Any, encoded as one path segment (`RestClientEntityApi.encodeSegment`) | `ids.*` | `encodeSegment`, `RestClientEntityApiSpec` | Open |
| Q09.2 | What is the maximum ID length? | None | `ids.length *` | Discovery tool validation (WS6) | Open |
| Q09.3 | Are IDs case-sensitive? | Yes | `ids.caseSensitivity` | Risk register §9: case-insensitive IDs can merge entities | Open |

### WS0.10 — Auth and limits

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q10.1 | How does the service authenticate, and how are missing, wrong and valid credentials answered? | **Answered:** one raw key in the `X-API-KEY` header; a missing key gets 401 and a wrong key gets 401 | `auth.status` | Done 2026-11-21 (P13): probe, `ConnectorConfig`, customizer contract, redaction, provisioner sample, fixtures, specs | **Confirmed** by the project owner, 2026-11-21. The probe will observe it on its first run |
| Q10.2 | When does the service return 403 rather than 401? | **Answered:** not expected. There is one key with no permission levels | Not observable | None. `HttpErrorMapper` keeps its 403 mapping as a safe default | **Confirmed** (follows from Q10.1), 2026-11-21 |
| Q10.3 | Are there rate limits, and does 429 carry `Retry-After`? | 429 with `Retry-After` in seconds or as an HTTP-date | `limits.headers` (partial) | `HttpErrorMapper.retryAfterSeconds`, queued sync settings | Open |

---

## 3. Questions only the service team can answer

The probe cannot observe these. Send this section ahead of the WS0.01 session.

| ID | Question | Why it matters | Status |
|---|---|---|---|
| T1 | **Does anything other than IDM write relationships to the service, now or planned?** | The design's invariant A1. If another writer exists, every PUT from the connector deletes its edges. The read-merge-write fallback in design §15 would then be needed before go-live. | Open |
| T2 | Is the paging order guaranteed, or just how it works today? | Recon correctness depends on it (Q03.5, Q03.6) | Open |
| T3 | Are the ID constraints documented, or just what is accepted today? | The discovery tool should validate against documented limits (Q09) | Open |
| T4 | What is the rate-limit policy, per account or per IP, and what are the limits? | Recon `taskThreads` and queued sync retry settings (plan §9, WS9.02) | Open |
| T5 | Can the service account be scoped to the entities API only? | Design §14 | **Answered 2026-11-21: no.** One constant key is shared by all consumers, so it cannot be scoped or attributed. Recorded as a risk in the plan §9. Worth asking whether per-client keys are planned |
| T6 | Is there a test instance or tenant the write probe and WS8 may use? | WS0.07 and WS8 need one | Open |
| T7 | Is the error body schema stable, or may it change without notice? | Whether the connector may ever parse it (§13) | Open |
| T8 | How is the API versioned, and how are breaking changes announced? | Upgrade and monitoring planning (WS9) | Open |
| T9 | Is TLS available on every non-development endpoint, and with which CA? | Design §14, customizer trust settings (WS3.13). With a shared key, TLS is the main protection against the key being read in transit | Open |
| T10 | How is the shared key rotated, and how are consumers told in advance? | Rotation breaks every consumer that has not switched. IDM needs a provisioner update at the same moment (WS9.01, WS9.06) | Open |
| T11 | Is the key the same in every environment, test and production alike? | If it is, a key handed out for testing is the production key, and the probe's write run could reach production data | Open |

---

## 4. Harness contract (WS3.01)

These are questions about Ping IDM's Scripted REST harness, not about the service. They are recorded here so that every external contract lives in one place. They are answered in WS3.01, from the connector bundle's sample scripts or the vendor documentation.

| ID | Question | Assumes today | Status |
|---|---|---|---|
| H1 | Which binding carries the HTTP client the customizer's `decorate` closure returns: `connection` or `customizedConnection`? | `connection`. The vendor docs suggest `customizedConnection` | Open |
| H2 | What are the signatures of the customizer's `init`, `decorate` and `release` closures? | `init(HttpClientBuilder)`, `release(...)`. No `decorate` yet | Open |
| H7 | Is `password` the right home for the API key, or does `customSensitiveConfiguration` reach the scripts decrypted? | `password`, read through `GuardedString.access()` (P13) | Open |
| H3 | Does a create script receive an `id` binding? | Not used | Open |
| H4 | May a search script return a `SearchResult` to pass the paged-results cookie? | Yes | Open |
| H5 | Does the schema script return a `Schema`, or call `builder.schema { }`? | Returns a `Schema` | Open |
| H6 | Which `operation` values does the update script receive? | `UPDATE`, plus `ADD_ATTRIBUTE_VALUES` and `REMOVE_ATTRIBUTE_VALUES` (WS3.10) | Open |

---

## 5. What the API definition answers

The service is the **IDP Entitlements API**. Its OpenAPI definition (version `v1`) was supplied on 2026-11-21. Commit the raw definition next to this file so it is versioned with the contract:

```bash
curl -s http://localhost:8080/swagger/v1/swagger.json -o docs/api/idp-entitlements-openapi.json
```

The search endpoint describes itself as "Compatible with OpenICF/PingIDM Scripted REST Connector search operations", so this API was shaped with this connector in mind.

### 5.1 Endpoints the connector needs

| Operation | Request | Response |
|---|---|---|
| Create entity | `POST /admin/entities` with an EntityDTO body | 200, no body. 400, 401 documented. No 409 documented |
| Read entity | `GET /admin/entities/{id}` | 200 with an Entity, 401, 404 |
| Update entity | `PUT /admin/entities/{id}` with an EntityDTO body | 200, 400, 401, 404 |
| Delete entity | `DELETE /admin/entities/{id}` | 200 with the deleted Entity, 401, 404 |
| Add or update one relationship | `POST /admin/entities/{id}/relationships` with `{sourceID, targetID, type}` | 200 |
| Set relationships to one target | `POST /admin/entities/{id}/relationships/{targetID}` with an array of relationships | 200. Semantics undocumented |
| Delete one relationship | `DELETE /admin/entities/{sourceID}/relationships/{targetID}/{type}` | 200 |
| Delete all relationships to one target | `DELETE /admin/entities/{sourceID}/relationships/{targetID}` | 200 |
| Assign an archetype directly | `POST /admin/entities/{id}/direct-assign-archetype` with `{sourceID: entity, targetID: archetype, type: "IS_A"}` | 200 |
| Bulk create | `POST /admin/entities/bulk` with an array of Entity (the read model) | 200 |
| Search | `POST /admin/entities/search` with an EntitySearchRequest | 200 with an EntitySearchResult |

Out of scope for the connector: `/entitlements`, `/policy-information`, `/admin/manifests` and delegations.

### 5.2 Document shapes: three models, three casings

| Model | Used by | Identifier | Base type | Attributes | Relationships |
|---|---|---|---|---|---|
| EntityDTO (write) | create, update | `id` | `baseType`, enum 0–4 | `attributes`: string → **string** | `relationships`: `{sourceID, targetID, type}` |
| Entity (read) | GET, DELETE, bulk | `id` | `baseType`, enum 0–4 | `Attributes`: name → `{name, type, value}`; plus `compoundAttributes` | `relationships`: `{source, target, type, sourceType}` |
| EntitySearchResultItem | search | `uid` and `name` | `baseType` (observed as `"Subject"`, `"Resource"`) | `attributes`: name → any value | **none** |

Also on the read side: `directArchetypes` (assigned directly), `isArchetypes` (assigned by policy rules, search only), `lastEvaluated` ("last evaluation timestamp") and `reconciliationStarted` (Entity only).

### 5.3 Search

- Request fields: `objectClass` (the base type), `filter` (a tree: `{operation, left, right, not}`), `pageSize` (default 100), `pagedResultsCookie`, `attributesToGet`, `sortBy` (default `"ID"`) and `sortAscending` (default true).
- Filter operations in the samples: `EQUALS`, `CONTAINS`, `STARTSWITH`, `AND`, with `not` for negation. The schema types `operation` as an integer enum 0–9 without naming the values.
- Response: `results`, `pagedResultsCookie` (null when there are no more), `totalResults` (−1 when unknown) and `hasMore`.
- Paging is cookie-based. Send back the returned `pagedResultsCookie`; the sample shows `"100"`, an offset in practice.
- There is **no "updated since" field**, and nothing documents a last-modified time.

### 5.4 Register answers

| ID | Answer from the definition | Status |
|---|---|---|
| Q02.2, Q02.3, Q02.4, Q02.5 | No timestamp search exists. `lastEvaluated` records policy evaluation, not modification | Answered: not supported |
| Q03.1 | `{results, pagedResultsCookie, totalResults, hasMore}` | Confirmed by definition and probe run |
| Q03.2 | `pageSize`, default 100 | Confirmed |
| Q03.3 | Cookie: `pagedResultsCookie` in both request and response | Confirmed by definition |
| Q03.4 | Not documented for search | Open |
| Q03.5 | Sorted by `ID` ascending by default, configurable | Answered by definition |
| Q04.1 | 409 is not documented for entity create | Open: observed 200, effect unknown |
| Q05.1 | Errors are RFC 7807 problem details (`application/problem+json`) | Confirmed |
| Q06.1 | No last-updated field. `lastEvaluated` and `reconciliationStarted` exist | Answered |
| Q06.2 | No. Search results and GET use different models (section 5.2) | Answered |
| Q06.4 | Read relationships carry `source`, `target`, `type`, `sourceType` | Answered |
| Q07.1 to Q07.7 | Not documented. Relationships carry both ends explicitly, and single-edge add and delete endpoints exist | Open |
| Q08.1 | Enum with five values, 0–4. Create samples: **2 = Subject, 4 = Resource** | Contradicts the design (section 5.5) |
| Q08.2 | `isArchetypes` and `directArchetypes` both exist | Partly answered |
| Q09.1 to Q09.3 | Not documented | Open |
| Q10.1 | `X-API-Key` header, 401 when missing or invalid | Confirmed (header names are case-insensitive) |

### 5.5 Contradictions to raise with the service team

| ID | Definition says | Probe observed (run `20261121-155100`) |
|---|---|---|
| C1 | GET of an entity: 200 or 404 | **500 for every ID tried**: missing ones and ones just created. No existing real entity was tried |
| C2 | PUT to a missing entity: 404 | 200 |
| C3 | DELETE of a missing entity: 404 | 200 |
| C4 | Invalid create: 400 | 500 for a create without an ID |
| C5 | Create returns 200 | 200, but none of the probe's entities ever appeared in search. The probe sent the design's document shape, so this may be the probe's fault (section 7) |
| C6 | Create samples: 2 = Subject, 4 = Resource | The design (§2.1, A4) says 4 = Subject, 2 = Resource |
| C7 | DELETE's summary reads "Retrieves an entity by ID" | Probably a copy-paste slip; DELETE returns the deleted entity |

## 6. What this changes in the design

These need a decision before WS1 starts, because the configuration model, payload builder, decoder and search planner all depend on them.

| ID | Design assumption | Real API | Decision needed |
|---|---|---|---|
| D1 | Relationships are written in a full-state PUT, which deletes omitted edges (§2.2, §8) | Relationships carry both ends. Single-edge add and delete endpoints exist. Whether PUT replaces relationships is undocumented | Keep full-state PUT (needs Q07.4 confirmed), or manage edges one at a time through the relationship endpoints |
| D2 | Each edge is expressed from one entity's perspective, as `TargetID` or `SourceID` (§2.2, §7) | Every edge names its source and target explicitly | The §7 convention still decides which IDM side is the source, but `entityRelationships` values change shape |
| D3 | Incremental drift check by "updated since" search (§11) | No such search | Drop the incremental check and rely on full recon, or ask the service for a last-modified field |
| D4 | Recon compares edges from search results (§9.4, §11) | Search results carry no relationships | Read each entity by ID during recon (one call per entity, blocked by C1), or don't compare edges in recon |
| D5 | Numeric `BaseType`, 4 = Subject (§2.1, A4) | Enum 0–4. Samples give 2 = Subject, 4 = Resource. Search returns names | Confirm the numbering (C6) and the three remaining enum values |
| D6 | Type scoping by archetypes plus BaseType (§9.4, P5) | Search can scope by `objectClass` and filter on attributes | Use `objectClass`, and confirm whether a filter can match `directArchetypes` |
| D7 | Attributes may be multi-valued (§6) | Write model attributes are string to string | Restrict schema attributes to single values, or encode lists |
| D8 | Only Equals on `__UID__`/`__NAME__` and "updated since" are searchable (§9.4, P7) | Filter trees with EQUALS, CONTAINS, STARTSWITH, AND, OR and NOT | Support more ICF filters natively, once the operation enum is named |
| D9 | Archetypes are stamped in the entity document | `directArchetypes` in the DTO, plus a separate assign endpoint. Archetypes are defined in manifests | Confirm that stamping via the DTO works, and that the archetypes must already exist in a manifest |

## 7. Status of the contract probe against this API

The probe's read-side checks (auth, search, paging, document fields, type discrimination) work against this API. Since 2026-11-21:
- its scrubber recognises `uid`, `id` and the read model's attribute objects, and fails closed;
- any answer that depends on a failed read is reported as "cannot tell".

Its **write checks still send the design's document shape** (`ID`, numeric `BaseType`, `Relationships` with `TargetID`/`SourceID`). The service may bind those names case-insensitively, but the relationship and base-type semantics differ. Treat write-mode results as unreliable until the write checks are realigned to EntityDTO and the relationship endpoints (plan task WS0.15).

## 8. Change log

| Date | Change |
|---|---|
| 2026-11-04 | Register created. Contract probe added. No answers confirmed yet: no instance of the service was reachable from the environment that wrote this. |
| 2026-11-21 | OpenAPI definition received. Sections 5 to 7 added. It answers the paging, error-format and timestamp questions, contradicts the design's base-type numbering and relationship model, and exposes seven contradictions with observed behaviour (C1–C7) and nine design decisions (D1–D9). The first full probe run (`20261121-155100`) leaked unscrubbed entity data into the `probe-results` branch; the scrubber is fixed. |
| 2026-11-21 | Q02.1 answered by the project owner, after the first probe run against the real service got 405 for `GET /admin/entities`: search is `POST /admin/entities/search` with a JSON body, and `{}` returns everything. Folded in as decision P14. The probe now searches that way and accepts `--timestamp-field`. |
| 2026-11-21 | Q10.1, Q10.2 and T5 answered by the project owner: one shared raw key in `X-API-KEY`, with 401 for a missing or wrong key. Folded into the code as decision P13. Added T10, T11 and H7. |
