# Local development stack (WS7.06)

A local IDM 7.5 with the connector from your build. It can use an in-memory stand-in for the Entitlements Accelerator Service, or a real test tenant.

```bash
./gradlew :connector:idmDist                  # build what IDM mounts (connector, mapping library)
./gradlew :connector:runFakeService           # optional, in another terminal: the service stand-in on :5276
docker compose -f dev/docker-compose.yml up -d
dev/load-config.sh                            # provisioner via createFullConfig, then the Test operation
```

- **The admin UI** is at http://localhost:8080/admin. The image's default login is `openidm-admin` / `openidm-admin`.
- **After changing code:** run `./gradlew :connector:idmDist`, then `docker compose -f dev/docker-compose.yml restart`. The mounts are read-only.
- **A real test tenant instead of the stand-in:**

  ```bash
  SERVICE_URL=https://… API_KEY=… dev/load-config.sh
  ```

  Never commit a real key.
- **Managed objects and mappings:**
  - Generate them with the discovery tool, as in the runbook (`docs/runbook.md` §2.3), and load them over IDM's REST API.
  - For a ready-made pair, use the integration fixture: `integration/src/test/resources/idm/managed.json` (IDM's default `managed.json` plus `account` with the `owns`/`ownedBy` pair, and the ID-change guard).
- **Settings for the stand-in** (`FakeServiceMain`), all from the environment:
  - `GRAPH_FAKE_PORT` (default 5276);
  - `GRAPH_FAKE_BIND` (default 0.0.0.0, so the container can reach it);
  - `GRAPH_FAKE_API_KEY` (default `dev-api-key`).

  It keeps everything in memory and has the service's known HTTP behaviour. It is for development only: don't expose it beyond your machine.
- **Stop the stack:** `docker compose -f dev/docker-compose.yml down`.
