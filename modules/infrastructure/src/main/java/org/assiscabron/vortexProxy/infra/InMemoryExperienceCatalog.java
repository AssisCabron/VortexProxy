package org.assiscabron.vortexProxy.infra;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceManifest;
import org.assiscabron.vortexProxy.api.ExperienceCatalog;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceManifest;
import org.assiscabron.vortexProxy.api.ExperienceCatalog;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class InMemoryExperienceCatalog implements ExperienceCatalog {
    private final ConcurrentMap<ExperienceId, ExperienceManifest> manifests = new ConcurrentHashMap<>();

    @Override
    public void register(ExperienceManifest manifest) {
        manifests.put(manifest.id(), manifest);
    }

    @Override
    public Optional<ExperienceManifest> find(ExperienceId id) {
        return Optional.ofNullable(manifests.get(id));
    }

    @Override
    public Collection<ExperienceManifest> list() {
        return List.copyOf(manifests.values());
    }

    @Override
    public void clear() {
        manifests.clear();
    }
}
