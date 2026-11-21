package au.com.azwork.idm.graphentity.model

import au.com.azwork.idm.graphentity.support.Fixtures
import spock.lang.Specification

/** Service contract §5.2 — the three document models and the connector's canonical form. */
class EntityDocumentSpec extends Specification {

    // ---------------------------------------------------------------- write model

    def "toWire produces the write model (EntityDTO) with the service's key casing"() {
        when:
        def wire = Fixtures.sanneDoc().toWire()

        then:
        wire == Fixtures.sanneWire()
        wire.keySet() == ['id', 'baseType', 'attributes', 'relationships', 'directArchetypes'] as Set
    }

    def "toWire always emits relationships and directArchetypes, even when empty, because writes are full-state (P15)"() {
        when:
        def wire = new EntityDocument(id: 'x', baseType: 2, attributes: [:]).toWire()

        then:
        wire.relationships == []
        wire.directArchetypes == []
        wire.attributes == [:]
    }

    def "toWire never emits read-only fields"() {
        given:
        def doc = Fixtures.sanneDoc()
        doc.lastEvaluated = '2026-07-06T00:00:00Z'
        doc.isArchetypes = ['Policy']

        expect:
        !doc.toWire().containsKey('lastEvaluated')
        !doc.toWire().containsKey('isArchetypes')
    }

    def "fromWire round-trips the write model"() {
        expect:
        EntityDocument.fromWire(Fixtures.sanneDoc().toWire()) == Fixtures.sanneDoc()
    }

    // ---------------------------------------------------------------- read model

    def "fromEntity reads the read model: attribute objects, source/target relationships, lastEvaluated"() {
        when:
        def doc = EntityDocument.fromEntity(Fixtures.sanneEntity())

        then:
        doc.id == 'Sanne'
        doc.baseType == 4
        doc.attributes == [name: 'Sanne Groen']
        doc.relationships == [Fixtures.owns()]
        doc.lastEvaluated == Fixtures.EVALUATED
    }

    def "the read model carries no archetypes, so directArchetypes is unknown (null), not empty"() {
        expect:
        EntityDocument.fromEntity(Fixtures.sanneEntity()).directArchetypes == null
    }

    def "fromEntity ignores compoundAttributes: multi-valued attributes are not synchronised (P20)"() {
        given:
        def entity = Fixtures.sanneEntity() + [compoundAttributes: [phones: [[name: 'phones', type: 'string', value: '+61']]]]

        expect:
        EntityDocument.fromEntity(entity).attributes == [name: 'Sanne Groen']
    }

    def "fromEntity accepts a base type given as a name"() {
        expect:
        EntityDocument.fromEntity(Fixtures.sanneEntity() + [baseType: 'Subject']).baseType == 4
    }

    def "fromEntity requires an id"() {
        when:
        EntityDocument.fromEntity([baseType: 4])

        then:
        thrown(IllegalArgumentException)
    }

    // ---------------------------------------------------------------- search item

    def "fromSearchItem reads uid, a named base type, plain attributes and both archetype lists"() {
        when:
        def doc = EntityDocument.fromSearchItem(Fixtures.sanneSearchItem() + [isArchetypes: ['Customer', 'Retail']])

        then:
        doc.id == 'Sanne'
        doc.baseType == 4
        doc.attributes == [name: 'Sanne Groen']
        doc.directArchetypes == ['Customer']
        doc.isArchetypes == ['Customer', 'Retail']
        doc.lastEvaluated == Fixtures.EVALUATED
    }

    def "search items carry no relationships, so relationships is unknown (null), not empty (D4)"() {
        expect:
        EntityDocument.fromSearchItem(Fixtures.sanneSearchItem()).relationships == null
    }

    def "fromSearchItem converts non-string attribute values to strings (P20)"() {
        expect:
        EntityDocument.fromSearchItem(Fixtures.accountSearchItem() + [attributes: [limit: 5000, active: true]]).attributes ==
            [limit: '5000', active: 'true']
    }

    def "fromSearchItem falls back to name when uid is missing, and requires one of them"() {
        expect:
        EntityDocument.fromSearchItem([name: 'Sanne', baseType: 'Subject']).id == 'Sanne'

        when:
        EntityDocument.fromSearchItem([baseType: 'Subject'])

        then:
        thrown(IllegalArgumentException)
    }

    def "an unknown base type decodes to null rather than failing the read"() {
        expect:
        EntityDocument.fromSearchItem(Fixtures.sanneSearchItem() + [baseType: 'Action']).baseType == null
    }
}
