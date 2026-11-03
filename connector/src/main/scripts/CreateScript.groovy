/* CreateScript.groovy — ICF Create operation (design §9.1). Returns the Uid. */
import au.com.azwork.idm.graphentity.ops.CreateOperation
import au.com.azwork.idm.graphentity.ops.ScriptSupport

def ctx = ScriptSupport.contextFrom(configuration, connection, log)
return new CreateOperation(ctx).execute(objectClass, attributes, options)
