package idm.entitlementsaccelerator.loadtest

import spock.lang.Specification
import java.nio.file.Files

class LoadReportSpec extends Specification {

    def "the report never carries the API key or the IDM password, even when an error message echoed one"() {
        given:
        def o = LoadTestOptions.parse(LoadTestOptionsSpec.BASE, LoadTestOptionsSpec.ENV)
        def r = new LoadReport(o)
        r.phases << [name: 'seed', result: 'FAIL', metrics: [:], notes: ['IDM said: bad header svc-key-123 / idm-pass-456']]
        File dir = Files.createTempDirectory('lt-report').toFile()

        when:
        r.write(dir)

        then:
        def md = new File(dir, 'report.md').text
        def json = new File(dir, 'report.json').text
        !md.contains('svc-key-123') && !md.contains('idm-pass-456')
        !json.contains('svc-key-123') && !json.contains('idm-pass-456')
        md.contains('bad header ***')
        md.startsWith("# Load test ${o.runId}: FAIL")
        json.contains('"verdict": "FAIL"')
    }

    def "a run passes only when every phase passed or was skipped, and nothing else went wrong"() {
        given:
        def r = new LoadReport(LoadTestOptions.parse(LoadTestOptionsSpec.BASE, LoadTestOptionsSpec.ENV))

        expect:
        r.passed()

        when:
        r.phases << [name: 'seed', result: 'PASS'] << [name: 'cleanup', result: 'SKIPPED']
        then:
        r.passed()

        when:
        r.problems << 'restoring failed'
        then:
        !r.passed()
    }
}
