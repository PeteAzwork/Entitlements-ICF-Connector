package idm.entitlementsaccelerator.ops

import idm.entitlementsaccelerator.config.ConnectorConfig
import idm.entitlementsaccelerator.config.ObjectTypeConfig
import idm.entitlementsaccelerator.http.HttpErrorMapper
import idm.entitlementsaccelerator.http.HttpResponse
import idm.entitlementsaccelerator.http.OpContext
import idm.entitlementsaccelerator.model.EntityDocument
import idm.entitlementsaccelerator.payload.EntityDecoder
import idm.entitlementsaccelerator.search.PageCursor
import idm.entitlementsaccelerator.search.SearchPlan
import idm.entitlementsaccelerator.search.SearchPlanner
import idm.entitlementsaccelerator.search.TypeScoper
import org.identityconnectors.framework.common.exceptions.ConnectorIOException
import org.identityconnectors.framework.common.objects.ConnectorObject
import org.identityconnectors.framework.common.objects.ResultsHandler

import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.SearchResult
import org.identityconnectors.framework.common.objects.filter.Filter

/**
 * Search / Get (design §9.4; decisions P19, P21). A read by ID uses GET or, with {@code byIdLookup: search}, a
 * search with an EQUALS filter on the ID; it checks only the base type. Enumeration posts the type's
 * {@code objectClass} and filter tree, follows {@code pagedResultsCookie}, and keeps only entities carrying the
 * type's scope archetype. The handler is the Groovy connector's {@code handler} closure; it is called once per
 * ConnectorObject. Returns the SearchResult carrying the paged-results cookie.
 */
class SearchOperation {
    private final OperationContext ctx

    SearchOperation(OperationContext ctx) { this.ctx = ctx }

    /**
     * @param handler the toolkit's {@code handler}: a Closure or an ICF ResultsHandler. Only an explicit
     *        {@code false} stops the search.
     */
    SearchResult execute(ObjectClass objectClass, Filter filter, Object handler, OperationOptions options) {
        ObjectTypeConfig type = ObjectClassResolver.resolve(objectClass, ctx.config)
        SearchPlan plan = SearchPlanner.plan(filter, options, type, ctx.config)
        if (plan.kind == SearchPlan.Kind.BY_ID) {
            byId(plan.id, type, objectClass, handler, plan.pageSize)
            return new SearchResult()
        }
        Integer callerPageSize = options?.pageSize != null && options.pageSize > 0 ? options.pageSize : null
        callerPageSize != null ? onePage(plan, type, objectClass, handler) : allPages(plan, type, objectClass, handler)
    }

    // ---------------------------------------------------------------- by ID

    private void byId(String id, ObjectTypeConfig type, ObjectClass objectClass, Object handler, int pageSize) {
        if (ctx.config.byIdLookup == ConnectorConfig.ByIdLookup.SEARCH) {
            Map<String, Object> filter = [operation: 'EQUALS', left: EntityDocument.ID_ATTRIBUTE, right: id] as Map<String, Object>
            HttpResponse response = Transport.call { ctx.api.search(query(type, filter, [pageSize: pageSize])) }
            if (HttpErrorMapper.raise(response, OpContext.SEARCH, ctx.config)) return
            EntityDocument match = items(response).findResult { Map item ->
                EntityDocument doc = readable(item)
                doc?.id == id ? doc : null
            }
            if (match != null && TypeScoper.inByIdScope(match, type)) deliver(handler, EntityDecoder.decode(match, type, objectClass))
            return
        }
        HttpResponse response = Transport.call { ctx.api.get(id) }
        if (HttpErrorMapper.raise(response, OpContext.GET, ctx.config)) return   // 404: absent
        if (!(response.body instanceof Map)) {
            throw new ConnectorIOException("GET of ${type.name} '${id}' returned HTTP ${response.status} without an entity")
        }
        EntityDocument doc = EntityDocument.fromEntity(response.body as Map<String, Object>)
        if (TypeScoper.inByIdScope(doc, type)) deliver(handler, EntityDecoder.decode(doc, type, objectClass))
    }

    // ---------------------------------------------------------------- enumeration

    /** The caller pages: one service page, returned with an ICF cookie for the next one. */
    private SearchResult onePage(SearchPlan plan, ObjectTypeConfig type, ObjectClass objectClass, Object handler) {
        HttpResponse response = fetch(plan, type, plan.cursor)
        if (response == null) return new SearchResult()
        List<Map> page = items(response)
        process(page, type, objectClass, handler)
        PageCursor next = PageCursor.next(response)
        int seen = plan.cursor.consumed + page.size()
        int total = PageCursor.totalResults(response)
        int remaining = total >= 0 ? Math.max(0, total - seen) : -1
        if (next == null) return new SearchResult(null, total >= 0 ? 0 : -1)
        next.consumed = seen
        new SearchResult(next.encode(), remaining)
    }

    /** No caller page size: stream every page through the handler, holding one page at a time. */
    private SearchResult allPages(SearchPlan plan, ObjectTypeConfig type, ObjectClass objectClass, Object handler) {
        PageCursor cursor = plan.cursor
        while (cursor != null) {
            HttpResponse response = fetch(plan, type, cursor)
            if (response == null) break
            if (!process(items(response), type, objectClass, handler)) break
            PageCursor next = PageCursor.next(response)
            if (next != null && next.serviceCookie == cursor.serviceCookie) {
                Logs.warn(ctx.log, "search of ${type.name} stopped: the service returned the same page cookie twice")
                break
            }
            cursor = next
        }
        new SearchResult()
    }

    /** One search page; null when the service answers 404 (nothing to search). */
    private HttpResponse fetch(SearchPlan plan, ObjectTypeConfig type, PageCursor cursor) {
        HttpResponse response = Transport.call { ctx.api.search(query(type, plan.filter, cursor.toQuery(plan.pageSize))) }
        HttpErrorMapper.raise(response, OpContext.SEARCH, ctx.config) ? null : response
    }

    /** Decodes, scopes and delivers one page. Returns false when the handler asked to stop. */
    private boolean process(List<Map> page, ObjectTypeConfig type, ObjectClass objectClass, Object handler) {
        Set<String> delivered = new HashSet<>()   // the service can repeat an item within a page (C14)
        for (Map item : page) {
            EntityDocument doc = readable(item)
            if (doc == null || !TypeScoper.inEnumerationScope(doc, type) || !delivered.add(doc.id)) continue
            if (!deliver(handler, EntityDecoder.decode(doc, type, objectClass))) return false
        }
        true
    }

    /** The search item as a document, or null (with one WARN, no attribute values) when it cannot be read. */
    private EntityDocument readable(Map item) {
        try {
            EntityDocument.fromSearchItem(item as Map<String, Object>)
        } catch (IllegalArgumentException e) {
            Logs.warn(ctx.log, "skipped a search result the connector cannot read: ${e.message}")
            null
        }
    }

    private static Map<String, Object> query(ObjectTypeConfig type, Map<String, Object> filter, Map<String, Object> paging) {
        Map<String, Object> q = new LinkedHashMap<>()
        q.objectClass = type.entityKind.baseType
        if (filter != null) q.filter = filter
        q.putAll(paging)
        q
    }

    private static List<Map> items(HttpResponse response) {
        Object results = response.body instanceof Map ? (response.body as Map).results : null
        results instanceof Collection ? (results as Collection).findAll { it instanceof Map } as List<Map> : []
    }

    private static boolean deliver(Object handler, ConnectorObject object) {
        Object more = handler instanceof ResultsHandler ? (handler as ResultsHandler).handle(object) : (handler as Closure).call(object)
        more != Boolean.FALSE
    }
}
