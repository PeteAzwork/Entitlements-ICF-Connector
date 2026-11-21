# Service Contract — Graph Entity Service Admin API

**Workstream:** WS0 (delivery plan §4 and §12)
**Status:** Open. The service's OpenAPI definition arrived on 2026-11-21 and answers much of the register (section 5). It also shows that the design's model of the API is wrong in several places, so the design needs revising (plan WS0.14, section 6). Relationship writes are confirmed as full-state (P15). Drift detection is deferred to the plan's final workstream (P16). Design questions D2 and D5–D9 are decided (P17–P22, `docs/design-decisions-2026-11-21.md`).
**Last updated:** 2026-11-21

This is the record of what the connector may rely on about the remote service. Each question has one of three states:

- **Open:** not yet answered. The code runs on the stated assumption.
- **Confirmed:** observed on a running instance or stated by the service team, with evidence.
- **Agreed assumption:** not verifiable, but the service team has agreed the connector may rely on it. Record who agreed and when.

When a question changes state, apply WS0.12 in the delivery plan: change the code and tests it names, and add a decision-log entry in plan §3.

---

## 1. How to collect the answers

Most questions can be answered by observation with the contract probe in `contract-probe/`. Its first real run was on 2026-11-21 (section 7). It was then realigned to the OpenAPI definition and decisions P17–P22 (plan WS0.15): it writes the service's EntityDTO shape and reads back by search wherever GET by ID fails.

**Step 1. Read-only run.** This is safe against any environment, because it only reads. It sends GET requests and search POSTs to `/admin/entities/search`, which read, and it refuses a search path that would create entities. It also reads `/admin/manifests`, recording only archetype names:

```bash
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --api-key KEY"
```

**Step 2. Write run, against a test instance only.** Add `--remove-leftovers` to also delete the entities earlier probe runs left behind (IDs starting `ws0-probe-`; IDs containing `/` cannot be addressed and are listed instead). It creates, changes and deletes entities whose IDs start with `ws0-probe-<run>-`, and deletes them all at the end, even after a failure. It never writes to any other entity. It creates one entity with base type 4, so it is the run that confirms or refutes P18 before anything else creates entities. The archetype check (P22) stamps two Resource archetypes on a probe entity: the ones given with `--archetypes A,B`, else the first two the manifests define, else two probe archetypes (`Ws0ProbeA`, `Ws0ProbeB`). The service accepts archetype names no manifest defines.

**Settle window.** The service applies writes with a short delay: search lags (C5), and GET fails while an entity is re-evaluated (C1). So every read-back after a write waits, polling, for up to `--settle-seconds` (default 10) until the write is visible. A "No" answer then means "not within the window". Raise the window if answers look like timing rather than behaviour; `--settle-seconds 0` reads once, as the first runs did.

```bash
./gradlew :contract-probe:run --args="--base-url https://graph.test.example --api-key KEY --allow-writes"
```

**Output** goes to `build/contract-probe/<run>/`:

| File | Contents |
|---|---|
| `report.md` | Answers grouped by WS0 task (the decision checks under WS0.15), suggested configuration values and cleanup status |
| `findings.json` | The same, machine-readable |
| `exchanges.jsonl` | Every request and response, numbered, so answers can cite evidence such as `#12` |
| `wiremock/mappings/` | Each exchange as a WireMock stub, the raw material for WS0.11 |

**Safe to share.** No output contains request headers or credentials. Every entity the probe did not create is scrubbed: its ID and attribute values are replaced, and its shape (base type, archetypes, field names, relationship types, timestamp formats) is kept. A search filter's value is aliased or redacted unless it is a probe ID, and manifests are reduced to archetype names, because their instance lists can name entities. Tests in `ProbeOutputSpec` enforce this.

**What the probe needs.** The service's API key, given with `--api-key`. That option keeps the key in shell history and shows it in the process list. To avoid that, leave it out and set the `GRAPH_PROBE_API_KEY` environment variable instead, or another variable named with `--api-key-env`. If both are given, `--api-key` wins. The key never appears in any output file. It is sent raw in the `X-API-KEY` header; `--api-key-header` overrides the name. Because every consumer shares one key, the key used for the write run can write anywhere that key is valid. Point `--allow-writes` only at a test instance, and check T11 below before assuming test and production keys differ. There is no option to disable TLS verification.

---

## 2. Question register

"Probe" names the finding key in `report.md` that answers the question. "Assumes" is what the code does today. "Changes" is what to update once the question is answered.

### WS0.02 — Search endpoint

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q02.1 | Which request serves search? | **Answered:** `POST /admin/entities/search` with a JSON body; `{}` is the unfiltered search. `GET /admin/entities` returns 405 | `search.path` | Done 2026-11-21 (P14): `EntityApi.search` takes body fields, plus the fake service, probe and specs | **Confirmed** by the project owner, 2026-11-21. Probe run `20261121-150804` #4 and #5 showed the 405 |
| Q02.2 | What is the request-body field that filters by last-updated time called? | None: the connector refuses timestamp searches (P16) | — | WS10.01 | **Answered by the definition:** the search has no timestamp filter. Deferred to WS10 (P16) |
| Q02.3 | Which datetime formats does it accept, at what precision? | — | `document.lastEvaluated` reports the format of `lastEvaluated` | WS10 | **Moot** until the service offers a timestamp filter (P16) |
| Q02.4 | Is the filter strictly-after or inclusive? | — | — | WS10 | **Moot** until the service offers a timestamp filter (P16) |
| Q02.5 | Does the search return updated entities as well as created ones? | — | `search.createdVisible` (write run) checks created entities are found at all (C5) | WS10 | **Moot** for timestamps (P16). C5 is open |

### WS0.03 — Paging protocol

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q03.1 | What is the response envelope? | **Answered by the definition:** `results`, `pagedResultsCookie`, `totalResults`, `hasMore` | `paging.envelope` | Done in WS0.14 (`PageCursor`, `FakeGraphService.search`) | **Documented**; probe confirms |
| Q03.2 | Which request-body field sets the page size? | `pageSize` (definition; default 100) | `paging.params` | Done in WS0.14 | **Documented**; probe confirms |
| Q03.3 | Offset, page number or cursor, and which request-body field? | An opaque `pagedResultsCookie`, sent back as returned (definition) | `paging.params` | Done in WS0.14 | **Documented**; probe confirms |
| Q03.4 | What is the maximum page size? | 1000 (`ConnectorConfig.MAX_PAGE_SIZE`) | `paging.maxPageSize` | `MAX_PAGE_SIZE`, `ConnectorConfigSpec` | **Partly:** no cap at or below 91, the instance's whole content (run `20261121-173141` #13 #14) |
| Q03.5 | Is ordering stable across pages, and what is it ordered by? | Stable | `paging.ordering` | Design §9.4 if not | **Confirmed:** stable, sorted by ID (run `20261121-173141` #15 #16) |
| Q03.6 | Can an entity updated mid-enumeration be skipped? (Returned twice is harmless.) | Not skipped | `paging.updateDuringEnumeration` (write run) | Risk register §9. A skip would undermine target recon | **Observed:** neither skipped nor duplicated, page size 1 (`190350` #156–#164) |

### WS0.04 — Duplicate POST

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q04.1 | What does a POST of an existing ID return? | 409, mapped to `AlreadyExistsException` (§13) | `create.duplicate` (write run) | `HttpErrorMapper`. If it upserts, raise a §9 risk and decide on GET-before-POST | **Answered:** 200, and the entity is replaced (upsert), visible after about 0.5 s (`211859` #72–#75). The connector never sees `AlreadyExistsException` |

### WS0.05 — Error bodies

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q05.1 | What do 400, 404 and 409 bodies look like? | Unparsed, logged after redaction (§13) | `errors.*` | `HttpErrorMapper.redact` if bodies echo input | **Partly:** 400s are problem details with `errors`, `status`, `title`, `traceId`, `type` (run `20261121-173153` #33 #34). 500s have no body. No 404 or 409 seen yet |
| Q05.2 | Can an error body echo request content, such as attribute values? | Possibly, so redact | `errors.postWithoutId`, `errors.malformedJson` | Logging rules (WS3.15) | Open |
| Q05.3 | What does a PUT to a missing entity return? | 404, mapped to `UnknownUidException` | `errors.putMissing` (write run) | `HttpErrorMapper`. An upsert changes recon handling | **Answered:** 200, and the entity is created (C2). The connector can never see `UnknownUidException` on update |

### WS0.06 — Read-only fields

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q06.1 | Which field carries last-updated, in what format? | `lastEvaluated`, read-only (definition). Its format | `document.lastEvaluated` | `EntityDecoder` | **Documented**; format open |
| Q06.2 | Do search and GET return the same document shape? | **Answered by the definition:** no. Search items and the read model differ (section 5; design §2.1) | `document.searchFields`, `document.readModelFields` | Done in WS0.14 (`EntityDocument.fromEntity`, `fromSearchItem`) | **Documented**; probe confirms |
| Q06.3 | What happens if a PUT carries the last-updated field? | Never sent: the write model has no such field | — | None | **Moot** |
| Q06.4 | Do relationship entries carry more than `TargetID`/`SourceID`/`type`? | No | `document.edgeKeys` | `Edge.fromWire` strictness | Open |

### WS0.07 — Edge semantics

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q07.1 | Does POST silently drop edges to missing entities? (A7) | Yes | `edges.postToMissing` | Design §9.1 if not | Open |
| Q07.2 | Does an inbound edge in a POST link to an existing entity? | Yes (§2.2) | `edges.inboundOnCreate` | Design §9.1 self-healing depends on it | Open |
| Q07.3 | Does GET of the target show the relationship, with the other entity as `source`? | Yes | `edges.getShowsInbound` | `EntityDecoder`, recon comparison | Open |
| Q07.4 | Does a write that omits a relationship delete it? | **Answered:** yes. Relationships can be written through the entity's `relationships` collection on create and update, or through `POST /admin/entities/{id}/relationships`. Any relationship missing from the collection is removed | `edges.putOmissionDeletesInbound` | Done: decision P15 keeps the design's full-state model | **Confirmed** by the project owner, 2026-11-21 |
| Q07.5 | Does an inbound edge in a PUT link the entities? | Yes | `edges.inboundOnPut` | Design §8 | Open |
| Q07.6 | Is a PUT edge to a missing entity dropped, rejected or kept? | Dropped silently (the fake's behaviour) | `edges.putToMissing` | `FakeGraphService.store`. If dropped, consider a verification GET after PUT | **Answered:** dropped silently. A PUT with relationships to an existing and a missing entity kept only the first (`190350` #61, #62) |
| Q07.7 | Does DELETE cascade the entity's edges? | Yes | `edges.deleteCascades` | Design §9.3 | Open |
| Q07.8 | Does a write of entity A also remove relationships where A is the **target** and that are missing from A's collection? | Yes (design A2 gathers both directions from IDM) | `edges.targetOmission` (write run) | `buildRelationships` (WS5.05): whether each entity's collection must list inbound relationships | **Answered: no.** Writing B without the A → B relationship left it in place (`190350` #59, #60). Whether writing the source without it removes it (P15) is still unobserved |

### WS0.08 — Type discrimination

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q08.1 | Is BaseType only ever 2 or 4? | Yes. Anything else decodes to null (`EntityKind.fromBaseType`) | `types.baseTypes` | `EntityKind` | **Confirmed on this instance:** 77 Resources and 14 Subjects, nothing else (run `20261121-173141` #19) |
| Q08.2 | Do archetypes, with BaseType, discriminate IDM types? | A scope archetype per type (P19) | `types.archetypes` | `TypeScoper`, P19 | **Partly:** search reports directly assigned archetypes in `isArchetypes`, by design (C9), so scoping reads it (P19, amended). `ThirdPartyApplication` occurs with both base types (`173141` #19) |

### WS0.09 — ID constraints

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q09.1 | Which characters are allowed, and are they addressable percent-encoded? | Any, encoded as one path segment (`RestClientEntityApi.encodeSegment`) | `ids.*` | `encodeSegment`, `RestClientEntityApiSpec` | **Answered:** a space, `?`, `#`, `+`, `%`, dots and non-ASCII letters are stored verbatim and addressable when percent-encoded. They were found by search and deleted by path (`211859` #214–#323). **`/` is not addressable:** an ID containing it is stored, but DELETE by its encoded path leaves it in place (#221–#247) |
| Q09.2 | What is the maximum ID length? | None | `ids.length *` | Discovery tool validation (WS6) | **Partly:** IDs of 64, 255, 256 and 1,024 characters are stored, found and deleted (`211859` #294–#323). No limit found |
| Q09.3 | Are IDs case-sensitive? | Yes | `ids.caseSensitivity` | Risk register §9: case-insensitive IDs can merge entities | **Confirmed:** yes. The entity was in search, and a search for the lower-cased ID did not find it (`211859` #324–#327) |

### WS0.10 — Auth and limits

| ID | Question | Assumes | Probe | Changes | Status |
|---|---|---|---|---|---|
| Q10.1 | How does the service authenticate, and how are missing, wrong and valid credentials answered? | **Answered:** one raw key in the `X-API-KEY` header; a missing key gets 401 and a wrong key gets 401 | `auth.status` | Done 2026-11-21 (P13): probe, `ConnectorConfig`, customizer contract, redaction, provisioner sample, fixtures, specs | **Confirmed** by the project owner, 2026-11-21. The probe will observe it on its first run |
| Q10.2 | When does the service return 403 rather than 401? | **Answered:** not expected. There is one key with no permission levels | Not observable | None. `HttpErrorMapper` keeps its 403 mapping as a safe default | **Confirmed** (follows from Q10.1), 2026-11-21 |
| Q10.3 | Are there rate limits, and does 429 carry `Retry-After`? | 429 with `Retry-After` in seconds or as an HTTP-date | `limits.headers` (partial) | `HttpErrorMapper.retryAfterSeconds`, queued sync settings | Open: no rate-limit headers in 55 responses (run `20261121-173153`); the policy needs the service team |

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

These are questions about Ping IDM's Scripted REST harness, not about the service. They are recorded here so that every external contract lives in one place. They were answered in WS3.01 (2026-11-21) from Ping's ICF documentation: the Scripted REST connector reference, "Custom configuration initialization", "ICF operations with Groovy scripts" and the per-script references (create, update, delete, search, schema, test) under `docs.pingidentity.com/openicf/`. The connector bundle's own sample scripts are on ForgeRock's customer Stash server and were not reachable.

| ID | Question | Answer | Status |
|---|---|---|---|
| H1 | Which binding carries the HTTP client the customizer's `decorate` closure returns? | `customizedConnection`. The reference's sample script reads `def httpClient = connection as HttpClient` and `def connection = customizedConnection as RESTClient`: `connection` is the Apache client built from `init`, and `customizedConnection` is what `decorate` returns. (Its prose says "the `connection` variable is the output of `decorate`", which describes the sample's local variable.) The scripts take `customizedConnection` when bound, else `connection`; `ScriptSupport` wraps a bare `HttpClient` in a `RESTClient` itself, so authentication holds either way | Answered |
| H2 | What are the signatures of the customizer's `init`, `decorate` and `release` closures? *Confirmed in IDM 7.5 (WS8): the customizer runs and every request carries the key, whichever branch `decorate` took.* | `init { HttpClientBuilder builder -> }` with the `ScriptedRESTConfiguration` as the closure's delegate (`def c = delegate as ScriptedRESTConfiguration`); it returns nothing. `decorate` returns the object bound as `customizedConnection`; its argument is not shown, and is taken to be the built `HttpClient`. `ClientCustomizer.decorate` accepts anything: given an `HttpClient` it wraps it, otherwise it sets the key header on the `RESTClient` itself. `release` is named in the dev guide (some versions say `destroy`); ours does nothing | Answered, `decorate`'s argument assumed |
| H7 | Is `password` the right home for the API key, or does `customSensitiveConfiguration` reach the scripts decrypted? | The reference is silent. `password`, read through `GuardedString.access()` (P13), stays | Open |
| H3 | Does a create script receive an `id` binding? | Yes: "The UID of the object to be created, if specified … corresponds to the ICF `__NAME__` attribute". `CreateScript` restores `__NAME__` from `id` when the attributes lack it | Answered |
| H4 | May a search script return a `SearchResult` to pass the paged-results cookie? | Yes: "a `SearchResult` object or … a `String` that represents the `pagedResultsCookie`". The `handler` binding is a Closure, called with a `ConnectorObject` | Answered |
| H5 | Does the schema script return a `Schema`, or call `builder.schema { }`? *Confirmed in IDM 7.5 (WS8): `createFullConfig` yields both classes with their attributes and flags, and `disable` with SPI classes raised no WARN.* | It calls `builder.schema { objectClass { type …; attribute …; attributes { }; disable … } }` on the `builder` binding (an `ICFObjectBuilder`) and returns nothing. There is no `connectorClass` binding, so the previous script would have failed in IDM. `SchemaScript` now hands `SchemaFactory`'s schema to the DSL (`SchemaDsl`), disabling Sync, Authenticate and ResolveUsername per class. `disable`'s parameter type is not documented; SPI classes are passed, and a refusal is logged at WARN | Answered, `disable`'s argument assumed |
| H6 | Which `operation` values does the update script receive? | `UPDATE`, `ADD_ATTRIBUTE_VALUES` and `REMOVE_ATTRIBUTE_VALUES` are all in the operation list. `UpdateScript` refuses the latter two (WS3.10), because a value delta turned into a full-state PUT would delete whatever it omits (P15) | Answered |

To confirm against a real IDM: H2's `decorate` argument and H5's `disable` argument. Both have fallbacks, so neither can stop authentication or the schema; a wrong guess shows as a WARN in the connector log. *Both behave in IDM 7.5 (WS8); which `decorate` branch ran was not observed.*

### 4.1 Found in IDM 7.5 (WS8)

Running the connector inside the real IDM 7.5.0 image (`gcr.io/forgerock-io/idm:7.5.0`) settled these. `integration/` repeats every one of them.

| ID | Finding | What the deliverables now do |
|---|---|---|
| H8 | **Versions** in the IDM 7.5.0 image: Scripted REST connector 1.5.20.21, Groovy 3.0.9, Rhino 1.7.14, JDK 17.0.10, embedded DS repository. | The connector builds for JDK 17; the Rhino smoke test uses 1.7.14 (WS7.04). |
| H9 | **The library deploys as Groovy sources, or as a jar.** *Corrected 2026-11-21:* a first test suggested sources fail to compile (*"BUG! exception in phase 'semantic analysis' … The lookup for …ClientCustomizer caused a failed compilation"*). That was a symptom of H11's race, not of sources: with `createFullConfig` called before the provisioner is saved, sources deploy cleanly (three fresh starts, the full integration suite twice, a 300-user scale run, concurrent re-initialisation, and 16 concurrent first uses after a boot with the provisioner in `conf/`). Details in `script_vs_jar.md`. | Sources by default (`lib/` on the `classpath`); `-PlibraryAs=jar` for the jar. `:connector:compileWithIdmGroovy` compiles everything with IDM's Groovy 3.0.9 in CI. P9 holds. |
| H10 | **IDM does not fill in the provisioner's `objectTypes`.** With it empty, `system/graphentity/<type>` does not exist and reconciliation fails. It must come from `POST system?_action=createFullConfig`. | The runbook and the provisioner sample say so; `IdmStack` does it. |
| H11 | **Call `createFullConfig` before saving the provisioner.** Saving first and then calling it initialises the connector twice at once on its first use in a fresh IDM, which fails: with the library as a jar, a `LinkageError` (duplicate class definition in the bundle's class loader); as sources, also the Groovy compile error of H9. Two of three fresh starts failed that way; with the right order, none did. Once the connector has initialised, concurrent re-initialisation is safe, and a boot with the provisioner already in `conf/` initialises it once. `createFullConfig` also runs the Test operation. | Sequence: `createFullConfig`, then save its result once (or drop the full provisioner into `conf/` before IDM starts). |
| H12 | **A transform needs `"source": ""`.** A property mapping without a `source` key hands its transform an undefined `source`; with `""` it gets the whole source object. | The mapping generator and the sample set it; the lint requires it. |
| H13 | **Deleting a managed object raises `SOURCE_MISSING`** (target linked, source gone), not `MISSING` (source linked, target gone). The old policy table had `SOURCE_MISSING: REPORT`, so IDM deletes never reached the service. | Policies corrected: `SOURCE_MISSING: DELETE`, `MISSING: CREATE`; orphans (`UNASSIGNED`, `UNQUALIFIED`, `TARGET_IGNORED`) stay `REPORT` (P16). |
| H14 | **Configuration applies asynchronously.** A `PUT` of `config/sync` or of the provisioner returns before the change is in effect; `sync/mappings` reports a change before implicit sync acts on it. | `IdmStack` waits on evidence: a canary write for implicit sync, three consecutive passing Tests for the connector. |
| H15 | **A relationship changed from one end does not re-sync the other end** (even with `notify` or `notifySelf` on the relationship). | Harmless by design: both ends emit the same relationship, so the changed end's full-state write updates the service for both, and the other end's next write is derived from IDM's current state. |
| H16 | **IDM refuses to reconcile from an empty source** (*"Cannot reconcile from an empty data source, unless allowEmptySourceSet is true"*). | A safeguard against mass deletion; the generated mappings leave it at its default. |
| H18 | **IDM loads a `require`d script module afresh for each script evaluation.** Module-level state, such as the mapping library's configuration cache, does not survive between evaluations, nor between the entities of one reconciliation (WS8.08). | The library reads the configuration per evaluation; the cost is in the measured times. `RuntimeInIdmSpec` pins it. |
| H19 | **Queued sync retries refused writes.** With `queuedSync` on a mapping, updates the service answered with 429 were retried by IDM, on its own `retryDelay`, until they succeeded; each refusal was logged with its `Retry-After` hint (WS8.09). Whether IDM honours `Retry-After` itself was not observed. | `RuntimeInIdmSpec`. Runbook §4 describes queued sync for high-churn types. |
| H20 | **Timing note.** The test stand-in's JDK HTTP server leaves Nagle's algorithm on unless `sun.net.httpserver.nodelay` is set, which adds about 40 ms to each exchange with Apache HttpClient. The WS8 timings (68 ms per user) included it; with it off, the same run took 14 ms per user. A stand-in artefact, not a property of the connector or of the service. | `integration/build.gradle` sets the property; the runbook's figures come from runs with it set. |
| H21 | **IDM discards a provisioner save that arrives while it is still applying the previous one.** The second `PUT` is acknowledged (200), but the stored configuration, the file in `conf/` and the connector all stay on the first save. Seen every time with two saves sent back to back (IDM limited to 1 CPU, 2026-11-21); a gap of a second or more was safe there. Applying a save is slower in scripts mode (the library is recompiled) and on a small host, so the window is wider there. This was the first GitHub run's failure (WS8.12): a test saved a wrong key, the restore arrived during that save's recompilation, and the wrong key stayed in use. A save identical to the stored configuration is not applied at all. | `IdmStack.putProvisioner` waits until IDM has settled, marks each save, waits for the connector to reactivate, and checks that the stored configuration is its own, saving again if not. Runbook §7 and §8: one provisioner change at a time, then verify it. |
| H17 | **Through `system/graphentity`, a delete reads the object first**, so a missing entity is a 404 before the connector is called; `deleteMissingTolerated` applies when the service answers the DELETE itself with 404. | `ErrorPathsInIdmSpec` tests it that way. |

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

Also on the read side: `directArchetypes` (in search, always empty), `isArchetypes` (search only: the entity's archetypes, both those assigned through `directArchetypes` and those assigned by policy; C9), `lastEvaluated` ("last evaluation timestamp") and `reconciliationStarted` (Entity only).

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
| Q07.1 to Q07.8 | Not documented. Relationships carry both ends explicitly, and single-edge add and delete endpoints exist. Q07.4 is answered by the project owner (P15) | Partly answered |
| Q08.1 | Enum with five values, 0–4. Create samples: **2 = Subject, 4 = Resource** | Contradicts the design (section 5.5) |
| Q08.2 | `isArchetypes` and `directArchetypes` both exist | Partly answered |
| Q09.1 to Q09.3 | Not documented | Open |
| Q10.1 | `X-API-Key` header, 401 when missing or invalid | Confirmed (header names are case-insensitive) |

### 5.5 Contradictions to raise with the service team

Runs: `20261121-155100` (the first write run, design-shaped documents), `20261121-173141` (read-only), `20261121-173153` (write, EntityDTO) and `20261121-190350` (write, with the create-shape diagnosis) `20261121-211859` (write, with a 10 s settle window) `20261121-214027` (write, settled writes read on the written entity, with leftover removal) and `20261121-215954` (write, waiting for relationship outcomes), all against a test instance. Evidence numbers refer to each run's `exchanges.jsonl`.

| ID | Definition says | Probe observed |
|---|---|---|
| C1 | GET of an entity: 200 or 404 | **Unreliable.** 500 for a long-standing real entity (`173141` #18, `190350` #29), for missing IDs, and for a probe entity just after any write that touches it (`190350` #47–#58). 200 with the documented read model once the entity has been left alone (#60–#69). A missing entity never got 404. Reads by ID need `byIdLookup: search` (`173141` #9) Details and questions for the service team: [`C1-GET-BY-ID-NOTE.md`](../C1-GET-BY-ID-NOTE.md) |
| C2 | PUT to a missing entity: 404 | **200, and the PUT creates the entity (upsert).** `155100` PUT the never-created ID `…-never-created`, and that entity is still found by search (`190350` #35) |
| C3 | DELETE of a missing entity: 404 | 200 (`155100`; `173153` #36) |
| C4 | Invalid create: 400 | 500 for a create without an ID (`173153` #32). Malformed JSON and a non-string attribute do get 400 (#33 #34) |
| C5 | Create returns 200 | **Search lags behind writes.** An entity created at `190350` #41 was not found at #43 but was at #45. Six entities created at #4–#14 were missing from the total at #15 (91) and present at #21 (97). The lag is short but real, so a single search straight after a write proves nothing |
| C6 | Create samples: 2 = Subject, 4 = Resource | **The design's numbering holds:** an entity created with base type 4 is returned by a Subject search (`objectClass: 4`) as a `Subject` (`190350` #41, #45). `objectClass` reads agree (`173141` #5–#8) |
| C7 | DELETE's summary reads "Retrieves an entity by ID" | Probably a copy-paste slip; DELETE returns the deleted entity |
| C8 | Create takes EntityDTO: `id`, `baseType`, `attributes`, `relationships`, `directArchetypes`; 200 | **By design** (confirmed by the service owner 2026-11-21): every create and update must carry `attributes.ID` equal to `id`. Without it the write gets 500. Of 8 body variants, only the 3 carrying it were accepted (`190350` #4–#11). Reads do not return it (#35, #60). Design §2.1 and §8.3–§8.4 updated; connector change is plan WS0.19 |
| C9 | Search items carry `directArchetypes` (assigned) and `isArchetypes` (from policy) | **By design** (confirmed by the service owner 2026-11-21): a search item reports archetypes assigned through `directArchetypes` in `isArchetypes`, and its own `directArchetypes` field is empty. Seen with `shape-4` (`190350` #7, #35). P19 is amended to scope on `isArchetypes` (design §9.4) |
| C10 | DELETE removes the entity | **Deletes work, after a short delay:** a deleted entity was gone from search 518 ms after DELETE (`211859` #37–#43). Every probe entity of `211859` and all but one of `190350` were removed. **Exception: an ID containing `/` cannot be deleted by path.** DELETE answers 200, and the entity remains (`211859` #221–#247, and the `190350` slash entity). The 19 entities `155100` created are still there; their DELETEs may have raced their creates (C13) |
| C11 | `GET /admin/manifests` returns a list of manifests | 200 with `{paths: [{entities: {}, entityRelationships: [], policies: {}, manifests: {4 fields}}]}` (`190350` #31): an export bundle, with the manifests nested inside. Where the archetype names sit inside `manifests` is not yet known |
| C13 | Writes are applied in the order sent | **Not always.** A PUT sent straight after its POST never took effect: for 10 s+ search showed the POST's attributes, and `lastEvaluated` did not move (`211859` #176–#199). Where the probe waited for the create to show before updating, the update applied within about 1 s (#200–#205). Rapid successive writes to one entity can be lost |
| C14 | A search lists each entity once, and `totalResults` counts them | Search listed the same entity twice in one result list (`211859` #180–#199). `totalResults` said 91 while 99 items came back (#22) |
| C15 | GET shows every relationship touching the entity, current | **Withdrawn (run `214027`).** Relationships in GET are current at both ends: A listed A → B although only B had been written (`214027` #141). The `211859` observation, where B still listed A → B after A was written without it, was not a stale read. A's write had an empty `relationships` list, and that removes nothing (see P15 below) |
| C16 | Archetypes are defined in manifests (P22) | The manifests define Subject archetypes Account Owner, Developer and Legal Representative, and Resource archetypes Credit Card, Current Account, Financing, Insurance, Savings Account, Third Party Application and User Access Requests (`211859` #29). The existing entities carry Customer, Account, Company, ThirdPartyApplication, Publisher and CreditCardApplication, none of which a manifest defines. The service also accepts undefined names (the probe's `Ws0Probe`). P22's Test-operation check would reject configurations that use the names on the existing data |
| C17 | A write replaces the entity's relationships and attributes (P15, P20) | **Attributes: merged, by design.** An update that omits an attribute keeps it, and `""` is stored as an empty value (`214027` #229–#234; `215954` #182–#193). The connector sends `""` to clear and reads `""` as absent (P20 amended). **Relationships: a service bug.** An empty `relationships` list should purge all of the entity's relationships (service owner, 2026-11-21), but removed nothing within 10 s (`215954` #85–#136). The owner treats this as a bug to be fixed in the service. `directArchetypes` is replaced in full |
| C18 | Relationships replaced or deleted stay gone | **A replaced relationship came back.** R was rewritten with only R → T2, and GET showed only R → T2 (`215954` #154). After the single-relationship DELETE removed R → T2, R → T1 was listed again (#155–#157). The single-relationship POST did not add within 10 s (#158–#179). Probably the same defect as C17: a write hides relationships it leaves out rather than deleting them. The service owner states that rewriting an entity with a relationship it already has changes nothing, and that relationships are deleted with `DELETE /admin/entities/{sourceID}/relationships/{targetID}/{type}`. The probe now checks both on every run |
| C12 | An invalid search request: 400 | 500 for an unknown `objectClass` (`173141` #23); 400 with problem details for an unknown filter operation (#22) |

## 6. What this changes in the design

These need a decision before WS1 starts, because the configuration model, payload builder, decoder and search planner all depend on them.

| ID | Design assumption | Real API | Decision needed |
|---|---|---|---|
| D1 | Relationships are written in a full-state PUT, which deletes omitted edges (§2.2, §8) | Relationships carry both ends. They can be written in the entity's `relationships` collection or through the single-relationship endpoint, and the collection is full-state | **Decided 2026-11-21 (P15):** write the full set in the entity's `relationships` collection on every create and update. Q07.8 still decides whether inbound relationships must be listed |
| D2 | Each edge is expressed from one entity's perspective, as `TargetID` or `SourceID` (§2.2, §7) | Every edge names its source and target explicitly | **Decided 2026-11-21 (P17):** `{sourceID, targetID, type}`, the service's write shape |
| D3 | Incremental drift check by "updated since" search (§11) | No such search | **Deferred 2026-11-21 (P16)** to the final workstream, WS10, which starts by asking the service for a last-modified signal |
| D4 | Recon compares edges from search results (§9.4, §11) | Search results carry no relationships | **Deferred 2026-11-21 (P16)** to WS10. Until then, reconciliation re-sends relationships it cannot compare (plan WS4.08) |
| D5 | Numeric `BaseType`, 4 = Subject (§2.1, A4) | Enum 0–4. Samples give 2 = Subject, 4 = Resource. Search returns names | **Decided 2026-11-21 (P18):** numbers, with the design's numbering, 4 = Subject and 2 = Resource. Reads accept names. Contradicts the API's create samples, so WS0.15 must confirm it first |
| D6 | Type scoping by archetypes plus BaseType (§9.4, P5) | Search can scope by `objectClass` and filter on attributes | **Decided 2026-11-21 (P19):** a scope archetype per type, stamped as a direct archetype; enumerate by `objectClass` plus the scope archetype; reads by ID check only the base type |
| D7 | Attributes may be multi-valued (§6) | Write model attributes are string to string | **Decided 2026-11-21 (P20):** single-valued string attributes; multi-valued IDM properties excluded; empty values omitted |
| D8 | Only Equals on `__UID__`/`__NAME__` and "updated since" are searchable (§9.4, P7) | Filter trees with EQUALS, CONTAINS, STARTSWITH, AND, OR and NOT | **Decided 2026-11-21 (P21):** by ID, plus equals, contains and starts-with combined with and, or and not, sent by operation name. Search-on-ID fallback while C1 persists |
| D9 | Archetypes are stamped in the entity document | `directArchetypes` in the DTO, plus a separate assign endpoint. Archetypes are defined in manifests | **Decided 2026-11-21 (P22):** `directArchetypes` in the entity body; the Test operation checks the manifests define every configured archetype |

## 7. Status of the contract probe against this API

**First real run, 2026-11-21.** The read-side checks worked. The write checks sent the design's document shape (`ID`, `BaseType`, `Relationships` with `TargetID`/`SourceID`), and every GET by ID returned 500 (C1), so the write-mode answers were unreliable. The scrubber has since been fixed to recognise `uid`, `id` and the read model's attribute objects, and to fail closed. Any answer that depends on a failed read is reported as "cannot tell".

**Realigned, 2026-11-21 (plan WS0.15).** The probe now:

- writes the EntityDTO shape: lowercase `id`, numeric `baseType` (4 for a Subject, 2 for a Resource, P18), string attributes (P20), and relationships as `{sourceID, targetID, type}` (P17);
- reads the read model on GET and search items from the search, and reads back by an `EQUALS` filter on the ID whenever GET fails (C1). Relationships are only in the read model, so relationship answers stay "cannot tell" while GET fails;
- tries GET on an entity that exists (`get.existingEntity`), which the first run never did;
- adds checks for the decisions' open conditions:

| Finding | Question | Gates |
|---|---|---|
| `baseType.objectClass` | Read-only: which base type does each numeric `objectClass` select? | P18 (C6) |
| `baseType.create` | Is an entity created with base type 4 a Subject? | P18: any create against a shared service; WS1.02 |
| `search.idFilter` | Which filter field matches the entity ID? | P21: the by-ID search fallback |
| `search.createdVisible` | Is a newly created entity found by search? | C5 |
| `edges.targetOmission` | Does writing the target without a relationship delete it? | Q07.8: WS5.05 |
| `edges.relationshipEndpoints` | Do the single-relationship endpoints work? | P15 (informational: the connector does not use them) |
| `attributes.omission` | Does an update that omits an attribute remove it? | P20: WS2.07 |
| `archetypes.replace` | Does an update replace `directArchetypes` in full? | P22: WS2.07 |
| `manifests.archetypes` | Which archetypes do the manifests define? | P22: the Test operation; `objectTypes` archetypes |
| `errors.nonStringAttribute` | What does a non-string attribute value get? | P20 |
| `create.shape` | Which create body is accepted? Tries each difference between the EntityDTO and the design document, then writes the rest of the run in the first accepted shape | C8; `EntityPayloadBuilder` |
| `create.relationshipShape` | Is `{sourceID, targetID, type}` accepted in a create, and is the design's `{TargetID, type}`? | P17 |
| `search.reflectsDelete` | Does search stop returning an entity once it is deleted? | C10; cleanup proof |
| `probe.leftovers` | Read-only: which entities from earlier probe runs remain? | C10 |

The timestamp checks are gone: the service has no timestamp search (P16). Cleanup confirms each probe entity is gone by GET 404 or, while GET fails, by a search on its ID that finds nothing.

**First realigned runs, 2026-11-21** (`20261121-173141` read-only, `20261121-173153` write). The read side answered fully: search, the ID filter, paging, ordering, field names, base-type numbering by `objectClass`, and that GET by ID fails even for an existing entity. The write side answered nothing, because every EntityDTO create got 500 (C8). They also surfaced C9–C12. Since then the probe:

- tries each create variant once and adopts the first accepted shape for the rest of the run (`create.shape`), or skips the other write checks with that reason;
- checks the relationship form (`create.relationshipShape`) and whether search drops deleted entities (`search.reflectsDelete`);
- lists entities left by earlier probe runs (`probe.leftovers`) and treats them as probe data, so they no longer count in the type statistics;
- proves a removal only with a GET 404, or with a search miss once `search.reflectsDelete` has shown that search drops deleted entities. Otherwise the removal is listed as unconfirmed. IDs the service never accepted need no proof;
- describes an undocumented manifests response by its outline.

**Write run `20261121-190350`.** Checked clean before being kept: all 1,754 attribute values are scrubbed, probe IDs or probe test values. It settled four questions:

- **C8:** creates need `attributes.ID` equal to `id`.
- **P18:** base type 4 is a Subject on create.
- **Q07.8:** writing the target without a relationship does not delete it.
- **C2:** a PUT to a missing entity creates it.

It also showed that search reports directly assigned archetypes under `isArchetypes`, which the service owner confirms is by design (C9). C8 is also by design. The probe's readings suggested DELETE removes nothing, but the service owner reports deletes work (C10). The readings were probably taken before a delayed deletion applied. Several answers came back "cannot tell" because the probe read once, straight after a write: search lags slightly (C5), and GET fails while an entity is being re-evaluated after a write (C1).

**Done since (plan WS0.15e):** read-backs wait within the settle window, archetypes are read from either field, and archetype names are found anywhere in the manifests bundle.

**Write run `20261121-211859`** (10 s settle window), checked before being kept: no entity data, only the probe's own attributes and manifest, archetype and relationship-type names.

Answered:
- **P22:** an update replaces `directArchetypes` in full.
- **P18:** base type 4 is a Subject on create.
- **C10:** deletes work, after about 0.5 s.
- **Q04.1:** a duplicate POST replaces the entity.
- **Q09.1:** `/` is the one character that makes an ID unaddressable.
- **Q09.3:** IDs are case-sensitive.
- **Q03.6:** paging stays stable while entities change.
- Search lag is about 0.5 s.

Not answered:
- **P20:** lost to a write race (C13).
- **P15:** read on the wrong entity (C15).

New: C13–C16.

**Write run `20261121-214027`** (settled writes, read on the written entity, `--remove-leftovers`), checked before being kept: no entity data.

Answered:
- **P20: no, omitted attributes are kept.** An update merges attributes rather than replacing them.
- **P15, with an empty list: not observed in one read.** Writing an entity with `relationships: []` still showed its relationships when the write's marker appeared (C17). The service owner states that an empty list purges all relationships, so removal is probably applied later. WS0.15g waits for it.
- **Q07.8: no.** Writing the target without the relationship keeps it.
- **C15: withdrawn.** GET's relationships are current at both ends.
- **Leftovers:** 18 of the 21 earlier probe entities were removed. The four with `/` in their IDs (one per write run) cannot be addressed and need the service team.
- **Confirmed again:** P22 replaced in full, P18, C2 upsert, Q04.1 duplicate replaces, Q09.3 case-sensitive, deletes after about 0.5 s.

Still open (WS0.15g adds the checks):
- whether relationship removal appears once the probe waits for it (P15);
- whether a non-empty `relationships` list replaces the old one (P15);
- whether an empty string clears an attribute (P20);
- whether the single-relationship endpoints work;
- whether DELETE removes the relationships touching the entity. GET of the other end failed within the window for the last two.

**Write run `20261121-215954`** (waiting for relationship outcomes), checked before being kept: no entity data.

Answered:
- **P20:** an omitted attribute is kept, and one written as `""` is stored empty rather than removed (C17).
- **P15:** an empty `relationships` list removed nothing within 10 s, at either end (C17). This conflicts with the service owner's statement.
- **C18, a replaced relationship resurfaced:** a non-empty list appeared to replace the old one, but the old relationship came back once the new one was deleted through the single-relationship endpoint.
- **Endpoints:** the single-relationship POST did not add within 10 s.
- **Leftovers:** only the four entities whose IDs contain `/` remain, plus this run's.

**Next:**
- **Relationship storage:** P15 and C18 need the service team, or a look at how the service stores relationships. The probe can only see GET's view, and that view changes in ways no simple rule explains.
- **Probe (plan WS0.15h):** check each relationship test's starting state before acting, so a void result like the delete cascade's is reported as such.
- **Owner (plan WS0.22):** decide how the connector clears an attribute, given that `""` is stored rather than removed.

**Next:**
- **Probe (plan WS0.15f):**
  - wait for each write to apply (its `lastEvaluated` advances, or a marker shows) before writing that entity again;
  - read relationship outcomes on the entity that was written;
  - offer to remove earlier runs' leftover entities now that deletes work.
- **Owner decisions:**
  - **C16:** whether the Test operation's manifest check should warn rather than fail.
  - **IDs containing `/` (Q09.1):** whether the connector should reject them.
- **Service team:** C1 (GET fails for long-standing entities), C13 (lost updates) and C14 (duplicate search items).

**Earlier next steps:**
- **Probe (plan WS0.15e, now done):** wait for a write to show up before reading it back. Poll search, retry GET within a short settle window, and read archetypes from either field. Find the archetype names inside the manifests bundle (C11). When the manifests give none, use probe archetypes for the P22 check, since the service does not validate archetype names.
- **Connector (plan WS0.19):** send `attributes.ID` on every write (C8, by design). Read archetypes from `isArchetypes` (C9, by design; P19 amended). Done in the specs, fakes and probe; the implementation follows in WS2 and WS4.
- **Leftovers:** the next run's `probe.leftovers` will show whether the entities from `155100` and `190350` have been deleted (C10).
- **Service team:** raise C1 first. C8 and C9 are by design. C10 is reported as working.

## 8. Change log

| Date | Change |
|---|---|
| 2026-11-21 | H18–H20: IDM loads modules per script evaluation; queued sync retries 429s; the WS8 timings were inflated by the stand-in's Nagle delay and are re-measured. |
| 2026-11-21 | H9 corrected: the library deploys as Groovy sources after all; the compile failure was H11's race. Scripts are the default deployment again, with the jar as an option (`script_vs_jar.md`). |
| 2026-11-21 | WS8: the connector run inside IDM 7.5.0. H2 and H5 behave; section 4.1 adds what only a real IDM showed (H8–H17): generate the provisioner's object types with `createFullConfig` before saving it, `"source": ""` on the library transforms, and `SOURCE_MISSING` is the situation a delete raises. |
| 2026-11-21 | WS3.01: harness contract answered from Ping's ICF documentation (section 4). H1, H3, H4, H6 answered; H2 and H5 answered with one assumed argument type each; H7 open. The schema script must use the `builder` DSL, which the previous script did not. |
| 2026-11-21 | Added `C1-GET-BY-ID-NOTE.md` at the repository root for the service team. Every failing GET (and the C4/C8 POST 500s) looks like an unhandled exception: Kestrel, empty body, no `x-request-id`. GET fails for a missing ID, for a long-standing entity, and while an entity's relationship points at a missing entity or after the single-relationship DELETE. |
| 2026-11-21 | Service owner: relationships are deleted with `DELETE /admin/entities/{sourceID}/relationships/{targetID}/{type}`. An empty list should purge, and the current behaviour is a bug (C17). Rewriting with the same relationship changes nothing. P20 amended (option a): `""` clears an attribute and reads as absent. Probe WS0.15h: starting-state checks, resurfacing detection, `edges.reassertSame`. |
| 2026-11-21 | Folded in write run `20261121-215954`. An empty `relationships` list removed nothing within 10 s (C17), against the service owner's statement. Added C18: a replaced relationship resurfaced once its replacement was deleted, and the single-relationship POST did not add. An attribute written as `""` is stored empty, not removed. |
| 2026-11-21 | Service owner: an empty `relationships` list purges all of the entity's relationships (P15). C17's relationship half is reframed as probable delayed removal. Probe WS0.15g waits for removals, and adds checks for non-empty replacement, clearing an attribute with an empty string, and the endpoints and delete cascade on entities of their own. |
| 2026-11-21 | Folded in write run `20261121-214027`. P20 answered: omitted attributes are kept (merge). An empty `relationships` list removes nothing (C17), which challenges P15 as stated. Q07.8 answered (no). C15 withdrawn: GET's relationships are current at both ends. 18 leftovers removed; four IDs containing `/` remain for the service team. |
| 2026-11-21 | Owner decisions: IDs containing `/` are rejected by the connector (P23, from Q09.1), and the Test operation's manifest check warns rather than fails (P22 amended, from C16). Probe WS0.15f: settled writes with revision markers (C13), relationship outcomes read on the written entity (C15), and `--remove-leftovers`. |
| 2026-11-21 | Folded in write run `20261121-211859`. Answered P22 (replaced in full), C10 (deletes work after about 0.5 s), Q04.1 (duplicate POST replaces), Q09.1 (`/` not addressable) and Q09.3 (case-sensitive). Added C13 (rapid writes can be lost), C14 (duplicate search items), C15 (relationship changes refresh only the written entity) and C16 (manifest archetype names differ from those on existing entities). P15 and P20 still open. |
| 2026-11-21 | Probe WS0.15e: read-backs wait within a settle window (`--settle-seconds`, default 10) for writes to show. Archetypes are read from either field, probe archetypes are used when none are given, and manifest archetype names are found inside the export bundle. |
| 2026-11-21 | P19 amended by the owner: enumeration scopes on `isArchetypes` (C9). The specs, fake, fixtures and probe now send `attributes.ID` (C8) and read archetypes from `isArchetypes` (plan WS0.18, WS0.19). |
| 2026-11-21 | Service owner's clarifications: C8 (`attributes.ID` required) and C9 (directly assigned archetypes reported in `isArchetypes`) are by design. Deletes work, so C10 is re-framed as delayed deletion, to be re-checked. Design §2.1, §8.3, §8.4, §9.4, §13 and Appendix A updated. |
| 2026-11-21 | Folded in write run `20261121-190350`. C8 answered (`attributes.ID` required); C6/P18 confirmed on create; C2 is an upsert; C10 is a DELETE that removes nothing; C9 confirmed (direct archetypes reported in `isArchetypes`); C5 is search lag; C1 refined (GET fails during re-evaluation and for some long-standing entities); C11 is a wrapped export bundle. Answered Q03.6, Q05.3, Q07.6 and Q07.8, and partly answered Q04.1, Q09.1 and Q09.2. |
| 2026-11-21 | Folded in runs `20261121-173141` and `20261121-173153`. Answered Q03.5 and Q08.1, and partly answered Q03.4, Q05.1 and Q08.2. Updated C1–C6 and added C8–C12 (section 5.5). The probe gained `create.shape`, `create.relationshipShape`, `search.reflectsDelete` and `probe.leftovers`, and no longer treats a search miss as proof of deletion. Runs `20261121-154839` and `20261121-155100` were made before the scrubber fix and contain real entity data. They were committed to `main` and are removed from the branch tip; removing them from history needs a rewrite of `main`. |
| 2026-11-21 | Probe realigned to the OpenAPI definition and P17–P22 (plan WS0.15): EntityDTO writes, search read-backs while GET fails, and new checks for P18, P20, P22, C5 and Q07.8 (section 7). The timestamp questions Q02.2–Q02.5 are answered by the definition (no timestamp search) and deferred to WS10. `--timestamp-field` is removed; `--archetypes` added. |
| 2026-11-04 | Register created. Contract probe added. No answers confirmed yet: no instance of the service was reachable from the environment that wrote this. |
| 2026-11-21 | The project owner decided D2 and D5–D9 (plan P17–P22), recorded in `docs/design-decisions-2026-11-21.md`. D5 keeps the design's numbering (4 = Subject), against the API's create samples; WS0.15 confirms it before any create. |
| 2026-11-21 | The project owner confirmed relationship writes: through the entity's `relationships` collection or the single-relationship endpoint, and any relationship missing from the collection is removed (Q07.4, D1 → P15). Q07.8 added. Drift detection deferred to the plan's final workstream (D3, D4 → P16). |
| 2026-11-21 | OpenAPI definition received. Sections 5 to 7 added. It answers the paging, error-format and timestamp questions, contradicts the design's base-type numbering and relationship model, and exposes seven contradictions with observed behaviour (C1–C7) and nine design decisions (D1–D9). The first full probe run (`20261121-155100`) leaked unscrubbed entity data into the `probe-results` branch; the scrubber is fixed. |
| 2026-11-21 | Q02.1 answered by the project owner, after the first probe run against the real service got 405 for `GET /admin/entities`: search is `POST /admin/entities/search` with a JSON body, and `{}` returns everything. Folded in as decision P14. The probe now searches that way and accepts `--timestamp-field`. |
| 2026-11-21 | Q10.1, Q10.2 and T5 answered by the project owner: one shared raw key in `X-API-KEY`, with 401 for a missing or wrong key. Folded into the code as decision P13. Added T10, T11 and H7. |
