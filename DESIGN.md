# DESIGN.md: LazyFitBike Indoor Cycling Cockpit

## Identity & Design Read
Reading this as: real-time 3D sports telemetry HUD and Wear OS sensor companion for indoor cyclists, in a high-contrast performance cockpit style, dial `ENERGY 2 / RHYTHM 2 / MOTION 2`.

## Color Palette (2 Core Neutrals + 1 Deliberate Accent + Semantic Status)
- `BgPhoneHud`: `#E609090B` (90% opaque Carbon Zinc `#09090B`, solid matte surface, zero full-screen glass blur)
- `BgWatch`: `#FF000000` (100% pure AMOLED Black for Wear OS battery efficiency and glare contrast)
- `SurfaceBorder`: `#FF27272A` (`1dp` solid subtle frame border)
- `TextPrimary`: `#FFF4F4F5` (Crisp White, contrast `15.8:1` against `#09090B`)
- `TextMuted`: `#FFA1A1AA` (Cool Zinc, contrast `7.2:1` against `#09090B`, passes WCAG AA)
- `AccentPrimary`: `#FFF97316` (Velodrome Orange: used ONLY on Primary Watts readout, Active Mode pill, and Primary CTA button)
- `SignalHeart`: `#FFFB7185` (Rose: Heart Rate BPM readout only)
- `SignalConnected`: `#FF34D399` (Emerald: static link indicator dot, no endless pulse)
- `SignalWarning`: `#FFFBBF24` (Amber: Road grade % and signal timeout banner)

## Typography & Layout Constraints
- All numeric telemetry readouts use tabular numerals (`fontFeatureSettings = "tnum"` in Compose, `font-variant-numeric: tabular-nums` in CSS) to prevent horizontal jitter at 10 Hz update rates.
- Phone Landscape (`sensorLandscape`) reserves the center 65% of the viewport for the 3D cyclist and road. HUD pods anchor strictly to the 4 perimeter zones with `16dp` outer safe margins.
- Galaxy Watch 4 circular layout sits inside an inscribed safe octagon (`20dp` vertical inset, `26dp` horizontal inset) with one primary `44sp` tabular metric and two `48dp`-tall bottom pill buttons.
- Minimum touch target size on all interactive controls: `48dp x 48dp`.

## One-Line Decision Log (Rule R-31)
- **Why Three.js Curved Holographic Visor HUD?** Replaces flat/shapey 2D canvas boxes with a 3D cylindrical cockpit visor running dynamic high-DPI canvas textures, delivering true 60 FPS WebGL depth, dynamic 5-zone power arc glows, and immersive camera inertia.
- **Why dark matte HUD pods?** Indoor rides happen in varied room lighting where solid high-contrast pods remain legible over moving 3D road geometry without blur artifacts.
- **Why Velodrome Orange as the sole accent?** Draws peripheral vision immediately to live Power (Watts) and primary gear/start controls without visual noise.
- **Why tabular numerals?** Prevents 10 Hz cadence and wattage updates from shifting card widths.
- **Why 4 perimeter HUD zones?** Keeps the center 3D avatar, road horizon, and AI pacer bots unobstructed.
