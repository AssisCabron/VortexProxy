package org.assiscabron.vortexProxy.platform.proxy.render;

import org.slf4j.Logger;

public final class LoggingRenderSurface implements VirtualRenderSurface {
    private final Logger logger;
    private final String playerName;

    public LoggingRenderSurface(Logger logger, String playerName) {
        this.logger = logger;
        this.playerName = playerName;
    }

    @Override
    public void send(RenderCommand command) {
        logger.info("Virtual render for {}: {}", playerName, command);
    }

    @Override
    public void flush() {
        logger.debug("Virtual render flush for {}", playerName);
    }
}
