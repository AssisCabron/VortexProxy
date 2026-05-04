package org.assiscabron.vortexProxy.platform.backend;

import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.tcp.TcpClientSession;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class BotConnectionBenchmark {

    public static void main(String[] args) throws InterruptedException {
        String host = "127.0.0.1";
        int port = 25565;
        int bots = 100;
        int rampPerSecond = 25;
        int holdSeconds = 30;
        String prefix = "VortexBot";

        for (String arg : args) {
            if (arg.startsWith("--host=")) host = arg.substring(7);
            if (arg.startsWith("--port=")) port = Integer.parseInt(arg.substring(7));
            if (arg.startsWith("--bots=")) bots = Integer.parseInt(arg.substring(7));
            if (arg.startsWith("--rampPerSecond=")) rampPerSecond = Integer.parseInt(arg.substring(16));
            if (arg.startsWith("--holdSeconds=")) holdSeconds = Integer.parseInt(arg.substring(14));
            if (arg.startsWith("--namePrefix=")) prefix = arg.substring(13);
        }

        System.out.printf("Starting Bot Benchmark to %s:%d (Bots: %d, Ramp: %d/s, Hold: %ds)%n",
                host, port, bots, rampPerSecond, holdSeconds);

        List<Session> sessions = new ArrayList<>();
        AtomicInteger connected = new AtomicInteger();
        long delayBetweenBotsMs = 1000 / rampPerSecond;

        for (int i = 0; i < bots; i++) {
            String name = prefix + i;
            if (name.length() > 16) {
                name = name.substring(0, 16);
            }
            final String finalName = name;

            MinecraftProtocol protocol = new MinecraftProtocol(finalName);
            Session session = new TcpClientSession(host, port, protocol);
            
            session.addListener(new org.geysermc.mcprotocollib.network.event.session.SessionAdapter() {
                @Override
                public void connected(org.geysermc.mcprotocollib.network.event.session.ConnectedEvent event) {
                    connected.incrementAndGet();
                }

                @Override
                public void disconnected(org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent event) {
                    connected.decrementAndGet();
                    System.out.println("Bot " + finalName + " disconnected: " + event.getReason());
                }
            });

            session.connect();
            sessions.add(session);

            if (i % 50 == 0) {
                System.out.printf("Started %d/%d bots... (Connected: %d)%n", i, bots, connected.get());
            }

            Thread.sleep(delayBetweenBotsMs);
        }

        System.out.printf("Finished ramping up %d bots. Online: %d. Holding for %d seconds...%n", 
                bots, connected.get(), holdSeconds);

        // Keep them alive and moving slightly
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> {
            for (Session s : sessions) {
                if (s.isConnected()) {
                    s.send(new ServerboundMovePlayerPosPacket(true, false, 0.5, 66.0, 0.5));
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        Thread.sleep(holdSeconds * 1000L);

        System.out.println("Benchmark time over. Disconnecting bots...");
        scheduler.shutdown();
        for (Session s : sessions) {
            if (s.isConnected()) {
                s.disconnect("Benchmark End");
            }
        }
        
        System.out.println("Benchmark complete.");
    }
}
