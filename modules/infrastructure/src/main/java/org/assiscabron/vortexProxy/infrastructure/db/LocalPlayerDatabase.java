package org.assiscabron.vortexProxy.infrastructure.db;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.assiscabron.vortexProxy.api.PlayerDatabase;
import java.util.UUID;

public class LocalPlayerDatabase implements PlayerDatabase {
    private final Path storageDir;
    private final Logger logger;
    private final Gson gson;

    public LocalPlayerDatabase(Path basePath, Logger logger) {
        this.storageDir = basePath.resolve("players");
        this.logger = logger;
        this.gson = new GsonBuilder().setPrettyPrinting().create();

        try {
            Files.createDirectories(this.storageDir);
        } catch (IOException e) {
            this.logger.error("Could not create local player database directory.", e);
        }
    }

    public void initPlayer(UUID uuid, String username, String ip) {
        Path playerFile = storageDir.resolve(uuid.toString() + ".json");
        try {
            JsonObject data;
            if (Files.exists(playerFile)) {
                String content = Files.readString(playerFile);
                data = gson.fromJson(content, JsonObject.class);
            } else {
                data = new JsonObject();
                data.addProperty("uuid", uuid.toString());
                data.addProperty("firstJoin", System.currentTimeMillis());
            }

            // Update stats
            data.addProperty("lastUsername", username);
            data.addProperty("lastIp", ip);
            data.addProperty("lastLogin", System.currentTimeMillis());

            Files.writeString(playerFile, gson.toJson(data));
        } catch (Exception e) {
            logger.warn("Failed to save player data for uuid={}", uuid, e);
        }
    }
}
