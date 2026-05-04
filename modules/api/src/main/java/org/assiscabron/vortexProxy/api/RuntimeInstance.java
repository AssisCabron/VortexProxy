package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.InstanceId;
import org.assiscabron.vortexProxy.api.RuntimeInstance;


import java.time.Instant;
import java.util.Objects;

public record RuntimeInstance(
        InstanceId id,
        ExperienceId experienceId,
        String serverName,
        int currentPlayers,
        int maxPlayers,
        boolean healthy,
        Instant lastHeartbeat
) {
    public RuntimeInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(experienceId, "experienceId");
        Objects.requireNonNull(serverName, "serverName");
        if (serverName.isBlank()) {
            throw new IllegalArgumentException("serverName cannot be blank");
        }
        Objects.requireNonNull(lastHeartbeat, "lastHeartbeat");
        if (currentPlayers < 0) {
            throw new IllegalArgumentException("currentPlayers cannot be negative");
        }
        if (maxPlayers < 1) {
            throw new IllegalArgumentException("maxPlayers must be positive");
        }
        if (currentPlayers > maxPlayers) {
            throw new IllegalArgumentException("currentPlayers cannot exceed maxPlayers");
        }
    }

    public boolean hasCapacity() {
        return healthy && currentPlayers < maxPlayers;
    }
}
