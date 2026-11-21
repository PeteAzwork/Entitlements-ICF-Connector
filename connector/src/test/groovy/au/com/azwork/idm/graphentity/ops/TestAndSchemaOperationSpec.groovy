package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.exceptions.InvalidCredentialException

/** Design §9.5 — Test and Schema. */
class TestAndSchemaOperationSpec extends OperationSpecBase {

    def setup() { Fixtures.seedManifest(service) }

    def "test issues an authenticated search with page size 1, then checks the manifests (P22)"() {
        when:
        new TestOperation(ctx()).execute()

        then:
        noExceptionThrown()
        service.requests*.op == ['SEARCH', 'MANIFESTS']
        service.requestsOf('SEARCH')[0].query.pageSize == 1
    }

    def "test fails when a configured archetype is not defined in any manifest, naming it (P22)"() {
        given:
        service.manifests.clear()
        service.seedManifest('test', ['Customer'], [])

        when:
        new TestOperation(ctx()).execute()

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('Account')
    }

    def "an archetype defined for the other base type does not count: Account must be a resource archetype"() {
        given:
        service.manifests.clear()
        service.seedManifest('test', ['Customer', 'Account'], [])

        when:
        new TestOperation(ctx()).execute()

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('Account')
    }

    def "test fails clearly when the service has no manifests at all (404)"() {
        given:
        service.manifests.clear()

        when:
        new TestOperation(ctx()).execute()

        then:
        def e = thrown(ConfigurationException)
        e.message.toLowerCase().contains('manifest')
    }

    def "archetypes in later manifests count too"() {
        given:
        service.manifests.clear()
        service.seedManifest('people', ['Customer'], [])
        service.seedManifest('products', [], ['Account'])

        when:
        new TestOperation(ctx()).execute()

        then:
        noExceptionThrown()
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
