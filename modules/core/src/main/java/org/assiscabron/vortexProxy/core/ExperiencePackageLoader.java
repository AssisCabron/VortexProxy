package org.assiscabron.vortexProxy.core;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceManifest;
import org.assiscabron.vortexProxy.api.ExperienceOwner;
import org.assiscabron.vortexProxy.api.ExperiencePresentation;
import org.assiscabron.vortexProxy.api.ResourceLimits;
import org.assiscabron.vortexProxy.api.WorldType;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public final class ExperiencePackageLoader {
    public List<ExperienceManifest> loadAll(Path experiencesRoot) throws IOException {
        Objects.requireNonNull(experiencesRoot, "experiencesRoot");
        if (!Files.isDirectory(experiencesRoot)) {
            return List.of();
        }

        try (var stream = Files.list(experiencesRoot)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.resolve("manifest.json"))
                    .filter(Files::isRegularFile)
                    .flatMap(path -> {
                        try {
                            return java.util.stream.Stream.of(load(path));
                        } catch (IOException | RuntimeException exception) {
                            org.slf4j.LoggerFactory.getLogger(ExperiencePackageLoader.class)
                                    .error("Erro ao carregar manifesto em {}: {}", path, exception.getMessage());
                            return java.util.stream.Stream.empty();
                        }
                    })
                    .toList();
        }
    }

    public ExperienceManifest load(Path manifestPath) throws IOException {
        Objects.requireNonNull(manifestPath, "manifestPath");
        try (Reader reader = Files.newBufferedReader(manifestPath)) {
            var json = JsonParser.parseReader(reader).getAsJsonObject();
            return parse(manifestPath.getParent(), json);
        }
    }



    private ExperienceManifest parse(Path packageRoot, JsonObject json) {
        var resources = object(json, "resources")
                .map(this::parseResources)
                .orElse(new ResourceLimits(50, 250, 50_000, 64));

        return new ExperienceManifest(
                new ExperienceId(requiredString(json, "id")),
                requiredString(json, "name"),
                requiredString(json, "version"),
                parseStringMap(object(json, "entrypoints").orElseThrow(() ->
                        new IllegalArgumentException("entrypoints is required"))),
                parseStringList(json, "permissions"),
                resources,
                parsePresentation(packageRoot, object(json, "presentation").orElse(new JsonObject())),
                parseOwner(object(json, "owner").orElse(null)),
                WorldType.fromString(optionalString(json, "worldType").orElse("NATURAL"))
        );
    }

    private Optional<ExperienceOwner> parseOwner(JsonObject json) {
        if (json == null) {
            return ExperienceOwner.none();
        }
        var playerId = optionalString(json, "playerId");
        var username = optionalString(json, "username");
        if (playerId.isEmpty() || username.isEmpty()) {
            return ExperienceOwner.none();
        }
        return Optional.of(new ExperienceOwner(playerId.orElseThrow(), username.orElseThrow()));
    }

    private ResourceLimits parseResources(JsonObject json) {
        return new ResourceLimits(
                intValue(json, "maxPlayers", 50),
                intValue(json, "maxEntities", 250),
                intValue(json, "luaInstructionBudgetPerTick", 50_000),
                intValue(json, "memoryMb", 64)
        );
    }

    private ExperiencePresentation parsePresentation(Path packageRoot, JsonObject json) {
        var image = optionalString(json, "galleryImage").map(value -> resolveAsset(packageRoot, value));
        return new ExperiencePresentation(
                optionalString(json, "description").orElse("A Vortex experience"),
                image,
                optionalString(json, "accentBlock").orElse("cyan_concrete")
        );
    }

    private String resolveAsset(Path packageRoot, String value) {
        if (value.startsWith("classpath:") || value.startsWith("http://") || value.startsWith("https://")) {
            return value;
        }
        var path = Path.of(value);
        if (path.isAbsolute()) {
            return path.toUri().toString();
        }
        return packageRoot.resolve(path).normalize().toUri().toString();
    }

    private Map<String, String> parseStringMap(JsonObject json) {
        var values = new LinkedHashMap<String, String>();
        for (var entry : json.entrySet()) {
            values.put(entry.getKey(), entry.getValue().getAsString());
        }
        return values;
    }

    private List<String> parseStringList(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return List.of();
        }
        return json.getAsJsonArray(key)
                .asList()
                .stream()
                .map(element -> element.getAsString())
                .toList();
    }

    private Optional<JsonObject> object(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonObject()) {
            return Optional.empty();
        }
        return Optional.of(json.getAsJsonObject(key));
    }

    private String requiredString(JsonObject json, String key) {
        return optionalString(json, key).orElseThrow(() -> new IllegalArgumentException(key + " is required"));
    }

    private Optional<String> optionalString(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return Optional.empty();
        }
        return Optional.of(json.get(key).getAsString());
    }

    private int intValue(JsonObject json, String key, int fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        return json.get(key).getAsInt();
    }
}
