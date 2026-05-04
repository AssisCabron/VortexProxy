package org.assiscabron.vortexProxy.api;

import org.assiscabron.vortexProxy.api.ExperiencePresentation;

import java.util.Objects;
import java.util.Optional;

public record ExperiencePresentation(
        String description,
        Optional<String> galleryImage,
        String accentBlock
) {
    public static final ExperiencePresentation DEFAULT = new ExperiencePresentation(
            "A Vortex experience",
            Optional.empty(),
            "cyan_concrete"
    );

    public ExperiencePresentation {
        description = requireText(description, "description");
        galleryImage = Objects.requireNonNull(galleryImage, "galleryImage")
                .map(String::trim)
                .filter(value -> !value.isBlank());
        accentBlock = requireText(accentBlock, "accentBlock");
    }

    public static ExperiencePresentation of(String description, String galleryImage, String accentBlock) {
        return new ExperiencePresentation(description, Optional.ofNullable(galleryImage), accentBlock);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " cannot be blank");
        }
        return value;
    }
}
