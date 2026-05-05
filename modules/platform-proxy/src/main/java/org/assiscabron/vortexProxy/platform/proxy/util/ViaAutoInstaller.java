package org.assiscabron.vortexProxy.platform.proxy.util;

import org.slf4j.Logger;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

public class ViaAutoInstaller {

    private static final String VIAVERSION_URL = "https://hangarcdn.papermc.io/plugins/ViaVersion/ViaVersion/versions/5.9.1/PAPER/ViaVersion-5.9.1.jar";
    private static final String VIABACKWARDS_URL = "https://hangarcdn.papermc.io/plugins/ViaVersion/ViaBackwards/versions/5.9.1/PAPER/ViaBackwards-5.9.1.jar";

    public static boolean checkAndInstall(Path pluginsFolder, Logger logger) {
        boolean downloaded = false;

        try {
            if (!Files.exists(pluginsFolder)) {
                Files.createDirectories(pluginsFolder);
            }

            // Verifica se o ViaVersion ja existe (tenta achar qualquer arquivo contendo
            // ViaVersion)
            boolean hasViaVersion = Files.list(pluginsFolder)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("viaversion"));

            boolean hasViaBackwards = Files.list(pluginsFolder)
                    .anyMatch(p -> p.getFileName().toString().toLowerCase().contains("viabackwards"));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.ALWAYS)
                    .build();

            if (!hasViaVersion) {
                logger.info(
                        "[Vortex Bootstrapper] O painel Minestom e Velocity exigem a ponte de Retrocompatibilidade.");
                downloadJar(client, VIAVERSION_URL, pluginsFolder.resolve("ViaVersion.jar"), "ViaVersion Component",
                        logger);
                downloaded = true;
            }

            if (!hasViaBackwards) {
                downloadJar(client, VIABACKWARDS_URL, pluginsFolder.resolve("ViaBackwards.jar"),
                        "ViaBackward Component", logger);
                downloaded = true;
            }

            if (downloaded) {
                logger.warn("==========================================================================");
                logger.warn("[Vortex Bootstrapper] Bridge baixada com SUCESSO! Auto-instalada.");
                logger.warn(
                        "[Vortex Bootstrapper] REINICIE O SERVIDOR Vortex para que a Engine carregue os novos clientes!");
                logger.warn("==========================================================================");
            }

        } catch (Exception e) {
            logger.error("Falha ao injetar automaticamente a compatibilidade de protocolo (Via APIs): ", e);
        }

        return downloaded;
    }

    private static void downloadJar(HttpClient client, String url, Path dest, String name, Logger logger)
            throws Exception {
        logger.info("   -> Baixando {} da nuvem (Isto ocorre apenas uma unica vez)...", name);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() == 200) {
            Files.copy(response.body(), dest, StandardCopyOption.REPLACE_EXISTING);
            logger.info("   -> {} instalada no kernel com sucesso!", name);
        } else {
            logger.error("   -> Falhou com HTTP Status {}", response.statusCode());
        }
    }
}
