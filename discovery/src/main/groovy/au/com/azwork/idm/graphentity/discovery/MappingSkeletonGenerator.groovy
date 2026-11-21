package au.com.azwork.idm.graphentity.discovery

/**
 * Generates a {@code sync.json} mapping entry per type (design §12): managed/<type> → system/<connector>/<type>,
 * property mappings for every configured attribute, {@code entityRelationships}/{@code directArchetypes} bound
 * to the shared library transformations with no condition (§8.4), correlation on the idProperty, and conservative
 * situational policies: target-phase situations REPORT (P16; promotion to DELETE is WS10). The shape follows
 * {@code config/sync-mapping-user.sample.json}.
 */
class MappingSkeletonGenerator {

    static final String LIBRARY_PATH = 'lib/graphEntityPayload.js'
    static final String LIBRARY_MODULE = 'lib/graphEntityPayload'

    /** Every situation IDM can raise, with its policy. Nothing is left to IDM's defaults. */
    static final List<Map<String, String>> POLICIES = [
        [situation: 'CONFIRMED', action: 'UPDATE'],
        [situation: 'FOUND', action: 'UPDATE'],
        [situation: 'ABSENT', action: 'CREATE'],
        [situation: 'AMBIGUOUS', action: 'EXCEPTION'],
        [situation: 'MISSING', action: 'DELETE'],
        [situation: 'UNQUALIFIED', action: 'REPORT'],
        [situation: 'UNASSIGNED', action: 'REPORT'],
        [situation: 'TARGET_IGNORED', action: 'REPORT'],
        [situation: 'SOURCE_IGNORED', action: 'REPORT'],
        [situation: 'SOURCE_MISSING', action: 'REPORT'],
        [situation: 'FOUND_ALREADY_LINKED', action: 'EXCEPTION'],
        [situation: 'LINK_ONLY', action: 'EXCEPTION'],
        [situation: 'ALL_GONE', action: 'IGNORE']
    ].asImmutable()

    static Map mapping(String typeName, Map objectType, GeneratorOptions options) {
        String connector = options?.connectorName ?: 'graphentity'
        String idProperty = objectType.idProperty as String
        List properties = []
        properties << [source: idProperty, target: '__NAME__']
        (objectType.attributes ?: []).each { String attribute -> properties << [source: attribute, target: attribute] }
        properties << [target: 'directArchetypes', transform: library("lib.buildArchetypes(source, lib.loadTypeConfig('${typeName}'));")]
        properties << [target: 'entityRelationships', transform: library("lib.buildRelationships(source._id, lib.loadTypeConfig('${typeName}'));")]

        Map m = new LinkedHashMap()
        m.name = "managed${typeName.capitalize()}_system${connector.capitalize()}${typeName.capitalize()}".toString()
        m.source = objectType.idmResource ?: "managed/${typeName}".toString()
        m.target = "system/${connector}/${typeName}".toString()
        m.enableSync = true
        m.correlationQuery = [type: 'text/javascript', source: correlation(idProperty)]
        m.properties = properties
        m.policies = POLICIES.collect { new LinkedHashMap(it) }
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
