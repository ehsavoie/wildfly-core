package org.jboss.as.domain.controller.operations.sync;

import java.util.Map;
import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.ModelController;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;

import org.jboss.as.controller.ProxyController;
import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.controller.operations.sync.ReadOperationsHandler;
import org.jboss.as.controller.operations.sync.SyncModelParameters;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.host.controller.HostControllerEnvironment;
import org.jboss.as.host.controller.ignored.IgnoredDomainResourceRegistry;
import org.jboss.as.host.controller.mgmt.HostControllerRegistrationHandler;
import org.jboss.as.repository.ContentReference;
import org.jboss.as.repository.ContentRepository;
import org.jboss.as.repository.HostFileRepository;
import org.jboss.dmr.ModelNode;

/**
 * @author <a href="mailto:kabir.khan@jboss.com">Kabir Khan</a>
 */
public class DomainSyncModelParameters extends SyncModelParameters {
    private static final ModelNode REMOTE_READ = Util.createEmptyOperation(ReadMasterDomainOperationsHandler.OPERATION_NAME, PathAddress.EMPTY_ADDRESS);

    private final IgnoredDomainResourceRegistry ignoredResourceRegistry;
    private final HostControllerRegistrationHandler.OperationExecutor operationExecutor;
    private final Map<String, ProxyController> serverProxies;
    private final HostFileRepository fileRepository;
    private final ContentRepository contentRepository;

    public DomainSyncModelParameters(ExpressionResolver expressionResolver,
                               IgnoredDomainResourceRegistry ignoredResourceRegistry,
                               HostControllerEnvironment hostControllerEnvironment,
                               ExtensionRegistry extensionRegistry,
                               HostControllerRegistrationHandler.OperationExecutor operationExecutor,
                               boolean fullModelTransfer,
                               Map<String, ProxyController> serverProxies, HostFileRepository fileRepository, ContentRepository contentRepository) {
        super(expressionResolver, hostControllerEnvironment, extensionRegistry, fullModelTransfer);
        this.ignoredResourceRegistry = ignoredResourceRegistry;
        this.operationExecutor = operationExecutor;
        this.serverProxies = serverProxies;
        this.fileRepository = fileRepository;
        this.contentRepository = contentRepository;
    }

    public Map<String, ProxyController> getServerProxies() {
        return serverProxies;
    }

    public void pullFile(ContentReference reference) {
        fileRepository.getDeploymentFiles(reference);
        contentRepository.addContentReference(reference);
    }

    public ContentRepository getContentRepository() {
        return contentRepository;
    }

    @Override
    public boolean isResourceExcluded(PathAddress address) {
        return this.ignoredResourceRegistry.isResourceExcluded(address);
    }

    @Override
    public void initializeModelSync() {
        ignoredResourceRegistry.getIgnoredClonedProfileRegistry().initializeModelSync();
    }

    @Override
    public void complete(boolean rollback) {
        ignoredResourceRegistry.getIgnoredClonedProfileRegistry().complete(rollback);
    }

    public ModelNode readLocalModel(OperationStepHandler handler) {
        return operationExecutor.executeReadOnly(LOCAL_READ, handler, ModelController.OperationTransactionControl.COMMIT);
    }

    public ModelNode readRemoteOperations(Resource resource, ReadOperationsHandler handler) {
        return operationExecutor.executeReadOnly(REMOTE_READ, resource, handler, ModelController.OperationTransactionControl.COMMIT);
    }

    public ModelNode readLocalOperations(Resource resource, ReadOperationsHandler handler) {
        return operationExecutor.executeReadOnly(LOCAL_READ, resource, handler, ModelController.OperationTransactionControl.COMMIT);
    }
}
