package au.com.azwork.idm.graphentity.loadtest

import au.com.azwork.idm.graphentity.discovery.DiscoveryTool
import au.com.azwork.idm.graphentity.discovery.GenerationResult
import au.com.azwork.idm.graphentity.discovery.GeneratorOptions
import au.com.azwork.idm.graphentity.discovery.ManagedSchemaReader
import au.com.azwork.idm.graphentity.discovery.MappingSkeletonGenerator
import au.com.azwork.idm.graphentity.discovery.ObjectTypesGenerator
import groovy.json.JsonSlurper

import java.nio.file.Files
import java.time.Instant

/**
 * {@code --configure}: sets up a disposable IDM for the load test the way the deployment guide does (§6–§9), with the
 * model the integration suite uses: IDM's managed.json plus {@code account} and the owns/ownedBy pair, with the
 * ID-change guard. It replaces {@code config/managed}, the provisioner and these two mappings, so it is for a test
 * environment only. The connector's files must already be in IDM ({@code connectors/graphentity}, {@code script/lib}).
 */
class IdmConfigurator {
    final LoadTestOptions o
    final IdmRest idm
    final IdmLog log   // null without --idm-container

    IdmConfigurator(LoadTestOptions o, IdmRest idm, IdmLog log) {
        this.o = o
        this.idm = idm
        this.log = log
    }

    /** Returns what it did, for the report. */
    Map<String, Object> configure() {
        String managedText = resource('managed.json')
        idm.put('config/managed', new JsonSlurper().parseText(managedText) as Map)

        File answersFile = Files.createTempFile('loadtest-answers', '.json').toFile()
        answersFile.deleteOnExit()
        answersFile.text = resource('answers.json')
        GeneratorOptions answers = DiscoveryTool.answers(answersFile.path)
        GenerationResult generated = ObjectTypesGenerator.generate(ManagedSchemaReader.read(managedText), answers)
        if (!generated.valid) throw new IllegalStateException("discovery failed on the load-test model: ${generated.errors}")

        Map provisioner = new JsonSlurper().parseText(resource('provisioner.openicf-graphentity.json')) as Map
        provisioner.remove('_comment')
        provisioner.name = o.connector
        provisioner.objectTypes = [:]
        Map props = provisioner.configurationProperties as Map
        props.serviceAddress = o.idmServiceUrl
        props.password = new String(o.apiKey)
        props.classpath = [o.library == 'jar'
            ? '&{idm.instance.dir}/connectors/graphentity/lib/graphentity-connector-lib.jar'
            : '&{idm.instance.dir}/connectors/graphentity/lib']
        props.customConfiguration = DiscoveryTool.customConfiguration(provisioner, generated.objectTypes)

        awaitSettled()   // createFullConfig alongside an initialisation in progress races (H11)
        Map full = idm.action('system', 'createFullConfig', provisioner)
        if (!(full.objectTypes as Map)?.keySet()?.containsAll(['user', 'account'])) {
            throw new IllegalStateException("createFullConfig returned no user/account object types; are the connector's files in IDM? " +
                "(${(full.objectTypes as Map)?.keySet()})")
        }
        int saves = putProvisioner(full)

        List<Map> ours = generated.objectTypes.collect { type, block -> MappingSkeletonGenerator.mapping(type as String, block as Map, answers) }
        List<Map> others = []
        try { others = (idm.get('config/sync').mappings as List<Map> ?: []).findAll { !(it.name in ours*.name) } } catch (IdmRest.IdmException ignored) { }
        idm.put('config/sync', [mappings: others + ours.collect { it + [enableSync: false] }])
        idm.awaitTrue(60, 'the load-test mappings to load') {
            List<String> names = (idm.get('sync/mappings?_queryFilter=true').result as List<Map> ?: [])*.name
            names.containsAll([o.userMapping, o.accountMapping])
        }
        [managed: 'replaced (IDM default + account)', provisioner: "saved after createFullConfig (${saves} save${saves > 1 ? 's' : ''})",
         mappings: ours*.name] as Map<String, Object>
    }

    /**
     * One provisioner save known to be in effect (H21: IDM discards a save made while it applies another). Each save
     * carries a marker, so it always changes the stored configuration; it is in effect when the stored marker is
     * ours and the connector has come back (the log's activation line with --idm-container, else three passing Tests).
     */
    int putProvisioner(Map config) {
        for (int attempt = 1; attempt <= 3; attempt++) {
            awaitSettled()
            String marker = "loadtest-${System.nanoTime()}".toString()
            Instant sent = Instant.now()
            idm.put("config/provisioner.openicf/${o.connector}", config + [_loadtestSave: marker])
            if (log) {
                idm.awaitTrue(180, 'the provisioner save to be applied') {
                    log.provisionerEvents(sent.minusSeconds(1), o.connector).any { it[1] == 'activated' } && log.settled(sent, o.connector)
                }
            } else {
                Thread.sleep(5000)
                int passes = 0
                idm.awaitTrue(180, 'the connector to pass its Test after the save') {
                    boolean ok = false
                    try { ok = idm.action("system/${o.connector}", 'test').ok == true } catch (Exception ignored) { }
                    passes = ok ? passes + 1 : 0
                    if (passes < 3) { Thread.sleep(2000); return false }
                    true
                }
            }
            if (idm.get("config/provisioner.openicf/${o.connector}")._loadtestSave == marker) return attempt
        }
        throw new IllegalStateException('IDM discarded the provisioner save three times (H21)')
    }

    private void awaitSettled() {
        if (log) idm.awaitTrue(180, 'IDM to finish applying provisioner changes') { log.settled(Instant.now().minusSeconds(300), o.connector) }
    }

    static String resource(String name) {
        InputStream in = IdmConfigurator.getResourceAsStream("/loadtest/${name}")
        if (in == null) throw new IllegalStateException("the load test's bundled ${name} is missing")
        in.getText('UTF-8')
    }
}
