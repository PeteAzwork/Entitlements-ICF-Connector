package au.com.azwork.idm.graphentity.integration

import au.com.azwork.idm.graphentity.loadtest.LoadTest
import au.com.azwork.idm.graphentity.loadtest.LoadTestOptions
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification

/**
 * The load test (docs/load-testing.md) end to end in IDM 7.5, at a small size: the same tool people point at a test
 * environment, here pointed at this suite's IDM and at the in-memory service over HTTP, as if it were remote. It
 * proves the tool's phases, checks and cleanup against the real harness, so a load run elsewhere fails only for
 * reasons of that environment.
 */
class LoadTestInIdmSpec extends Specification {

    @Shared IdmStack stack = IdmStack.instance
    @Shared File out = new File(System.getProperty('repo.root', '..'), 'integration/build/loadtest-it')

    def "a small load test passes every phase, checks the service over HTTP, and leaves nothing behind"() {
        given:
        LoadTestOptions o = LoadTestOptions.parse(['--idm-url', 'http://localhost:8080/openidm', '--service-url', stack.server.baseUrl,
            '--allow-writes', '--idm-container', IdmStack.CONTAINER, '--users', '200', '--changes', '50', '--rate', '25',
            '--sample', '20', '--settle-seconds', '60', '--run-id', 'it', '--out', out.path],
            [LOADTEST_API_KEY: IdmStack.API_KEY, LOADTEST_IDM_PASSWORD: 'openidm-admin'])

        when:
        int code = new LoadTest(o).run()
        Map report = new JsonSlurper().parse(new File(out, 'report.json')) as Map

        then:
        code == 0
        report.verdict == 'PASS'
        (report.phases as List<Map>)*.name == ['preflight', 'model', 'seed', 'initial', 'steady', 'sync', 'cleanup']
        (report.phases as List<Map>).every { it.result == 'PASS' }
        (report.phases as List<Map>).find { it.name == 'initial' }.metrics['relationships checked'] == 20
        (report.phases as List<Map>).find { it.name == 'cleanup' }.metrics.leftInService == 0

        and: 'the service holds none of the run\'s entities, and the key is in neither report'
        !stack.service.entities.keySet().any { it.startsWith('lt-it-') }
        !new File(out, 'report.json').text.contains(IdmStack.API_KEY)
        !new File(out, 'report.md').text.contains(IdmStack.API_KEY)
    }
}
