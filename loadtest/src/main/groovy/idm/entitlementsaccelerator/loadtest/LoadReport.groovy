package idm.entitlementsaccelerator.loadtest

import groovy.json.JsonOutput

/**
 * The run's record: report.json for machines, report.md for people (docs/load-testing.md §5). Holds IDs, counts,
 * timings and statuses; never attribute values beyond the run's own synthetic ones, and never a secret: the API key
 * and the IDM password are stripped from both files as a last guard.
 */
class LoadReport {
    final LoadTestOptions o
    final Map<String, Object> environment = [:]
    final List<Map<String, Object>> phases = []
    final Map<String, Latencies> latencies = new LinkedHashMap<>()
    final List<String> problems = []
    Map<String, Integer> serviceStatuses = [:]
    Map<String, Integer> logCounts
    boolean prefixFallback
    String started
    String finished

    LoadReport(LoadTestOptions o) { this.o = o }

    Latencies latency(String name) {
        synchronized (latencies) { latencies.computeIfAbsent(name) { new Latencies(name) } }
    }

    boolean passed() { !problems && phases.every { it.result in ['PASS', 'SKIPPED'] } }

    Map<String, Object> toMap() {
        [verdict: passed() ? 'PASS' : 'FAIL', started: started, finished: finished, settings: o.describe(), environment: environment,
         phases: phases, latencies: latencies.values()*.summary(), serviceStatusesSeenByTheTool: serviceStatuses,
         prefixSearch: prefixFallback ? 'refused by the service: paged through everything and filtered by prefix' : 'STARTSWITH filter on ID',
         idmLogCounts: logCounts, problems: problems] as Map<String, Object>
    }

    void write(File dir) {
        dir.mkdirs()
        new File(dir, 'report.json').setText(scrub(JsonOutput.prettyPrint(JsonOutput.toJson(toMap()))) + '\n', 'UTF-8')
        new File(dir, 'report.md').setText(scrub(markdown()), 'UTF-8')
    }

    String markdown() {
        StringBuilder md = new StringBuilder()
        md << "# Load test ${o.runId}: ${passed() ? 'PASS' : 'FAIL'}\n\n"
        md << "${started} to ${finished}. Entities: IDs starting `${o.prefix}`.\n\n"
        md << '## Environment\n\n'
        environment.each { k, v -> md << "- **${k}:** ${v}\n" }
        md << "- **Finding the run's entities in the service:** ${toMap().prefixSearch}\n\n"
        md << '## Phases\n\n| Phase | Result | Seconds | Figures |\n|---|---|---|---|\n'
        phases.each { Map p ->
            String figures = (p.metrics as Map ?: [:]).collect { k, v -> "${k}: ${v}" }.join('; ')
            md << "| ${p.name} | ${p.result} | ${p.seconds ?: ''} | ${figures} |\n"
        }
        List<String> notes = phases.collectMany { Map p -> (p.notes as List<String> ?: []).collect { "**${p.name}:** ${it}".toString() } }
        if (notes) { md << '\n### Notes\n\n'; notes.each { md << "- ${it}\n" } }
        md << '\n## Response times\n\n| Call | Count | Errors | Mean ms | p50 | p95 | p99 | Max |\n|---|---|---|---|---|---|---|---|\n'
        latencies.values()*.summary().each { Map s ->
            String errors = (s.errors as Map) ? (s.errors as Map).collect { k, v -> "${k}: ${v}" }.join(', ') : '0'
            md << "| ${s.name} | ${s.count} | ${errors} | ${s.meanMs ?: ''} | ${s.p50Ms ?: ''} | ${s.p95Ms ?: ''} | ${s.p99Ms ?: ''} | ${s.maxMs ?: ''} |\n"
        }
        md << "\n## The service, as this tool saw it\n\nHTTP statuses of the tool's own reads and deletes: " +
            (serviceStatuses ? serviceStatuses.collect { k, v -> "${k}: ${v}" }.join(', ') : 'none') + '.\n'
        md << "The connector's own calls are counted in IDM's log, below, when the tool can read it.\n"
        if (logCounts != null) {
            md << "\n## IDM's log during the run\n\n| Kind | Lines |\n|---|---|\n"
            logCounts.each { k, v -> md << "| ${k} | ${v} |\n" }
        }
        md << '\n## Settings\n\n'
        o.describe().each { k, v -> md << "- ${k}: ${v}\n" }
        if (problems) { md << '\n## Problems\n\n'; problems.each { md << "- ${it}\n" } }
        md.toString()
    }

    /** Strips the secrets, in case an error message from IDM or the service ever echoed one. */
    String scrub(String text) {
        String out = text
        [o.apiKey, o.idmPassword].each { char[] secret ->
            if (secret && secret.length >= 4) out = out.replace(new String(secret), '***')
        }
        out
    }
}
