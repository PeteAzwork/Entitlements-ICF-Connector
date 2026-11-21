package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.model.Edge
import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.objects.Attribute
import org.identityconnectors.framework.common.objects.AttributeBuilder
import org.identityconnectors.framework.common.objects.Name
import org.identityconnectors.framework.common.objects.Uid
import spock.lang.Specification
import spock.lang.Unroll

/** Design §8.3, §8.4; decisions P15, P17–P20 — the write model is a pure function of the delivered attributes. */
class EntityPayloadBuilderSpec extends Specification {

    def "builds Sanne's write model, the Appendix A create body"() {
        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes()).toWire() == Fixtures.sanneWire()
    }

    def "builds the account's write model, carrying the same relationship string"() {
        expect:
        EntityPayloadBuilder.build('Current-Account-1-Sanne', Fixtures.accountType(), Fixtures.accountAttributes()).toWire() == Fixtures.accountWire()
    }

    def "baseType comes from entityKind with P18's numbering: subject → 4, resource → 2"() {
        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes()).baseType == 4
        EntityPayloadBuilder.build('Current-Account-1-Sanne', Fixtures.accountType(), Fixtures.accountAttributes()).baseType == 2
    }

    def "the document's attributes hold domain attributes only; attributes.ID is added by toWire (C8)"() {
        expect:
        !EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes()).attributes.containsKey('ID')
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes()).toWire().attributes.ID == 'Sanne'
    }

    def "an attribute named ID is rejected"() {
        when:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(ID: 'Other'))

        then:
        thrown(InvalidAttributeValueException)
    }

    def "every configured attribute with a value is written"() {
        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(mail: 's@example.com', telephoneNumber: '+61')).attributes ==
            [name: 'Sanne Groen', mail: 's@example.com', telephoneNumber: '+61']
    }

    def "numbers and booleans are written as their text, because the service's attributes are strings (P20)"() {
        given:
        def attrs = Fixtures.sanneAttributes()
        attrs << AttributeBuilder.build('mail', 42)
        attrs << AttributeBuilder.build('telephoneNumber', true)

        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), attrs).attributes.subMap(['mail', 'telephoneNumber']) == [mail: '42', telephoneNumber: 'true']
    }

    def "a multi-valued domain attribute is rejected (P20)"() {
        when:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(mail: ['a@x', 'b@x']))

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('mail')
    }

    def "an attribute that is absent, or present with no value, is omitted (P20)"() {
        given:
        def attrs = Fixtures.sanneAttributes()
        attrs << AttributeBuilder.build('mail')

        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), attrs)

        then:
        !doc.attributes.containsKey('mail')
        !doc.attributes.containsKey('telephoneNumber')
    }

    def "an attribute outside the configured list is rejected rather than silently dropped"() {
        when:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(department: 'X'))

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('department')
    }

    def "relationships are parsed verbatim from entityRelationships (P17)"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(entityRelationships: [
            '{"sourceID":"Sanne","targetID":"A1","type":"Owns"}', '{"sourceID":"Mgr","targetID":"Sanne","type":"Manages"}']))

        then:
        doc.relationships == [Edge.of('Sanne', 'A1', 'Owns'), Edge.of('Mgr', 'Sanne', 'Manages')]
    }

    def "a relationship that does not name the entity as source or target is rejected (P17)"() {
        when:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(entityRelationships: [
            '{"sourceID":"Someone","targetID":"A1","type":"Owns"}']))

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('entityRelationships')
        e.message.contains('Sanne')
    }

    def "duplicate relationship values collapse to one"() {
        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(entityRelationships: [Fixtures.OWNS_JSON, Fixtures.OWNS_JSON])).relationships.size() == 1
    }

    def "a malformed relationship value is an InvalidAttributeValueException naming entityRelationships"() {
        when:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(entityRelationships: ['nope']))

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('entityRelationships')
    }

    def "an empty entityRelationships yields an explicit empty relationships array (P15)"() {
        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(entityRelationships: [])).toWire().relationships == []
    }

    def "directArchetypes puts the type's static archetypes first (the scope archetype, P19), then delivered ones, without duplicates"() {
        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(directArchetypes: ['VIP', 'Customer'])).directArchetypes == ['Customer', 'VIP']
    }

    def "the scope archetype is stamped even when directArchetypes is absent"() {
        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(directArchetypes: null)).directArchetypes == ['Customer']
    }

    @Unroll
    def "a blank or null id ('#id') is rejected"() {
        when:
        EntityPayloadBuilder.build(id, Fixtures.userType(), Fixtures.sanneAttributes())

        then:
        thrown(InvalidAttributeValueException)

        where:
        id << [null, '', '  ']
    }

    def "__NAME__, __UID__ and the read-only lastEvaluated are never written as attributes"() {
        given:
        def attrs = Fixtures.sanneAttributes()
        attrs << new Uid('Sanne')
        attrs << AttributeBuilder.build('lastEvaluated', '2026-01-01T00:00:00Z')

        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), attrs)

        then:
        doc.attributes.keySet() == ['name'] as Set
    }

    def "a __NAME__ that disagrees with the id is rejected (A3: __UID__ == __NAME__ == id)"() {
        when:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(__NAME__: 'Someone-Else'))

        then:
        thrown(InvalidAttributeValueException)
    }

    def "the builder never consults the remote service (pure function: no API dependency in its signature)"() {
        expect:
        EntityPayloadBuilder.methods.find { it.name == 'build' }.parameterTypes*.simpleName == ['String', 'ObjectTypeConfig', 'Set']
    }

    def "idForCreate prefers __NAME__ and falls back to __UID__"() {
        expect:
        EntityPayloadBuilder.idForCreate([new Name('N'), new Uid('U')] as Set<Attribute>) == 'N'
        EntityPayloadBuilder.idForCreate([new Uid('U')] as Set<Attribute>) == 'U'
    }

    def "idForCreate with neither is an InvalidAttributeValueException"() {
        when:
        EntityPayloadBuilder.idForCreate([AttributeBuilder.build('name', 'x')] as Set<Attribute>)

        then:
        thrown(InvalidAttributeValueException)
    }

    def "the same inputs always produce an equal document (idempotent assembly, §8.4)"() {
        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes()) ==
            EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes())
    }
}
