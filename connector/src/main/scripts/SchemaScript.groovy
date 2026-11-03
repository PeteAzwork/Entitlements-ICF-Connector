/* SchemaScript.groovy — ICF Schema operation (design §5.2, §9.5). The harness expects a Schema return value. */
import au.com.azwork.idm.graphentity.ops.SchemaOperation
import au.com.azwork.idm.graphentity.ops.ScriptSupport

def ctx = ScriptSupport.contextFrom(configuration, connection, log)
return new SchemaOperation(ctx).execute(connectorClass)
