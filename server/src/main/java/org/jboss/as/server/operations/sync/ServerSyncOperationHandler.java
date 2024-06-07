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


import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_MODEL;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.as.controller.Extension;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.extension.ExtensionRegistryType;
import org.jboss.as.controller.extension.ExtensionResource;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.server.logging.ServerLogger;
import org.jboss.dmr.ModelNode;
import org.jboss.modules.Module;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.modules.ModuleLoadException;

/**
 * Synchronize two standalone server instances.
 * It will first install all the missing extensions before invoking the ServerModelSyncOperationHandler which will synchronize the model itself.
 * @author Emmanuel Hugonnet (c) 2016 Red Hat, inc.
 */
public class ServerSyncOperationHandler implements OperationStepHandler {
    protected final ServerSyncModelParameters parameters;
    protected final boolean dryRun;
    protected final boolean export;
    protected final boolean config;

    protected ServerSyncOperationHandler(ServerSyncModelParameters parameters, final boolean dryRun, final boolean export, boolean config) {
        this.parameters = parameters;
        this.dryRun = dryRun;
        this.export = export;
        this.config = config;
    }


    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {

        // Create the remote model based on the result of the read-master-model operation
        final Set<String> remoteExtensions = new HashSet<>();
        final Resource remote = ReadServerModelUtil.createResourceFromServerModelOperation(operation.require(DOMAIN_MODEL), remoteExtensions);

        // Describe the local model
        final ModelNode localModel = parameters.readLocalModel();
        if (localModel.hasDefined(FAILURE_DESCRIPTION)) {
            context.getFailureDescription().set(localModel.get(FAILURE_DESCRIPTION));
            return;
        }

        // Translate the local domain-model to a resource
        final Set<String> localExtensions = new HashSet<>();
        ReadServerModelUtil.createResourceFromServerModelOperation(localModel, localExtensions);

        // Create the local describe operations
        final ModelNode localOperations = parameters.readLocalOperations();
        if (localOperations.hasDefined(FAILURE_DESCRIPTION)) {
            context.getFailureDescription().set(localOperations.get(FAILURE_DESCRIPTION));
            return;
        }
        final Set<String> superfluousExtensions = new HashSet<>(localExtensions).stream().filter(extension -> !remoteExtensions.contains(extension)).collect(Collectors.toSet());
        // Determine the extensions we are missing locally
        for (final String extension : localExtensions) {
            remoteExtensions.remove(extension);
        }
        final Set<String> missingExtensions = new HashSet<>(remoteExtensions);
        //Adding the missing extensions
        if(!remoteExtensions.isEmpty()) {
            final ManagementResourceRegistration registration = context.getResourceRegistrationForUpdate();
            Iterator<String> missingExtensionIter = remoteExtensions.iterator();
            while(missingExtensionIter.hasNext()) {
                String extension = missingExtensionIter.next();
                context.addResource(PathAddress.pathAddress(EXTENSION, extension), new ExtensionResource(extension, parameters.getExtensionRegistry()));
                initializeExtension(extension, registration);
                missingExtensionIter.remove();
            }
            context.completeStep(new OperationContext.RollbackHandler() {
                @Override
                public void handleRollback(OperationContext context, ModelNode operation) {
                    if (!missingExtensions.isEmpty()) {
                        for (String extension : missingExtensions) {
                            parameters.getExtensionRegistry().removeExtension(new ExtensionResource(extension, parameters.getExtensionRegistry()), extension, registration);
                        }
                    }
                }
            });
        }

        context.addStep(new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                final ModelNode result = localOperations;
                OrderedChildTypesAttachment orderedChildTypesAttachment = context.getAttachment(OrderedChildTypesAttachment.KEY);
                if(orderedChildTypesAttachment == null) {
                    orderedChildTypesAttachment = new OrderedChildTypesAttachment();
                }
                final ServerModelSyncOperationHandler handler;
                if (dryRun) {
                    if (export) {
                        if(config) {
                            handler = new ServerFeatureDiffExportOperationHandler(result.asList(), remote, remoteExtensions,
                                    parameters, orderedChildTypesAttachment, missingExtensions, superfluousExtensions);
                        } else {
                            handler = new ServerModelDiffExportOperationHandler(result.asList(), remote, remoteExtensions,
                                    parameters, orderedChildTypesAttachment, missingExtensions, superfluousExtensions);
                        }
                    } else {
                        handler = new ServerModelDiffOperationHandler(result.asList(), remote, remoteExtensions,
                                parameters, orderedChildTypesAttachment, missingExtensions, superfluousExtensions);
                    }
                } else {
                    handler = new ServerModelSyncOperationHandler(result.asList(), remote, remoteExtensions,
                            parameters, orderedChildTypesAttachment);
                }
                context.addStep(operation, handler, OperationContext.Stage.MODEL, true);
            }
        }, OperationContext.Stage.MODEL, true);

        if (!missingExtensions.isEmpty() && dryRun) {
            context.addStep(new OperationStepHandler() {
                @Override
                public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                    final ManagementResourceRegistration registration = context.getResourceRegistrationForUpdate();
                    for (String extension : missingExtensions) {
                        context.removeResource(PathAddress.pathAddress(EXTENSION, extension));
                        parameters.getExtensionRegistry().removeExtension(new ExtensionResource(extension, parameters.getExtensionRegistry()), extension, registration);
                    }
                }
            }, OperationContext.Stage.MODEL);
        }
    }

    private void initializeExtension(String module, ManagementResourceRegistration rootRegistration) throws OperationFailedException {
        try {
            for (final Extension extension : Module.loadServiceFromCallerModuleLoader(module, Extension.class)) {
                ClassLoader oldTccl = SecurityActions.setThreadContextClassLoader(extension.getClass());
                try {
                    extension.initializeParsers(parameters.getExtensionRegistry().getExtensionParsingContext(module, null));
                    extension.initialize(parameters.getExtensionRegistry().getExtensionContext(module, rootRegistration, ExtensionRegistryType.SERVER));
                } finally {
                    SecurityActions.setThreadContextClassLoader(oldTccl);
                }
            }
        } catch (ModuleLoadException e) {
            throw new OperationFailedException(ServerLogger.ROOT_LOGGER.failedToLoadModule(ModuleIdentifier.fromString(module), e).getMessage(), e);
        }
    }
}
