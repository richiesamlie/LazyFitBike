# DESIGN.md: LazyFitBike LangkahFit-Inspired Cycling Cockpit

## Identity & Design Read
Reading this as: real-time 3D indoor cycling telemetry HUD, Galaxy Watch 4 sensor companion, and Next.js spectator cockpit, inspired by LangkahFit's daylight 3D scenic road and Deep Forest Ink + Electric Lime visual language, dial `ENERGY 2 / RHYTHM 2 / MOTION 2`.

## Color Palette (Core Forest Neutrals + 1 Deliberate Electric Lime Accent + Semantic Status)
- `BgForestInk`: `#0B1510` (`#E60B1510` 90% opaque on Phone HUD pods, solid matte surface)
- `BgSurfaceInk`: `#13221A` (secondary dark forest control surface)
- `BorderForestLine`: `#1E3328` (`1dp` / `1.5px` subtle structural frame border)
- `BgWatch`: `#000000` (100% pure AMOLED Black for Wear OS battery efficiency and glare contrast)
- `AccentLime`: `#C8F04A` (Electric Lime: used deliberately on the hero Speed readout, active mode pill, route progress bar, and primary CTA fill paired with `#0B1510` dark ink text for `13.2:1` WCAG AAA contrast)
- `AccentForest`: `#1F5A3A` (secondary forest highlight fill)
- `TextPrimary`: `#F4F7F2` (Crisp Sage Off-White, `16.4:1` contrast against `#0B1510`)
- `TextMuted`: `#A9BDB0` (Muted Sage, `8.1:1` contrast against `#0B1510`, passes WCAG AA)
- `SignalHeart`: `#FB7185` (Rose: Heart Rate BPM readout only)
- `SignalConnected`: `#34D399` (Emerald: static live link indicator dot, no endless pulse)
- `SignalWarning`: `#FBBF24` (Amber: steep road grade % and signal timeout banner)

## Typography & Layout Constraints
- Web & Spectator typography uses `"Plus Jakarta Sans", -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif` with `font-variant-numeric: tabular-nums` (`fontFeatureSettings = "tnum"` in Compose) on all numeric telemetry readouts to prevent horizontal jitter at 10 Hz update rates.
- Phone Landscape (`sensorLandscape`) keeps the center 3D cyclist, teal jersey avatar, and daylight lakeside road unobstructed:
  - Top-left floating status capsule (`#0B1510` with `#C8F04A` route progress bar) and compact pacer proximity pill.
  - Bottom-left open typographic Speed (`km/h` in `#C8F04A`) + Cycling Power (`Watts`, `W/kg`, zone) + 4-column metric strip (`DISTANCE`, `TIME`, `ELEVATION`, `CADENCE / HR`).
  - Bottom-right rounded 2D Mini-Map card with live route polyline, checkpoints, pacer dots, and rider beacon.
- Galaxy Watch 4 circular layout sits inside an inscribed safe octagon (`16dp` vertical inset, `22dp` horizontal inset) on pure `#000000` AMOLED black with Electric Lime `#C8F04A` primary actions and two `48dp`-tall bottom pill buttons.
- Minimum touch target size on all interactive controls: `48dp x 48dp` (Android/Wear) and `44px` minimum height (Web).

## One-Line Decision Log (Rule R-31)
- **Why Daylight Lakeside 3D World?** Replaces dark night fog with crisp sky blue, green hillsides, stone embankment, lakeside water, and white road guardrails so indoor rides feel open and grounded like an outdoor road loop.
- **Why Deep Forest Ink (`#0B1510`) and Electric Lime (`#C8F04A`)?** High-chroma lime over deep forest ink gives instant peripheral contrast for speed and primary actions without rainbow noise.
- **Why `#0B1510` dark text on `#C8F04A` buttons?** White text on electric lime fails readability; dark forest ink on lime delivers `13.2:1` contrast across mobile and web.
- **Why typographic bottom-left overlay + bottom-right mini-map card?** Eliminates heavy symmetrical side boxes so the road, guardrails, and cyclist remain the central focal point.
- **Why tabular numerals?** Prevents 10 Hz speed, cadence, and wattage updates from shifting label alignment.
