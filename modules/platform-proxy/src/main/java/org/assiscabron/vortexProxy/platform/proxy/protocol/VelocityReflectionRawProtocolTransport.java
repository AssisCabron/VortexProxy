package org.assiscabron.vortexProxy.platform.proxy.protocol;

import com.velocitypowered.api.proxy.Player;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

public final class VelocityReflectionRawProtocolTransport implements RawProtocolTransport {
    private final Logger logger;

    public VelocityReflectionRawProtocolTransport(Logger logger) {
        this.logger = logger;
    }

    @Override
    public boolean isAvailable(Player player) {
        return findConnection(player).isPresent();
    }

    @Override
    public Set<ProtocolCapability> capabilities(Player player) {
        if (!isAvailable(player)) {
            return Set.of();
        }
        return Set.of(ProtocolCapability.INTERNAL_CONNECTION_ACCESS);
    }

    @Override
    public ProtocolWriteResult write(Player player, RawProtocolPacket packet) {
        var connection = findConnection(player);
        if (connection.isEmpty()) {
            return ProtocolWriteResult.skipped("Velocity internal connection was not found.");
        }

        logger.debug("Raw packet '{}' prepared for {}, but no Velocity packet object encoder is registered yet.",
                packet.name(), player.getUsername());
        return ProtocolWriteResult.skipped("Raw packet object encoder is not registered for " + packet.name() + ".");
    }

    private Optional<Object> findConnection(Player player) {
        return invokeNoArg(player, "getMinecraftConnection")
                .or(() -> invokeNoArg(player, "getConnection"));
    }

    private Optional<Object> invokeNoArg(Object target, String methodName) {
        try {
            Method method = findNoArgMethod(target.getClass(), methodName);
            method.setAccessible(true);
            return Optional.ofNullable(method.invoke(target));
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Method findNoArgMethod(Class<?> type, String methodName) throws NoSuchMethodException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName);
            } catch (NoSuchMethodException ignored) {
                current = current.getSuperclass();
            }
        }
        return type.getMethod(methodName);
    }
}
