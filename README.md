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
    - **Google Maps Platform Integration**:
      - Real topographical elevation sampling via Google Maps Elevation API (`solution_id=gmp_git_agentskills_v1`), replacing synthetic elevations with true mountain gradients.
      - Bike-safe road routing via Google Maps Routes API v2 (`travelMode=BICYCLE`).
      - High-performance Google polyline decoding.
    - Interactive OpenStreetMap view with 2-tap OSRM online road routing fallback.
    - `.GPX` route file import.
  - **Structured Workout & Interval Training Engine with Gemini AI Coach**:
    - Built-in library of 5 curated workouts (*FTP Ramp Test*, *Sweet Spot 2x20*, *30/30 VO2 Max Attacks*, *Threshold Over-Unders*, and *Active Recovery Spin*) plus native `.ZWO` (Zwift Workout XML) file import.
    - **Gemini AI Cycling Coach (`GeminiCoachEngine`)**:
      - Context-aware real-time pacing cues analyzing live cadence grinding, cardiac drift, interval countdowns, and drafting bonuses.
      - Conversational AI workout generator: translates natural language prompts (*"45 min sweet spot climbing session"*, *"30 min VO2 max attacks"*) into structured Coggan steps.
      - Post-ride physiological debrief analyzing normalized power, intensity factor, and glycogen depletion/recovery recommendations.
    - Curved Holographic 3D Visor interval timeline ribbon with real-time countdown timer, dynamic power target reticle bracket, live pacing cues, and step compliance stars.
    - Full Coggan cycling metrics (Normalized Power, Intensity Factor, Training Stress Score).
    - Bi-directional Galaxy Watch 4 interval synchronization with 3-second haptic transition pulse and low-latency audio countdown beeps.
    - Automated Ramp Test exhaustion detection with 1-tap FTP recalculation and profile/watch sync.
  - **Cloud Analytics & Workspace Sync (`GoogleSheetsSyncManager`, `GoogleCalendarSyncManager`, `FirebaseConfig`, & `StravaSyncManager`)**:
    - **Google Calendar Training Schedule**: 1-Tap scheduling of structured training blocks directly into Google Calendar with step-by-step target wattages and cadence guidelines.
    - **Google Sheets Telemetry Hub**: 1-Tap export of full training metrics to RFC 4180 CSV and TSV clipboard snippet for tracking long-term training load (CTL/ATL).
    - **Firebase Remote Config & Ghost Riders (`FirebaseConfig`)**:
      - Over-the-air physics tuning for aerodynamic drag (`CdA`), rolling resistance (`Crr`), and trainer resistance curves without APK redeployment.
      - Asynchronous Ghost Rider recording and serialization for Cloud Firestore leaderboard pacing in the 3D cockpit.
      - Crashlytics breadcrumbs and non-fatal logging for WebGL and Wear OS Bluetooth disconnects.
    - Records 1 Hz GPS trackpoints (`lat`, `lon`, `ele`, `time`) with Garmin `TrackPointExtension v1` (`<power>`, `<gpxtpx:hr>`, `<gpxtpx:cad>`).
    - 1-Tap `.GPX` export and Android Share Sheet integration plus direct Strava v3 API upload (`POST /api/v3/uploads`).

## Build & Test

```powershell
.\gradlew.bat :app:testDebugUnitTest :wear:testDebugUnitTest :app:assembleDebug :wear:assembleDebug
```
