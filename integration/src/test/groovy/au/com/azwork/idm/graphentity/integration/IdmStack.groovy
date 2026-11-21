package au.com.azwork.idm.graphentity.integration

import au.com.azwork.idm.graphentity.discovery.DiscoveryTool
import au.com.azwork.idm.graphentity.discovery.GenerationResult
import au.com.azwork.idm.graphentity.discovery.GeneratorOptions
import au.com.azwork.idm.graphentity.discovery.ManagedSchemaReader
import au.com.azwork.idm.graphentity.discovery.MappingSkeletonGenerator
import au.com.azwork.idm.graphentity.discovery.ObjectTypesGenerator
import au.com.azwork.idm.graphentity.support.FakeGraphHttpServer
import au.com.azwork.idm.graphentity.support.FakeGraphService
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

import java.nio.file.Files

/**
 * WS8.01: one real IDM 7.5 container per test run, with the connector deployed the way the runbook deploys it,
 * talking to the in-memory service ({@link FakeGraphService} behind {@link FakeGraphHttpServer}) so specs can read the
 * service's state and the requests IDM's writes produced.
 *
 * <p>Deployment, as an operator does it:
 * <ol>
 *   <li>copy the scripts to {@code connectors/graphentity/scripts}, the library to {@code connectors/graphentity/lib}
 *       (as Groovy sources by default, or as a jar with {@code -PidmLibrary=jar}; see script_vs_jar.md) and the mapping
 *       library to {@code script/lib};</li>
 *   <li>generate {@code objectTypes} and mapping skeletons with the discovery tool from the fixture managed.json;</li>
 *   <li>load managed.json; POST the provisioner to {@code createFullConfig}, which adds the object types (IDM does not
 *       fill them in by itself), and save that once; then the mappings. Saving the provisioner before
 *       {@code createFullConfig} initialises the connector twice at once, which races in the connector bundle's class
 *       loader (a LinkageError on the first call).</li>
 * </ol>
 * IDM runs on the host network, so it reaches the fake on loopback and is reached on localhost:8080.
 */
@Singleton(lazy = true, strict = false)
class IdmStack {

    static final String CONTAINER = 'graphentity-idm-it'
    static final String API_KEY = 'it-test-key'
    static final File ROOT = new File(System.getProperty('repo.root', '..'))
    static final File FIXTURES = new File(ROOT, 'integration/src/test/resources/idm')
    static final String IMAGE = System.getProperty('idm.image', 'gcr.io/forgerock-io/idm:7.5.0')
    /** 'sources': the library as Groovy sources on the provisioner classpath; 'jar': as graphentity-connector-lib.jar. */
    static final String LIBRARY = System.getProperty('idm.library', 'sources')

    final FakeGraphService service = new FakeGraphService()
    final FakeGraphHttpServer server = new FakeGraphHttpServer(service, API_KEY).start()
    final IdmClient idm = new IdmClient('http://localhost:8080/openidm')
    final Map provisioner
    /** The provisioner as saved: {@link #provisioner} plus the object types from createFullConfig. */
    Map fullProvisioner
    final List<Map> mappings
    final GenerationResult generated

    private IdmStack() {
        GeneratorOptions answers = DiscoveryTool.answers(new File(FIXTURES, 'answers.json').path)
        generated = ObjectTypesGenerator.generate(ManagedSchemaReader.read(new File(FIXTURES, 'managed.json').getText('UTF-8')), answers)
        if (!generated.valid) throw new IllegalStateException("Discovery failed on the fixture: ${generated.errors}")
        mappings = generated.objectTypes.collect { type, block -> MappingSkeletonGenerator.mapping(type as String, block as Map, answers) }

        Map sample = new JsonSlurper().parse(new File(ROOT, 'config/provisioner.openicf-graphentity.json')) as Map
        sample.remove('_comment')
        sample.objectTypes = [:]
        sample.configurationProperties.serviceAddress = server.baseUrl
        sample.configurationProperties.classpath = [LIBRARY == 'jar'
            ? '&{idm.instance.dir}/connectors/graphentity/lib/graphentity-connector-lib.jar'
            : '&{idm.instance.dir}/connectors/graphentity/lib']
        sample.configurationProperties.password = API_KEY
        sample.configurationProperties.customConfiguration =
            DiscoveryTool.customConfiguration(sample, generated.objectTypes)
        provisioner = sample

        startContainer()
        Runtime.runtime.addShutdownHook(new Thread({ if (System.getProperty('idm.keep') != 'true') docker('rm', '-f', CONTAINER) }))
        idm.waitUntilReady(300)
        idm.put('config/managed', new JsonSlurper().parse(new File(FIXTURES, 'managed.json')) as Map)
        loadProvisioner(provisioner)
        setMappings(true)
    }

    /** createFullConfig first, then one save of its result, which carries the object types. */
    void loadProvisioner(Map config) {
        Map full = idm.action('system', 'createFullConfig', config)
        if (!(full.objectTypes as Map)?.keySet()?.containsAll(['user', 'account'])) {
            throw new IllegalStateException("createFullConfig returned no user/account object types: ${full.objectTypes?.keySet()}")
        }
        idm.put('config/provisioner.openicf/graphentity', full)
        fullProvisioner = full
        awaitConnector()
    }

    /** Saves a variant of the working provisioner as is, without createFullConfig (which runs the Test operation). */
    void saveProvisioner(Map changes) {
        Map props = (fullProvisioner.configurationProperties as Map) + (changes.configurationProperties as Map ?: [:])
        Map saved = fullProvisioner + changes + [configurationProperties: props]
        idm.put('config/provisioner.openicf/graphentity', saved)
        // IDM applies it asynchronously; wait until the stored configuration is the one in use.
        idm.awaitTrue(30, 'the provisioner change to apply') {
            (idm.get('config/provisioner.openicf/graphentity').configurationProperties as Map).customConfiguration == props.customConfiguration
        }
        Thread.sleep(3000)
    }

    /**
     * The generated mappings, with implicit sync on or off. IDM applies configuration asynchronously, so this waits
     * until the sync service reports the mappings with the requested setting.
     */
    void setMappings(boolean enableSync, Map<String, Map> extras = [:]) {
        List<Map> wanted = mappings.collect { new LinkedHashMap(it) + [enableSync: enableSync] + (extras[it.name] ?: [:]) }
        idm.put('config/sync', [mappings: wanted])
        idm.awaitTrue(30, "the mappings with enableSync=${enableSync}") {
            List<Map> active = idm.query('sync/mappings')
            wanted.every { w -> active.any { it.name == w.name && (it.enableSync == null ? true : it.enableSync) == enableSync } }
        }
        if (enableSync) {
            awaitImplicitSync()
        } else {
            Thread.sleep(5000)   // nothing observable proves implicit sync is off; give IDM time to apply it
        }
    }

    /**
     * sync/mappings reports a change before the managed object service acts on it, so implicit sync is proven live by a
     * canary: a user patched until its write reaches the service.
     */
    private void awaitImplicitSync() {
        String id = idm.query('managed/user', 'userName eq "it-canary"').find()?._id ?:
            idm.create('managed/user', [userName: 'it-canary', givenName: 'Canary', sn: 'Canary', mail: 'canary@example.com'])._id
        int round = 0
        idm.awaitTrue(90, 'implicit sync to reach the service') {
            String marker = "canary-${System.nanoTime()}-${round++}"
            idm.patch("managed/user/${id}", [[operation: 'replace', field: 'description', value: marker]])
            long until = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < until) {
                if (service.entities['it-canary']?.attributes?.description == marker) return true
                Thread.sleep(200)
            }
            false
        }
    }

    /** The connector passes its Test three times running, so a re-registration in progress is not mistaken for ready. */
    void awaitConnector() {
        int passes = 0
        idm.awaitTrue(60, 'the connector to pass its Test steadily') {
            boolean ok = false
            try { ok = idm.action('system/graphentity', 'test').ok == true } catch (Exception ignored) { }
            passes = ok ? passes + 1 : 0
            if (passes < 3) { Thread.sleep(1000); return false }
            true
        }
    }

    /** IDM's log since the container started. */
    String log() { docker('logs', CONTAINER) }

    private void startContainer() {
        docker('rm', '-f', CONTAINER)
        File stage = Files.createTempDirectory('graphentity-idm').toFile()
        File connector = new File(stage, 'graphentity')
        copyTree(new File(ROOT, 'connector/src/main/scripts'), new File(connector, 'scripts'))
        if (LIBRARY == 'jar') {
            File jar = new File(System.getProperty('library.jar', ''))
            if (!jar.isFile()) throw new IllegalStateException("The connector library jar is missing (${jar}); run through Gradle, which builds it")
            new File(connector, 'lib').mkdirs()
            new File(connector, 'lib/graphentity-connector-lib.jar').bytes = jar.bytes
        } else {
            copyTree(new File(ROOT, 'connector/src/main/groovy'), new File(connector, 'lib'))
        }
        File lib = new File(stage, 'lib')
        copyTree(new File(ROOT, 'mapping-lib/src/lib'), lib)
        String cpus = System.getProperty('idm.cpus', '')
        List<String> create = ['create', '--name', CONTAINER, '--network', 'host',
                               '-e', "JAVA_OPTS=${System.getProperty('idm.javaOpts', '-Xmx2g -Xms1g')}".toString()]
        if (cpus) create += ['--cpus', cpus]
        dockerChecked(*(create + [IMAGE]))
        dockerChecked('cp', connector.path, "${CONTAINER}:/opt/openidm/connectors/graphentity")
        dockerChecked('cp', lib.path + '/.', "${CONTAINER}:/opt/openidm/script/lib/")
        dockerChecked('start', CONTAINER)
        stage.deleteDir()
    }

    private static void copyTree(File from, File to) {
        from.eachFileRecurse { File f ->
            if (f.isFile()) {
                File target = new File(to, from.toPath().relativize(f.toPath()).toString())
                target.parentFile.mkdirs()
                target.bytes = f.bytes
            }
        }
    }

    static String docker(String... args) {
        Process p = new ProcessBuilder(['docker'] + (args as List)).redirectErrorStream(true).start()
        String out = p.inputStream.getText('UTF-8')
        p.waitFor()
        out
    }

    private static String dockerChecked(String... args) {
        Process p = new ProcessBuilder(['docker'] + (args as List)).redirectErrorStream(true).start()
        String out = p.inputStream.getText('UTF-8')
        if (p.waitFor() != 0) throw new IllegalStateException("docker ${args.join(' ')} failed: ${out}")
        out
    }
}
