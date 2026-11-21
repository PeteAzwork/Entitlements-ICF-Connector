package au.com.azwork.idm.graphentity.discovery

/**
 * The code-review check design §8.4 and §15 ask for (WS6.9): every mapping that targets the connector must map
 * {@code entityRelationships} and {@code directArchetypes}, and neither may carry a {@code condition}. The service
 * replaces both in full on every write (P15), so a mapping that skips either, or sends it only sometimes, deletes
 * relationships or archetypes; the connector refuses such an update (P4), and this lint catches it before
 * deployment. A transform on either needs {@code "source": ""}, or IDM hands it an undefined source (found in IDM 7.5,
 * WS8). Accepts a whole sync.json ({@code {mappings: [...]}}) or a single mapping.
 */
class MappingLint {

    static final List<String> REQUIRED = ['entityRelationships', 'directArchetypes']

    /** Violations, one line each, naming the mapping and the property; empty when the document passes. */
    static List<String> check(Map document, String connectorName = 'graphentity') {
        List<Map> mappings = document?.mappings instanceof List ? document.mappings as List<Map> : (document?.target ? [document] : [])
        String prefix = "system/${connectorName}/"
        List<String> violations = []
        mappings.findAll { it?.target?.toString()?.startsWith(prefix) }.each { Map mapping ->
            String name = mapping.name ?: mapping.target
            List<Map> properties = mapping.properties instanceof List ? mapping.properties as List<Map> : []
            REQUIRED.each { String target ->
                List<Map> found = properties.findAll { it?.target == target }
                if (!found) {
                    violations << "${name}: no property mapping for ${target}; the service replaces it in full on every write, so leaving it out deletes it (P4, P15)".toString()
                }
                found.findAll { it.containsKey('condition') }.each {
                    violations << "${name}: the ${target} mapping has a condition; it must be sent on every update, or the service deletes what it omits (§8.4)".toString()
                }
                found.findAll { it.transform && it.source != '' }.each {
                    violations << "${name}: the ${target} mapping needs \"source\": \"\" so its transform receives the whole source object; without it IDM passes an undefined source".toString()
                }
            }
        }
        violations
    }
}
