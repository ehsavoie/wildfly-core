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

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.COMPOSITE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.UUID;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.operations.sync.SyncModelParameters;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.Property;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class ServerFeatureDiffExportOperationHandler extends ServerModelSyncOperationHandler {

    private final Set<String> missingExtensions;
    private final Set<String> superfluousExtensions;

    protected ServerFeatureDiffExportOperationHandler(List<ModelNode> localOperations, Resource remoteModel, Set<String> remoteExtensions,
            SyncModelParameters parameters, OrderedChildTypesAttachment localOrderedChildTypes,
            Set<String> missingExtensions, Set<String> superfluousExtensions) {
        super(localOperations, remoteModel, remoteExtensions, parameters, localOrderedChildTypes);
        this.missingExtensions = missingExtensions;
        this.superfluousExtensions = superfluousExtensions;
    }

    @Override
    protected void processSynchronizationOperations(OperationContext context, final List<ModelNode> ops) {
        ops.forEach(op -> ControllerLogger.ROOT_LOGGER.debugf("Synchronization operations are %s", op));
        final Map<String, ModelNode> configuration = new LinkedHashMap<>();
        if (!missingExtensions.isEmpty()) {
            missingExtensions.forEach(extension -> {
                ModelNode addExtensionOp = Util.createAddOperation(PathAddress.pathAddress("extension", extension));
                toFeatureSpecConfig(context, configuration, addExtensionOp);
            });
        }
        if (!superfluousExtensions.isEmpty()) {
            superfluousExtensions.forEach(extension -> {
                ModelNode removeExtensionOp = Util.createRemoveOperation(PathAddress.pathAddress("extension", extension));
                toFeatureSpecConfig(context, configuration, removeExtensionOp);
            });
        }
        ops.forEach(op -> {
            toFeatureSpecConfig(context, configuration, op);
        });
        ModelNode config = context.getResult().get("configuration").setEmptyList();
        for (Map.Entry<String, ModelNode> configElement : configuration.entrySet()) {
            config.add(configElement.getValue());
        }
        try (ByteArrayOutputStream buffer = new ByteArrayOutputStream()) {
            config.writeBase64(buffer);
            String uuid = context.attachResultStream("application/dmr-encoded", new ByteArrayInputStream(buffer.toByteArray()));
            context.getResult().get(UUID).set(uuid);
        } catch (IOException ex) {
            ex.printStackTrace();
            throw new RuntimeException(ControllerLogger.ROOT_LOGGER.operationHandlerFailed(ex.getMessage()));
        }
    }

    private static void toFeatureSpecConfig(OperationContext context, Map<String, ModelNode> configuration, ModelNode op) {
        ModelNode operation = op.clone();
        if (COMPOSITE.equals(op.get(OP).asString())) {
            op.get(STEPS).asList().forEach(step -> toFeatureSpecConfig(context, configuration, step));
            return;
        }
        PathAddress opAddress;
        if (op.hasDefined(OP_ADDR)) {
            opAddress = PathAddress.pathAddress(op.get(OP_ADDR));
        } else {
            opAddress = PathAddress.EMPTY_ADDRESS;
        }
        ImmutableManagementResourceRegistration registry = context.getRootResourceRegistration().getSubModel(opAddress);
        ModelNode featureConfig;
        if (configuration.containsKey(opAddress.toCLIStyleString())) {
            featureConfig = configuration.get(opAddress.toCLIStyleString());
        } else {
            if (registry.isFeature()) {
                featureConfig = new ModelNode();
                featureConfig.get("feature").get("address").set(opAddress.toModelNode());
                featureConfig.get("feature").get("spec").set(registry.getFeature());
                featureConfig.get("feature").get("exclude").set(REMOVE.equals(op.get(OP).asString()));
                configuration.put(opAddress.toCLIStyleString(), featureConfig);
            } else {
                return;
            }
        }
        operation.remove(OP);
        operation.remove(OP_ADDR);
        for (Property prop : operation.asPropertyList()) {
            featureConfig.get("feature").get("params").add(prop);
        }
    }
}
