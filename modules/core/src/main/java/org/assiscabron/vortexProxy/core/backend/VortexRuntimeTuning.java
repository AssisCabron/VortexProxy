package org.assiscabron.vortexProxy.core.backend;

final class VortexRuntimeTuning {
    private static final int DEFAULT_PUBLIC_SESSION_MAX_PLAYERS = 24;
    private static final int DEFAULT_STUDIO_SESSION_MAX_PLAYERS = 4;
    private static final int DEFAULT_VIEW_DISTANCE = 4;

    private VortexRuntimeTuning() {
    }

    static int publicSessionMaxPlayers() {
        return intProperty("vortex.session.public.maxPlayers", DEFAULT_PUBLIC_SESSION_MAX_PLAYERS);
    }

    static int studioSessionMaxPlayers() {
        return intProperty("vortex.session.studio.maxPlayers", DEFAULT_STUDIO_SESSION_MAX_PLAYERS);
    }

    static int viewDistance() {
        return intProperty("vortex.session.viewDistance", DEFAULT_VIEW_DISTANCE);
    }

    private static int intProperty(String name, int defaultValue) {
        var value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}
