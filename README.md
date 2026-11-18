# LazyFitBike

LazyFitBike is a 3-part indoor cycling system built for an Android phone cockpit (`:app`), a Samsung Galaxy Watch 4 sensor companion (`:wear`), and a Next.js live spectator web dashboard (`spectator-web`). It pairs live watch cadence or heart-rate telemetry with a daylight Three.js 3D cycling world, real-world GPS routes, structured interval training, and external cloud sync.

The visual system across all three targets follows the LangkahFit-inspired Deep Forest Ink (`#0B1510`) and Electric Lime (`#C8F04A`) palette documented in [DESIGN.md](DESIGN.md).

- [Latest APK Releases (Phone + Galaxy Watch 4)](https://github.com/richiesamlie/LazyFitBike/releases/latest)
- [Design System Specification](DESIGN.md)
- [Live Spectator Web Setup](spectator-web/README.md)

## System Architecture

| Module | Platform & Stack | SDK / Runtime | Responsibility |
| :--- | :--- | :--- | :--- |
| `:app` | Android (Kotlin, Jetpack Compose, Three.js WebGL) | `minSdk 26`, `targetSdk 36` | 3D cycling world, physics engine, GPS map & route builder, structured workouts, cloud sync, OTA relay |
| `:wear` | Wear OS (Kotlin, Compose, Samsung Health Sensor SDK 1.4.1) | `minSdk 30`, `targetSdk 36` | 10 Hz ankle gyro cadence estimation, optical heart rate tracking, haptics, Bluetooth & Wi-Fi UDP streaming |
| `spectator-web` | Web (Next.js 14, React 18, TypeScript) | Node.js / Vercel | Live 1 Hz telemetry cockpit, zone gauges, elevation track canvas, and spectator cheer engine |

## Android Phone 3D Cockpit (`:app`)

### Daylight Lakeside 3D World & Physics
- **3D WebGL Course (`assets/world3d.html`)**: Renders a daylight lakeside loop with a 360-degree mountain and cloud sky dome, continuous 80-segment road and guardrail ribbons, stone embankments, lakeside water, roadside pines, and distance milestone arches.
- **Articulated Cyclist & Cameras**: Models a rider avatar with inverse-kinematics (IK) forward crank pedaling, speed-synced wheel rotation, and grade/curvature road banking across 4 view modes (`CHASE`, `POV`, `DRONE`, `MAP`).
- **AI Pacer Bots & Drafting (`RideViewModel.kt`, `CyclingPhysics.kt`)**: Populates the road with 5 pacer bots (`Bot A` at `1.8 W/kg`, `Bot B` at `2.2 W/kg`, `Bot C` at `2.5 W/kg`, `Bot D` at `3.0 W/kg`, and `Bot E` at `3.6 W/kg`). Riding `1.0 m` to `8.5 m` behind a bot above `12 km/h` activates slipstream drafting (`0.75x` aerodynamic drag factor).
- **Road Cycling Physics (`CyclingPhysics.kt`)**: Converts either watch Ankle RPM or Wrist Heart Rate BPM into estimated Watts, `W/kg`, speed (`km/h`), elevation gain, slope grade (`%`), and active calories using rider weight, bike weight, FTP, resting HR, and max HR (including Tanaka `208 - 0.7 * age` estimation).
- **Open-Center Floating HUD (`MainActivity.kt`, `world3d.html`)**: Locks the phone in `sensorLandscape` with a top-left route progress capsule and pacer proximity pill, a bottom-left speed and power readout plus 4-column metric strip (`DISTANCE`, `TIME`, `CADENCE`, `HEART RATE`), an expandable bottom control `Menu` with a virtual 12-speed `GEAR` shifter, and a bottom-right 2D mini-map card.

### Online GPS Maps & Route Builder (`GpsRouteEngine.kt`, `OsmTileProxy`)
- **4 Preset Real-World Loops**: *Alpe d'Huez Climb* (French Alps), *Central Park Full Loop* (New York City), *Marina Bay Circuit* (Singapore), and *Kintamani Highland Loop* (Bali).
- **CyclOSM & OpenTopoMap Caching (`OsmTileProxy`)**: Serves interactive bicycle map tiles through an in-app disk and memory cache (`map_tiles_v3`) backed by CyclOSM and OpenTopoMap endpoints.
- **2-Tap Custom Route Builder**: Tap two points on the interactive map to generate a road route with loading and error/retry states. Queries **Google Maps Routes API v2** (`travelMode=BICYCLE`) and **Google Maps Elevation API** (`solution_id=gmp_git_agentskills_v1`) when an API key is set, and falls back to **OSRM** online road routing.
- **Live Location & `.GPX` Import**: Supports `Locate GPS` / `My GPS` hardware positioning (with network IP geolocation fallback indoors) and importing external `.GPX` track files.

### Structured Workouts & Gemini AI Coach (`WorkoutEngine.kt`, `GeminiCoachEngine.kt`)
- **Workout Library & `.ZWO` Import**: Includes 5 built-in workouts (*FTP Ramp Test*, *Sweet Spot 2x20 min*, *30/30 VO2 Max Attacks*, *Threshold Over-Unders*, and *Active Recovery Spin*) plus `.ZWO` (Zwift Workout XML) file import.
- **Live Interval Execution**: Displays active interval countdowns, target wattage and cadence guidance, `+/- 5%` intensity bias controls (up to `+/- 50%`), low-latency audio countdown beeps (`WorkoutTonePlayer.kt`), step compliance stars, and Coggan metrics (Normalized Power, Intensity Factor, and Training Stress Score).
- **Automated Ramp Test Detection**: Detects exhaustion during the *FTP Ramp Test* and updates rider FTP on the phone and synced watch profile.
- **Gemini AI Coach (`GeminiCoachEngine.kt`)**: Produces live pacing cues for cadence grinding, cardiac drift, recovery ceiling overshoot, and drafting; turns natural-language prompts into structured Coggan intervals; and generates a post-ride physiological summary.

### Cloud Sync, Social Cards, & OTA Updates
- **Strava & `.GPX` Recording (`StravaSyncManager.kt`)**: Records 1 Hz GPS trackpoints (`lat`, `lon`, `ele`, `time`) with Garmin `TrackPointExtension v1` (`<power>`, `<gpxtpx:hr>`, `<gpxtpx:cad>`) for direct upload to Strava (`POST /api/v3/uploads`) or Android Share Sheet export.
- **Google Workspace Sync**: Schedules structured workouts into **Google Calendar** (`GoogleCalendarSyncManager.kt`), formats RFC 4180 `.CSV` and `.TSV` telemetry for **Google Sheets** (`GoogleSheetsSyncManager.kt`), and exports `.gpx`, `.csv`, and `.zwo` backups to **Google Drive** (`GoogleDriveSyncManager.kt`).
- **Social Story Cards (`CanvaShareCardGenerator.kt`)**: Renders `1080x1920` (9:16) ride summary graphics with Normalized Power, TSS, Intensity Factor, ride stats, and elevation profile for Instagram, Strava, or Canva.
- **Firebase Integration (`FirebaseConfig.kt`)**: Supports Remote Config physics overrides (`cda_aero`, `crr_rolling`, `air_density_rho`, `drafting_factor`, `trainer_difficulty`), Ghost Rider JSON serialization for Cloud Firestore, and Crashlytics diagnostic breadcrumbs.
- **In-App GitHub OTA Updater (`GithubUpdater.kt`)**: Inspects `richiesamlie/LazyFitBike` GitHub Releases, downloads and installs Phone APKs, and relays Watch APKs over Bluetooth `ChannelClient` (`/lazyfitbike/watch_apk_update`).

## Samsung Galaxy Watch 4 Companion (`:wear`)

- **Ankle / Crank Mode (`CadenceEstimator.kt`)**: Estimates pedal cadence (`35` to `160 RPM`) from 3-axis gyroscope samples by combining mean angular velocity with dominant-axis oscillation cycle timing, dropping to `0 RPM` via a `1.5 s` deadband gate when pedaling stops.
- **Wrist Mode (`HeartRateTrackerManager.kt`)**: Multi-source heart rate tracker fusing **Samsung Health Sensor SDK 1.4.1** (`HEART_RATE_CONTINUOUS` with IBI median fallback), **Wear OS Health Services** (`MeasureClient`), and Android `SensorManager` (`TYPE_HEART_RATE`), supporting both Android 16 (`READ_HEART_RATE`) and `BODY_SENSORS` permissions.
- **10 Hz Foreground Streaming (`SensorWorkoutService.kt`, `TelemetrySender.kt`)**: Maintains a foreground service wake lock and streams telemetry at `10 Hz` over Google Play Services Wearable `MessageClient` (`/lazyfitbike/telemetry`) and a local Wi-Fi UDP broadcast fallback (`port 45454`).
- **Circular AMOLED UI & Bi-Directional Control (`wear/MainActivity.kt`, `WearOtaListenerService.kt`)**: Fits controls inside an inscribed safe octagon on pure `#000000` AMOLED black with a Stealth AMOLED Eco toggle, 5-zone color indicators, 3-second haptic interval transition alerts, and standalone or Bluetooth-relayed OTA updates (`WatchGithubUpdater.kt`).

## Live Spectator Web Dashboard (`spectator-web/`)

- **1 Hz Telemetry Receiver (`app/api/telemetry/route.ts`)**: Accepts JSON telemetry bursts (`POST /api/telemetry`) from `TelemetryBroadcaster.kt` on the phone app.
- **Spectator HUD (`app/page.tsx`)**: Displays Speed (`km/h`), Power (`W` and Normalized Power), a 5-zone color badge (`Z1 Recovery` through `Z5 VO2 Max`), Distance (`km`), Cadence (`RPM`), Heart Rate (`BPM`), Elevation (`m`), Grade (`%`), active workout step targets/countdowns, and a canvas elevation profile.
- **Cheer Engine (`app/api/cheer/route.ts`)**: Lets viewers send cheers (`Ride On`, `Hold Cadence`, `Attack the Climb`) that appear in the rider's phone HUD banner.
- **Standalone Demo Mode**: Includes a `Simulate Ride` control to preview telemetry and cheers without an active rider connected.

## Repository Structure

```text
LazyFitBike/
├── .github/workflows/release-apk.yml   # CI workflow building & publishing Phone/Watch APKs
├── app/                                # Android phone 3D cockpit module (:app)
│   └── src/main/assets/world3d.html    # Three.js daylight world, IK cyclist, and CyclOSM map
├── wear/                               # Galaxy Watch 4 Wear OS companion module (:wear)
├── spectator-web/                      # Next.js 14 live spectator web app (Vercel-ready)
├── DESIGN.md                           # LangkahFit-inspired Deep Forest Ink + Electric Lime spec
├── gradle.properties                   # Single Source of Truth (appVersionCode, appVersionName)
└── build.gradle.kts                    # Root Gradle config and version bump tasks
```

## Build, Test, & Release

### Android Phone & Galaxy Watch 4
Run unit tests and build debug APKs for both modules:

```powershell
.\gradlew.bat :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
```

Build R8-minified release APKs (configured via `gradle.properties` and `keystore/` or environment secrets):

```powershell
.\gradlew.bat :app:assembleRelease :wear:assembleRelease
```

Manage the Single Source of Truth version in `gradle.properties` (`appVersionCode` and `appVersionName`):

```powershell
.\gradlew.bat printVersion
.\gradlew.bat bumpPatch   # X.Y.Z -> X.Y.Z+1
.\gradlew.bat bumpMinor   # X.Y.Z -> X.Y+1.0
.\gradlew.bat bumpMajor   # X.Y.Z -> X+1.0.0
```

### Live Spectator Web (`spectator-web/`)
Run locally on `http://localhost:3000`:

```bash
cd spectator-web
npm install
npm run dev
```

Create a production Next.js build or deploy to Vercel:

```bash
cd spectator-web
npm run build
npx vercel
```

### Automated GitHub Releases
Pushing to `main`, pushing a `v*` tag, or dispatching `.github/workflows/release-apk.yml` runs `:app:testDebugUnitTest`, `:wear:testDebugUnitTest`, `:app:assembleRelease`, and `:wear:assembleRelease`, then uploads `LazyFitBike-phone.apk` and `LazyFitBike-watch.apk` to the matching GitHub Release.
