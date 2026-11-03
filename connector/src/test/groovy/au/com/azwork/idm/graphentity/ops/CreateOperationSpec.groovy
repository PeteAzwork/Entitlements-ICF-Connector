package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.common.logging.Log
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.exceptions.ConnectorSecurityException
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.objects.ObjectClass

/** Design §9.1 — create and the silently-dropped-edge problem. */
class CreateOperationSpec extends OperationSpecBase {

    def "POSTs the total Appendix A document and returns the Uid equal to the remote ID"() {
        when:
        def uid = new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        uid.uidValue == 'Sanne'
        service.requestsOf('POST').size() == 1
        service.requestsOf('POST')[0].body == Fixtures.sanneWire()
    }

    def "performs exactly one verification GET after the POST"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        service.requests*.op == ['POST', 'GET']
        service.requestsOf('GET')[0].id == 'Sanne'
    }

    def "logs each dropped edge at WARN with both endpoint IDs and the type when the other endpoint does not exist"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        log.messagesAt(Log.Level.WARN) == ['dropped edge Sanne -Owns-> Current-Account-1-Sanne']
    }

    def "logs nothing at WARN when every edge was established"() {
        given:
        service.seed(Fixtures.accountWire())

        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        log.messagesAt(Log.Level.WARN).empty
        service.edges == [['Sanne', 'Current-Account-1-Sanne', 'Owns']] as Set
    }

    def "the create still succeeds (returns the Uid) when edges were dropped: the model is self-healing"() {
        expect:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null).uidValue == 'Sanne'
    }

    def "an inbound edge asserted from the account side heals the earlier drop (Appendix A)"() {
        given:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        when:
        new CreateOperation(ctx()).execute(Fixtures.ACCOUNT, Fixtures.accountAttributes(), null)

        then:
        service.edges == [['Sanne', 'Current-Account-1-Sanne', 'Owns']] as Set
        service.get('Sanne').body.Relationships == [[TargetID: 'Current-Account-1-Sanne', type: 'Owns']]
        service.get('Current-Account-1-Sanne').body.Relationships == [[SourceID: 'Sanne', type: 'Owns']]
    }

    def "aggressive mode re-PUTs the entity when verification found drops (off by default)"() {
        when:
        new CreateOperation(ctx(rePutOnDroppedEdges: true)).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        service.requests*.op == ['POST', 'GET', 'PUT']
    }

    def "default mode never PUTs during create"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        service.requestsOf('PUT').empty
    }

    def "a 409 from the service → AlreadyExistsException so recon can link-and-update"() {
        given:
        service.seed(Fixtures.sanneWire())

        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        thrown(AlreadyExistsException)
    }

    def "a 400 → InvalidAttributeValueException and the body is logged at ERROR"() {
        given:
        service.failNextWith(400, [error: 'name is required'])

        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        thrown(InvalidAttributeValueException)
        log.messagesAt(Log.Level.ERROR).any { it.contains('name is required') }
    }

    def "a 403 → ConnectorSecurityException"() {
        given:
        service.failNextWith(403)

        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        thrown(ConnectorSecurityException)
    }

    def "a 503 → ConnectorIOException (retryable)"() {
        given:
        service.failNextWith(503)

        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        thrown(ConnectorIOException)
    }

    def "a transport IOException → ConnectorIOException"() {
        given:
        def failing = new OperationContext(Fixtures.connectorConfig(), Mock(au.com.azwork.idm.graphentity.http.EntityApi) {
            create(_) >> { throw new IOException('connection refused') }
        }, log.asLog())

        when:
        new CreateOperation(failing).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        thrown(ConnectorIOException)
    }

    def "a failing verification GET does not fail the create; it is logged at WARN"() {
        given:
        service.failNextWith(201, Fixtures.sanneWire())   // POST ok
        service.failNextWith(500)                         // GET fails

        when:
        def uid = new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        uid.uidValue == 'Sanne'
        log.messagesAt(Log.Level.WARN).any { it.toLowerCase().contains('verif') }
    }

    def "an unknown object class is rejected before any HTTP call"() {
        when:
        new CreateOperation(ctx()).execute(new ObjectClass('group'), Fixtures.sanneAttributes(), null)

        then:
        thrown(org.identityconnectors.framework.common.exceptions.ConnectorException)
        service.requests.empty
    }

    def "a malformed edge is rejected before any HTTP call"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(entityRelationships: ['bad']), null)

        then:
        thrown(InvalidAttributeValueException)
        service.requests.empty
    }

    def "an account create carries BaseType 2 and its inbound edge verbatim"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.ACCOUNT, Fixtures.accountAttributes(), null)

        then:
        with(service.requestsOf('POST')[0].body) {
            BaseType == 2
            Relationships == [[SourceID: 'Sanne', type: 'Owns']]
            directArchetypes == ['Account']
            attributes.ID == 'Current-Account-1-Sanne'
        }
    }

    def "attribute values never appear in log output above DEBUG (§13 PII rule)"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(mail: 'sanne@example.com'), null)

        then:
        !log.entries.findAll { it.level != Log.Level.OK }.any { it.message.contains('sanne@example.com') }
    }
}
