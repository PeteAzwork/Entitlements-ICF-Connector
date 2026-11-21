# Service contract probe report

| | |
|---|---|
| Run | `20261121-215954` |
| Service | `http://localhost:8080` |
| Mode | read-write (probe entities only) |
| Exchanges | 477 |
| Generated | 2026-11-21T11:01:18.448044Z |

Evidence numbers such as `#12` refer to `exchanges.jsonl` and to `wiremock/mappings/0012-*.json`.
Copy confirmed answers into `docs/service-contract.md` together with the evidence numbers.

## Answers by task

### WS0.02 Search endpoint

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which request serves the entity search? | `POST /admin/entities/search` with a JSON body. `{}` returned 79 entities. | observed | #13 |
| Which filter field matches the entity ID (P21)? | `ID`: an EQUALS filter on it returns exactly the entity.<br><sub>Filtered on the ID of one sampled entity. `ID` → 1 result(s), exactly the entity</sub> | observed | #18 |
| Does a deleted entity disappear from search? | Yes: after DELETE (200) it was gone from search in 522 ms. GET then returns 500. | observed | #37 #39 #40 #42 #43 |
| Is a newly created entity found by search, and how soon (C5)? | Yes: both created entities were found by a search on their ID within 525 ms. | observed | #44 #45 #47–#49 |

### WS0.03 Paging protocol

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a search response look like? | object, items under `results`; total count: `totalResults`; next-page cursor: `pagedResultsCookie`; `hasMore`: present.<br><sub>83 items returned with no paging fields, total reported 83.</sub> | observed | #19 |
| Do `pageSize` and `pagedResultsCookie` page through the results? | Yes: `pageSize` is honoured, and sending back `pagedResultsCookie` returns the next page.<br><sub>First page `hasMore`: true.</sub> | observed | #20 #21 |
| What is the largest page the service will return? | Asked for 10000: 200, 83 items. Asked for 1000: 200, 83 items.<br><sub>Total reported: 83. If fewer items came back than both the request and the total, that is the cap.</sub> | observed | #22 #23 |
| Is the result order stable and what is it ordered by? | Same first page twice: identical order. Order: sorted by ID. | observed | #24 #25 |
| Can an entity updated mid-enumeration be skipped or returned twice? | Neither: each entity appeared exactly once.<br><sub>Page size 1, a STARTSWITH filter on the probe IDs, one entity updated after the first page that contained a test entity.</sub> | observed | #318–#328 |

### WS0.04 Duplicate POST

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a duplicate POST return? | 200, and the entity was replaced (upsert), visible after 521 ms. | observed | #74 #76 |

### WS0.05 Error bodies

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does GET of a missing entity return? | 500: no content type, empty body | observed | #30 |
| What does a search with an unknown filter operation return? | 400: application/problem+json, JSON object with keys [errors, status, title, traceId, type] | observed | #31 |
| What does a search with an unknown objectClass return? | 500: no content type, empty body | observed | #32 |
| What does a POST without an ID return (C4)? | 500: no content type, empty body | observed | #200 |
| What does a malformed JSON body return? | 400: application/problem+json, JSON object with keys [errors, status, title, traceId, type] | observed | #201 |
| What does a POST with a non-string attribute value return (P20)? | 400: application/problem+json, JSON object with keys [errors, status, title, traceId, type] | observed | #202 |
| What does a PUT to a missing entity return (C2)? | 200: no content type, empty body. A read-back finds the entity, so PUT creates missing entities (upsert, C2). | observed | #203 #205 #207 |
| What does a DELETE of a missing entity return (C3)? | 200: no content type, empty body | observed | #204 |

### WS0.06 Read-only fields

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which fields does a search item carry? | attributes, baseType, directArchetypes, isArchetypes, lastEvaluated, name, uid (as documented).<br><sub>From one search result. Only field names are reported.</sub> | observed | #26 |
| In which format is `lastEvaluated` reported? | ISO-8601, no fraction, Z | observed | #26 |
| Does GET by ID work for an entity that exists (C1)? | No: 500, no content type, empty body. Reads by ID need `byIdLookup: search` (P21). | observed | #27 |
| Which fields does the read model (GET) carry? | Cannot tell: GET returned 500. | inconclusive | #27 |
| Which keys appear on relationships in the read model? | Cannot tell: GET returned 500. | inconclusive | #27 |

### WS0.07 Edge semantics

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What happens to a relationship to a missing entity on POST (A7)? | Cannot tell: GET returned 500 within the 10s settle window.<br><sub>Settled writes; read on the written entity within the 10s settle window.</sub> | inconclusive | #50 #52 #73 |
| Does a relationship in the target's POST link it to an existing source? | Yes: B, written with A → B, lists it.<br><sub>Settled writes; read on the written entity within the 10s settle window.</sub> | observed | #80 #82 #83 |
| Does GET of the target show the relationship, with the other entity as source? | Yes: B lists A → B, with A as source.<br><sub>Settled writes; read on the written entity within the 10s settle window.</sub> | observed | #83 |
| Which keys does the read model use for a relationship (P17)? | source, sourceType, target, type | observed | #83 |
| Does the other end's read model show a relationship it did not write (C15)? | Yes: A lists A → B, although only B was written.<br><sub>Read once, straight after B's write was applied.</sub> | observed | #84 |
| Does writing the source with an empty relationship list delete the relationship (P15)? | No: A, written with no relationships, still lists A → B within the 10s settle window, although B also asserts it.<br><sub>Settled writes; read on the written entity within the 10s settle window. Waits for the removal, not only for the write.</sub> | observed | #85 #87 #108 |
| Does a relationship in the target's PUT link the entities? | Yes: B, written with A → B, lists it.<br><sub>Settled writes; read on the written entity within the 10s settle window.</sub> | observed | #109 #111 #112 |
| Does writing the target with an empty relationship list delete the relationship (Q07.8)? | No: B, written with no relationships, still lists A → B within the 10s settle window.<br><sub>Settled writes; read on the written entity within the 10s settle window. Waits for the removal, not only for the write.</sub> | observed | #113 #115 #136 |
| What happens to a relationship to a missing entity on PUT? | Dropped silently: PUT succeeded and only the relationship to the existing entity remains.<br><sub>Settled writes; read on the written entity within the 10s settle window.</sub> | observed | #137 #139 #140 |
| Does a non-empty relationship list replace the old one (P15)? | Yes: R, rewritten with only R → T2, lists R → T2 and no longer R → T1 (after 6 ms).<br><sub>Settled writes; read on the written entity within the 10s settle window, waiting for the outcome.</sub> | observed | #151 #153 #154 |
| Do the relationship endpoints remove and add one relationship? | DELETE 200: removed. POST 200: not added within the 10s settle window. The connector does not use them (P15). | observed | #155 #157 #158 #179 |
| Does DELETE remove the relationships touching the entity? | Yes: R no longer lists R → T2.<br><sub>DELETE T2 200.</sub> | observed | #180 #181 |

### WS0.08 Type discrimination

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which base types occur? | Resource: 57, Subject: 14<br><sub>Sample of 71 entities over at most 5 pages.</sub> | observed | #28 |
| Do archetypes discriminate types? | Not on their own: ThirdPartyApplication appear with more than one base type.<br><sub>Archetypes read from directArchetypes and isArchetypes together; search reports directly assigned ones in isArchetypes (C9). 2 of 71 entities have no archetype. 7 distinct archetype sets: CreditCardApplication ×1, Customer ×8, Account ×46, (none) ×2, Company ×7, ThirdPartyApplication ×6, Publisher ×1</sub> | observed | #28 |

### WS0.09 ID constraints

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Is an ID with space accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-id%20with%20space</sub> | observed | #208 #209 #211–#214 |
| Is an ID with slash accepted and addressable when percent-encoded? | Created and GET by the encoded ID returned 500, but a search finds it, but delete returned 200 and removal was not visible: search still finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-id%2Fwith%2Fslash</sub> | observed | #215 #216 #218–#220 #241 |
| Is an ID with question accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-id%3Fq%3D1</sub> | observed | #242 #243 #245–#248 |
| Is an ID with hash accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-id%23hash</sub> | observed | #249 #250 #252–#255 |
| Is an ID with plus accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-id%2Bplus</sub> | observed | #256 #257 #259–#262 |
| Is an ID with percent accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-id%2525</sub> | observed | #263 #264 #266–#269 |
| Is an ID with unicode accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-id%C3%BC-%C3%B1-%E6%BC%A2</sub> | observed | #270 #271 #273–#276 |
| Is an ID with dot accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-id.with.dots</sub> | observed | #277 #278 #280–#283 |
| Is an ID with length 64 accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #284 #285 #287–#289 #291 |
| Is an ID with length 255 accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #292 #293 #295–#298 |
| Is an ID with length 256 accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #299 #300 #302–#304 #306 |
| Is an ID with length 1024 accepted and addressable when percent-encoded? | Yes: created, GET by the encoded ID returned 500, but a search finds it unchanged, and deleted (no longer found by search).<br><sub>Path used: /admin/entities/ws0-probe-20261121-215954-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #307 #308 #310–#313 |
| Are IDs case-sensitive? | Yes: the entity is in search, and a search for the lower-cased ID does not find it. | observed | #314 #316 #317 |

### WS0.10 Auth and limits

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| How does the service respond to a missing, wrong and valid `X-API-KEY` key? | No key: 401. Wrong key: 401. Valid key: 500.<br><sub>Checked with a GET of an entity ID that does not exist, so a valid key should get 404. The key is sent raw in the `X-API-KEY` header. The service uses one shared key, so a 403 is not expected.</sub> | observed | #1–#3 |
| Does the service advertise rate limits? | No rate-limit or Retry-After headers were seen in 328 responses.<br><sub>The probe does not try to trigger throttling. A 429 policy must come from the service team.</sub> | inconclusive |  |

### WS0.15 Decision checks (P18, P20, P22)

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which create body does the service accept? | The EntityDTO with attributes.ID is accepted, as designed (C8).<br><sub>Each variant changes one thing in the designed body. Archetype used: Ws0Probe. 1. EntityDTO with attributes.ID (C8) → 200; 2. EntityDTO without attributes.ID → 500; 3. EntityDTO with attributes.ID and a direct archetype → 200; 4. EntityDTO with attributes.ID, without empty arrays → 200; 5. EntityDTO with attributes.ID, path with a trailing slash → 200; 6. design casing (ID, BaseType, Relationships) → 200</sub> | observed | #4–#9 |
| Which base type does each numeric objectClass select (P18, C6)? | 4 = Subject, 2 = Resource, as P18 assumes.<br><sub>Search results name the base type, so a numeric objectClass shows its meaning. `4` → Subject ×14; `2` → Resource ×20; `"Subject"` → Subject ×14; `"Resource"` → Resource ×20</sub> | observed | #14–#17 |
| Which archetypes do the manifests define (P22)? | 4 manifest(s), nested in an export bundle rather than the documented list (C11). Subject archetypes: Account Owner, Developer, Legal Representative. Resource archetypes: Credit Card, Current Account, Financing, Insurance, Savings Account, Third Party Application, User Access Requests.<br><sub>Every archetype the connector is configured with must be listed here for its base type. Only archetype names are recorded.</sub> | observed | #29 |
| Are entities from earlier probe runs still in the service? | Yes: 4, from run(s) 20261121-155100 ×1, 20261121-190350 ×1, 20261121-211859 ×1, 20261121-214027 ×1. Remove them by hand; their IDs are in the detail.<br><sub>ws0-probe-20261121-155100-id/with/slash, ws0-probe-20261121-190350-id/with/slash, ws0-probe-20261121-211859-id/with/slash, ws0-probe-20261121-214027-id/with/slash</sub> | observed | #33 |
| Were the leftover probe entities removed? | Removed 0 of 4. Not addressable by path, so not attempted (Q09.1): ws0-probe-20261121-155100-id/with/slash, ws0-probe-20261121-190350-id/with/slash, ws0-probe-20261121-211859-id/with/slash, ws0-probe-20261121-214027-id/with/slash. Ask the service team to remove them. | observed | #34 |
| Which relationship form does a create accept (P17)? | P17 `{sourceID, targetID, type}` → 200; design `{TargetID, type}` → 200.<br><sub>Whether the relationship was stored needs GET, which fails (C1); this only shows the body is accepted.</sub> | observed | #34–#36 |
| Is an entity created with base type 4 a Subject (P18)? | Yes: created with 4 it is a `Subject`, and created with 2 a `Resource`; a search with objectClass 4 finds it. | observed | #44 #45 #47–#49 |
| Does an update that omits an attribute remove it (P20)? | No: once the update was visible, the omitted attribute was still there. The connector must send empty values explicitly instead of omitting them.<br><sub>Read by search after each write was applied.</sub> | observed | #182 #184 #185 #187 |
| Does writing an attribute as an empty string clear it (P20)? | Stored as an empty string: the attribute remains, with an empty value.<br><sub>Read by search after the update was applied.</sub> | observed | #188 #190 #191 #193 |
| Does an update replace directArchetypes in full (P22)? | Yes: Created with both; after an update listing only `Current Account`, only `Current Account` remains.<br><sub>Archetypes Credit Card, Current Account, from the manifests. Read from isArchetypes, where search reports them (C9).</sub> | observed | #194 #196 #197 #199 |

## Suggested values

These follow from the answers above. Check them before changing any code.

| Setting | Suggested value | Where it goes |
|---|---|---|
| Search request | `POST /admin/entities/search` with a JSON body | `RestClientEntityApi.search` |
| Create body accepted | EntityDTO with attributes.ID (C8) | `EntityPayloadBuilder`, `EntityDocument.toWire` |
| Relationship form accepted | P17 {sourceID, targetID, type} | `EdgeCodec` (P17) |
| Search drops deleted entities | yes | delete verification; WS10 orphan handling |
| Base-type numbering (objectClass) | P18 (4 = Subject, 2 = Resource) | `EntityKind` (P18) |
| Base type of a created entity | confirmed: 4 = Subject | `EntityKind` (P18); gates WS1.02 |
| GET by ID | fails (500) | `byIdLookup` default (P21) |
| ID filter field | `ID` | `SearchPlanner`, by-ID search fallback (P21) |
| Page size field | `pageSize` | `PageCursor.toQuery` |
| Cursor | response `pagedResultsCookie` → request `pagedResultsCookie` | `PageCursor` |
| Response envelope | object, items under `results`, total in `totalResults` | `PageCursor.next`, `FakeGraphService.search` |
| `lastEvaluated` format | ISO-8601, no fraction, Z | `EntityDecoder` |
| Ambiguous archetypes | ThirdPartyApplication | `TypeScoper` (P19) |
| Leftover probe entities | 4 | remove by hand |
| Manifest archetypes | Subject: Account Owner, Developer, Legal Representative; Resource: Credit Card, Current Account, Financing, Insurance, Savings Account, Third Party Application, User Access Requests | `objectTypes` archetypes; Test operation (P22) |

## Cleanup

**Some probe entities may remain.** Remove these by hand:

- ws0-probe-20261121-215954-id/with/slash still found by search after cleanup

 3 other probe IDs were never accepted by the service, and no search finds them.

## Not answerable by the probe

These need the service team. They are listed in `docs/service-contract.md`:

- Whether anything other than IDM writes relationships (design assumption A1).
- 403 semantics, service account scoping and the rate-limit policy.
- Documented limits on ID length and characters, as opposed to what happens to be accepted today.
- Whether ordering and paging behaviour are guaranteed or incidental.
- Why GET by ID fails (C1), and the names of the ten filter operations (P21).
