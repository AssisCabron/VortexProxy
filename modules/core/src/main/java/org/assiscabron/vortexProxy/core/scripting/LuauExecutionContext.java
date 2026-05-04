package org.assiscabron.vortexProxy.core.scripting;

import org.assiscabron.vortexProxy.api.ExperienceId;

import org.assiscabron.vortexProxy.api.ExperienceId;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import org.assiscabron.vortexProxy.api.ExperienceId;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

public record LuauExecutionContext(
        ExperienceId experienceId,
        Path scriptsRoot,
        Path script,
        String source,
        InstanceContainer instance,
        Collection<Player> contextPlayers
) {
    public LuauExecutionContext {
        Objects.requireNonNull(experienceId, "experienceId");
        Objects.requireNonNull(scriptsRoot, "scriptsRoot");
        Objects.requireNonNull(script, "script");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(instance, "instance");
        contextPlayers = List.copyOf(Objects.requireNonNull(contextPlayers, "contextPlayers"));
    }

    public String scriptName() {
        return scriptsRoot.relativize(script).toString().replace('\\', '/');
    }
}
