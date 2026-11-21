package au.com.azwork.idm.graphentity.probe

/**
 * Command line for the WS0 contract probe.
 *
 * <pre>
 * ./gradlew :contract-probe:run --args="--base-url https://graph.test.example --api-key KEY"
 * GRAPH_PROBE_API_KEY=... ./gradlew :contract-probe:run --args="--base-url https://graph.test.example --allow-writes"
 * </pre>
 *
 * The API key comes from {@code --api-key}, or failing that from an environment variable. The option is the
 * simplest to use but leaves the key in shell history and visible in the process list; the environment
 * variable avoids both. The key is sent raw in the {@code X-API-KEY} header (configurable with
 * {@code --api-key-header}) and never written to any output.
 * Exit codes: 0 finished (see the report for answers), 1 the probe could not complete, 2 usage error.
 */
class ProbeMain {

    static final String USAGE = '''\
Usage: contract-probe --base-url URL [--api-key KEY] [options]

  --base-url URL         Service base URL, e.g. https://graph.test.example (required)
  --api-key KEY          The service's API key. Convenient, but the key is kept in shell
                         history and is visible in the process list. If omitted, the key is
                         read from the environment variable named by --api-key-env.
  --api-key-env NAME     Environment variable holding the API key (default GRAPH_PROBE_API_KEY)
  --api-key-header NAME  Header the key is sent in, raw (default X-API-KEY)
  --allow-writes         Also create, change and delete probe entities (ID prefix ws0-probe-<run>-).
                         Use only against a test instance. Without it, only GET requests are made.
  --remove-leftovers     With --allow-writes: delete entities earlier probe runs left behind
                         (IDs starting ws0-probe-; none of this run's, nothing else)
  --out DIR              Output directory (default build/contract-probe/<run>)
  --max-pages N          Pages to read when sampling entities (default 5)
  --timeout-seconds N    Per-request timeout (default 15)
  --settle-seconds N     How long a read-back waits for a write to show (default 10; 0 reads once)
  --search-path PATH     Search endpoint, called with POST and a JSON body
                         (default /admin/entities/search)
  --archetypes A,B       Two Resource archetypes for the archetype-replacement check (P22).
                         Without it, they are taken from the manifests.
  --run-id ID            Override the run identifier (letters, digits and dashes)
'''

    static class UsageException extends RuntimeException {
        UsageException(String m) { super(m) }
    }

    /** Pure parsing so it can be tested without touching the network or the real environment. */
    static ProbeOptions parse(List<String> args, Map<String, String> env) {
        def o = new ProbeOptions()
        String keyEnv = 'GRAPH_PROBE_API_KEY'
        String keyArg = null
        def it = args.iterator()
        Closure<String> next = { String flag ->
            if (!it.hasNext()) throw new UsageException("${flag} needs a value")
            it.next()
        }
        while (it.hasNext()) {
            String a = it.next()
            switch (a) {
                case '--base-url': o.baseUrl = next(a); break
                case '--api-key': keyArg = next(a); break
                case '--api-key-env': keyEnv = next(a); break
                case '--api-key-header':
                    o.apiKeyHeader = next(a)
                    if (!(o.apiKeyHeader ==~ /[A-Za-z0-9-]{1,64}/)) throw new UsageException('--api-key-header must be a header name: letters, digits and dashes')
                    break
                case '--allow-writes': o.allowWrites = true; break
                case '--remove-leftovers': o.removeLeftovers = true; break
                case '--out': o.outDir = new File(next(a)); break
                case '--max-pages': o.maxPages = positive(a, next(a)); break
                case '--timeout-seconds': o.timeoutSeconds = positive(a, next(a)); break
                case '--settle-seconds': o.settleSeconds = nonNegative(a, next(a)); break
                case '--search-path':
                    o.searchPath = next(a)
                    if (!o.searchPath.startsWith('/')) throw new UsageException('--search-path must start with /')
                    if (o.searchPath in ContractProbe.CREATE_PATHS) throw new UsageException("--search-path ${o.searchPath} is the create endpoint: a POST there creates entities")
                    break
                case '--archetypes':
                    o.archetypes = next(a).split(',')*.trim().findAll() as List<String>
                    if (o.archetypes.size() < 2 || o.archetypes.any { !(it ==~ /[^\u0000-\u001f\u007f]{1,128}/) }) throw new UsageException('--archetypes needs two or more comma-separated archetype names')
                    break
                case '--run-id':
                    o.runId = next(a)
                    if (!(o.runId ==~ /[A-Za-z0-9-]{1,40}/)) throw new UsageException('--run-id must be 1-40 letters, digits or dashes')
                    break
                case '--help': case '-h': throw new UsageException('')
                default: throw new UsageException("Unknown argument: ${a}")
            }
        }
        if (!o.baseUrl) throw new UsageException('--base-url is required')
        if (o.removeLeftovers && !o.allowWrites) throw new UsageException('--remove-leftovers needs --allow-writes')
        if (!(o.baseUrl ==~ /https?:\/\/[^\/\s]+.*/)) throw new UsageException('--base-url must be an http or https URL')
        String key = keyArg != null ? keyArg : env[keyEnv]
        String source = keyArg != null ? '--api-key' : "the ${keyEnv} environment variable"
        if (!key) throw new UsageException(keyArg != null ? '--api-key must not be empty' :
            "No API key: pass --api-key KEY, or set the ${keyEnv} environment variable")
        if (key.any { it < ' ' || it == '\u007f' }) throw new UsageException("The API key from ${source} contains control characters")
        o.apiKey = key.toCharArray()
        if (!o.outDir) o.outDir = new File("build/contract-probe/${o.runId}")
        o
    }

    private static int nonNegative(String flag, String v) {
        try {
            int n = Integer.parseInt(v)
            if (n < 0 || n > 600) throw new NumberFormatException()
            n
        } catch (NumberFormatException e) {
            throw new UsageException("${flag} must be a whole number from 0 to 600")
        }
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
        if (probe.cleanupUnconfirmed) err.println("Removal of ${probe.cleanupUnconfirmed.size()} probe entities could not be confirmed, see the report")
        errors.any { it.key in ['transport', 'auth.valid', 'search.path'] } ? 1 : 0
    }

    static void main(String[] args) {
        System.exit(run(args as List, System.getenv(), System.out, System.err))
    }
}
