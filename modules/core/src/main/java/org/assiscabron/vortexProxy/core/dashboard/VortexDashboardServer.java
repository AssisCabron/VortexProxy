package org.assiscabron.vortexProxy.core.dashboard;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceCatalog;

import org.assiscabron.vortexProxy.api.ExperienceId;
import org.assiscabron.vortexProxy.api.ExperienceCatalog;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.assiscabron.vortexProxy.api.ExperienceCatalog;
import org.assiscabron.vortexProxy.api.ExperienceId;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public final class VortexDashboardServer {
    private static final Pattern EXPERIENCE_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{2,63}");
    private static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_]+");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final ExperienceCatalog catalog;
    private final Consumer<ExperienceId> publishCallback;
    private final BiPredicate<UUID, ExperienceId> openStudioCallback;
    private final Consumer<ExperienceId> closeExperienceCallback;
    private final Logger logger;
    private final SecureRandom secureRandom = new SecureRandom();
    private final ConcurrentMap<String, DashboardSession> sessions = new ConcurrentHashMap<>();

    private HttpServer server;
    private ExecutorService executor;
    private int port;

    public VortexDashboardServer(
            ExperienceCatalog catalog,
            Consumer<ExperienceId> publishCallback,
            BiPredicate<UUID, ExperienceId> openStudioCallback,
            Consumer<ExperienceId> closeExperienceCallback,
            Logger logger
    ) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.publishCallback = Objects.requireNonNull(publishCallback, "publishCallback");
        this.openStudioCallback = Objects.requireNonNull(openStudioCallback, "openStudioCallback");
        this.closeExperienceCallback = Objects.requireNonNull(closeExperienceCallback, "closeExperienceCallback");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public synchronized void start() {
        if (server != null) {
            return;
        }

        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16);
            port = server.getAddress().getPort();
            executor = Executors.newVirtualThreadPerTaskExecutor();
            server.setExecutor(executor);
            server.createContext("/", this::handleDashboard);
            server.createContext("/api/experiences", this::handleExperiences);
            server.start();
            logger.info("Vortex dashboard started on 127.0.0.1:{}.", port);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start Vortex dashboard", exception);
        }
    }

    public synchronized void stop() {
        if (server == null) {
            return;
        }
        server.stop(0);
        server = null;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        sessions.clear();
    }

    public String createSessionUrl(UUID playerId, String username) {
        if (server == null) {
            return "dashboard unavailable";
        }
        var token = generateToken();
        sessions.put(token, new DashboardSession(playerId, username));
        return "http://127.0.0.1:" + port + "/?token=" + token;
    }

    private void handleDashboard(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain", "Method not allowed");
            return;
        }
        if (session(exchange).isEmpty()) {
            send(exchange, 401, "text/plain", "Unauthorized");
            return;
        }
        send(exchange, 200, "text/html; charset=utf-8", dashboardHtml());
    }

    private void handleExperiences(HttpExchange exchange) throws IOException {
        var session = session(exchange);
        if (session.isEmpty()) {
            send(exchange, 401, "application/json", "{\"error\":\"unauthorized\"}");
            return;
        }

        var route = experienceRoute(exchange.getRequestURI().getPath());
        try {
            switch (exchange.getRequestMethod().toUpperCase(Locale.ROOT)) {
                case "GET" -> send(exchange, 200, "application/json", GSON.toJson(ownedExperiences(session.orElseThrow())));
                case "POST" -> handlePostExperience(exchange, session.orElseThrow(), route);
                case "PUT" -> handleUpdateExperience(exchange, session.orElseThrow(), route);
                case "DELETE" -> handleDeleteExperience(exchange, session.orElseThrow(), route);
                default -> send(exchange, 405, "application/json", "{\"error\":\"method not allowed\"}");
            }
        } catch (RuntimeException exception) {
            logger.warn("Dashboard request failed.", exception);
            send(exchange, 400, "application/json", GSON.toJson(Map.of("error", exception.getMessage())));
        }
    }

    private void handlePostExperience(HttpExchange exchange, DashboardSession session, ExperienceRoute route) throws IOException {
        if (route.id().isEmpty()) {
            var json = readJson(exchange);
            writeExperiencePackage(json, session, true);
            publishCallback.accept(new ExperienceId(required(json, "id").toLowerCase(Locale.ROOT).trim()));
            send(exchange, 201, "application/json", "{\"ok\":true}");
            return;
        }

        if (route.action().filter("open"::equals).isPresent()) {
            var id = new ExperienceId(route.id().orElseThrow());
            requireOwner(session, id);
            var opened = openStudioCallback.test(session.playerId(), id);
            send(exchange, opened ? 200 : 409, "application/json", opened
                    ? "{\"ok\":true}"
                    : "{\"error\":\"player must be online in the Vortex backend\"}");
            return;
        }

        send(exchange, 404, "application/json", "{\"error\":\"not found\"}");
    }

    private void handleUpdateExperience(HttpExchange exchange, DashboardSession session, ExperienceRoute route) throws IOException {
        var id = route.id().map(ExperienceId::new).orElseThrow(() -> new IllegalArgumentException("experience id is required"));
        requireOwner(session, id);
        var json = readJson(exchange);
        json.addProperty("id", id.value());
        writeExperiencePackage(json, session, false);
        publishCallback.accept(id);
        send(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private void handleDeleteExperience(HttpExchange exchange, DashboardSession session, ExperienceRoute route) throws IOException {
        var id = route.id().map(ExperienceId::new).orElseThrow(() -> new IllegalArgumentException("experience id is required"));
        requireOwner(session, id);
        deleteExperiencePackage(id);
        closeExperienceCallback.accept(id);
        send(exchange, 200, "application/json", "{\"ok\":true}");
    }

    private List<Map<String, Object>> ownedExperiences(DashboardSession session) {
        return catalog.list().stream()
                .filter(experience -> experience.owner()
                        .map(owner -> owner.playerId().equals(session.playerId().toString()))
                        .orElse(false))
                .sorted(Comparator.comparing(experience -> experience.id().value()))
                .map(experience -> Map.of(
                        "id", experience.id().value(),
                        "name", experience.name(),
                        "version", experience.version(),
                        "description", experience.presentation().description(),
                        "galleryImage", experience.presentation().galleryImage().orElse(""),
                        "accentBlock", experience.presentation().accentBlock(),
                        "scripts", readScripts(experience.id())
                ))
                .toList();
    }

    private void requireOwner(DashboardSession session, ExperienceId id) {
        var manifest = catalog.find(id).orElseThrow(() -> new IllegalArgumentException("experience not found"));
        var ownerMatches = manifest.owner()
                .map(owner -> owner.playerId().equals(session.playerId().toString()))
                .orElse(false);
        if (!ownerMatches) {
            throw new IllegalArgumentException("you do not own this experience");
        }
    }

    private void writeExperiencePackage(JsonObject json, DashboardSession session, boolean create) {
        var id = required(json, "id").toLowerCase(Locale.ROOT).trim();
        if (!EXPERIENCE_ID.matcher(id).matches()) {
            throw new IllegalArgumentException("id must use 3-64 chars: lowercase letters, numbers, _ or -");
        }
        if (create && catalog.find(new ExperienceId(id)).isPresent()) {
            throw new IllegalArgumentException("experience id already exists");
        }

        var name = required(json, "name").trim();
        var version = optional(json, "version", "0.1.0").trim();
        var description = optional(json, "description", "A Vortex experience").trim();
        var galleryImage = optional(json, "galleryImage", "").trim();
        var accentBlock = optional(json, "accentBlock", "cyan_concrete")
                .toLowerCase(Locale.ROOT)
                .replace(' ', '_')
                .trim();
        var scripts = json.has("scripts") && json.get("scripts").isJsonArray()
                ? json.getAsJsonArray("scripts")
                : null;

        if (!BLOCK_ID.matcher(accentBlock).matches()) {
            throw new IllegalArgumentException("accentBlock must be a vanilla block id like cyan_concrete");
        }

        var root = experienceRoot(id);
        try {
            Files.createDirectories(root.resolve("scripts/server"));
            Files.createDirectories(root.resolve("assets"));
            Files.createDirectories(root.resolve("config"));
            if (scripts == null) {
                writeScript(root, "scripts/server/init.lua", defaultScript());
            } else {
                clearScripts(root.resolve("scripts"));
                for (var element : scripts) {
                    var script = element.getAsJsonObject();
                    writeScript(root, required(script, "path"), optional(script, "content", ""));
                }
            }
            Files.writeString(root.resolve("manifest.json"), manifestJson(
                    id, name, version, description, galleryImage, accentBlock, session
            ), StandardCharsets.UTF_8);
            var scene = root.resolve("config/studio-scene.json");
            if (!Files.exists(scene)) {
                Files.writeString(scene, "{\n  \"entities\": []\n}\n", StandardCharsets.UTF_8);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("could not write experience package", exception);
        }
    }

    private void deleteExperiencePackage(ExperienceId id) {
        var root = experienceRoot(id.value());
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (var path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("could not delete experience package", exception);
        }
    }

    private List<Map<String, String>> readScripts(ExperienceId id) {
        var root = experienceRoot(id.value());
        var scriptsRoot = root.resolve("scripts");
        if (!Files.isDirectory(scriptsRoot)) {
            return List.of(Map.of("path", "scripts/server/init.lua", "content", defaultScript()));
        }
        try {
            try (var stream = Files.walk(scriptsRoot)) {
                var scripts = stream
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(".lua"))
                        .sorted(Comparator.comparing(path -> root.relativize(path).toString()))
                        .map(path -> {
                            try {
                                return Map.of(
                                        "path", root.relativize(path).toString().replace('\\', '/'),
                                        "content", Files.readString(path, StandardCharsets.UTF_8)
                                );
                            } catch (IOException exception) {
                                throw new IllegalStateException("could not read script " + path, exception);
                            }
                        })
                        .toList();
                return scripts.isEmpty()
                        ? List.of(Map.of("path", "scripts/server/init.lua", "content", defaultScript()))
                        : scripts;
            }
        } catch (IOException exception) {
            logger.warn("Could not read scripts for {}.", id.value(), exception);
            return List.of();
        }
    }

    private void clearScripts(Path scriptsRoot) throws IOException {
        if (!Files.exists(scriptsRoot)) {
            return;
        }
        try (var stream = Files.walk(scriptsRoot)) {
            for (var path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void writeScript(Path root, String relativePath, String content) throws IOException {
        var normalized = relativePath.replace('\\', '/');
        if (!normalized.startsWith("scripts/") || !normalized.endsWith(".lua") || normalized.contains("..")) {
            throw new IllegalArgumentException("script path must be inside scripts/ and end with .lua");
        }
        var path = root.resolve(normalized).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("invalid script path");
        }
        Files.createDirectories(path.getParent());
        Files.writeString(path, content + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private Path experienceRoot(String id) {
        var base = Path.of("experiences").toAbsolutePath().normalize();
        var root = base.resolve(id).normalize();
        if (!root.startsWith(base)) {
            throw new IllegalArgumentException("invalid experience path");
        }
        return root;
    }

    private String manifestJson(
            String id,
            String name,
            String version,
            String description,
            String galleryImage,
            String accentBlock,
            DashboardSession session
    ) {
        var manifest = new JsonObject();
        manifest.addProperty("id", id);
        manifest.addProperty("name", name);
        manifest.addProperty("version", version);

        var owner = new JsonObject();
        owner.addProperty("playerId", session.playerId().toString());
        owner.addProperty("username", session.username());
        manifest.add("owner", owner);

        var entrypoints = new JsonObject();
        entrypoints.addProperty("server", "scripts/server/init.lua");
        manifest.add("entrypoints", entrypoints);
        manifest.add("permissions", GSON.toJsonTree(List.of("players.read", "ui.render")));

        var resources = new JsonObject();
        resources.addProperty("maxPlayers", 50);
        resources.addProperty("maxEntities", 250);
        resources.addProperty("luaInstructionBudgetPerTick", 50_000);
        resources.addProperty("memoryMb", 64);
        manifest.add("resources", resources);

        var presentation = new JsonObject();
        presentation.addProperty("description", description);
        if (!galleryImage.isBlank()) {
            presentation.addProperty("galleryImage", galleryImage);
        }
        presentation.addProperty("accentBlock", accentBlock);
        manifest.add("presentation", presentation);

        return GSON.toJson(manifest);
    }

    private JsonObject readJson(HttpExchange exchange) throws IOException {
        try (var reader = new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private ExperienceRoute experienceRoute(String path) {
        var relative = path.substring("/api/experiences".length());
        if (relative.isBlank() || "/".equals(relative)) {
            return new ExperienceRoute(Optional.empty(), Optional.empty());
        }
        var parts = java.util.Arrays.stream(relative.split("/"))
                .filter(part -> !part.isBlank())
                .toList();
        return new ExperienceRoute(
                parts.isEmpty() ? Optional.empty() : Optional.of(parts.getFirst()),
                parts.size() < 2 ? Optional.empty() : Optional.of(parts.get(1))
        );
    }

    private Optional<DashboardSession> session(HttpExchange exchange) {
        var header = exchange.getRequestHeaders().getFirst("X-Vortex-Token");
        var token = header == null || header.isBlank()
                ? query(exchange.getRequestURI()).get("token")
                : header;
        return Optional.ofNullable(sessions.get(token));
    }

    private Map<String, String> query(URI uri) {
        var raw = uri.getRawQuery();
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        return java.util.Arrays.stream(raw.split("&"))
                .map(part -> part.split("=", 2))
                .filter(parts -> parts.length == 2)
                .collect(java.util.stream.Collectors.toMap(
                        parts -> decode(parts[0]),
                        parts -> decode(parts[1]),
                        (left, right) -> right
                ));
    }

    private String decode(String value) {
        return java.net.URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private String required(JsonObject json, String key) {
        var value = optional(json, key, "");
        if (value.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private String optional(JsonObject json, String key, String fallback) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return fallback;
        }
        return json.get(key).getAsString();
    }

    private String generateToken() {
        var bytes = new byte[24];
        secureRandom.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private String defaultScript() {
        return """
                Game.Events:OnStart(function()
                    print("Experience started")
                    Game.World:Fill("glass", -3, 65, -3, 3, 65, 3)
                    Game.World:SetBlock("sea_lantern", 0, 66, 0)
                    Game.Chat:ClearAll()
                    Game.Chat:Broadcast("Servidor carregado!")
                end)
                """;
    }

    private void send(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private String dashboardHtml() {
        return """
                <!doctype html>
                <html lang="pt-BR">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Vortex Studio</title>
                  <style>
                    :root { color-scheme: dark; font-family: Inter, Segoe UI, Arial, sans-serif; background: #101316; color: #edf3f8; }
                    body { margin: 0; min-height: 100vh; display: grid; grid-template-columns: 320px 1fr; }
                    aside { border-right: 1px solid #27313a; padding: 20px; background: #151a1f; overflow: auto; }
                    main { padding: 20px; display: grid; grid-template-rows: auto 1fr; gap: 16px; min-width: 0; }
                    h1, h2, h3 { margin: 0 0 14px; letter-spacing: 0; }
                    label { display: grid; gap: 6px; margin-bottom: 12px; color: #b8c7d4; font-size: 13px; }
                    input, textarea { box-sizing: border-box; width: 100%; border: 1px solid #33414d; border-radius: 6px; padding: 10px 12px; background: #0e1215; color: #edf3f8; }
                    textarea { resize: vertical; }
                    button { border: 0; border-radius: 6px; padding: 10px 12px; background: #18a4c7; color: #061014; font-weight: 700; cursor: pointer; }
                    button.secondary { background: #26323b; color: #dce8f0; }
                    button.danger { background: #d95252; color: white; }
                    .list { display: grid; gap: 10px; }
                    .item { border: 1px solid #29343e; border-radius: 8px; background: #171d22; padding: 12px; text-align: left; color: inherit; }
                    .item.active { outline: 2px solid #18a4c7; }
                    .editor { display: grid; grid-template-columns: 360px 1fr; gap: 16px; min-height: 0; }
                    .panel { border: 1px solid #29343e; border-radius: 8px; background: #171d22; padding: 16px; min-width: 0; }
                    .script { min-height: 480px; font-family: Consolas, monospace; line-height: 1.45; }
                    .scripts { display: grid; grid-template-columns: 240px 1fr; gap: 12px; }
                    .script-list { display: grid; gap: 8px; align-content: start; }
                    .script-file { border: 1px solid #29343e; border-radius: 6px; background: #10161b; padding: 9px; text-align: left; color: inherit; }
                    .script-file.active { outline: 2px solid #18a4c7; }
                    .row { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; }
                    .muted { color: #91a3b2; font-size: 13px; }
                    .status { min-height: 20px; margin-top: 12px; color: #9de7ff; }
                    .thumb { height: 130px; background: #0b0f12 center / cover no-repeat; border-radius: 6px; margin-bottom: 12px; }
                    @media (max-width: 980px) { body { grid-template-columns: 1fr; } aside { border-right: 0; border-bottom: 1px solid #27313a; } .editor { grid-template-columns: 1fr; } }
                  </style>
                </head>
                <body>
                  <aside>
                    <h1>Vortex Studio</h1>
                    <form id="create">
                      <label>ID <input name="id" placeholder="minha_experiencia" required pattern="[a-z0-9][a-z0-9_-]{2,63}"></label>
                      <label>Nome <input name="name" placeholder="Minha experiencia" required></label>
                      <button type="submit">Criar experiencia</button>
                    </form>
                    <div class="status" id="status"></div>
                    <h3>Minhas experiencias</h3>
                    <section class="list" id="experiences"></section>
                  </aside>
                  <main>
                    <div class="row">
                      <h2 id="title">Selecione uma experiencia</h2>
                      <button class="secondary" id="open" disabled>Entrar no Studio</button>
                      <button id="save" disabled>Salvar e publicar</button>
                      <button class="danger" id="delete" disabled>Apagar</button>
                    </div>
                    <section class="editor">
                      <div class="panel">
                        <div class="thumb" id="thumb"></div>
                        <label>Nome <input id="name"></label>
                        <label>Versao <input id="version"></label>
                        <label>Imagem da galeria <input id="galleryImage"></label>
                        <label>Bloco de destaque <input id="accentBlock"></label>
                        <label>Descricao <textarea id="description"></textarea></label>
                      </div>
                      <div class="panel">
                        <div class="row">
                          <h3>Scripts</h3>
                          <button class="secondary" id="addScript">Novo script</button>
                          <button class="danger" id="removeScript" disabled>Apagar script</button>
                        </div>
                        <div class="scripts">
                          <div class="script-list" id="scriptList"></div>
                          <label>Codigo <textarea class="script" id="scriptContent" spellcheck="false"></textarea></label>
                        </div>
                      </div>
                    </section>
                  </main>
                  <script>
                    const token = new URLSearchParams(location.search).get('token');
                    const status = document.querySelector('#status');
                    const api = path => `${path}?token=${encodeURIComponent(token)}`;
                    let experiences = [];
                    let selected = null;
                    let activeScript = 0;

                    function selectedPayload() {
                      return {
                        name: document.querySelector('#name').value,
                        version: document.querySelector('#version').value,
                        galleryImage: document.querySelector('#galleryImage').value,
                        accentBlock: document.querySelector('#accentBlock').value,
                        description: document.querySelector('#description').value,
                        scripts: currentScripts()
                      };
                    }

                    function currentScripts() {
                      if (!selected) return [];
                      if (selected.scripts[activeScript]) {
                        selected.scripts[activeScript].content = document.querySelector('#scriptContent').value;
                      }
                      return selected.scripts;
                    }

                    function renderList() {
                      document.querySelector('#experiences').innerHTML = experiences.map(item => `
                        <button class="item ${selected && selected.id === item.id ? 'active' : ''}" data-id="${item.id}">
                          <strong>${item.name}</strong>
                          <div class="muted">${item.id} - ${item.version}</div>
                        </button>
                      `).join('');
                      document.querySelectorAll('.item').forEach(button => {
                        button.addEventListener('click', () => selectExperience(button.dataset.id));
                      });
                    }

                    function selectExperience(id) {
                      selected = experiences.find(item => item.id === id);
                      document.querySelector('#title').textContent = selected ? selected.name : 'Selecione uma experiencia';
                      for (const id of ['open', 'save', 'delete']) document.querySelector(`#${id}`).disabled = !selected;
                      if (!selected) return;
                      document.querySelector('#name').value = selected.name;
                      document.querySelector('#version').value = selected.version;
                      document.querySelector('#galleryImage').value = selected.galleryImage;
                      document.querySelector('#accentBlock').value = selected.accentBlock;
                      document.querySelector('#description').value = selected.description;
                      document.querySelector('#thumb').style.backgroundImage = selected.galleryImage ? `url('${selected.galleryImage}')` : '';
                      activeScript = 0;
                      renderScripts();
                      renderList();
                    }

                    function renderScripts() {
                      if (!selected) return;
                      selected.scripts = selected.scripts && selected.scripts.length ? selected.scripts : [{path:'scripts/server/init.lua', content:''}];
                      document.querySelector('#scriptList').innerHTML = selected.scripts.map((script, index) => `
                        <button class="script-file ${index === activeScript ? 'active' : ''}" data-index="${index}">
                          ${script.path}
                        </button>
                      `).join('');
                      document.querySelectorAll('.script-file').forEach(button => {
                        button.addEventListener('click', () => {
                          if (selected.scripts[activeScript]) selected.scripts[activeScript].content = document.querySelector('#scriptContent').value;
                          activeScript = Number(button.dataset.index);
                          renderScripts();
                        });
                      });
                      document.querySelector('#scriptContent').value = selected.scripts[activeScript] ? selected.scripts[activeScript].content : '';
                      document.querySelector('#removeScript').disabled = selected.scripts.length <= 1;
                    }

                    async function refresh(keepId = selected && selected.id) {
                      const response = await fetch(api('/api/experiences'));
                      experiences = await response.json();
                      renderList();
                      selectExperience(keepId || (experiences[0] && experiences[0].id));
                    }

                    document.querySelector('#create').addEventListener('submit', async event => {
                      event.preventDefault();
                      const body = Object.fromEntries(new FormData(event.target).entries());
                      const response = await fetch(api('/api/experiences'), {
                        method: 'POST',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(body)
                      });
                      const result = await response.json();
                      status.textContent = result.ok ? 'Experiencia criada e publicada.' : result.error;
                      if (result.ok) await refresh(body.id);
                    });

                    document.querySelector('#addScript').addEventListener('click', event => {
                      event.preventDefault();
                      if (!selected) return;
                      const path = prompt('Caminho do script', 'scripts/server/folder/script.lua');
                      if (!path) return;
                      currentScripts();
                      selected.scripts.push({path, content: 'Game.Events:OnStart(function()\\n    print("Novo script")\\nend)'});
                      activeScript = selected.scripts.length - 1;
                      renderScripts();
                    });

                    document.querySelector('#removeScript').addEventListener('click', event => {
                      event.preventDefault();
                      if (!selected || selected.scripts.length <= 1) return;
                      selected.scripts.splice(activeScript, 1);
                      activeScript = Math.max(0, activeScript - 1);
                      renderScripts();
                    });

                    document.querySelector('#save').addEventListener('click', async () => {
                      const response = await fetch(api(`/api/experiences/${selected.id}`), {
                        method: 'PUT',
                        headers: {'Content-Type': 'application/json'},
                        body: JSON.stringify(selectedPayload())
                      });
                      const result = await response.json();
                      status.textContent = result.ok ? 'Alteracoes publicadas no jogo.' : result.error;
                      if (result.ok) await refresh(selected.id);
                    });

                    document.querySelector('#open').addEventListener('click', async () => {
                      const response = await fetch(api(`/api/experiences/${selected.id}/open`), { method: 'POST' });
                      const result = await response.json();
                      status.textContent = result.ok ? 'Studio aberto no jogo.' : result.error;
                    });

                    document.querySelector('#delete').addEventListener('click', async () => {
                      if (!confirm(`Apagar ${selected.name}?`)) return;
                      const response = await fetch(api(`/api/experiences/${selected.id}`), { method: 'DELETE' });
                      const result = await response.json();
                      status.textContent = result.ok ? 'Experiencia apagada e removida do jogo.' : result.error;
                      if (result.ok) { selected = null; await refresh(); }
                    });

                    refresh();
                  </script>
                </body>
                </html>
                """;
    }

    private record DashboardSession(UUID playerId, String username) {
    }

    private record ExperienceRoute(Optional<String> id, Optional<String> action) {
    }
}
