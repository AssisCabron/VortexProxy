package org.assiscabron.vortexProxy.api;

public enum WorldType {
    VOID,
    FLAT,
    NATURAL;

    public static WorldType fromString(String type) {
        if (type == null || type.isBlank()) return NATURAL;
        return switch (type.toUpperCase().trim()) {
            case "VOID" -> VOID;
            case "FLAT" -> FLAT;
            default -> NATURAL;
        };
    }
}
