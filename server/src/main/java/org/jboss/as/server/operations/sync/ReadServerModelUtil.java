/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2013, Red Hat, Inc., and individual contributors
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


import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.transform.Transformers;
import org.jboss.dmr.ModelNode;

/**
 * Utility for the DC operation handlers to describe the missing resources for the slave hosts which are
 * set up to ignore domain config which does not affect their servers
 *
 * @author <a href="kabir.khan@jboss.com">Kabir Khan</a>
 * @author Emanuel Muckenhuber
 */
public class ReadServerModelUtil {

    public static final String RESOURCE_ADDRESS = "resource-address";

    public static final String RESOURCE_MODEL = "resource-model";

    public static final String RESOURCE_PROPERTIES = "resource-properties";

    public static final String ORDERED_CHILD_TYPES_PROPERTY = "ordered-child-types";

    private final Set<PathElement> newRootResources = new HashSet<>();

    private volatile List<ModelNode> describedResources;

    private ReadServerModelUtil() {
    }

    /**
     * Used to read the domain model when a slave host connects to the DC
     *
     *  @param transformers the transformers for the host
     *  @param transformationInputs parameters for the transformation
     *  @param ignoredTransformationRegistry registry of resources ignored by the transformation target
     *  @param domainRoot the root resource for the domain resource tree
     * @return a read master domain model util instance
     */
    static ReadServerModelUtil readServerResourcesForInitialConnect(final Transformers transformers,
                                                                                final Transformers.TransformationInputs transformationInputs,
                                                                                final Transformers.ResourceIgnoredTransformationRegistry ignoredTransformationRegistry,
                                                                                final Resource domainRoot) throws OperationFailedException {

        Resource transformedResource = transformers.transformRootResource(transformationInputs, domainRoot, ignoredTransformationRegistry);
        ReadServerModelUtil util = new ReadServerModelUtil();
        util.describeAsNodeList(PathAddress.EMPTY_ADDRESS, transformedResource, false);
        return util;
    }

    /**
     * Gets a list of the resources for the slave's ApplyXXXXHandlers. Although the format might appear
     * similar as the operations generated at boot-time this description is only useful
     * to create the resource tree and cannot be used to invoke any operation.
     *
     * @return the resources
     */
    public List<ModelNode> getDescribedResources(){
        return describedResources;
    }

    /**
     * Describe the model as a list of resources with their address and model, which
     * the HC can directly apply to create the model. Although the format might appear
     * similar as the operations generated at boot-time this description is only useful
     * to create the resource tree and cannot be used to invoke any operation.
     *
     * @param rootAddress the address of the root resource being described
     * @param resource the root resource
     */
    private void describeAsNodeList(PathAddress rootAddress, final Resource resource, boolean isRuntimeChange) {
        this.describedResources = new ArrayList<>();
        describe(rootAddress, resource, this.describedResources, isRuntimeChange);
    }

    private void describe(final PathAddress base, final Resource resource, List<ModelNode> nodes, boolean isRuntimeChange) {
        if (resource.isProxy() || resource.isRuntime()) {
            return; // ignore runtime and proxies
        } else if (base.size() >= 1 && base.getElement(0).getKey().equals(ModelDescriptionConstants.HOST)) {
            return; // ignore hosts
        }
        if (base.size() == 1) {
            newRootResources.add(base.getLastElement());
        }
        final ModelNode description = new ModelNode();
        description.get(RESOURCE_ADDRESS).set(base.toModelNode());
        description.get(RESOURCE_MODEL).set(resource.getModel());
        Set<String> orderedChildren = resource.getOrderedChildTypes();
        if (orderedChildren.size() > 0) {
            ModelNode orderedChildTypes = description.get(RESOURCE_PROPERTIES, ORDERED_CHILD_TYPES_PROPERTY);
            for (String type : orderedChildren) {
                orderedChildTypes.add(type);
            }
        }
        nodes.add(description);
        for (final String childType : resource.getChildTypes()) {
            for (final Resource.ResourceEntry entry : resource.getChildren(childType)) {
                describe(base.append(entry.getPathElement()), entry, nodes, isRuntimeChange);
            }
        }
    }

    /**
     * Create a resource based on the result of the {@code ReadMasterDomainModelHandler}.
     *
     * @param result        the operation result
     * @param extensions    set to track extensions
     * @return the resource
     */
    static Resource createResourceFromServerModelOperation(final ModelNode result, final Set<String> extensions) {
        final Resource root = Resource.Factory.create();
        for (ModelNode model : result.asList()) {

            final PathAddress resourceAddress = PathAddress.pathAddress(model.require(RESOURCE_ADDRESS));

            if (resourceAddress.size() == 1) {
                final PathElement element = resourceAddress.getElement(0);
                if (element.getKey().equals(EXTENSION)) {
                    if (!extensions.contains(element.getValue())) {
                        extensions.add(element.getValue());
                    }
                }
            }

            Resource resource = root;
            final Iterator<PathElement> i = resourceAddress.iterator();
            if (!i.hasNext()) { //Those are root attributes
                resource.getModel().set(model.require(RESOURCE_MODEL));
            }
            while (i.hasNext()) {
                final PathElement e = i.next();

                if (resource.hasChild(e)) {
                    resource = resource.getChild(e);
                } else {
                    /*
                    {
                        "domain-resource-address" => [
                            ("profile" => "test"),
                            ("subsystem" => "test")
                        ],
                        "domain-resource-model" => {},
                        "domain-resource-properties" => {"ordered-child-types" => ["ordered-child"]}
                    }*/
                    final Resource nr;
                    if (model.hasDefined(RESOURCE_PROPERTIES, ORDERED_CHILD_TYPES_PROPERTY)) {
                        List<ModelNode> list = model.get(RESOURCE_PROPERTIES, ORDERED_CHILD_TYPES_PROPERTY).asList();
                        Set<String> orderedChildTypes = new HashSet<String>(list.size());
                        for (ModelNode type : list) {
                            orderedChildTypes.add(type.asString());
                        }
                        nr = Resource.Factory.create(false, orderedChildTypes);
                    } else {
                        nr = Resource.Factory.create();
                    }
                    resource.registerChild(e, nr);
                    resource = nr;
                }

                if (!i.hasNext()) {
                    resource.getModel().set(model.require(RESOURCE_MODEL));
                }
            }
        }
        return root;
    }
}
