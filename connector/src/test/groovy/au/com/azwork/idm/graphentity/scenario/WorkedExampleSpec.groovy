package au.com.azwork.idm.graphentity.scenario

import au.com.azwork.idm.graphentity.ops.CreateOperation
import au.com.azwork.idm.graphentity.ops.DeleteOperation
import au.com.azwork.idm.graphentity.ops.OperationContext
import au.com.azwork.idm.graphentity.ops.SearchOperation
import au.com.azwork.idm.graphentity.ops.UpdateOperation
import au.com.azwork.idm.graphentity.support.CapturingLog
import au.com.azwork.idm.graphentity.support.FakeGraphService
import au.com.azwork.idm.graphentity.support.Fixtures
import org.identityconnectors.common.logging.Log
import org.identityconnectors.framework.common.objects.Uid
import org.identityconnectors.framework.common.objects.filter.FilterBuilder
import spock.lang.Specification
import spock.lang.Stepwise

/**
 * Appendix A — the worked example, end to end, against the in-memory service model.
 * Steps are ordered (Stepwise) because each builds on the previous remote state.
 */
@Stepwise
class WorkedExampleSpec extends Specification {

    static FakeGraphService service = new FakeGraphService()
    static CapturingLog log = new CapturingLog()
    static OperationContext ctx

    def setupSpec() { ctx = new OperationContext(Fixtures.connectorConfig(), service, log.asLog()) }

    def "1. initial recon runs the user mapping first: Sanne is created, the edge is dropped and logged"() {
        when:
        new CreateOperation(ctx).execute(Fixtures.USER, Fixtures.sanneAttributes(), null)

        then:
        service.get('Sanne').body.Relationships == []
        log.messagesAt(Log.Level.WARN) == ['dropped edge Sanne -Owns-> Current-Account-1-Sanne']
    }

    def "2. the account mapping creates the account: its SourceID edge heals the drop with no further action"() {
        when:
        new CreateOperation(ctx).execute(Fixtures.ACCOUNT, Fixtures.accountAttributes(), null)

        then:
        service.get('Sanne').body.Relationships == [[TargetID: 'Current-Account-1-Sanne', type: 'Owns']]
        service.get('Current-Account-1-Sanne').body.Relationships == [[SourceID: 'Sanne', type: 'Owns']]
        log.messagesAt(Log.Level.WARN).size() == 1   // no new warning
    }

    def "3. Sanne's name changes: the update PUTs the full payload and the edge is preserved"() {
        when:
        new UpdateOperation(ctx).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)

        then:
        service.get('Sanne').body.attributes.name == 'Sanne Smith'
        service.get('Sanne').body.Relationships == [[TargetID: 'Current-Account-1-Sanne', type: 'Owns']]
    }

    def "4. a drift-detection read of Sanne shows the same edge set IDM would compute"() {
        given:
        def seen = []

        when:
        new SearchOperation(ctx).execute(Fixtures.USER, FilterBuilder.equalTo(new Uid('Sanne')), { seen << it; true }, null)

        then:
        seen[0].getAttributeByName('entityRelationships').value == ['{"TargetID":"Current-Account-1-Sanne","type":"Owns"}']
        seen[0].getAttributeByName('directArchetypes').value == ['Customer']
    }

    def "5. the account is deleted in IDM: the entity and its edge go; Sanne's follow-up PUT restates no edges"() {
        when:
        new DeleteOperation(ctx).execute(Fixtures.ACCOUNT, new Uid('Current-Account-1-Sanne'), null)
        new UpdateOperation(ctx).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith', entityRelationships: []), null)

        then:
        service.get('Current-Account-1-Sanne').status == 404
        service.get('Sanne').body.Relationships == []
        service.edges.empty
    }

    def "6. drift repair: a remote-side edge removal is corrected by replaying the IDM-derived PUT"() {
        given:
        service.seed(Fixtures.accountWire())
        new UpdateOperation(ctx).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)
        service.edges.clear()   // manual intervention in the remote service

        when:
        new UpdateOperation(ctx).execute(Fixtures.USER, new Uid('Sanne'), Fixtures.sanneAttributes(name: 'Sanne Smith'), null)

        then:
        service.edges == [['Sanne', 'Current-Account-1-Sanne', 'Owns']] as Set
    }
}
