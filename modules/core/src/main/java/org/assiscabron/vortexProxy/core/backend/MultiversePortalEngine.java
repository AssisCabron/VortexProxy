package org.assiscabron.vortexProxy.core.backend;

import net.kyori.adventure.text.Component;
import net.minestom.server.coordinate.BlockVec;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Entity;
import net.minestom.server.entity.Player;
import net.minestom.server.instance.Instance;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.server.play.BlockChangePacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.SpawnEntityPacket;
import org.slf4j.Logger;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MultiversePortalEngine {
    private final Logger logger;
    private final java.util.List<Portal> portals = new CopyOnWriteArrayList<>();
    private final Map<UUID, Long> teleportCooldown = new ConcurrentHashMap<>();
    private final Map<UUID, Set<Integer>> trackedFakeEntities = new ConcurrentHashMap<>();
    private final Map<UUID, Set<BlockVec>> revealedBlocks = new ConcurrentHashMap<>();
    private final Map<UUID, Map<BlockVec, Block>> virtualEnvironmentCache = new ConcurrentHashMap<>();

    public MultiversePortalEngine(Logger logger) {
        this.logger = logger;
    }

    public void registerPortal(Portal portal) {
        portals.add(portal);
        if (portal.destInstance() != null) {
            portal.destInstance().loadChunk(portal.destPos());
        }
        logger.info("Seamless Portal registered: Source {} -> Dest {}", portal.sourcePos(), portal.destPos());
    }

    public void checkTeleports(Player player) {
        if (teleportCooldown.getOrDefault(player.getUuid(), 0L) > System.currentTimeMillis()) {
            return;
        }

        for (Portal portal : portals) {
            Instance source = portal.sourceInstance();
            if (source == null || !source.equals(player.getInstance())) continue;

            if (isWithinPortal(player.getPosition(), portal)) {
                teleportSeamlessly(player, portal);
                break;
            }
        }
    }

    private void checkTeleportsInternal(Portal portal) {
        Instance source = portal.sourceInstance();
        if (source == null) return;

        for (Player player : source.getPlayers()) {
            checkTeleports(player);
        }
    }

    public void tick() {
        for (Portal portal : portals) {
            checkTeleportsInternal(portal);
            updateVision(portal);
            handleBlockReveal(portal);
        }
    }

    private void handleBlockReveal(Portal portal) {
        Instance source = portal.sourceInstance();
        if (source == null) return;

        for (Player player : source.getPlayers()) {
            double dx = player.getPosition().x() - portal.sourcePos().x();
            double dz = player.getPosition().z() - portal.sourcePos().z();
            double distSq = dx * dx + dz * dz;
            
            Set<BlockVec> revealed = revealedBlocks.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet());
            Map<BlockVec, Block> cache = virtualEnvironmentCache.computeIfAbsent(player.getUuid(), k -> new ConcurrentHashMap<>());

            if (distSq < 10.0 * 10.0) {
                for (BlockVec b : portal.wallBlocks()) {
                    if (!revealed.contains(b)) {
                        player.sendPacket(new BlockChangePacket(b, Block.AIR));
                        revealed.add(b);
                    }
                }
            } else if (distSq > 12.0 * 12.0) {
                for (BlockVec b : portal.wallBlocks()) {
                    if (revealed.contains(b)) {
                        player.sendPacket(new BlockChangePacket(b, source.getBlock(b)));
                        revealed.remove(b);
                    }
                }
                // Clean up virtual blocks
                for (BlockVec b : cache.keySet()) {
                    player.sendPacket(new BlockChangePacket(b, source.getBlock(b)));
                }
                cache.clear();
            }
        }
    }


    private boolean isWithinPortal(Pos pos, Portal portal) {
        double dx = Math.abs(pos.x() - portal.sourcePos().x());
        double dy = pos.y() - portal.sourcePos().y();
        
        // Z detection: The portal surface is at Z = -7.0.
        // We trigger ONLY when the player has physically 'stepped through' the wall
        // touching the first generated block at Z = -8.1.
        double dz = pos.z() - portal.sourcePos().z();
        
        // Trigger precisely when stepping onto the first virtual block (-1.1 relative to -7.0)
        boolean withinZ = dz <= -1.1 && dz >= -2.0;
        boolean withinX = dx < portal.width() / 2.0;
        boolean withinY = dy >= -1 && dy < portal.height();
        
        return withinX && withinY && withinZ;
    }

    private void updateVision(Portal portal) {
        Instance source = portal.sourceInstance();
        Instance dest = portal.destInstance();
        if (source == null || dest == null) return;

        for (Player viewer : source.getPlayers()) {
            double dx = viewer.getPosition().x() - portal.sourcePos().x();
            double dz = viewer.getPosition().z() - portal.sourcePos().z();
            double distSq = dx * dx + dz * dz;

            if (distSq < 20 * 20) { 
                dest.loadChunk(portal.destPos());
                mirrorEntities(viewer, portal);
                mirrorEnvironment(viewer, portal, distSq < 12 * 12);
            }
        }
    }

    private void mirrorEntities(Player viewer, Portal portal) {
        Pos sourceCenter = portal.sourcePos();
        Pos destCenter = portal.destPos();
        var tracked = trackedFakeEntities.computeIfAbsent(viewer.getUuid(), k -> ConcurrentHashMap.newKeySet());

        for (Entity entity : portal.destInstance().getEntities()) {
            if (entity instanceof Player p && p.getUuid().equals(viewer.getUuid())) continue;
            
            Vec relative = entity.getPosition().asVec().sub(destCenter.asVec());
            Pos targetPos = sourceCenter.add(relative);

            if (relative.lengthSquared() > 25 * 25) continue;

            if (!tracked.contains(entity.getEntityId())) {
                SpawnEntityPacket spawn = new SpawnEntityPacket(
                        entity.getEntityId(), entity.getUuid(), entity.getEntityType().id(),
                        targetPos, targetPos.yaw(), 0, (short) 0, (short) 0, (short) 0
                );
                viewer.sendPacket(spawn);
                tracked.add(entity.getEntityId());
            } else {
                EntityTeleportPacket teleport = new EntityTeleportPacket(
                        entity.getEntityId(), targetPos, Vec.ZERO, 0, true
                );
                viewer.sendPacket(teleport);
            }
        }
    }

    private void mirrorEnvironment(Player viewer, Portal portal, boolean closeRange) {
        Instance dest = portal.destInstance();
        Pos destCenter = portal.destPos();
        Pos sourceCenter = portal.sourcePos();
        
        Map<BlockVec, Block> cache = virtualEnvironmentCache.computeIfAbsent(viewer.getUuid(), k -> new ConcurrentHashMap<>());

        // LARGE AREA MIRRORING
        // Broad range: X: -16 to 16, Y: -8 to 8, Z: -1 to -32
        // We only mirror a subset per tick if we are out of range to avoid lag
        int hRange = closeRange ? 16 : 8;
        int dRange = closeRange ? 32 : 12;
        int yRange = closeRange ? 8 : 4;

        for (int x = -hRange; x <= hRange; x++) {
            for (int z = -1; z >= -dRange; z--) {
                for (int y = -yRange; y <= yRange; y++) {
                    BlockVec sourceBlockPos = new BlockVec(
                        (int)sourceCenter.x() + x, (int)sourceCenter.y() + y, (int)sourceCenter.z() + z
                    );
                    BlockVec destBlockPos = new BlockVec(
                        (int)destCenter.x() + x, (int)destCenter.y() + y, (int)destCenter.z() + z
                    );

                    if (!dest.isChunkLoaded(destBlockPos.chunkX(), destBlockPos.chunkZ())) continue;

                    Block block = dest.getBlock(destBlockPos);
                    Block cached = cache.get(sourceBlockPos);

                    if (block != cached) {
                        if (block.isAir()) {
                             if (cached != null) {
                                 viewer.sendPacket(new BlockChangePacket(sourceBlockPos, Block.AIR));
                                 cache.remove(sourceBlockPos);
                             }
                        } else {
                             viewer.sendPacket(new BlockChangePacket(sourceBlockPos, block));
                             cache.put(sourceBlockPos, block);
                        }
                    }
                }
            }
        }
    }

    private void teleportSeamlessly(Player player, Portal portal) {
        teleportCooldown.put(player.getUuid(), System.currentTimeMillis() + 1000);
        
        // Capture current state for fluid transition
        Vec velocity = player.getVelocity();
        Pos currentPos = player.getPosition();
        
        // Effects to mask the transition
        player.sendPacket(new net.minestom.server.network.packet.server.play.ParticlePacket(
            net.minestom.server.particle.Particle.PORTAL,
            currentPos.x(), currentPos.y() + 1, currentPos.z(),
            0.5f, 0.5f, 0.5f, 0.1f, 100
        ));
        player.playSound(net.kyori.adventure.sound.Sound.sound(
            net.minestom.server.sound.SoundEvent.BLOCK_PORTAL_TRAVEL,
            net.kyori.adventure.sound.Sound.Source.BLOCK,
            0.5f, 1.2f
        ));

        // Instant Handoff
        player.setInstance(portal.destInstance(), portal.destPos().withView(currentPos.yaw(), currentPos.pitch())).thenRun(() -> {
            // Re-apply velocity in the new world so they keep moving
            player.setVelocity(velocity);
            
            // Final "Arrival" sound
            player.playSound(net.kyori.adventure.sound.Sound.sound(
                net.minestom.server.sound.SoundEvent.ENTITY_PLAYER_LEVELUP,
                net.kyori.adventure.sound.Sound.Source.PLAYER,
                0.3f, 2.0f
            ));
            
            trackedFakeEntities.remove(player.getUuid());
            revealedBlocks.remove(player.getUuid());
            virtualEnvironmentCache.remove(player.getUuid());
            
            logger.info("Player {} handoff complete.", player.getUsername());
        });
    }

    public record Portal(
            Instance sourceInstance,
            Pos sourcePos,
            Instance destInstance,
            Pos destPos,
            double width,
            double height,
            double depth,
            Set<BlockVec> wallBlocks
    ) {}
}
