# Service contract probe report

| | |
|---|---|
| Run | `20261121-190350` |
| Service | `http://localhost:8080` |
| Mode | read-write (probe entities only) |
| Exchanges | 284 |
| Generated | 2026-11-21T08:03:52.679469Z |

Evidence numbers such as `#12` refer to `exchanges.jsonl` and to `wiremock/mappings/0012-*.json`.
Copy confirmed answers into `docs/service-contract.md` together with the evidence numbers.

## Answers by task

### WS0.02 Search endpoint

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which request serves the entity search? | `POST /admin/entities/search` with a JSON body. `{}` returned 91 entities. | observed | #15 |
| Which filter field matches the entity ID (P21)? | `ID`: an EQUALS filter on it returns exactly the entity.<br><sub>Filtered on the ID of one sampled entity. `ID` → 1 result(s), exactly the entity</sub> | observed | #20 |
| Does search stop returning an entity once it is deleted? | Cannot tell: the created entity is not found by search, so its removal cannot be seen there (C5). | inconclusive | #39 #40 |
| Is a newly created entity found by search (C5)? | No: the base-type-4 entity and the base-type-2 entity not found by a search on the ID. Creates may not be committed, or search may lag (C5). | observed | #41–#45 |

### WS0.03 Paging protocol

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a search response look like? | object, items under `results`; total count: `totalResults`; next-page cursor: `pagedResultsCookie`; `hasMore`: present.<br><sub>97 items returned with no paging fields, total reported 97.</sub> | observed | #21 |
| Do `pageSize` and `pagedResultsCookie` page through the results? | Yes: `pageSize` is honoured, and sending back `pagedResultsCookie` returns the next page.<br><sub>First page `hasMore`: true.</sub> | observed | #22 #23 |
| What is the largest page the service will return? | Asked for 10000: 200, 97 items. Asked for 1000: 200, 97 items.<br><sub>Total reported: 97. If fewer items came back than both the request and the total, that is the cap.</sub> | observed | #24 #25 |
| Is the result order stable and what is it ordered by? | Same first page twice: identical order. Order: sorted by ID. | observed | #26 #27 |
| Can an entity updated mid-enumeration be skipped or returned twice? | Neither: each entity appeared exactly once.<br><sub>Page size 1, a STARTSWITH filter on the probe IDs, one entity updated after the first page that contained a test entity.</sub> | observed | #156–#164 |

### WS0.04 Duplicate POST

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does a duplicate POST return? | 200, and the entity was left unchanged.<br><sub>Duplicate POST returned 200. Read back by search.</sub> | observed | #48–#50 |

### WS0.05 Error bodies

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What does GET of a missing entity return? | 500: no content type, empty body | observed | #32 |
| What does a search with an unknown filter operation return? | 400: application/problem+json, JSON object with keys [errors, status, title, traceId, type] | observed | #33 |
| What does a search with an unknown objectClass return? | 500: no content type, empty body | observed | #34 |
| What does a POST without an ID return (C4)? | 500: no content type, empty body | observed | #74 |
| What does a malformed JSON body return? | 400: application/problem+json, JSON object with keys [errors, status, title, traceId, type] | observed | #75 |
| What does a POST with a non-string attribute value return (P20)? | 400: application/problem+json, JSON object with keys [errors, status, title, traceId, type] | observed | #76 |
| What does a PUT to a missing entity return (C2)? | 200: no content type, empty body. A read-back by search does not find it, so the 2xx did not create anything. | observed | #77 #79 #80 |
| What does a DELETE of a missing entity return (C3)? | 200: no content type, empty body | observed | #78 |

### WS0.06 Read-only fields

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which fields does a search item carry? | attributes, baseType, directArchetypes, isArchetypes, lastEvaluated, name, uid (as documented).<br><sub>From one search result. Only field names are reported.</sub> | observed | #28 |
| In which format is `lastEvaluated` reported? | ISO-8601, no fraction, Z | observed | #28 |
| Does GET by ID work for an entity that exists (C1)? | No: 500, no content type, empty body. Reads by ID need `byIdLookup: search` (P21). | observed | #29 |
| Which fields does the read model (GET) carry? | Cannot tell: GET returned 500. | inconclusive | #29 |
| Which keys appear on relationships in the read model? | Cannot tell: GET returned 500. | inconclusive | #29 |

### WS0.07 Edge semantics

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| What happens to a relationship to a missing entity on POST (A7)? | Cannot tell: a read this check depends on returned 500.<br><sub>POST 200.</sub> | inconclusive | #46 #47 |
| Does a relationship in the target's POST link it to an existing source? | Cannot tell: a read this check depends on returned 500.<br><sub>POST B 200, carrying the same relationship as A's.</sub> | inconclusive | #52 #53 |
| Does GET of the target show the relationship, with the other entity as source? | Cannot tell: a read this check depends on returned 500. | inconclusive | #54 |
| Does writing the source without the relationship delete it (P15)? | Cannot tell: a read this check depends on returned 500.<br><sub>PUT A 200.</sub> | inconclusive | #55 #56 |
| Does a relationship in the target's PUT link the entities? | Cannot tell: a read this check depends on returned 500.<br><sub>PUT B 200.</sub> | inconclusive | #57 #58 |
| Does writing the target without the relationship delete it (Q07.8)? | No: A still lists A → B. A write only removes relationships where the entity is the source.<br><sub>PUT B without the relationship: 200.</sub> | observed | #59 #60 |
| What happens to a relationship to a missing entity on PUT? | Dropped silently: PUT succeeded and only the relationship to the existing entity remains. | observed | #61 #62 |
| Do the relationship endpoints remove and add one relationship? | DELETE 200: A still lists A → B. POST 200: added back. The connector does not use them (P15). | observed | #63–#66 |
| Does DELETE remove the relationships touching the entity? | No: A still lists a relationship to the deleted entity.<br><sub>DELETE 200 (no content type, empty body); GET of the deleted entity then returns 200.</sub> | observed | #67–#69 |

### WS0.08 Type discrimination

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which base types occur? | Resource: 58, Subject: 14<br><sub>Sample of 72 entities over at most 5 pages.</sub> | observed | #30 |
| Do archetypes discriminate types? | Not on their own: ThirdPartyApplication appear with more than one base type.<br><sub>Archetypes read from directArchetypes and isArchetypes together. 72 of 72 entities have no direct archetype, so P19's scope archetype will only appear once the connector writes them. 7 distinct archetype sets: CreditCardApplication ×1, Customer ×8, Account ×47, (none) ×2, Company ×7, ThirdPartyApplication ×6, Publisher ×1</sub> | observed | #30 |

### WS0.09 ID constraints

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Is an ID with space accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-id%20with%20space</sub> | observed | #81–#86 |
| Is an ID with slash accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-id%2Fwith%2Fslash</sub> | observed | #87–#92 |
| Is an ID with question accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-id%3Fq%3D1</sub> | observed | #93–#98 |
| Is an ID with hash accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-id%23hash</sub> | observed | #99–#104 |
| Is an ID with plus accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-id%2Bplus</sub> | observed | #105–#110 |
| Is an ID with percent accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-id%2525</sub> | observed | #111–#116 |
| Is an ID with unicode accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-id%C3%BC-%C3%B1-%E6%BC%A2</sub> | observed | #117–#122 |
| Is an ID with dot accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-id.with.dots</sub> | observed | #123–#128 |
| Is an ID with length 64 accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #129–#134 |
| Is an ID with length 255 accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #135–#140 |
| Is an ID with length 256 accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #141–#146 |
| Is an ID with length 1024 accepted and addressable when percent-encoded? | Created, but neither GET by the encoded ID (500) nor a search finds it.<br><sub>Path used: /admin/entities/ws0-probe-20261121-190350-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx</sub> | observed | #147–#152 |
| Are IDs case-sensitive? | Yes: the lower-cased ID is not found (by search). | observed | #153–#155 |

### WS0.10 Auth and limits

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| How does the service respond to a missing, wrong and valid `X-API-KEY` key? | No key: 401. Wrong key: 401. Valid key: 500.<br><sub>Checked with a GET of an entity ID that does not exist, so a valid key should get 404. The key is sent raw in the `X-API-KEY` header. The service uses one shared key, so a 403 is not expected.</sub> | observed | #1–#3 |
| Does the service advertise rate limits? | No rate-limit or Retry-After headers were seen in 164 responses.<br><sub>The probe does not try to trigger throttling. A 429 policy must come from the service team.</sub> | inconclusive |  |

### WS0.15 Decision checks (P18, P20, P22)

| Question | Answer | Confidence | Evidence |
|---|---|---|---|
| Which create body does the service accept? | The documented EntityDTO is not accepted; the first variant that is: EntityDTO + attributes.ID. The rest of this run writes that way, and the connector's payload builder must too.<br><sub>Each variant changes one thing between the documented EntityDTO and the design document the first run sent. Archetype used: Ws0Probe. 1. documented EntityDTO → 500; 2. EntityDTO + attributes.ID → 200; 3. EntityDTO + a direct archetype → 500; 4. EntityDTO + attributes.ID + a direct archetype → 200; 5. EntityDTO without empty arrays → 500; 6. EntityDTO, path with a trailing slash → 500; 7. design casing (ID, BaseType, Relationships) → 500; 8. design document as the first run sent it → 200</sub> | observed | #4–#11 |
| Which base type does each numeric objectClass select (P18, C6)? | 4 = Subject, 2 = Resource, as P18 assumes.<br><sub>Search results name the base type, so a numeric objectClass shows its meaning. `4` → Subject ×14; `2` → Resource ×20; `"Subject"` → Subject ×14; `"Resource"` → Resource ×20</sub> | observed | #16–#19 |
| Which archetypes do the manifests define (P22)? | Cannot tell: 200, but not the documented list of manifests. Outline: {paths: list of 1 × {entities: object (0 fields), entityRelationships: list of 0, policies: object (0 fields), manifests: object (4 fields)}}.<br><sub>The outline gives field names and types only, never values.</sub> | inconclusive | #31 |
| Are entities from earlier probe runs still in the service? | Yes: 19, from run(s) 20261121-155100 ×19. Remove them by hand; their IDs are in the detail.<br><sub>ws0-probe-20261121-155100-CaseTest, ws0-probe-20261121-155100-edge-A, ws0-probe-20261121-155100-edge-B, ws0-probe-20261121-155100-id with space, ws0-probe-20261121-155100-id#hash, ws0-probe-20261121-155100-id%25, ws0-probe-20261121-155100-id+plus, ws0-probe-20261121-155100-id.with.dots, ws0-probe-20261121-155100-id/with/slash, ws0-probe-20261121-155100-id?q=1, ws0-probe-20261121-155100-idü-ñ-漢, ws0-probe-20261121-155100-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, ws0-probe-20261121-155100-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, ws0-probe-20261121-155100-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, ws0-probe-20261121-155100-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx, ws0-probe-20261121-155100-never-created, ws0-probe-20261121-155100-seed-1, ws0-probe-20261121-155100-seed-2, ws0-probe-20261121-155100-seed-3</sub> | observed | #35 |
| Which relationship form does a create accept (P17)? | P17 `{sourceID, targetID, type}` → 200; design `{TargetID, type}` → 200.<br><sub>Whether the relationship was stored needs GET, which fails (C1); this only shows the body is accepted.</sub> | observed | #36–#38 |
| Is an entity created with base type 4 a Subject (P18)? | Cannot tell: the created entity is not found by search. | inconclusive | #41–#45 |
| Does an update that omits an attribute remove it (P20)? | Cannot tell: the entity is not found after its create and update (by search; C5). | inconclusive | #70–#73 |
| Does an update replace directArchetypes in full (P22)? | Not tested: it needs two Resource archetypes, from the manifests or --archetypes. | not-tested |  |

## Suggested values

These follow from the answers above. Check them before changing any code.

| Setting | Suggested value | Where it goes |
|---|---|---|
| Search request | `POST /admin/entities/search` with a JSON body | `RestClientEntityApi.search` |
| Create body accepted | EntityDTO + attributes.ID | `EntityPayloadBuilder`, `EntityDocument.toWire` |
| Relationship form accepted | P17 {sourceID, targetID, type} | `EdgeCodec` (P17) |
| Search drops deleted entities | — | delete verification; WS10 orphan handling |
| Base-type numbering (objectClass) | P18 (4 = Subject, 2 = Resource) | `EntityKind` (P18) |
| Base type of a created entity | — | `EntityKind` (P18); gates WS1.02 |
| GET by ID | fails (500) | `byIdLookup` default (P21) |
| ID filter field | `ID` | `SearchPlanner`, by-ID search fallback (P21) |
| Page size field | `pageSize` | `PageCursor.toQuery` |
| Cursor | response `pagedResultsCookie` → request `pagedResultsCookie` | `PageCursor` |
| Response envelope | object, items under `results`, total in `totalResults` | `PageCursor.next`, `FakeAcceleratorService.search` |
| `lastEvaluated` format | ISO-8601, no fraction, Z | `EntityDecoder` |
| Ambiguous archetypes | ThirdPartyApplication | `TypeScoper` (P19) |
| Leftover probe entities | 19 | remove by hand |
| Manifest archetypes | — | `objectTypes` archetypes; Test operation (P22) |

## Cleanup

**Some probe entities may remain.** Remove these by hand:

- ws0-probe-20261121-190350-id/with/slash still found by search after cleanup

**Removal not confirmed** for 32 entities. DELETE answered 2xx and search no longer finds them, but GET fails (C1) and search has not been shown to drop deleted entities (`search.reflectsDelete`). Check them by hand:

- ws0-probe-20261121-190350-shape-2
- ws0-probe-20261121-190350-shape-4
- ws0-probe-20261121-190350-shape-8
- ws0-probe-20261121-190350-seed-1
- ws0-probe-20261121-190350-seed-2
- ws0-probe-20261121-190350-seed-3
- ws0-probe-20261121-190350-rel-target
- ws0-probe-20261121-190350-rel-p17
- ws0-probe-20261121-190350-rel-design
- ws0-probe-20261121-190350-delete-check
- ws0-probe-20261121-190350-basetype-4
- ws0-probe-20261121-190350-basetype-2
- ws0-probe-20261121-190350-edge-A
- ws0-probe-20261121-190350-edge-B
- ws0-probe-20261121-190350-attrs
- ws0-probe-20261121-190350-never-created
- ws0-probe-20261121-190350-id with space
- ws0-probe-20261121-190350-id?q=1
- ws0-probe-20261121-190350-id#hash
- ws0-probe-20261121-190350-id+plus
- ws0-probe-20261121-190350-id%25
- ws0-probe-20261121-190350-idü-ñ-漢
- ws0-probe-20261121-190350-id.with.dots
- ws0-probe-20261121-190350-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
- ws0-probe-20261121-190350-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
- ws0-probe-20261121-190350-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
- ws0-probe-20261121-190350-len-xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
- ws0-probe-20261121-190350-CaseTest
- ws0-probe-20261121-190350-stab-1
- ws0-probe-20261121-190350-stab-2
- ws0-probe-20261121-190350-stab-3
- ws0-probe-20261121-190350-stab-4

 7 other probe IDs were never accepted by the service, and no search finds them.

## Not answerable by the probe

These need the service team. They are listed in `docs/service-contract.md`:

- Whether anything other than IDM writes relationships (design assumption A1).
- 403 semantics, service account scoping and the rate-limit policy.
- Documented limits on ID length and characters, as opposed to what happens to be accepted today.
- Whether ordering and paging behaviour are guaranteed or incidental.
- Why GET by ID fails (C1), and the names of the ten filter operations (P21).
