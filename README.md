# Anima

**Shader-free atmospheric visuals for Minecraft 26.2 (Fabric) + a standalone Minecraft voxel rendering engine for visual verification.**

Anima is designed to replace heavy shader packs (OptiFine / Iris) by enhancing Minecraft's native lighting, sky, fog, water, and foliage pipelines directly — achieving rich golden-hour warmth, cool atmospheric shadows, crystal-clear water, and aerial perspective depth with **near-zero GPU overhead**, so even low-end ("potato") hardware can run beautiful Minecraft smoothly. Because it uses Minecraft 26.2's high-level render state & fog/biome pipelines instead of raw OpenGL shaders, it is compatible with both **OpenGL** and **Vulkan** backends.

## Repository Structure

- **`Anima-mod/`** — Fabric mod for **Minecraft 26.2** (Java 25, Fabric Loom `1.17`, Fabric Loader `0.20.1+`, Fabric API `0.161.0+26.2`).
  - `dev.anima.core.*` — Pure-Java atmospheric visual engines (`AnimaAtmosphere`, `AnimaLightmapEngine`, `AnimaSkyEngine`, `AnimaFogEngine`, `AnimaWaterEngine`, `AnimaShadingEngine`, `AnimaConfig`, `ColorMath`).
  - `dev.anima.client.*` — Fabric client entrypoint, keybinds (`F9` toggle, `F10` cycle presets: Vanilla / Potato / Balanced / Lush), and Minecraft 26.2 mixins (`LightmapExtractorMixin`, `AtmosphericFogMixin`, `WaterFogMixin`, `SkyRendererMixin`, `BiomeColorsMixin`).
- **`Anima-renderer/`** — Pure-Java multi-threaded 3D Minecraft voxel rendering engine that **directly loads and executes `Anima-mod`'s compiled `dev.anima.core.*` classes** on a live Minecraft gameplay scene (rolling grassy hills, pond with sandy riverbed, hero oak tree, wildflowers, wooden pier with glowing torches, 3D blocky clouds, sun/moon/stars) to produce PNG screenshots and Vanilla-vs-Anima comparison sheets.

## Building `Anima-mod`

```powershell
cd Anima-mod
gradle build --no-daemon
```

The built mod JAR is output to `Anima-mod/build/libs/anima-0.1.0.jar`.

### In-Game Controls
- **`F9`** — Toggle Anima visuals ON / OFF
- **`F10`** — Cycle presets (`Vanilla (Off)` → `Potato (Ultra-Fast)` → `Balanced` → `Lush (Cinematic)`)
- Config is automatically saved to `config/anima.properties`.

## Running `Anima-renderer`

```powershell
cd Anima-renderer
.\run.ps1
```

Rendered PNGs are saved in `Anima-renderer/renders/`:
- `comparison_sheet.png` — Side-by-side Vanilla vs Anima Lush across Midday, Golden Hour, and Moonlit Night
- `presets_sheet.png` — 4-way comparison of Vanilla, Potato, Balanced, and Lush at Golden Hour
- Individual 960×540 renders (`day_vanilla.png`, `day_anima.png`, `golden_vanilla.png`, `golden_anima.png`, `night_vanilla.png`, `night_anima.png`)
