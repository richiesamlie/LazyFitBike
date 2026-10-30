# LazyFitBike Live Spectator Companion (Vercel)

A high-performance real-time companion web dashboard for **LazyFitBike** cyclists and their coaches/spectators.

## Features

- **Real-Time Telemetry HUD**: Live updates for Power (Watts), Heart Rate (BPM), Cadence (RPM), Speed (km/h), and Normalized Power (NP).
- **Coggan Power & HR Zones**: Visual color-coded zone gauges (Z1 Recovery through Z7 Neuromuscular).
- **Live Workout Interval Banner**: Displays current active workout interval step, target wattage, and remaining countdown.
- **Interactive Elevation & Track View**: Canvas rendering of the course elevation profile with animated rider beacon.
- **Real-Time Cheer Engine**: Spectators can send "Ride On! 🔔", "Sprint! ⚡", and "Allez! 🏆" cheers to the rider.
- **Offline / Demo Simulation**: Allows testing without an active rider connected.

---

## Local Development

```bash
cd spectator-web
npm install
npm run dev
```

Open [http://localhost:3000](http://localhost:3000) to view the spectator cockpit.

---

## Deploying to Vercel

### Option 1: Vercel CLI

```bash
cd spectator-web
npx vercel
```

Follow the prompts to link to your Vercel project and deploy.

### Option 2: Git Integration

Push this repository to GitHub and import the `spectator-web` root directory in the [Vercel Dashboard](https://vercel.com/new).

---

## Connecting the LazyFitBike Android App

1. In the **LazyFitBike** Android app, open the **Profile** modal.
2. In the **Live Spectator URL** field, enter your deployed endpoint:
   ```text
   https://<your-project>.vercel.app/api/telemetry
   ```
   *(Or `http://10.0.2.2:3000/api/telemetry` for the Android Emulator, or `http://<your-lan-ip>:3000/api/telemetry` on home Wi-Fi)*.
3. Tap **Save Profile**. Your ride telemetry will now stream automatically to the spectator dashboard once per second!
