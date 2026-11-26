package idm.entitlementsaccelerator.integration

import spock.lang.Shared
import spock.lang.Specification

/** WS7.07 / WS8: the connector loads and answers inside the real Scripted REST harness of IDM 7.5. */
class ConnectorInIdmSpec extends Specification {

    @Shared IdmStack stack = IdmStack.instance
    IdmClient getIdm() { stack.idm }

    def setupSpec() { stack.awaitConnector() }

    def "the Test operation succeeds through the real harness: customizer init and decorate, then TestScript"() {
        when:
        Map result = idm.action('system/entitlementsaccelerator', 'test')

        then:
        result.ok == true
        result.connectorRef.bundleName == 'org.forgerock.openicf.connectors.scriptedrest-connector'
    }

    def "the Schema script's builder DSL yields the user and account classes with the connector-managed attributes (H5)"() {
        when:
        Map types = idm.get('config/provisioner.openicf/entitlementsaccelerator').objectTypes as Map

        then:
        types.keySet().containsAll(['user', 'account'])
        with(types.user.properties as Map) {
            it.__NAME__.type == 'string'
            it.entityRelationships.type == 'array'
            it.directArchetypes.type == 'array'
            it.lastEvaluated.flags.containsAll(['NOT_CREATABLE', 'NOT_UPDATEABLE'])
            it.givenName.type == 'string'
        }
        (types.account.properties as Map).keySet() == ['__NAME__', 'name', 'productCode', 'directArchetypes', 'entityRelationships', 'lastEvaluated'] as Set
    }

    def "the library on the provisioner classpath, as sources or as a jar, loads without compilation errors (WS7.02)"() {
        expect:
        idm.action('system/entitlementsaccelerator', 'test').ok == true
        !stack.log().contains('ClassNotFound')
        !stack.log().contains('BUG! exception')
        !stack.log().contains('LinkageError')
    }

    def "the mapping library loads with IDM's own require and answers under IDM's Rhino (WS8.08)"() {
        when:
        def answer = idm.call('POST', 'script?_action=eval', [type: 'text/javascript', globals: [:],
            source: "var lib = require('lib/entitlementsAcceleratorPayload'); [lib.normalisePropertyName('ownsSet'), lib.isPassive('ownedBy'), lib.loadTypeConfig('account').idProperty].join(',');"])

        then:
        answer == 'Owns,true,accountId'
    }

    def "the API key is sent but never written to IDM's log"() {
        expect:
        !stack.log().contains(IdmStack.API_KEY)
    }
}
