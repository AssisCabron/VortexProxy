package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceManifest;
import org.assiscabron.vortexProxy.api.ExperienceCatalog;

import java.util.Collection;
import java.util.Optional;

public interface ExperienceCatalog {
    void register(ExperienceManifest manifest);

    Optional<ExperienceManifest> find(ExperienceId id);

    Collection<ExperienceManifest> list();

    void clear();
}
