package au.com.azwork.idm.graphentity.config

import org.identityconnectors.framework.common.exceptions.ConfigurationException
import spock.lang.Specification
import spock.lang.Unroll

class DirectionSpec extends Specification {

    @Unroll
    def "direction '#value' parses to #expected"() {
        expect:
        Direction.fromConfig(value) == expected

        where:
        value      | expected
        'outbound' | Direction.OUTBOUND
        'inbound'  | Direction.INBOUND
        'OUTBOUND' | Direction.OUTBOUND
        'Inbound'  | Direction.INBOUND
    }

    @Unroll
    def "direction '#value' is rejected"() {
        when:
        Direction.fromConfig(value)

        then:
        thrown(ConfigurationException)

        where:
        value << [null, '', 'out', 'both', 'TargetID']
    }

    def "opposite flips direction"() {
        expect:
        Direction.OUTBOUND.opposite() == Direction.INBOUND
        Direction.INBOUND.opposite() == Direction.OUTBOUND
    }
}
