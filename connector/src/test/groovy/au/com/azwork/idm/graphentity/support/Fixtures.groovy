package au.com.azwork.idm.graphentity.support

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import au.com.azwork.idm.graphentity.config.Direction
import au.com.azwork.idm.graphentity.config.EntityKind
import au.com.azwork.idm.graphentity.config.ObjectTypeConfig
import au.com.azwork.idm.graphentity.config.RelationshipConfig
import au.com.azwork.idm.graphentity.model.Edge
import au.com.azwork.idm.graphentity.model.EntityDocument
import groovy.json.JsonOutput
import org.identityconnectors.framework.common.objects.Attribute
import org.identityconnectors.framework.common.objects.AttributeBuilder
import org.identityconnectors.framework.common.objects.Name
import org.identityconnectors.framework.common.objects.ObjectClass

/**
 * Shared fixtures mirroring the design document's configuration example (§6) and worked example (Appendix A).
 * Built directly (not via the parser) so fixtures stay usable while ObjectTypesParser is a stub.
 */
class Fixtures {

    static final ObjectClass USER = new ObjectClass('user')
    static final ObjectClass ACCOUNT = new ObjectClass('account')

    static final String SANNE = 'Sanne'
    static final String SANNE_ACCOUNT = 'Current-Account-1-Sanne'

    /** The §6 objectTypes document, extended with the discovery-emitted relatedType/reverseProperty hints. */
    static Map<String, Object> objectTypesMap() {
        [
            user: [
                idmResource      : 'managed/user',
                entityKind       : 'subject',
                idProperty       : 'userName',
                attributes       : ['name', 'mail', 'telephoneNumber'],
                archetypes       : ['Customer'],
                archetypeProperty: null,
                relationships    : [
                    owns: [remoteType: 'Owns', direction: 'outbound', relatedType: 'account', reverseProperty: 'ownedBy']
                ]
            ],
            account: [
                idmResource  : 'managed/account',
                entityKind   : 'resource',
                idProperty   : 'accountId',
                attributes   : ['name', 'productCode'],
                archetypes   : ['Account'],
                relationships: [
                    ownedBy: [remoteType: 'Owns', direction: 'inbound', relatedType: 'user', reverseProperty: 'owns']
                ]
            ]
        ]
    }

    static String objectTypesJson() { JsonOutput.toJson(objectTypesMap()) }

    static ObjectTypeConfig userType() {
        new ObjectTypeConfig(
            name: 'user', idmResource: 'managed/user', entityKind: EntityKind.SUBJECT, idProperty: 'userName',
            attributes: ['name', 'mail', 'telephoneNumber'], archetypes: ['Customer'],
            relationships: [owns: new RelationshipConfig(property: 'owns', remoteType: 'Owns', direction: Direction.OUTBOUND,
                relatedType: 'account', reverseProperty: 'ownedBy', explicit: true)])
    }

    static ObjectTypeConfig accountType() {
        new ObjectTypeConfig(
            name: 'account', idmResource: 'managed/account', entityKind: EntityKind.RESOURCE, idProperty: 'accountId',
            attributes: ['name', 'productCode'], archetypes: ['Account'],
            relationships: [ownedBy: new RelationshipConfig(property: 'ownedBy', remoteType: 'Owns', direction: Direction.INBOUND,
                relatedType: 'user', reverseProperty: 'owns', explicit: true)])
    }

    static ConnectorConfig connectorConfig(Map overrides = [:]) {
        def cfg = new ConnectorConfig(
            serviceAddress: 'http://localhost:5276', username: 'svc-idm-sync', password: 'secret'.toCharArray(),
            objectTypes: [user: userType(), account: accountType()],
            timestampSearchParam: 'updatedAfter', pageSizeDefault: 200, deleteMissingTolerated: true)
        overrides.each { k, v -> cfg."$k" = v }
        cfg
    }

    /** Flat property map as ScriptSupport would produce it from the harness configuration. */
    static Map<String, Object> connectorProps(Map overrides = [:]) {
        def m = [
            serviceAddress        : 'http://localhost:5276',
            username              : 'svc-idm-sync',
            password              : 'secret',
            objectTypes           : objectTypesJson(),
            timestampSearchParam  : 'updatedAfter',
            pageSizeDefault       : 200,
            deleteMissingTolerated: true
        ] as Map<String, Object>
        m.putAll(overrides)
        m
    }

    // ---- documents (Appendix A) ----

    static EntityDocument sanneDoc() {
        new EntityDocument(id: SANNE, baseType: 4, attributes: [ID: SANNE, name: 'Sanne Groen'],
            relationships: [Edge.outbound(SANNE_ACCOUNT, 'Owns')], directArchetypes: ['Customer'])
    }

    static Map<String, Object> sanneWire() {
        [ID: SANNE, BaseType: 4, attributes: [ID: SANNE, name: 'Sanne Groen'],
         Relationships: [[TargetID: SANNE_ACCOUNT, type: 'Owns']], directArchetypes: ['Customer']]
    }

    static Map<String, Object> accountWire() {
        [ID: SANNE_ACCOUNT, BaseType: 2, attributes: [ID: SANNE_ACCOUNT, name: 'Current Account', productCode: 'CA1'],
         Relationships: [[SourceID: SANNE, type: 'Owns']], directArchetypes: ['Account']]
    }

    // ---- ICF attribute sets ----

    static Set<Attribute> sanneAttributes(Map overrides = [:]) {
        def attrs = [
            __NAME__            : SANNE,
            name                : 'Sanne Groen',
            directArchetypes    : ['Customer'],
            entityRelationships : ['{"TargetID":"Current-Account-1-Sanne","type":"Owns"}']
        ]
        attrs.putAll(overrides)
        toAttributes(attrs)
    }

    static Set<Attribute> accountAttributes(Map overrides = [:]) {
        def attrs = [
            __NAME__            : SANNE_ACCOUNT,
            name                : 'Current Account',
            productCode         : 'CA1',
            directArchetypes    : ['Account'],
            entityRelationships : ['{"SourceID":"Sanne","type":"Owns"}']
        ]
        attrs.putAll(overrides)
        toAttributes(attrs)
    }

    /** Values that are Lists become multivalued attributes; null removes the key. */
    static Set<Attribute> toAttributes(Map<String, Object> values) {
        values.findAll { k, v -> v != null }.collect { k, v ->
            if (k == '__NAME__') return (Attribute) new Name(v as String)
            v instanceof Collection ? AttributeBuilder.build(k, v as Collection) : AttributeBuilder.build(k, v)
        } as Set<Attribute>
    }
}
