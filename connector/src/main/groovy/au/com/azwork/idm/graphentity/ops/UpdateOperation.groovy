package au.com.azwork.idm.graphentity.ops

import org.identityconnectors.framework.common.objects.Attribute
import org.identityconnectors.framework.common.objects.ObjectClass
import org.identityconnectors.framework.common.objects.OperationOptions
import org.identityconnectors.framework.common.objects.Uid

/**
 * Update (design §8.4, §9.2): PUT the total document built purely from the delivered attribute set.
 * Never reads the remote entity first. Refuses (InvalidAttributeValueException) an update that omits
 * {@code entityRelationships} or {@code directArchetypes}, because a PUT without them would delete edges.
 */
class UpdateOperation {
    private final OperationContext ctx

    UpdateOperation(OperationContext ctx) { this.ctx = ctx }

    /** TODO WS3. */
    Uid execute(ObjectClass objectClass, Uid uid, Set<Attribute> attributes, OperationOptions options) {
        throw new UnsupportedOperationException('TODO WS3: UpdateOperation.execute')
    }
}
