/*
 * Copyright 2017 JBoss by Red Hat.
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



import java.util.List;
import java.util.Set;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.operations.sync.SyncModelParameters;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class ServerModelDiffOperationHandler extends ServerModelSyncOperationHandler {

    private Set<String> missingExtensions;
    private Set<String> superfluousExtensions;
    protected ServerModelDiffOperationHandler(List<ModelNode> localOperations, Resource remoteModel, Set<String> remoteExtensions,
                              SyncModelParameters parameters, OrderedChildTypesAttachment localOrderedChildTypes,
                              Set<String> missingExtensions, Set<String> superfluousExtensions) {
        super(localOperations, remoteModel, remoteExtensions, parameters, localOrderedChildTypes);
        this.missingExtensions = missingExtensions;
        this.superfluousExtensions = superfluousExtensions;
    }

    @Override
    protected void processSynchronizationOperations(OperationContext context, final List<ModelNode> ops) {
        ops.forEach(op -> ControllerLogger.ROOT_LOGGER.debugf("Synchronization operations are %s", op));
        ModelNode result = new ModelNode();
        if (!missingExtensions.isEmpty()) {
            final ModelNode extensionOps = result.get("extension-op");
            extensionOps.setEmptyList();
            missingExtensions.forEach(extension -> {
                ModelNode addExtensionOp = Util.createAddOperation(PathAddress.pathAddress("extension", extension));
                addExtensionOp.get("module").set(extension);
                extensionOps.add(addExtensionOp);
            });
        }
        if (!superfluousExtensions.isEmpty()) {
            final ModelNode extensionOps = result.get("extension-op");
            superfluousExtensions.forEach(extension -> {
                ModelNode removeExtensionOp = Util.createRemoveOperation(PathAddress.pathAddress("extension", extension));
                extensionOps.add(removeExtensionOp);
            });
        }
        ModelNode syncOps = result.get("sync-op");
        syncOps.setEmptyList();
        ops.forEach(op -> {
            syncOps.add(op);
        });
        context.getResult().set(result);
    }
}
