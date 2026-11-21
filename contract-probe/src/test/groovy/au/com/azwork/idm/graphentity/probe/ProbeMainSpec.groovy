package au.com.azwork.idm.graphentity.probe

import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

class ProbeMainSpec extends Specification {

    @TempDir File dir
    static final Map ENV = [GRAPH_PROBE_API_KEY: 'k-123']

    def "parses a minimal read-only invocation"() {
        when:
        def o = ProbeMain.parse(['--base-url', 'https://graph.test.example'], ENV)

        then:
        o.baseUrl == 'https://graph.test.example'
        String.valueOf(o.apiKey) == 'k-123'
        o.apiKeyHeader == 'X-API-KEY'
        !o.allowWrites
        o.maxPages == 5
        o.outDir.path.startsWith('build/contract-probe/')
    }

    def "parses every option"() {
        when:
        def o = ProbeMain.parse(['--base-url', 'http://h:1', '--api-key-env', 'X', '--api-key-header', 'X-Other-Key', '--allow-writes', '--out', dir.path,
                                 '--max-pages', '9', '--timeout-seconds', '3', '--search-path', '/x', '--run-id', 'abc-1'], [X: 'p'])

        then:
        String.valueOf(o.apiKey) == 'p'
        o.apiKeyHeader == 'X-Other-Key'
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
        why                          | args                                                          | env                                 | expected
        'a missing base URL'         | []                                                            | ENV                                 | '--base-url'
        'a missing API key'          | ['--base-url', 'http://h']                                    | [:]                                 | 'GRAPH_PROBE_API_KEY'
        'a key in another variable'  | ['--base-url', 'http://h', '--api-key-env', 'K']              | ENV                                 | 'K environment variable'
        'a key with a line break'    | ['--base-url', 'http://h']                                    | [GRAPH_PROBE_API_KEY: "a\r\nX: y"] | 'control characters'
        'a bad header name'          | ['--base-url', 'http://h', '--api-key-header', 'X API KEY']   | ENV                                 | '--api-key-header'
        'a non-http URL'             | ['--base-url', 'ftp://h']                                     | ENV                                 | 'http or https'
        'an unknown flag'            | ['--base-url', 'http://h', '--insecure']                      | ENV                                 | 'Unknown argument'
        'the removed username flag'  | ['--base-url', 'http://h', '--username', 'u']                 | ENV                                 | 'Unknown argument'
        'a bad page count'           | ['--base-url', 'http://h', '--max-pages', '0']                | ENV                                 | 'positive'
        'an unsafe run id'           | ['--base-url', 'http://h', '--run-id', '../x']                | ENV                                 | '--run-id'
        'a flag without a value'     | ['--base-url']                                                | ENV                                 | 'needs a value'
    }

    def "there is no option to disable TLS verification"() {
        expect:
        !ProbeMain.USAGE.toLowerCase().contains('insecure')
    }

    def "the API key never appears in the options' string form"() {
        expect:
        !ProbeMain.parse(['--base-url', 'http://h'], [GRAPH_PROBE_API_KEY: 'hunter2']).toString().contains('hunter2')
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
        ProbeMain.run(['--base-url', "http://127.0.0.1:${port}".toString(), '--out', dir.path, '--timeout-seconds', '2'],
            ENV, new PrintStream(out), new PrintStream(err)) == 1
        new File(dir, 'report.md').text.contains('Is the service reachable?')
    }
}
