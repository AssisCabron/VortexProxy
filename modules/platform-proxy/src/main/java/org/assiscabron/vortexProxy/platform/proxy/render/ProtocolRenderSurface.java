package org.assiscabron.vortexProxy.platform.proxy.render;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class ProtocolRenderSurface implements VirtualRenderSurface {
    private static final Title.Times DEFAULT_TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(250),
            Duration.ofSeconds(3),
            Duration.ofMillis(500)
    );

    private final Player player;
    private final Logger logger;
    private final List<RenderCommand> pendingCommands = new ArrayList<>();

    public ProtocolRenderSurface(Player player, Logger logger) {
        this.player = Objects.requireNonNull(player, "player");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public void send(RenderCommand command) {
        pendingCommands.add(Objects.requireNonNull(command, "command"));
    }

    @Override
    public void flush() {
        for (RenderCommand command : pendingCommands) {
            apply(command);
        }
        pendingCommands.clear();
    }

    private void apply(RenderCommand command) {
        switch (command) {
            case RenderCommand.BootstrapVirtualWorld bootstrap -> bootstrapVirtualWorld(bootstrap);
            case RenderCommand.Clear ignored -> clear();
            case RenderCommand.ShowActionBar showActionBar -> showActionBar(showActionBar);
            case RenderCommand.ShowTitle showTitle -> showTitle(showTitle);
            case RenderCommand.ShowMenu showMenu -> showMenu(showMenu);
            case RenderCommand.PlaySound playSound -> playSound(playSound);
        }
    }

    private void bootstrapVirtualWorld(RenderCommand.BootstrapVirtualWorld command) {
        player.sendPlayerListHeaderAndFooter(
                Component.text("Vortex Platform"),
                Component.text("Virtual shell: " + command.worldName())
        );
    }

    private void clear() {
        player.clearPlayerListHeaderAndFooter();
        player.clearTitle();
    }

    private void showActionBar(RenderCommand.ShowActionBar command) {
        player.sendActionBar(Component.text(command.message()));
    }

    private void showTitle(RenderCommand.ShowTitle command) {
        player.showTitle(Title.title(
                Component.text(command.title()),
                Component.text(command.subtitle()),
                DEFAULT_TITLE_TIMES
        ));
    }

    private void showMenu(RenderCommand.ShowMenu command) {
        player.sendPlayerListHeaderAndFooter(
                Component.text("Vortex Platform"),
                Component.text("Select an experience")
        );

        player.sendMessage(Component.empty());
        player.sendMessage(Component.text("Vortex Platform"));
        player.sendMessage(Component.text("Available experiences"));

        for (MenuItem item : command.items()) {
            var status = item.enabled() ? "READY" : "LOCKED";
            player.sendMessage(Component.text("[" + status + "] " + item.label()));
            if (item.description() != null && !item.description().isBlank()) {
                player.sendMessage(Component.text("  " + item.description()));
            }
        }

        player.sendMessage(Component.empty());
    }

    private void playSound(RenderCommand.PlaySound command) {
        try {
            player.playSound(Sound.sound(
                    net.kyori.adventure.key.Key.key(command.sound()),
                    Sound.Source.MASTER,
                    command.volume(),
                    command.pitch()
            ));
        } catch (RuntimeException exception) {
            logger.warn("Could not send virtual sound '{}' to {}.",
                    command.sound(), player.getUsername(), exception);
        }
    }
}
