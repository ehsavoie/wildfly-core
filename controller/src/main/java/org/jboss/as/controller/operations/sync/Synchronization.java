/*
 * Copyright 2016 JBoss by Red Hat.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.as.controller.operations.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.ListIterator;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.helpers.Operations;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_CLIENT_CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.UNDEFINE_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION;

import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Emmanuel Hugonnet (c) 2016 Red Hat, inc.
 */
public class Synchronization {

    public static OrderedOperationsCollection computeModelSyncOperations(final boolean booting, final SyncModelParameters parameters, final Node localNode, final Node remoteNode, final ImmutableManagementResourceRegistration registration) {
        // Compare the nodes and create the operations to sync the model
        OrderedOperationsCollection operations = new OrderedOperationsCollection(parameters, booting);
        processAttributes(localNode, remoteNode, operations, registration);
        SynchronizationContext.processChildren(localNode, remoteNode, operations, registration);
        return operations;
    }

    static void processAttributes(final Node current, final Node remote, final OrderedOperationsCollection operations, final ImmutableManagementResourceRegistration registration) {

        for (final String attribute : remote.attributes.keySet()) {
            // Remove from current model
            final ModelNode currentOp = current.attributes.remove(attribute);
            if (currentOp == null) {
                operations.add(remote.attributes.get(attribute));
            } else {
                final ModelNode remoteOp = remote.attributes.get(attribute);
                if (!remoteOp.equals(currentOp)) {
                    operations.add(remoteOp);
                }
            }
        }

        // Undefine operations if the remote write-attribute operation does not exist
        for (final String attribute : current.attributes.keySet()) {
            final ModelNode op = Operations.createUndefineAttributeOperation(current.address.toModelNode(), attribute);
            operations.add(op);
        }
    }
    public static class OrderedOperationsCollection {

    private final List<ModelNode> extensionAdds = new ArrayList<>();
    private final List<ModelNode> nonExtensionAdds = new ArrayList<>();

    private final List<ModelNode> extensionRemoves = new ArrayList<>();
    private final List<ModelNode> nonExtensionRemoves = new ArrayList<>();

    private final List<ModelNode> allOps = new ArrayList<>();

    private final boolean booting;
    private final SyncModelParameters parameters;

    private OrderedOperationsCollection(SyncModelParameters parameters, boolean booting) {
        this.booting = booting;
        this.parameters = parameters;
    }

    void add(ModelNode op) {
        final String name = op.require(OP).asString();
        final PathAddress addr = PathAddress.pathAddress(op.require(OP_ADDR));
        final String type = addr.size() == 0 ? "" : addr.getElement(0).getKey();
        if(parameters.isResourceExcluded(addr)) {
            return;
        }
        switch (name) {
            case ADD:
            case WRITE_ATTRIBUTE_OPERATION:
            case UNDEFINE_ATTRIBUTE_OPERATION:
                switch (type) {
                    case EXTENSION:
                        extensionAdds.add(op);
                        break;
                    case MANAGEMENT_CLIENT_CONTENT:
                        //Only add this on boot, since it is a 'hard-coded' one
                        //Otherwise, it will be added to the allOps further below which is used by SyncServerStateOperationHandler
                        //which will drop/re-add the custom resource as needed if necessary
                        if (booting) {
                            nonExtensionAdds.add(op);
                        }
                        break;
                    default:
                        nonExtensionAdds.add(op);
                        break;
                }
                break;
            case REMOVE:
                switch (type) {
                    case EXTENSION:
                        extensionRemoves.add(op);
                        break;
                    default:
                        nonExtensionRemoves.add(op);
                }
                break;
            default:
                assert false : "Unknown operation " + name;
                break;
        }
        allOps.add(op);
    }

    public List<ModelNode> getReverseList() {
        //This is the opposite order. Due to how the steps get added, once run we will do them in the following order:
        //  extension removes, extension adds, non-extension composite
        //  The non-extension composite in turn will do removes first, and then adds
        final List<ModelNode> result = new ArrayList<>();
        final ModelNode nonExtensionComposite = Util.createEmptyOperation(COMPOSITE, PathAddress.EMPTY_ADDRESS);
        final ModelNode nonExtensionSteps = nonExtensionComposite.get(STEPS).setEmptyList();
        final ListIterator<ModelNode> it = nonExtensionRemoves.listIterator(nonExtensionRemoves.size());
        while (it.hasPrevious()) {
            nonExtensionSteps.add(it.previous());
        }
        for (ModelNode op : nonExtensionAdds) {
            nonExtensionSteps.add(op);
        }
        if (!nonExtensionSteps.asList().isEmpty()) {
            result.add(nonExtensionComposite);
        }
        result.addAll(extensionAdds);
        result.addAll(extensionRemoves);
        return result;
    }

    public List<ModelNode> getAllOps() {
        return allOps;
    }
}

}
