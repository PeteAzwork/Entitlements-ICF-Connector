package au.com.azwork.idm.graphentity.discovery

/**
 * Diff-style summary between the live {@code objectTypes} configuration and a freshly generated proposal
 * (design §12: schema evolution is regenerate-plus-review). Lines start with {@code +} (added), {@code -} (removed)
 * or {@code ~} (changed, showing old and new). Attribute and archetype lists compare item by item; archetype order
 * is also compared, because the first archetype is the scope archetype (P19). Relationships compare per property.
 */
class DiffSummary {

    static final List<String> SCALARS = ['idmResource', 'entityKind', 'idProperty', 'archetypeProperty']
    static final List<String> LISTS = ['attributes', 'archetypes']

    static List<String> summarise(Map current, Map proposed) {
        Map before = current ?: [:]
        Map after = proposed ?: [:]
        List<String> lines = []
        (before.keySet() + after.keySet()).unique().each { Object key ->
            String type = key as String
            if (!before.containsKey(key)) {
                lines << "+ type ${type}".toString()
                lines.addAll(describe(type, after[key] as Map, '+'))
            } else if (!after.containsKey(key)) {
                lines << "- type ${type}".toString()
            } else {
                lines.addAll(compare(type, before[key] as Map ?: [:], after[key] as Map ?: [:]))
            }
        }
        lines
    }

    private static List<String> describe(String type, Map block, String sign) {
        List<String> lines = []
        (block ?: [:]).each { k, v ->
            if (k == 'relationships') {
                (v as Map ?: [:]).each { p, entry -> lines << "${sign} ${type}.relationships.${p}: ${entry}".toString() }
            } else {
                lines << "${sign} ${type}.${k}: ${v}".toString()
            }
        }
        lines
    }

    private static List<String> compare(String type, Map before, Map after) {
        List<String> lines = []
        SCALARS.each { String field ->
            Object a = before[field], b = after[field]
            if (a == b) return
            if (a == null) lines << "+ ${type}.${field}: ${b}".toString()
            else if (b == null) lines << "- ${type}.${field}: ${a}".toString()
            else lines << "~ ${type}.${field}: ${a} → ${b}".toString()
        }
        LISTS.each { String field ->
            List a = before[field] as List ?: [], b = after[field] as List ?: []
            b.findAll { !(it in a) }.each { lines << "+ ${type}.${field}: ${it}".toString() }
            a.findAll { !(it in b) }.each { lines << "- ${type}.${field}: ${it}".toString() }
            if (field == 'archetypes' && a && b && a != b && (a as Set) == (b as Set)) {
                lines << "~ ${type}.archetypes order: ${a} → ${b} (the first is the scope archetype, P19)".toString()
            } else if (field == 'archetypes' && a && b && a[0] != b[0] && (a[0] in b) && (b[0] in a)) {
                lines << "~ ${type}.archetypes scope archetype: ${a[0]} → ${b[0]} (P19)".toString()
            }
        }
        Map ra = before.relationships as Map ?: [:], rb = after.relationships as Map ?: [:]
        (ra.keySet() + rb.keySet()).unique().each { Object p ->
            if (!ra.containsKey(p)) lines << "+ ${type}.relationships.${p}: ${rb[p]}".toString()
            else if (!rb.containsKey(p)) lines << "- ${type}.relationships.${p}: ${ra[p]}".toString()
            else if (ra[p] != rb[p]) lines << "~ ${type}.relationships.${p}: ${ra[p]} → ${rb[p]}".toString()
        }
        lines
    }
}
