package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.exceptions.UnknownUidException
import org.identityconnectors.framework.common.objects.Uid

/** Design §8.4, §9.2 — full-state idempotent PUT. */
class UpdateOperationSpec extends OperationSpecBase {

    def setup() {
        service.seed(Fixtures.sanneWire())
        service.seed(Fixtures.accountWire())
        service.seedEdge('Sanne', 'Current-Account-1-Sanne', 'Owns')
    }

    def "PUTs the total document addressed by the Uid and returns the same Uid"() {
        when:
        def uid = new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)

        then:
        uid.uidValue == 'Sanne'
        service.requests*.op == ['PUT']
        service.requestsOf('PUT')[0].id == 'Sanne'
        service.requestsOf('PUT')[0].body == Fixtures.sanneWire() + [attributes: [ID: 'Sanne', name: 'Sanne Smith']]
    }

    def "never reads the remote entity before writing (pure IDM-derived payload, §8.1)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(), null)

        then:
        service.requestsOf('GET').empty
    }

    def "a scalar-only change keeps the edge because the payload restates it (Appendix A)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)

        then:
        service.edges == [['Sanne', 'Current-Account-1-Sanne', 'Owns']] as Set
    }

    def "an update whose payload omits the edge deletes it (destructive PUT semantics, as designed)"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(entityRelationships: []), null)

        then:
        service.edges.empty
    }

    def "replaying the same update converges to the same remote state (idempotent)"() {
        given:
        def op = new UpdateOperation(ctx())

        when:
        op.execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(), null)
        def first = service.render('Sanne')
        op.execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(), null)

        then:
        service.render('Sanne').findAll { it.key != 'lastUpdated' } == first.findAll { it.key != 'lastUpdated' }
    }

    def "refuses an update that omits entityRelationships: it would silently mass-delete edges (§8.4, §15)"() {
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

    def "an empty (present, no values) entityRelationships is a legitimate 'no edges' update"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(entityRelationships: []), null)

        then:
        service.requestsOf('PUT')[0].body.Relationships == []
    }

    def "404 on PUT → UnknownUidException so sync/recon can re-create"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Ghost'), Fixtures.sanneAttributes(__NAME__: 'Ghost'), null)

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

    def "an update without __NAME__ is fine: the ID comes from the Uid"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(__NAME__: null), null)

        then:
        service.requestsOf('PUT')[0].body.ID == 'Sanne'
        service.requestsOf('PUT')[0].body.attributes.ID == 'Sanne'
    }

    def "the doubled write on relationship removal is harmless: both endpoints PUT without the edge"() {
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

    def "an update on the account side with an inbound edge is sent verbatim"() {
        when:
        new UpdateOperation(ctx()).execute(Fixtures.ACCOUNT, new Uid('Current-Account-1-Sanne'), Fixtures.accountAttributes(), null)

        then:
        service.requestsOf('PUT')[0].body.Relationships == [[SourceID: 'Sanne', type: 'Owns']]
        service.requestsOf('PUT')[0].body.BaseType == 2
    }
}
