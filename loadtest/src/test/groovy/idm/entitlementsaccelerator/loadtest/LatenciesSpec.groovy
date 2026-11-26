package idm.entitlementsaccelerator.loadtest

import spock.lang.Specification

class LatenciesSpec extends Specification {

    def "nearest-rank percentiles, in milliseconds"() {
        given:
        def l = new Latencies('calls')
        (1..100).each { l.record(it * 1_000_000L) }   // 1..100 ms

        when:
        def s = l.summary()

        then:
        s.count == 100
        s.p50Ms == 50.0d
        s.p95Ms == 95.0d
        s.p99Ms == 99.0d
        s.maxMs == 100.0d
        s.meanMs == 50.5d
    }

    def "a failed call is counted by its HTTP status or exception class, not timed, and rethrown"() {
        given:
        def l = new Latencies('calls')

        when:
        l.time { throw new IdmRest.IdmException('POST', 'managed/user?_action=create', 409, 'conflict') }

        then:
        thrown(IdmRest.IdmException)
        l.count == 0
        l.errorCount == 1
        l.summary().errors == ['HTTP 409': 1]
    }
}
