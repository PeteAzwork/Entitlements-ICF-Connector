package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import org.identityconnectors.framework.common.objects.Attribute
import org.identityconnectors.framework.common.objects.Name
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.Uid
import org.identityconnectors.framework.common.objects.filter.AndFilter
import org.identityconnectors.framework.common.objects.filter.AttributeFilter
import org.identityconnectors.framework.common.objects.filter.CompositeFilter
import org.identityconnectors.framework.common.objects.filter.ContainsFilter
import org.identityconnectors.framework.common.objects.filter.EqualsFilter
import org.identityconnectors.framework.common.objects.filter.Filter
import org.identityconnectors.framework.common.objects.filter.NotFilter
import org.identityconnectors.framework.common.objects.filter.OrFilter
import org.identityconnectors.framework.common.objects.filter.StartsWithFilter

/**
 * Translates an ICF filter plus options into a {@link SearchPlan} (design §9.4; decision P21).
 *
 * <ul>
 *   <li>null → ENUMERATE with no filter;</li>
 *   <li>EqualsFilter on __UID__ or __NAME__ → BY_ID;</li>
 *   <li>EqualsFilter, ContainsFilter and StartsWithFilter on a configured domain attribute → ENUMERATE with
 *       {@code {operation: "EQUALS"|"CONTAINS"|"STARTSWITH", left: <attribute>, right: <value>}};</li>
 *   <li>AndFilter / OrFilter of supported filters → {@code {operation: "AND"|"OR", left: <tree>, right: <tree>}};</li>
 *   <li>NotFilter of a supported filter → the same tree with {@code not: true};</li>
 *   <li>anything else (other filter types, filters on connector-managed attributes such as
 *       {@code entityRelationships}, {@code directArchetypes} or {@code lastEvaluated}, __UID__/__NAME__ inside a
 *       compound filter, multi-valued values) → UnsupportedOperationException with "filter" in the message (P7).
 *       Never a silent full enumeration.</li>
 * </ul>
 * Operations are sent by name, as the service's samples do. Page size comes from options, capped at
 * ConnectorConfig.MAX_PAGE_SIZE, defaulting to pageSizeDefault.
 */
class SearchPlanner {

    static SearchPlan plan(Filter filter, OperationOptions options, ObjectTypeConfig type, ConnectorConfig config) {
        int pageSize = pageSize(options, config)
        String id = idOf(filter)
        if (id != null) return new SearchPlan(kind: SearchPlan.Kind.BY_ID, id: id, pageSize: pageSize, cursor: new PageCursor())
        new SearchPlan(kind: SearchPlan.Kind.ENUMERATE, filter: filter == null ? null : translate(filter, type),
            pageSize: pageSize, cursor: PageCursor.decode(options?.pagedResultsCookie))
    }

    /** The id of a top-level EqualsFilter on __UID__ or __NAME__ with one value; null otherwise. */
    private static String idOf(Filter filter) {
        if (!(filter instanceof EqualsFilter)) return null
        Attribute a = (filter as EqualsFilter).attribute
        if (!(a.name in [Uid.NAME, Name.NAME])) return null
        Object value = single(a, filter)
        if (value == null || !value.toString()) throw unsupported(filter, 'a read by ID needs a non-empty value')
        value.toString()
    }

    private static int pageSize(OperationOptions options, ConnectorConfig config) {
        Integer requested = options?.pageSize
        requested != null && requested > 0 ? Math.min(requested, ConnectorConfig.MAX_PAGE_SIZE) : config.pageSizeDefault
    }

    /** The service's filter tree for {@code filter}, or UnsupportedOperationException naming what is unsupported. */
    private static Map<String, Object> translate(Filter filter, ObjectTypeConfig type) {
        if (filter instanceof AndFilter || filter instanceof OrFilter) {
            List<Filter> parts = new ArrayList<>((filter as CompositeFilter).filters)
            String op = filter instanceof AndFilter ? 'AND' : 'OR'
            return parts.collect { translate(it, type) }.inject { Map left, Map right -> node(op, left, right) } as Map<String, Object>
        }
        if (filter instanceof NotFilter) {
            Map<String, Object> inner = new LinkedHashMap<>(translate((filter as NotFilter).filter, type))
            inner.not = !(inner.not == true)
            return inner
        }
        String op = filter instanceof EqualsFilter ? 'EQUALS' : filter instanceof ContainsFilter ? 'CONTAINS'
            : filter instanceof StartsWithFilter ? 'STARTSWITH' : null
        if (op == null) throw unsupported(filter, 'the service supports equals, contains and starts-with, combined with and, or and not (P21)')
        Attribute a = (filter as AttributeFilter).attribute
        if (!(a.name in type.attributes)) {
            throw unsupported(filter, "only the configured attributes of ${type.name} (${type.attributes.join(', ') ?: 'none'}) can be filtered on; " +
                '__UID__ and __NAME__ only in a top-level equals, which reads by ID')
        }
        Object value = single(a, filter)
        if (value == null) throw unsupported(filter, 'a filter value is required')
        Map<String, Object> leaf = new LinkedHashMap<>()
        leaf.operation = op
        leaf.left = a.name
        leaf.right = value.toString()
        leaf
    }

    private static Map<String, Object> node(String op, Map<String, Object> left, Map<String, Object> right) {
        Map<String, Object> n = new LinkedHashMap<>()
        n.operation = op
        n.left = left
        n.right = right
        n
    }

    private static Object single(Attribute a, Filter filter) {
        List<Object> values = a.value ?: []
        if (values.size() > 1) throw unsupported(filter, 'a filter can test one value; the service\'s attributes are single-valued (P20)')
        values ? values[0] : null
    }

    private static UnsupportedOperationException unsupported(Filter filter, String why) {
        new UnsupportedOperationException("Search filter ${filter.class.simpleName}${filter instanceof AttributeFilter ? ' on ' + (filter as AttributeFilter).name : ''} is not supported: ${why}. " +
            'The connector never falls back to enumerating everything (P7)')
    }
}
