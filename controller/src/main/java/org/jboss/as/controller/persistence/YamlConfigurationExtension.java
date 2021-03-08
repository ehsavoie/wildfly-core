/*
 * Copyright 2021 JBoss by Red Hat.
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
package org.jboss.as.controller.persistence;

import static java.util.function.Function.identity;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.MODULE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.VALUE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION;
import static org.jboss.as.controller.logging.ControllerLogger.MGMT_OP_LOGGER;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ListAttributeDefinition;
import org.jboss.as.controller.ObjectTypeAttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.ParsedBootOp;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.dmr.ModelNode;
import org.yaml.snakeyaml.Yaml;

/**
 *
 * @author Emmanuel Hugonnet (c) 2021 Red Hat, Inc.
 */
public class YamlConfigurationExtension implements ConfigurationExtension {

    private static final String CONFIGURATION_ROOT_KEY = "wildfly-bootable";
    private static final String EXTENSION_KEY = "extension";

    private final Map<String, Object> config;

    @SuppressWarnings("unchecked")
    public YamlConfigurationExtension(Path file) {
        if (file != null && Files.exists(file) && Files.isRegularFile(file)) {
            Map<String, Object> yamlConfig = Collections.emptyMap();
            try (InputStream inputStream = Files.newInputStream(file)) {
                Yaml yaml = new Yaml();
                yamlConfig = yaml.load(inputStream);
            } catch (IOException ioex) {
                MGMT_OP_LOGGER.warn("Error parsing yaml file %s ", file.toString(), ioex);
            }
            if (yamlConfig.containsKey(CONFIGURATION_ROOT_KEY)) {
                this.config = (Map<String, Object>) yamlConfig.get(CONFIGURATION_ROOT_KEY);
            } else {
                this.config = Collections.emptyMap();
            }
        } else {
            this.config = Collections.emptyMap();
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void processExtensions(ManagementResourceRegistration rootRegistration, List<ParsedBootOp> initialOps) {
        Map<PathAddress, ParsedBootOp> initialExtensions = initialOps.stream().filter(op -> op.getAddress().size() > 0).collect(Collectors.toMap(ParsedBootOp::getAddress, identity()));
        if (this.config.containsKey(EXTENSION_KEY)) {
            @SuppressWarnings("unchecked")
            Map<String, Object> extensions = (Map<String, Object>) this.config.get(EXTENSION_KEY);
            for (String extension : extensions.keySet()) {
                PathAddress address = PathAddress.pathAddress("extension", extension);
                if (!initialExtensions.containsKey(address)) {
                    ModelNode op = new ModelNode();
                    op.get(OP).set(ADD);
                    op.get(OP_ADDR).set(address.toModelNode());
                    op.get(MODULE).set(((Map<String, Object>) extensions.get(extension)).get("module").toString());
                    final OperationStepHandler stepHandler = rootRegistration.getOperationHandler(address, ADD);
                    initialOps.add(new ParsedBootOp(op, stepHandler));
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void processOperations(OperationContext context, ImmutableManagementResourceRegistration rootRegistration, List<ParsedBootOp> postExtensionOps) {
        Map<PathAddress, ParsedBootOp> xmlOperations = new HashMap<>();
        for (ParsedBootOp op : postExtensionOps) {
            if (op.getChildOperations().isEmpty()) {
                xmlOperations.put(op.getAddress(), op);
            } else {
                for (ModelNode childOp : op.getChildOperations()) {
                    ParsedBootOp subOp = new ParsedBootOp(childOp, null);
                    xmlOperations.put(subOp.getAddress(), subOp);
                }
            }
        }
        Map<String, Object> configuration = new HashMap<>(config);
        configuration.remove(EXTENSION_KEY);
        processResource(PathAddress.EMPTY_ADDRESS, config, context, rootRegistration, rootRegistration, xmlOperations, postExtensionOps, false);
    }

    private void processResource(PathAddress parentAddress, Map<String, Object> yaml, OperationContext context, ImmutableManagementResourceRegistration rootRegistration, ImmutableManagementResourceRegistration resourceRegistration, Map<PathAddress, ParsedBootOp> xmlOperations, List<ParsedBootOp> postExtensionOps, boolean placeHolder) {
        for (String name : yaml.keySet()) {
            if (resourceRegistration.getChildNames(PathAddress.EMPTY_ADDRESS).contains(name) || placeHolder) {
                // we are going into a child resource
                PathAddress address;
                if (placeHolder) {
                    address = parentAddress.getParent().append(parentAddress.getLastElement().getKey(), name);
                } else {
                    address = parentAddress.append(name);
                }
                Object value = yaml.get(name);
                if (value instanceof Map) {
                    Map<String, Object> map = (Map<String, Object>) value;
                    ImmutableManagementResourceRegistration childResourceRegistration = rootRegistration.getSubModel(address);
                    if (childResourceRegistration != null) {
                        processResource(address, map, context, rootRegistration, childResourceRegistration, xmlOperations, postExtensionOps, false);
                    } else {
                        MGMT_OP_LOGGER.infof("No registration found for address %s", address.toCLIStyleString());
                        processResource(address, map, context, rootRegistration, resourceRegistration, xmlOperations, postExtensionOps, true);
                    }
                } else {
                    MGMT_OP_LOGGER.warnf("We have a value %s for address %s", value, address.toCLIStyleString());
                }
            } else {
                PathAddress address = parentAddress.getParent().append(parentAddress.getLastElement().getKey(), name);
                if (isExistingResource(xmlOperations, address)) {
                    //we will have to check attributes
                    MGMT_OP_LOGGER.infof("Resource for address %s already exists", address.toCLIStyleString());
                    //need to process attributes for updating
                    Object value = yaml.get(name);
                    if (value instanceof Map) {
                        Map<String, Object> map = (Map<String, Object>) value;
                        processResource(address, map, context, rootRegistration, rootRegistration.getSubModel(address), xmlOperations, postExtensionOps, false);
                    } else {
                        MGMT_OP_LOGGER.warnf("We have a value %s for address %s", value, address.toCLIStyleString());
                    }
                } else {
                    if (resourceRegistration.getAttributeNames(PathAddress.EMPTY_ADDRESS).contains(name)) {
                        //we are processing an attribute: that is wrong
                        MGMT_OP_LOGGER.warnf("We processing the attribute %s for address %s", name, address.getParent().toCLIStyleString());
                        processAttribute(parentAddress, rootRegistration, name, yaml.get(name), postExtensionOps);
                    } else {
                        ImmutableManagementResourceRegistration childResourceRegistration = rootRegistration.getSubModel(address);
                        // we need to create a new resource
                        if (childResourceRegistration != null) {
                            OperationEntry operationEntry = rootRegistration.getOperationEntry(address, ADD);
                            if (operationEntry == null) {
                                MGMT_OP_LOGGER.warnf("Resource for address %s is a placeholder for %s so we don't create it", address.toCLIStyleString(), childResourceRegistration.getPathAddress().toCLIStyleString());
                                Object value = yaml.get(name);
                                if (value instanceof Map) {
                                    Map<String, Object> map = (Map<String, Object>) value;
                                    processResource(address, map, context, rootRegistration, childResourceRegistration, xmlOperations, postExtensionOps, false);
                                } else {
                                    MGMT_OP_LOGGER.infof("We have a value %s for address %s", value, address.toCLIStyleString());
                                }
                            } else {
                                MGMT_OP_LOGGER.warnf("Resource for address %s needs to be created with parameters %s", address.toCLIStyleString(), Arrays.stream(operationEntry.getOperationDefinition().getParameters()).map(AttributeDefinition::getName).collect(Collectors.joining()));
                                Object value = yaml.get(name);
                                if (value instanceof Map) {
                                    Map<String, Object> map = (Map<String, Object>) value;
                                    //need to process attributes for adding
                                    processAttributes(address, rootRegistration, operationEntry, map, postExtensionOps);
                                    processResource(address, map, context, rootRegistration, childResourceRegistration, xmlOperations, postExtensionOps, false);
                                } else {
                                    MGMT_OP_LOGGER.infof("We have a value %s for address %s and name %s", value, address.toCLIStyleString(), name);
                                }
                            }
                        } else {
                            MGMT_OP_LOGGER.warnf("Couldn't find a resource registration for address %s with current registration %s !!!!!!!!!!!!!!!", address.toCLIStyleString(), resourceRegistration.getPathAddress().toCLIStyleString());
                        }
                    }
                }
            }
        }
    }

    private boolean isExistingResource(Map<PathAddress, ParsedBootOp> xmlOperations, PathAddress address) {
        return xmlOperations.containsKey(address);
    }

    private void processAttribute(PathAddress address, ImmutableManagementResourceRegistration rootRegistration, String attributeName, Object value, List<ParsedBootOp> postExtensionOps) {
        AttributeAccess attributeAccess = rootRegistration.getAttributeAccess(address, attributeName);
        OperationEntry operationEntry = rootRegistration.getOperationEntry(address, WRITE_ATTRIBUTE_OPERATION);
        if (attributeAccess.getStorageType() == AttributeAccess.Storage.CONFIGURATION) {
            ModelNode op = new ModelNode();
            op.get(OP).set(operationEntry.getOperationDefinition().getName());
            op.get(OP_ADDR).set(address.toModelNode());
            op.get(NAME).set(attributeName);
            AttributeDefinition att = attributeAccess.getAttributeDefinition();
            if (att != null) {
                if (!att.isResourceOnly()) {
                    switch (att.getType()) {
                        case OBJECT:
                            //ObjectTypeAttributeDefinition
                            op.get(VALUE).set(processObjectAttribute((ObjectTypeAttributeDefinition) att, (Map<String, Object>) value));
                            break;
                        case LIST:
                            op.get(VALUE).set(processListAttribute((ListAttributeDefinition) att, value));
                            break;
                        default:
                            op.get(VALUE).set(value.toString());
                            break;
                    }
                }
            }
            ParsedBootOp operation = new ParsedBootOp(op, operationEntry.getOperationHandler());
            MGMT_OP_LOGGER.warnf("Updating attribute %s for resource %s with operation %s", attributeName, address, op);
            postExtensionOps.add(operation);
        }
    }

    private void processAttributes(PathAddress address, ImmutableManagementResourceRegistration rootRegistration, OperationEntry operationEntry, Map<String, Object> map, List<ParsedBootOp> postExtensionOps) {
        ModelNode op = new ModelNode();
        Set<AttributeDefinition> attributes = new HashSet<>();
        for (AttributeAccess attributeAccess : rootRegistration.getAttributes(address).values()) {
            if (attributeAccess.getStorageType() == AttributeAccess.Storage.CONFIGURATION) {
                AttributeDefinition def = attributeAccess.getAttributeDefinition();
                if (def != null) {
                    if (!def.isResourceOnly()) {
                        attributes.add(def);
                    }
                }
            }
        }
        attributes.addAll(Arrays.asList(operationEntry.getOperationDefinition().getParameters()));
        op.get(OP).set(operationEntry.getOperationDefinition().getName());
        op.get(OP_ADDR).set(address.toModelNode());
        for (AttributeDefinition att : attributes) {
            if (map.containsKey(att.getName())) {
                Object value = map.get(att.getName());
                map.remove(att.getName());
                switch (att.getType()) {
                    case OBJECT:
                        //ObjectTypeAttributeDefinition
                        op.get(att.getName()).set(processObjectAttribute((ObjectTypeAttributeDefinition) att, (Map<String, Object>) value));
                        break;
                    case LIST:
                        op.get(att.getName()).set(processListAttribute((ListAttributeDefinition) att, value));
                        break;
                    default:
                        op.get(att.getName()).set(value.toString());
                        break;
                }
            }
        }
        ParsedBootOp operation = new ParsedBootOp(op, operationEntry.getOperationHandler());
        MGMT_OP_LOGGER.warnf("Adding resource with operation %s", op);
        postExtensionOps.add(operation);
    }

    @SuppressWarnings("unchecked")
    private ModelNode processObjectAttribute(ObjectTypeAttributeDefinition att, Map<String, Object> map) {
        ModelNode objectNode = new ModelNode();
        for (AttributeDefinition child : att.getValueTypes()) {
            if (map.containsKey(child.getName())) {
                Object value = map.get(child.getName());
                switch (child.getType()) {
                    case OBJECT:
                        //ObjectTypeAttributeDefinition
                        objectNode.get(child.getName()).set(processObjectAttribute((ObjectTypeAttributeDefinition) child, (Map<String, Object>) value));
                        break;
                    case LIST:
                        objectNode.get(child.getName()).set(processListAttribute((ListAttributeDefinition) child, value));
                        break;
                    default:
                        objectNode.get(child.getName()).set(value.toString());
                        break;
                }
            }
        }
        return objectNode;
    }

    @SuppressWarnings("unchecked")
    private ModelNode processListAttribute(ListAttributeDefinition att, Object value) {
        AttributeDefinition type = att.getValueType();
        ModelNode listNode = new ModelNode();
        for (Object entry : ((Iterable<? extends Object>) value)) {
            switch (type.getType()) {
                case OBJECT:
                    listNode.add(processObjectAttribute((ObjectTypeAttributeDefinition) type, ((Map<String, Object>) entry)));
                    break;
                case LIST:
                default:
                    listNode.add(entry.toString());
                    break;
            }
        }
        return listNode;
    }
}
