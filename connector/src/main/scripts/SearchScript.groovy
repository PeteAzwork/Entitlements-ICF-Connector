/* SearchScript.groovy — ICF Search/Get operation (design §9.4). Returns the SearchResult (paging cookie). */
import idm.entitlementsaccelerator.ops.ScriptSupport
import idm.entitlementsaccelerator.ops.SearchOperation

def ctx = ScriptSupport.contextFrom(configuration, ScriptSupport.connectionOf(binding), log)
return new SearchOperation(ctx).execute(objectClass, filter, handler, options)
