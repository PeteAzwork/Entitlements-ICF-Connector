package au.com.azwork.idm.graphentity.probe

/**
 * Command line for the WS0 contract probe.
 *
 * <pre>
 * GRAPH_PROBE_PASSWORD=... ./gradlew :contract-probe:run --args="--base-url https://graph.test.example --username svc-probe"
 * GRAPH_PROBE_PASSWORD=... ./gradlew :contract-probe:run --args="--base-url https://graph.test.example --username svc-probe --allow-writes"
 * </pre>
 *
 * The password is read from an environment variable so it never appears in shell history or process lists.
 * Exit codes: 0 finished (see the report for answers), 1 the probe could not complete, 2 usage error.
 */
class ProbeMain {

    static final String USAGE = '''\
Usage: contract-probe --base-url URL --username USER [options]

  --base-url URL         Service base URL, e.g. https://graph.test.example (required)
  --username USER        Basic-auth user (required). Password comes from the environment.
  --password-env NAME    Environment variable holding the password (default GRAPH_PROBE_PASSWORD)
  --allow-writes         Also create, change and delete probe entities (ID prefix ws0-probe-<run>-).
                         Use only against a test instance. Without it, only GET requests are made.
  --out DIR              Output directory (default build/contract-probe/<run>)
  --max-pages N          Pages to read when sampling entities (default 5)
  --timeout-seconds N    Per-request timeout (default 15)
  --search-path PATH     Skip search-path discovery and use PATH
  --run-id ID            Override the run identifier (letters, digits and dashes)
'''

    static class UsageException extends RuntimeException {
        UsageException(String m) { super(m) }
    }

    /** Pure parsing so it can be tested without touching the network or the real environment. */
    static ProbeOptions parse(List<String> args, Map<String, String> env) {
        def o = new ProbeOptions()
        String passwordEnv = 'GRAPH_PROBE_PASSWORD'
        def it = args.iterator()
        Closure<String> next = { String flag ->
            if (!it.hasNext()) throw new UsageException("${flag} needs a value")
            it.next()
        }
        while (it.hasNext()) {
            String a = it.next()
            switch (a) {
                case '--base-url': o.baseUrl = next(a); break
                case '--username': o.username = next(a); break
                case '--password-env': passwordEnv = next(a); break
                case '--allow-writes': o.allowWrites = true; break
                case '--out': o.outDir = new File(next(a)); break
                case '--max-pages': o.maxPages = positive(a, next(a)); break
                case '--timeout-seconds': o.timeoutSeconds = positive(a, next(a)); break
                case '--search-path': o.searchPath = next(a); break
                case '--run-id':
                    o.runId = next(a)
                    if (!(o.runId ==~ /[A-Za-z0-9-]{1,40}/)) throw new UsageException('--run-id must be 1-40 letters, digits or dashes')
                    break
                case '--help': case '-h': throw new UsageException('')
                default: throw new UsageException("Unknown argument: ${a}")
            }
        }
        if (!o.baseUrl) throw new UsageException('--base-url is required')
        if (!(o.baseUrl ==~ /https?:\/\/[^\/\s]+.*/)) throw new UsageException('--base-url must be an http or https URL')
        if (!o.username) throw new UsageException('--username is required')
        String pw = env[passwordEnv]
        if (!pw) throw new UsageException("Set the password in the ${passwordEnv} environment variable")
        o.password = pw.toCharArray()
        if (!o.outDir) o.outDir = new File("build/contract-probe/${o.runId}")
        o
    }

    private static int positive(String flag, String v) {
        try {
            int n = Integer.parseInt(v)
            if (n < 1) throw new NumberFormatException()
            n
        } catch (NumberFormatException e) {
            throw new UsageException("${flag} must be a positive integer")
        }
    }

    static int run(List<String> args, Map<String, String> env, PrintStream out, PrintStream err) {
        ProbeOptions o
        try {
            o = parse(args, env)
        } catch (UsageException e) {
            if (e.message) err.println(e.message)
            err.print(USAGE)
            return 2
        }
        if (o.allowWrites) out.println("Write checks enabled. Probe entities use the ID prefix ${o.prefix} and are deleted at the end.")
        def probe = new ContractProbe(o)
        try {
            probe.run()
        } finally {
            ProbeReport.write(probe, o.outDir)
        }
        out.println("Report: ${new File(o.outDir, 'report.md').path}")
        def errors = probe.findings.findAll { it.confidence == 'error' }
        if (probe.cleanupProblems) err.println("Cleanup incomplete, see the report: ${probe.cleanupProblems.size()} problem(s)")
        errors.any { it.key in ['transport', 'auth.valid', 'search.path'] } ? 1 : 0
    }

    static void main(String[] args) {
        System.exit(run(args as List, System.getenv(), System.out, System.err))
    }
}
