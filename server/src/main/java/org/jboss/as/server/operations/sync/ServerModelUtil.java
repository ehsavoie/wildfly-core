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
import static org.jboss.as.server.operations.sync.ReadServerModelUtil.ORDERED_CHILD_TYPES_PROPERTY;
import static org.jboss.as.server.operations.sync.ReadServerModelUtil.RESOURCE_ADDRESS;
import static org.jboss.as.server.operations.sync.ReadServerModelUtil.RESOURCE_MODEL;
import static org.jboss.as.server.operations.sync.ReadServerModelUtil.RESOURCE_PROPERTIES;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.registry.Resource;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Emmanuel Hugonnet (c) 2016 Red Hat, inc.
 */
public class ServerModelUtil {
    /**
     * Create a resource based on the result of the {@code ReadMasterDomainModelHandler}.
     *
     * @param result        the operation result
     * @param extensions    set to track extensions
     * @return the resource
     */
    static Resource createResourceFromServerModelOp(final ModelNode result, final Set<String> extensions) {
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
