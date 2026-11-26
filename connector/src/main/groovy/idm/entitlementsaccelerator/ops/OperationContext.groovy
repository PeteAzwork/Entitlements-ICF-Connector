package idm.entitlementsaccelerator.ops

import idm.entitlementsaccelerator.config.ConnectorConfig
import idm.entitlementsaccelerator.http.EntityApi
import org.identityconnectors.common.logging.Log

/** Everything an operation needs: configuration, the remote API and the ICF logger. */
class OperationContext {
    final ConnectorConfig config
    final EntityApi api
    final Log log

    OperationContext(ConnectorConfig config, EntityApi api, Log log) {
        this.config = config
        this.api = api
        this.log = log
    }
}
