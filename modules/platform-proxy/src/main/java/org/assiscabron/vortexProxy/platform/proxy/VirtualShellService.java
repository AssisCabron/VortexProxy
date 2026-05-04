package org.assiscabron.vortexProxy.platform.proxy;

import org.assiscabron.vortexProxy.api.VirtualInstanceState;
import org.assiscabron.vortexProxy.core.PlatformControlPlane;

import org.assiscabron.vortexProxy.api.VirtualInstanceState;
import org.assiscabron.vortexProxy.core.PlatformControlPlane;

import com.velocitypowered.api.proxy.Player;
import org.assiscabron.vortexProxy.platform.proxy.render.MenuItem;
import org.assiscabron.vortexProxy.platform.proxy.render.RenderCommand;
import org.assiscabron.vortexProxy.platform.proxy.render.VirtualRenderSurfaceFactory;
import org.slf4j.Logger;

import java.util.Objects;

public final class VirtualShellService {
    private final PlatformControlPlane controlPlane;
    private final VirtualRenderSurfaceFactory renderSurfaceFactory;
    private final Logger logger;

    public VirtualShellService(
            PlatformControlPlane controlPlane,
            VirtualRenderSurfaceFactory renderSurfaceFactory,
            Logger logger
    ) {
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.renderSurfaceFactory = Objects.requireNonNull(renderSurfaceFactory, "renderSurfaceFactory");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void openHome(Player player) {
        var virtualInstance = controlPlane.virtualInstances().createFor(player.getUniqueId());
        controlPlane.virtualInstances().transition(player.getUniqueId(), VirtualInstanceState.RENDERING_HOME);

        var surface = renderSurfaceFactory.create(player);
        surface.send(new RenderCommand.BootstrapVirtualWorld("vortex_home"));
        surface.send(new RenderCommand.ShowTitle("Vortex", "Choose an experience"));
        surface.send(new RenderCommand.ShowActionBar("Welcome to Vortex"));
        surface.send(new RenderCommand.ShowMenu("platform_home", controlPlane.experiences().list().stream()
                .map(experience -> new MenuItem(
                        experience.id().value(),
                        experience.name(),
                        "Version " + experience.version(),
                        true
                ))
                .toList()));
        surface.flush();

        logger.info("Created virtual client instance {} for player {}.",
                virtualInstance.id().value(), player.getUsername());
    }

    public boolean isInVirtualShell(Player player) {
        return controlPlane.virtualInstances().findByPlayer(player.getUniqueId())
                .filter(instance -> instance.state() != VirtualInstanceState.CLOSED)
                .isPresent();
    }

    public void close(Player player) {
        controlPlane.virtualInstances().close(player.getUniqueId());
        logger.info("Closed virtual client instance for player {}.", player.getUsername());
    }
}
