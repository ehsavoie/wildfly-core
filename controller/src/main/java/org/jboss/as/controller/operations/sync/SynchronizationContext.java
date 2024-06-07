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
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD_INDEX;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OPERATION_HEADERS;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REQUEST_PROPERTIES;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SYNC_REMOVED_FOR_READD;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.as.controller.descriptions.DescriptionProvider;
import org.jboss.as.controller.logging.ControllerLogger;
import org.jboss.as.controller.operations.sync.Synchronization.OrderedOperationsCollection;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.dmr.ModelNode;

/**
 *
 * @author Emmanuel Hugonnet (c) 2016 Red Hat, inc.
 */
public class SynchronizationContext {

    private final Node current;
    private final Node remote;
    /**
     * These can be inserted by doing an add with an index
     */
    private final Set<String> orderedInsertCapableTypes;
    /**
     * These can not be adjusted by doing an insert with an index, instead it will need to remove everything after the
     * index and re-add
     */
    private final Set<String> orderedNotInsertCapableTypes;
    /**
     * These types are not ordered
     */
    private final Set<String> nonOrderedTypes;

    private SynchronizationContext(Node current, Node remote, Set<String> orderedInsertCapableTypes,
            Set<String> orderedNotInsertCapableTypes, Set<String> nonOrderedTypes) {
        this.current = current;
        this.remote = remote;
        this.orderedInsertCapableTypes = orderedInsertCapableTypes;
        this.orderedNotInsertCapableTypes = orderedNotInsertCapableTypes;
        this.nonOrderedTypes = nonOrderedTypes;
    }

    private static SynchronizationContext create(Node current, Node remote) {
        final Set<String> orderedInsertCapableTypes = current.getOrderedInsertCapable();
        final Set<String> orderedNotInsertCapableTypes = current.getOrderedNotInsertCapable(remote);
        Set<String> nonOrderedTypes = current.createNewChildSet();
        for (String type : current.childrenByType.keySet()) {
            if (!orderedInsertCapableTypes.contains(type) && !orderedNotInsertCapableTypes.contains(type)) {
                nonOrderedTypes.add(type);
            }
        }
        for (String type : remote.childrenByType.keySet()) {
            if (!orderedInsertCapableTypes.contains(type) && !orderedNotInsertCapableTypes.contains(type)) {
                nonOrderedTypes.add(type);
            }
        }
        return new SynchronizationContext(current, remote, orderedInsertCapableTypes, orderedNotInsertCapableTypes, nonOrderedTypes);
    }

    static void processChildren(final Node current, final Node remote, final Synchronization.OrderedOperationsCollection operations, final ImmutableManagementResourceRegistration registration) {
        SynchronizationContext childContext = SynchronizationContext.create(current, remote);

        for (String type : childContext.orderedInsertCapableTypes) {
            childContext.processOrderedChildrenOfType(type, operations, registration, true);
        }
        for (String type : childContext.orderedNotInsertCapableTypes) {
            childContext.processOrderedChildrenOfType(type, operations, registration, false);
        }
        for (String type : childContext.nonOrderedTypes) {
            childContext.processNonOrderedChildrenOfType(type, operations, registration);
        }
    }

    private void processOrderedChildrenOfType(final String type, final Synchronization.OrderedOperationsCollection operations, final ImmutableManagementResourceRegistration registration, boolean attemptInsert) {
        final Map<PathElement, Node> remoteChildren = remote.getChildrenOfType(type);
        final Map<PathElement, Node> currentChildren = current.getChildrenOfType(type);

        //Anything which is local only, we can delete right away
        removeCurrentOnlyChildren(currentChildren, remoteChildren, operations, registration);

        //Now figure out the merging strategy
        final Map<PathElement, Integer> currentIndexes = new HashMap<>();
        int i = 0;
        for (PathElement element : currentChildren.keySet()) {
            currentIndexes.put(element, i++);
        }
        Map<Integer, PathElement> addedIndexes = new LinkedHashMap<>();
        i = 0;
        int lastCurrent = -1;
        boolean differentOrder = false;
        boolean allAddsAtEnd = true;
        for (PathElement element : remoteChildren.keySet()) {
            Integer currentIndex = currentIndexes.get(element);
            if (currentIndex == null) {
                addedIndexes.put(i, element);
                if (allAddsAtEnd && i <= currentIndexes.size() - 1) {
                    //Some of the adds are in the middle, requiring an insert or a remove + readd
                    allAddsAtEnd = false;
                }
            } else {
                if (!differentOrder && currentIndex < lastCurrent) {
                    //We can't do inserts, the models have changed too much
                    differentOrder = true;
                }
                lastCurrent = currentIndex;
            }
            i++;
        }
        processOrderedChildModels(currentChildren, remoteChildren, addedIndexes, attemptInsert, differentOrder, allAddsAtEnd,
                operations, registration);
    }

    private static void processOrderedChildModels(final Map<PathElement, Node> currentChildren,
            final Map<PathElement, Node> remoteChildren, Map<Integer, PathElement> addedIndexes,
            boolean attemptInsert, boolean differentOrder,
            boolean allAddsAtEnd, final Synchronization.OrderedOperationsCollection operations, final ImmutableManagementResourceRegistration registration) {
        if (!differentOrder && (addedIndexes.isEmpty() || allAddsAtEnd)) {
            //Just 'compare' everything
            for (Node current : currentChildren.values()) {
                Node remote = remoteChildren.get(current.element);
                compareExistsInBothModels(current, remote, operations, registration.getSubModel(PathAddress.pathAddress(current.element)));
            }
            if (addedIndexes.size() > 0) {
                //Add the new ones to the end
                for (PathElement element : addedIndexes.values()) {
                    Node remote = remoteChildren.get(element);
                    addChildRecursive(remote, operations, registration.getSubModel(PathAddress.pathAddress(element)));
                }
            }
        } else {
            //We had some inserts, add them in order
            boolean added = false;
            if (attemptInsert && !differentOrder) {
                added = true;
                //Do the insert
                int i = 0;
                for (Node remote : remoteChildren.values()) {
                    if (addedIndexes.get(i) != null) {
                        //insert the node
                        remote.add.get(ADD_INDEX).set(i);
                        ImmutableManagementResourceRegistration childReg = registration.getSubModel(PathAddress.pathAddress(remote.element));
                        if (i == 0) {
                            DescriptionProvider desc = childReg.getOperationDescription(PathAddress.EMPTY_ADDRESS, ADD);
                            if (!desc.getModelDescription(Locale.ENGLISH).hasDefined(REQUEST_PROPERTIES, ADD_INDEX)) {
                                //Although the resource type supports ordering, the add handler was not set up to do an indexed add
                                //so we give up and go to remove + re-add
                                added = false;
                                break;
                            }
                        }
                        addChildRecursive(remote, operations, childReg);
                    } else {
                        //'compare' the nodes
                        Node current = currentChildren.get(remote.element);
                        compareExistsInBothModels(current, remote, operations, registration.getSubModel(PathAddress.pathAddress(current.element)));
                    }
                    i++;
                }
            }

            if (!added) {
                //Remove and re-add everything
                //We could do this more fine-grained, but for now let's just drop everything that has been added and readd
                for (Node current : currentChildren.values()) {
                    removeChildRecursive(current, operations, registration.getSubModel(PathAddress.pathAddress(current.element)), true);
                }
                for (Node remote : remoteChildren.values()) {
                    addChildRecursive(remote, operations, registration.getSubModel(PathAddress.pathAddress(remote.element)));
                }
            }
        }
    }

    private void processNonOrderedChildrenOfType(final String type, final OrderedOperationsCollection operations, final ImmutableManagementResourceRegistration registration) {
        final Map<PathElement, Node> remoteChildren = remote.getChildrenOfType(type);
        final Map<PathElement, Node> currentChildren = current.getChildrenOfType(type);
        for (final Node remoteChild : remoteChildren.values()) {
            final Node currentChild = currentChildren == null ? null : currentChildren.remove(remoteChild.element);
            if (currentChild != null && remoteChild != null) {
                compareExistsInBothModels(currentChild, remoteChild, operations, registration.getSubModel(PathAddress.pathAddress(currentChild.element)));
            } else if (currentChild == null && remoteChild != null) {
                addChildRecursive(remoteChild, operations, registration.getSubModel(PathAddress.pathAddress(remoteChild.element)));
            } else if (currentChild != null && remoteChild == null) {
                removeChildRecursive(currentChild, operations, registration.getSubModel(PathAddress.pathAddress(currentChild.element)), false);
            } else {
                throw new IllegalStateException();
            }
        }
        for (final Node currentChild : currentChildren.values()) {
            removeChildRecursive(currentChild, operations, registration.getSubModel(PathAddress.pathAddress(currentChild.element)), false);
        }
    }

    private static void addChildRecursive(Node remote, Synchronization.OrderedOperationsCollection operations, ImmutableManagementResourceRegistration registration) {
        assert remote != null : "remote cannot be null";
        // Just add all the remote operations
        if (remote.add != null) {
            operations.add(remote.add);
        }
        for (final ModelNode operation : remote.attributes.values()) {
            operations.add(operation);
        }
        for (final ModelNode operation : remote.operations) {
            operations.add(operation);
        }
        if(registration == null) {
            return;
        }
        //
        for (final Map.Entry<String, Map<PathElement, Node>> childrenByType : remote.childrenByType.entrySet()) {
            for (final Node child : childrenByType.getValue().values()) {
                if(registration.getSubModel(PathAddress.pathAddress(child.element)) == null) {
                    ControllerLogger.ROOT_LOGGER.warnf("Couldn't find a registration for %s at %s", child.element.toString(), registration.getPathAddress().toCLIStyleString());
                }
                addChildRecursive(child, operations, registration.getSubModel(PathAddress.pathAddress(child.element)));
            }
        }
    }

    private static void removeCurrentOnlyChildren(Map<PathElement, Node> currentChildren, Map<PathElement, Node> remoteChildren, final Synchronization.OrderedOperationsCollection operations, final ImmutableManagementResourceRegistration registration) {
        //Remove everything which exists in current and not in the remote
        List<PathElement> removedElements = new ArrayList<PathElement>();
        for (Node node : currentChildren.values()) {
            if (!remoteChildren.containsKey(node.element)) {
                removedElements.add(node.element);
            }
        }
        for (PathElement removedElement : removedElements) {
            Node removedCurrent = currentChildren.remove(removedElement);
            removeChildRecursive(removedCurrent, operations, registration.getSubModel(PathAddress.pathAddress(removedElement)), false);
        }
    }

    private static void compareExistsInBothModels(Node current, Node remote, OrderedOperationsCollection operations, ImmutableManagementResourceRegistration registration) {
        assert current != null : "current cannot be null";
        assert remote != null : "remote cannot be null";

        // If the current:add() and remote:add() don't match
        if (current.add != null && remote.add != null) {
            if (!current.add.equals(remote.add)) {
                Map<String, ModelNode> remoteAttributes = new  HashMap<>(remote.attributes);
                boolean dropAndReadd = false;
                // Iterate through all local attribute names
                for (String attribute : registration.getAttributeNames(PathAddress.EMPTY_ADDRESS)) {
                    final AttributeAccess access = registration.getAttributeAccess(PathAddress.EMPTY_ADDRESS, attribute);
                    if (access.getStorageType() == AttributeAccess.Storage.CONFIGURATION) {
                        boolean hasCurrent = current.add.hasDefined(attribute);
                        boolean hasRemote = remote.add.hasDefined(attribute);
                        if (access.getAccessType() == AttributeAccess.AccessType.READ_WRITE) {
                            // Compare each attribute
                            if (hasRemote) {
                                // If they are not equals add the remote one
                                if (!hasCurrent || !remote.add.get(attribute).equals(current.add.get(attribute))) {
                                    final ModelNode op = Operations.createWriteAttributeOperation(current.address.toModelNode(), attribute, remote.add.get(attribute));
                                    if (remoteAttributes.containsKey(attribute)) {
                                        throw new IllegalStateException();
                                    }
                                    remoteAttributes.put(attribute, op);
                                }
                            } else if (hasCurrent) {
                                // If there is no remote equivalent undefine the operation
                                final ModelNode op = Operations.createUndefineAttributeOperation(current.address.toModelNode(), attribute);
                                if (remoteAttributes.containsKey(attribute)) {
                                    throw new IllegalStateException();
                                }
                                remoteAttributes.put(attribute, op);
                            }
                        } else if (access.getAccessType() == AttributeAccess.AccessType.READ_ONLY) {
                            ModelNode currentValue = hasCurrent ? current.add.get(attribute) : new ModelNode();
                            ModelNode removeValue = hasRemote ? remote.add.get(attribute) : new ModelNode();
                            if (!currentValue.equals(removeValue)) {
                                //The adds differ in a read-only attribute's value. Since we cannot write to it,
                                //we need to drop it and add it again
                                dropAndReadd = true;
                                break;
                            }
                        }
                    }
                }
                if (dropAndReadd) {
                    removeChildRecursive(current, operations, registration.getSubModel(PathAddress.EMPTY_ADDRESS), true);
                    addChildRecursive(remote, operations, registration.getSubModel(PathAddress.EMPTY_ADDRESS));
                } else {
                    remote.attributes.putAll(remoteAttributes);
                }
            }
            // Process the attributes
            Synchronization.processAttributes(current, remote, operations, registration);
            // TODO process other operations maps, lists etc.
            // Process the children
            processChildren(current, remote, operations, registration);
        }
    }

    private static void removeChildRecursive(Node current, OrderedOperationsCollection operations,
                                      ImmutableManagementResourceRegistration registration, boolean dropForReadd) {
        //The remove operations get processed in reverse order by the operations collection, so add the parent
        //remove before the child remove
        if (registration.getOperationHandler(PathAddress.EMPTY_ADDRESS, REMOVE) != null) {
            final ModelNode op = Operations.createRemoveOperation(current.address.toModelNode());
            if (dropForReadd) {
                op.get(OPERATION_HEADERS, SYNC_REMOVED_FOR_READD).set(true);
            }
            operations.add(op);
        }
        for (final Map.Entry<String, Map<PathElement, Node>> childrenByType : current.childrenByType.entrySet()) {
            for (final Node child : childrenByType.getValue().values()) {
                removeChildRecursive(child, operations, registration.getSubModel(PathAddress.pathAddress(child.element)), dropForReadd);
            }
        }
    }
}
