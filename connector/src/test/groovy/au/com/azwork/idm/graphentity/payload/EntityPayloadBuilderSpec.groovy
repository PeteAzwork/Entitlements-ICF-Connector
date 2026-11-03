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

/** Design §8.3, §8.4 — the payload is a pure function of the delivered attribute set. */
class EntityPayloadBuilderSpec extends Specification {

    def "builds the Appendix A create document for Sanne"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes())

        then:
        doc.toWire() == Fixtures.sanneWire()
    }

    def "BaseType is injected from entityKind: subject→4, resource→2"() {
        expect:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes()).baseType == 4
        EntityPayloadBuilder.build('acct', Fixtures.accountType(), Fixtures.accountAttributes()).baseType == 2
    }

    def "attributes.ID mirrors the top-level ID and cannot be overridden by an attribute"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(ID: 'Other'))

        then:
        thrown(InvalidAttributeValueException)
    }

    def "only configured domain attributes are written into the attribute map"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(),
            Fixtures.sanneAttributes(mail: 's@example.com', telephoneNumber: '+61'))

        then:
        doc.attributes == [ID: 'Sanne', name: 'Sanne Groen', mail: 's@example.com', telephoneNumber: '+61']
    }

    def "an attribute outside the configured list is rejected rather than silently dropped"() {
        when:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(department: 'X'))

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('department')
    }

    def "a configured attribute that is absent from the set is omitted from the map (not written as null)"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes())

        then:
        !doc.attributes.containsKey('mail')
    }

    def "a configured attribute delivered with no values (removal) is written as null so the PUT clears it"() {
        given:
        def attrs = Fixtures.sanneAttributes()
        attrs << AttributeBuilder.build('mail')   // attribute present, no values

        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), attrs)

        then:
        doc.attributes.containsKey('mail')
        doc.attributes.mail == null
    }

    def "a multi-valued domain attribute is written as a list"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(mail: ['a@x', 'b@x']))

        then:
        doc.attributes.mail == ['a@x', 'b@x']
    }

    def "Relationships are parsed verbatim from entityRelationships"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(
            entityRelationships: ['{"TargetID":"A1","type":"Owns"}', '{"SourceID":"Mgr","type":"Manages"}']))

        then:
        doc.relationships == [Edge.outbound('A1', 'Owns'), Edge.inbound('Mgr', 'Manages')]
    }

    def "duplicate edge values collapse to one edge"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(
            entityRelationships: ['{"TargetID":"A1","type":"Owns"}', '{"TargetID":"A1","type":"Owns"}']))

        then:
        doc.relationships.size() == 1
    }

    def "a malformed edge value is an InvalidAttributeValueException naming entityRelationships"() {
        when:
        EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(entityRelationships: ['nope']))

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('entityRelationships')
    }

    def "an empty entityRelationships attribute yields an explicit empty Relationships array"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(entityRelationships: []))

        then:
        doc.relationships == []
        doc.toWire().Relationships == []
    }

    def "directArchetypes merges the static type archetypes with the delivered values, de-duplicated, order-stable"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(directArchetypes: ['VIP', 'Customer']))

        then:
        doc.directArchetypes == ['Customer', 'VIP']
    }

    def "static archetypes are stamped even when the attribute is absent"() {
        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), Fixtures.sanneAttributes(directArchetypes: null))

        then:
        doc.directArchetypes == ['Customer']
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

    def "the __NAME__ and __UID__ attributes are not written into the attribute map"() {
        given:
        def attrs = Fixtures.sanneAttributes()
        attrs << new Uid('Sanne')

        when:
        def doc = EntityPayloadBuilder.build('Sanne', Fixtures.userType(), attrs)

        then:
        !doc.attributes.containsKey('__NAME__')
        !doc.attributes.containsKey('__UID__')
    }

    def "a __NAME__ that disagrees with the id is rejected (A3: __UID__ == __NAME__ == ID)"() {
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
