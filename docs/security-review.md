# Security review pack: Graph Entity Service connector

**For:** the WS9.05 security review, ahead of the go-live decision (WS9.07).
**Scope:**
- the connector, its scripts and library, running in IDM 7.5;
- the mapping library;
- the discovery tool;
- the contract probe, as a development tool.

The service itself and IDM's own security are out of scope, except where the connector depends on them.

## 1. What is protected

| Asset | Why it matters |
|---|---|
| **The service's API key** | One constant key for every consumer of the service (P13). Whoever holds it can create, change and delete any entity and relationship. Requests cannot be attributed to a consumer |
| **Identity data in transit** | Attribute values (names, mail, phone and whatever else is configured), archetypes and relationships |
| **The integrity of the service's relationships** | Every write restates the entity's relationships in full (P15). Wrong input to the connector can delete relationships |
| **IDM configuration** | It holds the encrypted key, and it decides what is written |

## 2. Controls, with evidence

| # | Control | Evidence |
|---|---|---|
| 1 | **The key is stored only in the provisioner's `password`, which IDM encrypts at rest.** The scripts read it as a `GuardedString`, without copying it into the configuration. No username; `defaultAuthMethod: NONE`, so IDM adds no Basic header | `ScriptSupportSpec`, `CustomizerScriptSpec`; observed in IDM 7.5: the provisioner saved with a plain key comes back encrypted |
| 2 | **The key is sent only in the `X-API-KEY` header**, by one interceptor (`ClientCustomizer`). It is never sent as `Authorization`, and never in a URL or body | `CustomizerScriptSpec`, `OperationScriptsSpec` ("the decorated client sends the key"), `RestClientEntityApiSpec` |
| 3 | **The key never appears in logs or exception messages.** Error bodies are redacted: header, JSON-field and query forms of `X-API-KEY`, `apiKey`, `api_key`, `Authorization` and `password`. The configured key's literal value is also stripped, in case the service echoes it unlabelled. A control character in the key is rejected without echoing it | `HttpErrorMapperSpec` (108 cases), `ConnectorConfigSpec`, `OperationScriptsSpec` ("scripts never log the API key"), `ConnectorInIdmSpec` ("never written to IDM's log", checked on IDM's real log) |
| 4 | **Attribute values are never logged above `OK` (DEBUG).** WARN and ERROR lines carry IDs, relationship shapes, statuses and redacted bodies only. Every log call passes its message as a parameter, not as a format pattern | `CreateOperationSpec`, `DeleteOperationSpec`; the WS3.15 review of every log call |
| 5 | **TLS.** The system trust store is used for https. A WARN is logged when `serviceAddress` is plain http to a host other than localhost. A `serviceAddress` containing credentials (`https://user:pass@host`) is rejected, and never repeated in a message | `ClientCustomizer`, `ConnectorConfigSpec` |
| 5a | **Outbound proxy credentials go to the proxy alone.** `proxyUsername` and `proxyPassword` (encrypted by IDM) answer the proxy's 407 challenge as `Proxy-Authorization`. They are scoped to the proxy's host and port, so they never reach the service. A `proxyAddress` carrying credentials is refused without being repeated | `CustomizerScriptSpec`; `NetworkInIdmSpec` (proxy in IDM 7.5) |
| 5b | **TLS failures stop before any request.** An untrusted CA or a host-name mismatch fails the handshake, so nothing, the key included, reaches the server | `NetworkInIdmSpec` (a private CA in IDM's trust store, an unknown CA, a wrong host name) |
| 6 | **Timeouts on every request:** connect 10 s, socket 60 s, both configurable. A stalled service cannot hang reconciliation threads | `CustomizerScriptSpec` |
| 7 | **No ID can address the wrong entity.** IDs are URL-encoded as one path segment. IDs containing `/`, which the service cannot address, are rejected (P23). IDM's default `userName` policy also forbids `/` | `RestClientEntityApiSpec`, `EntityPayloadBuilderSpec`, `UpdateOperationSpec`, `DeleteOperationSpec` |
| 8 | **Relationships are only ever written from IDM's complete state**, so the connector cannot delete relationships that IDM has. Every relationship must name the entity being written. An update without `entityRelationships` or `directArchetypes` is refused, rather than deleting them (P4) | `EntityPayloadBuilderSpec`, `UpdateOperationSpec`, `MappingLint` (CI) |
| 9 | **Orphans are never deleted** (P16): reconciliation reports entities that exist only in the service. A deleted IDM object deletes its own linked entity only (`SOURCE_MISSING`) | `MappingSkeletonGeneratorSpec`, `AppendixAInIdmSpec` |
| 10 | **The discovery tool's IDM access is read-only and separate:** one GET of `config/managed`, with an IDM account of its own. The password comes only from `IDM_DISCOVERY_PASSWORD`; `--idm-password` is refused. The password is never printed | `IdmManagedSourceSpec`, `DiscoveryToolSpec` |
| 11 | **The contract probe scrubs what it records**, and its write mode refuses to run without `--allow-writes`. Its reports hold no data of entities it did not create | `ScrubberSpec`, `ProbeOutputSpec`, `RecordedShapesSpec` |
| 12 | **The development-only service stand-in** (`runFakeService`) holds state in memory, uses a development key, and is documented as never to be exposed | `dev/README.md`, `FakeServiceMain` |

## 3. Risks for the reviewers to accept, mitigate or reject

| # | Risk | Notes |
|---|---|---|
| R1 | **The shared key.** A leak from *any* consumer exposes everything. Rotation needs every consumer at once. Requests cannot be attributed | A service-side change: per-consumer keys, OAuth2 client credentials or mTLS. The connector confines authentication to `ClientCustomizer`, so a change there is local. Rotation procedure: runbook §7 |
| R2 | **Who can read IDM's configuration** can recover the key in use (IDM decrypts it for the connector), and can change what is written | Restrict IDM admin and the configuration endpoints. Audit admin access |
| R3 | **A second writer to the service (A1)** has its relationships silently removed by this connector. Equally, anyone holding the key can write relationships IDM will then overwrite | Any second writer needs a design review first (design §15) |
| R4 | **Library as editable sources on the IDM servers** (scripts mode, `script_vs_jar.md`). Whoever can write `connectors/graphentity` can change what is sent, including the key handling | The files should be writable only by deployment. Compare against the release artefact's `VERSION` and checksums. The jar mode is the alternative |
| R5 | **Identity data in IDM's own audit logs.** IDM's sync and activity audit record objects; the connector does not control them | IDM audit configuration and retention, outside this connector |
| R6 | **Plain http** is accepted with a WARN, for local development | Production must use https. The WARN is a monitoring signal (runbook §5) |
| R7 | **The service's error bodies** are logged at ERROR after redaction, and may contain attribute values the service echoes in a validation message | Decide whether that is acceptable, or whether 400 bodies should be logged at DEBUG only |

## 4. Log audit

What was checked, and how:
- **API key:** the integration suite asserts the key never appears in IDM's log through a full run: tests, reconciliations, error paths, and a changed and restored key.
- **Attribute values:** the unit suites assert that values do not appear above DEBUG for create and delete.
- **Every log call:** reviewed in WS3.15.

Still to do before go-live: repeat the key check on the **dry run's** logs (runbook §10), against the real service and real key, with `grep -c "<key>" openidm0.log.*` showing 0.

## 5. Decisions requested

1. Accept R1 for go-live, or require a service-side change first.
2. Confirm the IDM admin access model (R2) and the file permissions on `connectors/graphentity` (R4).
3. Decide R7: 400 bodies at ERROR (current) or at DEBUG.
4. Sign off in `docs/go-live-checklist.md`.
