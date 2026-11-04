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
        'WS0.10': 'Auth and limits']

    static void write(ContractProbe probe, File dir) {
        dir.mkdirs()
        def mappings = new File(dir, 'wiremock/mappings')
        mappings.mkdirs()
        def s = probe.scrubber

        new File(dir, 'exchanges.jsonl').withWriter('UTF-8') { w ->
            probe.http.exchanges.each { ex ->
                w << JsonOutput.toJson([n: ex.n, check: ex.check, method: ex.method, path: s.scrubPath(ex.path), query: ex.query, auth: ex.auth,
                    requestBody: s.scrub(ex.requestBody), status: ex.status, headers: ex.headers, body: s.scrub(ex.body),
                    rawBody: ex.rawBody, error: ex.error, durationMs: ex.durationMs]) << '\n'
            }
        }

        probe.http.exchanges.findAll { it.status > 0 && it.auth == 'valid' }.each { ex ->
            def request = [method: ex.method, urlPath: s.scrubPath(ex.path)] as Map<String, Object>
            if (ex.query) request.queryParameters = ex.query.collectEntries { k, v -> [(k): [equalTo: v]] }
            def response = [status: ex.status] as Map<String, Object>
            if (ex.contentType) response.headers = ['Content-Type': ex.contentType]
            if (ex.body != null) response.jsonBody = s.scrub(ex.body)
            else if (ex.rawBody != null) response.body = ex.rawBody
            def mapping = [request: request, response: response, metadata: [probeExchange: ex.n, check: ex.check, runId: probe.opts.runId]]
            new File(mappings, String.format('%04d-%s.json', ex.n, ex.method.toLowerCase())).text = JsonOutput.prettyPrint(JsonOutput.toJson(mapping))
        }

        new File(dir, 'findings.json').text = JsonOutput.prettyPrint(JsonOutput.toJson([
            runId: probe.opts.runId, baseUrl: probe.opts.baseUrl, generatedAt: Instant.now().toString(),
            mode: probe.opts.allowWrites ? 'read-write' : 'read-only', exchanges: probe.http.exchanges.size(),
            contract: probe.contract, findings: probe.findings, cleanupProblems: probe.cleanupProblems]))

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
        sb << "| Search path | ${v(c.searchPath)} | `RestClientEntityApi` |\n"
        sb << "| Timestamp parameter | ${v(c.timestampParam)} | `timestampSearchParam` default in `ConnectorConfig` |\n"
        sb << "| Datetime formats accepted | ${c.acceptedDatetimeFormats ? (c.acceptedDatetimeFormats as List).join('; ') : '—'} | `SearchPlanner` |\n"
        sb << "| Page size parameter | ${v(c.pageSizeParam)} | `PageCursor.toQuery` |\n"
        sb << "| Offset parameter | ${v(c.offsetParam)} | `PageCursor.toQuery` |\n"
        sb << "| Cursor | ${c.cursorParam ? "response `${c.cursorKey}` → request `${c.cursorParam}`" : '—'} | `PageCursor` |\n"
        sb << "| Response envelope | ${c.envelope ?: '—'}${c.totalKey ? ", total in `${c.totalKey}`" : ''} | `PageCursor.next`, `FakeGraphService.search` |\n"
        sb << "| Last-updated field | ${v(c.lastUpdatedField)}${c.lastUpdatedFormat ? ", ${c.lastUpdatedFormat}" : ''} | `EntityDocument`, `EntityDecoder` |\n"
        sb << "| Ambiguous archetypes | ${c.ambiguousArchetypes ? (c.ambiguousArchetypes as List).join(', ') : 'none seen'} | `TypeScoper` (P5) |\n\n"

        sb << "## Cleanup\n\n"
        if (!o.allowWrites) sb << "Read-only run: nothing was created.\n\n"
        else if (probe.cleanupProblems) sb << "**Some probe entities may remain.** Remove these by hand:\n\n" + probe.cleanupProblems.collect { "- ${it}" }.join('\n') + "\n\n"
        else sb << "All ${probe.touched.size()} probe entity IDs were deleted and confirmed gone.\n\n"

        sb << "## Not answerable by the probe\n\n"
        sb << "These need the service team. They are listed in `docs/service-contract.md`:\n\n"
        sb << "- Whether anything other than IDM writes relationships (design assumption A1).\n"
        sb << "- 403 semantics, service account scoping and the rate-limit policy.\n"
        sb << "- Documented limits on ID length and characters, as opposed to what happens to be accepted today.\n"
        sb << "- Whether ordering and paging behaviour are guaranteed or incidental.\n"
        sb.toString()
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
