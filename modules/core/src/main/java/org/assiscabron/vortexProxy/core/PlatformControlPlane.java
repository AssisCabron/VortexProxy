package org.assiscabron.vortexProxy.core;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceCatalog;
import org.assiscabron.vortexProxy.api.InstanceRegistry;
import org.assiscabron.vortexProxy.api.VirtualInstanceService;
import org.assiscabron.vortexProxy.api.RuntimeInstance;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceCatalog;
import org.assiscabron.vortexProxy.api.InstanceRegistry;
import org.assiscabron.vortexProxy.api.VirtualInstanceService;
import org.assiscabron.vortexProxy.api.RuntimeInstance;

import java.util.Optional;

public final class PlatformControlPlane {
    private final ExperienceCatalog experiences;
    private final InstanceRegistry instances;
    private final VirtualInstanceService virtualInstances;

    public PlatformControlPlane(
            ExperienceCatalog experiences,
            InstanceRegistry instances,
            VirtualInstanceService virtualInstances
    ) {
        this.experiences = experiences;
        this.instances = instances;
        this.virtualInstances = virtualInstances;
    }

    public ExperienceCatalog experiences() {
        return experiences;
    }

    public InstanceRegistry instances() {
        return instances;
    }

    public VirtualInstanceService virtualInstances() {
        return virtualInstances;
    }

    public Optional<RuntimeInstance> placePlayer(ExperienceId experienceId) {
        if (experiences.find(experienceId).isEmpty()) {
            return Optional.empty();
        }
        return instances.findPlacement(experienceId);
    }
}
