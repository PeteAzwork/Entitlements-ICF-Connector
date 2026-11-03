package au.com.azwork.idm.graphentity.config

import org.identityconnectors.framework.common.exceptions.ConfigurationException
import spock.lang.Specification
import spock.lang.Unroll

/** Design §3 A4, §6: entityKind ↔ BaseType. */
class EntityKindSpec extends Specification {

    @Unroll
    def "entityKind '#value' maps to #expected with BaseType #baseType"() {
        expect:
        EntityKind.fromConfig(value) == expected
        expected.baseType == baseType

        where:
        value      | expected            | baseType
        'subject'  | EntityKind.SUBJECT  | 4
        'resource' | EntityKind.RESOURCE | 2
        'Subject'  | EntityKind.SUBJECT  | 4
        'RESOURCE' | EntityKind.RESOURCE | 2
        ' subject '| EntityKind.SUBJECT  | 4
    }

    @Unroll
    def "entityKind '#value' is rejected with a ConfigurationException"() {
        when:
        EntityKind.fromConfig(value)

        then:
        def e = thrown(ConfigurationException)
        e.message.toLowerCase().contains('entitykind')

        where:
        value << [null, '', 'person', '4', 'subjects']
    }

    @Unroll
    def "BaseType #baseType decodes to #expected"() {
        expect:
        EntityKind.fromBaseType(baseType) == expected

        where:
        baseType | expected
        4        | EntityKind.SUBJECT
        2        | EntityKind.RESOURCE
    }

    @Unroll
    def "unknown BaseType #baseType decodes to null rather than failing a read"() {
        expect:
        EntityKind.fromBaseType(baseType) == null

        where:
        baseType << [null, 0, 1, 3, 99]
    }
}
