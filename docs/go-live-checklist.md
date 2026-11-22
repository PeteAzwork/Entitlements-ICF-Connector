# Go-live checklist and sign-off (WS9.07, WS9.08)

This is the record of the decision to put the connector into production (milestone M5). Fill it in, and keep it with the release.

## 1. The decision to make: go live at M5, or wait for M6

| At M5 (now) | At M6 (after WS10) |
|---|---|
| IDM writes the service one way. Each write restates the entity in full | Adds drift detection: changes made in the service outside IDM are found and repaired |
| Entities that exist only in the service are **reported**, never deleted | Orphans are deleted per type, after a promotion checklist |
| A change made in the service outside IDM persists until IDM next writes that entity; nightly reconciliation restates every linked entity, which repairs most of it | Repaired by the chosen drift mechanism, on its schedule |

Going live at M5 is reasonable when **IDM is the only writer to the service (A1)**, and when a day-long window for out-of-band changes is acceptable. Waiting for M6 depends on the service team (a last-modified signal, and a working GET by ID, C1).

**Decision:** ☐ go live at M5 ☐ wait for M6. **Recorded by:** ________ **Date:** ________

## 2. Prerequisites

| # | Item | Evidence | Done |
|---|---|---|---|
| 1 | Every suite green on the release commit: connector, discovery, mapping library, probe, `compileWithIdmGroovy`, mapping lint | CI run link | ☐ |
| 2 | Integration suite green on the target IDM version, both library modes | Nightly `integration` run link | ☐ |
| 3 | Volume measured at production size (`-PidmScale=<n>`), schedules sized from it | `scale-report.txt` | ☐ |
| 4 | Dry run against the real service, reviewed with operations and the service owner (runbook §10) | Situation counts per type, health report | ☐ |
| 5 | No `AMBIGUOUS` situations in the dry run; orphans explained | Dry-run record | ☐ |
| 6 | Security review complete; R1–R7 decided (`docs/security-review.md`) | Review record | ☐ |
| 7 | API key in production stored only in the provisioner; rotation procedure agreed with the service team (runbook §7) | Change record | ☐ |
| 8 | ID-change guard installed on every synced type (runbook §2.3, step 5) | Configuration review | ☐ |
| 9 | Monitoring in place: the signals of runbook §5, alerting on reconciliation failures and sync failures | Monitoring configuration | ☐ |
| 10 | **C17 decided:** until the service purges on an empty list, removing an entity's *last* relationship in IDM does not remove it in the service. Accept for go-live, or require the service fix first | Decision, with the service team's date | ☐ |
| 11 | **C1 status known:** create verification only warns while GET by ID fails | Service team's answer | ☐ |
| 12 | Natural-key signal agreed with the platform team (WS6.01), and each type's `idProperty` confirmed | Answers file under review | ☐ |

## 3. Sign-off

| Role | Name | Decision | Date |
|---|---|---|---|
| Operations | | ☐ approve ☐ reject | |
| Security | | ☐ approve ☐ reject | |
| Design owner | | ☐ approve ☐ reject | |
| Service owner (informed) | | | |

## 4. Handover (WS9.08)

Walk support through the following, and record attendance:
1. **Runbook §1:** what the connector does and does not do at M5.
2. **Runbook §5:** the health report (`ops/entitlementsaccelerator_health.py`), what each signal means, and when to act.
3. **Runbook §6:** reading the logs. Cover the dropped-relationship WARN, doubled writes, self-correcting intermediate states, ID changes, and messages by HTTP status.
4. **Runbook §7:** key rotation, and what to do if the key leaks.
5. **Runbook §8 and §9:** troubleshooting, and the known issues with their owners.
6. **Escalation:** connector defects go to the connector team; service behaviour to the service team, with `C1-GET-BY-ID-NOTE.md` as the model of the evidence they need.

| Session date | Support attendees | Runbook version |
|---|---|---|
| | | |
