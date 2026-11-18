"use client";

import React, { useEffect, useRef, useState } from "react";

interface Telemetry {
  riderName: string;
  timestampMs: number;
  powerWatts: number;
  heartRateBpm: number;
  cadenceRpm: number;
  speedKmh: number;
  distanceKm: number;
  slopeGradePercent: number;
  normalizedPowerWatts: number;
  powerZone: number;
  hrZone: number;
  caloriesKcal: number;
  routeName: string;
  lat: number;
  lon: number;
  elevationMeters: number;
  workoutActive: boolean;
  workoutName: string;
  workoutStepName: string;
  workoutTargetWatts: number;
  workoutRemainingSec: number;
}

const ZONE_COLORS: Record<number, string> = {
  1: "#94A3B8",
  2: "#38BDF8",
  3: "#34D399",
  4: "#FBBF24",
  5: "#FB7185"
};

const ZONE_NAMES: Record<number, string> = {
  1: "Z1 Recovery",
  2: "Z2 Endurance",
  3: "Z3 Tempo",
  4: "Z4 Threshold",
  5: "Z5 VO2 Max"
};

export default function SpectatorPage() {
  const [telemetry, setTelemetry] = useState<Telemetry | null>(null);
  const [isLive, setIsLive] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [demoMode, setDemoMode] = useState(false);
  const [cheerNotice, setCheerNotice] = useState<{ text: string; isError: boolean } | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  useEffect(() => {
    let active = true;

    const poll = async () => {
      if (demoMode) return;
      try {
        const res = await fetch("/api/telemetry", { cache: "no-store" });
        if (!res.ok) {
          throw new Error(`HTTP ${res.status}`);
        }
        const data = await res.json();
        if (!active) return;
        setFetchError(null);
        setIsLive(Boolean(data.live));
        if (data.telemetry) {
          setTelemetry(data.telemetry);
        }
      } catch {
        if (!active) return;
        setFetchError("Telemetry endpoint unreachable. Check your server connection or preview with Simulate Ride.");
      } finally {
        if (active) setIsLoading(false);
      }
    };

    poll();
    const timer = setInterval(poll, 1000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [demoMode]);

  useEffect(() => {
    if (!demoMode) return;
    setIsLoading(false);
    setFetchError(null);
    setIsLive(true);

    let sec = 0;
    const emitDemoSample = () => {
      sec++;
      const baseWatts = 225 + Math.sin(sec * 0.2) * 42;
      const baseBpm = 148 + Math.sin(sec * 0.1) * 12;
      const baseRpm = 88 + Math.cos(sec * 0.3) * 5;
      const grade = 2.4 + Math.sin(sec * 0.05) * 3.2;

      setTelemetry({
        riderName: "LazyFit Cyclist (Simulated)",
        timestampMs: Date.now(),
        powerWatts: Math.round(baseWatts),
        heartRateBpm: Math.round(baseBpm),
        cadenceRpm: Math.round(baseRpm),
        speedKmh: 34.4 + (baseWatts - 200) * 0.06,
        distanceKm: 6.2 + sec * 0.0095,
        slopeGradePercent: parseFloat(grade.toFixed(1)),
        normalizedPowerWatts: 234,
        powerZone: baseWatts > 250 ? 4 : 3,
        hrZone: baseBpm > 155 ? 4 : 3,
        caloriesKcal: 245 + Math.round(sec * 0.25),
        routeName: "Kintamani Bali Climb",
        lat: -8.282,
        lon: 115.367,
        elevationMeters: 1140 + Math.sin(sec * 0.1) * 38,
        workoutActive: true,
        workoutName: "Sweet Spot 2x20",
        workoutStepName: "Sweet Spot Block 1",
        workoutTargetWatts: 235,
        workoutRemainingSec: Math.max(0, 600 - sec)
      });
    };

    emitDemoSample();
    const interval = setInterval(emitDemoSample, 1000);
    return () => clearInterval(interval);
  }, [demoMode]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    // Subtle elevation baseline ticks
    ctx.strokeStyle = "rgba(30, 51, 40, 0.7)";
    ctx.lineWidth = 1;
    ctx.beginPath();
    ctx.moveTo(0, h - 28);
    ctx.lineTo(w, h - 28);
    ctx.stroke();

    // Topographical profile curve
    ctx.beginPath();
    ctx.strokeStyle = "#C8F04A";
    ctx.lineWidth = 3;
    for (let x = 0; x <= w; x += 10) {
      const ele = Math.sin(x * 0.018) * 26 + Math.cos(x * 0.007) * 18 + h * 0.52;
      if (x === 0) ctx.moveTo(x, ele);
      else ctx.lineTo(x, ele);
    }
    ctx.stroke();

    // Forest gradient fill beneath elevation curve
    const grad = ctx.createLinearGradient(0, 0, 0, h);
    grad.addColorStop(0, "rgba(200, 240, 74, 0.28)");
    grad.addColorStop(1, "rgba(31, 90, 58, 0.04)");
    ctx.fillStyle = grad;
    ctx.lineTo(w, h);
    ctx.lineTo(0, h);
    ctx.closePath();
    ctx.fill();

    // Live rider beacon
    const distRatio = telemetry ? (telemetry.distanceKm % 10) / 10 : 0.4;
    const rx = Math.max(36, Math.min(w - 36, distRatio * w));
    const ry = Math.sin(rx * 0.018) * 26 + Math.cos(rx * 0.007) * 18 + h * 0.52;

    ctx.beginPath();
    ctx.arc(rx, ry, 8, 0, Math.PI * 2);
    ctx.fillStyle = "#34D399";
    ctx.fill();
    ctx.strokeStyle = "#0B1510";
    ctx.lineWidth = 2.5;
    ctx.stroke();

    // Rider wattage pill on chart
    const pillW = 68;
    const pillH = 22;
    const pillY = Math.max(10, ry - 34);
    ctx.fillStyle = "#13221A";
    ctx.fillRect(rx - pillW / 2, pillY, pillW, pillH);
    ctx.strokeStyle = "#C8F04A";
    ctx.lineWidth = 1.5;
    ctx.strokeRect(rx - pillW / 2, pillY, pillW, pillH);
    ctx.fillStyle = "#C8F04A";
    ctx.font = "bold 11px sans-serif";
    ctx.textAlign = "center";
    ctx.fillText(`${telemetry?.powerWatts ?? 0} W`, rx, pillY + 15);
  }, [telemetry]);

  const sendCheer = async (msg: string) => {
    try {
      const res = await fetch("/api/cheer", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "Spectator", message: msg })
      });
      if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
      }
      setCheerNotice({ text: `Sent cheer to rider: ${msg}`, isError: false });
      setTimeout(() => setCheerNotice(null), 3000);
    } catch {
      setCheerNotice({ text: "Could not deliver cheer. Verify /api/cheer is reachable.", isError: true });
    }
  };

  const toggleSimulation = () => {
    if (demoMode) {
      setDemoMode(false);
      setIsLive(false);
      setTelemetry(null);
    } else {
      setDemoMode(true);
    }
  };

  const zoneColor = ZONE_COLORS[telemetry?.powerZone || 1] || "#94A3B8";
  const zoneName = ZONE_NAMES[telemetry?.powerZone || 1] || "Z1 Recovery";

  return (
    <main className="spectator-shell">
      <div className="spectator-container">
        <header className="spectator-header">
          <div className="brand-group">
            <div className="brand-mark" aria-hidden="true">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none">
                <circle cx="6.5" cy="16.5" r="3.5" stroke="#0B1510" strokeWidth="2.2" />
                <circle cx="17.5" cy="16.5" r="3.5" stroke="#0B1510" strokeWidth="2.2" />
                <path
                  d="M6.5 16.5L10.5 8.5H15L17.5 16.5M10.5 8.5L12.5 13.5H17.5"
                  stroke="#0B1510"
                  strokeWidth="2.2"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                />
              </svg>
            </div>
            <div>
              <h1 className="brand-title">
                LazyFitBike
                <span className="brand-tag">Spectator</span>
              </h1>
              <p className="brand-subtitle">
                Live cycling telemetry, course elevation profile, and rider cheer channel
              </p>
            </div>
          </div>

          <div className="header-actions">
            <div className={`status-pill ${isLive ? "status-pill-live" : ""}`}>
              <span className={`status-dot ${isLive ? "status-dot-live" : ""}`} />
              {isLive ? "LIVE STREAM" : "AWAITING RIDER"}
            </div>

            <button
              type="button"
              onClick={toggleSimulation}
              className={demoMode ? "btn-surface" : "btn-lime"}
            >
              {demoMode ? "Stop Simulation" : "Simulate Ride"}
            </button>
          </div>
        </header>

        {isLoading && !telemetry && (
          <section className="hud-pod state-banner" aria-live="polite">
            <div>
              <div className="state-title">Connecting to telemetry stream...</div>
              <p className="state-desc">
                Checking /api/telemetry for an active LazyFitBike rider session.
              </p>
            </div>
          </section>
        )}

        {fetchError && !demoMode && (
          <section className="hud-pod state-banner state-banner-error" role="alert">
            <div>
              <div className="state-title">Stream Connection Unavailable</div>
              <p className="state-desc">{fetchError}</p>
            </div>
            <button type="button" onClick={() => setDemoMode(true)} className="btn-lime">
              Simulate Ride
            </button>
          </section>
        )}

        {!isLoading && !fetchError && !isLive && !telemetry && !demoMode && (
          <section className="hud-pod state-banner">
            <div>
              <div className="state-title">No active bike stream connected</div>
              <p className="state-desc">
                Open Profile in the LazyFitBike Android app and set your Live Spectator URL, or start a live preview simulation.
              </p>
            </div>
            <button type="button" onClick={() => setDemoMode(true)} className="btn-lime">
              Simulate Ride
            </button>
          </section>
        )}

        <section className="cockpit-grid">
          {/* LEFT FOCAL TELEMETRY PANEL */}
          <div className="hud-pod hero-panel">
            <div>
              <div className="eyebrow-row">
                <span className="rider-label">
                  {telemetry?.riderName || "LazyFit Cyclist"}
                </span>
                <span className="zone-badge" style={{ backgroundColor: zoneColor }}>
                  {zoneName}
                </span>
              </div>

              <div className="hero-metrics-pair" style={{ marginTop: "1rem" }}>
                <div>
                  <span className="speed-figure tabular-nums">
                    {telemetry ? telemetry.speedKmh.toFixed(1) : "0.0"}
                  </span>
                  <span className="speed-unit">km/h</span>
                </div>

                <div>
                  <span className="power-figure tabular-nums">
                    {telemetry?.powerWatts ?? 0}
                  </span>
                  <span className="power-unit">W</span>
                  <div className="power-sub tabular-nums">
                    Normalized Power: <strong>{telemetry?.normalizedPowerWatts ?? 0} W</strong>
                  </div>
                </div>
              </div>
            </div>

            {telemetry?.workoutActive ? (
              <div className="workout-strip">
                <div>
                  <div className="workout-name">{telemetry.workoutName || "Structured Workout"}</div>
                  <div className="workout-step">{telemetry.workoutStepName}</div>
                </div>
                <div className="workout-targets tabular-nums">
                  <div>
                    <div className="target-label">Target</div>
                    <div className="target-val">{telemetry.workoutTargetWatts} W</div>
                  </div>
                  <div>
                    <div className="target-label">Remaining</div>
                    <div className="target-val">
                      {Math.floor(telemetry.workoutRemainingSec / 60)}:
                      {(telemetry.workoutRemainingSec % 60).toString().padStart(2, "0")}
                    </div>
                  </div>
                </div>
              </div>
            ) : (
              <div className="workout-strip">
                <div>
                  <div className="workout-name">Free Ride Mode</div>
                  <div className="workout-step">{telemetry?.routeName || "Select a route in app"}</div>
                </div>
                <div className="workout-targets tabular-nums">
                  <div>
                    <div className="target-label">Energy</div>
                    <div className="target-val">{telemetry?.caloriesKcal ?? 0} kcal</div>
                  </div>
                </div>
              </div>
            )}
          </div>

          {/* RIGHT COURSE ELEVATION & VITALS STAGE */}
          <div className="hud-pod course-panel">
            <div className="course-header">
              <span className="course-title">Course Elevation &amp; Rider Beacon</span>
              <span className="route-badge">
                {telemetry?.routeName || "Awaiting Route"}
              </span>
            </div>

            <div className="elevation-frame">
              <canvas
                ref={canvasRef}
                width={640}
                height={196}
                className="elevation-canvas"
                aria-label="Course elevation profile chart"
              />
            </div>

            <div className="secondary-strip tabular-nums">
              <div className="mini-stat">
                <div className="mini-label">Distance</div>
                <div className="mini-value">
                  {telemetry ? telemetry.distanceKm.toFixed(2) : "0.00"} km
                </div>
              </div>
              <div className="mini-stat">
                <div className="mini-label">Cadence</div>
                <div className="mini-value mini-value-cadence">
                  {telemetry?.cadenceRpm ?? 0} RPM
                </div>
              </div>
              <div className="mini-stat">
                <div className="mini-label">Heart Rate</div>
                <div className="mini-value mini-value-hr">
                  {telemetry?.heartRateBpm ?? 0} BPM
                </div>
              </div>
              <div className="mini-stat">
                <div className="mini-label">Elevation</div>
                <div className="mini-value">
                  {telemetry ? telemetry.elevationMeters.toFixed(0) : "0"} m
                </div>
              </div>
              <div className="mini-stat">
                <div className="mini-label">Grade</div>
                <div className="mini-value">
                  {telemetry ? `${telemetry.slopeGradePercent > 0 ? "+" : ""}${telemetry.slopeGradePercent.toFixed(1)}` : "0.0"}%
                </div>
              </div>
            </div>
          </div>
        </section>

        {/* CHEER INTERACTION BAR */}
        <section className="hud-pod cheer-bar">
          <div>
            <div className="cheer-title">Send Live Rider Cheer</div>
            <p className="cheer-subtitle">
              Encouragements appear directly inside the rider&apos;s cockpit banner.
            </p>
          </div>
          <div className="cheer-actions">
            <button type="button" onClick={() => sendCheer("Ride On!")} className="btn-lime">
              Ride On
            </button>
            <button type="button" onClick={() => sendCheer("Hold Cadence!")} className="btn-surface">
              Hold Cadence
            </button>
            <button type="button" onClick={() => sendCheer("Attack the Climb!")} className="btn-surface">
              Attack the Climb
            </button>
          </div>
        </section>

        {cheerNotice && (
          <div
            className={`notice-bar ${cheerNotice.isError ? "notice-error" : ""}`}
            role="status"
            aria-live="polite"
          >
            {cheerNotice.text}
          </div>
        )}

        <footer className="setup-footer">
          <div className="setup-heading">Connect LazyFitBike Android Cockpit</div>
          <p>
            1. Open <strong>Profile</strong> in the LazyFitBike Android app and set{" "}
            <strong>Live Spectator URL</strong> to{" "}
            <code className="endpoint-code">https://&lt;your-project&gt;.vercel.app/api/telemetry</code>.
          </p>
          <p>
            2. Save profile to stream power, speed, cadence, heart rate, and route coordinates every second.
          </p>
        </footer>
      </div>
    </main>
  );
}
