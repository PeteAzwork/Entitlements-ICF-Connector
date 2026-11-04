package au.com.azwork.idm.graphentity.probe

import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

class ProbeMainSpec extends Specification {

    @TempDir File dir
    static final Map ENV = [GRAPH_PROBE_PASSWORD: 'pw']

    def "parses a minimal read-only invocation"() {
        when:
        def o = ProbeMain.parse(['--base-url', 'https://graph.test.example', '--username', 'svc'], ENV)

        then:
        o.baseUrl == 'https://graph.test.example'
        o.username == 'svc'
        String.valueOf(o.password) == 'pw'
        !o.allowWrites
        o.maxPages == 5
        o.outDir.path.startsWith('build/contract-probe/')
    }

    def "parses every option"() {
        when:
        def o = ProbeMain.parse(['--base-url', 'http://h:1', '--username', 'u', '--password-env', 'X', '--allow-writes', '--out', dir.path,
                                 '--max-pages', '9', '--timeout-seconds', '3', '--search-path', '/x', '--run-id', 'abc-1'], [X: 'p'])

        then:
        o.allowWrites
        o.outDir == dir
        o.maxPages == 9
        o.timeoutSeconds == 3
        o.searchPath == '/x'
        o.prefix == 'ws0-probe-abc-1-'
    }

    @Unroll
    def "rejects #why"() {
        when:
        ProbeMain.parse(args, env)

        then:
        def e = thrown(ProbeMain.UsageException)
        e.message.contains(expected)

        where:
        why                       | args                                                                 | env  | expected
        'a missing base URL'      | ['--username', 'u']                                                  | ENV  | '--base-url'
        'a missing username'      | ['--base-url', 'http://h']                                           | ENV  | '--username'
        'a missing password'      | ['--base-url', 'http://h', '--username', 'u']                        | [:]  | 'GRAPH_PROBE_PASSWORD'
        'a non-http URL'          | ['--base-url', 'ftp://h', '--username', 'u']                         | ENV  | 'http or https'
        'an unknown flag'         | ['--base-url', 'http://h', '--username', 'u', '--insecure']          | ENV  | 'Unknown argument'
        'a bad page count'        | ['--base-url', 'http://h', '--username', 'u', '--max-pages', '0']    | ENV  | 'positive'
        'an unsafe run id'        | ['--base-url', 'http://h', '--username', 'u', '--run-id', '../x']    | ENV  | '--run-id'
        'a flag without a value'  | ['--base-url']                                                       | ENV  | 'needs a value'
    }

    def "there is no option to disable TLS verification"() {
        expect:
        !ProbeMain.USAGE.toLowerCase().contains('insecure')
    }

    def "the password never appears in the options' string form"() {
        expect:
        !ProbeMain.parse(['--base-url', 'http://h', '--username', 'u'], [GRAPH_PROBE_PASSWORD: 'hunter2']).toString().contains('hunter2')
    }

    def "usage errors exit with code 2 and print usage"() {
        given:
        def out = new ByteArrayOutputStream()
        def err = new ByteArrayOutputStream()

        expect:
        ProbeMain.run([], ENV, new PrintStream(out), new PrintStream(err)) == 2
        err.toString().contains('Usage: contract-probe')
    }

    def "an unreachable service exits with code 1 and still writes a report"() {
        given:
        def port = new ServerSocket(0).withCloseable { it.localPort }
        def out = new ByteArrayOutputStream()
        def err = new ByteArrayOutputStream()

        expect:
        ProbeMain.run(['--base-url', "http://127.0.0.1:${port}".toString(), '--username', 'u', '--out', dir.path, '--timeout-seconds', '2'],
            ENV, new PrintStream(out), new PrintStream(err)) == 1
        new File(dir, 'report.md').text.contains('Is the service reachable?')
    }
}
