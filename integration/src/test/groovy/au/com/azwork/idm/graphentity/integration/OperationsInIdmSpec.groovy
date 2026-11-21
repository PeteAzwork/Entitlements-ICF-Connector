package au.com.azwork.idm.graphentity.integration

import au.com.azwork.idm.graphentity.support.FakeGraphService
import groovy.json.JsonSlurper
import spock.lang.Shared
import spock.lang.Specification
import spock.util.concurrent.PollingConditions

/** WS9 in IDM 7.5: the idProperty change guard (WS9.04) and the reconciliation schedules (WS9.02). */
class OperationsInIdmSpec extends Specification {

    @Shared IdmStack stack = IdmStack.instance
    PollingConditions poll = new PollingConditions(timeout: 30, delay: 0.5)

    IdmClient getIdm() { stack.idm }
    FakeGraphService getService() { stack.service }

    def setupSpec() { stack.setMappings(true) }

    def "renaming a user in IDM moves the entity to its new ID, relationships included, instead of failing (WS9.04)"() {
        given: 'a user owning an account, both in the service'
        String uid = idm.create('managed/user', [userName: 'Rename-1', givenName: 'Re', sn: 'Name', mail: 'rename@example.com'])._id
        idm.create('managed/account', [accountId: 'Rename-Acct', name: 'Rename account', productCode: 'RN',
                                       ownedBy: [_ref: "managed/user/${uid}".toString()]])
        poll.eventually { assert ['Rename-1', 'Rename-Acct', 'Owns'] in service.edges }

        when: 'its idProperty changes'
        idm.patch("managed/user/${uid}", [[operation: 'replace', field: 'userName', value: 'Rename-2']])

        then: 'the old entity is gone, the new one exists with the relationship, and IDM is linked to it'
        poll.eventually {
            assert !service.entities.containsKey('Rename-1')
            assert service.entities.containsKey('Rename-2')
            assert ['Rename-2', 'Rename-Acct', 'Owns'] in service.edges
            assert !service.edges.any { it.contains('Rename-1') }
        }
        idm.get('system/graphentity/user/Rename-2').__NAME__ == 'Rename-2'

        and: 'a reconciliation afterwards finds it CONFIRMED, not an orphan plus a missing entity'
        Map recon = idm.recon('managedUser_systemGraphentityUser')
        recon.statusSummary.FAILURE == 0
        (recon.situationSummary.MISSING ?: 0) == 0
        service.entities.containsKey('Rename-2')
    }

    def "the reconciliation schedule samples load into IDM's scheduler (WS9.02)"() {
        given:
        List<String> names = ['account', 'user']

        when:
        names.each { String name ->
            Map schedule = new JsonSlurper().parse(new File(IdmStack.ROOT, "config/schedule-recon-graphentity-${name}.json")) as Map
            schedule.remove('_comment')
            idm.put("config/schedule/recon-graphentity-${name}", schedule)
        }

        then: 'the scheduler accepts both cron expressions and plans their next runs'
        poll.eventually {
            assert names.every { String name -> idm.get("scheduler/job/recon-graphentity-${name}").nextRunDate }
        }

        cleanup:
        names.each { String name -> try { idm.delete("config/schedule/recon-graphentity-${name}") } catch (ignored) { } }
    }
}
