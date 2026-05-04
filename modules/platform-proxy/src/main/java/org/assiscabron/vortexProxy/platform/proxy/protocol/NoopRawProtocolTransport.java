package org.assiscabron.vortexProxy.platform.proxy.protocol;

import com.velocitypowered.api.proxy.Player;

import java.util.Set;

public final class NoopRawProtocolTransport implements RawProtocolTransport {
    @Override
    public boolean isAvailable(Player player) {
        return false;
    }

    @Override
    public Set<ProtocolCapability> capabilities(Player player) {
        return Set.of();
    }

    @Override
    public ProtocolWriteResult write(Player player, RawProtocolPacket packet) {
        return ProtocolWriteResult.skipped("Raw protocol transport is unavailable.");
    }
}
