# Vortex Backend and Protocol Layer

Vortex no longer depends on a configured Velocity lobby server for the first
join surface. The proxy starts its own embedded backend process in the same JVM,
registers it dynamically with Velocity, and routes joining players to that
backend.

```text
Velocity login
  -> PlayerChooseInitialServerEvent
  -> EmbeddedVortexBackend
  -> Minestom virtual home instance
  -> VirtualShellService renders platform UI
```

This is intentionally not a traditional Minecraft lobby. The backend is a
platform bootstrap surface: enough world/protocol state to keep the client
connected while Vortex owns the product experience.

The virtual shell is still split into render paths:

```text
RenderCommand
  -> RawProtocolRenderSurface
       -> RawProtocolTransport
       -> Velocity internals / future packet backend
  -> ProtocolRenderSurface
       -> public Velocity + Adventure APIs
```

This gives the platform a stable product API while keeping unstable protocol
work isolated.

## Current Implementation

The preferred live connection strategy is:

```text
Velocity login
  -> dynamically registered server: vortex-embedded-home
  -> embedded Minestom listener on 127.0.0.1:<ephemeral-port>
  -> lightweight platform home world
  -> VirtualShellService renders platform UI
```

`EmbeddedVortexBackend` owns:

- choosing a local loopback port
- enabling Velocity modern forwarding when `forwarding.secret` is available
- starting the Minestom server
- registering/unregistering the backend with Velocity
- providing a small protected home instance
- reporting health through `/vortex status`

`ProtocolRenderSurface` uses public Velocity/Adventure APIs:

- title rendering
- action bar rendering
- tab header/footer
- menu text rendering
- sound requests where Velocity supports them

`RawProtocolRenderSurface` is prepared for lower-level packets:

- virtual world bootstrap
- virtual world clear
- future chunk/entity/player-position packets

`VelocityReflectionRawProtocolTransport` is deliberately conservative. It checks
whether a Velocity internal connection is reachable, but it does not fabricate
internal packet objects. The transport returns a skipped write until a concrete
packet encoder is registered for the running Velocity version.

That design avoids tying core platform code to private Velocity classes while
still giving us the correct extension point for a no-backend virtual world.

## Why Not Put Raw Packets Everywhere?

Velocity's public API does not expose stable raw packet writes for full world
simulation. A production implementation needs one of these strategies:

1. A small version-pinned module that depends on Velocity internals.
2. A standalone protocol engine before/alongside Velocity.
3. A maintained packet library that can generate the right Minecraft packets.

The rest of Vortex should never know which strategy is used. It should only send
`RenderCommand` objects.

## Next Packet Milestone

To make the virtual shell feel like an actual world instead of UI-only, implement
these packet capabilities behind `RawProtocolTransport`:

1. login/config/play-state bootstrap for clients with no backend selected
2. registry and dimension data
3. spawn position
4. chunk data for a small void/platform scene
5. player position/look
6. camera/game mode
7. display entities or text displays for menu cards
8. interaction packets mapped back into Vortex UI events

When that exists, `ProtocolRenderSurface` remains a fallback for clients or
Velocity builds where raw transport is unavailable.

## Runtime Compatibility

The previous LimboAPI direction was removed because LimboAPI hooks Velocity
internals and is protocol-version sensitive. The embedded backend avoids that
specific failure mode: Velocity sees a normal backend server, while Vortex owns
that server's implementation.

Operational notes:

- Remove LimboAPI from the Velocity plugins folder for this path.
- No lobby server needs to be configured in `velocity.toml`.
- If Velocity forwarding mode is `modern`, keep `forwarding.secret` in the proxy
  root so Minestom can validate forwarded player identities.
- If forwarding mode is `none`, the embedded backend can run without the secret,
  but that mode is not recommended for production.
- Minestom must support the Minecraft protocol used by the Velocity build.

Use `/vortex status` to confirm whether the embedded backend is available.
