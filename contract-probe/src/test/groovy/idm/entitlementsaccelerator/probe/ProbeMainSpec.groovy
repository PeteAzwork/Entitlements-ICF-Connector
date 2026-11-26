package idm.entitlementsaccelerator.probe

import idm.entitlementsaccelerator.support.FakeAcceleratorHttpServer
import idm.entitlementsaccelerator.support.FakeAcceleratorService
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

class ProbeMainSpec extends Specification {

    @TempDir File dir
    static final Map ENV = [ACCELERATOR_PROBE_API_KEY: 'k-123']

    def "parses a minimal read-only invocation"() {
        when:
        def o = ProbeMain.parse(['--base-url', 'https://accelerator.test.example'], ENV)

        then:
        o.baseUrl == 'https://accelerator.test.example'
        String.valueOf(o.apiKey) == 'k-123'
        o.apiKeyHeader == 'X-API-KEY'
        !o.allowWrites
        o.maxPages == 5
        o.outDir.path.startsWith('build/contract-probe/')
    }

    def "takes the API key from --api-key"() {
        when:
        def o = ProbeMain.parse(['--base-url', 'http://h', '--api-key', 'from-arg'], [:])

        then:
        String.valueOf(o.apiKey) == 'from-arg'
    }

    def "--api-key wins over the environment variable"() {
        expect:
        String.valueOf(ProbeMain.parse(['--api-key', 'from-arg', '--base-url', 'http://h'], [ACCELERATOR_PROBE_API_KEY: 'from-env']).apiKey) == 'from-arg'
    }

    def "the usage text warns that --api-key is kept in shell history"() {
        expect:
        ProbeMain.USAGE.contains('--api-key KEY')
        ProbeMain.USAGE.contains('shell')
        ProbeMain.USAGE.contains('history')
    }

    def "parses every option"() {
        when:
        def o = ProbeMain.parse(['--base-url', 'http://h:1', '--api-key-env', 'X', '--api-key-header', 'X-Other-Key', '--allow-writes', '--out', dir.path,
                                 '--max-pages', '9', '--timeout-seconds', '3', '--search-path', '/x', '--archetypes', 'Card, Account', '--settle-seconds', '0', '--remove-leftovers', '--run-id', 'abc-1'], [X: 'p'])

        then:
        String.valueOf(o.apiKey) == 'p'
        o.apiKeyHeader == 'X-Other-Key'
        o.allowWrites
        o.outDir == dir
        o.maxPages == 9
        o.timeoutSeconds == 3
        o.searchPath == '/x'
        o.archetypes == ['Card', 'Account']
        o.settleSeconds == 0
        o.removeLeftovers
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
        'no API key anywhere'        | ['--base-url', 'http://h']                                    | [:]                                 | '--api-key KEY'
        'no API key anywhere (env)'  | ['--base-url', 'http://h']                                    | [:]                                 | 'ACCELERATOR_PROBE_API_KEY'
        'an empty --api-key'         | ['--base-url', 'http://h', '--api-key', '']                   | [:]                                 | 'must not be empty'
        'a --api-key line break'     | ['--base-url', 'http://h', '--api-key', "a\r\nX: y"]          | [:]                                 | '--api-key contains control characters'
        'a --api-key with no value'  | ['--base-url', 'http://h', '--api-key']                       | [:]                                 | 'needs a value'
        'a key in another variable'  | ['--base-url', 'http://h', '--api-key-env', 'K']              | ENV                                 | 'K environment variable'
        'a key with a line break'    | ['--base-url', 'http://h']                                    | [ACCELERATOR_PROBE_API_KEY: "a\r\nX: y"] | 'control characters'
        'a bad header name'          | ['--base-url', 'http://h', '--api-key-header', 'X API KEY']   | ENV                                 | '--api-key-header'
        'a non-http URL'             | ['--base-url', 'ftp://h']                                     | ENV                                 | 'http or https'
        'an unknown flag'            | ['--base-url', 'http://h', '--insecure']                      | ENV                                 | 'Unknown argument'
        'the removed username flag'  | ['--base-url', 'http://h', '--username', 'u']                 | ENV                                 | 'Unknown argument'
        'a bad page count'           | ['--base-url', 'http://h', '--max-pages', '0']                | ENV                                 | 'positive'
        'an unsafe run id'           | ['--base-url', 'http://h', '--run-id', '../x']                | ENV                                 | '--run-id'
        'a flag without a value'     | ['--base-url']                                                | ENV                                 | 'needs a value'
        'the create path as search'  | ['--base-url', 'http://h', '--search-path', '/admin/entities'] | ENV                                | 'create endpoint'
        'a relative search path'     | ['--base-url', 'http://h', '--search-path', 'search']         | ENV                                 | 'must start with /'
        'one archetype only'         | ['--base-url', 'http://h', '--archetypes', 'Card']            | ENV                                 | '--archetypes'
        'a negative settle window'   | ['--base-url', 'http://h', '--settle-seconds', '-1']          | ENV                                 | 'from 0 to 600'
        'leftovers without writes'   | ['--base-url', 'http://h', '--remove-leftovers']              | ENV                                 | 'needs --allow-writes'
        'the removed timestamp flag' | ['--base-url', 'http://h', '--timestamp-field', 'x']          | ENV                                 | 'Unknown argument'
    }

    def "there is no option to disable TLS verification"() {
        expect:
        !ProbeMain.USAGE.toLowerCase().contains('insecure')
    }

    def "the API key never appears in the options' string form"() {
        expect:
        !ProbeMain.parse(['--base-url', 'http://h'], [ACCELERATOR_PROBE_API_KEY: 'hunter2']).toString().contains('hunter2')
        !ProbeMain.parse(['--base-url', 'http://h', '--api-key', 'hunter2'], [:]).toString().contains('hunter2')
    }

    def "end to end: a key given with --api-key authenticates, and appears in no output or console text"() {
        given:
        def service = new FakeAcceleratorService()
        service.seed([id: 'E1', baseType: 4, attributes: [:], directArchetypes: ['Customer']])
        def server = new FakeAcceleratorHttpServer(service, 'arg-key-123').start()
        def out = new ByteArrayOutputStream()
        def err = new ByteArrayOutputStream()

        when:
        int rc = ProbeMain.run(['--base-url', server.baseUrl, '--api-key', 'arg-key-123', '--out', dir.path, '--run-id', 'argkey'],
            [:], new PrintStream(out), new PrintStream(err))

        then:
        rc == 0
        new File(dir, 'report.md').text.contains('Valid key: 404')
        !(out.toString() + err.toString()).contains('arg-key-123')
        def files = []
        dir.eachFileRecurse { if (it.isFile()) files << it }
        files.findAll { it.text.contains('arg-key-123') }*.name == []

        cleanup:
        server?.close()
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
