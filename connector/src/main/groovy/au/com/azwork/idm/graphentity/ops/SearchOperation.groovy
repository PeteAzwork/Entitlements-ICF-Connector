package au.com.azwork.idm.graphentity.ops

import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.SearchResult
import org.identityconnectors.framework.common.objects.filter.Filter

/**
 * Search / Get (design §9.4). The handler is the Groovy connector's {@code handler} closure; it is called
 * once per ConnectorObject. Returns the SearchResult carrying the paged-results cookie.
 */
class SearchOperation {
    private final OperationContext ctx

    SearchOperation(OperationContext ctx) { this.ctx = ctx }

    /** TODO WS4. */
    SearchResult execute(ObjectClass objectClass, Filter filter, Closure handler, OperationOptions options) {
        throw new UnsupportedOperationException('TODO WS4: SearchOperation.execute')
    }
}
