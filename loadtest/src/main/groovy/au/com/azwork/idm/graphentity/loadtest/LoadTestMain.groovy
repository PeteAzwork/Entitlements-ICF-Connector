package au.com.azwork.idm.graphentity.loadtest

/** Command line: exit 0 when every phase passed, 1 when one failed, 2 for a usage error. */
class LoadTestMain {
    static void main(String[] args) {
        System.exit(run(args as List<String>, System.getenv()))
    }

    static int run(List<String> args, Map<String, String> env) {
        LoadTestOptions o
        try {
            o = LoadTestOptions.parse(args, env)
        } catch (IllegalArgumentException e) {
            if (e.message) System.err.println("error: ${e.message}")
            System.err.println(LoadTestOptions.USAGE)
            return 2
        }
        println "Load test ${o.runId}: ${o.users} users and accounts, ${o.changes} changes at ${o.rate}/s; entities ${o.prefix}*"
        int code = new LoadTest(o).run()
        println "${code == 0 ? 'PASS' : 'FAIL'}: report in ${new File(o.out, 'report.md').path}"
        code
    }
}
