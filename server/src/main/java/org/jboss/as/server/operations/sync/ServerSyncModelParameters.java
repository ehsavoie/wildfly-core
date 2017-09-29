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

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PATH;

import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.operations.common.ProcessEnvironment;
import org.jboss.as.controller.operations.sync.SyncModelParameters;
import org.jboss.as.controller.services.path.PathManager;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Emmanuel Hugonnet (c) 2016 Red Hat, inc.
 */
public class ServerSyncModelParameters extends SyncModelParameters {

    private final SynchronizationService syncService;
    private final PathManager pathManager;

    public ServerSyncModelParameters(SynchronizationService syncService, ExpressionResolver expressionResolver,
            ProcessEnvironment environment, ExtensionRegistry extensionRegistry, PathManager pathManager, boolean fullModelTransfer) {
        super(expressionResolver, environment, extensionRegistry, fullModelTransfer);
        this.syncService = syncService;
        this.pathManager = pathManager;
    }

    @Override
    public boolean isResourceExcluded(PathAddress address) {
        if (address.size() == 1 && EXTENSION.equals(address.getElement(0).getKey())) {
            return true;
        }
        if (address.size() == 1 && PATH.equals(address.getElement(0).getKey())) {
            return pathManager.getPathEntry(address.getElement(0).getValue()).isReadOnly();
        }
        return false;
    }

    @Override
    public void initializeModelSync() {
    }

    @Override
    public void complete(boolean rollback) {
    }

    public ModelNode readLocalModel() {
        return syncService.readLocalModel();
    }

    public ModelNode readRemoteOperations() {
         return syncService.readRemoteOperations();
    }

    public ModelNode readLocalOperations() {
        return syncService.readLocalOperations();
    }
}
