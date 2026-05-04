package org.assiscabron.vortexProxy.api;

import java.util.UUID;

public interface PlayerDatabase {
    void initPlayer(UUID uuid, String username, String ip);
}
