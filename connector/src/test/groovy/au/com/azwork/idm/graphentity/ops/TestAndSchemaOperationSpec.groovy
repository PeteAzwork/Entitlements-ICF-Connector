package au.com.azwork.idm.entitlementsaccelerator.ops

import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
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

    def "a configured archetype no manifest defines is a WARN naming it, not a failure (P22 as amended; C16)"() {
        given:
        service.manifests.clear()
        service.seedManifest('test', ['Customer'], [])

        when:
        new TestOperation(ctx()).execute()

        then:
        noExceptionThrown()
        log.messagesAt(org.identityconnectors.common.logging.Log.Level.WARN).any { it.contains('Account') }
        !log.messagesAt(org.identityconnectors.common.logging.Log.Level.WARN).any { it.contains('Customer') }
    }

    def "an archetype defined only for the other base type is warned about too: Account must be a resource archetype"() {
        given:
        service.manifests.clear()
        service.seedManifest('test', ['Customer', 'Account'], [])

        when:
        new TestOperation(ctx()).execute()

        then:
        noExceptionThrown()
        log.messagesAt(org.identityconnectors.common.logging.Log.Level.WARN).any { it.contains('Account') }
    }

    def "a service with no manifests (404) is a WARN, not a failure"() {
        given:
        service.manifests.clear()

        when:
        new TestOperation(ctx()).execute()

        then:
        noExceptionThrown()
        log.messagesAt(org.identityconnectors.common.logging.Log.Level.WARN).any { it.toLowerCase().contains('manifest') }
    }

    def "when every configured archetype is defined, nothing is logged at WARN"() {
        when:
        new TestOperation(ctx()).execute()

        then:
        log.messagesAt(org.identityconnectors.common.logging.Log.Level.WARN).empty
    }

    def "archetypes are found inside the export bundle the service actually returns (C11)"() {
        given:
        service.failNextWith(200, [results: [], pagedResultsCookie: null, totalResults: 0, hasMore: false])
        service.failNextWith(200, [paths: [[entities: [:], entityRelationships: [], policies: [:],
                                            manifests: [banking: [name: 'banking', subjectArchetypes: [[name: 'Customer']], resourceArchetypes: [[name: 'Account']]]]]]])

        when:
        new TestOperation(ctx()).execute()

        then:
        noExceptionThrown()
        log.messagesAt(org.identityconnectors.common.logging.Log.Level.WARN).empty
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
        service.entities.isEmpty()   // not .empty: on a Map that reads a key named 'empty'
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
        def failing = new OperationContext(Fixtures.connectorConfig(), Mock(au.com.azwork.idm.entitlementsaccelerator.http.EntityApi) {
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
        c.config.objectTypes.user.relationships.owns.direction = au.com.azwork.idm.entitlementsaccelerator.config.Direction.INBOUND

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
