package org.assiscabron.vortexProxy.platform.proxy.protocol;

import java.util.Arrays;
import java.util.Objects;

public record RawProtocolPacket(
        ProtocolPhase phase,
        String name,
        byte[] payload
) {
    public RawProtocolPacket {
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name cannot be blank");
        }
        payload = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
