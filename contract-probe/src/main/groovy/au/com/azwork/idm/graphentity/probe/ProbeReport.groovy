package au.com.azwork.idm.graphentity.probe

import groovy.json.JsonOutput

import java.time.Instant

/**
 * Writes a probe run to disk:
 * <ul>
 *   <li>{@code report.md}: answers grouped by WS0 task, suggested configuration values, cleanup status;</li>
 *   <li>{@code findings.json}: the same, machine-readable;</li>
 *   <li>{@code exchanges.jsonl}: every exchange, scrubbed, one JSON object per line;</li>
 *   <li>{@code wiremock/mappings/*.json}: each valid-credential exchange as a WireMock stub (WS0.11).</li>
 * </ul>
 * Nothing written contains request headers, and every entity not created by the probe is scrubbed.
 */
class ProbeReport {

    static final Map<String, String> TASK_TITLES = [
        'WS0.02': 'Search endpoint',
        'WS0.03': 'Paging protocol',
        'WS0.04': 'Duplicate POST',
        'WS0.05': 'Error bodies',
        'WS0.06': 'Read-only fields',
        'WS0.07': 'Edge semantics',
        'WS0.08': 'Type discrimination',
        'WS0.09': 'ID constraints',
        'WS0.10': 'Auth and limits',
        'WS0.15': 'Decision checks (P18, P20, P22)']

    static void write(ContractProbe probe, File dir) {
        dir.mkdirs()
        def mappings = new File(dir, 'wiremock/mappings')
        mappings.mkdirs()
        def s = probe.scrubber

        new File(dir, 'exchanges.jsonl').withWriter('UTF-8') { w ->
            probe.http.exchanges.each { ex ->
                w << JsonOutput.toJson([n: ex.n, check: ex.check, method: ex.method, path: s.scrubPath(ex.path), query: ex.query, auth: ex.auth,
                    requestBody: s.scrub(ex.requestBody), status: ex.status, headers: ex.headers, body: s.scrubResponse(ex.path, ex.body),
                    rawBody: persistedRawBody(ex), error: ex.error, durationMs: ex.durationMs]) << '\n'
            }
        }

        probe.http.exchanges.findAll { it.status > 0 && it.auth == 'valid' }.each { ex ->
            def request = [method: ex.method, urlPath: s.scrubPath(ex.path)] as Map<String, Object>
            if (ex.query) request.queryParameters = ex.query.collectEntries { k, v -> [(k): [equalTo: v]] }
            // Searches all POST to one path, so the body is what tells recorded requests apart.
            if (ex.method in ['POST', 'PUT'] && (ex.requestBody instanceof Map || ex.requestBody instanceof List)) {
                request.bodyPatterns = [[equalToJson: JsonOutput.toJson(s.scrub(ex.requestBody))]]
            }
            def response = [status: ex.status] as Map<String, Object>
            if (ex.contentType) response.headers = ['Content-Type': ex.contentType]
            if (ex.body != null) response.jsonBody = s.scrubResponse(ex.path, ex.body)
            else if (persistedRawBody(ex) != null) response.body = persistedRawBody(ex)
            def mapping = [request: request, response: response, metadata: [probeExchange: ex.n, check: ex.check, runId: probe.opts.runId]]
            new File(mappings, String.format('%04d-%s.json', ex.n, ex.method.toLowerCase())).text = JsonOutput.prettyPrint(JsonOutput.toJson(mapping))
        }

        new File(dir, 'findings.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
            runId: probe.opts.runId, baseUrl: probe.opts.baseUrl, generatedAt: Instant.now().toString(),
            mode: probe.opts.allowWrites ? 'read-write' : 'read-only', exchanges: probe.http.exchanges.size(),
            contract: probe.contract, findings: probe.findings, cleanupProblems: probe.cleanupProblems,
            cleanupUnconfirmed: probe.cleanupUnconfirmed]))

        new File(dir, 'report.md').text = markdown(probe)
    }

    static String markdown(ContractProbe probe) {
        def o = probe.opts
        def sb = new StringBuilder()
        sb << "# Service contract probe report\n\n"
        sb << "| | |\n|---|---|\n"
        sb << "| Run | `${o.runId}` |\n| Service | `${o.baseUrl}` |\n| Mode | ${o.allowWrites ? 'read-write (probe entities only)' : 'read-only'} |\n"
        sb << "| Exchanges | ${probe.http.exchanges.size()} |\n| Generated | ${Instant.now()} |\n\n"
        sb << "Evidence numbers such as `#12` refer to `exchanges.jsonl` and to `wiremock/mappings/0012-*.json`.\n"
        sb << "Copy confirmed answers into `docs/service-contract.md` together with the evidence numbers.\n\n"

        sb << "## Answers by task\n\n"
        def byTask = probe.findings.groupBy { it.task }
        (TASK_TITLES.keySet() + (byTask.keySet() - TASK_TITLES.keySet())).each { task ->
            def fs = byTask[task]
            if (!fs) return
            sb << "### ${task} ${TASK_TITLES[task] ?: ''}\n\n"
            sb << "| Question | Answer | Confidence | Evidence |\n|---|---|---|---|\n"
            fs.each { f ->
                sb << "| ${cell(f.question)} | ${cell(f.answer)}${f.detail ? '<br><sub>' + cell(f.detail) + '</sub>' : ''} | ${f.confidence} | ${evidence(f.evidence)} |\n"
            }
            sb << '\n'
        }

        sb << "## Suggested values\n\nThese follow from the answers above. Check them before changing any code.\n\n"
        sb << "| Setting | Suggested value | Where it goes |\n|---|---|---|\n"
        def c = probe.contract
        sb << "| Search request | ${c.searchPath ? "`POST ${c.searchPath}` with a JSON body" : '—'} | `RestClientEntityApi.search` |\n"
        sb << "| Create body accepted | ${c.createShape ?: '—'} | `EntityPayloadBuilder`, `EntityDocument.toWire` |\n"
        sb << "| Relationship form accepted | ${c.relationshipForm ?: '—'} | `EdgeCodec` (P17) |\n"
        sb << "| Search drops deleted entities | ${c.searchReflectsDelete == null ? '—' : (c.searchReflectsDelete ? 'yes' : '**no**')} | delete verification; WS10 orphan handling |\n"
        sb << "| Base-type numbering (objectClass) | ${c.baseTypeNumbering ?: '—'} | `EntityKind` (P18) |\n"
        sb << "| Base type of a created entity | ${c.baseTypeOnCreate ?: '—'} | `EntityKind` (P18); gates WS1.02 |\n"
        sb << "| GET by ID | ${c.getById ?: '—'} | `byIdLookup` default (P21) |\n"
        sb << "| ID filter field | ${v(c.idFilterField)} | `SearchPlanner`, by-ID search fallback (P21) |\n"
        sb << "| Page size field | ${v(c.pageSizeParam)} | `PageCursor.toQuery` |\n"
        sb << "| Cursor | ${c.cursorParam ? "response `${c.cursorKey}` → request `${c.cursorParam}`" : '—'} | `PageCursor` |\n"
        sb << "| Response envelope | ${c.envelope ?: '—'}${c.totalKey ? ", total in `${c.totalKey}`" : ''} | `PageCursor.next`, `FakeGraphService.search` |\n"
        sb << "| `lastEvaluated` format | ${c.lastEvaluatedFormat ?: '—'} | `EntityDecoder` |\n"
        sb << "| Ambiguous archetypes | ${c.ambiguousArchetypes ? (c.ambiguousArchetypes as List).join(', ') : 'none seen'} | `TypeScoper` (P19) |\n"
        sb << "| Leftover probe entities | ${c.leftoverProbeEntities == null ? '—' : c.leftoverProbeEntities} | remove by hand |\n"
        sb << "| Manifest archetypes | ${c.subjectArchetypes != null ? "Subject: ${(c.subjectArchetypes as List).join(', ') ?: 'none'}; Resource: ${(c.resourceArchetypes as List).join(', ') ?: 'none'}" : '—'} | `objectTypes` archetypes; Test operation (P22) |\n\n"

        sb << "## Cleanup\n\n"
        if (!o.allowWrites) sb << "Read-only run: nothing was created.\n\n"
        else {
            int byGet = probe.cleanupConfirmedBy.values().count { it == 'GET 404' } as int
            int bySearch = probe.cleanupConfirmedBy.values().count { it == 'search' } as int
            int neverWritten = (probe.touched - probe.written).size()
            if (probe.cleanupProblems) sb << "**Some probe entities may remain.** Remove these by hand:\n\n" + probe.cleanupProblems.collect { "- ${it}" }.join('\n') + "\n\n"
            if (probe.cleanupUnconfirmed) sb << "**Removal not confirmed** for ${probe.cleanupUnconfirmed.size()} entities. DELETE answered 2xx and search no longer finds them, but GET fails (C1) and search has not been shown to drop deleted entities (`search.reflectsDelete`). Check them by hand:\n\n" +
                probe.cleanupUnconfirmed.collect { "- ${it}" }.join('\n') + "\n\n"
            if (!probe.cleanupProblems && !probe.cleanupUnconfirmed) sb << "All ${probe.written.size()} probe entities that were written were deleted and confirmed gone" +
                (probe.written ? ": ${byGet} by a GET returning 404" + (bySearch ? ", ${bySearch} by a search on the ID finding nothing, after `search.reflectsDelete` showed search drops deleted entities" : '') : '') + '.'
            sb << (neverWritten ? " ${neverWritten} other probe IDs were never accepted by the service, and no search finds them.\n\n" : '\n\n')
        }

        sb << "## Not answerable by the probe\n\n"
        sb << "These need the service team. They are listed in `docs/service-contract.md`:\n\n"
        sb << "- Whether anything other than IDM writes relationships (design assumption A1).\n"
        sb << "- 403 semantics, service account scoping and the rate-limit policy.\n"
        sb << "- Documented limits on ID length and characters, as opposed to what happens to be accepted today.\n"
        sb << "- Whether ordering and paging behaviour are guaranteed or incidental.\n"
        sb << "- Why GET by ID fails (C1), and the names of the ten filter operations (P21).\n"
        sb.toString()
    }

    /**
     * A non-JSON body cannot be scrubbed field by field, so it is only kept for error responses, where it explains
     * the failure, and then only its first 500 characters. A successful non-JSON body is never written.
     */
    static String persistedRawBody(Exchange ex) {
        if (ex.rawBody == null || ex.status < 400) return null
        ex.rawBody.length() > 500 ? ex.rawBody.substring(0, 500) + '…' : ex.rawBody
    }

    private static String v(Object x) { x ? "`${x}`" : '—' }

    /** Compresses evidence numbers into ranges: [1,2,3,7] → "#1–#3 #7". */
    static String evidence(List<Integer> ns) {
        if (!ns) return ''
        List<String> parts = []
        List<Integer> sorted = ns.toSorted()
        int start = sorted[0], prev = sorted[0]
        (sorted.drop(1) + [Integer.MIN_VALUE]).each { n ->
            if (n == prev + 1) { prev = n; return }
            parts << (start == prev ? "#${start}" : (prev == start + 1 ? "#${start} #${prev}" : "#${start}–#${prev}")).toString()
            start = n; prev = n
        }
        parts.join(' ')
    }

    private static String cell(String s) { (s ?: '').replace('|', '\\|').replace('\n', ' ') }
}
