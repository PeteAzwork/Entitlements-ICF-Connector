package au.com.azwork.idm.graphentity.probe

import groovy.transform.Canonical

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Canonical
class ProbeOptions {
    static final String DEFAULT_API_KEY_HEADER = 'X-API-KEY'

    String baseUrl
    /** Sent raw, as the value of {@link #apiKeyHeader}. */
    char[] apiKey
    String apiKeyHeader = DEFAULT_API_KEY_HEADER
    /** Write checks create, change and delete entities whose ID starts with {@link #getPrefix()}. Off by default. */
    boolean allowWrites = false
    int maxPages = 5
    int timeoutSeconds = 15
    /** Search endpoint, called with POST and a JSON body. Null means /admin/entities/search. */
    String searchPath
    /**
     * Two or more Resource archetypes for the archetype-replacement check (P22). Null means take them from the
     * manifests. They must be defined there, or the service may reject the writes.
     */
    List<String> archetypes
    File outDir
    String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMdd-HHmmss'))

    String getPrefix() { "ws0-probe-${runId}-".toString() }

    @Override
    String toString() {
        "ProbeOptions(baseUrl=${baseUrl}, apiKeyHeader=${apiKeyHeader}, allowWrites=${allowWrites}, maxPages=${maxPages}, runId=${runId})"
    }
}
