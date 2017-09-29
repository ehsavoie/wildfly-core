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
package org.jboss.as.server.controller.resources;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SERVER;
import static org.jboss.dmr.ModelType.STRING;

import java.util.EnumSet;
import javax.security.auth.callback.Callback;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;
import javax.security.sasl.RealmCallback;
import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.ObjectTypeAttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ReloadRequiredWriteAttributeHandler;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.client.ModelControllerClientConfiguration;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.operations.validation.EnumValidator;
import org.jboss.as.controller.operations.validation.IntRangeValidator;
import org.jboss.as.controller.operations.validation.StringLengthValidator;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.security.CredentialReference;
import org.jboss.as.remoting.Protocol;
import org.jboss.as.server.controller.descriptions.ServerDescriptions;
import org.jboss.as.server.operations.sync.ScriptModelDiffWrapplerHandler;
import org.jboss.as.server.operations.sync.SynchronizationService;
import org.jboss.as.server.operations.sync.SynchronizationWrapperHandler;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;

/**
 *
 * @author Emmanuel Hugonnet (c) 2016 Red Hat, inc.
 */
public class SynchronizationResourceDefinition extends SimpleResourceDefinition {

    public static final RuntimeCapability<Void> SYNCHRONIZATION_CAPABILITY = RuntimeCapability.Builder.of("org.wildfly.management.synchronization", true, Void.class).build();

    public static final SimpleAttributeDefinition PORT
            = new SimpleAttributeDefinitionBuilder(ModelDescriptionConstants.PORT, ModelType.INT, false)
                    .setAllowExpression(true)
                    .setValidator(new IntRangeValidator(1, 65535, true, true))
                    .setDefaultValue(new ModelNode(9990))
                    .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
                    .setRequires(ModelDescriptionConstants.HOST)
                    .build();
    public static final SimpleAttributeDefinition HOST
            = new SimpleAttributeDefinitionBuilder(ModelDescriptionConstants.HOST, ModelType.STRING, false)
                    .setAllowExpression(true)
                    .setValidator(new StringLengthValidator(1, Integer.MAX_VALUE, true, true))
                    .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
                    .setRequires(ModelDescriptionConstants.PORT)
                    .build();
    public static final SimpleAttributeDefinition PROTOCOL
            = new SimpleAttributeDefinitionBuilder(ModelDescriptionConstants.PROTOCOL, ModelType.STRING, true)
                    .setAllowExpression(true)
                    .setValidator(new EnumValidator<Protocol>(Protocol.class, EnumSet.allOf(Protocol.class)))
                    .setDefaultValue(Protocol.HTTP_REMOTING.toModelNode())
                    .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
                    .setRequires(ModelDescriptionConstants.HOST, ModelDescriptionConstants.PORT)
                    .build();

    public static final SimpleAttributeDefinition USERNAME = new SimpleAttributeDefinitionBuilder(ModelDescriptionConstants.USERNAME, STRING, true)
            .setAllowExpression(true)
            .setValidator(new StringLengthValidator(1, Integer.MAX_VALUE, true, true))
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES).build();

    //Need to be replaced by elytron credentail reference
    public static final SimpleAttributeDefinition PASSWORD = new SimpleAttributeDefinitionBuilder(ModelDescriptionConstants.PASSWORD, STRING, true)
            .setAllowExpression(true)
            .setAlternatives(CredentialReference.CREDENTIAL_REFERENCE)
            .setValidator(new StringLengthValidator(1, Integer.MAX_VALUE, true, true))
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES).build();

    public static final ObjectTypeAttributeDefinition CREDENTIAL_REFERENCE =
            CredentialReference.getAttributeBuilder(CredentialReference.CREDENTIAL_REFERENCE, CredentialReference.CREDENTIAL_REFERENCE, true, true)
                .setAlternatives(ModelDescriptionConstants.PASSWORD)
                .build();

    private final ExtensionRegistry extensionRegistry;
    private final ExpressionResolver resolver;
    private static final AttributeDefinition[] ATTRIBUTES = new AttributeDefinition[] {HOST, PORT, PROTOCOL, USERNAME, PASSWORD, CREDENTIAL_REFERENCE};

    public SynchronizationResourceDefinition(ExtensionRegistry extensionRegistry, ExpressionResolver resolver) {
        super(new Parameters(PathElement.pathElement("synchronization", "simple"), ServerDescriptions.getResourceDescriptionResolver(SERVER, "synchronization"))
        .setCapabilities(SYNCHRONIZATION_CAPABILITY));
        this.extensionRegistry = extensionRegistry;
        this.resolver = resolver;
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        super.registerAttributes(resourceRegistration);
        for (AttributeDefinition attribute : ATTRIBUTES) {
            resourceRegistration.registerReadWriteAttribute(attribute, null, new ReloadRequiredWriteAttributeHandler(attribute));
        }
    }

    @Override
    public void registerOperations(ManagementResourceRegistration resourceRegistration) {
        super.registerOperations(resourceRegistration);
        this.registerAddOperation(resourceRegistration, new SynchronizationResourceAddHandler(ATTRIBUTES));
        resourceRegistration.registerOperationHandler(SynchronizationWrapperHandler.DEFINITION, new SynchronizationWrapperHandler());
        resourceRegistration.registerOperationHandler(ScriptModelDiffWrapplerHandler.DEFINITION, new ScriptModelDiffWrapplerHandler());
    }

    private class SynchronizationResourceAddHandler extends AbstractAddStepHandler {

        public SynchronizationResourceAddHandler(AttributeDefinition... attributes) {
            super(attributes);
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, Resource resource) throws OperationFailedException {
            super.performRuntime(context, operation, resource);
            final ModelControllerClientConfiguration.Builder builder = new ModelControllerClientConfiguration.Builder();
            builder.setHostName(HOST.resolveModelAttribute(context, operation).asString());
            builder.setProtocol(PROTOCOL.resolveModelAttribute(context, operation).asString());
            builder.setPort(PORT.resolveModelAttribute(context, operation).asInt());
            final String userName = USERNAME.resolveModelAttribute(context, operation).asString();
            final String password = PASSWORD.resolveModelAttribute(context, operation).asString();
            builder.setHandler((Callback[] callbacks) -> {
                for (Callback current : callbacks) {
                    if (current instanceof NameCallback) {
                        NameCallback ncb = (NameCallback) current;
                        ncb.setName(userName);
                    } else if (current instanceof PasswordCallback) {
                        PasswordCallback pcb = (PasswordCallback) current;
                        pcb.setPassword(password.toCharArray());
                    } else if (current instanceof RealmCallback) {
                        RealmCallback rcb = (RealmCallback) current;
                        rcb.setText(rcb.getDefaultText());
                    } else {
                        throw new UnsupportedCallbackException(current);
                    }
                }
            });
            SynchronizationService.addService(context, builder.build(), extensionRegistry, resolver);
        }

        @Override
        protected boolean requiresRuntime(OperationContext context) {
            return true;
        }
    }
}
