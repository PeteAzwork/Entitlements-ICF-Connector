package au.com.azwork.idm.entitlementsaccelerator.ops

import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.exceptions.UnknownUidException
import org.identityconnectors.framework.common.objects.Uid

/** Design §8.4, §9.2; decisions P4, P15, P20, P22 — the full-state, idempotent update. */
class UpdateOperationSpec extends OperationSpecBase {

    def setup() {
        service.seed(Fixtures.sanneWire())
        service.seed(Fixtures.accountWire())
        service.seedEdge('Sanne', 'Current-Account-1-Sanne', 'Owns')
    }

    static Map withoutTime(Map entity) { entity.findAll { it.key != 'lastEvaluated' } }

    def "PUTs the total write model addressed by the Uid and returns the same Uid"() {
        when:
        def uid = new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)

        then:
        uid.uidValue == 'Sanne'
        service.requests*.op == ['PUT']
        service.requestsOf('PUT')[0].id == 'Sanne'
        // Every configured attribute, "" when empty (P20 amended), with attributes.ID (C8).
        service.requestsOf('PUT')[0].body == Fixtures.sanneWire() + [attributes: Fixtures.sanneWire().attributes + [name: 'Sanne Smith']]
    }

    def "never reads the remote entity before writing (pure IDM-derived payload, §8.1)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(), null)

        then:
        service.requestsOf('GET').empty
    }

    def "a scalar-only change keeps the relationship, because the payload restates it (Appendix A)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)

        then:
        service.edges == [['Sanne', 'Current-Account-1-Sanne', 'Owns']] as Set
    }

    def "an update whose payload omits the relationship deletes it: the service removes what is missing (P15)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(entityRelationships: []), null)

        then:
        service.edges.empty
    }

    def "an attribute removed in IDM is sent as an empty string, because the service keeps an attribute an update omits (P20 amended, C17)"() {
        given:
        service.seed(Fixtures.sanneWire() + [attributes: [name: 'Sanne Groen', mail: 'sanne@example.com']])

        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(), null)

        then:
        service.requestsOf('PUT')[0].body.attributes.mail == ''
        service.entities['Sanne'].attributes.mail == ''
    }

    def "directArchetypes are restated in full on every update (P22; assumption confirmed in WS0.15)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(directArchetypes: ['Customer', 'VIP']), null)

        then:
        service.requestsOf('PUT')[0].body.directArchetypes == ['Customer', 'VIP']
        service.entities['Sanne'].directArchetypes == ['Customer', 'VIP']
    }

    def "a Uid containing a slash is rejected before any request (P23)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('a/b'), Fixtures.sanneAttributes(__NAME__: 'a/b', entityRelationships: []), null)

        then:
        thrown(InvalidAttributeValueException)
        service.requests.empty
    }

    def "replaying the same update converges to the same remote state (idempotent)"() {
        given:
        def op = new UpdateOperation(ctx())

        when:
        op.execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(), null)
        def first = withoutTime(service.renderEntity('Sanne'))
        op.execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(), null)

        then:
        withoutTime(service.renderEntity('Sanne')) == first
    }

    def "refuses an update that omits entityRelationships: it would silently delete every relationship (P4, P15)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(entityRelationships: null), null)

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('entityRelationships')
        service.requests.empty
    }

    def "refuses an update that omits directArchetypes for the same reason"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(directArchetypes: null), null)

        then:
        def e = thrown(InvalidAttributeValueException)
        e.message.contains('directArchetypes')
        service.requests.empty
    }

    def "an empty (present, no values) entityRelationships is a legitimate 'no relationships' update"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(entityRelationships: []), null)

        then:
        service.requestsOf('PUT')[0].body.relationships == []
    }

    def "404 on PUT → UnknownUidException so sync/recon can re-create"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Ghost'), Fixtures.sanneAttributes(__NAME__: 'Ghost', entityRelationships: []), null)

        then:
        thrown(UnknownUidException)
    }

    def "the Uid, not __NAME__, addresses the PUT; a __NAME__ that disagrees is rejected (A3)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(__NAME__: 'Renamed'), null)

        then:
        thrown(InvalidAttributeValueException)
        service.requests.empty
    }

    def "an update without __NAME__ is fine: the id comes from the Uid"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(__NAME__: null), null)

        then:
        service.requestsOf('PUT')[0].body.id == 'Sanne'
    }

    def "the doubled write on relationship removal is harmless: both entities PUT without the relationship"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(entityRelationships: []), null)
        new UpdateOperation(ctx()).execute(Fixtures.ACCOUNT, new Uid('Current-Account-1-Sanne'), Fixtures.accountAttributes(entityRelationships: []), null)

        then:
        service.edges.empty
        service.entities.keySet() == ['Sanne', 'Current-Account-1-Sanne'] as Set
    }

    def "429 with Retry-After → ConnectorIOException carrying the hint"() {
        given:
        service.failNextWith(429, null, ['Retry-After': '15'])

        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(), null)

        then:
        def e = thrown(ConnectorIOException)
        e.message.contains('15')
    }

    def "an update on the account sends base type 2 and the same relationship verbatim"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.ACCOUNT, new Uid('Current-Account-1-Sanne'), Fixtures.accountAttributes(), null)

        then:
        service.requestsOf('PUT')[0].body.relationships == [[sourceID: 'Sanne', targetID: 'Current-Account-1-Sanne', type: 'Owns']]
        service.requestsOf('PUT')[0].body.baseType == 2
    }
}
