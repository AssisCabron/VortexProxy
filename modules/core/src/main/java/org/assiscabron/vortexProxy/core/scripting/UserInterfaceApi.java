package org.assiscabron.vortexProxy.core.scripting;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.minestom.server.color.Color;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.EntityType;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta;
import net.minestom.server.entity.metadata.display.TextDisplayMeta;
import net.minestom.server.entity.metadata.other.InteractionMeta;
import net.minestom.server.instance.InstanceContainer;
import net.minestom.server.timer.TaskSchedule;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class UserInterfaceApi {
    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(UserInterfaceApi.class);
    private final InstanceContainer instance;
    private final Map<String, UiElement> elements = new ConcurrentHashMap<>();

    public UserInterfaceApi(InstanceContainer instance, Collection<Player> contextPlayers) {
        this.instance = instance;
        
        // Tick task for pinned elements
        instance.scheduler().submitTask(() -> {
            for (UiElement element : elements.values()) {
                if (element.pinnedPlayer != null && element.pinnedPlayer.isOnline()) {
                    updatePinnedPosition(element);
                }
            }
            return TaskSchedule.tick(1);
        });

        // Global interaction listener for UI buttons
        net.minestom.server.MinecraftServer.getGlobalEventHandler().addListener(net.minestom.server.event.player.PlayerEntityInteractEvent.class, event -> {
            handleInteraction(event.getPlayer(), event.getTarget().getEntityId());
        });
    }

    private void updatePinnedPosition(UiElement element) {
        Player player = element.pinnedPlayer;
        Vec offset = element.pinOffset;
        
        Pos playerPos = player.getPosition();
        float yaw = (float) Math.toRadians(playerPos.yaw());
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        
        double worldX = offset.x() * cosYaw + offset.z() * sinYaw;
        double worldZ = -offset.x() * sinYaw + offset.z() * cosYaw;
        double worldY = offset.y();
        
        Pos newPos = playerPos.add(worldX, worldY, worldZ);
        
        if (newPos.distanceSquared(element.entity.getPosition()) > 0.0001) {
            element.entity.teleport(newPos);
            if (element.interactionEntity != null) {
                element.interactionEntity.teleport(newPos);
            }
        }
    }

    public LuaTable getApi() {
        var ui = new LuaTable();
        ui.set("CreateElement", new CreateElementFunction());
        ui.set("UpdateElement", new UpdateElementFunction());
        ui.set("RemoveElement", new RemoveElementFunction());
        ui.set("PinToPlayer", new PinToPlayerFunction());
        ui.set("SetVisibleTo", new SetVisibleToFunction());
        ui.set("OnClick", new OnClickFunction());
        return ui;
    }

    private static class UiElement {
        final Entity entity;
        Entity interactionEntity;
        Player pinnedPlayer;
        Vec pinOffset;
        Player privateViewer;
        LuaValue clickCallback;

        UiElement(Entity entity) {
            this.entity = entity;
        }
    }

    private final class CreateElementFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String name = args.checkjstring(1);
            String text = args.checkjstring(2);
            LuaTable posTable = args.checktable(3);

            Pos position = new Pos(
                    posTable.get("X").optdouble(0),
                    posTable.get("Y").optdouble(0),
                    posTable.get("Z").optdouble(0)
            );

            if (elements.containsKey(name)) {
                throw new LuaError("UI element already exists: " + name);
            }

            Entity entity = new Entity(EntityType.TEXT_DISPLAY);
            TextDisplayMeta meta = (TextDisplayMeta) entity.getEntityMeta();
            meta.setText(parseText(text));
            meta.setHasNoGravity(true);
            meta.setBackgroundColor(0); 
            meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);
            meta.setShadow(true);
            meta.setTransformationInterpolationDuration(0);
            meta.setPosRotInterpolationDuration(0);

            entity.setInstance(instance, position);
            elements.put(name, new UiElement(entity));
            logger.info("Vortex Studio: Created UI element '{}' at {}", name, position);

            return LuaValue.TRUE;
        }
    }

    private final class UpdateElementFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String name = args.checkjstring(1);
            LuaTable props = args.checktable(2);

            UiElement element = elements.get(name);
            if (element == null) return LuaValue.FALSE;

            TextDisplayMeta meta = (TextDisplayMeta) element.entity.getEntityMeta();

            if (!props.get("TransitionTime").isnil()) {
                int ticks = (int) (props.get("TransitionTime").todouble() * 20);
                meta.setTransformationInterpolationDuration(ticks);
                meta.setPosRotInterpolationDuration(ticks);
                meta.setTransformationInterpolationStartDelta(0);
            }

            if (!props.get("Text").isnil()) {
                meta.setText(parseText(props.get("Text").tojstring()));
            }

            if (!props.get("Position").isnil()) {
                LuaTable posTable = props.get("Position").checktable();
                Pos newPos = new Pos(
                        posTable.get("X").optdouble(element.entity.getPosition().x()),
                        posTable.get("Y").optdouble(element.entity.getPosition().y()),
                        posTable.get("Z").optdouble(element.entity.getPosition().z())
                );
                element.entity.teleport(newPos);
                if (element.interactionEntity != null) {
                    element.interactionEntity.teleport(newPos);
                }
            }

            if (!props.get("Scale").isnil()) {
                float scale = (float) props.get("Scale").todouble();
                meta.setScale(new Vec(scale, scale, scale));
            }

            if (!props.get("BackgroundColor").isnil()) {
                String hex = props.get("BackgroundColor").tojstring().replace("#", "");
                int color = Integer.parseInt(hex, 16);
                // If it's a 6-digit hex, add full opacity
                if (hex.length() == 6) color |= 0xFF000000;
                meta.setBackgroundColor(color);
            }

            if (!props.get("Alignment").isnil()) {
                String align = props.get("Alignment").tojstring().toLowerCase();
                switch (align) {
                    case "left" -> meta.setAlignment(TextDisplayMeta.Alignment.LEFT);
                    case "right" -> meta.setAlignment(TextDisplayMeta.Alignment.RIGHT);
                    default -> meta.setAlignment(TextDisplayMeta.Alignment.CENTER);
                }
            }

            if (!props.get("LineWidth").isnil()) {
                meta.setLineWidth(props.get("LineWidth").checkint());
            }

            return LuaValue.TRUE;
        }
    }

    private final class RemoveElementFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String name = args.checkjstring(1);
            UiElement element = elements.remove(name);
            if (element != null) {
                element.entity.remove();
                if (element.interactionEntity != null) {
                    element.interactionEntity.remove();
                }
            }
            return LuaValue.TRUE;
        }
    }

    private final class PinToPlayerFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String name = args.checkjstring(1);
            LuaValue playerVal = args.arg(2);
            LuaTable offsetTable = args.checktable(3);

            UiElement element = elements.get(name);
            if (element == null) return LuaValue.FALSE;

            if (playerVal.isnil()) {
                element.pinnedPlayer = null;
                return LuaValue.TRUE;
            }

            element.pinnedPlayer = playerFromLua(playerVal);
            element.pinOffset = new Vec(
                    offsetTable.get("X").optdouble(0),
                    offsetTable.get("Y").optdouble(0),
                    offsetTable.get("Z").optdouble(0)
            );

            TextDisplayMeta meta = (TextDisplayMeta) element.entity.getEntityMeta();
            meta.setBillboardRenderConstraints(AbstractDisplayMeta.BillboardConstraints.CENTER);

            return LuaValue.TRUE;
        }
    }

    private final class SetVisibleToFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String name = args.checkjstring(1);
            LuaValue playerVal = args.arg(2); // NIL = Everyone, PlayerObj = Only that player

            UiElement element = elements.get(name);
            if (element == null) return LuaValue.FALSE;

            if (playerVal.isnil()) {
                element.privateViewer = null;
                element.entity.updateViewableRule(player -> true);
                if (element.interactionEntity != null) element.interactionEntity.updateViewableRule(player -> true);
            } else {
                Player viewer = playerFromLua(playerVal);
                element.privateViewer = viewer;
                element.entity.updateViewableRule(player -> player.getUuid().equals(viewer.getUuid()));
                if (element.interactionEntity != null) {
                    element.interactionEntity.updateViewableRule(player -> player.getUuid().equals(viewer.getUuid()));
                }
            }
            return LuaValue.TRUE;
        }
    }

    private final class OnClickFunction extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            String name = args.checkjstring(1);
            LuaValue callback = args.arg(2);

            UiElement element = elements.get(name);
            if (element == null) return LuaValue.FALSE;

            element.clickCallback = callback;
            
            if (element.interactionEntity == null) {
                Entity interact = new Entity(EntityType.INTERACTION);
                InteractionMeta meta = (InteractionMeta) interact.getEntityMeta();
                meta.setHeight(0.5f);
                meta.setWidth(0.5f);
                
                interact.setInstance(instance, element.entity.getPosition());
                element.interactionEntity = interact;
                
                // Set same viewable rule as text
                if (element.privateViewer != null) {
                    interact.updateViewableRule(player -> player.getUuid().equals(element.privateViewer.getUuid()));
                }
            }
            
            return LuaValue.TRUE;
        }
    }

    public void handleInteraction(Player player, int entityId) {
        for (UiElement element : elements.values()) {
            if (element.interactionEntity != null && element.interactionEntity.getEntityId() == entityId) {
                if (element.clickCallback != null && element.clickCallback.isfunction()) {
                    element.clickCallback.call(playerApi(player));
                }
                return;
            }
        }
    }

    private Player playerFromLua(LuaValue value) {
        LuaValue handle = value.get("_handle");
        if (handle.isnil()) throw new LuaError("Invalid player object");
        return (Player) handle.touserdata(Player.class);
    }
    
    // Stub for now, should map back to existing Lua player API
    private LuaValue playerApi(Player player) {
        LuaTable api = new LuaTable();
        api.set("_handle", LuaValue.userdataOf(player));
        api.set("Name", player.getUsername());
        return api;
    }

    private Component parseText(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
