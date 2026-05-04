package org.assiscabron.vortexProxy.platform.proxy.render;

import java.util.List;
import java.util.Objects;

public sealed interface RenderCommand
        permits RenderCommand.BootstrapVirtualWorld,
        RenderCommand.Clear,
        RenderCommand.ShowActionBar,
        RenderCommand.ShowTitle,
        RenderCommand.ShowMenu,
        RenderCommand.PlaySound {

    record BootstrapVirtualWorld(String worldName) implements RenderCommand {
        public BootstrapVirtualWorld {
            worldName = requireText(worldName, "worldName");
        }
    }

    record Clear() implements RenderCommand {
    }

    record ShowActionBar(String message) implements RenderCommand {
        public ShowActionBar {
            message = requireText(message, "message");
        }
    }

    record ShowTitle(String title, String subtitle) implements RenderCommand {
        public ShowTitle {
            title = requireText(title, "title");
            subtitle = Objects.requireNonNullElse(subtitle, "");
        }
    }

    record ShowMenu(String menuId, List<MenuItem> items) implements RenderCommand {
        public ShowMenu {
            menuId = requireText(menuId, "menuId");
            items = List.copyOf(items);
        }
    }

    record PlaySound(String sound, float volume, float pitch) implements RenderCommand {
        public PlaySound {
            sound = requireText(sound, "sound");
            if (volume < 0) {
                throw new IllegalArgumentException("volume cannot be negative");
            }
            if (pitch < 0.5f || pitch > 2.0f) {
                throw new IllegalArgumentException("pitch must be between 0.5 and 2.0");
            }
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
