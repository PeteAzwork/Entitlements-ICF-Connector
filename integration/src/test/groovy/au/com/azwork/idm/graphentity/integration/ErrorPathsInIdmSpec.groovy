package au.com.azwork.idm.entitlementsaccelerator.integration

import au.com.azwork.idm.entitlementsaccelerator.support.FakeGraphService
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/** WS8.09: error paths as IDM sees them. */
class ErrorPathsInIdmSpec extends Specification {

    @Shared IdmStack stack = IdmStack.instance
    PollingConditions poll = new PollingConditions(timeout: 20, delay: 0.5)

    IdmClient getIdm() { stack.idm }
    FakeGraphService getService() { stack.service }

    def cleanup() {
        stack.loadProvisioner(stack.provisioner)   // restore the working configuration; waits for its Test to pass
    }

    def "a wrong API key makes the Test operation fail, and the key is not logged"() {
        given:
        stack.saveProvisioner([configurationProperties: [password: 'wrong-key']])

        expect:
        new PollingConditions(timeout: 30, delay: 1).eventually { assert idm.action('system/entitlementsaccelerator', 'test').ok == false }
        !stack.log().contains('wrong-key')
    }

    def "a 5xx on a write during implicit sync is reported against that object, and the next reconciliation repairs it"() {
        given:
        stack.setMappings(true)
        service.failNextOf('POST', 503)

        when:
        String id = idm.create('managed/user', [userName: 'Err-1', givenName: 'Err', sn: 'One', mail: 'err1@example.com'])._id

        then: 'the write was attempted and failed, so the entity is not in the service, and IDM logged the failure naming it'
        poll.eventually { assert service.requestsOf('POST').any { it.body?.id == 'Err-1' } }
        !service.entities.containsKey('Err-1')
        poll.eventually { assert stack.log().contains('HTTP 503 during CREATE') }

        when:
        idm.recon('managedUser_systementitlementsacceleratorUser')

        then:
        service.entities.containsKey('Err-1')
    }

    def "a DELETE the service answers 404: tolerated by default, UnknownUid when deleteMissingTolerated is false"() {
        given: 'IDM reads the entity before deleting it, so it exists; the service then answers the DELETE with 404'
        service.seed([id: 'Ghost-1', baseType: 2, attributes: [:], directArchetypes: ['Account']])
        service.failNextOf('DELETE', 404)

        when:
        idm.delete('system/entitlementsaccelerator/account/Ghost-1')

        then:
        noExceptionThrown()

        when:
        String custom = (stack.fullProvisioner.configurationProperties as Map).customConfiguration
        stack.saveProvisioner([configurationProperties: [customConfiguration: custom.replace('deleteMissingTolerated=true', 'deleteMissingTolerated=false')]])
        service.seed([id: 'Ghost-2', baseType: 2, attributes: [:], directArchetypes: ['Account']])
        service.failNextOf('DELETE', 404)
        idm.delete('system/entitlementsaccelerator/account/Ghost-2')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('404')
    }
}
