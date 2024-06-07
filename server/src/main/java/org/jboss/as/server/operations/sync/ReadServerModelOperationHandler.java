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

package org.jboss.as.server.operations.sync;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.controller.operations.common.OrderedChildTypesAttachment.ORDERED_CHILDREN;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.SimpleOperationDefinition;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.access.management.SensitiveTargetAccessConstraintDefinition;
import org.jboss.as.controller.descriptions.common.ControllerResolver;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.as.controller.transform.Transformers;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * Step handler responsible for collecting a complete description of the domain model,
 * which is going to be sent back to a remote host-controller. This is called when the
 * remote slave boots up or when it reconnects to the DC
 *
 * @author John Bailey
 * @author Kabir Khan
 * @author Emanuel Muckenhuber
 */
public class ReadServerModelOperationHandler implements OperationStepHandler {

    public static final String OPERATION_NAME = "read-server-model";
    public static final SimpleOperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(OPERATION_NAME, ControllerResolver.getResolver(SUBSYSTEM))
            .addAccessConstraint(SensitiveTargetAccessConstraintDefinition.READ_WHOLE_CONFIG)
            .setReplyType(ModelType.LIST)
            .setReplyValueType(ModelType.OBJECT)
            .build();

    private final boolean lock;

    public ReadServerModelOperationHandler(boolean lock) {
        this.lock = lock;
    }

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
        // if the calling process has already acquired a lock, don't relock.
        if (lock) {
            context.acquireControllerLock();
        }
        final Transformers.TransformationInputs transformationInputs = new Transformers.TransformationInputs(context);
        final ReadServerModelUtil readUtil = ReadServerModelUtil.readServerResourcesForInitialConnect(Transformers.Factory.createLocal(),
                transformationInputs, Transformers.DEFAULT, transformationInputs.getRootResource());
        context.getResult().set(readUtil.getDescribedResources());
        if(context.getAttachment(OrderedChildTypesAttachment.KEY) != null) {
            context.getResponseHeaders().get(ORDERED_CHILDREN).set(context.getAttachment(OrderedChildTypesAttachment.KEY).toModel());
        }
    }

}
