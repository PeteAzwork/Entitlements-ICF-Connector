package au.com.azwork.idm.graphentity.ops

/**
 * Test (design §9.5): an authenticated search (POST /admin/entities/search, P14) with page size 1, where any 2xx
 * is healthy; then the manifest check of decision P22: every configured archetype must be defined in a manifest,
 * as a subject archetype for subject types and a resource archetype for resource types. A missing archetype fails
 * the test with a {@code ConfigurationException} naming it.
 */
class TestOperation {
    private final OperationContext ctx

    TestOperation(OperationContext ctx) { this.ctx = ctx }

    /** TODO WS3. */
    void execute() {
        throw new UnsupportedOperationException('TODO WS3: TestOperation.execute')
    }
}
