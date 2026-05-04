package org.assiscabron.vortexProxy.platform.proxy.protocol;

import com.velocitypowered.api.proxy.Player;

import java.util.Set;

public interface RawProtocolTransport {
    boolean isAvailable(Player player);

    Set<ProtocolCapability> capabilities(Player player);

    ProtocolWriteResult write(Player player, RawProtocolPacket packet);
}
