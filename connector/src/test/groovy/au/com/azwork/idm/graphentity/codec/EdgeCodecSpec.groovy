package au.com.azwork.idm.entitlementsaccelerator.codec

import au.com.azwork.idm.entitlementsaccelerator.model.Edge
import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import spock.lang.Specification
import spock.lang.Unroll

/** Design §8.3, decision P17 — the entityRelationships ICF attribute encoding. */
class EdgeCodecSpec extends Specification {

    def "serialises to the compact canonical form: sourceID, targetID, type, no whitespace"() {
        expect:
        EdgeCodec.serialise(Fixtures.owns()) == Fixtures.OWNS_JSON
    }

    def "the same relationship serialises identically whichever end is being written"() {
        expect:
        EdgeCodec.serialise(Edge.of('Sanne', 'Current-Account-1-Sanne', 'Owns')) == Fixtures.OWNS_JSON
    }

    @Unroll
    def "parses '#json'"() {
        expect:
        EdgeCodec.parse(json) == expected

        where:
        json                                                             | expected
        '{"sourceID":"a","targetID":"b","type":"Owns"}'                  | Edge.of('a', 'b', 'Owns')
        ' { "type" : "Owns" , "targetID" : "b", "sourceID" : "a" } '     | Edge.of('a', 'b', 'Owns')
        '{"sourceID":"with \\"quote\\"","targetID":"b","type":"T"}'      | Edge.of('with "quote"', 'b', 'T')
        '{"sourceID":"ünïcödé","targetID":"b","type":"T"}'               | Edge.of('ünïcödé', 'b', 'T')
    }

    @Unroll
    def "parse rejects '#json' with InvalidAttributeValueException"() {
        when:
        EdgeCodec.parse(json)

        then:
        thrown(InvalidAttributeValueException)

        where:
        json << [null, '', 'Owns', '{"targetID":"b","type":"Owns"}', '{"sourceID":"a","type":"T"}',
                 '{"sourceID":"a","targetID":"b"}', '{"TargetID":"b","type":"T"}', '["a","b"]',
                 '{"sourceID":1,"targetID":"b","type":"T"}', '{"sourceID":"a","targetID":"b","type":"T"} trailing']
    }

    def "parseAll parses every value and collapses duplicates, keeping first-seen order"() {
        expect:
        EdgeCodec.parseAll(['{"sourceID":"a","targetID":"b","type":"T"}', '{"type":"T","targetID":"b","sourceID":"a"}',
                            '{"sourceID":"c","targetID":"a","type":"T"}']) == [Edge.of('a', 'b', 'T'), Edge.of('c', 'a', 'T')]
    }

    def "parseAll of null or empty is an empty list"() {
        expect:
        EdgeCodec.parseAll(null) == []
        EdgeCodec.parseAll([]) == []
    }

    def "parseAll reports the offending value in the exception"() {
        when:
        EdgeCodec.parseAll(['{"sourceID":"a","targetID":"b","type":"T"}', 'garbage'])

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('garbage')
    }

    def "serialiseAll is canonical and sorted, so set comparison in recon is stable regardless of order"() {
        given:
        def edges = [Edge.of('b', 'c', 'T'), Edge.of('a', 'b', 'T'), Edge.of('a', 'b', 'U')]

        expect:
        EdgeCodec.serialiseAll(edges) == EdgeCodec.serialiseAll(edges.reverse())
        EdgeCodec.serialiseAll(edges).size() == 3
    }

    def "serialise/parse round-trips"() {
        expect:
        EdgeCodec.parse(EdgeCodec.serialise(Fixtures.owns())) == Fixtures.owns()
    }
}
