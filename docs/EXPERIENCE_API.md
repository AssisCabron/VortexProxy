# Vortex Experience API

Vortex experiences are loaded from:

```text
experiences/
  experience_id/
    manifest.json
    scripts/
    assets/
    config/
```

Minimal `manifest.json`:

```json
{
  "id": "my_game",
  "name": "My Game",
  "version": "0.1.0",
  "entrypoints": {
    "server": "scripts/server/init.lua"
  },
  "permissions": ["ui.render"],
  "presentation": {
    "description": "Shown in the Vortex gallery.",
    "galleryImage": "assets/thumb.jpg",
    "accentBlock": "lime_concrete"
  }
}
```

`presentation.galleryImage` can be:

- a relative file inside the experience package, for example `assets/thumb.jpg`
- a classpath resource for built-in platform assets, for example `classpath:/assets/vortex/platform_home.jpg`
- an HTTP/HTTPS URL for development experiments

The backend renders each manifest as a clickable gallery card. Clicking a card
currently teleports the player to that experience's demo surface. Later this
same selection event will place the player into a real runtime instance.
