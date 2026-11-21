# Service contract probe report

| | |
|---|---|
| Run | `20261121-154839` |
| Service | `http://localhost:8080` |
| Mode | read-only |
| Exchanges | 46 |
| Generated | 2026-11-21T04:48:40.115277Z |

Evidence numbers such as `#12` refer to `exchanges.jsonl` and to `wiremock/mappings/0012-*.json`.
Copy confirmed answers into `docs/service-contract.md` together with the evidence numbers.

## Answers by task

### WS0.02 Search endpoint

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which request serves the entity search? | `POST /admin/entities/search` with a JSON body. `{}` returned 72 entities. | observed | #4 |
| Which request-body field filters the search by last-updated time? | None of the candidate names was honoured.<br><sub>A field is honoured when a far-future value returns fewer entities than the epoch. `updatedAfter`: epoch → 200/72 items, far future → 200/72 items: ignored; `modifiedSince`: epoch → 200/72 items, far future → 200/72 items: ignored; `updatedSince`: epoch → 200/72 items, far future → 200/72 items: ignored; `since`: epoch → 200/72 items, far future → 200/72 items: ignored; `lastUpdatedAfter`: epoch → 200/72 items, far future → 200/72 items: ignored; `changedSince`: epoch → 200/72 items, far future → 200/72 items: ignored; `from`: epoch → 200/72 items, far future → 200/72 items: ignored</sub> | inconclusive | #5–#18 |
| Is the timestamp filter strictly-after or inclusive? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.03 Paging protocol

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a search response look like? | object, items under `results`; total count: `totalResults`; next-page cursor: none; Link header: none.<br><sub>72 items returned with no paging parameters, total reported 72.</sub> | observed | #19 |
| Which parameter sets the page size? | `pageSize`<br><sub>Tried: pageSize, limit, size, page_size, per_page, _pageSize, count, top, max.</sub> | observed | #20 |
| Is paging offset-based, page-number based or cursor-based, and with which parameter? | response carries `pagedResultsCookie` but no candidate parameter accepted it | inconclusive | #21–#39 |
| What is the largest page the service will return? | Asked for 10000: 200, 72 items. Asked for 1000: 200, 72 items.<br><sub>Total reported: 72. If fewer items came back than both the request and the total, that is the cap.</sub> | observed | #40 #41 |
| Is the result order stable and what is it ordered by? | Same first page twice: identical order. Order: sorted by ID. | observed | #42 #43 |
| Can an entity updated mid-enumeration be skipped or returned twice? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.04 Duplicate POST

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a duplicate POST return? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.05 Error bodies

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does GET of a missing entity return? | 500: no content type, empty body | observed | #46 |

### WS0.06 Read-only fields

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| The check itself failed | java.lang.NullPointerException: Cannot invoke "String.length()" because "s" is null<br><sub>A probe defect or an unexpected response shape. See exchanges.jsonl around this check.</sub> | error | #44 |

### WS0.07 Edge semantics

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Edge semantics on POST, PUT, GET and DELETE | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.08 Type discrimination

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which BaseType values occur? | null: 72<br><sub>Sample of 72 entities over at most 5 pages.</sub> | observed | #45 |
| Do archetypes discriminate types? | In this sample every archetype appears with a single BaseType.<br><sub>1 distinct archetype sets; 72 entities have no archetypes. Sets: (none) ×72</sub> | observed | #45 |

### WS0.09 ID constraints

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| ID characters, length and case sensitivity | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.10 Auth and limits

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| How does the service respond to a missing, wrong and valid `X-API-KEY` key? | No key: 401. Wrong key: 401. Valid key: 500.<br><sub>Checked with a GET of an entity ID that does not exist, so a valid key should get 404. The key is sent raw in the `X-API-KEY` header. The service uses one shared key, so a 403 is not expected.</sub> | observed | #1–#3 |
| Does the service advertise rate limits? | No rate-limit or Retry-After headers were seen in 46 responses.<br><sub>The probe does not try to trigger throttling. A 429 policy must come from the service team.</sub> | inconclusive |  |

## Suggested values

These follow from the answers above. Check them before changing any code.

| Setting | Suggested value | Where it goes |
|---|---|---|
| Search request | `POST /admin/entities/search` with a JSON body | `RestClientEntityApi.search` |
| Timestamp field (request body) | — | `timestampSearchParam` default in `ConnectorConfig` |
| Datetime formats accepted | — | `SearchPlanner` |
| Page size field | `pageSize` | `PageCursor.toQuery` |
| Offset field | — | `PageCursor.toQuery` |
| Cursor | — | `PageCursor` |
| Response envelope | object, items under `results`, total in `totalResults` | `PageCursor.next`, `FakeGraphService.search` |
| Last-updated field | `lastEvaluated`, ISO-8601, no fraction, Z | `EntityDocument`, `EntityDecoder` |
| Ambiguous archetypes | none seen | `TypeScoper` (P5) |

## Cleanup

Read-only run: nothing was created.

## Not answerable by the probe

These need the service team. They are listed in `docs/service-contract.md`:

- Whether anything other than IDM writes relationships (design assumption A1).
- 403 semantics, service account scoping and the rate-limit policy.
- Documented limits on ID length and characters, as opposed to what happens to be accepted today.
- Whether ordering and paging behaviour are guaranteed or incidental.
