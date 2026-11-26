package idm.entitlementsaccelerator.model

import spock.lang.Specification
import spock.lang.Unroll

/** Decision P17 — one relationship with both ends named explicitly, in the service's write shape. */
class EdgeSpec extends Specification {

    def "the write shape is {sourceID, targetID, type}, in that key order"() {
        when:
        def wire = Edge.of('Sanne', 'Acct-1', 'Owns').toWire()

        then:
        wire == [sourceID: 'Sanne', targetID: 'Acct-1', type: 'Owns']
        wire.keySet() as List == ['sourceID', 'targetID', 'type']
    }

    def "fromWire parses the write shape"() {
        expect:
        Edge.fromWire([sourceID: 'Sanne', targetID: 'Acct-1', type: 'Owns']) == Edge.of('Sanne', 'Acct-1', 'Owns')
        Edge.fromWire([type: 'Owns', targetID: 'Acct-1', sourceID: 'Sanne']) == Edge.of('Sanne', 'Acct-1', 'Owns')
    }

    @Unroll
    def "fromWire rejects #wire because #why"() {
        when:
        Edge.fromWire(wire)

        then:
        thrown(IllegalArgumentException)

        where:
        wire                                                        | why
        null                                                        | 'null'
        [targetID: 'b', type: 'T']                                  | 'no source'
        [sourceID: 'a', type: 'T']                                  | 'no target'
        [sourceID: 'a', targetID: 'b']                              | 'no type'
        [sourceID: '', targetID: 'b', type: 'T']                    | 'blank source'
        [sourceID: 'a', targetID: ' ', type: 'T']                   | 'blank target'
        [sourceID: 'a', targetID: 'b', type: '']                    | 'blank type'
        [sourceID: 1, targetID: 'b', type: 'T']                     | 'non-string source'
        [TargetID: 'b', type: 'T']                                  | "the design's old one-sided form"
        [sourceID: 'a', targetID: 'b', type: 'T', extra: 'x']       | 'an unknown key'
    }

    def "fromRead converts the read model's {source, target, type, sourceType}; sourceType is dropped"() {
        expect:
        Edge.fromRead([source: 'Sanne', target: 'Acct-1', type: 'Owns', sourceType: 'Subject']) == Edge.of('Sanne', 'Acct-1', 'Owns')
    }

    def "fromRead rejects a read entry without both ends and a type"() {
        when:
        Edge.fromRead([source: 'Sanne', type: 'Owns'])

        then:
        thrown(IllegalArgumentException)
    }

    def "involves and otherEnd work from either end"() {
        given:
        def e = Edge.of('Sanne', 'Acct-1', 'Owns')

        expect:
        e.involves('Sanne')
        e.involves('Acct-1')
        !e.involves('Someone')
        e.otherEnd('Sanne') == 'Acct-1'
        e.otherEnd('Acct-1') == 'Sanne'
    }

    def "a self-relationship's other end is the entity itself"() {
        expect:
        Edge.of('A', 'A', 'Manages').otherEnd('A') == 'A'
    }

    def "equality is by value, so edge sets compare correctly in recon and verification"() {
        expect:
        Edge.of('a', 'b', 'T') == Edge.of('a', 'b', 'T')
        Edge.of('a', 'b', 'T') != Edge.of('b', 'a', 'T')
        Edge.of('a', 'b', 'T') != Edge.of('a', 'b', 'U')
        ([Edge.of('a', 'b', 'T'), Edge.of('a', 'b', 'T')] as Set).size() == 1
    }
}
