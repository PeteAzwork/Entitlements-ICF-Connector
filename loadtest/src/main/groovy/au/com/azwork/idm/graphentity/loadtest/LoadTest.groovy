package au.com.azwork.idm.graphentity.loadtest

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The end-to-end load test (docs/load-testing.md). Drives IDM through its REST API, as an operator and the users of
 * IDM would; the connector carries every change to the service; the test reads the service back over HTTP to check
 * it. Phases, each timed and checked:
 * <ol>
 *   <li><b>seed</b>: N users and N accounts (each owned by its user) created in IDM with implicit sync off;</li>
 *   <li><b>initial</b>: reconcile accounts, then users (runbook §3); every entity and its attributes in the
 *       service; a sample of relationships read back;</li>
 *   <li><b>steady</b>: reconcile users again; every linked user is restated (WS4.08);</li>
 *   <li><b>sync</b>: implicit sync on; M users changed at a set rate; time until the service shows every change;</li>
 *   <li><b>cleanup</b>: the run's users and accounts deleted in IDM, which deletes them in the service; anything left
 *       is then deleted directly and reported.</li>
 * </ol>
 * The mappings' implicit-sync setting is restored at the end, whatever happened.
 */
class LoadTest {
    static final int RECON_TIMEOUT_SECONDS = 4 * 3600

    final LoadTestOptions o
    final IdmRest idm
    final ServiceClient service
    final IdmLog log
    final LoadReport report
    final Map<Integer, String> userIds = new ConcurrentHashMap<>()
    private Map originalSync
    private Boolean syncOn

    LoadTest(LoadTestOptions o) {
        this(o, new IdmRest(o.idmUrl, o.idmUser, o.idmPassword), new ServiceClient(o.serviceUrl, o.apiKeyHeader, o.apiKey),
            o.idmContainer ? new IdmLog(o.idmContainer) : null)
    }

    LoadTest(LoadTestOptions o, IdmRest idm, ServiceClient service, IdmLog log) {
        this.o = o
        this.idm = idm
        this.service = service
        this.log = log
        this.report = new LoadReport(o)
    }

    /** Runs every selected phase and writes the report. 0 when everything passed, 1 otherwise. */
    int run() {
        Instant began = Instant.now()
        report.started = began.toString()
        boolean stop = false
        try {
            stop = !phase('preflight') { preflight() }
            if (!stop && o.configure) stop = !phase('configure') { new IdmConfigurator(o, idm, log).configure() }
            if (!stop) stop = !phase('model') { checkModel() }
            for (String name : LoadTestOptions.PHASES) {
                if (!o.runs(name) || (name == 'cleanup' && o.keep)) { skip(name, o.keep && name == 'cleanup' ? '--keep' : 'not selected'); continue }
                if (stop && name != 'cleanup') { skip(name, 'an earlier phase failed'); continue }
                if (stop && originalSync == null) { skip(name, 'IDM was not reachable'); continue }
                boolean ok = phase(name) { "${name}"() }
                if (!ok) stop = true
            }
        } finally {
            restoreSync()
            if (log) { try { report.logCounts = log.connectorCounts(began) } catch (Exception e) { report.problems << "IDM's log could not be read: ${e.message}".toString() } }
            report.serviceStatuses = service.statusCounts()
            report.prefixFallback = service.prefixFilterRefused
            report.finished = Instant.now().toString()
            report.write(o.out)
        }
        report.passed() ? 0 : 1
    }

    // ------------------------------------------------------------------ phases

    Map preflight() {
        Map ping = idm.get('info/ping')
        if (ping.state != 'ACTIVE_READY') throw new IllegalStateException("IDM is not ready: ${ping.state}")
        try { report.environment['IDM version'] = idm.get('info/version').productVersion } catch (Exception ignored) { }
        service.reachable()
        report.environment['Service'] = 'reachable with the key'
        [idm: 'ACTIVE_READY', service: 'reachable']
    }

    Map checkModel() {
        Map test = idm.action("system/${o.connector}", 'test')
        if (test.ok != true) throw new IllegalStateException("the connector's Test fails: ${test.error ?: test}")
        report.environment['Connector Test'] = 'ok'
        List<String> names = (idm.get('sync/mappings?_queryFilter=true').result as List<Map> ?: [])*.name
        [o.userMapping, o.accountMapping].each { if (!(it in names)) throw new IllegalStateException("mapping ${it} is not loaded; use --configure, or name yours with --user-mapping and --account-mapping") }
        Map account = (idm.get('config/managed').objects as List<Map>).find { it.name == 'account' }
        Map props = account?.schema?.properties as Map ?: [:]
        if (!props.accountId || !props.ownedBy) throw new IllegalStateException('managed/account with accountId and ownedBy is needed (the load-test model; --configure loads it)')
        originalSync = idm.get('config/sync')
        [connectorTest: 'ok', mappings: [o.userMapping, o.accountMapping]]
    }

    Map seed() {
        setSync(false)
        Latencies users = report.latency('IDM create user'), accounts = report.latency('IDM create account')
        long t0 = System.nanoTime()
        int failed = parallel(1..o.users, o.threads) { int i ->
            String uid = users.time { idm.create('managed/user', [userName: o.user(i), givenName: 'Load', sn: "T${i}".toString(), mail: "${o.user(i)}@example.com".toString()])._id }
            userIds[i] = uid
            accounts.time {
                idm.create('managed/account', [accountId: o.account(i), name: "Load account ${i}".toString(), productCode: 'LT',
                                               ownedBy: [_ref: "managed/user/${uid}".toString()]])
            }
        }
        double s = seconds(t0)
        if (failed) throw new IllegalStateException("${failed} of ${o.users} user/account pairs could not be created in IDM")
        [users: o.users, accounts: o.users, 'creates/s': rate(2 * o.users, s)]
    }

    Map initial() {
        setSync(false)
        Map accounts = idm.recon(o.accountMapping, RECON_TIMEOUT_SECONDS)
        Map users = idm.recon(o.userMapping, RECON_TIMEOUT_SECONDS)
        Map m = reconFigures('account recon', accounts) + reconFigures('user recon', users)
        requireNoFailures(accounts, o.accountMapping)
        requireNoFailures(users, o.userMapping)
        Map<String, Map> inService = awaitInService('every user and account in the service') { Map<String, Map> byId ->
            (1..o.users).every { byId.containsKey(o.user(it)) && byId.containsKey(o.account(it)) }
        }
        List<String> wrong = (1..o.users).findAll { int i ->
            Map attrs = inService[o.user(i)]?.attributes as Map ?: [:]
            !(attrs.givenName == 'Load' && attrs.sn == "T${i}".toString() && attrs.mail == "${o.user(i)}@example.com".toString())
        }.collect { o.user(it) }
        if (wrong) throw new IllegalStateException("${wrong.size()} users have wrong attributes in the service, e.g. ${wrong.take(5)}")
        m + sampleRelationships()
    }

    Map steady() {
        Map users = idm.recon(o.userMapping, RECON_TIMEOUT_SECONDS)
        requireNoFailures(users, o.userMapping)
        reconFigures('user recon', users)
    }

    Map sync() {
        setSync(true)
        ensureUserIds()
        Latencies patches = report.latency('IDM change user (implicit sync)')
        long t0 = System.nanoTime()
        int failed = parallel(1..o.changes, o.threads) { int i ->
            long due = t0 + (long) (((i - 1) / o.rate) * 1e9)
            long wait = due - System.nanoTime()
            if (wait > 0) Thread.sleep(wait.intdiv(1_000_000), (int) (wait % 1_000_000))
            patches.time { idm.patch("managed/user/${userIds[i]}", [[operation: 'replace', field: 'givenName', value: "Changed-${i}".toString()]]) }
        }
        double sent = seconds(t0)
        long sentAt = System.nanoTime()
        Map<String, Map> inService = awaitInService('every change in the service') { Map<String, Map> byId ->
            (1..o.changes).every { (byId[o.user(it)]?.attributes as Map)?.givenName == "Changed-${it}".toString() }
        }
        double converged = seconds(sentAt)
        List<Integer> unchanged = o.changes < o.users ? ((o.changes + 1)..o.users).toList() : []
        List<String> disturbed = unchanged.findAll { (inService[o.user(it)]?.attributes as Map)?.givenName != 'Load' }.collect { o.user(it) }
        if (failed) throw new IllegalStateException("${failed} of ${o.changes} changes failed in IDM")
        if (disturbed) throw new IllegalStateException("${disturbed.size()} users that were not changed differ in the service, e.g. ${disturbed.take(5)}")
        [changes: o.changes, 'target/s': o.rate, 'achieved/s': rate(o.changes, sent), 'last change to all visible (s)': round(converged)]
    }

    Map cleanup() {
        setSync(true)
        Latencies deletes = report.latency('IDM delete (implicit sync)')
        List<Map> accounts = idm.queryAll('managed/account', "accountId sw \"${o.prefix}\"")
        List<Map> users = idm.queryAll('managed/user', "userName sw \"${o.prefix}\"")
        int failed = parallel(accounts, o.threads) { Map a -> deletes.time { idm.delete("managed/account/${a._id}") } }
        failed += parallel(users, o.threads) { Map u -> deletes.time { idm.delete("managed/user/${u._id}") } }
        List<Map> left = []
        try {
            awaitInService('the run\'s entities to leave the service', true) { Map<String, Map> byId -> byId.isEmpty() }
        } catch (IllegalStateException ignored) {
            left = service.findByPrefix(o.prefix)
        }
        int swept = left.count { service.delete(ServiceClient.idOf(it)) in [200, 202, 204, 404] } as int
        Map m = [deletedInIdm: accounts.size() + users.size() - failed, leftInService: left.size(), sweptDirectly: swept] as Map
        if (failed) throw new IllegalStateException("${failed} deletes failed in IDM")
        if (left) throw new IllegalStateException("${left.size()} entities stayed in the service after their IDM objects were deleted (swept directly: ${swept})")
        m
    }

    // ------------------------------------------------------------------ helpers

    /** Runs a phase: PASS with its figures, or FAIL with the reason. Returns whether it passed. */
    private boolean phase(String name, Closure<Map> body) {
        long t0 = System.nanoTime()
        Map<String, Object> entry = [name: name, result: 'PASS', metrics: [:], notes: []] as Map<String, Object>
        report.phases << entry
        String memoryBefore = memory()
        try {
            entry.metrics = body.call() ?: [:]
        } catch (Exception e) {
            entry.result = 'FAIL'
            (entry.notes as List) << (e.message ?: e.class.simpleName)
        }
        entry.seconds = round(seconds(t0))
        String memoryAfter = memory()
        if (memoryAfter && name in LoadTestOptions.PHASES) (entry.metrics as Map)['IDM heap MiB'] = "${memoryBefore} → ${memoryAfter}".toString()
        println "${name}: ${entry.result} in ${entry.seconds} s ${entry.metrics ?: ''}${entry.notes ? ' ' + entry.notes : ''}"
        entry.result == 'PASS'
    }

    private void skip(String name, String why) {
        report.phases << ([name: name, result: 'SKIPPED', metrics: [:], notes: [why]] as Map<String, Object>)
    }

    /**
     * Implicit sync on or off for the two mappings, waiting until IDM reports it. On is proven by a canary: a user of
     * this run, changed until the change reaches the service (sync/mappings reports a change before it acts on it).
     */
    void setSync(boolean on) {
        if (syncOn == on) return
        Map current = idm.get('config/sync')
        List<Map> mappings = (current.mappings as List<Map>).collect { Map m ->
            m.name in [o.userMapping, o.accountMapping] ? (new LinkedHashMap(m) + [enableSync: on]) : m
        }
        idm.put('config/sync', current + [mappings: mappings])
        idm.awaitTrue(60, "implicit sync ${on ? 'on' : 'off'}") {
            List<Map> active = idm.get('sync/mappings?_queryFilter=true').result as List<Map>
            [o.userMapping, o.accountMapping].every { n -> active.any { it.name == n && (it.enableSync == null ? true : it.enableSync) == on } }
        }
        if (on) awaitCanary() else Thread.sleep(5000)
        syncOn = on
    }

    private void awaitCanary() {
        List<Map> found = idm.queryAll('managed/user', "userName eq \"${o.canary}\"")
        String id = found ? found[0]._id : idm.create('managed/user', [userName: o.canary, givenName: 'Load', sn: 'Canary', mail: "${o.canary}@example.com".toString()])._id
        int round = 0
        idm.awaitTrue(120, 'implicit sync to reach the service') {
            String marker = "canary-${round++}".toString()
            idm.patch("managed/user/${id}", [[operation: 'replace', field: 'sn', value: marker]])
            long until = System.currentTimeMillis() + 5000
            while (System.currentTimeMillis() < until) {
                if ((service.findById(o.canary)?.attributes as Map)?.sn == marker) return true
                Thread.sleep(500)
            }
            false
        }
    }

    private void restoreSync() {
        if (originalSync == null) return
        try {
            List<Map> current = idm.get('config/sync').mappings as List<Map>
            Map<String, Object> original = (originalSync.mappings as List<Map>).collectEntries { [(it.name): it.enableSync] }
            List<Map> restored = current.collect { Map m ->
                m.name in [o.userMapping, o.accountMapping] && original.containsKey(m.name)
                    ? (original[m.name] == null ? m.findAll { k, v -> k != 'enableSync' } : new LinkedHashMap(m) + [enableSync: original[m.name]]) : m
            }
            idm.put('config/sync', idm.get('config/sync') + [mappings: restored])
        } catch (Exception e) {
            report.problems << "Restoring the mappings' implicit-sync setting failed; check config/sync: ${e.message}".toString()
        }
    }

    /** Polls the service until {@code done} holds for the run's entities by ID, or fails after --settle-seconds. */
    private Map<String, Map> awaitInService(String what, boolean withCanary = false, Closure<Boolean> done) {
        long deadline = System.currentTimeMillis() + o.settleSeconds * 1000L
        Map<String, Map> byId = [:]
        while (true) {
            byId = service.findByPrefix(o.prefix).findAll { withCanary || ServiceClient.idOf(it) != o.canary }
                .collectEntries { [(ServiceClient.idOf(it)): it] } as Map<String, Map>
            if (done(byId)) return byId
            if (System.currentTimeMillis() > deadline) {
                throw new IllegalStateException("timed out after ${o.settleSeconds}s waiting for ${what} (the service holds ${byId.size()} of the run's entities)")
            }
            Thread.sleep(2000)
        }
    }

    /** Reads back --sample users by GET, which carries relationships; GET is unreliable on the real service (C1). */
    private Map sampleRelationships() {
        if (!o.sample) return [:]
        List<Integer> picks = (0..<o.sample).collect { int k -> 1 + (int) (k * (long) o.users / o.sample) }.unique()
        int found = 0, missing = 0, unreadable = 0
        List<String> missingIds = []
        picks.each { int i ->
            Map r = service.read(o.user(i))
            if (r.status != 200) { unreadable++; return }
            boolean ok = ((r.body as Map).relationships as List<Map> ?: []).any {
                it.source == o.user(i) && it.target == o.account(i) && it.type == o.relationshipType
            }
            if (ok) found++ else { missing++; missingIds << o.user(i) }
        }
        if (missing) throw new IllegalStateException("${missing} of ${picks.size()} sampled users lack their ${o.relationshipType} relationship, e.g. ${missingIds.take(5)}")
        Map m = ['relationships checked': found] as Map
        if (unreadable) m['relationships unreadable (GET by ID, C1)'] = unreadable
        m
    }

    private void ensureUserIds() {
        if (userIds.size() >= o.changes) return
        idm.queryAll('managed/user', "userName sw \"${o.prefix}u\"", '_id,userName').each { Map u ->
            def m = (u.userName as String) =~ /u(\d+)$/
            if (m.find()) userIds[m.group(1) as int] = u._id as String
        }
        List<Integer> absent = (1..o.changes).findAll { !userIds.containsKey(it) }
        if (absent) throw new IllegalStateException("${absent.size()} of the run's users are not in IDM: run the seed phase, or use the same --run-id")
    }

    private static Map reconFigures(String label, Map recon) {
        Map summary = recon.statusSummary as Map ?: [:]
        int processed = ((summary.SUCCESS ?: 0) as int) + ((summary.FAILURE ?: 0) as int)
        Map situations = (recon.situationSummary as Map ?: [:]).findAll { k, v -> v } as Map
        Map m = [:] as Map
        m["${label} s".toString()] = round((recon.elapsedMs as long) / 1000d)
        m["${label} ms/entity".toString()] = processed ? round((recon.elapsedMs as long) / processed) : null
        m["${label} situations".toString()] = situations
        m
    }

    private static void requireNoFailures(Map recon, String mapping) {
        int failures = ((recon.statusSummary as Map)?.FAILURE ?: 0) as int
        if (recon.state != 'SUCCESS' || failures) throw new IllegalStateException("reconciliation of ${mapping}: ${recon.state}, ${failures} failures")
    }

    /** Runs {@code task} for each item on {@code threads} threads; returns how many threw. */
    static <T> int parallel(Iterable<T> items, int threads, Closure task) {
        def pool = Executors.newFixedThreadPool(threads)
        AtomicInteger failed = new AtomicInteger()
        items.each { T item -> pool.submit { try { task.call(item) } catch (Throwable ignored) { failed.incrementAndGet() } } }
        pool.shutdown()
        pool.awaitTermination(12, TimeUnit.HOURS)
        failed.get()
    }

    private String memory() {
        try {
            Map heap = idm.get('health/memory').heapMemoryUsage as Map
            heap?.used != null ? Math.round((heap.used as long) / 1048576d).toString() : null
        } catch (Exception ignored) {
            null
        }
    }

    private static double seconds(long t0) { (System.nanoTime() - t0) / 1e9 }
    private static double rate(int n, double s) { s > 0 ? round(n / s) : 0 }
    static double round(Number n) { Math.round(n.doubleValue() * 10) / 10d }
}
