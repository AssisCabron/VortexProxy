package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.InstanceId;
import org.assiscabron.vortexProxy.api.VirtualInstanceState;
import org.assiscabron.vortexProxy.api.VirtualClientInstance;

import org.assiscabron.vortexProxy.api.VirtualClientInstance;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.InstanceId;
import org.assiscabron.vortexProxy.api.VirtualInstanceState;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record VirtualClientInstance(
        InstanceId id,
        UUID playerId,
        VirtualInstanceState state,
        Optional<ExperienceId> experienceId,
        Instant createdAt,
        Instant updatedAt
) {
    public VirtualClientInstance {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(experienceId, "experienceId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt cannot be before createdAt");
        }
    }

    public static VirtualClientInstance create(UUID playerId) {
        var now = Instant.now();
        return new VirtualClientInstance(
                InstanceId.random(),
                playerId,
                VirtualInstanceState.CREATED,
                Optional.empty(),
                now,
                now
        );
    }

    public VirtualClientInstance transitionTo(VirtualInstanceState nextState) {
        return new VirtualClientInstance(id, playerId, nextState, experienceId, createdAt, Instant.now());
    }

    public VirtualClientInstance launch(ExperienceId experience) {
        return new VirtualClientInstance(id, playerId, VirtualInstanceState.RENDERING_EXPERIENCE, Optional.of(experience), createdAt, Instant.now());
    }
}
