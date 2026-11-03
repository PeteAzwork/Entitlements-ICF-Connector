package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.model.Edge
import au.com.azwork.idm.graphentity.model.EntityDocument
import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.objects.AttributeUtil
import spock.lang.Specification

/** Design §9.4 — decomposing a remote document back into ICF attributes. */
class EntityDecoderSpec extends Specification {

    def "decodes Sanne into a ConnectorObject with uid == name == ID"() {
        when:
        def obj = EntityDecoder.decode(EntityDocument.fromWire(Fixtures.sanneWire() + [lastUpdated: '2026-07-06T00:00:00Z']),
            Fixtures.userType(), Fixtures.USER)

        then:
        obj.objectClass == Fixtures.USER
        obj.uid.uidValue == 'Sanne'
        obj.name.nameValue == 'Sanne'
        AttributeUtil.getStringValue(obj.getAttributeByName('name')) == 'Sanne Groen'
        obj.getAttributeByName('directArchetypes').value == ['Customer']
        obj.getAttributeByName('entityRelationships').value == ['{"TargetID":"Current-Account-1-Sanne","type":"Owns"}']
        AttributeUtil.getStringValue(obj.getAttributeByName('lastUpdated')) == '2026-07-06T00:00:00Z'
    }

    def "the mirrored attributes.ID is not surfaced as a domain attribute"() {
        when:
        def obj = EntityDecoder.decode(EntityDocument.fromWire(Fixtures.sanneWire()), Fixtures.userType(), Fixtures.USER)

        then:
        obj.getAttributeByName('ID') == null
    }

    def "remote attributes not in the configured list are ignored"() {
        given:
        def wire = Fixtures.sanneWire()
        wire.attributes.secretScore = 42

        when:
        def obj = EntityDecoder.decode(EntityDocument.fromWire(wire), Fixtures.userType(), Fixtures.USER)

        then:
        obj.getAttributeByName('secretScore') == null
    }

    def "configured attributes absent remotely are not emitted (so recon sees them as absent, not null)"() {
        when:
        def obj = EntityDecoder.decode(EntityDocument.fromWire(Fixtures.sanneWire()), Fixtures.userType(), Fixtures.USER)

        then:
        obj.getAttributeByName('mail') == null
    }

    def "inbound and outbound edges are both re-serialised, in canonical order"() {
        given:
        def doc = new EntityDocument(id: 'acct', baseType: 2, relationships: [Edge.outbound('Ledger', 'PostsTo'), Edge.inbound('Sanne', 'Owns')])

        when:
        def obj = EntityDecoder.decode(doc, Fixtures.accountType(), Fixtures.ACCOUNT)

        then:
        obj.getAttributeByName('entityRelationships').value as Set ==
            ['{"SourceID":"Sanne","type":"Owns"}', '{"TargetID":"Ledger","type":"PostsTo"}'] as Set
    }

    def "an entity with no edges yields an empty entityRelationships attribute (present, no values)"() {
        when:
        def obj = EntityDecoder.decode(new EntityDocument(id: 'x', baseType: 4), Fixtures.userType(), Fixtures.USER)

        then:
        obj.getAttributeByName('entityRelationships') != null
        obj.getAttributeByName('entityRelationships').value == []
    }

    def "lastUpdated is omitted when the service did not return it"() {
        when:
        def obj = EntityDecoder.decode(new EntityDocument(id: 'x', baseType: 4), Fixtures.userType(), Fixtures.USER)

        then:
        obj.getAttributeByName('lastUpdated') == null
    }

    def "a multi-valued remote attribute decodes to a multi-valued ICF attribute"() {
        given:
        def wire = Fixtures.sanneWire()
        wire.attributes.mail = ['a@x', 'b@x']

        expect:
        EntityDecoder.decode(EntityDocument.fromWire(wire), Fixtures.userType(), Fixtures.USER).getAttributeByName('mail').value == ['a@x', 'b@x']
    }

    def "non-string scalar values are converted to strings (ICF schema declares string attributes)"() {
        given:
        def wire = Fixtures.accountWire()
        wire.attributes.productCode = 7

        expect:
        AttributeUtil.getStringValue(EntityDecoder.decode(EntityDocument.fromWire(wire), Fixtures.accountType(), Fixtures.ACCOUNT)
            .getAttributeByName('productCode')) == '7'
    }

    def "decode then build reproduces the same wire document (recon comparison is symmetric)"() {
        given:
        def original = EntityDocument.fromWire(Fixtures.sanneWire())

        when:
        def obj = EntityDecoder.decode(original, Fixtures.userType(), Fixtures.USER)
        def rebuilt = EntityPayloadBuilder.build(obj.uid.uidValue, Fixtures.userType(), obj.attributes)

        then:
        rebuilt.toWire() == original.toWire()
    }
}
