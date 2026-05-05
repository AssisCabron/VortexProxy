package org.assiscabron.vortexProxy.platform.proxy;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceManifest;
import org.assiscabron.vortexProxy.api.ExperiencePresentation;
import org.assiscabron.vortexProxy.api.ResourceLimits;
import org.assiscabron.vortexProxy.api.ExperienceCatalog;

import com.google.inject.Inject;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import org.assiscabron.vortexProxy.api.*;
import org.assiscabron.vortexProxy.core.ExperiencePackageLoader;
import org.assiscabron.vortexProxy.core.PlatformControlPlane;
import org.assiscabron.vortexProxy.core.backend.BackendGateway;
import org.assiscabron.vortexProxy.core.backend.EmbeddedVortexBackend;
import org.assiscabron.vortexProxy.core.backend.UnavailableBackendGateway;
import org.assiscabron.vortexProxy.core.dashboard.VortexDashboardServer;
import org.assiscabron.vortexProxy.infra.InMemoryExperienceCatalog;
import org.assiscabron.vortexProxy.infra.InMemoryInstanceRegistry;
import org.assiscabron.vortexProxy.infra.InMemoryVirtualInstanceService;
import org.assiscabron.vortexProxy.infrastructure.db.LocalPlayerDatabase;
import org.assiscabron.vortexProxy.platform.proxy.command.VortexCommand;
import org.assiscabron.vortexProxy.platform.proxy.protocol.VelocityReflectionRawProtocolTransport;
import org.assiscabron.vortexProxy.platform.proxy.render.VirtualRenderSurfaceFactory;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.velocitypowered.api.plugin.annotation.DataDirectory;

@Plugin(id = "vortexproxy", name = "VortexProxy", version = "1.0", authors = {"assiscabron"})
public class VortexProxy {

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxyServer;

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private PlatformControlPlane controlPlane;
    private VirtualShellService virtualShellService;
    private BackendGateway backendGateway;
    private VortexDashboardServer dashboardServer;

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        Path pluginsFolder = dataDirectory.getParent();
        if (org.assiscabron.vortexProxy.platform.proxy.util.ViaAutoInstaller.checkAndInstall(pluginsFolder, logger)) {
             proxyServer.shutdown(Component.text("Vortex: Compatibility Bridge downloaded! Please start the proxy again to apply the injector."));
             return;
        }

        var catalog = new InMemoryExperienceCatalog();
        var instances = new InMemoryInstanceRegistry();
        var virtualInstances = new InMemoryVirtualInstanceService();

        loadExperiencePackages(catalog);

        controlPlane = new PlatformControlPlane(catalog, instances, virtualInstances);
        var renderSurfaceFactory = new VirtualRenderSurfaceFactory(
                new VelocityReflectionRawProtocolTransport(logger),
                logger
        );
        virtualShellService = new VirtualShellService(controlPlane, renderSurfaceFactory, logger);
        backendGateway = createBackendGateway();
        backendGateway.start();
        dashboardServer = new VortexDashboardServer(
                controlPlane.experiences(),
                this::publishExperience,
                (playerId, experienceId) -> backendGateway.openStudio(playerId, experienceId),
                this::deleteExperience,
                logger
        );
        dashboardServer.start();
        registerCommands();

        logger.info("Vortex control plane initialized with {} registered experience(s).",
                controlPlane.experiences().list().size());
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        backendGateway.initialServer().ifPresentOrElse(event::setInitialServer, () ->
                logger.error("No embedded Vortex backend is available for {}: {}",
                        event.getPlayer().getUsername(), backendGateway.status()));
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        if (backendGateway.owns(event.getServer())) {
            virtualShellService.openHome(event.getPlayer());
        }
    }

    @Subscribe(order = PostOrder.FIRST)
    public void onKickedFromServer(KickedFromServerEvent event) {
        if (!backendGateway.owns(event.getServer())) {
            return;
        }

        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(
                Component.text("Vortex backend rejected the connection: "
                        + event.getServerKickReason()
                        .map(Component::toString)
                        .orElse("unknown reason"))
        ));
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        virtualShellService.close(event.getPlayer());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (backendGateway != null) {
            backendGateway.stop();
        }
        if (dashboardServer != null) {
            dashboardServer.stop();
        }
    }

    private void registerCommands() {
        var commandManager = proxyServer.getCommandManager();
        var meta = commandManager.metaBuilder("vortex")
                .aliases("vx")
                .plugin(this)
                .build();
        commandManager.register(meta, new VortexCommand(this, controlPlane, backendGateway, this::dashboardUrl));
    }

    private BackendGateway createBackendGateway() {
        try {
            var playerDatabase = new LocalPlayerDatabase(dataDirectory, logger);
            return new EmbeddedVortexBackend(proxyServer, controlPlane, logger, this::dashboardUrl, playerDatabase);
        } catch (RuntimeException exception) {
            logger.error("Could not initialize embedded Vortex backend.", exception);
            return new UnavailableBackendGateway(
                    exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage()
            );
        }
    }

    public synchronized void reloadExperiences() {
        controlPlane.experiences().clear();
        loadExperiencePackages(controlPlane.experiences());
        backendGateway.reload();
        logger.info("Experiences reloaded. Total: {}", controlPlane.experiences().list().size());
    }

    public synchronized void publishExperience(ExperienceId experienceId) {
        controlPlane.experiences().clear();
        loadExperiencePackages(controlPlane.experiences());
        backendGateway.reload();
        backendGateway.restartExperience(experienceId);
        logger.info("Experience {} published. Total: {}", experienceId.value(), controlPlane.experiences().list().size());
    }

    public synchronized void deleteExperience(ExperienceId experienceId) {
        backendGateway.closeExperience(experienceId);
        reloadExperiences();
    }

    public String dashboardUrl(UUID playerId, String username) {
        return dashboardServer == null ? "dashboard unavailable" : dashboardServer.createSessionUrl(playerId, username);
    }

    private void loadExperiencePackages(ExperienceCatalog catalog) {
        var loader = new ExperiencePackageLoader();
        var root = Path.of("experiences");
        logger.info("Tentando carregar experiências de: {}", root.toAbsolutePath());
        try {
            var loaded = loader.loadAll(root);
            loaded.forEach(catalog::register);
            if (!loaded.isEmpty()) {
                logger.info("Loaded {} experience package(s) from disk.", loaded.size());
                return;
            }
        } catch (IOException | RuntimeException exception) {
            logger.warn("Could not load experiences from disk. Falling back to built-in platform home.", exception);
        }

        catalog.register(new ExperienceManifest(
                new ExperienceId("platform_home"),
                "Platform Home",
                "0.1.0",
                Map.of("server", "scripts/server/init.lua"),
                List.of("players.read", "ui.render"),
                new ResourceLimits(200, 250, 50_000, 64),
                ExperiencePresentation.of(
                        "The default Vortex platform experience.",
                        "classpath:/assets/vortex/platform_home.jpg",
                        "cyan_concrete"
                )
        ));
    }
}
