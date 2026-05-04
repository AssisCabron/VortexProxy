package org.assiscabron.vortexProxy.platform.proxy.render;

import java.util.Objects;

public record MenuItem(
        String id,
        String label,
        String description,
        boolean enabled
) {
    public MenuItem {
        id = requireText(id, "id");
        label = requireText(label, "label");
        description = Objects.requireNonNullElse(description, "");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
