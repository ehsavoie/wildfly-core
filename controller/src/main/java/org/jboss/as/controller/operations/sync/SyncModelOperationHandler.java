/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2014, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.as.controller.operations.sync;


import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_MAJOR_VERSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_MICRO_VERSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_MINOR_VERSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAMESPACES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PRODUCT_NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PRODUCT_VERSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RELEASE_CODENAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RELEASE_VERSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SCHEMA_LOCATIONS;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;

/**
 * Internal {@code OperationStepHandler} which synchronizes the model based on a comparison of local and remote operations.
 *
 * Basically it compares the current state of the model to the one from the master. Where the initial connection to the
 * master tries to sync the whole model, fetching missing configuration only looks at server-groups and it's references,
 * ignoring all other resources.
 *
 * @author Emanuel Muckenhuber
 */
public abstract class SyncModelOperationHandler implements OperationStepHandler {

    protected final Resource remoteModel;
    protected final List<ModelNode> localOperations;
    protected final Set<String> missingExtensions;
    protected final SyncModelParameters parameters;
    protected final OrderedChildTypesAttachment localOrderedChildTypes;
    private static final Set<String> ROOT_ATTRIBUTES = new HashSet<>(Arrays.asList(
            MANAGEMENT_MAJOR_VERSION, MANAGEMENT_MINOR_VERSION, MANAGEMENT_MICRO_VERSION,
            PRODUCT_NAME, PRODUCT_VERSION, RELEASE_CODENAME, RELEASE_VERSION,
            NAMESPACES, NAME, SCHEMA_LOCATIONS));

    protected SyncModelOperationHandler(List<ModelNode> localOperations, Resource remoteModel, Set<String> missingExtensions,
                              SyncModelParameters parameters, OrderedChildTypesAttachment localOrderedChildTypes) {
        this.localOperations = localOperations;
        this.remoteModel = remoteModel;
        this.missingExtensions = missingExtensions;
        this.parameters = parameters;
        this.localOrderedChildTypes = localOrderedChildTypes;
    }

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
        // There should be no missing extensions
        if (!missingExtensions.isEmpty()) {
            throw ControllerLogger.ROOT_LOGGER.missingExtensions(missingExtensions);
        }
        // Create the node models based on the operations
        final Node currentRoot = Node.createOperationTree(localOperations, localOrderedChildTypes);
        final Node remoteRoot = createRemoteRoot(context);
        if(remoteRoot == null) {
            return;
        }
        final ImmutableManagementResourceRegistration rootRegistration = context.getRootResourceRegistration();
        // Compare the nodes and create the operations to sync the model
        Synchronization.OrderedOperationsCollection operations = Synchronization.computeModelSyncOperations(context.isBooting(), parameters, currentRoot, remoteRoot, rootRegistration);
                        //Process root domain attributes manually as those are read-only
        if (context.getCurrentAddress().size() == 0) {
            Resource rootResource = context.readResourceForUpdate(PathAddress.EMPTY_ADDRESS);
            ModelNode rootModel = rootResource.getModel().clone();
            ModelNode remoteRootModel = remoteModel.getModel();
            ROOT_ATTRIBUTES.stream().filter((attributeName) -> (remoteRootModel.hasDefined(attributeName))).forEach((attributeName) -> {
                rootModel.get(attributeName).set(remoteRootModel.get(attributeName));
            });
            rootResource.writeModel(rootModel);
        }
        // Reverse, since we are adding the steps on top of the queue
        final List<ModelNode> ops = operations.getReverseList();
        ops.forEach(op -> ControllerLogger.ROOT_LOGGER.debugf("Synchronization operations are %s", op));
        processSynchronizationOperations(context, ops);
        fetchMissingConfiguration(context, operations);
    }

    protected void processSynchronizationOperations(OperationContext context, final List<ModelNode> ops ) {
        ops.forEach(op -> ControllerLogger.ROOT_LOGGER.debugf("Synchronization operations are %s", op));
        for (final ModelNode op : ops) {
            final String operationName = op.require(OP).asString();
            final PathAddress address = PathAddress.pathAddress(op.require(OP_ADDR));
            final OperationStepHandler stepHandler = context.getRootResourceRegistration().getOperationHandler(address, operationName);
            if(stepHandler != null) {
                context.addStep(op, stepHandler, OperationContext.Stage.MODEL, true);
            } else {
                final ImmutableManagementResourceRegistration child = context.getRootResourceRegistration().getSubModel(address);
                if (child == null) {
                    context.getFailureDescription().set(ControllerLogger.ROOT_LOGGER.noSuchResourceType(address));
                } else {
                    context.getFailureDescription().set(ControllerLogger.ROOT_LOGGER.noHandlerForOperation(operationName, address));
                }
            }
        }
    }

    protected abstract Node createRemoteRoot(OperationContext context);

    protected abstract void fetchMissingConfiguration(OperationContext context, Synchronization.OrderedOperationsCollection operations);
}
