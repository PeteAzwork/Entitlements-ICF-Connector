package au.com.azwork.idm.graphentity.config

import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConfigurationException
import spock.lang.Specification
import spock.lang.Unroll

/** Design §6: whole-connector configuration loading and defaults. */
class ConnectorConfigSpec extends Specification {

    def "loads the full property set"() {
        when:
        def cfg = ConnectorConfig.from(Fixtures.connectorProps())

        then:
        cfg.serviceAddress == 'http://localhost:5276'
        cfg.username == 'svc-idm-sync'
        String.valueOf(cfg.password) == 'secret'
        cfg.objectTypes.keySet() == ['user', 'account'] as Set
        cfg.timestampSearchParam == 'updatedAfter'
        cfg.pageSizeDefault == 200
        cfg.deleteMissingTolerated
        !cfg.rePutOnDroppedEdges
    }

    def "objectTypes may be given as a JSON string or as a decoded map"() {
        expect:
        ConnectorConfig.from(Fixtures.connectorProps(objectTypes: Fixtures.objectTypesMap())).objectTypes ==
            ConnectorConfig.from(Fixtures.connectorProps()).objectTypes
    }

    @Unroll
    def "optional '#key' defaults to #expected when absent"() {
        given:
        def props = Fixtures.connectorProps()
        props.remove(key)

        expect:
        ConnectorConfig.from(props)."$key" == expected

        where:
        key                      | expected
        'timestampSearchParam'   | 'updatedAfter'
        'pageSizeDefault'        | 200
        'deleteMissingTolerated' | true
        'rePutOnDroppedEdges'    | false
    }

    @Unroll
    def "boolean/integer properties accept string forms as delivered through customConfiguration: #key=#raw"() {
        expect:
        ConnectorConfig.from(Fixtures.connectorProps([(key): raw]))."$key" == expected

        where:
        key                      | raw     | expected
        'pageSizeDefault'        | '50'    | 50
        'deleteMissingTolerated' | 'false' | false
        'deleteMissingTolerated' | 'true'  | true
        'rePutOnDroppedEdges'    | 'true'  | true
    }

    @Unroll
    def "missing or blank required property '#key' is a ConfigurationException naming it"() {
        given:
        def props = Fixtures.connectorProps()
        props[key] = value

        when:
        ConnectorConfig.from(props)

        then:
        def e = thrown(ConfigurationException)
        e.message.contains(key)

        where:
        key              | value
        'serviceAddress' | null
        'serviceAddress' | ''
        'username'       | null
        'password'       | null
        'objectTypes'    | null
    }

    @Unroll
    def "serviceAddress '#address' is rejected"() {
        when:
        ConnectorConfig.from(Fixtures.connectorProps(serviceAddress: address))

        then:
        thrown(ConfigurationException)

        where:
        address << ['localhost:5276', 'ftp://host', 'http://', '::']
    }

    def "serviceAddress trailing slash is normalised away so paths concatenate cleanly"() {
        expect:
        ConnectorConfig.from(Fixtures.connectorProps(serviceAddress: 'http://localhost:5276/')).serviceAddress == 'http://localhost:5276'
    }

    @Unroll
    def "pageSizeDefault #value is rejected (must be 1..#max)"() {
        when:
        ConnectorConfig.from(Fixtures.connectorProps(pageSizeDefault: value))

        then:
        thrown(ConfigurationException)

        where:
        value << [0, -1, 'abc', ConnectorConfig.MAX_PAGE_SIZE + 1]
        max = ConnectorConfig.MAX_PAGE_SIZE
    }

    def "password accepts char[] and String"() {
        expect:
        String.valueOf(ConnectorConfig.from(Fixtures.connectorProps(password: 'pw'.toCharArray())).password) == 'pw'
        String.valueOf(ConnectorConfig.from(Fixtures.connectorProps(password: 'pw')).password) == 'pw'
    }

    def "toString never reveals the password"() {
        expect:
        !ConnectorConfig.from(Fixtures.connectorProps(password: 'hunter2')).toString().contains('hunter2')
    }

    def "typeFor resolves configured classes and returns null for unknown ones"() {
        given:
        def cfg = ConnectorConfig.from(Fixtures.connectorProps())

        expect:
        cfg.typeFor('user').idProperty == 'userName'
        cfg.typeFor('account').idProperty == 'accountId'
        cfg.typeFor('group') == null
        cfg.typeFor(null) == null
    }

    def "loading runs the §7 consistency validation and fails fast on an asymmetric pair"() {
        given:
        def m = Fixtures.objectTypesMap()
        m.account.relationships.ownedBy.direction = 'outbound'   // both sides outbound

        when:
        ConnectorConfig.from(Fixtures.connectorProps(objectTypes: m))

        then:
        def e = thrown(ConfigurationException)
        e.message.contains('ownedBy') || e.message.contains('owns')
    }

    def "timestampSearchParam is configurable because the real parameter name is unconfirmed (§15)"() {
        expect:
        ConnectorConfig.from(Fixtures.connectorProps(timestampSearchParam: 'modifiedSince')).timestampSearchParam == 'modifiedSince'
    }
}
