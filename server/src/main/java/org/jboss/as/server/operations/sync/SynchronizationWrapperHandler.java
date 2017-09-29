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

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinition;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.access.management.SensitiveTargetAccessConstraintDefinition;
import org.jboss.as.server.controller.descriptions.ServerDescriptions;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 *
 * @author Emmanuel Hugonnet (c) 2016 Red Hat, inc.
 */
public class SynchronizationWrapperHandler implements OperationStepHandler {
    private static final SimpleAttributeDefinition DRY_RUN = SimpleAttributeDefinitionBuilder.create("dry-run", ModelType.BOOLEAN, true)
            .setDefaultValue(new ModelNode(false))
            .setAllowExpression(false)
            .build();

    public static final SimpleOperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder("synchronize", ServerDescriptions.getResourceDescriptionResolver(SERVER))
            .addParameter(DRY_RUN)
            .addAccessConstraint(SensitiveTargetAccessConstraintDefinition.READ_WHOLE_CONFIG)
            .setReplyType(ModelType.LIST)
            .setReplyValueType(ModelType.OBJECT)
            .build();

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
        boolean dryRun = DRY_RUN.resolveModelAttribute(context, operation).asBoolean();
        ((SynchronizationService) context.getServiceRegistry(false).getRequiredService(SynchronizationService.SERVICE_NAME).getService()).synchronize(context, dryRun, false);
    }

}
