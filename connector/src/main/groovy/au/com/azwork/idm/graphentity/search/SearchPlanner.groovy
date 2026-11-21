package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.filter.Filter

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

    /** TODO WS4. */
    static SearchPlan plan(Filter filter, OperationOptions options, ObjectTypeConfig type, ConnectorConfig config) {
        throw new UnsupportedOperationException('TODO WS4: SearchPlanner.plan')
    }
}
