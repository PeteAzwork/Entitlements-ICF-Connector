package au.com.azwork.idm.graphentity.discovery

import groovy.json.JsonOutput
import groovy.json.JsonSlurper

/**
 * Command line (WS6.07–6.09). Reads managed.json from a file or, read-only, from IDM's REST interface; writes the
 * proposed objectTypes, the customConfiguration value to paste into the provisioner, and a mapping skeleton per
 * type; prints warnings, errors and the diff against the current provisioner. All logic is in the generator
 * classes; this class parses arguments and does file IO.
 *
 * <pre>
 * generate: --managed managed.json | --idm-url URL --idm-user USER   (password from IDM_DISCOVERY_PASSWORD)
 *           --types user,account [--answers answers.json] [--current provisioner.json] --out DIR
 * lint:     --lint sync.json [more.json ...] [--connector graphentity]
 * </pre>
 * Exit codes: 0 success; 1 generation or lint errors; 2 usage or input errors.
 */
class DiscoveryTool {

    static final int OK = 0
    static final int FAILED = 1
    static final int USAGE = 2

    static final String USAGE_TEXT = '''\
Usage:
  discovery --managed managed.json --types user,account [--answers answers.json] [--current provisioner.json] --out DIR
  discovery --idm-url https://idm.example.com --idm-user USER --types ... --out DIR   (password in IDM_DISCOVERY_PASSWORD)
  discovery --lint sync.json [more.json ...] [--connector graphentity]

answers.json holds the operator's answers (GeneratorOptions): selectedTypes, entityKinds, idProperties, archetypes,
relationshipOverrides, connectorName. --types overrides selectedTypes.'''

    static void main(String[] args) {
        System.exit(run(args as List<String>, System.out, System.err, System.getenv()))
    }

    static int run(List<String> args, PrintStream out, PrintStream err, Map<String, String> env) {
        Map<String, List<String>> opts
        try {
            opts = parse(args)
        } catch (IllegalArgumentException e) {
            err.println(e.message)
            err.println(USAGE_TEXT)
            return USAGE
        }
        try {
            opts.containsKey('lint') ? lint(opts, out, err) : generate(opts, out, err, env)
        } catch (IllegalArgumentException | IOException e) {
            err.println("error: ${e.message}")
            USAGE
        }
    }

    // ---------------------------------------------------------------- lint

    private static int lint(Map<String, List<String>> opts, PrintStream out, PrintStream err) {
        List<String> files = opts.lint
        if (!files) throw new IllegalArgumentException('--lint needs at least one mapping file')
        String connector = single(opts, 'connector') ?: 'graphentity'
        int violations = 0
        files.each { String path ->
            List<String> found = MappingLint.check(readJson(path) as Map, connector)
            found.each { err.println("${path}: ${it}") }
            violations += found.size()
        }
        out.println(violations ? "lint: ${violations} problem(s) in ${files.size()} file(s)" : "lint: ${files.size()} file(s) OK")
        violations ? FAILED : OK
    }

    // ---------------------------------------------------------------- generate

    private static int generate(Map<String, List<String>> opts, PrintStream out, PrintStream err, Map<String, String> env) {
        String outDir = single(opts, 'out')
        if (!outDir) throw new IllegalArgumentException('--out is required')
        String managedText
        if (single(opts, 'managed')) {
            managedText = new File(single(opts, 'managed')).getText('UTF-8')
        } else if (single(opts, 'idm-url')) {
            managedText = IdmManagedSource.fetch(single(opts, 'idm-url'), single(opts, 'idm-user'), env?.get(IdmManagedSource.PASSWORD_ENV))
        } else {
            throw new IllegalArgumentException('Give --managed FILE or --idm-url URL')
        }

        GeneratorOptions options = answers(single(opts, 'answers'))
        if (single(opts, 'types')) options.selectedTypes = single(opts, 'types').split(',')*.trim().findAll { it } as List<String>
        GenerationResult result = ObjectTypesGenerator.generate(ManagedSchemaReader.read(managedText), options)

        result.warnings.each { err.println("warning: ${it}") }
        result.errors.each { err.println("error: ${it}") }
        Map current = single(opts, 'current') ? currentObjectTypes(readJson(single(opts, 'current')) as Map) : null
        List<String> diff = DiffSummary.summarise(current, result.objectTypes)
        out.println(current == null ? 'Proposed objectTypes (no --current to compare with):' : 'Changes against the current provisioner:')
        (diff ?: ['  (no changes)']).each { out.println("  ${it}") }
        if (!result.valid) {
            err.println("Nothing written: fix the ${result.errors.size()} error(s) above, with --answers, and run again")
            return FAILED
        }

        File dir = new File(outDir)
        dir.mkdirs()
        new File(dir, 'objectTypes.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson(result.objectTypes)) + '\n', 'UTF-8')
        String custom = customConfiguration(single(opts, 'current') ? readJson(single(opts, 'current')) as Map : null, result.objectTypes)
        new File(dir, 'customConfiguration.txt').setText(custom + '\n', 'UTF-8')
        new File(dir, 'customConfiguration.json').setText(JsonOutput.prettyPrint(JsonOutput.toJson([customConfiguration: custom])) + '\n', 'UTF-8')
        result.objectTypes.each { String type, Map block ->
            new File(dir, "sync-${type}.json").setText(
                JsonOutput.prettyPrint(JsonOutput.toJson(MappingSkeletonGenerator.mapping(type, block, options))) + '\n', 'UTF-8')
        }
        out.println("Wrote objectTypes.json, customConfiguration.txt/.json and ${result.objectTypes.size()} mapping skeleton(s) to ${dir.path}")
        OK
    }

    /** GeneratorOptions from answers.json; absent file → defaults. */
    static GeneratorOptions answers(String path) {
        if (!path) return new GeneratorOptions()
        Map raw = readJson(path) as Map
        Set<String> known = ['selectedTypes', 'idProperties', 'entityKinds', 'archetypes', 'relationshipOverrides', 'connectorName'] as Set
        List<String> unknown = raw.keySet().findAll { !(it in known) } as List<String>
        if (unknown) throw new IllegalArgumentException("${path} has unknown key(s) ${unknown.join(', ')}; allowed: ${known.join(', ')}")
        GeneratorOptions options = new GeneratorOptions()
        raw.each { k, v -> options."$k" = v }
        options
    }

    /** objectTypes from a provisioner configuration: configurationProperties.objectTypes or customConfiguration. */
    static Map currentObjectTypes(Map provisioner) {
        Map props = provisioner?.configurationProperties as Map ?: [:]
        Object raw = props.objectTypes
        if (raw == null && props.customConfiguration) raw = new ConfigSlurper().parse(props.customConfiguration as String).objectTypes
        if (raw == null || (raw instanceof Map && raw.isEmpty())) return [:]
        raw instanceof Map ? raw as Map : new JsonSlurper().parseText(raw as String) as Map
    }

    /**
     * The provisioner's customConfiguration with objectTypes replaced by the proposal, other settings kept; just the
     * objectTypes entry when there is no current provisioner. Quotes and backslashes are escaped for Groovy.
     */
    static String customConfiguration(Map provisioner, Map objectTypes) {
        String json = JsonOutput.toJson(objectTypes).replace('\\', '\\\\').replace("'", "\\'")
        String entry = "objectTypes='${json}'"
        String current = provisioner?.configurationProperties?.customConfiguration as String
        if (!current?.trim()) return entry
        String replaced = current.replaceFirst(/objectTypes\s*=\s*'(?:[^'\\]|\\.)*'/, java.util.regex.Matcher.quoteReplacement(entry))
        replaced == current && !(current =~ /objectTypes\s*=/) ? "${current.trim().replaceAll(/;\s*$/, '')}; ${entry}" : replaced
    }

    // ---------------------------------------------------------------- arguments

    static final Set<String> VALUE_OPTIONS = ['managed', 'idm-url', 'idm-user', 'types', 'answers', 'current', 'out', 'connector'] as Set

    static Map<String, List<String>> parse(List<String> args) {
        Map<String, List<String>> opts = [:]
        String listOption = null
        for (int i = 0; i < args.size(); i++) {
            String a = args[i]
            if (a == '--idm-password' || a.startsWith('--idm-password=')) {
                throw new IllegalArgumentException("Passwords are never taken from arguments: set ${IdmManagedSource.PASSWORD_ENV}")
            }
            if (a == '--lint') { listOption = 'lint'; opts.lint = []; continue }
            if (a.startsWith('--')) {
                String name = a.substring(2)
                if (!(name in VALUE_OPTIONS)) throw new IllegalArgumentException("Unknown option ${a}")
                if (i + 1 >= args.size()) throw new IllegalArgumentException("${a} needs a value")
                opts[name] = [args[++i]]
                listOption = null
                continue
            }
            if (listOption) { opts[listOption] << a; continue }
            throw new IllegalArgumentException("Unexpected argument '${a}'")
        }
        if (!opts) throw new IllegalArgumentException('No options given')
        opts
    }

    private static String single(Map<String, List<String>> opts, String name) {
        opts[name] ? opts[name][0] : null
    }

    private static Object readJson(String path) {
        File f = new File(path)
        if (!f.isFile()) throw new IllegalArgumentException("${path} does not exist")
        try {
            new JsonSlurper().parse(f, 'UTF-8')
        } catch (groovy.json.JsonException e) {
            throw new IllegalArgumentException("${path} is not valid JSON: ${e.message}")
        }
    }
}
