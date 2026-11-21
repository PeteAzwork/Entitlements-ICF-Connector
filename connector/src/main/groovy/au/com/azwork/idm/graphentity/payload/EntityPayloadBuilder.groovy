package au.com.azwork.idm.graphentity.payload

import au.com.azwork.idm.graphentity.codec.EdgeCodec
import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.model.Edge
import au.com.azwork.idm.graphentity.model.EntityDocument
import org.identityconnectors.framework.common.exceptions.InvalidAttributeValueException
import org.identityconnectors.framework.common.objects.Attribute
import org.identityconnectors.framework.common.objects.Name
import org.identityconnectors.framework.common.objects.Uid

/**
 * Builds the total write-model document from the ICF attribute set (design §8.3, §8.4; decisions P15, P17–P20):
 * <ul>
 *   <li>{@code id} from the uid or name (A3). An id containing {@code /} is rejected: the service stores it but
 *       cannot address it by path, so it could never be updated or deleted (decision P23, Q09.1);</li>
 *   <li>{@code baseType} from the type's entityKind (P18);</li>
 *   <li>{@code attributes}: every configured attribute, as a string (P20). Numbers and booleans become their text;
 *       a multi-valued attribute is rejected. An attribute that is absent or empty in IDM is sent as {@code ""}: the
 *       service keeps an attribute an update omits, so this is how it is cleared (P20 amended, C17). {@code ID} is
 *       not a domain attribute: {@link EntityDocument#toWire} adds it (C8);</li>
 *   <li>{@code directArchetypes}: the type's static archetypes first (the first is the scope archetype, P19), then
 *       delivered ones, without duplicates;</li>
 *   <li>{@code relationships}: parsed from {@code entityRelationships} (P17). Every edge must name the entity as
 *       source or target.</li>
 * </ul>
 * The payload is a pure function of its inputs (§8.1): no remote read is ever performed here.
 */
class EntityPayloadBuilder {

    static final String ATTR_DIRECT_ARCHETYPES = 'directArchetypes'
    static final String ATTR_ENTITY_RELATIONSHIPS = 'entityRelationships'
    /** Read-only; ignored if delivered back on a write (e.g. after a decode-then-build round trip). */
    static final String ATTR_LAST_EVALUATED = 'lastEvaluated'

    /**
     * @param id the remote ID (from {@code Uid} on update, from {@code __NAME__}/{@code __UID__} on create)
     * @param type the resolved object type configuration
     * @param attributes the ICF attributes delivered by the sync engine
     * Throws InvalidAttributeValueException for attributes not in the configured list, a multi-valued
     * domain attribute, a malformed edge, an edge that does not involve the entity, a {@code __NAME__} or
     * {@code __UID__} that disagrees with the id (A3), or a blank id or one containing {@code /} (P23).
     */
    static EntityDocument build(String id, ObjectTypeConfig type, Set<Attribute> attributes) {
        String entityId = checkId(id, 'id')
        Map<String, Attribute> byName = new LinkedHashMap<>()
        (attributes ?: [] as Set).each { Attribute a -> byName[a.name] = a }

        [Name.NAME, Uid.NAME].each { String identity ->
            Attribute a = byName[identity]
            if (a != null) {
                String value = single(a, type)
                if (value != entityId) {
                    throw new InvalidAttributeValueException("${type.name}: ${identity} '${value}' does not match the entity id '${entityId}'; they must be equal (A3)")
                }
            }
        }

        Set<String> managed = [Name.NAME, Uid.NAME, ATTR_DIRECT_ARCHETYPES, ATTR_ENTITY_RELATIONSHIPS, ATTR_LAST_EVALUATED] as Set
        List<String> unknown = byName.keySet().findAll { !(it in managed) && !(it in type.attributes) }.sort()
        if (unknown) {
            throw new InvalidAttributeValueException("${type.name}: ${unknown.join(', ')} ${unknown.size() == 1 ? 'is' : 'are'} not configured attributes of this type; configured: ${type.attributes.join(', ') ?: 'none'}")
        }

        Map<String, String> domain = new LinkedHashMap<>()
        type.attributes.each { String name ->
            Attribute a = byName[name]
            domain[name] = a == null ? '' : (single(a, type) ?: '')
        }

        new EntityDocument(
            id: entityId,
            baseType: type.entityKind.baseType,
            attributes: domain,
            relationships: relationships(entityId, type, byName[ATTR_ENTITY_RELATIONSHIPS]),
            directArchetypes: archetypes(type, byName[ATTR_DIRECT_ARCHETYPES]))
    }

    /** Resolves the remote ID for a create from __NAME__ (preferred) or __UID__; rejects one containing {@code /} (P23). */
    static String idForCreate(Set<Attribute> attributes) {
        Attribute name = attributes?.find { it.name == Name.NAME }
        Attribute uid = attributes?.find { it.name == Uid.NAME }
        Attribute source = name?.value?.find { it != null } != null ? name : uid
        Object value = source?.value?.find { it != null }
        if (value == null || !value.toString().trim()) {
            throw new InvalidAttributeValueException('A create needs __NAME__ or __UID__ to give the entity its id (A3)')
        }
        checkId(value.toString(), source.name)
    }

    private static String checkId(String id, String what) {
        if (id == null || !id.trim()) throw new InvalidAttributeValueException("The entity ${what} is blank")
        if (id.contains('/')) {
            throw new InvalidAttributeValueException("The entity ${what} '${id}' contains '/', which the service stores but cannot address by path, so the entity could never be updated or deleted (P23)")
        }
        id
    }

    /** The single value of a domain attribute, as text (P20); null when it has none. */
    private static String single(Attribute a, ObjectTypeConfig type) {
        List<Object> values = (a.value ?: []).findAll { it != null }
        if (values.size() > 1) {
            throw new InvalidAttributeValueException("${type.name}: ${a.name} has ${values.size()} values, but the service's attributes are single-valued strings (P20)")
        }
        values ? values[0].toString() : null
    }

    private static List<Edge> relationships(String entityId, ObjectTypeConfig type, Attribute attr) {
        List<Edge> edges
        try {
            edges = EdgeCodec.parseAll(attr?.value ?: [])
        } catch (InvalidAttributeValueException e) {
            throw new InvalidAttributeValueException("${type.name} '${entityId}': ${e.message}")
        }
        Edge stranger = edges.find { !it.involves(entityId) }
        if (stranger != null) {
            throw new InvalidAttributeValueException("${type.name} '${entityId}': ${ATTR_ENTITY_RELATIONSHIPS} value ${EdgeCodec.serialise(stranger)} names neither end as '${entityId}'; every relationship must have the entity as source or target (P17)")
        }
        edges
    }

    private static List<String> archetypes(ObjectTypeConfig type, Attribute attr) {
        LinkedHashSet<String> out = new LinkedHashSet<>(type.archetypes ?: [])
        (attr?.value ?: []).findAll { it != null && it.toString().trim() }.each { out << it.toString().trim() }
        new ArrayList<String>(out)
    }
}
