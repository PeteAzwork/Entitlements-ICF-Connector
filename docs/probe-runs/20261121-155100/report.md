# Service contract probe report

| | |
|---|---|
| Run | `20261121-155100` |
| Service | `http://localhost:8080` |
| Mode | read-write (probe entities only) |
| Exchanges | 159 |
| Generated | 2026-11-21T04:51:04.824083Z |

Evidence numbers such as `#12` refer to `exchanges.jsonl` and to `wiremock/mappings/0012-*.json`.
Copy confirmed answers into `docs/service-contract.md` together with the evidence numbers.

## Answers by task

### WS0.02 Search endpoint

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which request serves the entity search? | `POST /admin/entities/search` with a JSON body. `{}` returned 72 entities. | observed | #7 |
| Which request-body field filters the search by last-updated time? | None of the candidate names was honoured.<br><sub>A field is honoured when a far-future value returns fewer entities than the epoch. `updatedAfter`: epoch → 200/72 items, far future → 200/72 items: ignored; `modifiedSince`: epoch → 200/72 items, far future → 200/72 items: ignored; `updatedSince`: epoch → 200/72 items, far future → 200/72 items: ignored; `since`: epoch → 200/72 items, far future → 200/72 items: ignored; `lastUpdatedAfter`: epoch → 200/72 items, far future → 200/72 items: ignored; `changedSince`: epoch → 200/72 items, far future → 200/72 items: ignored; `from`: epoch → 200/72 items, far future → 200/72 items: ignored</sub> | inconclusive | #8–#21 |
| Is the timestamp filter strictly-after or inclusive? | Not tested: no timestamp parameter or last-updated field was found. | not-tested |  |

### WS0.03 Paging protocol

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a search response look like? | object, items under `results`; total count: `totalResults`; next-page cursor: none; Link header: none.<br><sub>72 items returned with no paging parameters, total reported 72.</sub> | observed | #22 |
| Which parameter sets the page size? | `pageSize`<br><sub>Tried: pageSize, limit, size, page_size, per_page, _pageSize, count, top, max.</sub> | observed | #23 |
| Is paging offset-based, page-number based or cursor-based, and with which parameter? | response carries `pagedResultsCookie` but no candidate parameter accepted it | inconclusive | #24–#42 |
| What is the largest page the service will return? | Asked for 10000: 200, 72 items. Asked for 1000: 200, 72 items.<br><sub>Total reported: 72. If fewer items came back than both the request and the total, that is the cap.</sub> | observed | #43 #44 |
| Is the result order stable and what is it ordered by? | Same first page twice: identical order. Order: sorted by ID. | observed | #45 #46 |
| Can an entity updated mid-enumeration be skipped or returned twice? | Not tested: the timestamp parameter, page size, paging parameter and last-updated field must all be known first. | not-tested |  |

### WS0.04 Duplicate POST

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a duplicate POST return? | 200, and the entity was left unchanged. | observed | #52 #53 |

### WS0.05 Error bodies

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does GET of a missing entity return? | 500: no content type, empty body | observed | #49 |
| What does a POST without an ID return? | 500: no content type, empty body | observed | #66 |
| What does a malformed JSON body return? | 400: application/problem+json, JSON object with keys [errors, status, title, traceId, type] | observed | #67 |
| What does a PUT to a missing entity return? | 200: no content type, empty body. The PUT created the entity (upsert), which the design does not expect. | observed | #68 |
| What does a DELETE of a missing entity return? | 200: no content type, empty body | observed | #69 |

### WS0.06 Read-only fields

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| The check itself failed | java.lang.NullPointerException: Cannot invoke "String.length()" because "s" is null<br><sub>A probe defect or an unexpected response shape. See exchanges.jsonl around this check.</sub> | error | #47 |

### WS0.07 Edge semantics

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What happens to an edge to a missing entity on POST (A7)? | Dropped silently: POST succeeded and the edge is absent.<br><sub>POST 200.</sub> | observed | #50 #51 |
| Does an inbound edge asserted in a POST link to an existing entity? | No: A does not show an edge to B.<br><sub>POST B 200.</sub> | observed | #54 #55 |
| Does GET show inbound edges as SourceID? | No: B's relationships are null. | observed | #56 |
| Does a PUT that omits an edge delete it, seen from the other end? | Yes: B no longer shows SourceID A.<br><sub>PUT A 200.</sub> | observed | #57 #58 |
| Does an inbound edge asserted in a PUT link the entities? | No.<br><sub>PUT B 200.</sub> | observed | #59 #60 |
| What happens to an edge to a missing entity on PUT? | Dropped silently: PUT succeeded and only the edge to the existing entity remains. | observed | #61 #62 |
| Does DELETE remove the edges touching the entity? | Yes: A no longer shows the edge.<br><sub>DELETE 200; GET of the deleted entity then returns 500.</sub> | observed | #63–#65 |

### WS0.08 Type discrimination

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which BaseType values occur? | null: 72<br><sub>Sample of 72 entities over at most 5 pages.</sub> | observed | #48 |
| Do archetypes discriminate types? | In this sample every archetype appears with a single BaseType.<br><sub>1 distinct archetype sets; 72 entities have no archetypes. Sets: (none) ×72</sub> | observed | #48 |

### WS0.09 ID constraints

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Is an ID with space accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-id%20with%20space</sub> | observed | #70–#73 |
| Is an ID with slash accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-id%2Fwith%2Fslash</sub> | observed | #74–#77 |
| Is an ID with question accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-id%3Fq%3D1</sub> | observed | #78–#81 |
| Is an ID with hash accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-id%23hash</sub> | observed | #82–#85 |
| Is an ID with plus accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-id%2Bplus</sub> | observed | #86–#89 |
| Is an ID with percent accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-id%2525</sub> | observed | #90–#93 |
| Is an ID with unicode accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-id%C3%BC-%C3%B1-%E6%BC%A2</sub> | observed | #94–#97 |
| Is an ID with dot accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-id.with.dots</sub> | observed | #98–#101 |
| Is an ID with length 64 accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #102–#105 |
| Is an ID with length 255 accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #106–#109 |
| Is an ID with length 256 accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #110–#113 |
| Is an ID with length 1024 accepted and addressable when percent-encoded? | Created, but GET by the encoded ID returned 500.<br><sub>Path used: /admin/entities/ws0-probe-20261121-155100-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #114–#117 |
| Are IDs case-sensitive? | Unclear: 500. | observed | #118 #119 |

### WS0.10 Auth and limits

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| How does the service respond to a missing, wrong and valid `X-API-KEY` key? | No key: 401. Wrong key: 401. Valid key: 500.<br><sub>Checked with a GET of an entity ID that does not exist, so a valid key should get 404. The key is sent raw in the `X-API-KEY` header. The service uses one shared key, so a 403 is not expected.</sub> | observed | #1–#3 |
| Does the service advertise rate limits? | No rate-limit or Retry-After headers were seen in 119 responses.<br><sub>The probe does not try to trigger throttling. A 429 policy must come from the service team.</sub> | inconclusive |  |

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

All 20 probe entity IDs were deleted and confirmed gone.

## Not answerable by the probe

These need the service team. They are listed in `docs/service-contract.md`:

- Whether anything other than IDM writes relationships (design assumption A1).
- 403 semantics, service account scoping and the rate-limit policy.
- Documented limits on ID length and characters, as opposed to what happens to be accepted today.
- Whether ordering and paging behaviour are guaranteed or incidental.
