package au.com.azwork.idm.graphentity.ops

import au.com.azwork.idm.graphentity.support.CapturingLog
import au.com.azwork.idm.graphentity.support.FakeGraphService
import au.com.azwork.idm.graphentity.support.Fixtures
import spock.lang.Specification

/** Common wiring for operation specs: a fake service with the documented semantics and a capturing log. */
abstract class OperationSpecBase extends Specification {
    FakeGraphService service = new FakeGraphService()
    CapturingLog log = new CapturingLog()

    OperationContext ctx(Map configOverrides = [:]) {
        new OperationContext(Fixtures.connectorConfig(configOverrides), service, log.asLog())
    }
}
