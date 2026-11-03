package au.com.azwork.idm.graphentity.codec

import au.com.azwork.idm.graphentity.model.Edge

/**
 * Encodes edges as the compact JSON strings carried in the multivalued {@code entityRelationships}
 * ICF attribute (design §8.3), e.g. {@code {"TargetID":"Current-Account-1-Sanne","type":"Owns"}}.
 * Serialisation is canonical (fixed key order, no whitespace) so set comparison in recon is stable.
 */
class EdgeCodec {

    /** TODO WS2. Throws InvalidAttributeValueException on malformed input. */
    static Edge parse(String json) {
        throw new UnsupportedOperationException('TODO WS2: EdgeCodec.parse')
    }

    /** TODO WS2. */
    static String serialise(Edge edge) {
        throw new UnsupportedOperationException('TODO WS2: EdgeCodec.serialise')
    }

    /** Parses every value; null/empty collection → empty list. Duplicates are collapsed. TODO WS2. */
    static List<Edge> parseAll(Collection<?> values) {
        throw new UnsupportedOperationException('TODO WS2: EdgeCodec.parseAll')
    }

    /** Serialises in canonical sorted order. TODO WS2. */
    static List<String> serialiseAll(Collection<Edge> edges) {
        throw new UnsupportedOperationException('TODO WS2: EdgeCodec.serialiseAll')
    }
}
