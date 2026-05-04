package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.InstanceId;

import java.util.Objects;
import java.util.UUID;

public record InstanceId(String value) {
    public InstanceId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Instance id cannot be blank");
        }
    }

    public static InstanceId random() {
        return new InstanceId(UUID.randomUUID().toString());
    }
}
