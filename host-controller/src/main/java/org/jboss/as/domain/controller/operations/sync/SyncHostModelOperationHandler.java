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

package org.jboss.as.domain.controller.operations.sync;



import java.util.List;
import java.util.Set;

import org.jboss.as.controller.OperationContext;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.as.controller.operations.sync.Node;
import org.jboss.as.controller.operations.sync.ReadOperationsHandler;
import org.jboss.as.controller.operations.sync.SyncModelOperationHandler;
import org.jboss.as.controller.operations.sync.SyncModelParameters;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.operations.sync.Synchronization;
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
class SyncHostModelOperationHandler extends SyncModelOperationHandler {

    SyncHostModelOperationHandler(List<ModelNode> localOperations, Resource remoteModel, Set<String> missingExtensions,
                              SyncModelParameters parameters, OrderedChildTypesAttachment localOrderedChildTypes) {
        super(localOperations, remoteModel, missingExtensions, parameters, localOrderedChildTypes);
    }

    @Override
    protected void fetchMissingConfiguration(OperationContext context, Synchronization.OrderedOperationsCollection operations) {
        if (!operations.getAllOps().isEmpty() && parameters.isFullModelTransfer() && !context.isBooting()) {
            //Only do this is if it is a full model transfer as a result of a _reconnect_ to the DC.
            //When fetching missing configuration while connected, the servers will get put into reload-required as a
            // result of changing the server-group, profile or the socket-binding-group
            context.addStep(new SyncServerStateOperationHandler(parameters, operations.getAllOps()),
                    OperationContext.Stage.MODEL, true);
        }
    }

    @Override
    protected Node createRemoteRoot(OperationContext context) {
        // Describe the operations based on the remote model
        final ReadOperationsHandler readOperationsHandler = new ReadMasterDomainOperationsHandler();
        final ModelNode result = ((DomainSyncModelParameters) parameters).readRemoteOperations(remoteModel, readOperationsHandler);
        if (result.hasDefined(FAILURE_DESCRIPTION)) {
            context.getFailureDescription().set(result.get(FAILURE_DESCRIPTION));
            return null;
        }
        final List<ModelNode> remoteOperations = result.get(RESULT).asList();
        return Node.createOperationTree(remoteOperations, readOperationsHandler.getOrderedChildTypes());
    }
}
