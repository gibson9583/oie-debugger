package org.openintegrationengine.debugger.engine;

import java.util.List;
import java.util.Set;

import org.openintegrationengine.debugger.runtime.DebugRuntime;

import com.mirth.connect.model.LibraryProperties;
import com.mirth.connect.server.controllers.DefaultContextFactoryController;
import com.mirth.connect.server.controllers.ExtensionController;
import com.mirth.connect.server.util.javascript.MirthContextFactory;

/** Preserves the engine's resource handling and observes every returned normal factory. */
public final class DebugContextFactoryController extends DefaultContextFactoryController {
    public DebugContextFactoryController() { super(); }

    DebugContextFactoryController(ExtensionController extensions) { super(extensions); }

    @Override
    public synchronized void initGlobalContextFactory() {
        super.initGlobalContextFactory();
        observe(super.getGlobalContextFactory());
    }

    @Override
    public MirthContextFactory getGlobalContextFactory() {
        return observe(super.getGlobalContextFactory());
    }

    @Override
    public MirthContextFactory getGlobalScriptContextFactory() throws Exception {
        return observe(super.getGlobalScriptContextFactory());
    }

    @Override
    public MirthContextFactory getContextFactory(Set<String> resourceIds) throws Exception {
        return observe(super.getContextFactory(resourceIds));
    }

    @Override
    public MirthContextFactory getDebugContextFactory(Set<String> resourceIds, String channelId,
            String scriptId) throws Exception {
        // Native lifecycle factories can have isDebug=false before their first compilation.
        // Keep this entire engine-owned path free of our listeners regardless of that flag.
        return super.getDebugContextFactory(resourceIds, channelId, scriptId);
    }

    @Override
    public void reloadResource(String resourceId) throws Exception {
        DebugRuntime.getInstance().closeAll("Library resources are reloading; arm a new session.");
        super.reloadResource(resourceId);
    }

    @Override
    public synchronized void updateResources(List<LibraryProperties> resources, boolean startup) throws Exception {
        if (!startup) DebugRuntime.getInstance().closeAll("Library resources changed; arm a new session.");
        super.updateResources(resources, startup);
    }

    private MirthContextFactory observe(MirthContextFactory factory) {
        DebugRuntime runtime = DebugRuntime.getInstance();
        runtime.setAttachmentGuard(EngineCompatibility::canAttach);
        if (EngineCompatibility.canAttach(factory)) runtime.attach(factory);
        return factory;
    }
}
