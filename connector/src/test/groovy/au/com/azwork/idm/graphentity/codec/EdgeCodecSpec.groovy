package au.com.azwork.idm.graphentity.codec

import au.com.azwork.idm.graphentity.model.Edge
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import spock.lang.Specification
import spock.lang.Unroll

/** Design §8.3 — ICF attribute encoding of edges. */
class EdgeCodecSpec extends Specification {

    def "serialises an outbound edge to the compact canonical form from the design"() {
        expect:
        EdgeCodec.serialise(Edge.outbound('Current-Account-1-Sanne', 'Owns')) == '{"TargetID":"Current-Account-1-Sanne","type":"Owns"}'
    }

    def "serialises an inbound edge with SourceID first"() {
        expect:
        EdgeCodec.serialise(Edge.inbound('Sanne', 'Owns')) == '{"SourceID":"Sanne","type":"Owns"}'
    }

    @Unroll
    def "parses '#json'"() {
        expect:
        EdgeCodec.parse(json) == expected

        where:
        json                                              | expected
        '{"TargetID":"a","type":"Owns"}'                  | Edge.outbound('a', 'Owns')
        '{"SourceID":"b","type":"Owns"}'                  | Edge.inbound('b', 'Owns')
        ' { "type" : "Owns" , "TargetID" : "a" } '        | Edge.outbound('a', 'Owns')
        '{"TargetID":"with \\"quote\\"","type":"T"}'      | Edge.outbound('with "quote"', 'T')
        '{"TargetID":"ünïcödé","type":"T"}'               | Edge.outbound('ünïcödé', 'T')
    }

    @Unroll
    def "parse rejects '#json' with InvalidAttributeValueException"() {
        when:
        EdgeCodec.parse(json)

        then:
        thrown(InvalidAttributeValueException)

        where:
        json << [null, '', 'Owns', '{"type":"Owns"}', '{"TargetID":"a"}', '{"TargetID":"a","SourceID":"b","type":"T"}',
                 '["TargetID","a"]', '{"TargetID":1,"type":"T"}', '{"TargetID":"a","type":"T"} trailing']
    }

    def "parseAll parses every value and collapses duplicates"() {
        expect:
        EdgeCodec.parseAll(['{"TargetID":"a","type":"T"}', '{"type":"T","TargetID":"a"}', '{"SourceID":"b","type":"T"}']) ==
            [Edge.outbound('a', 'T'), Edge.inbound('b', 'T')]
    }

    def "parseAll of null or empty is an empty list"() {
        expect:
        EdgeCodec.parseAll(null) == []
        EdgeCodec.parseAll([]) == []
    }

    def "parseAll reports the offending value in the exception"() {
        when:
        EdgeCodec.parseAll(['{"TargetID":"a","type":"T"}', 'garbage'])

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('garbage')
    }

    def "serialiseAll is canonical: sorted, so set comparison in recon is stable regardless of input order"() {
        given:
        def edges = [Edge.outbound('b', 'T'), Edge.inbound('a', 'T'), Edge.outbound('a', 'T')]

        expect:
        EdgeCodec.serialiseAll(edges) == EdgeCodec.serialiseAll(edges.reverse())
        EdgeCodec.serialiseAll(edges).size() == 3
    }

    def "serialise/parse round-trips"() {
        given:
        def edge = Edge.outbound('Current-Account-1-Sanne', 'Owns')

        expect:
        EdgeCodec.parse(EdgeCodec.serialise(edge)) == edge
    }

    def "serialise contains no whitespace so values are compact in logs and recon diffs"() {
        expect:
        !EdgeCodec.serialise(Edge.outbound('a', 'T')).contains(' ')
    }
}
