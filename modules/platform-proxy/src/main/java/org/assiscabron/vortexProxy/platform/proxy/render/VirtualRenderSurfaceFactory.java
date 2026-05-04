package org.assiscabron.vortexProxy.platform.proxy.render;

import com.velocitypowered.api.proxy.Player;
import org.assiscabron.vortexProxy.platform.proxy.protocol.RawProtocolTransport;
import org.slf4j.Logger;

import java.util.List;
import java.util.Objects;

public final class VirtualRenderSurfaceFactory {
    private final RawProtocolTransport rawProtocolTransport;
    private final Logger logger;

    public VirtualRenderSurfaceFactory(RawProtocolTransport rawProtocolTransport, Logger logger) {
        this.rawProtocolTransport = Objects.requireNonNull(rawProtocolTransport, "rawProtocolTransport");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public VirtualRenderSurface create(Player player) {
        return new CompositeRenderSurface(List.of(
                new RawProtocolRenderSurface(player, rawProtocolTransport, logger),
                new ProtocolRenderSurface(player, logger)
        ), logger);
    }
}
