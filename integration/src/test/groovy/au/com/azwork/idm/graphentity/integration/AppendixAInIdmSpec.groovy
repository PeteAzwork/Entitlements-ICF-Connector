package au.com.azwork.idm.entitlementsaccelerator.integration

import au.com.azwork.idm.entitlementsaccelerator.support.FakeGraphService
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.util.concurrent.PollingConditions

/**
 * WS8.03–8.08: design Appendix A and the life of a relationship, driven through IDM 7.5: reconciliation, then
 * implicit sync for updates, relationship removal and delete, and an orphan that is reported but never deleted.
 * The mappings and objectTypes are the discovery tool's output for the fixture managed.json.
 */
@Stepwise
class AppendixAInIdmSpec extends Specification {

    static final String SANNE = 'Sanne'
    static final String ACCOUNT = 'Current-Account-1-Sanne'
    static final List<String> OWNS = [SANNE, ACCOUNT, 'Owns']
    static final String OWNS_JSON = '{"sourceID":"Sanne","targetID":"Current-Account-1-Sanne","type":"Owns"}'

    @Shared IdmStack stack = IdmStack.instance
    @Shared String userId
    @Shared String accountId
    PollingConditions poll = new PollingConditions(timeout: 20, delay: 0.5)

    IdmClient getIdm() { stack.idm }
    FakeGraphService getService() { stack.service }

    /** The latest write (POST or PUT) the service received for an entity. */
    Map lastWrite(String id) { service.requests.findAll { it.op in ['POST', 'PUT'] && (it.id ?: it.body?.id) == id }.last()?.body as Map }

    def setupSpec() {
        stack.setMappings(false)   // seed IDM first, then reconcile
        userId = stack.idm.create('managed/user', [userName: SANNE, givenName: 'Sanne', sn: 'Groen', mail: 'sanne@example.com'])._id
        accountId = stack.idm.create('managed/account', [accountId: ACCOUNT, name: 'Current Account', productCode: 'CA1',
                                                         ownedBy: [_ref: "managed/user/${userId}".toString()]])._id
    }

    def "users reconcile first: Sanne is created, and the relationship to an account not yet in the service is dropped with a WARN (WS8.03)"() {
        when:
        Map recon = idm.recon('managedUser_systementitlementsacceleratorUser')

        then:
        recon.state == 'SUCCESS'
        recon.statusSummary.FAILURE == 0
        recon.situationSummary.ABSENT >= 1
        with(service.entities[SANNE]) {
            baseType == 4
            attributes.givenName == 'Sanne'
            attributes.mail == 'sanne@example.com'
            directArchetypes == ['Customer']
        }
        lastWrite(SANNE).relationships == [[sourceID: SANNE, targetID: ACCOUNT, type: 'Owns']]
        !(OWNS in service.edges)
        stack.log().contains("dropped edge Sanne -Owns-> ${ACCOUNT}")
    }

    def "accounts next: the account carries the same relationship, which heals the drop"() {
        when:
        Map recon = idm.recon('managedAccount_systementitlementsacceleratorAccount')

        then:
        recon.statusSummary.FAILURE == 0
        service.entities[ACCOUNT].baseType == 2
        service.entities[ACCOUNT].directArchetypes == ['Account']
        OWNS in service.edges
    }

    def "a second user reconciliation is CONFIRMED and restates the relationship (WS4.08)"() {
        when:
        Map recon = idm.recon('managedUser_systementitlementsacceleratorUser')

        then:
        recon.situationSummary.CONFIRMED >= 1
        recon.statusSummary.FAILURE == 0
        lastWrite(SANNE).relationships == [[sourceID: SANNE, targetID: ACCOUNT, type: 'Owns']]
        OWNS in service.edges
    }

    def "reading through IDM returns entityRelationships in the P17 shape"() {
        expect:
        idm.get("system/entitlementsaccelerator/user/${SANNE}").entityRelationships == [OWNS_JSON]
        idm.get("system/entitlementsaccelerator/account/${ACCOUNT}").entityRelationships == [OWNS_JSON]
    }

    def "with implicit sync on, a scalar-only change restates the relationship, which survives (WS8.04)"() {
        given:
        stack.setMappings(true)

        when:
        idm.patch("managed/user/${userId}", [[operation: 'replace', field: 'givenName', value: 'Sanne-Marie']])

        then:
        poll.eventually {
            assert service.entities[SANNE].attributes.givenName == 'Sanne-Marie'
            assert lastWrite(SANNE).relationships == [[sourceID: SANNE, targetID: ACCOUNT, type: 'Owns']]
            assert OWNS in service.edges
        }
    }

    def "removing the relationship in IDM removes it from the service through the changed end's full-state write (WS8.05)"() {
        when: 'the relationship is removed on the account side'
        idm.patch("managed/account/${accountId}", [[operation: 'remove', field: 'ownedBy']])

        then: "the account's write omits it, and the service drops it for both entities"
        poll.eventually {
            assert lastWrite(ACCOUNT).relationships == []
            assert !(OWNS in service.edges)
        }

        when: "IDM 7.5 does not re-sync the user for a change made from the other end (even with notify/notifySelf), so the user's next write is the reconciliation's"
        idm.recon('managedUser_systementitlementsacceleratorUser')

        then: "that write is derived from IDM's current state, so it does not bring the relationship back"
        lastWrite(SANNE).relationships == []
        !(OWNS in service.edges)
    }

    def "deleting the account in IDM deletes it in the service, with its relationships: SOURCE_MISSING → DELETE (WS8.06)"() {
        given:
        idm.patch("managed/account/${accountId}", [[operation: 'add', field: 'ownedBy', value: [_ref: "managed/user/${userId}".toString()]]])
        poll.eventually { assert OWNS in service.edges }

        when:
        idm.delete("managed/account/${accountId}")

        then:
        poll.eventually {
            assert !service.entities.containsKey(ACCOUNT)
            assert !(OWNS in service.edges)
        }
        poll.eventually {   // IDM writes audit asynchronously
            assert idm.query('audit/sync', 'situation eq "SOURCE_MISSING"').any { it.action == 'DELETE' && it.targetObjectId?.toString()?.endsWith(ACCOUNT) }
        }
    }

    def "an entity created directly in the service is reported by reconciliation, never deleted (WS8.07, P16)"() {
        given:
        service.seed([id: 'Orphan-1', baseType: 4, attributes: [givenName: 'Nobody'], directArchetypes: ['Customer']])
        int deletesBefore = service.requestsOf('DELETE').size()

        when:
        Map recon = idm.recon('managedUser_systementitlementsacceleratorUser')

        then:
        recon.statusSummary.FAILURE == 0
        (recon.situationSummary.UNASSIGNED ?: 0) + (recon.situationSummary.UNQUALIFIED ?: 0) + (recon.situationSummary.TARGET_IGNORED ?: 0) >= 1
        service.entities.containsKey('Orphan-1')
        service.requestsOf('DELETE').size() == deletesBefore
    }
}
