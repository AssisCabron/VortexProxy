package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ExperienceId;

import java.util.Objects;
import java.util.regex.Pattern;

public record ExperienceId(String value) {
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{2,63}");

    public ExperienceId {
        Objects.requireNonNull(value, "value");
        if (!VALID_ID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid experience id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
