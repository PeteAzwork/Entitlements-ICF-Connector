package au.com.azwork.idm.graphentity.model

import au.com.azwork.idm.graphentity.support.Fixtures
import spock.lang.Specification

/** Design §2.1 — entity document shape. */
class EntityDocumentSpec extends Specification {

    def "toWire produces the §2.1 document with exact key casing"() {
        when:
        def wire = Fixtures.sanneDoc().toWire()

        then:
        wire == Fixtures.sanneWire()
        wire.keySet() == ['ID', 'BaseType', 'attributes', 'Relationships', 'directArchetypes'] as Set
    }

    def "toWire omits lastUpdated (it is read-only on the service side)"() {
        given:
        def doc = Fixtures.sanneDoc()
        doc.lastUpdated = '2026-07-06T00:00:00Z'

        expect:
        !doc.toWire().containsKey('lastUpdated')
    }

    def "toWire always emits Relationships and directArchetypes, even when empty, so a PUT is explicit"() {
        given:
        def doc = new EntityDocument(id: 'x', baseType: 2, attributes: [ID: 'x'])

        when:
        def wire = doc.toWire()

        then:
        wire.Relationships == []
        wire.directArchetypes == []
    }

    def "fromWire parses a service response including relationships in both directions and lastUpdated"() {
        given:
        def wire = Fixtures.accountWire() + [lastUpdated: '2026-07-06T10:00:00Z']
        wire.Relationships = [[SourceID: 'Sanne', type: 'Owns'], [TargetID: 'Ledger-1', type: 'PostsTo']]

        when:
        def doc = EntityDocument.fromWire(wire)

        then:
        doc.id == 'Current-Account-1-Sanne'
        doc.baseType == 2
        doc.attributes == [ID: 'Current-Account-1-Sanne', name: 'Current Account', productCode: 'CA1']
        doc.relationships == [Edge.inbound('Sanne', 'Owns'), Edge.outbound('Ledger-1', 'PostsTo')]
        doc.directArchetypes == ['Account']
        doc.lastUpdated == '2026-07-06T10:00:00Z'
    }

    def "fromWire tolerates missing collections"() {
        when:
        def doc = EntityDocument.fromWire([ID: 'x', BaseType: 4])

        then:
        doc.attributes == [:]
        doc.relationships == []
        doc.directArchetypes == []
        doc.lastUpdated == null
    }

    def "fromWire requires an ID"() {
        when:
        EntityDocument.fromWire([BaseType: 4])

        then:
        thrown(IllegalArgumentException)
    }

    def "fromWire converts a numeric BaseType delivered as a string or long"() {
        expect:
        EntityDocument.fromWire([ID: 'x', BaseType: '4']).baseType == 4
        EntityDocument.fromWire([ID: 'x', BaseType: 2L]).baseType == 2
    }

    def "round trip toWire→fromWire is lossless for a write document"() {
        given:
        def doc = Fixtures.sanneDoc()

        expect:
        EntityDocument.fromWire(doc.toWire()) == doc
    }
}
