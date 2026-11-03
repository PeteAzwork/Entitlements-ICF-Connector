package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.model.Edge
import spock.lang.Specification

/** Design §9.1 — post-create edge verification. */
class EdgeVerifierSpec extends Specification {

    def "nothing dropped when returned equals submitted"() {
        given:
        def edges = [Edge.outbound('a', 'Owns'), Edge.inbound('b', 'Manages')]

        expect:
        EdgeVerifier.dropped(edges, edges) == []
    }

    def "reports the Appendix A drop: one submitted, zero returned"() {
        expect:
        EdgeVerifier.dropped([Edge.outbound('Current-Account-1-Sanne', 'Owns')], []) == [Edge.outbound('Current-Account-1-Sanne', 'Owns')]
    }

    def "comparison is as sets: order and duplicates do not matter"() {
        expect:
        EdgeVerifier.dropped([Edge.outbound('a', 'T'), Edge.outbound('b', 'T')],
            [Edge.outbound('b', 'T'), Edge.outbound('a', 'T'), Edge.outbound('a', 'T')]) == []
    }

    def "extra edges on the remote side are not reported as drops (they are a drift concern, not a create failure)"() {
        expect:
        EdgeVerifier.dropped([Edge.outbound('a', 'T')], [Edge.outbound('a', 'T'), Edge.inbound('z', 'Other')]) == []
    }

    def "an edge with the same endpoint but a different type counts as dropped"() {
        expect:
        EdgeVerifier.dropped([Edge.outbound('a', 'Owns')], [Edge.outbound('a', 'Holds')]) == [Edge.outbound('a', 'Owns')]
    }

    def "null collections are treated as empty"() {
        expect:
        EdgeVerifier.dropped(null, null) == []
        EdgeVerifier.dropped([Edge.outbound('a', 'T')], null) == [Edge.outbound('a', 'T')]
    }

    def "warn message for an outbound edge matches the Appendix A wording"() {
        expect:
        EdgeVerifier.warnMessage('Sanne', Edge.outbound('Current-Account-1-Sanne', 'Owns')) ==
            'dropped edge Sanne -Owns-> Current-Account-1-Sanne'
    }

    def "warn message for an inbound edge shows the arrow pointing at this entity"() {
        expect:
        EdgeVerifier.warnMessage('Current-Account-1-Sanne', Edge.inbound('Sanne', 'Owns')) ==
            'dropped edge Current-Account-1-Sanne <-Owns- Sanne'
    }
}
