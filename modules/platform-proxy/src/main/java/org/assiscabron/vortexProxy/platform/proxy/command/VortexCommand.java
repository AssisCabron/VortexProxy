package org.assiscabron.vortexProxy.platform.proxy.command;

import org.assiscabron.vortexProxy.core.PlatformControlPlane;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.assiscabron.vortexProxy.core.PlatformControlPlane;
import org.assiscabron.vortexProxy.core.backend.BackendGateway;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiFunction;

public final class VortexCommand implements SimpleCommand {
    private final org.assiscabron.vortexProxy.platform.proxy.VortexProxy plugin;
    private final PlatformControlPlane controlPlane;
    private final BackendGateway backendGateway;
    private final BiFunction<UUID, String, String> dashboardUrl;

    public VortexCommand(
            org.assiscabron.vortexProxy.platform.proxy.VortexProxy plugin,
            PlatformControlPlane controlPlane,
            BackendGateway backendGateway,
            BiFunction<UUID, String, String> dashboardUrl
    ) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.controlPlane = Objects.requireNonNull(controlPlane, "controlPlane");
        this.backendGateway = Objects.requireNonNull(backendGateway, "backendGateway");
        this.dashboardUrl = Objects.requireNonNull(dashboardUrl, "dashboardUrl");
    }

    @Override
    public void execute(Invocation invocation) {
        var args = invocation.arguments();
        var command = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);

        switch (command) {
            case "status" -> status(invocation);
            case "experiences" -> experiences(invocation);
            case "virtuals" -> virtuals(invocation);
            case "reload" -> reload(invocation);
            case "dashboard", "studio" -> dashboard(invocation);
            default -> help(invocation);
        }
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            return List.of("status", "experiences", "virtuals", "reload", "dashboard", "studio");
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return true;
    }

    private void status(Invocation invocation) {
        invocation.source().sendMessage(Component.text("Vortex status"));
        invocation.source().sendMessage(Component.text("Experiences: " + controlPlane.experiences().list().size()));
        invocation.source().sendMessage(Component.text("Runtime instances: " + controlPlane.instances().list().size()));
        invocation.source().sendMessage(Component.text("Virtual shells: " + controlPlane.virtualInstances().list().size()));
        invocation.source().sendMessage(Component.text("Embedded backend: "
                + (backendGateway.available() ? "available" : "unavailable")
                + " (" + backendGateway.status() + ")"));
        invocation.source().sendMessage(Component.text("Backend online: " + backendGateway.onlinePlayers()));
    }

    private void experiences(Invocation invocation) {
        invocation.source().sendMessage(Component.text("Registered experiences"));
        for (var experience : controlPlane.experiences().list()) {
            invocation.source().sendMessage(Component.text("- "
                    + experience.id().value()
                    + " | "
                    + experience.name()
                    + " | "
                    + experience.version()));
        }
    }

    private void virtuals(Invocation invocation) {
        invocation.source().sendMessage(Component.text("Virtual shells"));
        for (var virtualInstance : controlPlane.virtualInstances().list()) {
            invocation.source().sendMessage(Component.text("- "
                    + virtualInstance.id().value()
                    + " | player="
                    + virtualInstance.playerId()
                    + " | state="
                    + virtualInstance.state()));
        }
    }

    private void reload(Invocation invocation) {
        plugin.reloadExperiences();
        invocation.source().sendMessage(Component.text("Experiences reloaded! Total: " + controlPlane.experiences().list().size()));
    }

    private void dashboard(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            invocation.source().sendMessage(Component.text("Open the dashboard from inside the game."));
            return;
        }
        var url = dashboardUrl.apply(player.getUniqueId(), player.getUsername());
        invocation.source().sendMessage(Component.text("Vortex Studio dashboard:"));
        invocation.source().sendMessage(Component.text(url)
                .clickEvent(ClickEvent.openUrl(url))
                .hoverEvent(HoverEvent.showText(Component.text("Open Vortex Studio"))));
    }

    private void help(Invocation invocation) {
        invocation.source().sendMessage(Component.text("Usage: /vortex <status|experiences|virtuals|reload|dashboard>"));
    }
}
