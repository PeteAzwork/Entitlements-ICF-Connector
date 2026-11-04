package au.com.azwork.idm.graphentity.probe

import groovy.transform.Canonical

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Canonical
class ProbeOptions {
    String baseUrl
    String username
    char[] password
    /** Write checks create, change and delete entities whose ID starts with {@link #getPrefix()}. Off by default. */
    boolean allowWrites = false
    int maxPages = 5
    int timeoutSeconds = 15
    /** Use this search path instead of trying the candidates. */
    String searchPath
    File outDir
    String runId = LocalDateTime.now().format(DateTimeFormatter.ofPattern('yyyyMMdd-HHmmss'))

    String getPrefix() { "ws0-probe-${runId}-".toString() }

    @Override
    String toString() {
        "ProbeOptions(baseUrl=${baseUrl}, username=${username}, allowWrites=${allowWrites}, maxPages=${maxPages}, runId=${runId})"
    }
}
