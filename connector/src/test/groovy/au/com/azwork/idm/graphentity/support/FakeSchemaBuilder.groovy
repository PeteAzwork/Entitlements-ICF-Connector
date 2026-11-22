package au.com.azwork.idm.entitlementsaccelerator.support

import org.identityconnectors.framework.common.objects.AttributeInfo

/**
 * Stand-in for the Groovy toolkit's ICFObjectBuilder, shaped as Ping's schema-script reference shows it:
 * {@code builder.schema { objectClass { type ...; attribute ...; disable ... } }}. Records what the script defines.
 */
class FakeSchemaBuilder {
    final List<Map> objectClasses = []

    void schema(Closure c) {
        c.delegate = new SchemaDelegate()
        c.resolveStrategy = Closure.DELEGATE_FIRST
        c()
    }

    class SchemaDelegate {
        void objectClass(Closure c) {
            def oc = new ObjectClassDelegate()
            c.delegate = oc
            c.resolveStrategy = Closure.DELEGATE_FIRST
            c()
            objectClasses << [type: oc.typeName, attributes: oc.attributes, disabled: oc.disabled]
        }
    }

    static class ObjectClassDelegate {
        String typeName
        final List<AttributeInfo> attributes = []
        final List<Class> disabled = []

        void type(String name) { typeName = name }
        void attribute(AttributeInfo info) { attributes << info }
        void disable(Class... operations) { disabled.addAll(operations) }
    }
}
