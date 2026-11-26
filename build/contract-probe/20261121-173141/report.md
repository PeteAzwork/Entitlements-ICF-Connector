# Service contract probe report

| | |
|---|---|
| Run | `20261121-173141` |
| Service | `http://localhost:8080` |
| Mode | read-only |
| Exchanges | 23 |
| Generated | 2026-11-21T06:31:41.918026Z |

Evidence numbers such as `#12` refer to `exchanges.jsonl` and to `wiremock/mappings/0012-*.json`.
Copy confirmed answers into `docs/service-contract.md` together with the evidence numbers.

## Answers by task

### WS0.02 Search endpoint

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which request serves the entity search? | `POST /admin/entities/search` with a JSON body. `{}` returned 91 entities. | observed | #4 |
| Which filter field matches the entity ID (P21)? | `ID`: an EQUALS filter on it returns exactly the entity.<br><sub>Filtered on the ID of one sampled entity. `ID` → 1 result(s), exactly the entity</sub> | observed | #9 |
| Is a newly created entity found by search (C5)? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.03 Paging protocol

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a search response look like? | object, items under `results`; total count: `totalResults`; next-page cursor: `pagedResultsCookie`; `hasMore`: present.<br><sub>91 items returned with no paging fields, total reported 91.</sub> | observed | #10 |
| Do `pageSize` and `pagedResultsCookie` page through the results? | Yes: `pageSize` is honoured, and sending back `pagedResultsCookie` returns the next page.<br><sub>First page `hasMore`: true.</sub> | observed | #11 #12 |
| What is the largest page the service will return? | Asked for 10000: 200, 91 items. Asked for 1000: 200, 91 items.<br><sub>Total reported: 91. If fewer items came back than both the request and the total, that is the cap.</sub> | observed | #13 #14 |
| Is the result order stable and what is it ordered by? | Same first page twice: identical order. Order: sorted by ID. | observed | #15 #16 |
| Can an entity updated mid-enumeration be skipped or returned twice? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.04 Duplicate POST

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a duplicate POST return? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.05 Error bodies

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does GET of a missing entity return? | 500: no content type, empty body | observed | #21 |
| What does a search with an unknown filter operation return? | 400: application/problem+json, JSON object with keys [errors, status, title, traceId, type] | observed | #22 |
| What does a search with an unknown objectClass return? | 500: no content type, empty body | observed | #23 |

### WS0.06 Read-only fields

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which fields does a search item carry? | attributes, baseType, directArchetypes, isArchetypes, lastEvaluated, name, uid (as documented).<br><sub>From one search result. Only field names are reported.</sub> | observed | #17 |
| In which format is `lastEvaluated` reported? | ISO-8601, no fraction, Z | observed | #17 |
| Does GET by ID work for an entity that exists (C1)? | No: 500, no content type, empty body. Reads by ID need `byIdLookup: search` (P21). | observed | #18 |
| Which fields does the read model (GET) carry? | Cannot tell: GET returned 500. | inconclusive | #18 |
| Which keys appear on relationships in the read model? | Cannot tell: GET returned 500. | inconclusive | #18 |

### WS0.07 Edge semantics

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Relationship semantics on POST, PUT, GET and DELETE, including Q07.8 | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.08 Type discrimination

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which base types occur? | Resource: 77, Subject: 14<br><sub>Sample of 91 entities over at most 5 pages.</sub> | observed | #19 |
| Do archetypes discriminate types? | Not on their own: ThirdPartyApplication appear with more than one base type.<br><sub>Archetypes read from directArchetypes and isArchetypes together. 91 of 91 entities have no direct archetype, so P19's scope archetype will only appear once the connector writes them. 8 distinct archetype sets: CreditCardApplication ×1, Customer ×8, Account ×47, (none) ×2, Company ×7, ThirdPartyApplication ×6, Publisher ×1, Ws0Probe ×19</sub> | observed | #19 |

### WS0.09 ID constraints

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| ID characters, length and case sensitivity | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.10 Auth and limits

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| How does the service respond to a missing, wrong and valid `X-API-KEY` key? | No key: 401. Wrong key: 401. Valid key: 500.<br><sub>Checked with a GET of an entity ID that does not exist, so a valid key should get 404. The key is sent raw in the `X-API-KEY` header. The service uses one shared key, so a 403 is not expected.</sub> | observed | #1–#3 |
| Does the service advertise rate limits? | No rate-limit or Retry-After headers were seen in 23 responses.<br><sub>The probe does not try to trigger throttling. A 429 policy must come from the service team.</sub> | inconclusive |  |

### WS0.15 Decision checks (P18, P20, P22)

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which base type does each numeric objectClass select (P18, C6)? | 4 = Subject, 2 = Resource, as P18 assumes.<br><sub>Search results name the base type, so a numeric objectClass shows its meaning. `4` → Subject ×14; `2` → Resource ×20; `"Subject"` → Subject ×14; `"Resource"` → Resource ×20</sub> | observed | #5–#8 |
| Which archetypes do the manifests define (P22)? | Cannot tell: 200, application/json, JSON object with keys [paths]. | inconclusive | #20 |
| Is an entity created with base type 4 a Subject (P18)? | Not tested: needs --allow-writes against a test instance. | not-tested |  |
| Does an update that omits an attribute remove it (P20)? | Not tested: needs --allow-writes against a test instance. | not-tested |  |
| Does an update replace directArchetypes in full (P22)? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

## Suggested values

These follow from the answers above. Check them before changing any code.

| Setting | Suggested value | Where it goes |
|---|---|---|
| Search request | `POST /admin/entities/search` with a JSON body | `RestClientEntityApi.search` |
| Base-type numbering (objectClass) | P18 (4 = Subject, 2 = Resource) | `EntityKind` (P18) |
| Base type of a created entity | — | `EntityKind` (P18); gates WS1.02 |
| GET by ID | fails (500) | `byIdLookup` default (P21) |
| ID filter field | `ID` | `SearchPlanner`, by-ID search fallback (P21) |
| Page size field | `pageSize` | `PageCursor.toQuery` |
| Cursor | response `pagedResultsCookie` → request `pagedResultsCookie` | `PageCursor` |
| Response envelope | object, items under `results`, total in `totalResults` | `PageCursor.next`, `FakeAcceleratorService.search` |
| `lastEvaluated` format | ISO-8601, no fraction, Z | `EntityDecoder` |
| Ambiguous archetypes | ThirdPartyApplication | `TypeScoper` (P19) |
| Manifest archetypes | — | `objectTypes` archetypes; Test operation (P22) |

## Cleanup

Read-only run: nothing was created.

## Not answerable by the probe

These need the service team. They are listed in `docs/service-contract.md`:

- Whether anything other than IDM writes relationships (design assumption A1).
- 403 semantics, service account scoping and the rate-limit policy.
- Documented limits on ID length and characters, as opposed to what happens to be accepted today.
- Whether ordering and paging behaviour are guaranteed or incidental.
- Why GET by ID fails (C1), and the names of the ten filter operations (P21).
