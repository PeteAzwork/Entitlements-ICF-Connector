package idm.entitlementsaccelerator.probe

import groovy.transform.Canonical

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Canonical
class ProbeOptions {
    static final String DEFAULT_API_KEY_HEADER = 'X-API-KEY'
    /** Every probe ID starts with this; each run adds its own run ID after it. */
    static final String NAMESPACE = 'ws0-probe-'

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

    /**
     * How long a read-back may wait for a write to show (WS0.15e): search lags behind writes (C5), and GET fails
     * while an entity is re-evaluated (C1). Zero means a single read.
     */
    int settleSeconds = 10
    /** Pause between read-back attempts within the settle window. */
    int pollMillis = 500
    /** With allowWrites: delete the entities earlier probe runs left behind (probe namespace only). */
    boolean removeLeftovers = false

    String getPrefix() { "${NAMESPACE}${runId}-".toString() }

    @Override
    String toString() {
        "ProbeOptions(baseUrl=${baseUrl}, apiKeyHeader=${apiKeyHeader}, allowWrites=${allowWrites}, maxPages=${maxPages}, settleSeconds=${settleSeconds}, runId=${runId})"
    }
}
