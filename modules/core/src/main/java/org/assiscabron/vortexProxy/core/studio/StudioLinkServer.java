package org.assiscabron.vortexProxy.core.studio;

import com.google.gson.Gson;
import org.assiscabron.vortexProxy.api.ExperienceId;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class StudioLinkServer extends WebSocketServer {
    private static final Logger logger = LoggerFactory.getLogger(StudioLinkServer.class);
    private static final Gson gson = new Gson();

    // Map code -> WebSocket (Pending authentication)
    private final Map<String, WebSocket> pendingLinks = new ConcurrentHashMap<>();
    // Map UUID (Player) -> WebSocket (Authenticated)
    private final Map<UUID, WebSocket> authenticatedSessions = new ConcurrentHashMap<>();
    // Persistent verified codes (Map code -> Player UUID)
    private final Map<String, UUID> verifiedCoupons = new ConcurrentHashMap<>();
    private static final Path PAIRINGS_FILE = Path.of("studio_pairings.json");

    private final SyncHandler syncHandler;

    public interface SyncHandler {
        void onFileSync(UUID playerId, String experienceId, String path, String content);
    }

    public StudioLinkServer(int port, SyncHandler syncHandler) {
        super(new InetSocketAddress(port));
        this.syncHandler = syncHandler;
        loadPairings();
    }

    private void loadPairings() {
        if (Files.exists(PAIRINGS_FILE)) {
            try {
                String json = Files.readString(PAIRINGS_FILE);
                java.lang.reflect.Type type = new com.google.gson.reflect.TypeToken<Map<String, UUID>>(){}.getType();
                Map<String, UUID> loaded = gson.fromJson(json, type);
                if (loaded != null) verifiedCoupons.putAll(loaded);
                logger.info("Loaded {} Studio Link pairings from disk", verifiedCoupons.size());
            } catch (IOException e) {
                logger.error("Failed to load Studio Link pairings", e);
            }
        }
    }

    private void savePairings() {
        try {
            Files.writeString(PAIRINGS_FILE, gson.toJson(verifiedCoupons));
        } catch (IOException e) {
            logger.error("Failed to save Studio Link pairings", e);
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        logger.info("New Studio Link connection from {}", conn.getRemoteSocketAddress());
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        authenticatedSessions.values().remove(conn);
        pendingLinks.values().remove(conn);
        logger.info("Studio Link connection closed: {}", reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        try {
            logger.info("Raw message from CLI: {}", message);
            StudioLinkMessage msg = gson.fromJson(message, StudioLinkMessage.class);
            if ("AUTH".equals(msg.type())) {
                handleAuth(conn, msg);
            } else {
                handleSyncMessage(conn, msg);
            }
        } catch (Exception e) {
            logger.error("Error processing Studio Link message", e);
        }
    }

    private void handleAuth(WebSocket conn, StudioLinkMessage msg) {
        // Check if this code was already verified before
        UUID existingPlayer = verifiedCoupons.get(msg.code());
        if (existingPlayer != null) {
            authenticatedSessions.put(existingPlayer, conn);
            conn.send(gson.toJson(new StudioLinkMessage("LINK_ACK", msg.code(), null, null, null, "Re-authenticated successfully", null)));
            logger.info("CLI re-authenticated successfully with code: {}", msg.code());
            return;
        }

        pendingLinks.put(msg.code(), conn);
        logger.info("CLI requested link with code: {}", msg.code());
    }

    private void handleSyncMessage(WebSocket conn, StudioLinkMessage msg) {
        if ("SYNC_FILE".equals(msg.type())) {
            handleFileSync(conn, msg);
        } else if ("PULL_FILES".equals(msg.type())) {
            handleFilePull(conn, msg);
        }
    }

    private void handleFilePull(WebSocket conn, StudioLinkMessage msg) {
        UUID playerId = getPlayerForConnection(conn);
        if (playerId == null) return;

        var root = Path.of("experiences").resolve(msg.experienceId()).resolve("scripts").resolve("server");
        
        if (!Files.exists(root)) return;

        List<StudioLinkMessage.StudioFileInfo> files = new java.util.ArrayList<>();
        try {
            Files.walk(root).filter(Files::isRegularFile).forEach(path -> {
                try {
                    String relPath = root.relativize(path).toString().replace("\\", "/");
                    byte[] content = Files.readAllBytes(path);
                    files.add(new StudioLinkMessage.StudioFileInfo(relPath, java.util.Base64.getEncoder().encodeToString(content)));
                } catch (IOException ignored) {}
            });

            conn.send(gson.toJson(new StudioLinkMessage("PULL_RESPONSE", null, msg.experienceId(), null, null, null, files)));
        } catch (IOException e) {
            logger.error("Failed to walk experience directory for pull", e);
        }
    }

    private UUID getPlayerForConnection(WebSocket conn) {
        for (var entry : authenticatedSessions.entrySet()) {
            if (entry.getValue().equals(conn)) return entry.getKey();
        }
        return null;
    }

    private void handleFileSync(WebSocket conn, StudioLinkMessage msg) {
        UUID playerId = getPlayerForConnection(conn);
        if (playerId == null) {
            logger.warn("Received sync request from unauthenticated connection!");
            return;
        }

        syncHandler.onFileSync(playerId, msg.experienceId(), msg.path(), msg.content());
    }

    public boolean confirmLink(UUID playerId, String code) {
        WebSocket conn = pendingLinks.remove(code);
        if (conn != null) {
            verifiedCoupons.put(code, playerId); // Remember this!
            authenticatedSessions.put(playerId, conn);
            savePairings(); // Persist to disk!
            conn.send(gson.toJson(new StudioLinkMessage("LINK_ACK", code, null, null, null, "Linked to player " + playerId, null)));
            return true;
        }
        return false;
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        logger.error("Studio Link error", ex);
    }

    @Override
    public void onStart() {
        logger.info("Vortex Studio Link Server started on port {}", getPort());
    }
}
