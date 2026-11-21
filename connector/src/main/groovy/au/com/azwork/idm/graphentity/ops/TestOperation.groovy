package au.com.azwork.idm.graphentity.ops

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

    /** TODO WS3. */
    void execute() {
        throw new UnsupportedOperationException('TODO WS3: TestOperation.execute')
    }
}
