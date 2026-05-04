package org.assiscabron.vortexProxy.core.scripting;

import org.assiscabron.vortexProxy.api.ExperienceId;

import org.assiscabron.vortexProxy.api.ExperienceId;

import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import org.assiscabron.vortexProxy.api.ExperienceId;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class ExperienceScriptEngine {
    private final Logger logger;
    private final LuauRuntime runtime;

    public ExperienceScriptEngine(Logger logger) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.runtime = new LuaJLuauRuntime(logger);
    }

    public void runServerScripts(ExperienceId experienceId, Path experienceRoot, InstanceContainer instance) {
        runServerScripts(experienceId, experienceRoot, instance, List.of());
    }

    public void runServerScripts(
            ExperienceId experienceId,
            Path experienceRoot,
            InstanceContainer instance,
            Collection<Player> contextPlayers
    ) {
        var scriptsRoot = experienceRoot.resolve("scripts/server").normalize();
        if (!Files.isDirectory(scriptsRoot)) {
            return;
        }

        for (var script : serverScripts(scriptsRoot)) {
            runScript(experienceId, scriptsRoot, script, instance, contextPlayers);
        }
    }

    private List<Path> serverScripts(Path scriptsRoot) {
        try (var stream = Files.walk(scriptsRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        var name = path.getFileName().toString();
                        return name.endsWith(".lua") || name.endsWith(".luau");
                    })
                    .sorted(Comparator.comparing(path -> scriptsRoot.relativize(path).toString()))
                    .toList();
        } catch (IOException exception) {
            logger.warn("Could not list Luau scripts in {}.", scriptsRoot, exception);
            return List.of();
        }
    }

    private void runScript(
            ExperienceId experienceId,
            Path scriptsRoot,
            Path script,
            InstanceContainer instance,
            Collection<Player> contextPlayers
    ) {
        try {
            var source = Files.readString(script, StandardCharsets.UTF_8);
            var context = new LuauExecutionContext(
                    experienceId,
                    scriptsRoot,
                    script,
                    source,
                    instance,
                    contextPlayers
            );
            runtime.execute(context);
            logger.info("Ran Luau script {} for experience {}.", scriptsRoot.relativize(script), experienceId.value());
        } catch (IOException | LuauRuntimeException exception) {
            logger.warn("Luau script {} failed in experience {}.", script, experienceId.value(), exception);
        }
    }
}
