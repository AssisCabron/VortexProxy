package org.assiscabron.vortexProxy.platform.proxy.render;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;

public final class CompositeRenderSurface implements VirtualRenderSurface {
    private final List<VirtualRenderSurface> surfaces;
    private final Logger logger;
    private final List<RenderCommand> pendingCommands = new ArrayList<>();

    public CompositeRenderSurface(List<VirtualRenderSurface> surfaces, Logger logger) {
        this.surfaces = List.copyOf(Objects.requireNonNull(surfaces, "surfaces"));
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void send(RenderCommand command) {
        pendingCommands.add(Objects.requireNonNull(command, "command"));
    }

    @Override
    public void flush() {
        for (VirtualRenderSurface surface : surfaces) {
            try {
                for (RenderCommand command : pendingCommands) {
                    surface.send(command);
                }
                surface.flush();
            } catch (RuntimeException exception) {
                logger.warn("Virtual render surface {} failed during flush.",
                        surface.getClass().getSimpleName(), exception);
            }
        }
        pendingCommands.clear();
    }
}
