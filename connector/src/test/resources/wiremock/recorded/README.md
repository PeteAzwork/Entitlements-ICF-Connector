# Recorded service responses (WS0.11)

One recording of each document shape the connector meets, kept from contract-probe run `20261121-215954` against the test instance. Each file is a WireMock stub mapping: the request the probe sent and the response it got.

| File | Exchange | Shape |
|---|---|---|
| `create-entity.json` | #80 | `POST /admin/entities`: the write model (EntityDTO) with `attributes.ID` (C8) and a relationship in the P17 shape; 200, no body |
| `get-entity.json` | #83 | `GET /admin/entities/{id}`: the read model, with `Attributes` objects and a `{source, target, type, sourceType}` relationship |
| `search-page.json` | #24 | `POST /admin/entities/search` with `pageSize: 5`: five search items, `pagedResultsCookie`, `totalResults`, `hasMore` |
| `create-invalid-400.json` | #202 | A create with a non-string attribute: 400 problem details with an `errors` map |
| `manifests-bundle.json` | #29 | `GET /admin/manifests`: the export bundle (C11), reduced to manifest and archetype names |

**Safe to share.** The probe scrubbed these before writing them. Entities the probe created (`ws0-probe-…`) are kept as they are. Entities it did not create appear only in the search page, with their IDs replaced by `scrubbed-N` and their attribute values by `<scrubbed>`; field names, base types and archetypes are kept. The manifests keep only manifest, archetype and relationship-type names. Do not replace these files with recordings from a run made before the scrubber fix (runs before `20261121-173141`).

`RecordedShapesSpec` checks the in-memory fake service against these shapes, so the fake cannot drift from the real service unnoticed. WS4.09 and WS8 use them as WireMock stubs.
