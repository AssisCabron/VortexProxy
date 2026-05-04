package org.assiscabron.vortexProxy.platform.proxy.protocol;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

public final class PacketByteWriter {
    private static final int MAX_STRING_BYTES = 32767;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    public PacketByteWriter writeBoolean(boolean value) {
        output.write(value ? 1 : 0);
        return this;
    }

    public PacketByteWriter writeByte(int value) {
        output.write(value & 0xff);
        return this;
    }

    public PacketByteWriter writeVarInt(int value) {
        int remaining = value;
        do {
            int temp = remaining & 0b01111111;
            remaining >>>= 7;
            if (remaining != 0) {
                temp |= 0b10000000;
            }
            writeByte(temp);
        } while (remaining != 0);
        return this;
    }

    public PacketByteWriter writeString(String value) {
        Objects.requireNonNull(value, "value");
        var bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            throw new IllegalArgumentException("String exceeds max protocol length");
        }
        writeVarInt(bytes.length);
        output.writeBytes(bytes);
        return this;
    }

    public PacketByteWriter writeUuid(UUID value) {
        Objects.requireNonNull(value, "value");
        writeLong(value.getMostSignificantBits());
        writeLong(value.getLeastSignificantBits());
        return this;
    }

    public PacketByteWriter writeLong(long value) {
        for (int shift = 56; shift >= 0; shift -= 8) {
            writeByte((int) (value >>> shift));
        }
        return this;
    }

    public byte[] toByteArray() {
        return output.toByteArray();
    }
}
