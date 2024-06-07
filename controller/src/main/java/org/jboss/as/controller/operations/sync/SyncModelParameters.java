package org.jboss.as.controller.operations.sync;

import org.jboss.as.controller.ExpressionResolver;
import org.jboss.as.controller.PathAddress;

import org.jboss.as.controller.extension.ExtensionRegistry;
import org.jboss.as.controller.operations.common.ProcessEnvironment;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.dmr.ModelNode;

/**
 * @author <a href="mailto:kabir.khan@jboss.com">Kabir Khan</a>
 */
public abstract class SyncModelParameters {
    public static final ModelNode LOCAL_READ = Util.createEmptyOperation("sync", PathAddress.EMPTY_ADDRESS);

    private final ExpressionResolver expressionResolver;
    private final ProcessEnvironment environment;
    private final ExtensionRegistry extensionRegistry;
    private final boolean fullModelTransfer;

    public SyncModelParameters(ExpressionResolver expressionResolver,
                               ProcessEnvironment environment,
                               ExtensionRegistry extensionRegistry,
                               boolean fullModelTransfer) {
        this.expressionResolver = expressionResolver;
        this.environment = environment;
        this.extensionRegistry = extensionRegistry;
        this.fullModelTransfer = fullModelTransfer;
    }

    public ProcessEnvironment getEnvironment() {
        return environment;
    }

    public ExpressionResolver getExpressionResolver() {
        return this.expressionResolver;
    }

    public ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
    }

    public boolean isFullModelTransfer() {
        return fullModelTransfer;
    }

    public abstract boolean isResourceExcluded(PathAddress address) ;

    public abstract void initializeModelSync();

    public abstract void complete(boolean rollback);
}
