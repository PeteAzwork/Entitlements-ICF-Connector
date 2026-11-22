package au.com.azwork.idm.entitlementsaccelerator.codec

import au.com.azwork.idm.entitlementsaccelerator.model.Edge
import groovy.json.JsonException
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException

/**
 * Encodes edges as the compact JSON strings carried in the multivalued {@code entityRelationships} ICF attribute
 * (decision P17), e.g. {@code {"sourceID":"Sanne","targetID":"Current-Account-1-Sanne","type":"Owns"}}.
 * Serialisation is canonical (keys in that order, no whitespace) so set comparison in recon is stable, and the
 * same edge produces the same string whichever of its two entities is being written.
 */
class EdgeCodec {

    static final String ATTRIBUTE = 'entityRelationships'

    /** Compact output; non-ASCII kept as is rather than escaped, so the string is the same in every encoder. */
    private static final JsonGenerator JSON = new JsonGenerator.Options().disableUnicodeEscaping().build()

    /** Throws InvalidAttributeValueException on malformed input, naming the value. */
    static Edge parse(String json) {
        if (json == null || !json.trim()) throw invalid(json, 'it is empty')
        String text = json.trim()
        if (!text.startsWith('{')) throw invalid(json, 'it is not a JSON object')
        int end = endOfObject(text)
        if (end < 0) throw invalid(json, 'it is not a complete JSON object')
        if (end != text.length() - 1) throw invalid(json, 'there is text after the JSON object')
        Object decoded
        try {
            decoded = new JsonSlurper().parseText(text)
        } catch (JsonException | IllegalArgumentException e) {
            throw invalid(json, 'it is not valid JSON')
        }
        if (!(decoded instanceof Map)) throw invalid(json, 'it is not a JSON object')
        try {
            Edge.fromWire(decoded as Map<String, Object>)
        } catch (IllegalArgumentException e) {
            throw invalid(json, e.message)
        }
    }

    static String serialise(Edge edge) {
        if (edge == null) throw new IllegalArgumentException('edge is required')
        JSON.toJson(edge.toWire())
    }

    /** Parses every value; null/empty collection → empty list. Duplicates are collapsed, keeping first-seen order. */
    static List<Edge> parseAll(Collection<?> values) {
        if (!values) return []
        LinkedHashSet<Edge> edges = new LinkedHashSet<>()
        values.each { v ->
            if (v != null && !(v instanceof CharSequence)) throw invalid(v.toString(), 'it is not a string')
            edges << parse(v as String)
        }
        new ArrayList<Edge>(edges)
    }

    /** Serialises in canonical sorted order, without duplicates. */
    static List<String> serialiseAll(Collection<Edge> edges) {
        (edges ?: []).collect { serialise(it) }.unique().sort()
    }

    private static InvalidAttributeValueException invalid(String value, String why) {
        String shown = value == null ? 'null' : (value.length() > 200 ? value.substring(0, 200) + '…' : value)
        new InvalidAttributeValueException("${ATTRIBUTE} value '${shown}' is not a relationship {\"sourceID\",\"targetID\",\"type\"}: ${why}")
    }

    /**
     * Index of the brace that closes the object opening at index 0, honouring strings and escapes; -1 when it never
     * closes. JsonSlurper ignores text after the first value, so this is what rejects trailing input.
     */
    private static int endOfObject(String text) {
        int depth = 0
        boolean inString = false, escaped = false
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i)
            if (inString) {
                if (escaped) escaped = false
                else if (c == ('\\' as char)) escaped = true
                else if (c == ('"' as char)) inString = false
            } else if (c == ('"' as char)) {
                inString = true
            } else if (c == ('{' as char) || c == ('[' as char)) {
                depth++
            } else if (c == ('}' as char) || c == (']' as char)) {
                depth--
                if (depth == 0) return i
            }
        }
        -1
    }
}
