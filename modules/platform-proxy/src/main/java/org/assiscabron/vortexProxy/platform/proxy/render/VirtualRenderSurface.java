package org.assiscabron.vortexProxy.platform.proxy.render;

public interface VirtualRenderSurface {
    void send(RenderCommand command);

    void flush();
}
