# Service Contract — Graph Entity Service Admin API

**Workstream:** WS0 (delivery plan §4 and §12)
**Status:** Open. No answer below is confirmed yet.
**Last updated:** 2026-11-04

This is the record of what the connector may rely on about the remote service. Each question has one of three states:

- **Open:** not yet answered. The code runs on the stated assumption.
- **Confirmed:** observed on a running instance or stated by the service team, with evidence.
- **Agreed assumption:** not verifiable, but the service team has agreed the connector may rely on it. Record who agreed and when.

When a question changes state, apply WS0.12 in the delivery plan: change the code and tests it names, and add a decision-log entry in plan §3.

---

## 1. How to collect the answers

Most questions can be answered by observation with the contract probe in `contract-probe/`. The probe was tested against an in-memory model of the documented semantics; it has not yet been run against the real service.

**Step 1. Read-only run.** This is safe against any environment, because it only sends GET requests:

```bash
export GRAPH_PROBE_PASSWORD='…'
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --username svc-probe"
```

**Step 2. Write run, against a test instance only.** It creates, changes and deletes entities whose IDs start with `ws0-probe-<run>-`, and deletes them all at the end, even after a failure. It never touches any other entity.

```bash
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --username svc-probe --allow-writes"
```

**Output** goes to `build/contract-probe/<run>/`:

| File | Contents |
|---|---|
| `report.md` | Answers grouped by WS0 task, suggested configuration values and cleanup status |
| `findings.json` | The same, machine-readable |
| `exchanges.jsonl` | Every request and response, numbered, so answers can cite evidence such as `#12` |
| `wiremock/mappings/` | Each exchange as a WireMock stub, the raw material for WS0.11 |

**Safe to share.** No output contains request headers or credentials. Every entity the probe did not create is scrubbed: its ID and attribute values are replaced, and its shape (BaseType, archetypes, field names, edge types, timestamp formats) is kept. Tests in `ProbeOutputSpec` enforce this.

**What the probe needs.** A service account that can read entities for step 1, and create and delete them for step 2. The password is read from an environment variable and never from the command line. There is no option to disable TLS verification.

---

## 2. Question register

"Probe" names the finding key in `report.md` that answers the question. "Assumes" is what the code does today. "Changes" is what to update once the question is answered.

### WS0.02 — Search endpoint

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q02.1 | Which path serves search: `/admin/entities` or `/admin/entities/`? | `/admin/entities` (`RestClientEntityApiSpec`) | `search.path` | `RestClientEntityApi` | Open |
| Q02.2 | What is the timestamp parameter called? | `updatedAfter` (`ConnectorConfig.DEFAULT_TIMESTAMP_PARAM`, configurable) | `search.timestampParam` | Default value, `Fixtures`, `FakeGraphService.timestampParam` | Open |
| Q02.3 | Which datetime formats does it accept, at what precision? | ISO-8601 instant with `Z` (`SearchPlanner`, `SearchPlanner.EPOCH`) | `search.datetimeFormats` | `SearchPlanner`, `SearchPlannerSpec` | Open |
| Q02.4 | Is the filter strictly-after or inclusive? | Undecided. `GreaterThan` and `GreaterThanOrEqual` map identically | `search.boundary` (write run) | `SearchPlanner` (WS4.04) | Open |
| Q02.5 | Does the search return updated entities as well as created ones? | Yes (design §2) | `search.returnsUpdated` (write run) | Design §11 if not | Open |

### WS0.03 — Paging protocol

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q03.1 | What is the response envelope? | Object with `items`, plus optional `totalCount` or `nextCursor` (P6) | `paging.envelope` | `PageCursor.next`, `FakeGraphService.search` | Open |
| Q03.2 | Which parameter sets the page size? | `pageSize` | `paging.pageSizeParam` | `PageCursor.toQuery`, `PageCursorSpec` | Open |
| Q03.3 | Offset, page number or cursor, and which parameter? | `offset`, or an opaque `cursor` when the response supplies one | `paging.params` | `PageCursor`, `PageCursorSpec` | Open |
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
| Q10.1 | How are missing, wrong and valid credentials answered? | 401 for both failures; Basic sent preemptively | `auth.status` | `HttpErrorMapper` (TEST context) | Open |
| Q10.2 | When does the service return 403 rather than 401? | Authenticated but not permitted | Not observable | `HttpErrorMapper` | Open |
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
| T5 | Can the service account be scoped to the entities API only? | Design §14 | Open |
| T6 | Is there a test instance or tenant the write probe and WS8 may use? | WS0.07 and WS8 need one | Open |
| T7 | Is the error body schema stable, or may it change without notice? | Whether the connector may ever parse it (§13) | Open |
| T8 | How is the API versioned, and how are breaking changes announced? | Upgrade and monitoring planning (WS9) | Open |
| T9 | Is TLS available on every non-development endpoint, and with which CA? | Design §14, customizer trust settings (WS3.13) | Open |

---

## 4. Harness contract (WS3.01)

These are questions about Ping IDM's Scripted REST harness, not about the service. They are recorded here so that every external contract lives in one place. They are answered in WS3.01, from the connector bundle's sample scripts or the vendor documentation.

| ID | Question | Assumes today | Status |
|---|---|---|---|
| H1 | Which binding carries the HTTP client the customizer's `decorate` closure returns: `connection` or `customizedConnection`? | `connection`. The vendor docs suggest `customizedConnection` | Open |
| H2 | What are the signatures of the customizer's `init`, `decorate` and `release` closures? | `init(HttpClientBuilder)`, `release(...)`. No `decorate` yet | Open |
| H3 | Does a create script receive an `id` binding? | Not used | Open |
| H4 | May a search script return a `SearchResult` to pass the paged-results cookie? | Yes | Open |
| H5 | Does the schema script return a `Schema`, or call `builder.schema { }`? | Returns a `Schema` | Open |
| H6 | Which `operation` values does the update script receive? | `UPDATE`, plus `ADD_ATTRIBUTE_VALUES` and `REMOVE_ATTRIBUTE_VALUES` (WS3.10) | Open |

---

## 5. Change log

| Date | Change |
|---|---|
| 2026-11-04 | Register created. Contract probe added. No answers confirmed yet: no instance of the service was reachable from the environment that wrote this. |
