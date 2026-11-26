package idm.entitlementsaccelerator.ops

import idm.entitlementsaccelerator.config.ObjectTypeConfig
import idm.entitlementsaccelerator.http.HttpErrorMapper
import idm.entitlementsaccelerator.http.HttpResponse
import idm.entitlementsaccelerator.http.OpContext
import idm.entitlementsaccelerator.config.EntityKind
/**
 * Test (design §9.5): an authenticated search (POST /admin/entities/search, P14) with page size 1, where any 2xx
 * is healthy; then the manifest check of decision P22, as amended 2026-11-21: every configured archetype should be
 * defined in a manifest, as a subject archetype for subject types and a resource archetype for resource types. A
 * missing archetype, or a service with no manifests, is logged at WARN naming it; it does not fail the test, because
 * the service accepts archetype names no manifest defines and existing data uses such names (C16). Archetype names
 * are read from any {@code subjectArchetypes}/{@code resourceArchetypes} list in the response, which is an export
 * bundle rather than the documented list (C11).
 */
class TestOperation {
    private final OperationContext ctx

    TestOperation(OperationContext ctx) { this.ctx = ctx }

    void execute() {
        HttpResponse search = Transport.call { ctx.api.search([pageSize: 1]) }
        HttpErrorMapper.raise(search, OpContext.TEST, ctx.config)
        checkManifests()
        null
    }

    private void checkManifests() {
        HttpResponse response = Transport.call { ctx.api.manifests() }
        if (response.status == 404) {
            Logs.warn(ctx.log, 'The service has no manifests, so no configured archetype could be checked (P22)')
            return
        }
        HttpErrorMapper.raise(response, OpContext.TEST, ctx.config)
        Map<String, Set<String>> defined = [subjectArchetypes: new HashSet<String>(), resourceArchetypes: new HashSet<String>()]
        collect(response.body, defined)
        ctx.config.objectTypes.values().each { ObjectTypeConfig type ->
            String key = type.entityKind == EntityKind.SUBJECT ? 'subjectArchetypes' : 'resourceArchetypes'
            String kind = type.entityKind == EntityKind.SUBJECT ? 'subject' : 'resource'
            type.archetypes.each { String archetype ->
                if (!(archetype in defined[key])) {
                    Logs.warn(ctx.log, "Archetype '${archetype}' of ${type.name} is not defined as a ${kind} archetype in any manifest; the service accepts it, but no policy will refer to it (P22, C16)")
                }
            }
        }
    }

    /** Walks the response, list or export bundle alike (C11), collecting archetype names (strings or {name}). */
    private static void collect(Object node, Map<String, Set<String>> defined) {
        if (node instanceof Map) {
            (node as Map).each { k, v ->
                if (defined.containsKey(k as String) && v instanceof Collection) {
                    (v as Collection).each { a ->
                        Object name = a instanceof Map ? (a as Map).name : a
                        if (name != null) defined[k as String] << name.toString()
                    }
                } else {
                    collect(v, defined)
                }
            }
        } else if (node instanceof Collection) {
            (node as Collection).each { collect(it, defined) }
        }
    }
}
