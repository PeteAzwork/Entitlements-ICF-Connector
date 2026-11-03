package au.com.azwork.idm.graphentity.discovery

import groovy.transform.Canonical

/** A managed object type as read from managed.json, reduced to what generation needs (design §12). */
@Canonical
class ManagedType {
    String name
    /** Scalar/complex property names → their schema fragment. Relationship properties are excluded here. */
    Map<String, Map> properties = [:]
    List<ManagedRelationship> relationships = []
    /** Candidate natural keys (e.g. properties with a unique index or userName-like names). */
    List<String> naturalKeyCandidates = []

    @Canonical
    static class ManagedRelationship {
        String property
        String reversePropertyName
        /** Managed resource names the property may reference, e.g. ['managed/account']. */
        List<String> resourceCollections = []
        boolean multiValued
    }
}
