package org.assiscabron.vortexProxy.core.scripting;

import org.assiscabron.vortexProxy.api.ExperienceId;

import org.assiscabron.vortexProxy.api.ExperienceId;

import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.instance.block.Block;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.JsePlatform;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class LuaJLuauRuntime implements LuauRuntime {
    private static final Pattern COMPOUND_ASSIGN = Pattern.compile("^([\\t ]*)([A-Za-z_][A-Za-z0-9_\\.\\[\\]\"']*)\\s*([+\\-*/%])=\\s*(.+)$", Pattern.MULTILINE);

    private final Logger logger;
    private final org.assiscabron.vortexProxy.core.backend.MultiversePortalEngine portalEngine;

    public LuaJLuauRuntime(Logger logger, org.assiscabron.vortexProxy.core.backend.MultiversePortalEngine portalEngine) {
        this.logger = Objects.requireNonNull(logger, "logger");
        this.portalEngine = portalEngine;
    }

    @Override
    public void execute(LuauExecutionContext context) {
        try {
            var globals = JsePlatform.standardGlobals();
            var game = gameApi(context);
            globals.set("Game", game);
            globals.set("game", game);
            globals.set("workspace", game.get("World"));
            globals.set("script", scriptApi(context));
            installLuauCompat(globals, context);

            var source = LuauSourcePreprocessor.toLua51(context.source());
            globals.load(source, context.scriptName()).call();
            fireStartCallbacks(globals);
        } catch (LuaError exception) {
            throw new LuauRuntimeException("Luau runtime error: " + exception.getMessage(), exception);
        }
    }

    private void fireStartCallbacks(LuaValue globals) {
        var game = globals.get("Game");
        var events = game.get("Events");
        var startCallbacks = events.get("_startCallbacks");
        for (int index = 1; index <= startCallbacks.length(); index++) {
            startCallbacks.get(index).call();
        }
    }

    private LuaTable scriptApi(LuauExecutionContext context) {
        var script = new LuaTable();
        script.set("Name", context.script().getFileName().toString());
        script.set("SourcePath", context.scriptName());
        script.set("Parent", LuaValue.NIL);
        return script;
    }

    private LuaTable gameApi(LuauExecutionContext context) {
        var game = new LuaTable();
        var events = eventsApi();
        var world = worldApi(context.instance());
        var players = playersApi(context.instance(), context.contextPlayers());
        var chat = chatApi(context.instance(), context.contextPlayers());
        var runService = runServiceApi();
        var shards = new WorldApi().getApi();
        var ui = new UserInterfaceApi(this, context.instance(), context.contextPlayers()).getApi();

        game.set("ExperienceId", context.experienceId().value());
        game.set("Events", events);
        game.set("World", world);
        game.set("Workspace", world);
        game.set("Players", players);
        game.set("Chat", chat);
        game.set("RunService", runService);
        game.set("UI", ui);
        game.set("Shards", shards);
        game.set("GetService", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                var name = args.checkjstring(1 + offset);
                return switch (name.toLowerCase()) {
                    case "players" -> players;
                    case "workspace" -> world;
                    case "chat", "chatservice" -> chat;
                    case "runservice" -> runService;
                    case "ui", "uiservice" -> ui;
                    default -> throw new LuaError("Unknown service: " + name);
                };
            }
        });
        return game;
    }

    private void installLuauCompat(LuaValue globals, LuauExecutionContext context) {
        var wait = waitFunction();
        globals.set("wait", wait);
        globals.set("time", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(System.nanoTime() / 1_000_000_000.0);
            }
        });
        globals.set("tick", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(System.currentTimeMillis() / 1000.0);
            }
        });
        globals.set("elapsedTime", globals.get("time"));
        globals.set("typeof", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(typeof(args.arg(1)));
            }
        });
        globals.set("warn", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                logger.warn("[Luau warn] {}", joinArgs(args));
                return LuaValue.TRUE;
            }
        });
        globals.set("printidentity", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                logger.info("[Luau] Current identity is VortexScript ({})", context.experienceId().value());
                return LuaValue.TRUE;
            }
        });

        installTask(globals, wait);
        installVector3(globals);
        installColor3(globals);
        installCFrame(globals);
        patchTableLibrary(globals);
        patchStringLibrary(globals);
        patchMathLibrary(globals);
    }

    private void installTask(LuaValue globals, LuaValue wait) {
        var task = new LuaTable();
        task.set("wait", wait);
        task.set("spawn", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                args.checkfunction(1).invoke(args.subargs(2));
                return LuaValue.TRUE;
            }
        });
        task.set("defer", task.get("spawn"));
        task.set("delay", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                waitSeconds(args.checkdouble(1));
                args.checkfunction(2).invoke(args.subargs(3));
                return LuaValue.TRUE;
            }
        });
        task.set("desynchronize", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.TRUE;
            }
        });
        task.set("synchronize", task.get("desynchronize"));
        globals.set("task", task);
    }

    private void installVector3(LuaValue globals) {
        var vector3 = new LuaTable();
        vector3.set("new", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                return vector3Value(
                        args.optdouble(1 + offset, 0),
                        args.optdouble(2 + offset, 0),
                        args.optdouble(3 + offset, 0)
                );
            }
        });
        vector3.set("zero", vector3Value(0, 0, 0));
        vector3.set("one", vector3Value(1, 1, 1));
        vector3.set("xAxis", vector3Value(1, 0, 0));
        vector3.set("yAxis", vector3Value(0, 1, 0));
        vector3.set("zAxis", vector3Value(0, 0, 1));
        globals.set("Vector3", vector3);
    }

    private LuaTable vector3Value(double x, double y, double z) {
        var value = new LuaTable();
        value.set("X", x);
        value.set("Y", y);
        value.set("Z", z);
        value.set("x", x);
        value.set("y", y);
        value.set("z", z);
        value.set("Magnitude", Math.sqrt((x * x) + (y * y) + (z * z)));
        value.set("__type", "Vector3");
        return value;
    }

    private void installColor3(LuaValue globals) {
        var color3 = new LuaTable();
        color3.set("new", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                return color3Value(
                        args.optdouble(1 + offset, 0),
                        args.optdouble(2 + offset, 0),
                        args.optdouble(3 + offset, 0)
                );
            }
        });
        color3.set("fromRGB", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                return color3Value(
                        args.optdouble(1 + offset, 0) / 255.0,
                        args.optdouble(2 + offset, 0) / 255.0,
                        args.optdouble(3 + offset, 0) / 255.0
                );
            }
        });
        color3.set("fromHex", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                var hex = args.checkjstring(1 + offset).replace("#", "");
                if (hex.length() != 6) {
                    throw new LuaError("Color3.fromHex expects RRGGBB");
                }
                return color3Value(
                        Integer.parseInt(hex.substring(0, 2), 16) / 255.0,
                        Integer.parseInt(hex.substring(2, 4), 16) / 255.0,
                        Integer.parseInt(hex.substring(4, 6), 16) / 255.0
                );
            }
        });
        globals.set("Color3", color3);
    }

    private LuaTable color3Value(double r, double g, double b) {
        var value = new LuaTable();
        value.set("R", clamp01(r));
        value.set("G", clamp01(g));
        value.set("B", clamp01(b));
        value.set("__type", "Color3");
        return value;
    }

    private void installCFrame(LuaValue globals) {
        var cframe = new LuaTable();
        cframe.set("new", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                var value = new LuaTable();
                value.set("X", args.optdouble(1 + offset, 0));
                value.set("Y", args.optdouble(2 + offset, 0));
                value.set("Z", args.optdouble(3 + offset, 0));
                value.set("Position", vector3Value(value.get("X").todouble(), value.get("Y").todouble(), value.get("Z").todouble()));
                value.set("__type", "CFrame");
                return value;
            }
        });
        globals.set("CFrame", cframe);
    }

    private void patchTableLibrary(LuaValue globals) {
        var table = globals.get("table");
        table.set("clear", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var source = args.checktable(1);
                var keys = new java.util.ArrayList<LuaValue>();
                var key = LuaValue.NIL;
                while (true) {
                    var next = source.next(key);
                    key = next.arg1();
                    if (key.isnil()) {
                        break;
                    }
                    keys.add(key);
                }
                for (var existingKey : keys) {
                    source.set(existingKey, LuaValue.NIL);
                }
                return LuaValue.TRUE;
            }
        });
        table.set("clone", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var source = args.checktable(1);
                var clone = new LuaTable();
                var key = LuaValue.NIL;
                while (true) {
                    var next = source.next(key);
                    key = next.arg1();
                    if (key.isnil()) {
                        break;
                    }
                    clone.set(key, next.arg(2));
                }
                return clone;
            }
        });
        table.set("freeze", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return args.checktable(1);
            }
        });
        table.set("isfrozen", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.FALSE;
            }
        });
        table.set("find", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var source = args.checktable(1);
                var needle = args.arg(2);
                var start = args.optint(3, 1);
                for (int index = start; index <= source.length(); index++) {
                    if (source.get(index).eq_b(needle)) {
                        return LuaValue.valueOf(index);
                    }
                }
                return LuaValue.NIL;
            }
        });
        table.set("create", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var count = args.checkint(1);
                var value = args.arg(2);
                var created = new LuaTable();
                for (int index = 1; index <= count; index++) {
                    created.set(index, value);
                }
                return created;
            }
        });
    }

    private void patchStringLibrary(LuaValue globals) {
        var string = globals.get("string");
        string.set("split", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var source = args.checkjstring(1);
                var separator = args.checkjstring(2);
                var output = new LuaTable();
                var parts = source.split(Pattern.quote(separator), -1);
                for (int index = 0; index < parts.length; index++) {
                    output.set(index + 1, parts[index]);
                }
                return output;
            }
        });
    }

    private void patchMathLibrary(LuaValue globals) {
        var math = globals.get("math");
        math.set("clamp", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var value = args.checkdouble(1);
                var min = args.checkdouble(2);
                var max = args.checkdouble(3);
                return LuaValue.valueOf(Math.max(min, Math.min(max, value)));
            }
        });
        math.set("round", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(Math.floor(args.checkdouble(1) + 0.5));
            }
        });
        math.set("sign", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.valueOf(Double.compare(args.checkdouble(1), 0));
            }
        });
    }

    private LuaTable runServiceApi() {
        var service = new LuaTable();
        service.set("Heartbeat", signalApi("Heartbeat"));
        service.set("Stepped", signalApi("Stepped"));
        service.set("RenderStepped", signalApi("RenderStepped"));
        service.set("IsServer", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.TRUE;
            }
        });
        service.set("IsClient", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return LuaValue.FALSE;
            }
        });
        return service;
    }

    private LuaTable signalApi(String name) {
        var signal = new LuaTable();
        var callbacks = new LuaTable();
        signal.set("_callbacks", callbacks);
        signal.set("Connect", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var callback = args.arg(1).istable() ? args.arg(2) : args.arg(1);
                if (!callback.isfunction()) {
                    throw new LuaError(name + ":Connect expects a function");
                }
                callbacks.set(callbacks.length() + 1, callback);
                var connection = new LuaTable();
                connection.set("Connected", LuaValue.TRUE);
                connection.set("Disconnect", new VarArgFunction() {
                    @Override
                    public Varargs invoke(Varargs disconnectArgs) {
                        connection.set("Connected", LuaValue.FALSE);
                        return LuaValue.TRUE;
                    }
                });
                return connection;
            }
        });
        signal.set("Wait", waitFunction());
        return signal;
    }

    private LuaTable eventsApi() {
        var events = new LuaTable();
        
        // Start Event
        var startSignal = signalApi("OnStart");
        events.set("OnStart", startSignal);
        // OnStart is triggered manually by the runtime after loading
        events.set("_startCallbacks", startSignal.get("_callbacks"));

        // Player Join
        var joinSignal = signalApi("OnPlayerJoin");
        events.set("OnPlayerJoin", joinSignal);
        net.minestom.server.MinecraftServer.getGlobalEventHandler().addListener(net.minestom.server.event.player.PlayerSpawnEvent.class, event -> {
            if (!event.isFirstSpawn()) return;
            var callbacks = joinSignal.get("_callbacks");
            for (int i = 1; i <= callbacks.length(); i++) {
                callbacks.get(i).call(playerApi(event.getPlayer()));
            }
        });

        // Player Leave
        var leaveSignal = signalApi("OnPlayerLeave");
        events.set("OnPlayerLeave", leaveSignal);
        net.minestom.server.MinecraftServer.getGlobalEventHandler().addListener(net.minestom.server.event.player.PlayerDisconnectEvent.class, event -> {
            var callbacks = leaveSignal.get("_callbacks");
            for (int i = 1; i <= callbacks.length(); i++) {
                callbacks.get(i).call(playerApi(event.getPlayer()));
            }
        });

        return events;
    }

    private LuaTable worldApi(InstanceContainer instance) {
        var world = new LuaTable();
        world.set("SetBlock", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                var blockName = args.checkjstring(1 + offset);
                var x = args.checkint(2 + offset);
                var y = args.checkint(3 + offset);
                var z = args.checkint(4 + offset);
                var block = block(blockName);
                instance.setBlock(x, y, z, block);
                return LuaValue.TRUE;
            }
        });
        world.set("Fill", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                var block = block(args.checkjstring(1 + offset));
                var minX = Math.min(args.checkint(2 + offset), args.checkint(5 + offset));
                var minY = Math.min(args.checkint(3 + offset), args.checkint(6 + offset));
                var minZ = Math.min(args.checkint(4 + offset), args.checkint(7 + offset));
                var maxX = Math.max(args.checkint(2 + offset), args.checkint(5 + offset));
                var maxY = Math.max(args.checkint(3 + offset), args.checkint(6 + offset));
                var maxZ = Math.max(args.checkint(4 + offset), args.checkint(7 + offset));
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            instance.setBlock(x, y, z, block);
                        }
                    }
                }
                return LuaValue.TRUE;
            }
        });
        world.set("SpawnPoint", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                return vector3Value(0.5, 66.0, 0.5);
            }
        });
        world.set("CreatePortal", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                LuaTable config = args.checktable(1);
                Pos sourcePos = new Pos(
                        config.get("Position").get("X").checkdouble(),
                        config.get("Position").get("Y").checkdouble(),
                        config.get("Position").get("Z").checkdouble()
                );
                
                // Destination instance is usually passed as a Handle or ID
                // For now, let's assume it's another Instance userdata if available
                net.minestom.server.instance.Instance targetInstance = (net.minestom.server.instance.Instance) config.get("Destination").get("_handle").touserdata(net.minestom.server.instance.Instance.class);
                Pos destPos = new Pos(
                        config.get("Destination").get("Position").get("X").checkdouble(),
                        config.get("Destination").get("Position").get("Y").checkdouble(),
                        config.get("Destination").get("Position").get("Z").checkdouble()
                );

                portalEngine.registerPortal(new org.assiscabron.vortexProxy.core.backend.MultiversePortalEngine.Portal(
                        instance,
                        sourcePos,
                        targetInstance,
                        destPos,
                        config.get("Size").get("Width").optdouble(2.0),
                        config.get("Size").get("Height").optdouble(3.0),
                        config.get("Size").get("Depth").optdouble(0.5),
                        java.util.Collections.emptySet()
                ));
                return LuaValue.TRUE;
            }
        });
        world.set("_handle", LuaValue.userdataOf(instance));
        return world;
    }

    private LuaTable playersApi(InstanceContainer instance, Collection<Player> contextPlayers) {
        var players = new LuaTable();
        players.set("GetAll", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var values = new LuaTable();
                var index = 1;
                for (var player : visiblePlayers(instance, contextPlayers)) {
                    values.set(index++, playerApi(player));
                }
                return values;
            }
        });
        players.set("GetPlayers", players.get("GetAll"));
        return players;
    }

    LuaTable playerApi(Player player) {
        var api = new LuaTable();
        api.set("_handle", LuaValue.userdataOf(player));
        api.set("Name", player.getUsername());
        api.set("DisplayName", player.getUsername());
        api.set("UserId", player.getUuid().hashCode());
        api.set("SendMessage", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var message = args.arg(1).istable() ? args.checkjstring(2) : args.checkjstring(1);
                sendChat(player, message);
                return LuaValue.TRUE;
            }
        });
        api.set("ClearChat", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                clearChat(player);
                return LuaValue.TRUE;
            }
        });
        api.set("Teleport", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() && !isPlayerApi(args.arg(1)) ? 1 : 0;
                var first = args.arg(1 + offset);
                if (first.istable() && !first.get("X").isnil()) {
                    player.teleport(new Pos(first.get("X").todouble(), first.get("Y").todouble(), first.get("Z").todouble()));
                    return LuaValue.TRUE;
                }
                player.teleport(new Pos(
                        args.checkdouble(1 + offset),
                        args.checkdouble(2 + offset),
                        args.checkdouble(3 + offset)
                ));
                return LuaValue.TRUE;
            }
        });
        api.set("SetInstance", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() && !isPlayerApi(args.arg(1)) ? 1 : 0;
                var instanceObj = args.checktable(1 + offset);
                var instance = (net.minestom.server.instance.Instance) instanceObj.get("_handle").checkuserdata(net.minestom.server.instance.Instance.class);
                
                var pos = new Pos(0.5, 66.0, 0.5);
                if (args.narg() >= 2 + offset) {
                    var posTable = args.checktable(2 + offset);
                    pos = new Pos(posTable.get("X").todouble(), posTable.get("Y").todouble(), posTable.get("Z").todouble());
                }
                
                player.setInstance(instance, pos);
                return LuaValue.TRUE;
            }
        });
        return api;
    }

    private LuaTable chatApi(InstanceContainer instance, Collection<Player> contextPlayers) {
        var chat = new LuaTable();
        chat.set("Send", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() && !isPlayerApi(args.arg(1)) ? 1 : 0;
                sendChat(playerFromLua(args.arg(1 + offset)), args.checkjstring(2 + offset));
                return LuaValue.TRUE;
            }
        });
        chat.set("Broadcast", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                var message = args.checkjstring(1 + offset);
                for (var player : visiblePlayers(instance, contextPlayers)) {
                    sendChat(player, message);
                }
                return LuaValue.TRUE;
            }
        });
        chat.set("Clear", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() && !isPlayerApi(args.arg(1)) ? 1 : 0;
                clearChat(playerFromLua(args.arg(1 + offset)));
                return LuaValue.TRUE;
            }
        });
        chat.set("ClearAll", new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                for (var player : visiblePlayers(instance, contextPlayers)) {
                    clearChat(player);
                }
                return LuaValue.TRUE;
            }
        });
        return chat;
    }

    private VarArgFunction waitFunction() {
        return new VarArgFunction() {
            @Override
            public Varargs invoke(Varargs args) {
                var offset = args.arg(1).istable() ? 1 : 0;
                var seconds = args.optdouble(1 + offset, 0.03);
                var started = System.nanoTime();
                waitSeconds(seconds);
                return LuaValue.valueOf((System.nanoTime() - started) / 1_000_000_000.0);
            }
        };
    }

    private void waitSeconds(double seconds) {
        var boundedSeconds = Math.max(0, Math.min(seconds, 10.0));
        try {
            TimeUnit.NANOSECONDS.sleep((long) (boundedSeconds * 1_000_000_000L));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new LuaError("wait interrupted");
        }
    }

    private List<Player> visiblePlayers(InstanceContainer instance, Collection<Player> contextPlayers) {
        var players = new LinkedHashMap<java.util.UUID, Player>();
        for (var player : instance.getPlayers()) {
            players.put(player.getUuid(), player);
        }
        for (var player : contextPlayers) {
            players.put(player.getUuid(), player);
        }
        return List.copyOf(players.values());
    }

    private boolean isPlayerApi(LuaValue value) {
        return value.istable() && !value.get("_handle").isnil();
    }

    private Player playerFromLua(LuaValue value) {
        if (!isPlayerApi(value)) {
            throw new LuaError("Expected a player returned by Game.Players:GetAll()");
        }
        var handle = value.get("_handle").touserdata(Player.class);
        if (handle == null) {
            throw new LuaError("Invalid player handle");
        }
        return (Player) handle;
    }

    private void sendChat(Player player, String message) {
        player.sendMessage(Component.text(message));
    }

    private void clearChat(Player player) {
        for (int line = 0; line < 100; line++) {
            player.sendMessage(Component.empty());
        }
    }

    private Block block(String blockName) {
        var block = Block.fromNamespaceId(normalizeBlock(blockName));
        if (block == null || block == Block.AIR) {
            throw new LuaError("Unknown block: " + blockName);
        }
        return block;
    }

    private String normalizeBlock(String blockName) {
        var normalized = blockName.toLowerCase().replace(' ', '_');
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    private double clamp01(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private String typeof(LuaValue value) {
        if (value.istable()) {
            var customType = value.get("__type");
            return customType.isnil() ? "table" : customType.tojstring();
        }
        if (value.isfunction()) return "function";
        if (value.isnumber()) return "number";
        if (value.isboolean()) return "boolean";
        if (value.isstring()) return "string";
        if (value.isnil()) return "nil";
        if (value.isuserdata()) return "userdata";
        return value.typename();
    }

    private String joinArgs(Varargs args) {
        var builder = new StringBuilder();
        for (int index = 1; index <= args.narg(); index++) {
            if (index > 1) {
                builder.append('\t');
            }
            builder.append(args.arg(index).tojstring());
        }
        return builder.toString();
    }
}
