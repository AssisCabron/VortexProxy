# Vortex Platform Architecture

Vortex turns Minecraft into a platform runtime. Minecraft remains the renderer,
transport, input surface, and low-level entity host; Vortex owns experiences,
sessions, scripting, data, routing, permissions, and developer-facing APIs.

## 1. High-Level Architecture

```text
                         Developer CLI / SDK
                                |
                                v
                       Experience Packages
              manifest.json / scripts / assets / config
                                |
                                v
+------------------------------------------------------------------+
|                         Control Plane                            |
|                                                                  |
|  Velocity Plugin: VortexProxy                                    |
|  - player routing                                                |
|  - virtual client instances                                      |
|  - instance registry                                             |
|  - capacity and placement decisions                              |
|  - cross-server session tickets                                  |
|  - admin / developer commands                                    |
+---------------------------+--------------------------------------+
                            |
                            | signed runtime messages
                            v
+------------------------------------------------------------------+
|                          Runtime Plane                           |
|                                                                  |
|  Paper/Purpur Plugin: VortexRuntime                              |
|  - core engine lifecycle                                         |
|  - experience sandbox                                            |
|  - Lua VM workers                                                |
|  - API facade: Game.*, UI.*, DataStore.*                         |
|  - world/entity/UI adapters                                      |
|  - per-experience resource budgets                               |
+---------------------------+--------------------------------------+
                            |
             +--------------+--------------+
             |                             |
             v                             v
     PostgreSQL / Redis              Observability Stack
     - durable state                 - logs per experience
     - player profiles               - metrics
     - leases / pubsub               - traces
```

For production, this should be a multi-module project:

```text
vortex-platform/
  vortex-common/       shared IDs, manifests, protocol messages
  vortex-proxy/        Velocity control plane plugin
  vortex-virtual/      per-player virtual shell rendered from proxy packets
  vortex-runtime/      Paper/Purpur runtime plugin
  vortex-lua/          sandboxed Lua bindings and scheduler
  vortex-sdk/          developer CLI + packaging tools
  vortex-api-docs/     public scripting API docs
```

The current repository starts as `vortex-proxy`. Its first product surface is a
virtual per-player platform shell: players can enter the proxy with no configured
backend lobby and still see a platform menu/world rendered by packets generated
by Vortex itself.

Important protocol boundary: Velocity normally forwards a player to a real
backend server. A "client-side rendered server" is only possible if Vortex acts
as a virtual backend for a limited surface area by generating protocol packets
itself. This is viable for the platform home, menus, selectors, loading screens,
and lightweight preview spaces. Full gameplay experiences should still move to
Paper/Purpur runtime nodes unless the platform later implements a much larger
protocol/world simulation layer inside the proxy.

## 2. Module Breakdown

### Core Engine

Responsibilities:

- Load, validate, start, stop, reload, and unload experiences.
- Maintain experience state: `DISCOVERED`, `LOADED`, `STARTING`, `RUNNING`,
  `STOPPING`, `FAILED`, `DISABLED`.
- Own player sessions and handoffs between experiences.
- Track budgets: entities, scheduled tasks, memory estimate, Lua instruction
  count, IO operations, data-store throughput.
- Provide global and scoped event buses.
- Expose only stable platform APIs to scripts.

Key interfaces:

```java
interface ExperienceManager {
    ExperienceHandle load(ExperienceManifest manifest);
    void start(ExperienceId id);
    void stop(ExperienceId id, StopReason reason);
    void reload(ExperienceId id);
}

interface PlayerSessionService {
    PlayerSession attach(UUID playerId, ExperienceId experienceId, InstanceId instanceId);
    void transfer(UUID playerId, ExperienceId targetExperienceId);
    void detach(UUID playerId, DetachReason reason);
}
```

### Virtual Client Instance

The virtual instance is a per-player session rendered directly from the proxy.
It replaces the traditional lobby server.

Responsibilities:

- Create one isolated virtual shell per connecting player.
- Render the platform home, experience catalog, loading states, and errors.
- Own only presentation state, not full gameplay state.
- Keep player input routed into platform commands.
- Transfer the player to a real runtime instance only when an experience needs
  gameplay execution.

Lifecycle:

```text
Player connects to Velocity
  -> Vortex authenticates player
  -> VirtualClientInstance created
  -> proxy enters virtual render mode
  -> Vortex sends initial world/UI packets
  -> player selects experience
  -> control plane finds runtime placement
  -> signed transfer ticket created
  -> player moves to Paper/Purpur runtime
  -> virtual instance disposed
```

Rendering abstraction:

```java
interface VirtualRenderSurface {
    void send(RenderCommand command);
    void flush();
}

sealed interface RenderCommand {
    record ShowTitle(String title, String subtitle) implements RenderCommand {}
    record ShowMenu(String menuId, List<MenuItem> items) implements RenderCommand {}
    record PlaySound(String sound, float volume, float pitch) implements RenderCommand {}
}
```

The implementation behind `VirtualRenderSurface` is protocol-specific and should
be kept out of experience logic. This lets the platform later swap between raw
packet handling, a protocol helper library, or a small embedded virtual-world
engine.

The current code follows that boundary:

- `ProtocolRenderSurface` uses stable Velocity/Adventure APIs.
- `RawProtocolRenderSurface` receives the same `RenderCommand` stream and
  prepares virtual-world packets.
- `RawProtocolTransport` isolates any Velocity internals or future packet
  backend from the platform core.
- `VirtualRenderSurfaceFactory` composes raw protocol rendering with the public
  API fallback.

### Experience System

Package shape:

```text
experiences/
  parkour_rush/
    manifest.json
    scripts/
      server/init.lua
      server/checkpoints.lua
      client/hud.lua
    assets/
      resourcepack/
      ui/
    config/
      default.json
      production.json
```

Manifest example:

```json
{
  "id": "parkour_rush",
  "name": "Parkour Rush",
  "version": "1.0.0",
  "entrypoints": {
    "server": "scripts/server/init.lua",
    "client": "scripts/client/hud.lua"
  },
  "permissions": [
    "world.entity.spawn",
    "player.teleport",
    "datastore.read",
    "datastore.write"
  ],
  "resources": {
    "maxPlayers": 40,
    "maxEntities": 500,
    "luaInstructionBudgetPerTick": 200000,
    "memoryMb": 128
  }
}
```

Isolation rules:

- Each experience has its own class-independent script context.
- Each experience gets its own scoped services: logger, datastore, scheduler,
  event bus, asset namespace, UI root, entity registry.
- Cross-experience communication is disabled by default and only allowed through
  platform-provided messaging channels with explicit permissions.

### Scripting Layer

Lua is the product API. Java/Paper internals are implementation details.

Example script:

```lua
local Players = Game.Players
local World = Game.World
local Events = Game.Events

Events:OnStart(function()
  Game.Log:Info("Parkour Rush started")
end)

Players:OnJoin(function(player)
  player:SetDisplayName("Runner " .. player.Name)
  player:Teleport({ x = 0, y = 80, z = 0 })
end)

Events:On("checkpoint.hit", function(event)
  Game.DataStore:Get("progress"):Set(event.Player.Id, event.CheckpointId)
end)
```

API design:

```lua
Game = {
  Experience = {
    Id = "parkour_rush",
    Version = "1.0.0"
  },
  Players = {
    GetAll = function() end,
    GetById = function(playerId) end,
    OnJoin = function(callback) end,
    OnLeave = function(callback) end
  },
  World = {
    SpawnEntity = function(type, position, options) end,
    DestroyEntity = function(entityId) end,
    Raycast = function(origin, direction, options) end
  },
  Events = {
    OnStart = function(callback) end,
    OnStop = function(callback) end,
    On = function(name, callback) end,
    Emit = function(name, payload) end
  },
  UI = {
    CreateScreen = function(id, spec) end,
    Show = function(player, screenId) end,
    Hide = function(player, screenId) end
  },
  DataStore = {
    Get = function(storeName) end
  },
  Scheduler = {
    Delay = function(seconds, callback) end,
    Every = function(seconds, callback) end
  },
  Log = {
    Info = function(message) end,
    Warn = function(message) end,
    Error = function(message) end
  }
}
```

Implementation model:

- Lua code runs on controlled worker threads, not directly on the Minecraft main
  thread.
- Scripts enqueue world operations into command queues.
- The runtime applies allowed commands on the server thread.
- Lua callbacks are resumed through a tick-aware scheduler with instruction
  limits.
- API objects are capabilities, not raw Java objects.

### Sandbox and Isolation

Required controls:

- No Java reflection exposed to Lua.
- No filesystem access except package-local readonly assets and explicit config.
- No network access from scripts in MVP.
- Instruction-count limits per coroutine.
- Per-experience task queues with backpressure.
- Per-experience entity ownership; one experience cannot mutate another
  experience's entities.
- Defensive serialization for all event payloads.
- Main-thread bridge validates every command before execution.

Crash containment:

- A Lua failure marks only that script or experience as failed.
- Runtime catches all script exceptions and emits structured diagnostics.
- Stop hooks are best-effort with timeouts.
- Hung experiences are terminated by scheduler budget enforcement.

Threading boundary:

```text
Lua Worker Thread
  script callback
  -> validates API call
  -> emits VortexCommand
  -> command queue

Minecraft Server Thread
  drains command queue
  -> validates budget and ownership
  -> calls Paper API
  -> emits result event

Lua Worker Thread
  resumes awaiting coroutine
```

### Data Layer

Data must be namespaced and permissioned:

```text
vortex:{experienceId}:player:{playerUuid}:profile
vortex:{experienceId}:store:{storeName}:{key}
vortex:global:sessions:{playerUuid}
vortex:instances:{instanceId}
```

Recommended split:

- PostgreSQL for durable state, economy, inventories, audit logs, and purchases.
- Redis for sessions, instance registry, pub/sub, locks, matchmaking queues, and
  short-lived presence.
- Optional object storage for large assets and generated packages.

Lua API:

```lua
local profiles = Game.DataStore:Get("profiles")

local profile = profiles:Get(player.Id)
profile.coins = (profile.coins or 0) + 10
profiles:Set(player.Id, profile)
```

The script never sees table names, credentials, SQL, Redis clients, or global
keys.

### Networking and Scalability

Velocity owns the first connection surface. It does not need a configured lobby
backend if Vortex handles the join flow as a virtual client instance.

For the virtual platform shell:

- No lobby backend is required in `velocity.toml`.
- Player remains attached to the proxy-controlled virtual surface.
- The surface renders menus and lightweight visuals directly to the client.
- Any action that needs real world simulation triggers placement into a runtime
  node.

For full experiences, Velocity should route players to runtime instances.
Runtime servers advertise:

- instance ID
- hosted experience ID
- current players
- max players
- health
- region
- version
- warm/cold state

Placement strategy:

1. Player enters the proxy.
2. Vortex creates a per-player virtual instance.
3. The virtual shell renders the platform home.
4. Player requests an experience.
5. Proxy asks the instance registry for a healthy runtime with capacity.
6. If none exists, orchestrator starts or warms an instance.
7. Proxy creates a signed session ticket in Redis.
8. Player is connected to the selected Paper/Purpur server.
9. Runtime validates the ticket and attaches the player to the experience.

Cross-instance sync should be event-driven:

- Redis Streams or NATS for low-latency platform events.
- PostgreSQL as source of truth for durable player and experience data.
- Avoid shared mutable world state across instances in MVP.
- For realtime multi-instance worlds, use a dedicated state authority service.

### UI/UX Layer

Minecraft UI should be treated as render targets:

- inventory screens for grids, shops, selectors
- boss bars/action bars for transient HUD
- maps/resource-pack fonts for richer panels
- custom resource pack for platform identity
- chat hidden or converted into a controlled command/input surface
- titles/subtitles for transitions
- scoreboards only as low-density HUD elements

The platform home should run inside the proxy virtual shell, not inside a
traditional lobby server. The lobby is therefore a product surface, not an
extra Minecraft world.

Declarative UI spec example:

```lua
Game.UI:CreateScreen("main_menu", {
  type = "panel",
  theme = "vortex.dark",
  children = {
    { type = "text", id = "title", value = "Parkour Rush" },
    { type = "button", id = "play", text = "Play" },
    { type = "button", id = "leaderboard", text = "Leaderboard" }
  }
})

Game.UI:OnClick("main_menu.play", function(player)
  Game.Events:Emit("matchmaking.join", { player = player.Id })
end)
```

The runtime converts UI specs into Minecraft-specific renderers. Scripts do not
know whether a screen is inventory-backed, map-backed, or resource-pack-backed.

## 3. Core APIs

Java-side runtime contracts:

```java
public interface VortexRuntime {
    ExperienceManager experiences();
    SessionManager sessions();
    EventBus globalEvents();
    InstanceRegistry instances();
    ResourceBudgetService budgets();
}

public interface ScriptRuntime {
    ScriptHandle load(ExperienceContext context, ScriptSource source);
    void start(ScriptHandle handle);
    void stop(ScriptHandle handle, Duration timeout);
    void emit(ScriptHandle handle, ScriptEvent event);
}

public interface PlatformCommandBus {
    CompletionStage<CommandResult> submit(ExperienceId owner, PlatformCommand command);
}
```

Lua-side public API:

```lua
Game.Players:GetAll()
Game.Players:OnJoin(callback)
Game.World:SpawnEntity("display.block", { x = 10, y = 70, z = 3 })
Game.Events:OnStart(callback)
Game.Events:On("custom.event", callback)
Game.DataStore:Get("profiles"):Get(playerId)
Game.UI:CreateScreen("screen_id", spec)
Game.Scheduler:Every(1.0, callback)
```

Developer CLI:

```text
vortex init parkour_rush
vortex validate experiences/parkour_rush
vortex pack experiences/parkour_rush
vortex deploy parkour_rush --env staging
vortex logs parkour_rush --follow
vortex reload parkour_rush --instance auto
```

Admin commands:

```text
/vortex experiences
/vortex experience load parkour_rush
/vortex experience reload parkour_rush
/vortex instance list
/vortex player send <player> <experience>
/vortex logs <experience>
```

## 4. Data Flow

### Experience Startup

```text
Developer package
  -> manifest validation
  -> dependency and permission validation
  -> asset registration
  -> Lua VM creation
  -> API capability injection
  -> OnStart callbacks
  -> RUNNING state
```

### Player Join

```text
Player joins proxy
  -> proxy authenticates/rates limits
  -> platform menu shown
  -> player selects experience
  -> proxy selects runtime instance
  -> signed session ticket created
  -> player transferred to runtime server
  -> runtime validates ticket
  -> PlayerSession attached
  -> experience receives Players:OnJoin
  -> UI/world state rendered
```

### Script World Operation

```text
Lua script calls Game.World:SpawnEntity
  -> API validates permission and argument schema
  -> budget service checks entity limit
  -> command queued
  -> Paper main thread applies command
  -> entity tagged with experience owner
  -> result returned to script
```

## 5. MVP Roadmap

### Phase 0: Foundation

- Split repository into `common`, `proxy`, and `runtime` modules.
- Define IDs, manifests, lifecycle states, and protocol messages.
- Add structured logging with experience and instance fields.
- Add `/vortex status` and `/vortex experiences`.
- Add per-player virtual instance lifecycle in the proxy.

### Phase 1: Single-Server Runtime

- Proxy renders the platform home without a backend lobby.
- Paper/Purpur plugin loads experiences from `/experiences`.
- Validate `manifest.json`.
- Start/stop one experience at a time.
- Implement scoped event bus.
- Implement player session attach/detach.

### Phase 2: Lua Sandbox

- Embed LuaJ or a maintained Lua runtime.
- Expose minimal `Game.Events`, `Game.Players`, `Game.Log`.
- Add instruction budgets and callback timeouts.
- Add script error reporting and per-experience logs.

### Phase 3: World and UI APIs

- Add safe world commands: teleport, spawn owned entities, destroy owned
  entities, play sound, show title.
- Add declarative UI spec renderer for inventory menus and HUD.
- Add asset namespace and resource-pack pipeline.

### Phase 4: Persistence

- Add PostgreSQL-backed datastore API.
- Add Redis-backed session and instance registry.
- Enforce per-experience data namespaces.
- Add migrations/versioning for platform tables.

### Phase 5: Multi-Instance Control Plane

- Velocity routes players by experience and capacity.
- Runtime servers heartbeat to Redis.
- Add signed transfer tickets.
- Add `/vortex player send` and `/vortex experience scale`.

### Phase 6: Developer Platform

- CLI: init, validate, pack, deploy, logs.
- Local dev mode with hot reload.
- API documentation generated from Java binding metadata.
- Script debugger hooks: breakpoints are future work; structured traces first.

## 6. Scaling Strategy

### Horizontal runtime scaling

- Treat each Paper/Purpur server as a runtime node.
- A runtime node can host one or more experience instances depending on load.
- Keep hard caps per node: players, entities, tick time, script queue depth.
- Use Velocity only for routing, not game execution.

### Instance model

```text
ExperienceDefinition: "parkour_rush" version 1.0.0
ExperienceInstance: "parkour_rush/us-east/instance-42"
RuntimeNode: "paper-17"
PlayerSession: player X attached to instance-42
```

### Event patterns

- Local events for gameplay inside one instance.
- Platform events for lifecycle, telemetry, player transfers, matchmaking.
- Durable events only where replay matters.
- Never require every gameplay event to leave the server.

### Performance rules

- Do not run script callbacks on the Minecraft main thread.
- Apply world mutations in bounded batches per tick.
- Prefer immutable payloads between threads.
- Use backpressure before queue growth causes memory pressure.
- Track tick cost by experience, not only by server.
- Enforce max UI updates per player per second.

## 7. Security Considerations

### Threat model

Experiences are untrusted code. Assume a developer may accidentally or
intentionally:

- run infinite loops
- allocate excessive memory
- spam entity creation
- read another experience's data
- leak tokens or credentials
- abuse UI or chat to phish players
- crash runtime hooks

### Controls

- Capability-based Lua API.
- Manifest permissions required for sensitive APIs.
- No raw Bukkit/Paper objects exposed to scripts.
- No arbitrary Java class access.
- No direct filesystem, process, socket, or reflection access.
- Per-experience datastore namespace enforced server-side.
- Signed proxy/runtime session tickets with short TTL.
- Rate limits on datastore, UI updates, event emission, command queues.
- Experience-level circuit breaker after repeated failures.
- Audit logs for lifecycle changes and privileged APIs.

### Operational security

- Store secrets outside experience packages.
- Rotate signing keys.
- Validate packages before deployment.
- Keep platform APIs versioned.
- Run production runtime nodes with least-privilege OS permissions.
- Consider container-per-runtime-node isolation for stronger blast-radius
  control.

## Key Risks and Bottlenecks

- Lua sandbox quality: this is the hardest correctness boundary.
- Minecraft main-thread constraints: world mutations must be aggressively
  batched and budgeted.
- UI limitations: rich UX is possible, but requires resource packs and careful
  abstractions.
- Hot reload: safe for scripts/configs first; full asset/world reload later.
- Multi-instance realtime state: avoid until a clear product use case requires
  a state authority service.
- Developer trust: bad API ergonomics will make the platform feel like a plugin
  framework instead of a product.

## Recommended First Implementation Target

Build a vertical slice:

1. Player joins Velocity with no lobby backend configured.
2. Vortex creates a virtual per-player platform shell.
3. The proxy renders a polished home menu directly to the client.
4. Player selects an experience.
5. Velocity selects or starts a runtime node.
6. Player receives a signed transfer ticket and moves to Paper/Purpur.
7. Paper runtime loads one experience from disk.
8. Lua script receives `Game.Events:OnStart` and `Game.Players:OnJoin`.
9. Script shows custom UI and owns gameplay.
10. Logs and errors are scoped to the experience.

That slice proves the product concept without prematurely building a giant
distributed system.
