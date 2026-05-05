package org.assiscabron.vortexProxy.core.backend;

import org.assiscabron.vortexProxy.api.ExperienceId;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import net.minestom.server.MinecraftServer;
import net.minestom.server.command.CommandSender;
import net.minestom.server.command.builder.Command;
import net.minestom.server.command.builder.arguments.ArgumentType;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.entity.metadata.other.InteractionMeta;
import net.minestom.server.entity.metadata.other.ItemFrameMeta;
import net.minestom.server.event.GlobalEventHandler;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerBlockBreakEvent;
import net.minestom.server.event.player.PlayerBlockPlaceEvent;
import net.minestom.server.event.player.PlayerEntityInteractEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.extras.velocity.VelocityProxy;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.LightingChunk;
import net.minestom.server.instance.Weather;
import net.minestom.server.instance.block.Block;
import net.minestom.server.item.ItemComponent;
import net.minestom.server.item.ItemStack;
import net.minestom.server.item.Material;
import net.minestom.server.map.Framebuffer;
import net.minestom.server.map.framebuffers.Graphics2DFramebuffer;
import net.minestom.server.network.player.ClientSettings;
import net.minestom.server.potion.Potion;
import net.minestom.server.potion.PotionEffect;
import org.assiscabron.vortexProxy.core.PlatformControlPlane;
import org.assiscabron.vortexProxy.core.scripting.ExperienceScriptEngine;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import org.assiscabron.vortexProxy.core.studio.StudioLinkServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

public final class EmbeddedVortexBackend implements BackendGateway {
    private static final String SERVER_NAME = "vortex-embedded-home";
    private static final Pos SPAWN = new Pos(0.5, 65, 9.5, 180f, 0f);
    private static final int GALLERY_WALL_Z = -8;
    private static final int GALLERY_FLOOR_Y = 64;
    private static final int MAP_ID_BASE = 10_000;
    private static final Pattern TOML_STRING_VALUE = Pattern.compile("(?i)^\\s*%s\\s*=\\s*\"([^\"]+)\"\\s*(?:#.*)?$");

    private final ProxyServer proxyServer;
    private final PlatformControlPlane controlPlane;
    private final Logger logger;
    private final BiFunction<UUID, String, String> dashboardUrl;
    private final ExperienceScriptEngine scriptEngine;
    private final ExperienceWorldStore worldStore;
    private final LocalExperienceSessionManager sessionManager;
    private final AtomicBoolean started = new AtomicBoolean(false);

    private volatile RegisteredServer registeredServer;
    private volatile ServerInfo serverInfo;
    private final List<InstanceContainer> homeShards = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final int MAX_PLAYERS_PER_SHARD = 50;
    private volatile int port;
    private volatile String status = "not started";
    private volatile boolean minestomInitialized;
    
    private final ConcurrentMap<Integer, Framebuffer[]> galleryGrids = new ConcurrentHashMap<>();
    private final ConcurrentMap<Integer, ExperienceCard> cardsByEntityId = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, ExperienceId> studioEditingExperience = new ConcurrentHashMap<>();
    private final java.util.List<net.minestom.server.entity.Entity> galleryEntities = new java.util.concurrent.CopyOnWriteArrayList<>();

    private final org.assiscabron.vortexProxy.api.PlayerDatabase playerDatabase;
    private final MultiversePortalEngine portalEngine;
    private StudioLinkServer studioLinkServer;
    
    private double currentMspt = 0.0;
    private long lastTickNano = 0;

    public EmbeddedVortexBackend(
            ProxyServer proxyServer,
            PlatformControlPlane controlPlane,
            Logger logger,
            BiFunction<UUID, String, String> dashboardUrl,
            org.assiscabron.vortexProxy.api.PlayerDatabase playerDatabase
    ) {
        this.proxyServer = Objects.requireNonNull(proxyServer, "proxyServer");
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.dashboardUrl = Objects.requireNonNull(dashboardUrl, "dashboardUrl");
        this.portalEngine = new MultiversePortalEngine(logger);
        this.scriptEngine = new ExperienceScriptEngine(logger, portalEngine);
        this.worldStore = new ExperienceWorldStore(Path.of("experiences"), logger);
        this.sessionManager = new LocalExperienceSessionManager(this::createExperienceInstance);
        this.playerDatabase = Objects.requireNonNull(playerDatabase, "playerDatabase");
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        try {
            port = findFreePort();
            configureVelocityForwarding();

            var minecraftServer = MinecraftServer.init();
            minestomInitialized = true;
            MinecraftServer.setBrandName("Vortex");

            createHomeShard();
            loadGalleryMaps();
            
            spawnExperienceGallery();
            registerEvents(MinecraftServer.getGlobalEventHandler());

            this.studioLinkServer = new StudioLinkServer(8080, this::onCLIFileSync);
            this.studioLinkServer.start();

            registerCommands();

            MinecraftServer.getSchedulerManager().buildTask(portalEngine::tick).repeat(net.minestom.server.timer.TaskSchedule.tick(1)).schedule();

            MinecraftServer.getSchedulerManager().buildTask(() -> {
                long current = System.nanoTime();
                if (lastTickNano != 0) {
                    currentMspt = (current - lastTickNano) / 1_000_000.0;
                }
                lastTickNano = current;
            }).repeat(Duration.ofMillis(50)).schedule();

            minecraftServer.start("127.0.0.1", port);

            serverInfo = new ServerInfo(SERVER_NAME, new InetSocketAddress("127.0.0.1", port));
            registeredServer = proxyServer.registerServer(serverInfo);
            status = "listening on 127.0.0.1:" + port;
            logger.info("Embedded Vortex backend started on 127.0.0.1:{}.", port);
        } catch (Throwable exception) {
            status = "failed: " + failureMessage(exception);
            started.set(false);
            stopMinestomQuietly();
            logger.error("Failed to start embedded Vortex backend.", exception);
        }
    }

    @Override
    public Optional<RegisteredServer> initialServer() {
        return Optional.ofNullable(registeredServer);
    }

    @Override
    public boolean owns(RegisteredServer server) {
        if (server == null || serverInfo == null) {
            return false;
        }
        return SERVER_NAME.equals(server.getServerInfo().getName());
    }

    @Override
    public boolean available() {
        return started.get() && registeredServer != null;
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public int onlinePlayers() {
        var total = 0;
        for (var shard : homeShards) {
            total += shard.getPlayers().size();
        }
        for (var session : sessionManager.all()) {
            total += session.players().size();
        }
        return total;
    }

    @Override
    public void stop() {
        if (!started.compareAndSet(true, false)) {
            return;
        }

        if (serverInfo != null) {
            proxyServer.unregisterServer(serverInfo);
        }
        stopMinestomQuietly();
        status = "stopped";
    }

    @Override
    public void reload() {
        if (!started.get()) {
            return;
        }
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            loadGalleryMaps();
            paintAllShards();
            spawnExperienceGallery();
            // Force chunk refresh for all players in all shards
            for (var shard : homeShards) {
                for (var player : shard.getPlayers()) {
                    var chunkX = (int) player.getPosition().x() >> 4;
                    var chunkZ = (int) player.getPosition().z() >> 4;
                    for (int x = -2; x <= 2; x++) {
                        for (int z = -2; z <= 2; z++) {
                            var chunk = shard.getChunk(chunkX + x, chunkZ + z);
                            if (chunk != null) chunk.sendChunk(player);
                        }
                    }
                    // Also resend maps
                    sendGalleryMaps(player);
                }
            }
            logger.info("Embedded Vortex backend gallery reloaded across all shards.");
        }).schedule();
    }

    @Override
    public boolean openStudio(UUID playerId, ExperienceId experienceId) {
        if (!started.get() || homeShards.isEmpty()) {
            return false;
        }

        var player = findOnlinePlayer(playerId);
        if (player.isEmpty()) {
            return false;
        }

        MinecraftServer.getSchedulerManager().buildTask(() -> openExperienceStudio(player.orElseThrow(), experienceId))
                .schedule();
        return true;
    }

    @Override
    public void closeExperience(ExperienceId experienceId) {
        var removedSessions = sessionManager.removeExperience(experienceId);

        MinecraftServer.getSchedulerManager().buildTask(() -> {
            for (var session : removedSessions) {
                for (var player : session.players()) {
                    studioEditingExperience.remove(player.getUuid());
                    sendAnnounceMsg(player, "Experiência Desativada", "A experiência foi removida do Vortex Studio.");
                    player.setTag(net.minestom.server.tag.Tag.Boolean("alert_active"), true);
                    player.setInstance(getOrLaunchHomeShard(), SPAWN);
                }
            }
        }).schedule();
    }

    @Override
    public void restartExperience(ExperienceId experienceId) {
        if (!started.get()) {
            return;
        }
        MinecraftServer.getSchedulerManager().buildTask(() -> restartExperienceNow(experienceId)).schedule();
    }

    private void restartExperienceNow(ExperienceId experienceId) {
        var publicPlayers = sessionManager.byExperience(experienceId).stream()
                .filter(session -> session.mode() == ExperienceSessionMode.PUBLIC)
                .findFirst()
                .map(ExperienceSession::players)
                .orElse(List.of());
        for (var player : publicPlayers) {
            sendAnnounceMsg(player, "Reiniciando Servidor", "Publicando nova versão. Aguarde...");
            player.setTag(net.minestom.server.tag.Tag.Boolean("alert_active"), true);
            player.setInstance(getOrLaunchHomeShard(), SPAWN);
        }

        var newSession = sessionManager.recreatePublic(experienceId);
        for (var player : publicPlayers) {
            player.setInstance(newSession.instance(), new Pos(0.5, 66.0, 0.5));
            applyPlayMode(player);
            player.sendMessage(Component.text("Versão mais recente carregada com sucesso.", NamedTextColor.GREEN));
        }
        runExperienceScripts(experienceId, newSession.instance(), publicPlayers);
    }

    private void sendAnnounceMsg(net.minestom.server.entity.Player player, String topMessage, String subMessage) {
        for (int i = 0; i < 100; i++) player.sendMessage(Component.empty());
        player.sendMessage(Component.empty());
        var spacer = Component.text("          ", NamedTextColor.BLACK);
        player.sendMessage(Component.text("-----------------------------------------------------", NamedTextColor.RED, net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH));
        player.sendMessage(Component.empty());
        player.sendMessage(spacer.append(Component.text(" ✖✖ ALERTA DA PLATAFORMA ✖✖ ", NamedTextColor.DARK_RED, net.kyori.adventure.text.format.TextDecoration.BOLD)));
        player.sendMessage(Component.empty());
        player.sendMessage(spacer.append(Component.text(topMessage, NamedTextColor.RED)));
        player.sendMessage(spacer.append(Component.text(subMessage, NamedTextColor.GRAY)));
        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("-----------------------------------------------------", NamedTextColor.RED, net.kyori.adventure.text.format.TextDecoration.STRIKETHROUGH));
    }

    private InstanceContainer getOrLaunchHomeShard() {
        for (var shard : homeShards) {
            if (shard.getPlayers().size() < MAX_PLAYERS_PER_SHARD) {
                return shard;
            }
        }
        return createHomeShard();
    }

    private boolean isHomeShard(Instance instance) {
        return homeShards.contains(instance);
    }

    private void cleanupEmptyShards() {
        if (homeShards.size() <= 1) return;
        for (var shard : homeShards) {
            if (shard.getPlayers().isEmpty()) {
                homeShards.remove(shard);
                MinecraftServer.getInstanceManager().unregisterInstance(shard);
                logger.info("Unregistered empty lobby shard.");
            }
        }
    }

    private InstanceContainer createHomeShard() {
        var shard = MinecraftServer.getInstanceManager().createInstanceContainer();
        shard.setChunkSupplier(LightingChunk::new);
        shard.setTime(6000);
        shard.setTimeRate(0);
        shard.setWeather(Weather.CLEAR, 1);
        shard.setGenerator(homeInstancesGenerator());
        homeShards.add(shard);
        
        // Spawn gallery in the new shard if we already have maps/cards
        MinecraftServer.getSchedulerManager().buildTask(() -> {
            spawnGalleryInShard(shard);
            paintShard(shard);
        }).schedule();
        
        logger.info("Created new lobby shard (Total: {}).", homeShards.size());
        return shard;
    }

    private void paintAllShards() {
        homeShards.forEach(this::paintShard);
    }

    private void paintShard(InstanceContainer shard) {
        if (shard == null) return;
        for (int x = -18; x <= 18; x++) {
            for (int y = 66; y <= 70; y++) {
                shard.setBlock(x, y, GALLERY_WALL_Z + 1, Block.WHITE_CONCRETE);
            }
        }
        for (var card : experienceCards()) {
            for (int x = card.centerX() - 3; x <= card.centerX() + 3; x++) {
                for (int y = 66; y <= 70; y++) {
                    var border = Math.abs(x - card.centerX()) == 3 || y == 66 || y == 70;
                    shard.setBlock(x, y, GALLERY_WALL_Z + 1, border ? Block.QUARTZ_BLOCK : card.block());
                }
            }
        }
    }

    private net.minestom.server.instance.generator.Generator homeInstancesGenerator() {
        return unit -> {
            var start = unit.absoluteStart();
            var modifier = unit.modifier();
            for (int x = 0; x < unit.size().blockX(); x++) {
                for (int z = 0; z < unit.size().blockZ(); z++) {
                    int absoluteX = start.blockX() + x;
                    int absoluteZ = start.blockZ() + z;
                    if (isGalleryFloor(absoluteX, absoluteZ)) {
                        var border = Math.abs(absoluteX) == 18 || absoluteZ == GALLERY_WALL_Z || absoluteZ == 16;
                        modifier.setBlock(absoluteX, GALLERY_FLOOR_Y, absoluteZ,
                                border ? Block.QUARTZ_BLOCK : Block.SMOOTH_QUARTZ);
                        if (absoluteZ % 6 == 0 && absoluteX % 6 == 0) {
                            modifier.setBlock(absoluteX, GALLERY_FLOOR_Y, absoluteZ, Block.SEA_LANTERN);
                        }
                    }
                    if (isGalleryWall(absoluteX, absoluteZ)) {
                        boolean behindCard = false;
                        for (var card : experienceCards()) {
                            if (Math.abs(absoluteX - card.centerX()) <= 2) {
                                behindCard = true;
                                break;
                            }
                        }
                        for (int y = 65; y <= 73; y++) {
                            boolean inCardY = y >= 67 && y <= 69;
                            modifier.setBlock(absoluteX, y, absoluteZ, (behindCard && inCardY) ? Block.AIR : Block.WHITE_CONCRETE);
                        }
                        if (absoluteX % 4 == 0) {
                            modifier.setBlock(absoluteX, 72, absoluteZ + 1, Block.SEA_LANTERN);
                        }
                    }
                    if (isGalleryCeiling(absoluteX, absoluteZ)) {
                        modifier.setBlock(absoluteX, 74, absoluteZ, Block.WHITE_STAINED_GLASS);
                        if (absoluteX % 5 == 0 && absoluteZ % 5 == 0) {
                            modifier.setBlock(absoluteX, 73, absoluteZ, Block.SEA_LANTERN);
                        }
                    }
                    for (var card : experienceCards()) {
                        if (isCardFrame(card, absoluteX, absoluteZ)) {
                            for (int y = 66; y <= 70; y++) {
                                var border = Math.abs(absoluteX - card.centerX()) == 3 || y == 66 || y == 70;
                                modifier.setBlock(absoluteX, y, absoluteZ, border
                                        ? Block.QUARTZ_BLOCK
                                        : Block.AIR);
                            }
                        }
                        if (isExperienceFloor(card, absoluteX, absoluteZ)) {
                            var centerZ = card.experienceZ();
                            var border = Math.abs(absoluteX) == 10
                                    || absoluteZ == centerZ - 8
                                    || absoluteZ == centerZ + 8;
                            modifier.setBlock(absoluteX, GALLERY_FLOOR_Y, absoluteZ,
                                    border ? Block.LIGHT_BLUE_STAINED_GLASS : card.block());
                        }
                    }
                }
            }
        };
    }

    private Optional<net.minestom.server.entity.Player> findOnlinePlayer(UUID playerId) {
        for (var shard : homeShards) {
            for (var player : shard.getPlayers()) {
                if (player.getUuid().equals(playerId)) {
                    return Optional.of(player);
                }
            }
        }
        for (var session : sessionManager.all()) {
            for (var player : session.players()) {
                if (player.getUuid().equals(playerId)) {
                    return Optional.of(player);
                }
            }
        }
        return Optional.empty();
    }

    private void openExperienceStudio(net.minestom.server.entity.Player player, ExperienceId experienceId) {
        // Save position in current experience before switching
        if (player.getInstance() != null) {
            savePlayerPosition(player, player.getInstance());
        }
        controlPlane.virtualInstances().launch(player.getUuid(), experienceId);
        var session = sessionManager.studioSession(player.getUuid(), experienceId);
        studioEditingExperience.put(player.getUuid(), experienceId);
        
        var pos = worldStore.loadPlayerPosition(experienceId, player.getUuid()).orElse(new Pos(0.5, 66.0, 0.5));
        
        player.setInstance(session.instance(), pos)
                .thenRun(() -> MinecraftServer.getSchedulerManager().buildTask(() -> applyStudioMode(player)).schedule());
        applyStudioMode(player);
        runExperienceScripts(experienceId, session.instance(), List.of(player));
        player.sendMessage(Component.text("Studio mode opened for " + experienceId.value() + "."));
        player.sendMessage(Component.text("Creative edits are now saved automatically to this experience."));
        giveAdminTools(player);
    }


    private void stopMinestomQuietly() {
        if (!minestomInitialized) {
            return;
        }
        try {
            galleryGrids.clear();
            cardsByEntityId.clear();
            homeShards.clear();
            MinecraftServer.stopCleanly();
            minestomInitialized = false;
        } catch (Throwable exception) {
            logger.warn("Could not stop embedded Vortex backend cleanly.", exception);
        }
    }

    private String failureMessage(Throwable exception) {
        if (exception.getMessage() != null && !exception.getMessage().isBlank()) {
            return exception.getMessage();
        }
        return exception.getClass().getSimpleName();
    }

    private void registerEvents(GlobalEventHandler events) {
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            var shard = getOrLaunchHomeShard();
            event.setSpawningInstance(shard);
            event.getPlayer().setRespawnPoint(SPAWN);
        });

        events.addListener(PlayerSpawnEvent.class, event -> {
            var player = event.getPlayer();
            
            // Save local DB
            playerDatabase.initPlayer(
                    player.getUuid(), 
                    player.getUsername(), 
                    player.getPlayerConnection().getRemoteAddress().toString()
            );
            
            if (!isHomeShard(event.getInstance())) {
                applyExperienceMode(player, event.getInstance());
                return;
            }
            studioEditingExperience.remove(player.getUuid());
            sendGalleryMaps(player);
            applyHomeMode(player);
            player.addEffect(new Potion(PotionEffect.NIGHT_VISION, 0, Potion.INFINITE_DURATION, 0));

            if (event.isFirstSpawn()) {
                player.teleport(SPAWN);
            }
            
            player.sendPlayerListHeaderAndFooter(
                    Component.text("Vortex Platform", NamedTextColor.AQUA),
                    Component.text("Galeria de Experiências", NamedTextColor.GRAY)
            );

            // Prevent lobby message from overriding an alert 
            if (player.hasTag(net.minestom.server.tag.Tag.Boolean("alert_active"))) {
                player.removeTag(net.minestom.server.tag.Tag.Boolean("alert_active"));
                return; 
            }

            player.showTitle(Title.title(
                    Component.text("VORTEX", NamedTextColor.AQUA, net.kyori.adventure.text.format.TextDecoration.BOLD),
                    Component.text("Clique em uma imagem para entrar", NamedTextColor.WHITE),
                    Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(3), Duration.ofMillis(500))
            ));
            player.sendActionBar(Component.text("Selecione uma experiência na galeria", NamedTextColor.YELLOW));
            player.sendMessage(Component.text("--------------------------------------", NamedTextColor.DARK_GRAY));
            player.sendMessage(Component.text("Vortex carregado com sucesso!", NamedTextColor.GREEN));
            player.sendMessage(Component.text("Você está no ", NamedTextColor.GRAY)
                .append(Component.text("Shard #" + homeShards.indexOf(event.getInstance()), NamedTextColor.YELLOW)));
            player.sendMessage(Component.text("Use ", NamedTextColor.GRAY)
                .append(Component.text("/vx ajuda", NamedTextColor.WHITE))
                .append(Component.text(" para ver os comandos.", NamedTextColor.GRAY)));
            player.sendMessage(Component.text("--------------------------------------", NamedTextColor.DARK_GRAY));
        });

        events.addListener(PlayerEntityInteractEvent.class, event -> {
            onInteraction(event.getPlayer(), event);
        });

        events.addListener(PlayerBlockPlaceEvent.class, this::onBlockPlace);

        events.addListener(PlayerBlockBreakEvent.class, this::onBlockBreak);

        events.addListener(PlayerMoveEvent.class, event -> {
            var player = event.getPlayer();
            portalEngine.checkTeleports(player);

            if (isHomeShard(event.getInstance())) {
                var pos = event.getNewPosition();
                if (Math.abs(pos.x()) > 22 || pos.z() < GALLERY_WALL_Z - 3 || pos.z() > 96 || pos.y() < 58) {
                    event.setNewPosition(SPAWN);
                }
            }
        });



        // Isolate chat messages to the same instance (same experience/lobby)
        events.addListener(net.minestom.server.event.player.PlayerChatEvent.class, event -> {
            var instance = event.getPlayer().getInstance();
            if (instance != null) {
                event.getRecipients().removeIf(p -> p.getInstance() != instance);
            }
        });


        events.addListener(PlayerDisconnectEvent.class, event ->
        {
            var player = event.getPlayer();
            var instance = player.getInstance();
            if (instance != null) {
                savePlayerPosition(player, instance);
            }
            studioEditingExperience.remove(player.getUuid());
            logger.info("{} left embedded Vortex backend.", player.getUsername());
            cleanupEmptyShards();
        });
    }

    private void savePlayerPosition(net.minestom.server.entity.Player player, Instance instance) {
        var session = sessionManager.byInstance(instance);
        if (session.isPresent()) {
            var expId = session.get().experienceId();
            worldStore.savePlayerPosition(expId, player.getUuid(), player.getPosition());
            logger.info("Saved position for {} in experience {}", player.getUsername(), expId.value());
        }
    }

    private void onBlockPlace(PlayerBlockPlaceEvent event) {
        if (event.isCancelled()) {
            return;
        }
        var experienceId = editableExperience(event.getPlayer(), event.getInstance());
        if (experienceId.isEmpty()) {
            return;
        }
        event.consumeBlock(false);
        var position = event.getBlockPosition();
        worldStore.record(
                experienceId.orElseThrow(),
                position.blockX(),
                position.blockY(),
                position.blockZ(),
                event.getBlock()
        );
    }

    private void onBlockBreak(PlayerBlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        var experienceId = editableExperience(event.getPlayer(), event.getInstance());
        if (experienceId.isEmpty()) {
            return;
        }
        var position = event.getBlockPosition();
        worldStore.record(
                experienceId.orElseThrow(),
                position.blockX(),
                position.blockY(),
                position.blockZ(),
                event.getResultBlock()
        );
    }

    private Optional<ExperienceId> editableExperience(net.minestom.server.entity.Player player, Instance instance) {
        if (player.getGameMode() != GameMode.CREATIVE) {
            return Optional.empty();
        }
        var sessionExperience = studioEditingExperience.get(player.getUuid());
        var session = sessionManager.byInstance(instance);
        if (sessionExperience == null
                || session.isEmpty()
                || session.orElseThrow().mode() != ExperienceSessionMode.STUDIO
                || !sessionExperience.equals(session.orElseThrow().experienceId())) {
            return Optional.empty();
        }
        return Optional.of(sessionExperience);
    }

    private void applyExperienceMode(net.minestom.server.entity.Player player, Instance instance) {
        var session = sessionManager.byInstance(instance);
        if (session.isPresent() && session.orElseThrow().isStudioOwnedBy(player.getUuid())) {
            applyStudioMode(player);
            return;
        }
        applyPlayMode(player);
    }

    private void applyHomeMode(net.minestom.server.entity.Player player) {
        applyOptimizedClientSettings(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlying(true);
        player.setFlying(false);
        player.setReducedDebugScreenInformation(true);
    }

    private void applyPlayMode(net.minestom.server.entity.Player player) {
        applyOptimizedClientSettings(player);
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlying(false);
        player.setFlying(false);
        player.setReducedDebugScreenInformation(true);
    }

    private void applyStudioMode(net.minestom.server.entity.Player player) {
        applyOptimizedClientSettings(player);
        player.setGameMode(GameMode.CREATIVE);
        player.setAllowFlying(true);
        player.setFlying(true);
        player.setReducedDebugScreenInformation(false);
    }

    private void applyOptimizedClientSettings(net.minestom.server.entity.Player player) {
        var settings = player.getSettings();
        var viewDistance = (byte) Math.max(2, Math.min(32, VortexRuntimeTuning.viewDistance()));
        player.refreshSettings(new ClientSettings(
                settings.locale(),
                viewDistance,
                settings.chatMessageType(),
                settings.chatColors(),
                settings.displayedSkinParts(),
                settings.mainHand(),
                settings.enableTextFiltering(),
                settings.allowServerListings(),
                ClientSettings.ParticleSetting.MINIMAL
        ));
    }

    private void registerCommands() {
        var tpsCommand = new Command("tps");
        tpsCommand.setDefaultExecutor((sender, context) -> {
            double mspt = currentMspt;
            double tps = Math.min(20, 1000.0 / Math.max(50, mspt));
            if (mspt <= 50) tps = 20.0; // Normal behavior
            
            var tpsColor = tps > 18 ? NamedTextColor.GREEN : (tps > 15 ? NamedTextColor.YELLOW : NamedTextColor.RED);
            
            sender.sendMessage(Component.text("---- [ Desempenho do Servidor ] ----", NamedTextColor.LIGHT_PURPLE));
            sender.sendMessage(Component.text(" TPS: ", NamedTextColor.GRAY)
                .append(Component.text(String.format("%.2f", tps), tpsColor)));
            sender.sendMessage(Component.text(" MSPT: ", NamedTextColor.GRAY)
                .append(Component.text(String.format("%.2fms", mspt), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("------------------------------------", NamedTextColor.LIGHT_PURPLE));
        });
        MinecraftServer.getCommandManager().register(tpsCommand);

        var shardsCommand = new Command("shards");
        shardsCommand.setDefaultExecutor((sender, context) -> {
            sender.sendMessage(Component.text("===== Instâncias do Lobby (Shards) =====", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Total de shards ativos: ", NamedTextColor.GRAY)
                .append(Component.text(homeShards.size(), NamedTextColor.YELLOW)));
            
            for (int i = 0; i < homeShards.size(); i++) {
                var shard = homeShards.get(i);
                var count = shard.getPlayers().size();
                var color = count >= MAX_PLAYERS_PER_SHARD ? NamedTextColor.RED : (count > (MAX_PLAYERS_PER_SHARD * 0.8) ? NamedTextColor.YELLOW : NamedTextColor.GREEN);
                
                sender.sendMessage(Component.text("  > Shard #" + i + ": ", NamedTextColor.WHITE)
                    .append(Component.text(count + "/" + MAX_PLAYERS_PER_SHARD + " jogadores", color)));
            }
            sender.sendMessage(Component.text("=======================================", NamedTextColor.GOLD));
        });
        MinecraftServer.getCommandManager().register(shardsCommand);

        var linkDirect = new Command("vincular");
        linkDirect.addSyntax((sender, context) -> {
            if (!(sender instanceof net.minestom.server.entity.Player player)) return;
            String code = context.get("code");
            if (studioLinkServer.confirmLink(player.getUuid(), code)) {
                player.sendMessage(Component.text("§b[Vortex Studio] §7Terminal vinculado com sucesso!", NamedTextColor.AQUA));
            } else {
                player.sendMessage(Component.text("§c[Vortex Studio] §7Código inválido ou expirado.", NamedTextColor.RED));
            }
        }, ArgumentType.String("code"));
        MinecraftServer.getCommandManager().register(linkDirect);
        logger.info("[Vortex] Comando /vincular registrado com sucesso.");

        var command = new Command("vortex", "vx");
        command.setDefaultExecutor((sender, context) -> sendStatus(sender));
        command.addSyntax((sender, context) -> sendStatus(sender), ArgumentType.Literal("status"));
        
        command.addSyntax((sender, context) -> {
            if (!(sender instanceof net.minestom.server.entity.Player player)) return;
            String code = context.get("code");
            if (studioLinkServer.confirmLink(player.getUuid(), code)) {
                player.sendMessage(Component.text("§b[Vortex Studio] §7Terminal vinculado com sucesso! §aO ambiente local agora está sincronizado.", NamedTextColor.AQUA));
            } else {
                player.sendMessage(Component.text("§c[Vortex Studio] §7Código de vínculo inválido ou já expirado.", NamedTextColor.RED));
            }
        }, ArgumentType.Literal("vincular"), ArgumentType.String("code"));
        command.addSyntax((sender, context) -> sendExperiences(sender), ArgumentType.Literal("experiences"), ArgumentType.Literal("experiencias"));
        command.addSyntax((sender, context) -> sendVirtuals(sender), ArgumentType.Literal("virtuals"), ArgumentType.Literal("virtuais"));
        command.addSyntax((sender, context) -> sendDashboard(sender), ArgumentType.Literal("dashboard"), ArgumentType.Literal("studio"));
        command.addSyntax((sender, context) -> sendHelp(sender), ArgumentType.Literal("help"), ArgumentType.Literal("ajuda"));

        MinecraftServer.getCommandManager().register(command);
    }

    private void sendStatus(CommandSender sender) {
        sender.sendMessage(Component.text("--------- [ Status do Vortex ] ---------", NamedTextColor.AQUA));
        sender.sendMessage(Component.text("Experiências: ", NamedTextColor.GRAY)
            .append(Component.text(controlPlane.experiences().list().size(), NamedTextColor.WHITE)));
        sender.sendMessage(Component.text("Studio Link: ", NamedTextColor.GRAY)
            .append(Component.text("Ativo (Port 8080)", NamedTextColor.YELLOW)));
        sender.sendMessage(Component.text("Comando: /vx vincular <code>", NamedTextColor.GRAY));
        
        var avail = available();
        var availText = avail ? "DISPONÍVEL" : "INDISPONÍVEL";
        var availColor = avail ? NamedTextColor.GREEN : NamedTextColor.RED;
        
        sender.sendMessage(Component.text("Backend embutido: ", NamedTextColor.GRAY)
            .append(Component.text(availText, availColor))
            .append(Component.text(" (" + status() + ")", NamedTextColor.DARK_GRAY)));
        
        sender.sendMessage(Component.text("Total Jogadores Online: ", NamedTextColor.GRAY)
            .append(Component.text(onlinePlayers(), NamedTextColor.GREEN)));
        sender.sendMessage(Component.text("--------------------------------------", NamedTextColor.AQUA));
    }

    private void sendExperiences(CommandSender sender) {
        sender.sendMessage(Component.text("--- [ Experiências Registradas ] ---", NamedTextColor.GOLD));
        for (var experience : controlPlane.experiences().list()) {
            sender.sendMessage(Component.text(" • ", NamedTextColor.DARK_GRAY)
                .append(Component.text(experience.name(), NamedTextColor.WHITE))
                .append(Component.text(" (v" + experience.version() + ")", NamedTextColor.GRAY))
                .append(Component.text(" ID: " + experience.id().value(), NamedTextColor.DARK_GRAY)));
        }
    }

    private void sendVirtuals(CommandSender sender) {
        sender.sendMessage(Component.text("--- [ Shells Virtuais Ativos ] ---", NamedTextColor.LIGHT_PURPLE));
        var list = controlPlane.virtualInstances().list();
        if (list.isEmpty()) {
            sender.sendMessage(Component.text(" Nenhum shell ativo no momento.", NamedTextColor.DARK_GRAY));
        }
        for (var virtualInstance : list) {
            sender.sendMessage(Component.text(" » ", NamedTextColor.DARK_GRAY)
                .append(Component.text(virtualInstance.id().value(), NamedTextColor.WHITE))
                .append(Component.text(" Jogador: ", NamedTextColor.GRAY))
                .append(Component.text(virtualInstance.playerId().toString().substring(0, 8), NamedTextColor.YELLOW))
                .append(Component.text(" Estado: ", NamedTextColor.GRAY))
                .append(Component.text(virtualInstance.state().toString(), NamedTextColor.GREEN)));
        }
    }

    private void sendDashboard(CommandSender sender) {
        if (!(sender instanceof net.minestom.server.entity.Player player)) {
            sender.sendMessage(Component.text("Acesse o dashboard de dentro do jogo.", NamedTextColor.RED));
            return;
        }
        var url = dashboardUrl.apply(player.getUuid(), player.getUsername());
        sender.sendMessage(Component.text("--- [ Vortex Studio Dashboard ] ---", NamedTextColor.GREEN));
        sender.sendMessage(Component.text("Clique abaixo para abrir o seu painel:", NamedTextColor.GRAY));
        sender.sendMessage(Component.text(" ▶ ", NamedTextColor.DARK_GRAY)
            .append(Component.text(url, NamedTextColor.AQUA, net.kyori.adventure.text.format.TextDecoration.UNDERLINED))
            .clickEvent(ClickEvent.openUrl(url))
            .hoverEvent(HoverEvent.showText(Component.text("Abrir o Vortex Studio no Navegador", NamedTextColor.WHITE))));
        sender.sendMessage(Component.text("------------------------------------", NamedTextColor.GREEN));
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("Uso: /vortex <status|experiences|virtuals|dashboard>", NamedTextColor.YELLOW));
    }

    private void spawnExperienceGallery() {
        galleryEntities.forEach(Entity::remove);
        galleryEntities.clear();
        cardsByEntityId.clear();
        homeShards.forEach(this::spawnGalleryInShard);
    }

    private void spawnGalleryInShard(InstanceContainer shard) {
        for (var card : experienceCards()) {
            if (card.mapId().isPresent() && galleryGrids.containsKey(card.mapId().orElseThrow())) {
                int baseId = card.mapId().orElseThrow();
                
                // Spawn 5x3 grid of maps
                for (int row = 0; row < 3; row++) {
                    for (int col = 0; col < 5; col++) {
                        var itemFrame = new Entity(EntityType.ITEM_FRAME);
                        int finalBaseId = baseId;
                        int finalCol = col;
                        int finalRow = row;
                        itemFrame.editEntityMeta(ItemFrameMeta.class, meta -> {
                            meta.setOrientation(ItemFrameMeta.Orientation.SOUTH);
                            meta.setItem(ItemStack.of(Material.FILLED_MAP)
                                    .with(ItemComponent.MAP_ID, finalBaseId + (finalRow * 5 + finalCol)));
                        });
                        double x = card.centerX() + col - 1.5; 
                        double y = 69.0 - row;
                        itemFrame.setInstance(shard, new Pos(x, y, GALLERY_WALL_Z + 2.0, 0f, 0f));
                        galleryEntities.add(itemFrame);
                    }
                }
            }
            var label = new Entity(EntityType.TEXT_DISPLAY);
            label.setNoGravity(true);
            label.editEntityMeta(TextDisplayMeta.class, meta -> {
                meta.setText(Component.text(card.name() + "\n" + card.id().value() + "\nClick to enter"));
                meta.setLineWidth(160);
                meta.setBackgroundColor(0xDDFFFFFF);
                meta.setShadow(true);
                meta.setSeeThrough(true);
                meta.setAlignment(TextDisplayMeta.Alignment.CENTER);
            });
            label.setInstance(shard, new Pos(card.centerX() + 0.5, 71.4, GALLERY_WALL_Z + 2.0, 0f, 0f));
            galleryEntities.add(label);

            var interaction = new Entity(EntityType.INTERACTION);
            interaction.editEntityMeta(InteractionMeta.class, meta -> {
                meta.setWidth(5.0f);
                meta.setHeight(3.0f);
                meta.setResponse(true);
            });
            interaction.setNoGravity(true);
            interaction.setInvisible(true);
            interaction.setInstance(shard, new Pos(card.centerX() + 0.5, 68.0, GALLERY_WALL_Z + 2.0, 0f, 0f));
            galleryEntities.add(interaction);
            cardsByEntityId.put(interaction.getEntityId(), card);

            // --- DYNAMIC REVEAL MULTIVERSE PORTAL (BEHIND THE IMAGE) ---
            double portalX = card.centerX() + 0.5;
            double portalY = 68.0;
            double portalZ = GALLERY_WALL_Z + 1.0; 

            // Inner wall blocks (5x3) at Z = GALLERY_WALL_Z + 1 AND concrete back at Z = GALLERY_WALL_Z
            java.util.Set<net.minestom.server.coordinate.BlockVec> wallBlocks = new java.util.HashSet<>();
            for (int y = 67; y <= 69; y++) {
                for (int x = card.centerX() - 2; x <= card.centerX() + 2; x++) {
                    wallBlocks.add(new net.minestom.server.coordinate.BlockVec(x, y, GALLERY_WALL_Z + 1));
                    wallBlocks.add(new net.minestom.server.coordinate.BlockVec(x, y, GALLERY_WALL_Z));
                }
            }

            // Register the actual portal logic with the wall blocks to reveal
            var session = sessionManager.publicSession(card.id());
            portalEngine.registerPortal(new org.assiscabron.vortexProxy.core.backend.MultiversePortalEngine.Portal(
                shard,
                new Pos(portalX, portalY, portalZ),
                session.instance(),
                new Pos(0.5, 66.0, 0.5),
                5.0, 3.0, 1.0,
                wallBlocks
            ));
        }
    }

    private void onInteraction(net.minestom.server.entity.Player player, net.minestom.server.event.player.PlayerEntityInteractEvent event) {
        var card = cardsByEntityId.get(event.getTarget().getEntityId());
        if (card != null) {
            player.sendMessage(Component.text("Connecting to " + card.name() + "..."));
            
            try {
                // Register the launch in the control plane
                controlPlane.virtualInstances().launch(player.getUuid(), card.id());
                
                openExperiencePlayer(player, card.id());
                
            } catch (Exception e) {
                player.sendMessage(Component.text("Failed to launch: " + e.getMessage()));
            }
        }
    }

    private InstanceContainer createExperienceInstance(ExperienceId id, ExperienceSessionMode mode) {
        var instance = MinecraftServer.getInstanceManager().createInstanceContainer();
        instance.setChunkSupplier(LightingChunk::new);
        
        var manifest = controlPlane.experiences().find(id).orElse(null);
        org.assiscabron.vortexProxy.api.WorldType worldType = (manifest != null) ? manifest.worldType() : org.assiscabron.vortexProxy.api.WorldType.NATURAL;

        if (worldType == org.assiscabron.vortexProxy.api.WorldType.NATURAL) {
            instance.setGenerator(new VortexWorldGenerator());
        } else if (worldType == org.assiscabron.vortexProxy.api.WorldType.FLAT) {
            instance.setGenerator(unit -> {
                if (unit.absoluteStart().blockY() < 64) {
                    unit.modifier().fillHeight(0, 63, Block.DIRT);
                    unit.modifier().fillHeight(63, 64, Block.GRASS_BLOCK);
                }
            });
        } else {
            // VOID
            instance.setGenerator(unit -> {
                if (unit.absoluteStart().blockX() == 0 && unit.absoluteStart().blockZ() == 0 && unit.absoluteStart().blockY() < 64) {
                    unit.modifier().setBlock(0, 60, 0, Block.BEDROCK);
                }
            });
        }

        instance.setTime(6000);
        instance.setTimeRate(0);
        instance.setWeather(Weather.CLEAR, 1);
        worldStore.loadInto(id, instance);

        // Enforce chunk rendering texturization for seamless world exploring
        // Sometimes Minestom doesn't dispatch chunks immediately if light calculus is slightly delayed
        instance.eventNode().addListener(net.minestom.server.event.instance.InstanceChunkLoadEvent.class, event -> {
            net.minestom.server.MinecraftServer.getSchedulerManager().buildTask(() -> {
                var chunk = event.getChunk();
                for (var p : instance.getPlayers()) {
                    // Resend to players in standard simulation distance logic
                    chunk.sendChunk(p);
                }
            }).delay(100, java.time.temporal.ChronoUnit.MILLIS).schedule();
        });

        // Pre-load chunks around spawn so the client renders them immediately
        for (int cx = -8; cx <= 8; cx++) {
            for (int cz = -8; cz <= 8; cz++) {
                instance.loadChunk(cx, cz);
            }
        }

        logger.info("Created {} virtual session container for experience: {}", mode, id.value());
        return instance;
    }

    private void openExperiencePlayer(net.minestom.server.entity.Player player, ExperienceId id) {
        // Save position in current experience before switching
        if (player.getInstance() != null) {
            savePlayerPosition(player, player.getInstance());
        }
        var session = sessionManager.publicSession(id);
        studioEditingExperience.remove(player.getUuid());
        
        var pos = worldStore.loadPlayerPosition(id, player.getUuid()).orElse(new Pos(0.5, 66.0, 0.5));
        
        player.setInstance(session.instance(), pos)
                .thenRun(() -> MinecraftServer.getSchedulerManager()
                        .buildTask(() -> {
                            applyPlayMode(player);
                            announceSessionJoin(session, player);
                        })
                        .schedule());
        applyPlayMode(player);
        runExperienceScripts(id, session.instance(), List.of(player));
        player.sendMessage(Component.text("You joined " + id.value() + "!", net.kyori.adventure.text.format.NamedTextColor.GREEN));
    }

    private void announceSessionJoin(ExperienceSession session, net.minestom.server.entity.Player joinedPlayer) {
        var online = session.players().size();
        for (var player : session.players()) {
            player.sendPlayerListHeaderAndFooter(
                    Component.text("Vortex Experience"),
                    Component.text(session.experienceId().value() + " | " + online + "/" + session.maxPlayers() + " players")
            );
            if (!player.getUuid().equals(joinedPlayer.getUuid())) {
                player.sendMessage(Component.text(joinedPlayer.getUsername() + " joined this experience."));
            }
        }
    }

    private void runExperienceScripts(
            ExperienceId id,
            InstanceContainer instance,
            java.util.Collection<net.minestom.server.entity.Player> contextPlayers
    ) {
        scriptEngine.runServerScripts(id, Path.of("experiences").resolve(id.value()), instance, contextPlayers);
    }

    private void giveAdminTools(net.minestom.server.entity.Player player) {
        player.getInventory().addItemStack(ItemStack.of(Material.NETHER_STAR)
                .with(ItemComponent.CUSTOM_NAME, Component.text("Vortex Configuration Wand", net.kyori.adventure.text.format.NamedTextColor.GOLD)));
        player.getInventory().addItemStack(ItemStack.of(Material.REPEATING_COMMAND_BLOCK)
                .with(ItemComponent.CUSTOM_NAME, Component.text("Experience Settings", net.kyori.adventure.text.format.NamedTextColor.AQUA)));
        player.sendMessage(Component.text("Admin permissions detected. Tools given.", net.kyori.adventure.text.format.NamedTextColor.YELLOW));
    }

    private List<ExperienceCard> experienceCards() {
        var experiences = controlPlane.experiences().list().stream()
                .sorted(java.util.Comparator.comparing(m -> m.id().value()))
                .toList();
        var count = experiences.size();
        return java.util.stream.IntStream.range(0, count)
                .mapToObj(index -> {
                    var experience = experiences.get(index);
                    var centerX = (index * 8) - ((count - 1) * 4);
                    return new ExperienceCard(
                            experience.id(),
                            experience.name(),
                            centerX,
                            36 + (index * 22),
                            blockFromPresentation(experience.presentation().accentBlock(), index),
                            experience.presentation().galleryImage().map(ignored -> MAP_ID_BASE + (index * 100)),
                            experience.presentation().galleryImage()
                    );
                })
                .toList();
    }

    private Block blockFromPresentation(String blockName, int index) {
        var block = Block.fromNamespaceId("minecraft:" + blockName.toLowerCase().replace(' ', '_'));
        return block == null || block == Block.AIR ? cardBlock(index) : block;
    }

    private Block cardBlock(int index) {
        return switch (Math.floorMod(index, 6)) {
            case 0 -> Block.CYAN_CONCRETE;
            case 1 -> Block.LIME_CONCRETE;
            case 2 -> Block.PURPLE_CONCRETE;
            case 3 -> Block.ORANGE_CONCRETE;
            case 4 -> Block.RED_CONCRETE;
            default -> Block.YELLOW_CONCRETE;
        };
    }

    private boolean isGalleryFloor(int x, int z) {
        return Math.abs(x) <= 18 && z >= GALLERY_WALL_Z && z <= 16;
    }

    private boolean isGalleryWall(int x, int z) {
        return Math.abs(x) <= 18 && z == GALLERY_WALL_Z;
    }

    private boolean isGalleryCeiling(int x, int z) {
        return Math.abs(x) <= 18 && z >= GALLERY_WALL_Z && z <= 16;
    }

    private boolean isCardFrame(ExperienceCard card, int x, int z) {
        return z == GALLERY_WALL_Z + 1
                && Math.abs(x - card.centerX()) <= 3;
    }

    private boolean isExperienceFloor(ExperienceCard card, int x, int z) {
        return Math.abs(x) <= 10 && z >= card.experienceZ() - 8 && z <= card.experienceZ() + 8;
    }

    private void sendGalleryMaps(net.minestom.server.entity.Player player) {
        galleryGrids.forEach((baseId, grid) -> {
            for (int i = 0; i < grid.length; i++) {
                player.sendPacket(grid[i].preparePacket(baseId + i));
            }
        });
    }

    private void loadGalleryMaps() {
        galleryGrids.clear();
        for (var card : experienceCards()) {
            if (card.mapId().isEmpty() || card.galleryImage().isEmpty()) {
                continue;
            }
            loadMapGrid(card.galleryImage().orElseThrow(), 5, 3)
                    .ifPresent(grid -> galleryGrids.put(card.mapId().orElseThrow(), grid));
        }
    }

    private Optional<Framebuffer[]> loadMapGrid(String imageSource, int columns, int rows) {
        try (InputStream input = openImageSource(imageSource)) {
            var source = ImageIO.read(input);
            if (source == null) {
                logger.warn("Gallery image source {} is not a readable image (ImageIO returned null).", imageSource);
                return Optional.empty();
            }

            logger.info("Fatiando imagem para grid {}x{}: {}x{} pixels", columns, rows, source.getWidth(), source.getHeight());

            var grid = new Framebuffer[columns * rows];
            int partWidth = source.getWidth() / columns;
            int partHeight = source.getHeight() / rows;

            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < columns; col++) {
                    var part = source.getSubimage(col * partWidth, row * partHeight, partWidth, partHeight);
                    var framebuffer = new Graphics2DFramebuffer();
                    var renderer = framebuffer.getRenderer();
                    // Scale each part to 128x128 to fill the map
                    renderer.drawImage(part, 0, 0, 128, 128, null);
                    grid[row * columns + col] = framebuffer;
                }
            }
            return Optional.of(grid);
        } catch (IOException exception) {
            logger.warn("Could not load gallery image source {}.", imageSource, exception);
            return Optional.empty();
        }
    }

    private InputStream openImageSource(String imageSource) throws IOException {
        logger.info("Tentando abrir imagem: {}", imageSource);
        if (imageSource.startsWith("classpath:")) {
            var stream = EmbeddedVortexBackend.class.getResourceAsStream(imageSource.substring("classpath:".length()));
            if (stream == null) logger.error("Recurso não encontrado no classpath: {}", imageSource);
            return stream;
        }
        if (imageSource.startsWith("http://") || imageSource.startsWith("https://")) {
            return new URL(imageSource).openStream();
        }
        if (imageSource.startsWith("file:")) {
            try {
                var path = Path.of(URI.create(imageSource));
                if (!Files.exists(path)) logger.error("Arquivo não existe no disco (URI): {}", path);
                return Files.newInputStream(path);
            } catch (Exception e) {
                logger.error("Erro ao processar URI de arquivo {}: {}", imageSource, e.getMessage());
                throw e;
            }
        }
        var path = Path.of(imageSource);
        if (!Files.exists(path)) logger.error("Arquivo não existe no disco (Path): {}", path);
        return Files.newInputStream(path);
    }

    private void configureVelocityForwarding() {
        var config = readVelocityConfig();
        var forwardingMode = config
                .flatMap(LoadedConfig::readForwardingMode)
                .orElse("none")
                .toLowerCase();

        if (!"modern".equals(forwardingMode)) {
            logger.info("Minestom Velocity forwarding disabled because player-info-forwarding-mode is '{}'.", forwardingMode);
            return;
        }

        var secretFile = config
                .flatMap(LoadedConfig::readForwardingSecretFile)
                .orElse("forwarding.secret");

        readForwardingSecret(secretFile).ifPresentOrElse(secret -> {
            VelocityProxy.enable(secret);
            logger.info("Enabled Minestom Velocity forwarding using {}.", secretFile);
        }, () -> logger.warn("Velocity forwarding is modern, but {} was not found or was blank.", secretFile));
    }

    private Optional<String> readForwardingSecret(String configuredPath) {
        for (Path candidate : forwardingSecretCandidates(configuredPath)) {
            try {
                if (Files.isRegularFile(candidate)) {
                    var value = Files.readString(candidate).trim();
                    if (!value.isBlank()) {
                        return Optional.of(value);
                    }
                }
            } catch (IOException exception) {
                logger.warn("Could not read {}.", candidate, exception);
            }
        }
        return Optional.empty();
    }

    private Path[] forwardingSecretCandidates(String configuredPath) {
        var configured = Path.of(configuredPath);
        if (configured.isAbsolute()) {
            return new Path[]{configured};
        }
        return new Path[]{
                configured,
                Path.of("config").resolve(configured)
        };
    }

    private Optional<LoadedConfig> readVelocityConfig() {
        for (Path candidate : new Path[]{
                Path.of("velocity.toml"),
                Path.of("config", "velocity.toml")
        }) {
            try {
                if (Files.isRegularFile(candidate)) {
                    var content = Files.readString(candidate);
                    logger.info("Loaded Velocity config for embedded backend from {}.", candidate.toAbsolutePath());
                    return Optional.of(new LoadedConfig(candidate, content));
                }
            } catch (IOException exception) {
                logger.warn("Could not read {}.", candidate, exception);
            }
        }
        logger.warn("velocity.toml was not found. Assuming player-info-forwarding-mode = none for embedded backend.");
        return Optional.empty();
    }

    private Optional<String> readTomlString(String content, String key) {
        var pattern = Pattern.compile(TOML_STRING_VALUE.pattern().formatted(Pattern.quote(key)));
        for (String line : content.split("\\R")) {
            var matcher = pattern.matcher(line);
            if (matcher.matches()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private record LoadedConfig(Path path, String content) {
        Optional<String> readForwardingMode() {
            return read("player-info-forwarding-mode");
        }

        Optional<String> readForwardingSecretFile() {
            return read("forwarding-secret-file");
        }

        private Optional<String> read(String key) {
            var pattern = Pattern.compile(TOML_STRING_VALUE.pattern().formatted(Pattern.quote(key)));
            for (String line : content.split("\\R")) {
                var matcher = pattern.matcher(line);
                if (matcher.matches()) {
                    return Optional.of(matcher.group(1));
                }
            }
            return Optional.empty();
        }
    }

    private int findFreePort() throws IOException {
        try (var socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private record ExperienceCard(
            org.assiscabron.vortexProxy.api.ExperienceId id,
            String name,
            int centerX,
            int experienceZ,
            Block block,
            Optional<Integer> mapId,
            Optional<String> galleryImage
    ) {
    }

    private void onCLIFileSync(UUID playerId, String experienceId, String path, String contentBase64) {
        var expId = new ExperienceId(experienceId);
        
        // 1. Security Check: Ownership
        var manifestOpt = controlPlane.experiences().find(expId);
        if (manifestOpt.isEmpty()) {
            logger.warn("Vortex Studio: Experience {} not found for sync", experienceId);
            return;
        }
        
        var manifest = manifestOpt.get();
        var ownerOpt = manifest.owner();
        boolean isOwner = ownerOpt.isPresent() && ownerOpt.get().playerId().equals(playerId.toString());
        
        // FOR DEV MODE: If experience has no owner, let's allow it (but log it)
        if (ownerOpt.isEmpty()) {
            logger.warn("Vortex Studio: Experience {} has no owner defined. Allowing sync but this is insecure for production.", experienceId);
        } else if (!isOwner) {
            logger.error("Security Violation: Player {} (UUID) tried to sync scripts to experience {} owned by {}!", 
                playerId, experienceId, ownerOpt.get().playerId());
            return;
        }

        if (contentBase64 == null) {
            logger.warn("Vortex Studio: Received sync for {} with null content. Skipping.", path);
            return;
        }

        byte[] data = java.util.Base64.getDecoder().decode(contentBase64);
        var experienceRoot = Path.of("experiences").resolve(experienceId).resolve("scripts").resolve("server").toAbsolutePath().normalize();
        
        // 2. Prevent directory traversal
        var filePath = experienceRoot.resolve(path).normalize();
        if (!filePath.startsWith(experienceRoot)) {
            logger.error("Security Violation: Attempted directory traversal sync for experience {}. Root: {}, Path: {}", experienceId, experienceRoot, filePath);
            return;
        }

        try {
            Files.createDirectories(filePath.getParent());
            Files.write(filePath, data);
            logger.info("Vortex Studio: Synced file {} for experience {}", path, experienceId);

            // Hot Reload if anyone is in this experience
            var session = sessionManager.publicSession(expId);
            if (session != null) {
                scriptEngine.runServerScripts(expId, experienceRoot, session.instance());
                var notification = Component.text("§b[Vortex Studio] §7Script §f" + path + " §7sincronizado e recarregado!", NamedTextColor.GRAY);
                session.instance().getPlayers().forEach(p -> p.sendMessage(notification));
            }
        } catch (IOException e) {
            logger.error("Failed to sync Vortex Studio file", e);
        }
    }
}
