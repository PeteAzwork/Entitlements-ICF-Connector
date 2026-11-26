# Service contract probe report

| | |
|---|---|
| Run | `20261121-150804` |
| Service | `http://localhost:8080` |
| Mode | read-only |
| Exchanges | 5 |
| Generated | 2026-11-21T04:08:04.249283Z |

Evidence numbers such as `#12` refer to `exchanges.jsonl` and to `wiremock/mappings/0012-*.json`.
Copy confirmed answers into `docs/service-contract.md` together with the evidence numbers.

## Answers by task

### WS0.02 Search endpoint

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which path serves the entity search? | Neither candidate returned a list of entities.<br><sub>GET /admin/entities → 405, no content type, empty body; GET /admin/entities/ → 405, no content type, empty body</sub> | error | #4 #5 |
| Is the timestamp filter strictly-after or inclusive? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.03 Paging protocol

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Can an entity updated mid-enumeration be skipped or returned twice? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.04 Duplicate POST

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a duplicate POST return? | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.07 Edge semantics

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Edge semantics on POST, PUT, GET and DELETE | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.09 ID constraints

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| ID characters, length and case sensitivity | Not tested: needs --allow-writes against a test instance. | not-tested |  |

### WS0.10 Auth and limits

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| How does the service respond to a missing, wrong and valid `X-API-KEY` key? | No key: 405. Wrong key: 405. Valid key: 405.<br><sub>The key is sent raw in the `X-API-KEY` header. The service uses one shared key, so a 403 (authenticated but not permitted) is not expected.</sub> | observed | #1–#3 |
| Does the service advertise rate limits? | No rate-limit or Retry-After headers were seen in 5 responses.<br><sub>The probe does not try to trigger throttling. A 429 policy must come from the service team.</sub> | inconclusive |  |

## Suggested values

These follow from the answers above. Check them before changing any code.

| Setting | Suggested value | Where it goes |
|---|---|---|
| Search path | — | `RestClientEntityApi` |
| Timestamp parameter | — | `timestampSearchParam` default in `ConnectorConfig` |
| Datetime formats accepted | — | `SearchPlanner` |
| Page size parameter | — | `PageCursor.toQuery` |
| Offset parameter | — | `PageCursor.toQuery` |
| Cursor | — | `PageCursor` |
| Response envelope | — | `PageCursor.next`, `FakeAcceleratorService.search` |
| Last-updated field | — | `EntityDocument`, `EntityDecoder` |
| Ambiguous archetypes | none seen | `TypeScoper` (P5) |

## Cleanup

Read-only run: nothing was created.

## Not answerable by the probe

These need the service team. They are listed in `docs/service-contract.md`:

- Whether anything other than IDM writes relationships (design assumption A1).
- 403 semantics, service account scoping and the rate-limit policy.
- Documented limits on ID length and characters, as opposed to what happens to be accepted today.
- Whether ordering and paging behaviour are guaranteed or incidental.
