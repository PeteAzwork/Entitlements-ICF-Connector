package au.com.azwork.idm.graphentity.model

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString

/** The remote entity document (design §2.1). */
@EqualsAndHashCode
@ToString(includeNames = true)
class EntityDocument {
    String id
    Integer baseType
    Map<String, Object> attributes = [:]
    List<Edge> relationships = []
    List<String> directArchetypes = []
    /** Read-only, as returned by the service. Null on write. */
    String lastUpdated

    /** Wire form with the exact key casing of §2.1 ({@code ID}, {@code BaseType}, {@code Relationships}, ...). TODO WS2. */
    Map<String, Object> toWire() {
        throw new UnsupportedOperationException('TODO WS2: EntityDocument.toWire')
    }

    /** Parses a document returned by the service. Missing collections become empty. TODO WS2. */
    static EntityDocument fromWire(Map<String, Object> wire) {
        throw new UnsupportedOperationException('TODO WS2: EntityDocument.fromWire')
    }
}
