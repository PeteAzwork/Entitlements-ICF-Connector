package au.com.azwork.idm.graphentity.discovery

import groovy.transform.Canonical

/** Operator-supplied answers for the prompts in design §12. */
@Canonical
class GeneratorOptions {
    /** Managed type names to generate for, e.g. ['user', 'account']. */
    List<String> selectedTypes = []
    /** type → idProperty. Missing entries use the generator's default (natural key, then userName-like). */
    Map<String, String> idProperties = [:]
    /** type → 'subject' | 'resource'. Required for every selected type. */
    Map<String, String> entityKinds = [:]
    /** type → static archetypes. Defaults to the capitalised type name. */
    Map<String, List<String>> archetypes = [:]
    /** type → property → explicit override map (remoteType/direction/sync). */
    Map<String, Map<String, Map>> relationshipOverrides = [:]
    /** Connector name used in system/<connector>/<type>. */
    String connectorName = 'graphentity'
}
