package idm.entitlementsaccelerator.discovery

import idm.entitlementsaccelerator.config.ConnectorConfig
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import spock.lang.Specification
import spock.lang.TempDir

import java.nio.file.Path

/** WS6.07 — the command line, end to end, in a temporary directory. */
class DiscoveryToolSpec extends Specification {

    static final String MANAGED = new File('src/test/resources/managed-sample.json').absolutePath

    @TempDir Path tmp
    ByteArrayOutputStream outBytes = new ByteArrayOutputStream()
    ByteArrayOutputStream errBytes = new ByteArrayOutputStream()

    int run(List<String> args, Map<String, String> env = [:]) {
        DiscoveryTool.run(args, new PrintStream(outBytes, true, 'UTF-8'), new PrintStream(errBytes, true, 'UTF-8'), env)
    }
    String getOut() { outBytes.toString('UTF-8') }
    String getErr() { errBytes.toString('UTF-8') }

    File answers(Map content = defaultAnswers()) {
        File f = tmp.resolve('answers.json').toFile()
        f.text = JsonOutput.toJson(content)
        f
    }

    static Map defaultAnswers() {
        [entityKinds: [user: 'subject', account: 'resource'], archetypes: [user: ['Customer'], account: ['Account']],
         relationshipOverrides: [user: [manager: [remoteType: 'ReportsTo', direction: 'outbound'], reports: [remoteType: 'ReportsTo', direction: 'inbound']]]]
    }

    File outDir() { tmp.resolve('out').toFile() }

    def "generates objectTypes, the customConfiguration value and one mapping skeleton per type"() {
        when:
        int code = run(['--managed', MANAGED, '--types', 'user,account', '--answers', answers().path, '--out', outDir().path])

        then:
        code == DiscoveryTool.OK
        def objectTypes = new JsonSlurper().parse(new File(outDir(), 'objectTypes.json')) as Map
        objectTypes.keySet() == ['user', 'account'] as Set
        new File(outDir(), 'sync-user.json').isFile()
        new File(outDir(), 'sync-account.json').isFile()
        new JsonSlurper().parse(new File(outDir(), 'customConfiguration.json')).customConfiguration ==
            new File(outDir(), 'customConfiguration.txt').text.trim()
        out.contains('+ type user')
    }

    def "the customConfiguration value loads through the connector's own configuration path"() {
        given:
        run(['--managed', MANAGED, '--types', 'user,account', '--answers', answers().path, '--out', outDir().path])
        String custom = new File(outDir(), 'customConfiguration.txt').text.trim()

        when:
        def objectTypes = new ConfigSlurper().parse(custom).objectTypes
        def config = ConnectorConfig.from([serviceAddress: 'https://h', apiKey: 'k', objectTypes: objectTypes])

        then:
        config.objectTypes.keySet() == ['user', 'account'] as Set
    }

    def "the generated mappings pass the mapping lint"() {
        given:
        run(['--managed', MANAGED, '--types', 'user,account', '--answers', answers().path, '--out', outDir().path])

        expect:
        MappingLint.check(new JsonSlurper().parse(new File(outDir(), 'sync-user.json')) as Map) == []
    }

    def "with --current, the diff is against the provisioner and other customConfiguration settings are kept"() {
        given:
        File current = tmp.resolve('provisioner.json').toFile()
        current.text = JsonOutput.toJson([configurationProperties: [customConfiguration:
            "pageSizeDefault=50; objectTypes='${JsonOutput.toJson([user: [idmResource: 'managed/user', entityKind: 'subject', idProperty: 'userName', attributes: ['sn'], archetypes: ['Customer']]])}'"]])

        when:
        int code = run(['--managed', MANAGED, '--types', 'user,account', '--answers', answers().path, '--current', current.path, '--out', outDir().path])

        then:
        code == DiscoveryTool.OK
        out.contains('+ type account')
        out.contains('+ user.attributes: givenName')
        String custom = new File(outDir(), 'customConfiguration.txt').text
        custom.startsWith('pageSizeDefault=50; ')
        new ConfigSlurper().parse(custom).pageSizeDefault == 50
    }

    def "with --current, a complete provisioner is written: its customConfiguration replaced, objectTypes left to createFullConfig (WS7.03)"() {
        given:
        File current = new File('../config/provisioner.openicf-entitlementsaccelerator.json')

        when:
        int code = run(['--managed', MANAGED, '--types', 'user,account', '--answers', answers().path, '--current', current.path, '--out', outDir().path])

        then:
        code == DiscoveryTool.OK
        def written = new JsonSlurper().parse(new File(outDir(), 'provisioner.openicf-entitlementsaccelerator.json')) as Map
        def original = new JsonSlurper().parse(current) as Map
        written.connectorRef == original.connectorRef
        written.configurationProperties.classpath == original.configurationProperties.classpath
        written.configurationProperties.customConfiguration == new File(outDir(), 'customConfiguration.txt').text.trim()
        written.objectTypes.keySet() == ['_comment'] as Set
        out.contains('createFullConfig')
    }

    def "--report-only writes dry-run mappings: every situation REPORT, implicit sync off (WS9.06)"() {
        when:
        int code = run(['--managed', MANAGED, '--types', 'user,account', '--answers', answers().path, '--report-only', '--out', outDir().path])
        def mapping = new JsonSlurper().parse(new File(outDir(), 'sync-user.json')) as Map

        then:
        code == DiscoveryTool.OK
        mapping.policies.size() == 13
        mapping.policies.every { it.action == 'REPORT' }
        mapping.enableSync == false
        MappingLint.check(mapping) == []
    }

    def "generation errors exit 1 and write nothing"() {
        when:
        int code = run(['--managed', MANAGED, '--types', 'user,account', '--answers', answers([entityKinds: [user: 'subject']]).path, '--out', outDir().path])

        then:
        code == DiscoveryTool.FAILED
        err.contains('account')
        err.contains('entityKind')
        !outDir().exists()
    }

    def "warnings are printed, naming the type and property"() {
        when:
        run(['--managed', MANAGED, '--types', 'user,account', '--answers', answers().path, '--out', outDir().path])

        then:
        err.contains('warning: user.aliases')
        err.contains('orphanLink')
    }

    @spock.lang.Unroll
    def "usage errors exit 2: #why"() {
        expect:
        run(args) == DiscoveryTool.USAGE
        err

        where:
        why                    | args
        'no options'           | []
        'unknown option'       | ['--frobnicate', 'x']
        'no --out'             | ['--managed', MANAGED, '--types', 'user']
        'no source'            | ['--types', 'user', '--out', '/tmp/x']
        'missing managed file' | ['--managed', '/no/such/file.json', '--types', 'user', '--out', '/tmp/x']
    }

    def "an answers file with an unknown key exits 2 naming it, so a misspelt answer is not silently ignored"() {
        given:
        File bad = tmp.resolve('bad-answers.json').toFile()
        bad.text = '{"entityKind": {"user": "subject"}}'

        expect:
        run(['--managed', MANAGED, '--types', 'user', '--answers', bad.path, '--out', outDir().path]) == DiscoveryTool.USAGE
        err.contains('entityKind')
        err.contains('entityKinds')
    }

    def "a password is never accepted as an argument"() {
        expect:
        run(['--idm-url', 'https://idm', '--idm-user', 'u', '--idm-password', 'secret', '--types', 'user', '--out', '/tmp/x']) == DiscoveryTool.USAGE
        err.contains('IDM_DISCOVERY_PASSWORD')
        !err.contains('secret')
    }

    def "lint: a mapping without entityRelationships, or with a condition on directArchetypes, fails with exit 1"() {
        given:
        File bad = tmp.resolve('sync.json').toFile()
        bad.text = JsonOutput.toJson([mappings: [[name: 'm1', source: 'managed/user', target: 'system/entitlementsaccelerator/user',
            properties: [[source: '', target: 'directArchetypes', condition: 'false', transform: [type: 'text/javascript', source: 'x']]]]]])

        when:
        int code = run(['--lint', bad.path])

        then:
        code == DiscoveryTool.FAILED
        err.contains('m1: no property mapping for entityRelationships')
        err.contains('the directArchetypes mapping has a condition')
    }

    def "lint: the sample mapping in config/ passes"() {
        expect:
        run(['--lint', new File('../config/sync-mapping-user.sample.json').path]) == DiscoveryTool.OK
        out.contains('OK')
    }
}
