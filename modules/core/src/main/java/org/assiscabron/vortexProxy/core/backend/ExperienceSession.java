package org.assiscabron.vortexProxy.core.backend;

import org.assiscabron.vortexProxy.api.ExperienceId;

import org.assiscabron.vortexProxy.api.ExperienceId;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import org.assiscabron.vortexProxy.api.ExperienceId;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

record ExperienceSession(
        String id,
        ExperienceId experienceId,
        ExperienceSessionMode mode,
        InstanceContainer instance,
        Optional<UUID> owner,
        int maxPlayers
) {
    boolean hasCapacity() {
        return players().size() < maxPlayers;
    }

    List<Player> players() {
        return List.copyOf(instance.getPlayers());
    }

    boolean isStudioOwnedBy(UUID playerId) {
        return mode == ExperienceSessionMode.STUDIO && owner.isPresent() && owner.orElseThrow().equals(playerId);
    }
}
