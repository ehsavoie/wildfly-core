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

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DOMAIN_ORGANIZATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.RESULT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.VALUE;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.SimpleOperationDefinition;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.access.management.SensitiveTargetAccessConstraintDefinition;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.common.ControllerResolver;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 * A generic model "describe" handler, returning a list of operations which is needed to create an equivalent model.
 *
 * @author Emanuel Muckenhuber
 */
public class GenericModelDescribeOperationHandler implements OperationStepHandler {
    private static final String OPERATION_NAME = "describe-model";
    public static final GenericModelDescribeOperationHandler INSTANCE = new GenericModelDescribeOperationHandler(OPERATION_NAME, false);

    public static final SimpleOperationDefinition DEFINITION = new SimpleOperationDefinitionBuilder(OPERATION_NAME, ControllerResolver.getResolver(SUBSYSTEM))
            .addAccessConstraint(SensitiveTargetAccessConstraintDefinition.READ_WHOLE_CONFIG)
            .setReplyType(ModelType.LIST)
            .setReplyValueType(ModelType.OBJECT)
            .setPrivateEntry()
            .build();

    private static final Set<String> ROOT_ATTRIBUTES = new HashSet<>(Arrays.asList(DOMAIN_ORGANIZATION));
    private static final Set<String> FULL_ROOT_ATTRIBUTES = new HashSet<>(Arrays.asList(DOMAIN_ORGANIZATION, NAME));
    private final String operationName;
    private final boolean skipLocalAdd;
    protected GenericModelDescribeOperationHandler(final String operationName, final boolean skipAdd) {
        this.operationName = operationName;
        this.skipLocalAdd = skipAdd;
    }

    @Override
    public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
        final PathAddress address = PathAddress.pathAddress(operation.require(ModelDescriptionConstants.OP_ADDR));
        final PathAddressFilter filter = context.getAttachment(PathAddressFilter.KEY);
        if (filter != null && ! filter.accepts(address)) {
            return;
        }
        final ImmutableManagementResourceRegistration registration = context.getResourceRegistration();
        if (registration.isAlias() || registration.isRemote() || registration.isRuntimeOnly()) {
            return;
        }
        final Resource resource = context.readResource(PathAddress.EMPTY_ADDRESS, false);
        final ModelNode result = context.getResult();
        result.setEmptyList();
        final ModelNode results = new ModelNode().setEmptyList();
        final AtomicReference<ModelNode> failureRef = new AtomicReference<>();
        final Map<String, ModelNode> includeResults = new HashMap<>();

        // Step to handle failed operations
        context.addStep(new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                boolean failed = false;
                if (failureRef.get() != null) {
                    // One of our subsystems failed
                    context.getFailureDescription().set(failureRef.get());
                    failed = true;
                } else {
                    for (final ModelNode includeRsp : includeResults.values()) {
                        if (includeRsp.hasDefined(FAILURE_DESCRIPTION)) {
                            context.getFailureDescription().set(includeRsp.get(FAILURE_DESCRIPTION));
                            failed = true;
                            break;
                        }
                        final ModelNode includeResult = includeRsp.get(RESULT);
                        if (includeResult.isDefined()) {
                            for (ModelNode op : includeResult.asList()) {
                                addOrderedChildTypeInfo(context, resource, op);
                                result.add(op);
                            }
                        }
                    }
                }
                if (!failed) {
                    for (final ModelNode childRsp : results.asList()) {
                        addOrderedChildTypeInfo(context, resource, childRsp);
                        result.add(childRsp);
                    }
                    context.getResult().set(result);
                }
            }
        }, OperationContext.Stage.MODEL, true);

        describeChildren(resource, registration, filter, address, context, failureRef, results, operation);
        if (resource.isProxy() || resource.isRuntime()) {
            return;
        }
        appendGenericOperation(resource, registration, includeResults, operation, address, context);
    }

    private void describeChildren(final Resource resource, final ImmutableManagementResourceRegistration registration, final PathAddressFilter filter, final PathAddress address, OperationContext context, final AtomicReference<ModelNode> failureRef, final ModelNode results, ModelNode operation) {
        resource.getChildTypes().forEach((childType) -> {
            resource.getChildren(childType).stream()
                    .filter(entry -> {
                        final ImmutableManagementResourceRegistration childRegistration = registration.getSubModel(PathAddress.EMPTY_ADDRESS.append(entry.getPathElement()));
                        if(childRegistration == null) {
                            ControllerLogger.ROOT_LOGGER.warnf("Couldn't find a registration for %s at %s for resource %s at %s", entry.getPathElement().toString(), registration.getPathAddress().toCLIStyleString(), resource, address.toCLIStyleString());
                            return false;
                        }
                        return !childRegistration.isRuntimeOnly() && !childRegistration.isRemote() && !childRegistration.isAlias();
                    })
                    .filter(entry ->  filter == null || filter.accepts(address.append(entry.getPathElement())))
                    .forEach((entry) -> describeChildResource(entry, registration, address, context, failureRef, results, operation));
        });
    }

    private void appendGenericOperation(final Resource resource, final ImmutableManagementResourceRegistration registration, final Map<String, ModelNode> includeResults, ModelNode operation, final PathAddress address, OperationContext context) throws OperationFailedException {
        // Generic operation generation
        final ModelNode model = resource.getModel();
        if (registration.getOperationHandler(PathAddress.EMPTY_ADDRESS, ModelDescriptionConstants.ADD) != null) {
            appendAddResourceOperation(registration, includeResults, operation, address, context, resource);
        } else {
            registration.getAttributeNames(PathAddress.EMPTY_ADDRESS).stream()
                    .filter(attribute -> model.hasDefined(attribute))
                    .filter(attribute -> address.size() != 0 || isAcceptedRootAttribute(context, attribute))
                    .filter(attribute -> registration.getAttributeAccess(PathAddress.EMPTY_ADDRESS, attribute).getStorageType() == AttributeAccess.Storage.CONFIGURATION)
                    .forEach(attribute -> appendWriteAttributeOperation(address, context, resource, attribute));
        }
    }

    private boolean isAcceptedRootAttribute(OperationContext context, String attribute) {
        if(context.getProcessType().isServer()) {
            return FULL_ROOT_ATTRIBUTES.contains(attribute);
        }
        return ROOT_ATTRIBUTES.contains(attribute);
    }
    private void appendAddResourceOperation(final ImmutableManagementResourceRegistration registration,
            final Map<String, ModelNode> includeResults, final ModelNode operation, final PathAddress address,
            final OperationContext context, final Resource resource) throws OperationFailedException {
        final ModelNode model = resource.getModel();
        final ModelNode add = new ModelNode();
        add.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.ADD);
        add.get(ModelDescriptionConstants.OP_ADDR).set(address.toModelNode());
        registration.getAttributeNames(PathAddress.EMPTY_ADDRESS).stream()
                    .filter(attribute -> model.hasDefined(attribute))
                    .filter(attribute -> registration.getAttributeAccess(PathAddress.EMPTY_ADDRESS, attribute).getStorageType() == AttributeAccess.Storage.CONFIGURATION)
                    .forEach(attribute -> add.get(attribute).set(model.get(attribute)));
        // Allow the profile describe handler to process profile includes
        processMore(context, operation, resource, address, includeResults);
        if (!skipLocalAdd) {
            addOrderedChildTypeInfo(context, resource, add);
            context.getResult().add(add);
        }
    }

    private void appendWriteAttributeOperation(final PathAddress address, final OperationContext context, final Resource resource, String attribute) {
        final ModelNode model = resource.getModel();
        final ModelNode writeAttribute = new ModelNode();
        writeAttribute.get(ModelDescriptionConstants.OP).set(ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION);
        writeAttribute.get(ModelDescriptionConstants.OP_ADDR).set(address.toModelNode());
        writeAttribute.get(NAME).set(attribute);
        writeAttribute.get(VALUE).set(model.get(attribute));
        addOrderedChildTypeInfo(context, resource, writeAttribute);
        context.getResult().add(writeAttribute);
    }

    private void describeChildResource(final Resource.ResourceEntry entry,
            final ImmutableManagementResourceRegistration registration, final PathAddress address,
            OperationContext context, final AtomicReference<ModelNode> failureRef,
            final ModelNode results, ModelNode operation) throws IllegalArgumentException {
        final ModelNode childRsp = new ModelNode();
        context.addStep(new OperationStepHandler() {
            @Override
            public void execute(OperationContext context, ModelNode operation) throws OperationFailedException {
                if (failureRef.get() == null) {
                    if (childRsp.hasDefined(FAILURE_DESCRIPTION)) {
                        failureRef.set(childRsp.get(FAILURE_DESCRIPTION));
                    } else if (childRsp.hasDefined(RESULT)) {
                        addChildOperation(address, childRsp.require(RESULT).asList(), results);
                    }
                }
            }
        }, OperationContext.Stage.MODEL, true);
        final ModelNode childOperation = operation.clone();
        childOperation.get(ModelDescriptionConstants.OP).set(operationName);
        final PathElement childPE = entry.getPathElement();
        childOperation.get(ModelDescriptionConstants.OP_ADDR).set(address.append(childPE).toModelNode());
        final ImmutableManagementResourceRegistration childRegistration = registration.getSubModel(PathAddress.EMPTY_ADDRESS.append(childPE));
        final OperationStepHandler stepHandler = childRegistration.getOperationHandler(PathAddress.EMPTY_ADDRESS, operationName);
        context.addStep(childRsp, childOperation, stepHandler, OperationContext.Stage.MODEL, true);
    }

    private void addOrderedChildTypeInfo(OperationContext context, Resource resource, ModelNode operation) {
        OrderedChildTypesAttachment attachment = context.getAttachment(OrderedChildTypesAttachment.KEY);
        if (attachment != null) {
            attachment.addOrderedChildResourceTypes(PathAddress.pathAddress(operation.get(OP_ADDR)), resource);
        }
    }

    protected void addChildOperation(final PathAddress parent, final List<ModelNode> operations, ModelNode results) {
        for (final ModelNode operation : operations) {
            results.add(operation);
        }
    }

    protected void processMore(final OperationContext context, final ModelNode operation, final Resource resource, final PathAddress address, final Map<String, ModelNode> includeResults) throws OperationFailedException {

    }

}
