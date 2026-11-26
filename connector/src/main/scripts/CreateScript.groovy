/* CreateScript.groovy — ICF Create operation (design §9.1). Returns the Uid. */
import idm.entitlementsaccelerator.ops.CreateOperation
import idm.entitlementsaccelerator.ops.ScriptSupport

def ctx = ScriptSupport.contextFrom(configuration, ScriptSupport.connectionOf(binding), log)
return new CreateOperation(ctx).execute(objectClass, ScriptSupport.createAttributes(binding), ScriptSupport.optional(binding, 'options'))
