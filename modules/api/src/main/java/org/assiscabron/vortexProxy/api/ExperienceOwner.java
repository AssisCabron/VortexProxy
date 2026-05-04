package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ExperienceOwner;

import java.util.Objects;
import java.util.Optional;

public record ExperienceOwner(String playerId, String username) {
    public ExperienceOwner {
        playerId = requireText(playerId, "playerId");
        username = requireText(username, "username");
    }

    public static Optional<ExperienceOwner> none() {
        return Optional.empty();
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
