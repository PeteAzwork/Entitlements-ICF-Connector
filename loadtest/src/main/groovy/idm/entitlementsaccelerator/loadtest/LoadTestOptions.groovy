package idm.entitlementsaccelerator.loadtest

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The load test's settings (docs/load-testing.md §3), from the command line and the environment.
 *
 * <p>Guards, because every run writes to IDM and, through the connector, to the service:
 * <ul>
 *   <li>nothing runs without {@code --allow-writes};</li>
 *   <li>every host it talks to, or tells IDM to talk to, must be on the allow-list: localhost by default, more from
 *       {@code --allowed-hosts} or {@code LOADTEST_ALLOWED_HOSTS};</li>
 *   <li>the service's API key and IDM's admin password come only from the environment ({@code LOADTEST_API_KEY},
 *       {@code LOADTEST_IDM_PASSWORD}). Options that would carry them are refused, so they stay out of shell
 *       history and the process list;</li>
 *   <li>every entity it creates has an ID starting {@code lt-<run>-}, and cleanup removes only those.</li>
 * </ul>
 */
class LoadTestOptions {
    static final List<String> PHASES = ['seed', 'initial', 'steady', 'sync', 'cleanup']
    static final Set<String> DEFAULT_HOSTS = ['localhost', '127.0.0.1', '::1', '[::1]'] as Set
    static final Set<String> SECRET_OPTIONS = ['api-key', 'idm-password', 'password', 'key'] as Set
    static final String USAGE = '''\
Usage: entitlementsaccelerator-loadtest --idm-url URL --service-url URL --allow-writes [options]
  (environment: LOADTEST_API_KEY, LOADTEST_IDM_PASSWORD; optionally LOADTEST_ALLOWED_HOSTS)

  --idm-url URL            IDM's REST base, e.g. http://localhost:8080/openidm
  --idm-user NAME          IDM admin user (default openidm-admin); the password comes from LOADTEST_IDM_PASSWORD
  --service-url URL        the service, as this tool reaches it, e.g. http://localhost:5276
  --idm-service-url URL    the service, as IDM reaches it (default: --service-url); with --configure only
  --allow-writes           required: the run creates, changes and deletes entities
  --allowed-hosts A,B      hosts besides localhost that may be addressed (or LOADTEST_ALLOWED_HOSTS)
  --configure              load managed objects, provisioner and mappings first (a disposable environment only)
  --library sources|jar    the library mode, for the provisioner's classpath with --configure (default sources)
  --idm-container NAME     IDM's Docker container, if local: its log is read for activations, 429s and warnings
  --users N                users, each with one account (default 1000)
  --changes M              users changed through implicit sync (default 200)
  --rate R                 changes per second to aim for (default 20)
  --threads T              client threads for seeding, changes and cleanup (default 8)
  --sample K               users whose relationships are read back one by one (default 100)
  --settle-seconds S       how long the service may take to show a change (default 180)
  --phases LIST            some of seed,initial,steady,sync,cleanup (default all)
  --keep                   skip cleanup: leave the run's entities in IDM and the service
  --run-id ID              lower-case letters, digits and dashes (default: the time)
  --user-mapping NAME      default managedUser_systemEntitlementsacceleratorUser
  --account-mapping NAME   default managedAccount_systemEntitlementsacceleratorAccount
  --connector NAME         the provisioner's name (default entitlementsaccelerator)
  --relationship-type T    the service's type for a user owning an account (default Owns)
  --api-key-header NAME    default X-API-KEY
  --out DIR                where report.md and report.json go (default build/loadtest/<run-id>)'''

    String idmUrl
    String idmUser = 'openidm-admin'
    char[] idmPassword
    String serviceUrl
    String idmServiceUrl
    String apiKeyHeader = 'X-API-KEY'
    char[] apiKey
    boolean allowWrites
    boolean configure
    boolean keep
    String library = 'sources'
    String idmContainer
    Set<String> allowedHosts = new LinkedHashSet<>(DEFAULT_HOSTS)
    int users = 1000
    int changes = 200
    double rate = 20
    int threads = 8
    int sample = 100
    int settleSeconds = 180
    List<String> phases = PHASES
    String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMdd-HHmmss'))
    String userMapping = 'managedUser_systemEntitlementsacceleratorUser'
    String accountMapping = 'managedAccount_systemEntitlementsacceleratorAccount'
    String connector = 'entitlementsaccelerator'
    String relationshipType = 'Owns'
    File out

    String getPrefix() { "lt-${runId}-".toString() }
    String user(int i) { "${prefix}u${i}".toString() }
    String account(int i) { "${prefix}a${i}".toString() }
    String getCanary() { "${prefix}canary".toString() }
    boolean runs(String phase) { phase in phases }

    /** The settings as the report shows them: never a secret. */
    Map<String, Object> describe() {
        [idmUrl: idmUrl, idmUser: idmUser, serviceUrl: serviceUrl, idmServiceUrl: idmServiceUrl, configure: configure,
         library: library, idmContainer: idmContainer, users: users, changes: changes, rate: rate, threads: threads,
         sample: sample, settleSeconds: settleSeconds, phases: phases, keep: keep, runId: runId, prefix: prefix,
         userMapping: userMapping, accountMapping: accountMapping, connector: connector, relationshipType: relationshipType,
         apiKeyHeader: apiKeyHeader] as Map<String, Object>
    }

    /** Parses and checks; IllegalArgumentException with a message fit for the user on any problem. */
    static LoadTestOptions parse(List<String> args, Map<String, String> env) {
        LoadTestOptions o = new LoadTestOptions()
        Map<String, String> values = [:]
        Set<String> flags = [] as Set
        for (int i = 0; i < args.size(); i++) {
            String a = args[i]
            if (!a.startsWith('--')) throw new IllegalArgumentException("unexpected argument '${a}'")
            String name = a.substring(2)
            String value = null
            if (name.contains('=')) { value = name.substring(name.indexOf('=') + 1); name = name.substring(0, name.indexOf('=')) }
            if (name in SECRET_OPTIONS) {
                throw new IllegalArgumentException("--${name} is refused: secrets come only from the environment (LOADTEST_API_KEY, LOADTEST_IDM_PASSWORD)")
            }
            if (name in ['allow-writes', 'configure', 'keep', 'help']) { flags << name; continue }
            if (value == null) {
                if (i + 1 >= args.size()) throw new IllegalArgumentException("--${name} needs a value")
                value = args[++i]
            }
            values[name] = value
        }
        if ('help' in flags) throw new IllegalArgumentException('')
        o.allowWrites = 'allow-writes' in flags
        o.configure = 'configure' in flags
        o.keep = 'keep' in flags

        values.each { String name, String value ->
            switch (name) {
                case 'idm-url': o.idmUrl = value.replaceAll('/+$', ''); break
                case 'idm-user': o.idmUser = value; break
                case 'service-url': o.serviceUrl = value.replaceAll('/+$', ''); break
                case 'idm-service-url': o.idmServiceUrl = value.replaceAll('/+$', ''); break
                case 'api-key-header': o.apiKeyHeader = value; break
                case 'allowed-hosts': o.allowedHosts.addAll(hosts(value)); break
                case 'library': o.library = value; break
                case 'idm-container': o.idmContainer = value; break
                case 'users': o.users = positive(name, value); break
                case 'changes': o.changes = positive(name, value, 0); break
                case 'rate': o.rate = positiveDecimal(name, value); break
                case 'threads': o.threads = positive(name, value); break
                case 'sample': o.sample = positive(name, value, 0); break
                case 'settle-seconds': o.settleSeconds = positive(name, value); break
                case 'phases': o.phases = value.split(',')*.trim().findAll { it } as List<String>; break
                case 'run-id': o.runId = value; break
                case 'user-mapping': o.userMapping = value; break
                case 'account-mapping': o.accountMapping = value; break
                case 'connector': o.connector = value; break
                case 'relationship-type': o.relationshipType = value; break
                case 'out': o.out = new File(value); break
                default: throw new IllegalArgumentException("unknown option --${name}")
            }
        }
        if (env.LOADTEST_ALLOWED_HOSTS) o.allowedHosts.addAll(hosts(env.LOADTEST_ALLOWED_HOSTS))
        o.apiKey = env.LOADTEST_API_KEY?.toCharArray()
        o.idmPassword = env.LOADTEST_IDM_PASSWORD?.toCharArray()
        o.idmServiceUrl = o.idmServiceUrl ?: o.serviceUrl
        if (o.out == null) o.out = new File("build/loadtest/${o.runId}")
        o.check()
        o
    }

    private void check() {
        if (!allowWrites) throw new IllegalArgumentException('--allow-writes is required: every run creates, changes and deletes entities in IDM and the service')
        if (!idmUrl) throw new IllegalArgumentException('--idm-url is required')
        if (!serviceUrl) throw new IllegalArgumentException('--service-url is required')
        if (!apiKey) throw new IllegalArgumentException('LOADTEST_API_KEY is not set: the service\'s API key, which the tool needs to read the service')
        if (!idmPassword) throw new IllegalArgumentException('LOADTEST_IDM_PASSWORD is not set: the password of the IDM admin user')
        if (!(runId ==~ /[a-z0-9][a-z0-9-]{0,39}/)) throw new IllegalArgumentException('--run-id must be 1 to 40 lower-case letters, digits and dashes')
        if (!(library in ['sources', 'jar'])) throw new IllegalArgumentException('--library must be sources or jar')
        if (!(apiKeyHeader ==~ /[A-Za-z0-9-]+/)) throw new IllegalArgumentException('--api-key-header must be a header name')
        List<String> unknown = phases.findAll { !(it in PHASES) }
        if (unknown || !phases) throw new IllegalArgumentException("--phases takes some of ${PHASES.join(',')}; not ${unknown.join(',')}")
        if (changes > users) throw new IllegalArgumentException('--changes cannot exceed --users')
        if (sample > users) throw new IllegalArgumentException('--sample cannot exceed --users')
        [['--idm-url', idmUrl], ['--service-url', serviceUrl], ['--idm-service-url', idmServiceUrl]].each { String opt, String url ->
            URI uri
            try { uri = new URI(url) } catch (URISyntaxException ignored) { throw new IllegalArgumentException("${opt} is not a valid URL") }
            if (!(uri.scheme in ['http', 'https']) || !uri.host) throw new IllegalArgumentException("${opt} must be an http or https URL with a host")
            if (uri.rawUserInfo != null) throw new IllegalArgumentException("${opt} must not contain credentials")
            if (!(uri.host.toLowerCase() in allowedHosts)) {
                throw new IllegalArgumentException("${opt} host '${uri.host}' is not allowed: add it with --allowed-hosts or LOADTEST_ALLOWED_HOSTS, " +
                    'and only for a test environment')
            }
        }
    }

    private static Set<String> hosts(String list) { list.split(',')*.trim()*.toLowerCase().findAll { it } as Set<String> }

    private static int positive(String name, String value, int min = 1) {
        if (!(value ==~ /\d+/) || (value as long) < min || (value as long) > 10_000_000) {
            throw new IllegalArgumentException("--${name} must be a whole number${min ? ' of at least ' + min : ''}")
        }
        value as int
    }

    private static double positiveDecimal(String name, String value) {
        if (!(value ==~ /\d+(\.\d+)?/) || (value as double) <= 0) throw new IllegalArgumentException("--${name} must be a positive number")
        value as double
    }
}
