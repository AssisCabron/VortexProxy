package org.assiscabron.vortexProxy.core.scripting;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.assiscabron.vortexProxy.api.ExperienceId;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class WorldApi {
    private final Map<Integer, InstanceContainer> dynamicInstances = new ConcurrentHashMap<>();
    private final AtomicInteger idGenerator = new AtomicInteger(100);

    public WorldApi() {
    }

    public LuaTable getApi() {
        var world = new LuaTable();
        world.set("CreateInstance", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                String type = args.arg(1).optjstring("FLAT").toUpperCase();
                var instance = MinecraftServer.getInstanceManager().createInstanceContainer();
                instance.setChunkSupplier(net.minestom.server.instance.LightingChunk::new);
                instance.setTime(6000);
                instance.setTimeRate(0);
                instance.setWeather(net.minestom.server.instance.Weather.CLEAR, 1);
                
                if (type.equals("NATURAL")) {
                    instance.setGenerator(new org.assiscabron.vortexProxy.core.backend.VortexWorldGenerator());
                } else {
                    instance.setGenerator(unit -> {
                        if (unit.absoluteStart().blockY() < 64) {
                            unit.modifier().fillHeight(0, 63, Block.STONE);
                            unit.modifier().fillHeight(63, 64, Block.GRASS_BLOCK);
                        }
                    });
                }

                int id = idGenerator.incrementAndGet();
                dynamicInstances.put(id, instance);

                // Enforce chunk rendering texturization for seamless world exploring
                instance.eventNode().addListener(net.minestom.server.event.instance.InstanceChunkLoadEvent.class, event -> {
                    net.minestom.server.MinecraftServer.getSchedulerManager().buildTask(() -> {
                        var chunk = event.getChunk();
                        for (var p : instance.getPlayers()) {
                            chunk.sendChunk(p);
                        }
                    }).delay(100, java.time.temporal.ChronoUnit.MILLIS).schedule();
                });

                // Pre-load chunks around spawn
                for (int cx = -8; cx <= 8; cx++) {
                    for (int cz = -8; cz <= 8; cz++) {
                        instance.loadChunk(cx, cz);
                    }
                }

                return createInstanceObject(id, instance);
            }
        });
        return world;
    }

    private LuaTable createInstanceObject(int id, InstanceContainer instance) {
        var obj = new LuaTable();
        obj.set("_id", LuaValue.valueOf(id));
        obj.set("_handle", LuaValue.userdataOf(instance));

        obj.set("SetBlock", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                int x = args.checkint(2);
                int y = args.checkint(3);
                int z = args.checkint(4);
                String blockName = args.checkjstring(5);
                var block = Block.fromNamespaceId(blockName.contains(":") ? blockName : "minecraft:" + blockName);
                if (block != null) {
                    instance.setBlock(x, y, z, block);
                }
                return LuaValue.TRUE;
            }
        });

        obj.set("Destroy", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                dynamicInstances.remove(id);
                // Teleport players out before destroying
                instance.getPlayers().forEach(p -> p.setInstance(MinecraftServer.getInstanceManager().getInstances().iterator().next(), new Pos(0, 100, 0)));
                MinecraftServer.getInstanceManager().unregisterInstance(instance);
                return LuaValue.TRUE;
            }
        });

        return obj;
    }
}
