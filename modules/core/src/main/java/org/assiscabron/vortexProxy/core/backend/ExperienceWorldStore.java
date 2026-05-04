package org.assiscabron.vortexProxy.core.backend;

import org.assiscabron.vortexProxy.api.ExperienceId;

import org.assiscabron.vortexProxy.api.ExperienceId;

import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.assiscabron.vortexProxy.api.ExperienceId;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

final class ExperienceWorldStore {
    private static final String WORLD_FILE = "world/blocks.vortex";

    private final Path experiencesRoot;
    private final Logger logger;
    private final Map<ExperienceId, Map<BlockPosition, String>> editedBlocks = new ConcurrentHashMap<>();

    ExperienceWorldStore(Path experiencesRoot, Logger logger) {
        this.experiencesRoot = Objects.requireNonNull(experiencesRoot, "experiencesRoot");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    void loadInto(ExperienceId experienceId, InstanceContainer instance) {
        var blocks = editedBlocks.computeIfAbsent(experienceId, this::readBlocks);
        for (var entry : blocks.entrySet()) {
            var position = entry.getKey();
            var block = Block.fromNamespaceId(entry.getValue());
            if (block == null) {
                logger.warn("Ignoring unknown saved block {} at {} in {}.", entry.getValue(), position, experienceId.value());
                continue;
            }
            instance.setBlock(position.x(), position.y(), position.z(), block);
        }
    }

    void record(ExperienceId experienceId, int x, int y, int z, Block block) {
        var namespace = block.namespace().asString();
        editedBlocks.computeIfAbsent(experienceId, this::readBlocks)
                .put(new BlockPosition(x, y, z), namespace);
        save(experienceId);
    }

    private Map<BlockPosition, String> readBlocks(ExperienceId experienceId) {
        var blocks = new ConcurrentHashMap<BlockPosition, String>();
        var file = worldFile(experienceId);
        if (!Files.isRegularFile(file)) {
            return blocks;
        }

        try {
            for (var line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                var trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                var parts = trimmed.split("\\s+", 4);
                if (parts.length != 4) {
                    logger.warn("Ignoring malformed world line in {}: {}", file, line);
                    continue;
                }
                blocks.put(
                        new BlockPosition(
                                Integer.parseInt(parts[0]),
                                Integer.parseInt(parts[1]),
                                Integer.parseInt(parts[2])
                        ),
                        parts[3]
                );
            }
        } catch (IOException | NumberFormatException exception) {
            logger.warn("Could not load saved world for {} from {}.", experienceId.value(), file, exception);
        }
        return blocks;
    }

    private void save(ExperienceId experienceId) {
        var file = worldFile(experienceId);
        var blocks = editedBlocks.getOrDefault(experienceId, Map.of());
        try {
            Files.createDirectories(file.getParent());
            var builder = new StringBuilder("# Vortex saved block edits v1\n");
            blocks.entrySet().stream()
                    .sorted(Comparator.comparingInt((Map.Entry<BlockPosition, String> entry) -> entry.getKey().x())
                            .thenComparingInt(entry -> entry.getKey().y())
                            .thenComparingInt(entry -> entry.getKey().z()))
                    .forEach(entry -> {
                        var position = entry.getKey();
                        builder
                                .append(position.x()).append(' ')
                                .append(position.y()).append(' ')
                                .append(position.z()).append(' ')
                                .append(entry.getValue()).append('\n');
                    });
            Files.writeString(
                    file,
                    builder.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
        } catch (IOException exception) {
            logger.warn("Could not save world edits for {} to {}.", experienceId.value(), file, exception);
        }
    }

    private Path worldFile(ExperienceId experienceId) {
        return experiencesRoot.resolve(experienceId.value()).resolve(WORLD_FILE).normalize();
    }

    private record BlockPosition(int x, int y, int z) {
    }
}
