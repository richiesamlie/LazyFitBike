# LazyFitBike

Zwift-style 3D indoor cycling application for Android Phone (`:app`) and Samsung Galaxy Watch 4 (`:wear`) with real-world online GPS maps and Strava telemetry co-op.

## Features

- **Samsung Galaxy Watch 4 Sensor App (`:wear`)**:
  - **Ankle / Crank Mode**: Uses 3-axis gyroscope and accelerometer (`CadenceEstimator`) to measure live pedal RPM cadence.
  - **Wrist Mode**: Uses optical Heart Rate sensor (`Sensor.TYPE_HEART_RATE`) to drive virtual cycling power (Watts) while hands remain on the handlebars.
  - **Dual Wireless Bridge**: Streams at 10 Hz via Google Play Services Wearable `MessageClient` (`/lazyfitbike/telemetry`) and local Wi-Fi UDP broadcast (`port 45454`).
- **Android Phone 3D Cockpit (`:app`)**:
  - **3D Virtual Cycling World & Three.js Holographic HUD**:
    - WebGL 3D world with procedural curving roads, elevation grades, 3D articulated cyclist avatar, rotating wheels, animated pedaling, low-poly pine trees, 5 AI Pacer Bots with 3D nametags, and distance milestone arches.
    - Curved 3D Fighter Jet Holographic Visor HUD parented to camera rig with dynamic 5-zone power glows, sweeping tachometer arc, cadence tachometer, pulsing cardiac waveform, elevation profile ribbons, and physics-driven camera inertia.
  - **Online GPS Maps & Route Builder**:
    - 4 preset real-world routes (*Alpe d'Huez*, *Central Park*, *Marina Bay*, *Kintamani Bali*).
    - Interactive OpenStreetMap view with 2-tap OSRM online road routing anywhere in the world.
    - `.GPX` route file import.
  - **Structured Workout & Interval Training Engine**:
    - Built-in library of 5 curated workouts (*FTP Ramp Test*, *Sweet Spot 2x20*, *30/30 VO2 Max Attacks*, *Threshold Over-Unders*, and *Active Recovery Spin*) plus native `.ZWO` (Zwift Workout XML) file import.
    - Curved Holographic 3D Visor interval timeline ribbon with real-time countdown timer, dynamic power target reticle bracket, live pacing cues, and step compliance stars.
    - Full Coggan cycling metrics (Normalized Power, Intensity Factor, Training Stress Score).
    - Bi-directional Galaxy Watch 4 interval synchronization with 3-second haptic transition pulse and low-latency audio countdown beeps.
    - Automated Ramp Test exhaustion detection with 1-tap FTP recalculation and profile/watch sync.
  - **Strava Co-op (`StravaSyncManager`)**:
    - Records 1 Hz GPS trackpoints (`lat`, `lon`, `ele`, `time`) with Garmin `TrackPointExtension v1` (`<power>`, `<gpxtpx:hr>`, `<gpxtpx:cad>`).
    - 1-Tap `.GPX` export and Android Share Sheet integration plus direct Strava v3 API upload (`POST /api/v3/uploads`).

## Build & Test

```powershell
.\gradlew.bat :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
```
