package org.assiscabron.vortexProxy.platform.proxy.render;

import com.velocitypowered.api.proxy.Player;
import org.assiscabron.vortexProxy.platform.proxy.protocol.RawProtocolTransport;
import org.assiscabron.vortexProxy.platform.proxy.protocol.VirtualProtocolPackets;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class RawProtocolRenderSurface implements VirtualRenderSurface {
    private final Player player;
    private final RawProtocolTransport transport;
    private final Logger logger;
    private final List<RenderCommand> pendingCommands = new ArrayList<>();

    public RawProtocolRenderSurface(Player player, RawProtocolTransport transport, Logger logger) {
        this.player = Objects.requireNonNull(player, "player");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void send(RenderCommand command) {
        pendingCommands.add(Objects.requireNonNull(command, "command"));
    }

    @Override
    public void flush() {
        if (!transport.isAvailable(player)) {
            pendingCommands.clear();
            return;
        }

        for (RenderCommand command : pendingCommands) {
            apply(command);
        }
        pendingCommands.clear();
    }

    private void apply(RenderCommand command) {
        switch (command) {
            case RenderCommand.BootstrapVirtualWorld bootstrap -> writeBootstrap(bootstrap);
            case RenderCommand.Clear ignored -> writeClear();
            case RenderCommand.ShowActionBar ignored -> {
            }
            case RenderCommand.ShowTitle ignored -> {
            }
            case RenderCommand.ShowMenu ignored -> {
            }
            case RenderCommand.PlaySound ignored -> {
            }
        }
    }

    private void writeBootstrap(RenderCommand.BootstrapVirtualWorld command) {
        var result = transport.write(player,
                VirtualProtocolPackets.bootstrapVirtualWorld(player.getUniqueId(), command.worldName()));
        if (!result.success()) {
            logger.debug("Virtual bootstrap packet skipped for {}: {}", player.getUsername(), result.message());
        }
    }

    private void writeClear() {
        var result = transport.write(player, VirtualProtocolPackets.clearVirtualWorld(player.getUniqueId()));
        if (!result.success()) {
            logger.debug("Virtual clear packet skipped for {}: {}", player.getUsername(), result.message());
        }
    }
}
