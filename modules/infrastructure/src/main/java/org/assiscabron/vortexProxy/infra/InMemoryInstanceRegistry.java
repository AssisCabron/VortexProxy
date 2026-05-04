package org.assiscabron.vortexProxy.infra;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.InstanceId;
import org.assiscabron.vortexProxy.api.InstanceRegistry;
import org.assiscabron.vortexProxy.api.RuntimeInstance;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.InstanceId;
import org.assiscabron.vortexProxy.api.InstanceRegistry;
import org.assiscabron.vortexProxy.api.RuntimeInstance;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryInstanceRegistry implements InstanceRegistry {
    private final ConcurrentMap<InstanceId, RuntimeInstance> instances = new ConcurrentHashMap<>();

    @Override
    public void upsert(RuntimeInstance instance) {
        instances.put(instance.id(), instance);
    }

    @Override
    public void remove(InstanceId instanceId) {
        instances.remove(instanceId);
    }

    @Override
    public Collection<RuntimeInstance> list() {
        return List.copyOf(instances.values());
    }

    @Override
    public Optional<RuntimeInstance> findPlacement(ExperienceId experienceId) {
        return instances.values().stream()
                .filter(instance -> instance.experienceId().equals(experienceId))
                .filter(RuntimeInstance::hasCapacity)
                .min(Comparator.comparingInt(RuntimeInstance::currentPlayers));
    }
}
