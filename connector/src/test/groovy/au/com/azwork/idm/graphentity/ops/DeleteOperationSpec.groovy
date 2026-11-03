package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.exceptions.ConnectorSecurityException
import org.identityconnectors.framework.common.exceptions.UnknownUidException
import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.Uid

/** Design §9.3 — delete. */
class DeleteOperationSpec extends OperationSpecBase {

    def setup() {
        service.seed(Fixtures.sanneWire())
        service.seed(Fixtures.accountWire())
        service.seedEdge('Sanne', 'Current-Account-1-Sanne', 'Owns')
    }

    def "DELETEs the entity by Uid; the service removes the entity and every edge touching it"() {
        when:
        new DeleteOperation(ctx()).execute(Fixtures.ACCOUNT, new Uid('Current-Account-1-Sanne'), null)

        then:
        service.requests*.op == ['DELETE']
        !service.entities.containsKey('Current-Account-1-Sanne')
        service.edges.empty
        service.entities.containsKey('Sanne')
    }

    def "404 is success when deleteMissingTolerated (the default)"() {
        when:
        new DeleteOperation(ctx()).execute(Fixtures.USER, new Uid('Ghost'), null)

        then:
        noExceptionThrown()
    }

    def "404 → UnknownUidException when deleteMissingTolerated is false"() {
        when:
        new DeleteOperation(ctx(deleteMissingTolerated: false)).execute(Fixtures.USER, new Uid('Ghost'), null)

        then:
        thrown(UnknownUidException)
    }

    def "a tolerated 404 is logged at INFO so operators can see already-deleted cases"() {
        when:
        new DeleteOperation(ctx()).execute(Fixtures.USER, new Uid('Ghost'), null)

        then:
        log.messagesAt(org.identityconnectors.common.logging.Log.Level.INFO).any { it.contains('Ghost') }
    }

    def "403 → ConnectorSecurityException"() {
        given:
        service.failNextWith(403)

        when:
        new DeleteOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), null)

        then:
        thrown(ConnectorSecurityException)
    }

    def "500 → ConnectorIOException"() {
        given:
        service.failNextWith(500)

        when:
        new DeleteOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), null)

        then:
        thrown(ConnectorIOException)
    }

    def "an unknown object class is rejected before any HTTP call"() {
        when:
        new DeleteOperation(ctx()).execute(new ObjectClass('group'), new Uid('Sanne'), null)

        then:
        thrown(org.identityconnectors.framework.common.exceptions.ConnectorException)
        service.requests.empty
    }

    def "delete does not GET first"() {
        when:
        new DeleteOperation(ctx()).execute(Fixtures.USER, new Uid('Sanne'), null)

        then:
        service.requestsOf('GET').empty
    }
}
