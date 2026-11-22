package au.com.azwork.idm.entitlementsaccelerator.search

import au.com.azwork.idm.entitlementsaccelerator.config.ConnectorConfig
import au.com.azwork.idm.entitlementsaccelerator.support.Fixtures
import org.identityconnectors.framework.common.objects.AttributeBuilder
import org.identityconnectors.framework.common.objects.Name
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder
import org.identityconnectors.framework.common.objects.Uid
import org.identityconnectors.framework.common.objects.filter.FilterBuilder
import spock.lang.Specification
import spock.lang.Unroll

/** Design §9.4; decision P21 — translating ICF filters into reads by ID and the service's filter tree. */
class SearchPlannerSpec extends Specification {

    static final def CFG = Fixtures.connectorConfig()
    static final def USER = Fixtures.userType()

    static SearchPlan plan(filter, options = null, cfg = CFG) { SearchPlanner.plan(filter, options, USER, cfg) }

    static Map leaf(String op, String attr, String value) { [operation: op, left: attr, right: value] }

    def "no filter → ENUMERATE with no filter tree, the default page size and the first page"() {
        when:
        def p = plan(null, new OperationOptionsBuilder().build())

        then:
        p.kind == SearchPlan.Kind.ENUMERATE
        p.filter == null
        p.pageSize == 200
        p.cursor.serviceCookie == null
    }

    @Unroll
    def "EqualsFilter on #attr → BY_ID"() {
        when:
        def p = plan(FilterBuilder.equalTo(attribute))

        then:
        p.kind == SearchPlan.Kind.BY_ID
        p.id == 'Sanne'
        p.filter == null

        where:
        attr       | attribute
        '__UID__'  | new Uid('Sanne')
        '__NAME__' | new Name('Sanne')
    }

    @Unroll
    def "#desc on a configured attribute → the service's #op leaf, sent by name"() {
        when:
        def p = plan(filter)

        then:
        p.kind == SearchPlan.Kind.ENUMERATE
        p.filter == leaf(op, attr, value)

        where:
        desc         | filter                                                                    | op           | attr   | value
        'equals'     | FilterBuilder.equalTo(AttributeBuilder.build('name', 'Sanne Groen'))      | 'EQUALS'     | 'name' | 'Sanne Groen'
        'contains'   | FilterBuilder.contains(AttributeBuilder.build('mail', 'example.com'))     | 'CONTAINS'   | 'mail' | 'example.com'
        'startsWith' | FilterBuilder.startsWith(AttributeBuilder.build('name', 'San'))           | 'STARTSWITH' | 'name' | 'San'
    }

    def "AND and OR combine supported filters into a tree"() {
        given:
        def a = FilterBuilder.equalTo(AttributeBuilder.build('name', 'Sanne Groen'))
        def b = FilterBuilder.startsWith(AttributeBuilder.build('mail', 'sanne'))

        expect:
        plan(FilterBuilder.and(a, b)).filter == [operation: 'AND', left: leaf('EQUALS', 'name', 'Sanne Groen'), right: leaf('STARTSWITH', 'mail', 'sanne')]
        plan(FilterBuilder.or(a, b)).filter == [operation: 'OR', left: leaf('EQUALS', 'name', 'Sanne Groen'), right: leaf('STARTSWITH', 'mail', 'sanne')]
    }

    def "NOT sets the not flag on the negated tree"() {
        given:
        def a = FilterBuilder.equalTo(AttributeBuilder.build('name', 'Sanne Groen'))
        def b = FilterBuilder.contains(AttributeBuilder.build('mail', 'x'))

        expect:
        plan(FilterBuilder.not(a)).filter == leaf('EQUALS', 'name', 'Sanne Groen') + [not: true]
        plan(FilterBuilder.not(FilterBuilder.or(a, b))).filter ==
            [operation: 'OR', left: leaf('EQUALS', 'name', 'Sanne Groen'), right: leaf('CONTAINS', 'mail', 'x'), not: true]
    }

    def "non-string filter values are sent as text, because the service's attributes are strings (P20)"() {
        expect:
        plan(FilterBuilder.equalTo(AttributeBuilder.build('telephoneNumber', 61400))).filter == leaf('EQUALS', 'telephoneNumber', '61400')
    }

    @Unroll
    def "unsupported filter #desc → UnsupportedOperationException, never a silent full enumeration (P7)"() {
        when:
        plan(filter)

        then:
        def e = thrown(UnsupportedOperationException)
        e.message.toLowerCase().contains('filter')

        where:
        desc                                   | filter
        'endsWith (until operations are named)'| FilterBuilder.endsWith(AttributeBuilder.build('name', 'Groen'))
        'greaterThan on an attribute'          | FilterBuilder.greaterThan(AttributeBuilder.build('name', 'A'))
        'lessThan on an attribute'             | FilterBuilder.lessThan(AttributeBuilder.build('name', 'Z'))
        'equals on an unconfigured attribute'  | FilterBuilder.equalTo(AttributeBuilder.build('department', 'X'))
        'equals on entityRelationships'        | FilterBuilder.equalTo(AttributeBuilder.build('entityRelationships', 'x'))
        'equals on directArchetypes'           | FilterBuilder.equalTo(AttributeBuilder.build('directArchetypes', 'Customer'))
        'contains on __NAME__'                 | FilterBuilder.contains(new Name('San'))
        'startsWith on __UID__'                | FilterBuilder.startsWith(new Uid('San'))
        '__UID__ inside a compound filter'     | FilterBuilder.and(FilterBuilder.equalTo(new Uid('a')), FilterBuilder.equalTo(AttributeBuilder.build('name', 'x')))
        'a multi-valued equals'                | FilterBuilder.equalTo(AttributeBuilder.build('name', 'a', 'b'))
        'containsAllValues'                    | FilterBuilder.containsAllValues(AttributeBuilder.build('mail', 'a'))
    }

    def "timestamp searches are refused until drift detection is built in WS10 (P16): the service has none"() {
        when:
        plan(FilterBuilder.greaterThan(AttributeBuilder.build('lastEvaluated', '2026-07-06T00:00:00Z')))

        then:
        def e = thrown(UnsupportedOperationException)
        e.message.toLowerCase().contains('filter')
    }

    def "page size comes from OperationOptions when set, capped at MAX_PAGE_SIZE"() {
        expect:
        plan(null, new OperationOptionsBuilder().setPageSize(50).build()).pageSize == 50
        plan(null, new OperationOptionsBuilder().setPageSize(ConnectorConfig.MAX_PAGE_SIZE * 5).build()).pageSize == ConnectorConfig.MAX_PAGE_SIZE
    }

    def "the configured pageSizeDefault applies when options set none"() {
        expect:
        plan(null, null, Fixtures.connectorConfig(pageSizeDefault: 25)).pageSize == 25
    }

    def "a paged-results cookie is decoded into the cursor"() {
        given:
        def cookie = new PageCursor(serviceCookie: '100').encode()

        expect:
        plan(null, new OperationOptionsBuilder().setPagedResultsCookie(cookie).setPageSize(100).build()).cursor.serviceCookie == '100'
    }

    def "a paged-results cookie on a BY_ID search is ignored"() {
        expect:
        plan(FilterBuilder.equalTo(new Uid('x')), new OperationOptionsBuilder().setPagedResultsCookie(new PageCursor(serviceCookie: '10').encode()).build()).kind ==
            SearchPlan.Kind.BY_ID
    }
}
