package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.VirtualInstanceState;
import org.assiscabron.vortexProxy.api.VirtualInstanceService;
import org.assiscabron.vortexProxy.api.VirtualClientInstance;

import org.assiscabron.vortexProxy.api.VirtualClientInstance;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface VirtualInstanceService {
    VirtualClientInstance createFor(UUID playerId);

    Optional<VirtualClientInstance> findByPlayer(UUID playerId);

    VirtualClientInstance transition(UUID playerId, VirtualInstanceState nextState);

    void close(UUID playerId);

    void launch(UUID playerId, ExperienceId experienceId);

    Collection<VirtualClientInstance> list();
}
