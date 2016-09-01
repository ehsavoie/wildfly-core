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
package org.jboss.as.controller.operations.sync;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.CORE_SERVICE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.DEPLOYMENT_OVERLAY;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.EXTENSION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.INTERFACE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MANAGEMENT_CLIENT_CONTENT;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PATH;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.PROFILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SOCKET_BINDING_GROUP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SYSTEM_PROPERTY;
import org.jboss.as.controller.operations.common.OrderedChildTypesAttachment;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Emmanuel Hugonnet (c) 2016 Red Hat, inc.
 */
public class Node {

    public final PathElement element;
    public final PathAddress address;
    public ModelNode add;
    public Map<String, ModelNode> attributes = new HashMap<>();
    public final List<ModelNode> operations = new ArrayList<>();
    public final Set<String> orderedChildTypes = new HashSet<>();
    public final Map<String, Map<PathElement, Node>> childrenByType;

    private Node(PathElement element, PathAddress address) {
        this.element = element;
        this.address = address;
        this.childrenByType = element == null ? new TreeMap<>(RootNodeComparator.ROOT_NODE_COMPARATOR) : // The root node uses a pre-defined order
        new LinkedHashMap<>();
    } // The root node uses a pre-defined order

    private Node getOrCreate(final PathElement element, final Iterator<PathElement> i, PathAddress current, OrderedChildTypesAttachment orderedChildTypesAttachment) {
        if (i.hasNext()) {
            final PathElement next = i.next();
            final PathAddress addr = current.append(next);
            Map<PathElement, Node> children = childrenByType.get(next.getKey());
            if (children == null) {
                children = new LinkedHashMap<>();
                childrenByType.put(next.getKey(), children);
            }
            Node node = children.get(next);
            if (node == null) {
                node = new Node(next, addr);
                children.put(next, node);
                Set<String> orderedChildTypes = orderedChildTypesAttachment.getOrderedChildTypes(addr);
                if (orderedChildTypes != null) {
                    node.orderedChildTypes.addAll(orderedChildTypes);
                }
            }
            return node.getOrCreate(next, i, addr, orderedChildTypesAttachment);
        } else if (element == null) {
            throw new IllegalStateException();
        } else {
            if (address.equals(current)) {
                return this;
            } else {
                throw new IllegalStateException(current.toString());
            }
        }
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        toString(sb, 0);
        return sb.toString();
    }

    private void toString(StringBuilder builder, int depth) {
        for (int i = 0; i < depth; i++) {
            builder.append(" ");
        }
        builder.append("Node: {").append(address).append("\n");
        for (Map<PathElement, Node> children : childrenByType.values()) {
            for (Node child : children.values()) {
                child.toString(builder, depth + 1);
            }
        }
        for (int i = 0; i < depth; i++) {
            builder.append(" ");
        }
        builder.append("}\n");
    }

    Set<String> createNewChildSet() {
        if (element == null) {
            return new TreeSet<>(RootNodeComparator.ROOT_NODE_COMPARATOR);
        }
        return new HashSet<>();
    }

    Set<String> getOrderedInsertCapable() {
        if (orderedChildTypes.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(orderedChildTypes);
    }

    Map<PathElement, Node> getChildrenOfType(String type) {
        Map<PathElement, Node> map = childrenByType.get(type);
        if (map != null) {
            return map;
        }
        return Collections.emptyMap();
    }

    Set<String> getOrderedNotInsertCapable(Node remote) {
        return remote.orderedChildTypes.stream().filter(type -> !orderedChildTypes.contains(type)).collect(Collectors.toSet());
    }

    public static final Node createOperationTree(final List<ModelNode> operations,
            OrderedChildTypesAttachment orderedChildTypesAttachment) {
        Node rootNode = new Node(null, PathAddress.EMPTY_ADDRESS);
        for (final ModelNode operation : operations) {
            final String operationName = operation.get(OP).asString();
            final PathAddress address = PathAddress.pathAddress(operation.require(OP_ADDR));
            final Node node;
            if (address.size() == 0) {
                node = rootNode;
            } else {
                node = rootNode.getOrCreate(null, address.iterator(), PathAddress.EMPTY_ADDRESS, orderedChildTypesAttachment);
            }
            switch (operationName) {
                case ADD:
                    node.add = operation;
                    break;
                case WRITE_ATTRIBUTE_OPERATION:
                    final String name = operation.get(NAME).asString();
                    node.attributes.put(name, operation);
                    break;
                default:
                    node.operations.add(operation);
                    break;
            }
        }
        return rootNode;
    }
    private static class RootNodeComparator implements Comparator<String> {

        private static final RootNodeComparator ROOT_NODE_COMPARATOR = new RootNodeComparator();
        private final Map<String, Integer> orderedChildTypes;

        private RootNodeComparator() {
            //The order here is important for the direct children of the root resource
            String[] orderedTypes = new String[]{EXTENSION, //Extensions need to be done before everything else (and separately WFCORE-323)
                SYSTEM_PROPERTY, //Everything might use system properties
                PATH, //A lot of later stuff might need paths
                CORE_SERVICE,
                PROFILE, //Used by server-group
                INTERFACE, //Used by socket-binding-group
                SOCKET_BINDING_GROUP, //Used by server-group; needs interface
                DEPLOYMENT, //Used by server-group
                DEPLOYMENT_OVERLAY, //Used by server-group
                MANAGEMENT_CLIENT_CONTENT, //Used by server-group
                SERVER_GROUP};                  //Uses profile, socket-binding-group, deployment, deployment-overlay and management-client-content
            Map<String, Integer> map = new HashMap<>();
            for (int i = 0; i < orderedTypes.length; i++) {
                map.put(orderedTypes[i], i);
            }
            orderedChildTypes = Collections.unmodifiableMap(map);
        }

        @Override
        public int compare(String type1, String type2) {
            if (type1.equals(type2)) {
                return 0;
            }
            if (getIndex(type1) < getIndex(type2)) {
                return -1;
            }
            return 1;
        }

        private int getIndex(String type) {
            Integer i = orderedChildTypes.get(type);
            if (i != null) {
                return i;
            }
            return -1;
        }
    }
}
