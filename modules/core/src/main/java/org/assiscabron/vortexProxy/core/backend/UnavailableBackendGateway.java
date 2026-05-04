package org.assiscabron.vortexProxy.core.backend;

import org.assiscabron.vortexProxy.api.ExperienceId;

import org.assiscabron.vortexProxy.api.ExperienceId;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.assiscabron.vortexProxy.api.ExperienceId;

import java.util.Optional;
import java.util.UUID;

public final class UnavailableBackendGateway implements BackendGateway {
    private final String reason;

    public UnavailableBackendGateway(String reason) {
        this.reason = reason;
    }

    @Override
    public void start() {
    }

    @Override
    public Optional<RegisteredServer> initialServer() {
        return Optional.empty();
    }

    @Override
    public boolean owns(RegisteredServer server) {
        return false;
    }

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String status() {
        return reason;
    }

    @Override
    public int onlinePlayers() {
        return 0;
    }

    @Override
    public void stop() {
    }

    @Override
    public void reload() {
    }

    @Override
    public boolean openStudio(UUID playerId, ExperienceId experienceId) {
        return false;
    }

    @Override
    public void restartExperience(ExperienceId experienceId) {
    }

    @Override
    public void closeExperience(ExperienceId experienceId) {
    }
}
