# Vortex Platform Architecture

Vortex turns Minecraft into a platform runtime. The project is designed as a unified system where the **Proxy (Velocity)** not only routes players but also orchestrates an **Embedded Runtime (Minestom)** for core platform services like the Home lobby, Studio, and lightweight experiences.

## 1. High-Level Architecture

```text
                         Developer CLI / SDK / Dashboard
                                 |
                                 v
                        Experience Packages
               manifest.json / scripts / assets / config / world (JSON)
                                 |
                                 v
+------------------------------------------------------------------+
|                         Vortex Platform                          |
|                                                                  |
|  Velocity Plugin: VortexProxy                                    |
|  - Orchestration & Routing                                       |
|  - Virtual Player Sessions                                       |
|  - Platform Dashboard (HTTP Server)                               |
|                                                                  |
|  +------------------------------------------------------------+  |
|  |             Embedded Backend (Minestom)                    |  |
|  |  - High-performance platform home & studio                  |  |
|  |  - Lobby Sharding (Horizontal scaling within proxy)         |  |
|  |  - Real-time World Engine (JSON-based world store)          |  |
|  |  - Experience Script Engine (Lua VM)                        |  |
|  |  - Gallery Renderer (In-game previews via maps)              |  |
|  +------------------------------------------------------------+  |
+---------------------------+--------------------------------------+
                            |
                            | Orchestration / Routing
                            v
+------------------------------------------------------------------+
|                       External Runtimes                          |
|                                                                  |
|  Paper/Purpur/Fabric Plugin: VortexRuntime                       |
|  - "Heavy" experiences requiring full Vanilla physics             |
|  - Dedicated world management (NMS/Bukkit)                       |
|  - Remote script execution                                       |
+---------------------------+--------------------------------------+
                            |
             +--------------+--------------+
             |                             |
             v                             v
      Local / SQL / Redis            Observability
      - Player Profiles (JSON)        - Logs per experience
      - Experience Catalog            - Performance (TPS/MSPT)
      - World Persistence             - Metrics
```

## 2. Module Breakdown

### Core Engine (`vortex-core`)

The heart of the platform, shared between the proxy and runtimes.

- **Experience Manager**: Load, validate, and manage experience lifecycles.
- **Scripting Layer**: Lua execution via `LuauRuntime` and `ExperienceScriptEngine`.
- **Backend Gateway**: Abstraction for communicating with runtimes (Embedded or External).
- **World Store**: Performance-optimized JSON-based world persistence for experiences.

### Proxy Platform (`vortex-platform-proxy`)

The entry point for all players and developers.

- **VortexProxy**: The Velocity plugin that initializes the system.
- **EmbeddedVortexBackend**: A high-performance Minecraft runtime (Minestom-based) running inside the proxy process.
  - **Lobby Sharding**: Automatically creates new shards as players join.
  - **Studio Mode**: Real-time creative environment for developers.
  - **Experience Gallery**: Dynamic wall of experiences rendered using protocol-level maps.
- **Dashboard Server**: An embedded HTTP server providing the web-based Vortex Studio.
- **Virtual Shell**: Low-level protocol manipulation for UI and transitions.

### Infrastructure (`vortex-infrastructure`)

Persistence and external service integration.

- **LocalPlayerDatabase**: JSON-backed storage for player profiles and metadata.
- **InMemory Registry**: Fast indexing of experiences and active instances.

## 3. Core Systems

### Embedded Minestom Backend

Unlike traditional Minecraft servers, the platform uses an embedded Minestom instance for the lobby and studio. This allows:
- **Instant Response**: No lag between proxy and lobby.
- **Programmatic Control**: Deep integration with platform APIs.
- **Efficiency**: Multiple "shards" (instances) can run with minimal overhead.

### Studio & Experience Workflow

1.  **Discovery**: Developers access the **Vortex Studio** dashboard (HTTP).
2.  **Creation**: A `StudioSession` is created in the embedded backend.
3.  **Editing**: Developers build in-game using creative tools; every block change is recorded by the **World Store**.
4.  **Scripting**: Lua scripts specify logic (init.lua).
5.  **Publishing**: The dashboard triggers a reload, updating the **Experience Gallery** for all players.

### Scripting Layer (Lua)

Lua is the product API. It runs in a tick-aware sandbox.

```lua
-- Example: Experience Join Logic
Game.Players:OnJoin(function(player)
  player:SendMessage("Welcome to Vortex!")
end)
```

- **Safety**: No reflection, no filesystem access, limited instructions per tick.
- **Concurrency**: Scripts run on worker threads, mutations are batched for the main tick.

## 4. Scaling Strategy

### Lobby Sharding
The embedded backend implements horizontal scaling through "shards". When a shard reaches its player limit (e.g., 50 players), a new Minestom `InstanceContainer` is created dynamically.

### Production Deployment
For large-scale deployments:
- **Proxy**: Multiple Velocity nodes with a shared Redis/SQL backbone.
- **Runtimes**: Heavy gameplay experiences move to external Paper/Purpur clusters.
- **State**: World and player data move from local JSON to PostgreSQL/S3.

## 5. Security & Isolation

- **Script Sandbox**: Rigid LuaJ/Luau environment.
- **Instance Isolation**: Players in different experiences are in strictly separated world instances.
- **Signature Verification**: All cross-server transfers use signed session tickets.
- **Resource Limits**: Configurable caps on entities, memory, and CPU per experience.

## 6. MVP Roadmap (Current State)

- [x] **Foundation**: Multi-module architecture with unified Core.
- [x] **Lobby**: Embedded Minestom backend with Gallery and Sharding.
- [x] **Studio**: In-game creative editing with automatic world persistence.
- [x] **Dashboard**: Embedded HTTP server for experience management.
- [x] **Scripting**: Lua engine integration (Phase 1).
- [p] **Persistence**: Local JSON databases (Migrating to SQL).
- [ ] **External Runtimes**: Paper/Purpur bridge for heavy experiences.

## Key Risks and Bottlenecks

- **Minestom Limitations**: While high-performance, Minestom lacks many Vanilla features (physics, complex entity AI, full block ticking), requiring custom implementation for "heavy" experiences.
- **Lua Sandbox Stability**: Ensuring that untrusted scripts cannot crash the proxy process or escape the sandbox remains a top priority.
- **Protocol Fidelity**: Since the embedded backend does not use Vanilla code, maintaining compatibility with different Minecraft client versions requires careful protocol handling.
- **Persistence Complexity**: Moving from local JSON files to a distributed SQL cluster while maintaining real-time world stream performance.

## Future Vision

The platform aims to become a "Roblox for Minecraft," where:
1.  Developers build instantly in the **Vortex Studio** (embedded).
2.  Experiences are published globally with one click.
3.  The platform handles all scaling, sharding, and hosting transparently.
4.  Standardized APIs allow the same experience to run on **Minestom (performance)** or **Paper (Vanilla fidelity)**.
