package au.com.azwork.idm.graphentity.search

import au.com.azwork.idm.graphentity.config.ConnectorConfig
import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.framework.common.objects.AttributeBuilder
import org.identityconnectors.framework.common.objects.Name
import org.identityconnectors.framework.common.objects.OperationOptionsBuilder
import org.identityconnectors.framework.common.objects.Uid
import org.identityconnectors.framework.common.objects.filter.Filter
import org.identityconnectors.framework.common.objects.filter.FilterBuilder
import spock.lang.Specification
import spock.lang.Unroll

/** Design §9.4 — filter translation. */
class SearchPlannerSpec extends Specification {

    static final def CFG = Fixtures.connectorConfig()

    def "no filter → ENUMERATE from epoch with the default page size"() {
        when:
        def plan = SearchPlanner.plan(null, new OperationOptionsBuilder().build(), CFG)

        then:
        plan.kind == SearchPlan.Kind.ENUMERATE
        plan.since == SearchPlanner.EPOCH
        plan.pageSize == 200
        plan.cursor.offset == 0
    }

    def "EqualsFilter on __UID__ → BY_ID"() {
        when:
        def plan = SearchPlanner.plan(FilterBuilder.equalTo(new Uid('Sanne')), null, CFG)

        then:
        plan.kind == SearchPlan.Kind.BY_ID
        plan.id == 'Sanne'
    }

    def "EqualsFilter on __NAME__ → BY_ID"() {
        when:
        def plan = SearchPlanner.plan(FilterBuilder.equalTo(new Name('Sanne')), null, CFG)

        then:
        plan.kind == SearchPlan.Kind.BY_ID
        plan.id == 'Sanne'
    }

    @Unroll
    def "#desc on lastUpdated → SINCE #since"() {
        when:
        def plan = SearchPlanner.plan(filter, null, CFG)

        then:
        plan.kind == SearchPlan.Kind.SINCE
        plan.since == since

        where:
        desc                   | filter                                                                                   | since
        'GreaterThan'          | FilterBuilder.greaterThan(AttributeBuilder.build('lastUpdated', '2026-07-06T00:00:00Z'))        | '2026-07-06T00:00:00Z'
        'GreaterThanOrEqual'   | FilterBuilder.greaterThanOrEqualTo(AttributeBuilder.build('lastUpdated', '2026-07-06T01:02:03Z')) | '2026-07-06T01:02:03Z'
    }

    def "a lastUpdated value that is not an ISO instant is rejected"() {
        when:
        SearchPlanner.plan(FilterBuilder.greaterThan(AttributeBuilder.build('lastUpdated', 'yesterday')), null, CFG)

        then:
        thrown(IllegalArgumentException)
    }

    @Unroll
    def "unsupported filter #desc → UnsupportedOperationException rather than a silent full enumeration"() {
        when:
        SearchPlanner.plan(filter, null, CFG)

        then:
        def e = thrown(UnsupportedOperationException)
        e.message.toLowerCase().contains('filter')   // not the stub's TODO message

        where:
        desc                              | filter
        'equals on a domain attribute'    | FilterBuilder.equalTo(AttributeBuilder.build('name', 'Sanne Groen'))
        'contains on __NAME__'            | FilterBuilder.contains(new Name('San'))
        'startsWith on __NAME__'          | FilterBuilder.startsWith(new Name('San'))
        'lessThan on lastUpdated'         | FilterBuilder.lessThan(AttributeBuilder.build('lastUpdated', '2026-01-01T00:00:00Z'))
        'greaterThan on another attr'     | FilterBuilder.greaterThan(AttributeBuilder.build('name', 'A'))
        'and of two equals'               | FilterBuilder.and(FilterBuilder.equalTo(new Uid('a')), FilterBuilder.equalTo(new Name('a')))
        'or'                              | FilterBuilder.or(FilterBuilder.equalTo(new Uid('a')), FilterBuilder.equalTo(new Uid('b')))
        'not'                             | FilterBuilder.not(FilterBuilder.equalTo(new Uid('a')))
    }

    def "page size comes from OperationOptions when set"() {
        when:
        def plan = SearchPlanner.plan(null, new OperationOptionsBuilder().setPageSize(50).build(), CFG)

        then:
        plan.pageSize == 50
    }

    def "page size is capped at MAX_PAGE_SIZE"() {
        when:
        def plan = SearchPlanner.plan(null, new OperationOptionsBuilder().setPageSize(ConnectorConfig.MAX_PAGE_SIZE * 5).build(), CFG)

        then:
        plan.pageSize == ConnectorConfig.MAX_PAGE_SIZE
    }

    def "a paged-results cookie is decoded into the cursor"() {
        given:
        def cookie = new PageCursor(offset: 400).encode()

        when:
        def plan = SearchPlanner.plan(null, new OperationOptionsBuilder().setPagedResultsCookie(cookie).setPageSize(200).build(), CFG)

        then:
        plan.cursor.offset == 400
    }

    def "a paged-results cookie on a BY_ID search is ignored"() {
        when:
        def plan = SearchPlanner.plan(FilterBuilder.equalTo(new Uid('x')),
            new OperationOptionsBuilder().setPagedResultsCookie(new PageCursor(offset: 10).encode()).build(), CFG)

        then:
        plan.kind == SearchPlan.Kind.BY_ID
    }

    def "the configured pageSizeDefault is honoured"() {
        expect:
        SearchPlanner.plan(null, null, Fixtures.connectorConfig(pageSizeDefault: 25)).pageSize == 25
    }
}
