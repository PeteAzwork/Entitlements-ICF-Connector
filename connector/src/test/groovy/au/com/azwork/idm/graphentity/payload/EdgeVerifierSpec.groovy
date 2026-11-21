package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.model.Edge
import au.com.azwork.idm.graphentity.support.Fixtures
import spock.lang.Specification

/** Design §9.1 — post-create edge verification. */
class EdgeVerifierSpec extends Specification {

    def "nothing dropped when returned equals submitted"() {
        given:
        def edges = [Edge.of('a', 'b', 'Owns'), Edge.of('c', 'a', 'Manages')]

        expect:
        EdgeVerifier.dropped(edges, edges) == []
    }

    def "reports the Appendix A drop: one submitted, none returned"() {
        expect:
        EdgeVerifier.dropped([Fixtures.owns()], []) == [Fixtures.owns()]
    }

    def "comparison is as sets: order and duplicates do not matter"() {
        expect:
        EdgeVerifier.dropped([Edge.of('a', 'b', 'T'), Edge.of('a', 'c', 'T')],
            [Edge.of('a', 'c', 'T'), Edge.of('a', 'b', 'T'), Edge.of('a', 'b', 'T')]) == []
    }

    def "extra edges on the remote side are not reported as drops (they are a drift concern, WS10)"() {
        expect:
        EdgeVerifier.dropped([Edge.of('a', 'b', 'T')], [Edge.of('a', 'b', 'T'), Edge.of('z', 'a', 'Other')]) == []
    }

    def "an edge with the same ends but a different type counts as dropped"() {
        expect:
        EdgeVerifier.dropped([Edge.of('a', 'b', 'Owns')], [Edge.of('a', 'b', 'Holds')]) == [Edge.of('a', 'b', 'Owns')]
    }

    def "an edge with its ends swapped counts as dropped: direction matters"() {
        expect:
        EdgeVerifier.dropped([Edge.of('a', 'b', 'Owns')], [Edge.of('b', 'a', 'Owns')]) == [Edge.of('a', 'b', 'Owns')]
    }

    def "null collections are treated as empty"() {
        expect:
        EdgeVerifier.dropped(null, null) == []
        EdgeVerifier.dropped([Edge.of('a', 'b', 'T')], null) == [Edge.of('a', 'b', 'T')]
    }

    def "warn message reads source -type-> target, the Appendix A wording, whichever end is being written"() {
        expect:
        EdgeVerifier.warnMessage(Fixtures.owns()) == 'dropped edge Sanne -Owns-> Current-Account-1-Sanne'
    }
}
