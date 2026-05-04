package org.assiscabron.vortexProxy.infra;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.VirtualInstanceState;
import org.assiscabron.vortexProxy.api.VirtualInstanceService;
import org.assiscabron.vortexProxy.api.VirtualClientInstance;

import org.assiscabron.vortexProxy.api.VirtualClientInstance;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.VirtualInstanceState;
import org.assiscabron.vortexProxy.api.VirtualInstanceService;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryVirtualInstanceService implements VirtualInstanceService {
    private final ConcurrentMap<UUID, VirtualClientInstance> instancesByPlayer = new ConcurrentHashMap<>();

    @Override
    public VirtualClientInstance createFor(UUID playerId) {
        var instance = VirtualClientInstance.create(playerId);
        instancesByPlayer.put(playerId, instance);
        return instance;
    }

    @Override
    public Optional<VirtualClientInstance> findByPlayer(UUID playerId) {
        return Optional.ofNullable(instancesByPlayer.get(playerId));
    }

    @Override
    public VirtualClientInstance transition(UUID playerId, VirtualInstanceState nextState) {
        return instancesByPlayer.compute(playerId, (ignored, current) -> {
            if (current == null) {
                return VirtualClientInstance.create(playerId).transitionTo(nextState);
            }
            return current.transitionTo(nextState);
        });
    }

    @Override
    public void close(UUID playerId) {
        instancesByPlayer.remove(playerId);
    }

    @Override
    public void launch(UUID playerId, ExperienceId experienceId) {
        instancesByPlayer.computeIfPresent(playerId, (ignored, current) -> current.launch(experienceId));
    }

    @Override
    public Collection<VirtualClientInstance> list() {
        return List.copyOf(instancesByPlayer.values());
    }
}
