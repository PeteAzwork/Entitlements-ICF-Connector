# Design Decisions — Aligning the Connector to the Real API

**Date:** 2026-11-21
**Decided by:** the project owner, one decision at a time, from proposals drafted against the service's OpenAPI definition and the first probe run.
**Resolves:** design questions D2 and D5–D9 in `docs/service-contract.md` §6, and task WS0.14a in the delivery plan.
**Recorded as:** decisions P17–P22 in `docs/delivery-plan.md` §3.

Each decision below gives the context, the decision, its consequences, what must still be confirmed, and the code it changes. The code changes themselves are made in WS0.14b–e.

| Plan ID | Question | Decision |
|---|---|---|
| P17 | D2 — how relationships are encoded | `{sourceID, targetID, type}`, the service's write shape |
| P18 | D5 — how the base type is sent | Numbers, with the design's numbering: **4 = Subject, 2 = Resource** |
| P19 | D6 — how IDM types are told apart | A scope archetype per type, stamped as a direct archetype |
| P20 | D7 — attributes | Single-valued strings |
| P21 | D8 — which searches are supported | By ID, plus equals, contains and starts-with combined with and, or and not |
| P22 | D9 — how archetypes are assigned | `directArchetypes` in the entity body; Test checks manifests |

---

## P17 (D2) — Relationship encoding

**Context.** The design expressed each edge from one entity's point of view: `{"TargetID": …}` for an outbound edge or `{"SourceID": …}` for an inbound one. The service's write model names both ends explicitly, as `{sourceID, targetID, type}`. Its read model uses `{source, target, type, sourceType}`.

**Decision.**
- Each value of the `entityRelationships` ICF attribute is canonical JSON in the service's write shape: `{"sourceID":"…","targetID":"…","type":"…"}`, with keys in that order and no whitespace.
- The connector copies the values straight into the `relationships` collection of the create or update body.
- On reads, the connector converts the read model's `source` and `target` into the same shape, so reconciliation compares like with like. `sourceType` is dropped.
- The §7 convention still decides which IDM side of a relationship pair is the source: the outbound side.
- Every value must name the entity being written as its source or its target. Any other value is rejected with `InvalidAttributeValueException`.

**Consequences.**
- `Edge` becomes `{sourceId, targetId, type}`. `flip` and the per-entity direction disappear from the model; `Direction` remains only in configuration and the §7 convention.
- `EdgeCodec`, `EdgeSpec`, `EdgeCodecSpec`, the edge cases of `EntityPayloadBuilderSpec` and `EntityDecoderSpec`, `EdgeVerifier`, `FakeGraphService`, `Fixtures` and the Appendix A scenario change shape.
- The mapping library's `buildRelationships` emits the same format.
- The format carries inbound relationships naturally, so either answer to Q07.8 fits it.

**Still to confirm.** Q07.8: whether each entity's collection must also list relationships where it is the target.

---

## P18 (D5) — Base type

**Context.** The base type is an integer enum, 0–4. The definition's create samples say `2 = Subject` and `4 = Resource`. The design (§2.1, A4) says the reverse. Search results return names (`"Subject"`, `"Resource"`).

**Decision.**
- The connector sends the base type as a **number**, with the **design's numbering: 4 = Subject, 2 = Resource**. The search request's `objectClass` uses the same numbers.
- Reads accept either a number or a name, because search returns names.

**Consequences.**
- `EntityKind` and `EntityKindSpec` keep `SUBJECT(4)` and `RESOURCE(2)`, as they are today. `EntityKind.fromBaseType` also accepts `"Subject"` and `"Resource"`.

**Condition before anything is created.** This numbering contradicts the service's own create samples (C6). If the samples are right, every entity would be created as the wrong type. The realigned probe write run (WS0.15) must create one entity with base type 4 and confirm that a search for Subjects finds it. That must pass before WS1.02 ships, and before any IDM-driven create against a shared service.

---

## P19 (D6) — Telling IDM types apart

**Context.** Several IDM types can share a base type, for example `user` and `employee` as Subjects. The service can scope a search by `objectClass` and filter on attributes. Entities carry `directArchetypes`, which the writer assigns, and `isArchetypes`, which policy rules assign. Existing entities observed in the first run had empty `directArchetypes` and populated `isArchetypes`.

**Decision.**
- Each object type's **scope archetype** is the first entry of its static `archetypes`. The connector stamps it as a direct archetype on every create and update.
- **Enumeration** sends the type's `objectClass`. It also sends a filter on the scope archetype if the service supports filtering on `directArchetypes`; otherwise the connector filters the results itself.
- **Validation:** two types with the same base type must have different scope archetypes, or the configuration is rejected with `ConfigurationException`.
- **Reads by ID** check only the base type. An existing entity without the scope archetype is still found when IDM correlates it, and receives the archetype on its next update.
- `isArchetypes` is never used for scoping, because the connector does not control it. *Amendment pending (plan WS0.18): search reports archetypes assigned through `directArchetypes` in `isArchetypes`, by design, and leaves `directArchetypes` empty (C9). Scoping must therefore read `isArchetypes`.*

**Consequences.**
- `TypeScoper` matches on the scope archetype within `directArchetypes`; the base type is already enforced by `objectClass`.
- `RelationshipConvention.validate`, or a sibling check in `ObjectTypesParser`, gains the scope-archetype uniqueness rule.
- Entities without a scope archetype are invisible to enumeration, so they are never reported as orphans of the wrong type.

**Still to confirm.** Whether the search filter can match an element of `directArchetypes`. Until that is known, the connector filters results itself, and the paging cookie still reflects the service's pages.

---

## P20 (D7) — Attributes

**Context.** The write model's `attributes` is a map of string to **string**. The read model wraps each value as `{name, type, value}` and has separate `compoundAttributes` for multi-valued ones. Search returns values as they are.

**Decision.**
- Domain attributes in the ICF schema are single-valued strings. Numbers and booleans are sent as their text form.
- The discovery tool leaves multi-valued IDM properties out of generated configuration, with a warning.
- Every write sends every configured attribute that has a value in IDM. An attribute that is empty in IDM is omitted, on the assumption that the service removes omitted attributes, as it removes omitted relationships.

**Consequences.**
- `SchemaFactory` declares domain attributes single-valued, and `EntityPayloadBuilder` converts values to strings.
- `EntityDecoder` reads the read model's `value` and search's plain values.
- `ManagedSchemaReader` and `ObjectTypesGenerator` flag and exclude array-typed properties.

**Still to confirm.** Whether an update with an attribute omitted removes it. WS0.15 checks it. If omitted attributes are kept, empty values must be sent explicitly instead.

---

## P21 (D8) — Supported searches

**Context.** The search takes a filter tree, `{operation, left, right, not}`. The schema types `operation` as an integer enum 0–9 without names. The samples use names: `EQUALS`, `CONTAINS`, `STARTSWITH` and `AND`, and the description also names `OR`. In the first run, GET by ID returned 500 every time (C1).

**Decision.**
- An `EqualsFilter` on `__UID__` or `__NAME__` is a read by ID.
- `EqualsFilter`, `ContainsFilter` and `StartsWithFilter` on configured attributes, combined with `AndFilter`, `OrFilter` and `NotFilter` (the `not` flag), translate into the service's filter tree. Operations are sent by name, as the samples do.
- `EndsWithFilter` and the greater-than and less-than filters are added only once the service names its ten operations.
- Any other filter, including any filter on `entityRelationships`, `directArchetypes` or `lastEvaluated`, raises `UnsupportedOperationException`. It is never silently turned into a full scan (P7).
- If GET by ID is still failing when WS4 is built, a by-ID read falls back to a search with an `EQUALS` filter on the ID.

**Consequences.**
- `SearchPlanner` produces a filter tree instead of a timestamp search, and `SearchPlannerSpec` is rewritten for it (WS0.14d).
- A configuration switch selects GET or the search fallback for reads by ID.

**Still to confirm.** The names of the ten filter operations; the attribute name for filtering on the ID (the default sort key is `"ID"`); and C1.

---

## P22 (D9) — Archetype assignment

**Context.** Archetypes can be set through `directArchetypes` in the entity body, or through `POST /admin/entities/{id}/direct-assign-archetype`. Archetypes themselves are defined in the service's manifests.

**Decision.**
- The connector sets archetypes through `directArchetypes` in the same create or update body: one idempotent write per entity. It never uses the assignment endpoint.
- The connector does not create or change manifests.
- The **Test** operation reads the manifests (`GET /admin/manifests`) and fails with a clear message naming any configured archetype that is not defined.

**Consequences.**
- `TestOperation` gains a manifest check, and `TestAndSchemaOperationSpec` gains cases for a missing archetype and a missing manifest list.
- `EntityApi` gains a manifest read.
- The runbook documents that manifests must define every configured archetype before the connector is enabled.

**Still to confirm.** Whether an update replaces `directArchetypes` in full. WS0.15 checks it together with P20.

---

## Conditions that gate implementation

| Condition | Gates | Checked by |
|---|---|---|
| Base type 4 really is Subject (P18) | Any create against a shared service; WS1.02 | WS0.15 |
| Whether omitted attributes and archetypes are removed on update (P20, P22) | WS2.07 | WS0.15 |
| Whether each entity's collection must list relationships where it is the target (Q07.8, P17) | WS5.05 | Service team, WS0.15 |
| Names of the filter operations (P21) | The ends-with and comparison filters only | Service team |
| GET by ID works (C1) | Whether reads by ID use GET or the search fallback | Service team |

---

## Findings since these decisions

The first realigned probe runs (`20261121-173141`, `20261121-173153`) bear on three decisions. Details are in `docs/service-contract.md` §5.5.

| Decision | Finding | Effect |
|---|---|---|
| P18 | `objectClass: 4` returns only Subjects (C6), and an entity created with base type 4 is a Subject (run `190350` #41, #45) | **Confirmed** |
| P17, P20, P22 | Creates and updates need `attributes.ID` equal to `id`. This is **by design**, confirmed by the service owner (C8, run `190350`). With it, the P17 relationship form is accepted, and so is the design's. Writing the target without a relationship leaves it (Q07.8 answered: no) | P20 gains a requirement: mirror the ID into `attributes.ID` (plan WS0.19). Attribute removal (P20) and archetype replacement (P22) are still unobserved, because the probe read back before the service caught up (WS0.15e) |
| P19 | By design, search reports archetypes assigned through `directArchetypes` in `isArchetypes`, and leaves `directArchetypes` empty (C9, confirmed by the service owner) | The scope archetype must be matched in `isArchetypes`. Proposed amendment, pending the owner's confirmation (plan WS0.18). Residual risk: a policy could assign one type's scope archetype to other entities |
| §9.3 delete | The service owner reports deletes work. The probe saw deleted entities still readable straight afterwards (C10) | No design change. Probably a delayed deletion; the probe re-checks with a settle window (plan WS0.15e) |
