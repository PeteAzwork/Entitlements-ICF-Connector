package au.com.azwork.idm.entitlementsaccelerator.ops

import au.com.azwork.idm.entitlementsaccelerator.config.ConnectorConfig
import au.com.azwork.idm.entitlementsaccelerator.http.EntityApi
import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import org.identityconnectors.common.logging.Log
import org.identityconnectors.framework.common.exceptions.AlreadyExistsException
import org.identityconnectors.framework.common.exceptions.ConnectorException
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.exceptions.ConnectorSecurityException
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.objects.ObjectClass

/** Design §9.1; decisions P15, P17 — create, and the silently-dropped-relationship problem. */
class CreateOperationSpec extends OperationSpecBase {

    def "POSTs Sanne's write model (the Appendix A create) and returns the Uid equal to the remote id"() {
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

    def "logs each dropped relationship at WARN, source -type-> target, when the other entity does not exist yet"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        log.messagesAt(Log.Level.WARN) == ['dropped edge Sanne -Owns-> Current-Account-1-Sanne']
    }

    def "logs nothing at WARN when every relationship was established"() {
        given:
        service.seed(Fixtures.accountWire())

        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        log.messagesAt(Log.Level.WARN).empty
        service.edges == [['Sanne', 'Current-Account-1-Sanne', 'Owns']] as Set
    }

    def "the create still succeeds when relationships were dropped: the model is self-healing"() {
        expect:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null).uidValue == 'Sanne'
    }

    def "creating the account afterwards heals the drop, because it carries the same relationship (Appendix A)"() {
        given:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        when:
        new CreateOperation(ctx()).execute(Fixtures.ACCOUNT, Fixtures.accountAttributes(), null)

        then:
        service.edges == [['Sanne', 'Current-Account-1-Sanne', 'Owns']] as Set
        service.get('Sanne').body.relationships == [[source: 'Sanne', target: 'Current-Account-1-Sanne', type: 'Owns', sourceType: 'Subject']]
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

    def "with byIdLookup: search there is no verification read, because search results carry no relationships"() {
        when:
        new CreateOperation(ctx(byIdLookup: ConnectorConfig.ByIdLookup.SEARCH)).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        service.requests*.op == ['POST']
    }

    def "a 409 → AlreadyExistsException so recon can link-and-update (assumed status; Q04.1)"() {
        given:
        service.seed(Fixtures.sanneWire())

        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        thrown(AlreadyExistsException)
    }

    def "a 400 → InvalidAttributeValueException and the problem details are logged at ERROR"() {
        given:
        service.failNextWith(400, [title: 'name is required', status: 400])

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
        def failing = new OperationContext(Fixtures.connectorConfig(), Mock(EntityApi) {
            create(_) >> { throw new IOException('connection refused') }
        }, log.asLog())

        when:
        new CreateOperation(failing).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        thrown(ConnectorIOException)
    }

    def "a failing verification GET (the 500s of C1) does not fail the create; it is logged at WARN (P11)"() {
        given:
        service.failNextWith(200)   // POST
        service.failNextWith(500)   // verification GET

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
        thrown(ConnectorException)
        service.requests.empty
    }

    def "a malformed relationship is rejected before any HTTP call"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(entityRelationships: ['bad']), null)

        then:
        thrown(InvalidAttributeValueException)
        service.requests.empty
    }

    def "a relationship that does not involve the entity is rejected before any HTTP call (P17)"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.USER,
            Fixtures.sanneAttributes(entityRelationships: ['{"sourceID":"X","targetID":"Y","type":"Owns"}']), null)

        then:
        thrown(InvalidAttributeValueException)
        service.requests.empty
    }

    def "the account create carries base type 2, its archetype and the same relationship verbatim"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.ACCOUNT, Fixtures.accountAttributes(), null)

        then:
        with(service.requestsOf('POST')[0].body) {
            baseType == 2
            relationships == [[sourceID: 'Sanne', targetID: 'Current-Account-1-Sanne', type: 'Owns']]
            directArchetypes == ['Account']
            attributes == [ID: 'Current-Account-1-Sanne', name: 'Current Account', productCode: 'CA1']   // attributes.ID by design (C8)
        }
    }

    def "attribute values never appear in log output above DEBUG (§13 PII rule)"() {
        when:
        new CreateOperation(ctx()).execute(Fixtures.USER, Fixtures.sanneAttributes(mail: 'sanne@example.com'), null)

        then:
        !log.entries.findAll { it.level != Log.Level.OK }.any { it.message.contains('sanne@example.com') }
    }
}
