package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException

/** Design §9.5 — Test and Schema. */
class TestAndSchemaOperationSpec extends OperationSpecBase {

    def "test issues an authenticated search with page size 1 and treats 2xx as healthy"() {
        when:
        new TestOperation(ctx()).execute()

        then:
        noExceptionThrown()
        service.requests*.op == ['SEARCH']
        service.requestsOf('SEARCH')[0].query.pageSize == 1
    }

    def "test on an empty service is still healthy"() {
        expect:
        new TestOperation(ctx()).execute() == null
        service.entities.empty
    }

    def "401 → InvalidCredentialException"() {
        given:
        service.failNextWith(401)

        when:
        new TestOperation(ctx()).execute()

        then:
        thrown(InvalidCredentialException)
    }

    def "403 → InvalidCredentialException"() {
        given:
        service.failNextWith(403)

        when:
        new TestOperation(ctx()).execute()

        then:
        thrown(InvalidCredentialException)
    }

    def "503 → ConnectorIOException"() {
        given:
        service.failNextWith(503)

        when:
        new TestOperation(ctx()).execute()

        then:
        thrown(ConnectorIOException)
    }

    def "transport failure → ConnectorIOException"() {
        given:
        def failing = new OperationContext(Fixtures.connectorConfig(), Mock(au.com.azwork.idm.graphentity.http.EntityApi) {
            search(_) >> { throw new IOException('refused') }
        }, log.asLog())

        when:
        new TestOperation(failing).execute()

        then:
        thrown(ConnectorIOException)
    }

    def "schema operation returns one class per configured type"() {
        expect:
        new SchemaOperation(ctx()).execute(org.identityconnectors.framework.spi.Connector).objectClassInfo*.type as Set == ['user', 'account'] as Set
    }

    def "schema operation refuses an inconsistent relationship configuration"() {
        given:
        def c = ctx()
        c.config.objectTypes.user.relationships.owns.direction = au.com.azwork.idm.graphentity.config.Direction.INBOUND

        when:
        new SchemaOperation(c).execute(org.identityconnectors.framework.spi.Connector)

        then:
        thrown(ConfigurationException)
    }

    def "schema operation makes no HTTP calls"() {
        when:
        new SchemaOperation(ctx()).execute(org.identityconnectors.framework.spi.Connector)

        then:
        service.requests.empty
    }
}
