package au.com.azwork.idm.graphentity.model

import au.com.azwork.idm.graphentity.config.Direction
import spock.lang.Specification
import spock.lang.Unroll

/** Design §2.2 — edge representation, normative rules. */
class EdgeSpec extends Specification {

    def "an outbound edge carries TargetID and the type"() {
        expect:
        Edge.outbound('acct-1', 'Owns').toWire() == [TargetID: 'acct-1', type: 'Owns']
        Edge.outbound('acct-1', 'Owns').direction == Direction.OUTBOUND
        Edge.outbound('acct-1', 'Owns').otherId == 'acct-1'
    }

    def "an inbound edge carries SourceID and the type"() {
        expect:
        Edge.inbound('Sanne', 'Owns').toWire() == [SourceID: 'Sanne', type: 'Owns']
        Edge.inbound('Sanne', 'Owns').direction == Direction.INBOUND
        Edge.inbound('Sanne', 'Owns').otherId == 'Sanne'
    }

    def "the type string is identical from both sides; only the endpoint field differs"() {
        expect:
        Edge.outbound('a', 'Owns').toWire().type == Edge.inbound('a', 'Owns').toWire().type
        !Edge.outbound('a', 'Owns').toWire().containsKey('SourceID')
        !Edge.inbound('a', 'Owns').toWire().containsKey('TargetID')
    }

    @Unroll
    def "fromWire parses #wire"() {
        expect:
        Edge.fromWire(wire) == expected

        where:
        wire                                 | expected
        [TargetID: 'x', type: 'Owns']        | Edge.outbound('x', 'Owns')
        [SourceID: 'y', type: 'Owns']        | Edge.inbound('y', 'Owns')
        [type: 'Owns', TargetID: 'x']        | Edge.outbound('x', 'Owns')
    }

    @Unroll
    def "fromWire rejects #wire because #why"() {
        when:
        Edge.fromWire(wire)

        then:
        thrown(IllegalArgumentException)

        where:
        wire                                                | why
        [type: 'Owns']                                      | 'no endpoint'
        [TargetID: 'x', SourceID: 'y', type: 'Owns']        | 'both endpoints'
        [TargetID: 'x']                                     | 'no type'
        [TargetID: '', type: 'Owns']                        | 'blank endpoint'
        [TargetID: 'x', type: '']                           | 'blank type'
        null                                                | 'null'
        [targetId: 'x', type: 'Owns']                       | 'wrong key casing (TargetID is normative)'
    }

    def "flip re-expresses the edge from the other endpoint"() {
        expect:
        Edge.outbound('Current-Account-1-Sanne', 'Owns').flip('Sanne') == Edge.inbound('Sanne', 'Owns')
        Edge.inbound('Sanne', 'Owns').flip('Current-Account-1-Sanne') == Edge.outbound('Current-Account-1-Sanne', 'Owns')
    }

    def "flip twice returns the original"() {
        given:
        def e = Edge.outbound('b', 'Owns')

        expect:
        e.flip('a').flip('b') == e
    }

    def "equality is by value so edge sets can be compared in recon and verification"() {
        expect:
        Edge.outbound('a', 'T') == Edge.outbound('a', 'T')
        Edge.outbound('a', 'T') != Edge.inbound('a', 'T')
        Edge.outbound('a', 'T') != Edge.outbound('a', 'U')
        ([Edge.outbound('a', 'T'), Edge.outbound('a', 'T')] as Set).size() == 1
    }

    def "toWire never emits a null endpoint key"() {
        expect:
        !Edge.outbound('a', 'T').toWire().containsKey('SourceID')
        Edge.outbound('a', 'T').toWire().keySet() == ['TargetID', 'type'] as Set
    }
}
