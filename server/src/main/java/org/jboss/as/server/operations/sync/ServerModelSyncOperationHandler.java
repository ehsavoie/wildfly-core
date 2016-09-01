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
package org.jboss.as.server.operations.sync;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESPONSE_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.operations.common.OrderedChildTypesAttachment.ORDERED_CHILDREN;

import java.util.List;
import java.util.Set;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.as.controller.operations.sync.Node;
import org.jboss.as.controller.operations.sync.SyncModelOperationHandler;
import org.jboss.as.controller.operations.sync.SyncModelParameters;
import org.jboss.as.controller.operations.sync.Synchronization;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;

/**
 * Synchronize two standalone server instances model.
 * Itwill synchronize the model only, not the full configuration (extension, etc.).
 * @author Emmanuel Hugonnet (c) 2016 Red Hat, inc.
 */
class ServerModelSyncOperationHandler extends SyncModelOperationHandler {

    protected ServerModelSyncOperationHandler(List<ModelNode> localOperations, Resource remoteModel, Set<String> missingExtensions,
                              SyncModelParameters parameters, OrderedChildTypesAttachment localOrderedChildTypes) {
        super(localOperations, remoteModel, missingExtensions, parameters, localOrderedChildTypes);
    }

    @Override
    protected void fetchMissingConfiguration(OperationContext context, Synchronization.OrderedOperationsCollection operations) {
    }

    @Override
    protected Node createRemoteRoot(OperationContext context) {
        // Describe the operations based on the remote model
        final ModelNode response = ((ServerSyncModelParameters)parameters).readRemoteOperations();
        if (response.hasDefined(FAILURE_DESCRIPTION)) {
            context.getFailureDescription().set(response.get(FAILURE_DESCRIPTION));
            return null;
        }
        final List<ModelNode> remoteOperations = response.get(RESULT).asList();
        OrderedChildTypesAttachment orderedChildTypes = new OrderedChildTypesAttachment();
        if (response.hasDefined(RESPONSE_HEADERS, ORDERED_CHILDREN)) {
            orderedChildTypes.fromModel(response.get(RESPONSE_HEADERS).get(ORDERED_CHILDREN));
        }
        return Node.createOperationTree(remoteOperations, orderedChildTypes);
    }
}
