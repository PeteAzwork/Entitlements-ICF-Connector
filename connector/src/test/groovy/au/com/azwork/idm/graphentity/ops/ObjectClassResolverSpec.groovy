package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.exceptions.ConnectorException
import org.identityconnectors.framework.common.objects.ObjectClass
import spock.lang.Specification

class ObjectClassResolverSpec extends Specification {

    def "resolves configured object classes"() {
        expect:
        ObjectClassResolver.resolve(Fixtures.USER, Fixtures.connectorConfig()).idProperty == 'userName'
        ObjectClassResolver.resolve(Fixtures.ACCOUNT, Fixtures.connectorConfig()).idProperty == 'accountId'
    }

    def "an unknown object class fails fast naming the class and the configured ones"() {
        when:
        ObjectClassResolver.resolve(new ObjectClass('group'), Fixtures.connectorConfig())

        then:
        def e = thrown(ConnectorException)
        e.message.contains('group')
        e.message.contains('user')
        e.message.contains('account')
    }

    def "the generic __ACCOUNT__ class is not implicitly mapped (Option B: per-type classes only)"() {
        when:
        ObjectClassResolver.resolve(ObjectClass.ACCOUNT, Fixtures.connectorConfig())

        then:
        thrown(ConnectorException)
    }

    def "a null object class is rejected"() {
        when:
        ObjectClassResolver.resolve(null, Fixtures.connectorConfig())

        then:
        thrown(ConnectorException)
    }
}
