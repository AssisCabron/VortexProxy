package org.assiscabron.vortexProxy.core.backend;

import org.assiscabron.vortexProxy.api.ExperienceId;

import org.assiscabron.vortexProxy.api.ExperienceId;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.assiscabron.vortexProxy.api.ExperienceId;

import java.util.Optional;
import java.util.UUID;

public interface BackendGateway {
    void start();

    Optional<RegisteredServer> initialServer();

    boolean owns(RegisteredServer server);

    boolean available();

    String status();

    int onlinePlayers();

    void stop();

    void reload();

    boolean openStudio(UUID playerId, ExperienceId experienceId);

    void restartExperience(ExperienceId experienceId);

    void closeExperience(ExperienceId experienceId);
}
