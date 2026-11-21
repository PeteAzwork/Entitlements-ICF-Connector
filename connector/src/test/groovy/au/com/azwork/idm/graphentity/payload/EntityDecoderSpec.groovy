package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.model.EntityDocument
import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.objects.AttributeUtil
import spock.lang.Specification

/** Design §9.4 — decomposing the read model and search items into ICF attributes. */
class EntityDecoderSpec extends Specification {

    def "decodes the read model: uid == name == id, attributes, relationships in the P17 shape, lastEvaluated"() {
        when:
        def obj = EntityDecoder.decode(EntityDocument.fromEntity(Fixtures.sanneEntity()), Fixtures.userType(), Fixtures.USER)

        then:
        obj.objectClass == Fixtures.USER
        obj.uid.uidValue == 'Sanne'
        obj.name.nameValue == 'Sanne'
        AttributeUtil.getStringValue(obj.getAttributeByName('name')) == 'Sanne Groen'
        obj.getAttributeByName('entityRelationships').value == [Fixtures.OWNS_JSON]
        AttributeUtil.getStringValue(obj.getAttributeByName('lastEvaluated')) == Fixtures.EVALUATED
    }

    def "the read model carries no archetypes, so directArchetypes is not emitted"() {
        expect:
        EntityDecoder.decode(EntityDocument.fromEntity(Fixtures.sanneEntity()), Fixtures.userType(), Fixtures.USER).getAttributeByName('directArchetypes') == null
    }

    def "decodes a search item: attributes and directArchetypes, but no entityRelationships (D4, deferred to WS10)"() {
        when:
        def obj = EntityDecoder.decode(EntityDocument.fromSearchItem(Fixtures.sanneSearchItem()), Fixtures.userType(), Fixtures.USER)

        then:
        obj.uid.uidValue == 'Sanne'
        obj.getAttributeByName('directArchetypes').value == ['Customer']
        obj.getAttributeByName('entityRelationships') == null
    }

    def "a search item's isArchetypes surface as directArchetypes, because search reports direct assignments there (C9)"() {
        when:
        def obj = EntityDecoder.decode(EntityDocument.fromSearchItem(Fixtures.sanneSearchItem() + [isArchetypes: ['Customer', 'Retail']]), Fixtures.userType(), Fixtures.USER)

        then:
        obj.getAttributeByName('directArchetypes').value == ['Customer', 'Retail']
    }

    def "isArchetypes is not a separate ICF attribute"() {
        expect:
        EntityDecoder.decode(EntityDocument.fromSearchItem(Fixtures.sanneSearchItem() + [isArchetypes: ['Retail']]), Fixtures.userType(), Fixtures.USER)
            .attributes*.name.every { it != 'isArchetypes' }
    }

    def "remote attributes not in the configured list are ignored"() {
        expect:
        EntityDecoder.decode(EntityDocument.fromSearchItem(Fixtures.sanneSearchItem() + [attributes: [name: 'Sanne Groen', secretScore: '42']]),
            Fixtures.userType(), Fixtures.USER).getAttributeByName('secretScore') == null
    }

    def "an attribute stored as an empty string is treated as absent: the connector clears attributes that way (P20 amended)"() {
        when:
        def obj = EntityDecoder.decode(EntityDocument.fromSearchItem(Fixtures.sanneSearchItem() + [attributes: [name: 'Sanne Groen', mail: '']]), Fixtures.userType(), Fixtures.USER)

        then:
        obj.getAttributeByName('mail') == null
        obj.getAttributeByName('name').value == ['Sanne Groen']
    }

    def "the same holds for the read model's attribute objects"() {
        when:
        def entity = Fixtures.sanneEntity() + [Attributes: [name: [name: 'name', type: 'string', value: 'Sanne Groen'], mail: [name: 'mail', type: 'string', value: '']]]

        then:
        EntityDecoder.decode(EntityDocument.fromEntity(entity), Fixtures.userType(), Fixtures.USER).getAttributeByName('mail') == null
    }

    def "configured attributes absent remotely are not emitted (recon sees them as absent, not null)"() {
        expect:
        EntityDecoder.decode(EntityDocument.fromSearchItem(Fixtures.sanneSearchItem()), Fixtures.userType(), Fixtures.USER).getAttributeByName('mail') == null
    }

    def "a read-model entity with no relationships yields an empty entityRelationships attribute (present, no values)"() {
        when:
        def obj = EntityDecoder.decode(EntityDocument.fromEntity(Fixtures.sanneEntity() + [relationships: []]), Fixtures.userType(), Fixtures.USER)

        then:
        obj.getAttributeByName('entityRelationships') != null
        obj.getAttributeByName('entityRelationships').value == []
    }

    def "relationships are re-serialised in canonical sorted order"() {
        given:
        def entity = Fixtures.sanneEntity() + [relationships: [
            [source: 'Sanne', target: 'Z-Acct', type: 'Owns', sourceType: 'Subject'],
            [source: 'Mgr', target: 'Sanne', type: 'Manages', sourceType: 'Subject']]]

        expect:
        EntityDecoder.decode(EntityDocument.fromEntity(entity), Fixtures.userType(), Fixtures.USER).getAttributeByName('entityRelationships').value ==
            ['{"sourceID":"Mgr","targetID":"Sanne","type":"Manages"}', '{"sourceID":"Sanne","targetID":"Z-Acct","type":"Owns"}']
    }

    def "lastEvaluated is omitted when the service did not return it"() {
        expect:
        EntityDecoder.decode(new EntityDocument(id: 'x', baseType: 4), Fixtures.userType(), Fixtures.USER).getAttributeByName('lastEvaluated') == null
    }

    def "decode then build reproduces the write model when the read carried everything (recon comparison is symmetric)"() {
        given:
        def doc = Fixtures.sanneDoc()

        when:
        def obj = EntityDecoder.decode(doc, Fixtures.userType(), Fixtures.USER)
        def rebuilt = EntityPayloadBuilder.build(obj.uid.uidValue, Fixtures.userType(), obj.attributes)

        then:
        rebuilt.toWire() == doc.toWire()
    }
}
