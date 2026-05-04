package org.assiscabron.vortexProxy.platform.proxy.protocol;

import java.util.UUID;

public final class VirtualProtocolPackets {
    private VirtualProtocolPackets() {
    }

    public static RawProtocolPacket bootstrapVirtualWorld(UUID playerId, String worldName) {
        var payload = new PacketByteWriter()
                .writeUuid(playerId)
                .writeString(worldName)
                .writeBoolean(true)
                .toByteArray();
        return new RawProtocolPacket(ProtocolPhase.PLAY, "vortex:bootstrap_virtual_world", payload);
    }

    public static RawProtocolPacket clearVirtualWorld(UUID playerId) {
        var payload = new PacketByteWriter()
                .writeUuid(playerId)
                .toByteArray();
        return new RawProtocolPacket(ProtocolPhase.PLAY, "vortex:clear_virtual_world", payload);
    }
}
