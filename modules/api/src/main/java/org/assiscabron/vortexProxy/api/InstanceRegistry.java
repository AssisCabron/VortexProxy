package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.InstanceId;
import org.assiscabron.vortexProxy.api.InstanceRegistry;
import org.assiscabron.vortexProxy.api.RuntimeInstance;

import java.util.Collection;
import java.util.Optional;

public interface InstanceRegistry {
    void upsert(RuntimeInstance instance);

    void remove(InstanceId instanceId);

    Collection<RuntimeInstance> list();

    Optional<RuntimeInstance> findPlacement(ExperienceId experienceId);
}
