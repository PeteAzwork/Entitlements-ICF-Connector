package au.com.azwork.idm.graphentity.discovery

import groovy.transform.Canonical

@Canonical
class GenerationResult {
    /** The proposed objectTypes document (plain maps, JSON-ready). */
    Map<String, Map> objectTypes = [:]
    /** Non-fatal findings the operator must review (unresolved pairs, missing reverse, collisions, excluded props). */
    List<String> warnings = []
    /** Fatal findings: generation produced a configuration that would fail the §7 validation. */
    List<String> errors = []

    boolean isValid() { errors.empty }
}
