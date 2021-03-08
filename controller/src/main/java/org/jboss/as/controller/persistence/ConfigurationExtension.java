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

import java.util.List;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.ParsedBootOp;
import org.jboss.as.controller.registry.ImmutableManagementResourceRegistration;
import org.jboss.as.controller.registry.ManagementResourceRegistration;

/**
 *
 * @author Emmanuel Hugonnet (c) 2021 Red Hat, Inc.
 */
public interface ConfigurationExtension {

    void processExtensions(ManagementResourceRegistration rootRegistration, List<ParsedBootOp> initialOps);

    void processOperations(OperationContext context, ImmutableManagementResourceRegistration rootRegistration, List<ParsedBootOp> postExtensionOps);
}
