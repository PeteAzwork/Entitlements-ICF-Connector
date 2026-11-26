# entitlementsAcceleratorPayload — mapping-side payload library

IDM mappings to the Entitlements Accelerator Service connector use this library to fill the two attributes the service
replaces in full on every write: `entityRelationships` and `directArchetypes` (design §8.2, decisions P15, P17).
It reads IDM's own state, so the payload is a pure function of IDM. It never reads the remote service.

## Deploying and loading it

Copy `src/lib/entitlementsAcceleratorPayload.js` to `<idm>/script/lib/entitlementsAcceleratorPayload.js`, then use it from a mapping's
property transformations:

```javascript
// entityRelationships
var lib = require('lib/entitlementsAcceleratorPayload');
lib.buildRelationships(source._id, lib.loadTypeConfig('user'));

// directArchetypes
var lib = require('lib/entitlementsAcceleratorPayload');
lib.buildArchetypes(source, lib.loadTypeConfig('user'));
```

Both properties must always be mapped, even when empty. `UpdateOperation` refuses an update that leaves either out
(P4), because the service would delete whatever is missing.

## Functions

| Function | What it does |
|---|---|
| `loadTypeConfig(typeName, options)` | Reads `objectTypes` from the provisioner configuration `config/provisioner.openicf/entitlementsaccelerator`, from `configurationProperties.objectTypes` or from the `objectTypes='…'` entry in `customConfiguration`. This is the single source of truth: the connector reads the same document. Returns a copy of the named type with `name` attached, and throws naming the configured types when it is unknown. `options.reload` bypasses the cache; `options.ttlMs` sets its lifetime. |
| `clearCache()` | Forgets the cached configuration, e.g. after the provisioner changes. |
| `buildRelationships(sourceId, typeConfig, options)` | Every relationship touching the source object, in both directions, as sorted, de-duplicated strings `{"sourceID":…,"targetID":…,"type":…}`: keys in that order, no whitespace, both ends named by their **remote** IDs (each type's `idProperty`). Both ends of one relationship emit the identical string (P17). `sourceId` is the IDM `_id`. |
| `buildArchetypes(sourceObject, typeConfig)` | The type's static archetypes first (the first is the scope archetype, P19), then the values of its `archetypeProperty`, single or multi-valued, without duplicates. |
| `resolveRelationship(property, typeConfig, managedSchema)` | One property's remote type and direction: an explicit `remoteType` + `direction` wins; otherwise the §7 convention, with the reverse property from the entry's `reverseProperty` hint or from managed.json's `reversePropertyName`. Returns null for `sync: false`; throws naming the property when unresolvable. |
| `onIdPropertyChange(newObject, oldObject, typeName, options)` | For a managed object's `onUpdate` hook (plan WS9.04). When the type's `idProperty` changes, it deletes the entity under its old ID in the service and IDM's links to it, so implicit sync re-creates it under the new ID with its relationships. The remote ID cannot change (A3). Returns true when it acted. A delete failure other than "not found" propagates, so the rename fails rather than leaving the service out of step. `options.connector` defaults to `entitlementsaccelerator`. Install: `"onUpdate": {"type": "text/javascript", "source": "require('lib/entitlementsAcceleratorPayload').onIdPropertyChange(newObject, oldObject, 'user');"}` |
| `resolvePair`, `normalisePropertyName`, `isPassive` | The §7 convention itself, identical to the connector's `RelationshipConvention`. |

### How `buildRelationships` treats edge cases
- It reads the source with `fields: ['*', '*_ref']`, then each related object once per call (for its remote ID).
- A relationship property listed in `objectTypes` is always processed.
- An unlisted one is processed by convention, through managed.json, only when it points at a configured type. Others, such as `roles`, are ignored and logged at debug.
- A reference is skipped with a warning when it points at an unconfigured type, at a deleted object, or at an object without its remote ID. An edge with a null ID is never emitted.
- References that carry only `_ref` (some IDM versions) are split into collection and ID.
- A missing source object throws. An empty array would make the PUT delete every relationship.

## Constraints

- **ES5 only.** IDM runs mapping scripts on Rhino. That rules out `let`, `const`, arrow functions, template literals and classes; the test suite checks the source for them.
- **Globals:** only `openidm`, `logger` and what the mapping passes in.
- **IDM's collections:** inside IDM, `openidm.read` returns wrapped Java collections, for which `Array.isArray` is false. The library recognises lists by their numeric `length` and reads them by index.

## Cache

- The library caches the provisioner configuration and managed.json in its module, for five minutes by default.
- **In IDM 7.5, though, the module lasts one script evaluation** (plan WS8.08, `RuntimeInIdmSpec`). IDM loads `require`d modules afresh for each evaluation, so each transform reads the configuration again, through IDM's in-memory configuration service.
  - **The cost is small.** It is included in the measured reconciliation times (runbook §4).
  - **A configuration change takes effect at once** in IDM. `clearCache()` only matters outside IDM, for example in tests.
- `cacheInfo()` reports when the cache was loaded, for diagnosing exactly this.

## Tests

- `npm test`: the full specification under Node, in an isolated `vm` context standing in for Rhino. The §7 cases in `../fixtures/convention-cases.json` are shared with the connector's `RelationshipConventionSpec`, so the two implementations cannot drift apart.
- `./gradlew :connector:test --tests '*EntitlementsAcceleratorPayloadRhinoSpec'`: the same source evaluated under Mozilla Rhino 1.7.14 in ES5 mode (plan WS5.07).
