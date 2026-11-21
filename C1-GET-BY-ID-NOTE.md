# Note for the service team: GET of an entity by ID returns 500 (C1)

**From:** the IDM connector project
**Date:** 2026-11-21
**Service:** IDP Entitlements API v1, test instance
**Asks:** an explanation of the failures below, and either a fix or documented behaviour the connector can design around.

## Summary

`GET /admin/entities/{id}` returns **500 with an empty body** in three situations where the API definition promises 200 or 404:

1. for an ID that does not exist (the definition says 404);
2. for an entity that has existed for some time;
3. for an entity just written with a relationship to an entity that does not exist yet, or just after its relationship was removed through `DELETE /admin/entities/{sourceID}/relationships/{targetID}/{type}`.

Other GETs work: once an entity has settled, the same request returns 200 with the documented `Entity` model.

Every failing response looks like an **unhandled exception**:
- `Server: Kestrel`;
- an empty body and no content type;
- **no `x-request-id` header**, which every 200 and 400 response carries;
- 5–17 ms, so not a timeout.

The 500s returned for a create without an `id` (C4) and without `attributes.ID` (C8) look exactly the same.

## Evidence

From our contract probe's runs against the test instance on 2026-11-21. Times are UTC, from the responses' `Date` headers. `#n` is the exchange number in the run's recording. IDs starting `ws0-probe-` are test entities the probe created; no real entity data is included here.

### 1. An ID that does not exist: 500, expected 404

| Run | # | Time | Request | Status |
|---|---|---|---|---|
| `20261121-215954` | #3 | 10:59:53 | `GET /admin/entities/ws0-probe-20261121-215954-auth-check-does-not-exist` | 500 |
| `20261121-215954` | #30 | 10:59:54 | `GET /admin/entities/ws0-probe-20261121-215954-does-not-exist` | 500 |
| `20261121-215954` | #205 | 11:00:47 | `GET /admin/entities/ws0-probe-20261121-215954-never-created` | 500 |
| `20261121-214027` | #30 | 10:40:27 | `GET /admin/entities/ws0-probe-20261121-214027-does-not-exist` | 500 |

Every probe run that day shows the same. A GET of an entity just deleted also returns 500, not 404 (e.g. `214027` #203, 10:41:05).

### 2. An entity that has existed for some time: 500

The probe reads the first existing entity in ID order, the same one each run. It got 500 every time, e.g. `215954` #27 at 10:59:54 and `214027` #27 at 10:40:27. Its ID is not recorded, because the probe scrubs data it did not create; a GET of the first entity a `{}` search returns reproduces it. We have only tried this one long-standing entity.

### 3. An entity whose relationship points at a missing entity: 500 until the other entity exists

The same sequence in two runs:

| Run | # | Time | Step | GET of `edge-A` |
|---|---|---|---|---|
| `215954` | #50 | 10:59:55 | `POST` `edge-A` with relationship `edge-A → edge-B`; `edge-B` does not exist yet | |
| `215954` | #53–#73 | 10:59:56–11:00:06 | GET `edge-A`, repeated for 10 s | **500 every time** |
| `215954` | #80 | 11:00:07 | `POST` `edge-B`, carrying the same relationship | |
| `215954` | #84 | 11:00:08 | GET `edge-A` | **200**, lists `edge-A → edge-B` |
| `214027` | #107 | 10:40:30 | `POST` `edge-A` with `edge-A → edge-B`; `edge-B` does not exist yet | |
| `214027` | #110–#130 | 10:40:31–10:40:41 | GET `edge-A`, repeated for 10 s | **500 every time** |
| `214027` | #137 | 10:40:42 | `POST` `edge-B` | |
| `214027` | #141 | 10:40:42 | GET `edge-A` | **200** |

The full probe IDs are `ws0-probe-<run>-edge-A` and `ws0-probe-<run>-edge-B`. Note that the relationship to the missing entity was evidently kept, not dropped: `edge-A` listed it as soon as `edge-B` existed.

### 4. After the single-relationship DELETE: 500 for 20 s or more

| Run | # | Time | Step | Result |
|---|---|---|---|---|
| `214027` | #158 | 10:40:45 | `DELETE /admin/entities/ws0-probe-20261121-214027-edge-A/relationships/ws0-probe-20261121-214027-edge-B/Ws0ProbeEdge` | 200 |
| `214027` | #159 | 10:40:45 | GET `edge-A` | 200, still lists `edge-A → edge-B` |
| `214027` | #160–#201 | 10:40:45–10:41:05 | GET `edge-A`, repeated, including after re-adding the relationship with `POST .../relationships` at #180 | **500 every time** |

### For contrast: GETs that work

| Run | # | Time | Request | Status | `x-request-id` |
|---|---|---|---|---|---|
| `215954` | #83 | 11:00:08 | `GET /admin/entities/ws0-probe-20261121-215954-edge-B` | 200 | `e89ba2de-8acf-43dc-a635-876b5f8316e4` |
| `215954` | #84 | 11:00:08 | `GET /admin/entities/ws0-probe-20261121-215954-edge-A` | 200 | `c4c33785-fab6-4b11-b096-8b04568b3cff` |
| `215954` | #88 | 11:00:08 | `GET /admin/entities/ws0-probe-20261121-215954-edge-A` | 200 | `c6f880e5-92ab-44c6-be91-f187c274b42a` |

## What we think is happening

These are inferences from the outside; your logs will say.

- **Missing ID.** The lookup throws, for example on a null entity, instead of returning 404.
- **Unresolvable relationships.** Building the `Entity` model throws when one of the entity's relationships points at something that cannot be resolved: an entity that does not exist yet, or a relationship left half-removed by the single-relationship DELETE. A long-standing entity may fail the same way, if it has a relationship to an entity deleted since.
- **Unhandled exceptions.** The missing `x-request-id` suggests these exceptions escape the pipeline before the middleware that adds that header runs.

## Questions

1. **Missing IDs.** Why does a GET of an ID that does not exist return 500? Will it return the documented 404?
2. **Long-standing entities.** Why does a GET of the entity above fail? What does the server log for one of those requests show?
3. **Relationships.** Does GET fail when an entity has a relationship to an entity that does not exist, or one left behind by the single-relationship DELETE? Is a relationship to a missing entity kept on create, and resolved once that entity is created, as section 3 suggests?
4. **After writes.** Apart from the cases above, is GET expected to be unavailable for a while after a write, while the entity is re-evaluated? If so, for how long, and could it answer with a retryable status such as 503 with `Retry-After` instead of 500?
5. **Reading relationships.** Is GET the only supported way to read an entity's relationships? Search results do not include them.
6. **Error responses.** Could unhandled errors carry the `x-request-id` header, or a problem-details body with a `traceId`, so that each failure can be matched to a log entry?

## What would resolve it

Either:
- **a fix:** 404 for a missing ID, 200 for every existing entity, and, if there is a re-evaluation window, a retryable status during it; or
- **documented behaviour:** what each case returns, and for how long, so the connector can retry the right cases and treat the rest as errors.

We will confirm with a probe run: GET of an existing entity answers 200, GET of a missing ID answers 404, and the relationship checks stop being inconclusive.

## Why it matters to the connector

- **Reconciliation.** Reads by ID go through the search filter on `ID` instead of GET. With GET as it is, a read of an entity that does not exist would fail instead of returning "not found", and IDM's reconciliation would stop rather than create the entity.
- **Relationships.** Only GET returns an entity's relationships, so until it is reliable the connector cannot report current relationships to IDM. Detecting relationships changed outside IDM (drift detection) depends on this.
- **Create verification.** The connector reads an entity back after creating it to check its relationships were kept. That check fails in exactly the case it exists for: a relationship to an entity created later.

## Contacts and references

- **Probe recordings:** `build/contract-probe/<run>/` in the connector repository. `exchanges.jsonl` has every request and response, numbered as above; no real entity data.
- **Service contract:** `docs/service-contract.md`, section 5.5, items C1, C4, C8, C17 and C18.
- **Delivery plan:** `docs/delivery-plan.md`, task WS0.01.
