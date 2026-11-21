package au.com.azwork.idm.graphentity.discovery

/**
 * Generates a {@code sync.json} mapping entry per type (design §12): managed/<type> → system/<connector>/<type>,
 * property mappings for every configured attribute, {@code entityRelationships}/{@code directArchetypes} bound
 * to the shared library transformations with no condition (§8.4), correlation on the idProperty, and conservative
 * situational policies: orphans (target-phase situations with no link) REPORT (P16; promotion to DELETE is WS10);
 * a deleted source deletes its linked target (SOURCE_MISSING) and a linked target that has gone is re-created
 * (MISSING). The shape follows {@code config/sync-mapping-user.sample.json}.
 *
 * <p>The two library transforms carry {@code "source": ""}: in IDM a property mapping without a source key gives its
 * transform an undefined {@code source}; with an empty one it gets the whole source object (found in WS8).
 */
class MappingSkeletonGenerator {

    static final String LIBRARY_PATH = 'lib/graphEntityPayload.js'
    static final String LIBRARY_MODULE = 'lib/graphEntityPayload'
    /**
     * Reconciliation threads per mapping: IDM's default is 10. 4 is the setting WS8.10 showed converging under
     * concurrent implicit sync; raise it only after measuring (WS8.11, runbook §4).
     */
    static final int TASK_THREADS = 4

    /** Every situation IDM can raise, with its policy. Nothing is left to IDM's defaults. */
    static final List<Map<String, String>> POLICIES = [
        [situation: 'CONFIRMED', action: 'UPDATE'],
        [situation: 'FOUND', action: 'UPDATE'],
        [situation: 'ABSENT', action: 'CREATE'],
        [situation: 'AMBIGUOUS', action: 'EXCEPTION'],
        // Source linked, target gone: re-create it (design §11). Found in IDM 7.5 by WS8.06.
        [situation: 'MISSING', action: 'CREATE'],
        [situation: 'UNQUALIFIED', action: 'REPORT'],
        [situation: 'UNASSIGNED', action: 'REPORT'],
        [situation: 'TARGET_IGNORED', action: 'REPORT'],
        [situation: 'SOURCE_IGNORED', action: 'REPORT'],
        // Target linked, source deleted in IDM: delete it (one-way, IDM authoritative). A deleted managed object raises
        // this situation, not MISSING (WS8.06), so REPORT here meant IDM deletes never reached the service.
        [situation: 'SOURCE_MISSING', action: 'DELETE'],
        [situation: 'FOUND_ALREADY_LINKED', action: 'EXCEPTION'],
        [situation: 'LINK_ONLY', action: 'EXCEPTION'],
        [situation: 'ALL_GONE', action: 'IGNORE']
    ].asImmutable()

    /**
     * @param reportOnly every situation's action is REPORT, so a reconciliation classifies without writing anything:
     *        the non-production dry run of runbook §8 (WS9.06). Implicit sync is off in that mode too.
     */
    static Map mapping(String typeName, Map objectType, GeneratorOptions options, boolean reportOnly = false) {
        String connector = options?.connectorName ?: 'graphentity'
        String idProperty = objectType.idProperty as String
        List properties = []
        properties << [source: idProperty, target: '__NAME__']
        (objectType.attributes ?: []).each { String attribute -> properties << [source: attribute, target: attribute] }
        properties << [source: '', target: 'directArchetypes', transform: library("lib.buildArchetypes(source, lib.loadTypeConfig('${typeName}'));")]
        properties << [source: '', target: 'entityRelationships', transform: library("lib.buildRelationships(source._id, lib.loadTypeConfig('${typeName}'));")]

        Map m = new LinkedHashMap()
        m.name = "managed${typeName.capitalize()}_system${connector.capitalize()}${typeName.capitalize()}".toString()
        m.source = objectType.idmResource ?: "managed/${typeName}".toString()
        m.target = "system/${connector}/${typeName}".toString()
        m.enableSync = true
        m.taskThreads = TASK_THREADS
        m.correlationQuery = [type: 'text/javascript', source: correlation(idProperty)]
        m.properties = properties
        m.policies = POLICIES.collect { new LinkedHashMap(it) + (reportOnly ? [action: 'REPORT'] : [:]) }
        if (reportOnly) m.enableSync = false
        m
    }

    private static Map library(String call) {
        [type: 'text/javascript', source: "var lib = require('${LIBRARY_MODULE}'); ${call}".toString()]
    }

    /** __NAME__ eq "<idProperty>", with backslashes and quotes in the value escaped so the filter stays well-formed. */
    private static String correlation(String idProperty) {
        "var v = String(source.${idProperty}).replace(/\\\\/g, '\\\\\\\\').replace(/\"/g, '\\\\\"'); " +
            "var qry = { '_queryFilter': '__NAME__ eq \"' + v + '\"' }; qry;"
    }
}
