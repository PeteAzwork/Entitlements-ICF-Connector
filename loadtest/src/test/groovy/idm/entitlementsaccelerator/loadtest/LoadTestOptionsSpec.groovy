package idm.entitlementsaccelerator.loadtest

import spock.lang.Specification

/** The load test's guards (docs/load-testing.md §2): no writes without consent, no unlisted hosts, no secrets in arguments. */
class LoadTestOptionsSpec extends Specification {
    static final Map<String, String> ENV = [LOADTEST_API_KEY: 'svc-key-123', LOADTEST_IDM_PASSWORD: 'idm-pass-456']
    static final List<String> BASE = ['--idm-url', 'http://localhost:8080/openidm', '--service-url', 'http://127.0.0.1:5276', '--allow-writes']

    def "the minimum: two URLs on localhost, --allow-writes, and the secrets from the environment"() {
        when:
        def o = LoadTestOptions.parse(BASE, ENV)

        then:
        o.idmServiceUrl == 'http://127.0.0.1:5276'
        o.users == 1000 && o.changes == 200 && o.threads == 8
        o.phases == LoadTestOptions.PHASES
        o.prefix ==~ /lt-\d{8}-\d{6}-/
        new String(o.apiKey) == 'svc-key-123'
        o.out.path == "build/loadtest/${o.runId}"
    }

    def "every run writes, so --allow-writes is required"() {
        when:
        LoadTestOptions.parse(BASE - '--allow-writes', ENV)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('--allow-writes is required')
    }

    def "secrets are refused as options, so they stay out of shell history and the process list"() {
        when:
        LoadTestOptions.parse(BASE + [option, 'x'], ENV)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('refused')

        where:
        option << ['--api-key', '--idm-password', '--password', '--key']
    }

    def "the secrets must be in the environment"() {
        when:
        LoadTestOptions.parse(BASE, env)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(missing)

        where:
        env                                   | missing
        [LOADTEST_IDM_PASSWORD: 'p']          | 'LOADTEST_API_KEY'
        [LOADTEST_API_KEY: 'k']               | 'LOADTEST_IDM_PASSWORD'
    }

    def "a host that is not localhost must be allowed explicitly, including the address IDM uses for the service"() {
        when:
        LoadTestOptions.parse(args, ENV + env)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains("host '${host}' is not allowed")

        where:
        args                                                                                 | env | host
        ['--idm-url', 'https://idm.test.example/openidm', '--service-url', 'http://localhost:1', '--allow-writes'] | [:] | 'idm.test.example'
        BASE + ['--idm-service-url', 'http://accelerator-service:8080']                            | [:] | 'accelerator-service'
        BASE + ['--idm-service-url', 'http://accelerator-service:8080']                            | [LOADTEST_ALLOWED_HOSTS: 'other'] | 'accelerator-service'
    }

    def "allowed hosts come from --allowed-hosts or LOADTEST_ALLOWED_HOSTS"() {
        expect:
        LoadTestOptions.parse(BASE + ['--idm-service-url', 'http://accelerator-service:8080', '--allowed-hosts', 'accelerator-service'], ENV).idmServiceUrl == 'http://accelerator-service:8080'
        LoadTestOptions.parse(BASE + ['--idm-service-url', 'http://accelerator-service:8080'], ENV + [LOADTEST_ALLOWED_HOSTS: 'Accelerator-Service, x']).idmServiceUrl == 'http://accelerator-service:8080'
    }

    def "a URL with credentials in it is refused"() {
        when:
        LoadTestOptions.parse(['--idm-url', 'http://admin:pw@localhost:8080/openidm', '--service-url', 'http://localhost:1', '--allow-writes'], ENV)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains('must not contain credentials')
        !e.message.contains('pw@')
    }

    def "sizes, phases and the run ID are checked"() {
        when:
        LoadTestOptions.parse(BASE + extra, ENV)

        then:
        def e = thrown(IllegalArgumentException)
        e.message.contains(expected)

        where:
        extra                                   | expected
        ['--users', '10', '--changes', '11']    | 'cannot exceed --users'
        ['--users', '0']                        | 'whole number'
        ['--phases', 'seed,boom']               | 'boom'
        ['--run-id', 'Has Spaces']              | '--run-id'
        ['--rate', '-1']                        | 'positive number'
        ['--library', 'zip']                    | 'sources or jar'
        ['--nope', '1']                         | 'unknown option'
    }

    def "the report's view of the settings carries no secret"() {
        when:
        def text = LoadTestOptions.parse(BASE, ENV).describe().toString()

        then:
        !text.contains('svc-key-123')
        !text.contains('idm-pass-456')
    }
}
