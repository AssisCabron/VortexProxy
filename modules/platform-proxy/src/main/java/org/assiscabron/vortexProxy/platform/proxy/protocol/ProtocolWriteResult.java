package org.assiscabron.vortexProxy.platform.proxy.protocol;

import java.util.Objects;

public record ProtocolWriteResult(
        boolean success,
        String message
) {
    public ProtocolWriteResult {
        message = Objects.requireNonNullElse(message, "");
    }

    public static ProtocolWriteResult success(String message) {
        return new ProtocolWriteResult(true, message);
    }

    public static ProtocolWriteResult skipped(String message) {
        return new ProtocolWriteResult(false, message);
    }
}
