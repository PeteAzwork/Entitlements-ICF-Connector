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

    /** The service's shared API key, as test data. Sent raw in the X-API-KEY header (decision P13). */
    static final String API_KEY = 'test-api-key'

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
            serviceAddress: 'http://localhost:5276', apiKey: API_KEY.toCharArray(),
            objectTypes: [user: userType(), account: accountType()],
            pageSizeDefault: 200, deleteMissingTolerated: true)
        overrides.each { k, v -> cfg."$k" = v }
        cfg
    }

    /** Flat property map as ScriptSupport would produce it from the harness configuration. */
    static Map<String, Object> connectorProps(Map overrides = [:]) {
        def m = [
            serviceAddress        : 'http://localhost:5276',
            apiKey                : API_KEY,
            objectTypes           : objectTypesJson(),
            pageSizeDefault       : 200,
            deleteMissingTolerated: true
        ] as Map<String, Object>
        m.putAll(overrides)
        m
    }

    // ---- Appendix A, in the service's real shapes (decisions P17, P18, P20) ----

    /** Sanne owns her current account. The same string encodes the relationship whichever end is written (P17). */
    static final String OWNS_JSON = '{"sourceID":"Sanne","targetID":"Current-Account-1-Sanne","type":"Owns"}'
    static final String EVALUATED = '2026-01-01T00:00:10Z'

    static Edge owns() { Edge.of(SANNE, SANNE_ACCOUNT, 'Owns') }

    static EntityDocument sanneDoc() {
        new EntityDocument(id: SANNE, baseType: 4, attributes: [name: 'Sanne Groen', mail: '', telephoneNumber: ''],
            relationships: [owns()], directArchetypes: ['Customer'])
    }

    /**
     * Write model (EntityDTO) for Sanne: a Subject, base type 4 (P18), with attributes.ID by design (C8). Her mail and
     * telephone number are empty in IDM, so they are sent as "" (P20 amended).
     */
    static Map<String, Object> sanneWire() {
        [id: SANNE, baseType: 4, attributes: [ID: SANNE, name: 'Sanne Groen', mail: '', telephoneNumber: ''],
         relationships: [[sourceID: SANNE, targetID: SANNE_ACCOUNT, type: 'Owns']], directArchetypes: ['Customer']]
    }

    /** Write model (EntityDTO) for the account: a Resource, base type 2 (P18), with attributes.ID (C8). */
    static Map<String, Object> accountWire() {
        [id: SANNE_ACCOUNT, baseType: 2, attributes: [ID: SANNE_ACCOUNT, name: 'Current Account', productCode: 'CA1'],
         relationships: [[sourceID: SANNE, targetID: SANNE_ACCOUNT, type: 'Owns']], directArchetypes: ['Account']]
    }

    /** Read model (Entity) for Sanne, as GET returns it. It carries no archetypes. */
    static Map<String, Object> sanneEntity() {
        [id: SANNE, lastEvaluated: EVALUATED, reconciliationStarted: null, baseType: 4,
         Attributes: [name: [name: 'name', type: 'string', value: 'Sanne Groen']], compoundAttributes: [:],
         relationships: [[source: SANNE, target: SANNE_ACCOUNT, type: 'Owns', sourceType: 'Subject']]]
    }

    /**
     * Search item for Sanne. It carries no relationships; the base type is a name. Her directly assigned archetype
     * is reported in isArchetypes, and directArchetypes is empty (C9, by design).
     */
    static Map<String, Object> sanneSearchItem() {
        [uid: SANNE, name: SANNE, baseType: 'Subject', attributes: [name: 'Sanne Groen'],
         isArchetypes: ['Customer'], directArchetypes: [], lastEvaluated: EVALUATED]
    }

    static Map<String, Object> accountSearchItem() {
        [uid: SANNE_ACCOUNT, name: SANNE_ACCOUNT, baseType: 'Resource', attributes: [name: 'Current Account', productCode: 'CA1'],
         isArchetypes: ['Account'], directArchetypes: [], lastEvaluated: EVALUATED]
    }

    /** A manifest defining the archetypes the fixture types use (P22). */
    static void seedManifest(FakeGraphService service) {
        service.seedManifest('test', ['Customer'], ['Account'])
    }

    // ---- ICF attribute sets ----

    static Set<Attribute> sanneAttributes(Map overrides = [:]) {
        def attrs = [
            __NAME__            : SANNE,
            name                : 'Sanne Groen',
            directArchetypes    : ['Customer'],
            entityRelationships : [OWNS_JSON]
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
            entityRelationships : [OWNS_JSON]
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
