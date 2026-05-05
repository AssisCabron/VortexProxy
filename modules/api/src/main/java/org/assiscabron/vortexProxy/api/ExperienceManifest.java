package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceManifest;
import org.assiscabron.vortexProxy.api.ExperienceOwner;
import org.assiscabron.vortexProxy.api.ExperiencePresentation;
import org.assiscabron.vortexProxy.api.ResourceLimits;

import org.assiscabron.vortexProxy.api.WorldType;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ExperienceManifest(
        ExperienceId id,
        String name,
        String version,
        Map<String, String> entrypoints,
        List<String> permissions,
        ResourceLimits resources,
        ExperiencePresentation presentation,
        Optional<ExperienceOwner> owner,
        WorldType worldType
) {
    public ExperienceManifest {
        Objects.requireNonNull(id, "id");
        name = requireText(name, "name");
        version = requireText(version, "version");
        entrypoints = Map.copyOf(Objects.requireNonNull(entrypoints, "entrypoints"));
        if (entrypoints.isEmpty()) {
            throw new IllegalArgumentException("entrypoints cannot be empty");
        }
        permissions = List.copyOf(Objects.requireNonNull(permissions, "permissions"));
        resources = Objects.requireNonNull(resources, "resources");
        presentation = Objects.requireNonNull(presentation, "presentation");
        owner = Objects.requireNonNull(owner, "owner");
        worldType = Objects.requireNonNull(worldType, "worldType");
    }

    public ExperienceManifest(
            ExperienceId id,
            String name,
            String version,
            Map<String, String> entrypoints,
            List<String> permissions,
            ResourceLimits resources,
            ExperiencePresentation presentation
    ) {
        this(id, name, version, entrypoints, permissions, resources, presentation, ExperienceOwner.none(), WorldType.NATURAL);
    }

    public ExperienceManifest(
            ExperienceId id,
            String name,
            String version,
            Map<String, String> entrypoints,
            List<String> permissions,
            ResourceLimits resources
    ) {
        this(id, name, version, entrypoints, permissions, resources, ExperiencePresentation.DEFAULT);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
