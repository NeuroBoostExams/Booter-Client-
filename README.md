# Booter Client

A client-side Fabric mod for **Minecraft 1.21.6** featuring the **Waypoint Walker** module:
record a route of waypoints and let the mod walk it for you using nothing but vanilla
movement inputs — no packets, no teleportation, no server-side tricks.

Verified to compile against Minecraft `1.21.6`, Yarn `1.21.6+build.1`,
Fabric Loader `0.19.3`, Fabric API `0.128.2+1.21.6`.

---

## Features

### Waypoint Walker
- Unlimited waypoints (X/Y/Z), walked in order with seamless transitions (forward is
  never released between waypoints).
- **Waypoint Radius** (0.5–5.0 blocks, default 2.0): a waypoint counts as reached when
  the player enters its radius — no pixel-perfect coordinates needed.
- **Loop mode** restarts from waypoint #1 after the last one.
- Start / Stop / Pause / Resume at any time; movement stops automatically when the
  destination is reached.
- The working route auto-saves after every change and is restored next session.

### Movement (input-level only)
- Holds the vanilla **forward** key while travelling; optional **Hold Sprint** and
  **Hold Crouch**.
- **Hold Left Click** (optional): holds the attack binding exactly like a physical
  mouse button — including the initial click — and releases the instant the module
  stops. While the click GUI is open, vanilla itself suppresses attacking (same as any
  screen), but walking continues.
- When the route stops, every key is restored to its *real* physical state.

### Rotation
- **Linear** (constant angular velocity) rotations — never snaps to the target.
- Delta-time based: identical turn speed at 30 FPS and 240 FPS, updated every frame.
- **Rotation Speed Multiplier** 0.1x–10.0x (default 1.0x ≈ 180°/s yaw, 120°/s pitch).
- Yaw and pitch smoothed independently.
- **Custom Pitch** (-90 to +90): the view holds your chosen pitch while walking, using
  the same smoothing.
- **Randomized micro-rotations**: small jitter (±1.5° yaw, ±1.0° pitch) re-rolled every
  150–450 ms so the aim drifts like a human hand. Toggleable in the GUI; **disabled by
  default**.

### Rendering (toggleable, minimal FPS cost)
- In-world waypoint markers, lines connecting the route, a closing line in loop mode.
- The active waypoint is highlighted green with a taller marker.
- Billboarded waypoint numbers in route order (visible through walls).
- The configured waypoint radius drawn as a ring around every waypoint.
- All geometry goes through the world renderer's shared line buffer; boxes, rings and
  labels are distance-culled (96 blocks), so hundreds of waypoints stay cheap.

### Pathfinder (A*)
- A grid-based **A\*** pathfinder that walks the player to any target block using only
  vanilla movement inputs (forward + auto-jump) — no packets, no teleportation.
- Walkability uses **Minecraft's own block logic** (collision shapes,
  `BlockState.isPathfindable`, face sturdiness), so fences, walls and closed gates are
  avoided automatically, and step-ups, drops (up to 3) and diagonals are validated the
  way the player physically moves. Diagonal corner-cutting past obstacles is rejected.
- Smooth, framerate-independent yaw **and** pitch rotations toward each node (shares the
  Rotation Speed Multiplier, 0.1×–10×).
- Auto-jump handles hills, stairs and small obstacles; optional **Allow Water (swim)**.
- Stops when the destination is reached; re-paths automatically if it gets stuck.
- In the GUI: type a target X/Y/Z or press **Target = Crosshair** to use the block you're
  looking at, then **Pathfind**. The path nodes are listed live with the active one
  highlighted. Bounded by a node budget + search radius so it never freezes the client.

### Click GUI (default key: **Right Shift**)
A left-hand sidebar lists sections; selecting one fills the content area on the right.
The **Route Walker** section holds every Waypoint Walker control: module Enable/Disable,
Start/Pause-Resume/Stop, Loop Route, Hold Sprint, Hold Crouch, Hold Left Click,
Render Route, Micro Rotations, Custom Pitch / Rotation Speed / Waypoint Radius sliders,
Add / Remove Nearest / Clear All buttons, a scrollable waypoint list (click a row to
walk to it, click `x` to delete it), and JSON route export/import. The sidebar is built
to hold additional sections as more modules are added.

### Keybinds (rebindable in Options → Controls → Booter Client)
| Key | Action |
|-----|--------|
| `N` | Add Waypoint (stores your current X/Y/Z) |
| `M` | Remove Nearest Waypoint (within configurable distance, default 8 blocks) |
| `K` | Clear All Waypoints |
| `J` | Toggle Waypoint Walker (start/stop the route) |
| `Right Shift` | Open the click GUI |

Every add/remove/clear prints a confirmation in chat with the waypoint's number.

### Routes as JSON (import/export)
- Working route: `config/booterclient/waypoints.json` (auto-saved).
- Named routes: type a name in the GUI's **Route** field, then **Save JSON** /
  **Load JSON**. Files live in `config/booterclient/routes/<name>.json` — plain JSON
  you can share; drop a file in that folder and load it by name. The **Routes Folder**
  button opens the folder.
- Settings persist in `config/booterclient/config.json`.

---

## Project layout

```
booter-client/
├── build.gradle / settings.gradle / gradle.properties
├── gradle/wrapper/                      Gradle 9.5.1 wrapper
└── src/main/
    ├── resources/
    │   ├── fabric.mod.json              mod metadata (client entrypoint)
    │   ├── booterclient.mixins.json     mixin config
    │   └── assets/booterclient/lang/en_us.json
    └── java/com/booter/client/
        ├── BooterClient.java            entrypoint, event wiring, chat helpers
        ├── WaypointWalkerModule.java    route state machine (stop/walk/pause)
        ├── waypoint/Waypoint.java       one route point
        ├── waypoint/WaypointManager.java list + JSON persistence/import/export
        ├── movement/MovementController.java vanilla input (KeyBinding) driver
        ├── rotation/RotationController.java linear, delta-time smoothed aim
        ├── render/RouteRenderer.java    in-world markers/lines/rings/numbers
        ├── gui/WaypointScreen.java      custom click GUI
        ├── input/KeybindManager.java    keybind registration + handling
        ├── config/ConfigManager.java    settings JSON load/save
        └── mixin/KeyBindingAccessor.java exposes KeyBinding.timesPressed
```

## Build

Requires **JDK 21+** (any recent JDK works; the build targets Java 21). No Gradle
install needed — the wrapper downloads everything.

```bash
cd booter-client
./gradlew build          # Windows: gradlew.bat build
```

The mod jar appears at `build/libs/booter-client-1.0.0.jar`.

## Install

1. Install the [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 1.21.6.
2. Download the matching [Fabric API](https://modrinth.com/mod/fabric-api) (`0.128.x+1.21.6`)
   into your `mods/` folder.
3. Copy `build/libs/booter-client-1.0.0.jar` into `mods/`.
4. Launch the 1.21.6 Fabric profile.

## Quick start

1. Walk to a spot, press `N` — repeat along the path you want.
2. Press `Right Shift`, enable **Loop Route** if you want the route to repeat.
3. Press `J` (or **Start** in the GUI). The player turns smoothly toward waypoint #1
   and walks the route. Press `J` again or **Stop** to halt.

## Development

```bash
./gradlew runClient      # launch a dev client
./gradlew genSources     # decompiled, mapped Minecraft sources for your IDE
```

---

*Use responsibly: automated movement may violate the rules of some multiplayer
servers. Check the rules of any server you play on.*
