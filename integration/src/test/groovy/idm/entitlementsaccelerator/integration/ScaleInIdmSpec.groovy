package idm.entitlementsaccelerator.integration

import idm.entitlementsaccelerator.support.FakeAcceleratorService
import spock.lang.IgnoreIf
import spock.lang.Shared
import spock.lang.Specification
import spock.lang.Stepwise
import spock.util.concurrent.PollingConditions

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WS8.10 / WS8.11 (opt-in: -PidmScale=N). N users each owning one account, through IDM 7.5:
 * the initial load by reconciliation, the cost of re-sending relationships on every reconciliation (WS4.08), and
 * implicit sync racing a multi-threaded reconciliation, after which the service must equal IDM.
 * Timings are printed and written to build/scale-report.txt; they are measurements, not pass criteria.
 */
@Stepwise
@IgnoreIf({ (System.getProperty('idm.scale', '0') as int) <= 0 })
class ScaleInIdmSpec extends Specification {

    static final int N = System.getProperty('idm.scale', '0') as int
    static final File REPORT = new File('build/scale-report.txt')

    @Shared IdmStack stack = IdmStack.instance
    @Shared Map<Integer, String> users = new ConcurrentHashMap<>()
    PollingConditions poll = new PollingConditions(timeout: 900, delay: 2)

    IdmClient getIdm() { stack.idm }
    FakeAcceleratorService getService() { stack.service }

    static String user(int i) { "Scale-U${i}".toString() }
    static String account(int i) { "Scale-A${i}".toString() }

    static void report(String line) {
        println line
        REPORT.parentFile.mkdirs()
        REPORT << line + '\n'
    }

    static String memory() {
        IdmStack.docker('stats', '--no-stream', '--format', '{{.MemUsage}}', IdmStack.CONTAINER).trim()
    }

    def setupSpec() {
        REPORT.text = "Scale run: ${N} users, ${N} accounts, IDM image ${IdmStack.IMAGE}\n"
        stack.setMappings(false)
        long t0 = System.nanoTime()
        def pool = Executors.newFixedThreadPool(8)   // seeding is set-up, not measurement: do it in parallel
        AtomicReference<Throwable> failure = new AtomicReference<>()
        (1..N).each { int i ->
            pool.submit {
                try {
                    String uid = stack.idm.create('managed/user', [userName: user(i), givenName: 'Scale', sn: "U${i}", mail: "u${i}@example.com"])._id
                    users[i] = uid
                    stack.idm.create('managed/account', [accountId: account(i), name: "Account ${i}", productCode: 'SC',
                                                         ownedBy: [_ref: "managed/user/${uid}".toString()]])
                } catch (Throwable t) { failure.compareAndSet(null, t) }
            }
        }
        pool.shutdown()
        pool.awaitTermination(4, TimeUnit.HOURS)
        if (failure.get()) throw failure.get()
        report("seeding IDM (2N creates in 8 threads, no sync): ${(System.nanoTime() - t0).intdiv(1_000_000)} ms; IDM memory ${memory()}")
    }

    def "initial load: reconciling users, then accounts, puts every entity and relationship in the service (WS8.11)"() {
        when:
        long t0 = System.nanoTime()
        Map users = idm.recon('managedUser_systemEntitlementsacceleratorUser')
        long tUsers = (System.nanoTime() - t0).intdiv(1_000_000)
        t0 = System.nanoTime()
        Map accounts = idm.recon('managedAccount_systemEntitlementsacceleratorAccount')
        long tAccounts = (System.nanoTime() - t0).intdiv(1_000_000)
        report("initial user recon: ${tUsers} ms (${String.format('%.1f', tUsers / N)} ms per user, each building its relationships); account recon: ${tAccounts} ms; IDM memory ${memory()}")

        then:
        users.statusSummary.FAILURE == 0
        accounts.statusSummary.FAILURE == 0
        (1..N).every { service.entities.containsKey(user(it)) && service.entities.containsKey(account(it)) }
        (1..N).every { [user(it), account(it), 'Owns'] in service.edges }
    }

    def "a later reconciliation re-sends every user's relationships: one PUT per linked user (WS4.08 cost)"() {
        given:
        int putsBefore = service.requestsOf('PUT').size()

        when:
        long t0 = System.nanoTime()
        Map recon = idm.recon('managedUser_systemEntitlementsacceleratorUser')
        long ms = (System.nanoTime() - t0).intdiv(1_000_000)
        int puts = service.requestsOf('PUT').size() - putsBefore
        report("steady-state user recon: ${ms} ms, ${puts} PUTs for ${N} unchanged users (${String.format('%.1f', ms / N)} ms per user)")

        then:
        recon.statusSummary.FAILURE == 0
        puts >= N   // every linked user is restated: search items carry no relationships (WS4.08)
    }

    def "implicit sync racing a 4-thread reconciliation converges: afterwards the service equals IDM (WS8.10)"() {
        given:
        stack.setMappings(true)
        Map mapping = stack.mappings.find { it.name == 'managedUser_systemEntitlementsacceleratorUser' }
        stack.idm.put('config/sync', [mappings: stack.mappings.collect { (it.name == mapping.name ? it + [taskThreads: 4] : it) + [enableSync: true] }])
        Thread.sleep(5000)
        List<Integer> changed = (1..N).findAll { it % 5 == 0 }.take(500)
        def pool = Executors.newFixedThreadPool(4)

        when:
        Map started = idm.call('POST', 'recon?_action=recon&mapping=managedUser_systemEntitlementsacceleratorUser&waitForCompletion=false', [:]) as Map
        changed.each { int i ->
            pool.submit { idm.patch("managed/user/${users[i]}", [[operation: 'replace', field: 'givenName', value: "Changed-${i}".toString()]]) }
        }
        pool.shutdown()
        pool.awaitTermination(2, TimeUnit.HOURS)
        long t0 = System.nanoTime()
        while (!(idm.get("recon/${started._id}").state in ['SUCCESS', 'FAILED', 'CANCELED'])) Thread.sleep(2000)
        report("concurrent 4-thread user recon finished ${(System.nanoTime() - t0).intdiv(1_000_000)} ms after the last change was sent")
        report("concurrent: ${changed.size()} implicit syncs during a 4-thread user recon of ${N}; recon state ${idm.get("recon/${started._id}").state}")

        then:
        poll.eventually {
            assert changed.every { service.entities[user(it)].attributes.givenName == "Changed-${it}".toString() }
            assert (1..N).findAll { !(it in changed) }.every { service.entities[user(it)].attributes.givenName == 'Scale' }
            assert (1..N).every { [user(it), account(it), 'Owns'] in service.edges }
        }
    }
}
