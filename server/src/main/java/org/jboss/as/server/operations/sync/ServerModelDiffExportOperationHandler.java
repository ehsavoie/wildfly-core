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
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.STEPS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.UUID;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
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
import org.jboss.dmr.ModelType;

/**
 *
 * @author Emmanuel Hugonnet (c) 2017 Red Hat, inc.
 */
public class ServerModelDiffExportOperationHandler extends ServerModelSyncOperationHandler {

    private Set<String> missingExtensions;
    private Set<String> superfluousExtensions;
    protected ServerModelDiffExportOperationHandler(List<ModelNode> localOperations, Resource remoteModel, Set<String> remoteExtensions,
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
        final StringWriter buffer = new StringWriter();
        if (!missingExtensions.isEmpty()) {
            missingExtensions.forEach(extension -> {
                ModelNode addExtensionOp = Util.createAddOperation(PathAddress.pathAddress("extension", extension));
                addExtensionOp.get("module").set(extension);
                buffer.write(toCliLine(addExtensionOp));
            });
        }
        if (!superfluousExtensions.isEmpty()) {
            superfluousExtensions.forEach(extension -> {
                ModelNode removeExtensionOp = Util.createRemoveOperation(PathAddress.pathAddress("extension", extension));
                buffer.write(toCliLine(removeExtensionOp));
            });
        }
        ops.forEach(op -> {
            buffer.write(toCliLine(op));
        });
        String uuid = context.attachResultStream("text/jboss-cli", new ByteArrayInputStream(buffer.toString().getBytes(StandardCharsets.UTF_8)));
        context.getResult().get(UUID).set(uuid);
    }

    private static String toCliLine(ModelNode op) {
        final StringBuilder buf = new StringBuilder();
        if(COMPOSITE.equals(op.get(OP).asString())) {
//            buf.append("batch\n");
            op.get(STEPS).asList().forEach(step -> buf.append(toCliLine(step)));
//            buf.append("run-batch\n");
            return buf.toString();
        }
        PathAddress opAddress;
        if (op.hasDefined(OP_ADDR)) {
            opAddress = PathAddress.pathAddress(op.get(OP_ADDR));
        } else {
            opAddress = PathAddress.EMPTY_ADDRESS;
        }
        buf.append(PathAddress.pathAddress(op.get(OP_ADDR)).toCLIStyleString());
        buf.append(':').append(op.get(OP).asString());
        if (op.keys().size() > 2) {
            buf.append('(');
            int p = 0;
            for (String key : op.keys()) {
                if (OP_ADDR.equals(key) || OP.equals(key) || !op.hasDefined(key)) {
                    continue;
                }
                if(NAME.equals(key) && op.hasDefined(key) && opAddress.size() > 0 && opAddress.getLastElement().getValue().equals(op.get(key).asString())) {
                    continue;
                }
                if (p++ > 0) {
                    buf.append(',');
                }
                buf.append(key).append("=");
                final ModelNode value = op.get(key);
                final boolean complexType = ModelType.OBJECT.equals(value.getType()) || ModelType.LIST.equals(value.getType())
                        || ModelType.PROPERTY.equals(value.getType());
                final String strValue = value.asString();
                if (!complexType) {
                    buf.append("\"");
                    if (!strValue.isEmpty() && strValue.charAt(0) == '$') {
                        buf.append('\\');
                    }
                }
                buf.append(strValue);
                if (!complexType) {
                    buf.append('"');
                }
            }
            buf.append(')');
        }
        buf.append('\n');
        return buf.toString();
    }
}
