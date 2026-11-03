/* UpdateScript.groovy — ICF Update operation (design §8.4, §9.2). Returns the Uid. */
import au.com.azwork.idm.graphentity.ops.ScriptSupport
import au.com.azwork.idm.graphentity.ops.UpdateOperation

def ctx = ScriptSupport.contextFrom(configuration, connection, log)
return new UpdateOperation(ctx).execute(objectClass, uid, attributes, options)
