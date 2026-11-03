package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.filter.Filter

/** Translates an ICF filter plus options into a {@link SearchPlan} (design §9.4). */
class SearchPlanner {

    static final String EPOCH = '1970-01-01T00:00:00Z'

    /**
     * Supported: null filter → ENUMERATE; EqualsFilter on __UID__/__NAME__ → BY_ID; GreaterThanFilter or
     * GreaterThanOrEqualFilter on lastUpdated → SINCE. Anything else → UnsupportedOperationException
     * (ICF reports the filter as unsupported rather than silently returning everything).
     * Page size comes from options, capped at ConnectorConfig.MAX_PAGE_SIZE, defaulting to pageSizeDefault.
     * TODO WS4.
     */
    static SearchPlan plan(Filter filter, OperationOptions options, ConnectorConfig config) {
        throw new UnsupportedOperationException('TODO WS4: SearchPlanner.plan')
    }
}
