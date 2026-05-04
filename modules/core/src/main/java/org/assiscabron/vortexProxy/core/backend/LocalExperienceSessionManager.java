package org.assiscabron.vortexProxy.core.backend;

import org.assiscabron.vortexProxy.api.ExperienceId;

import org.assiscabron.vortexProxy.api.ExperienceId;

import net.minestom.server.instance.Instance;
import net.minestom.server.instance.InstanceContainer;
import org.assiscabron.vortexProxy.api.ExperienceId;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;

final class LocalExperienceSessionManager {
    private final BiFunction<ExperienceId, ExperienceSessionMode, InstanceContainer> instanceFactory;
    private final ConcurrentMap<ExperienceId, ExperienceSession> publicSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<StudioKey, ExperienceSession> studioSessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<Instance, ExperienceSession> sessionsByInstance = new ConcurrentHashMap<>();

    LocalExperienceSessionManager(BiFunction<ExperienceId, ExperienceSessionMode, InstanceContainer> instanceFactory) {
        this.instanceFactory = instanceFactory;
    }

    ExperienceSession publicSession(ExperienceId experienceId) {
        return publicSessions.compute(experienceId, (id, existing) -> {
            if (existing != null && existing.hasCapacity()) {
                return existing;
            }
            var created = new ExperienceSession(
                    "public-" + experienceId.value() + "-1",
                    experienceId,
                    ExperienceSessionMode.PUBLIC,
                    instanceFactory.apply(experienceId, ExperienceSessionMode.PUBLIC),
                    Optional.empty(),
                    VortexRuntimeTuning.publicSessionMaxPlayers()
            );
            sessionsByInstance.put(created.instance(), created);
            return created;
        });
    }

    ExperienceSession studioSession(UUID owner, ExperienceId experienceId) {
        var key = new StudioKey(owner, experienceId);
        return studioSessions.computeIfAbsent(key, ignored -> {
            var created = new ExperienceSession(
                    "studio-" + experienceId.value() + "-" + owner,
                    experienceId,
                    ExperienceSessionMode.STUDIO,
                    instanceFactory.apply(experienceId, ExperienceSessionMode.STUDIO),
                    Optional.of(owner),
                    VortexRuntimeTuning.studioSessionMaxPlayers()
            );
            sessionsByInstance.put(created.instance(), created);
            return created;
        });
    }

    Optional<ExperienceSession> byInstance(Instance instance) {
        return Optional.ofNullable(sessionsByInstance.get(instance));
    }

    List<ExperienceSession> all() {
        var sessions = new ArrayList<ExperienceSession>();
        sessions.addAll(publicSessions.values());
        sessions.addAll(studioSessions.values());
        return List.copyOf(sessions);
    }

    List<ExperienceSession> byExperience(ExperienceId experienceId) {
        return all().stream()
                .filter(session -> session.experienceId().equals(experienceId))
                .toList();
    }

    List<ExperienceSession> removeExperience(ExperienceId experienceId) {
        var removed = new ArrayList<ExperienceSession>();
        var publicSession = publicSessions.remove(experienceId);
        if (publicSession != null) {
            removed.add(publicSession);
        }
        for (Map.Entry<StudioKey, ExperienceSession> entry : List.copyOf(studioSessions.entrySet())) {
            if (entry.getKey().experienceId().equals(experienceId) && studioSessions.remove(entry.getKey(), entry.getValue())) {
                removed.add(entry.getValue());
            }
        }
        removed.forEach(session -> sessionsByInstance.remove(session.instance()));
        return List.copyOf(removed);
    }

    ExperienceSession recreatePublic(ExperienceId experienceId) {
        var old = publicSessions.remove(experienceId);
        if (old != null) {
            sessionsByInstance.remove(old.instance());
        }
        var created = new ExperienceSession(
                "public-" + experienceId.value() + "-1",
                experienceId,
                ExperienceSessionMode.PUBLIC,
                instanceFactory.apply(experienceId, ExperienceSessionMode.PUBLIC),
                Optional.empty(),
                VortexRuntimeTuning.publicSessionMaxPlayers()
        );
        publicSessions.put(experienceId, created);
        sessionsByInstance.put(created.instance(), created);
        return created;
    }

    private record StudioKey(UUID owner, ExperienceId experienceId) {
    }
}
