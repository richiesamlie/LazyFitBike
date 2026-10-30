"use client";

import React, { useEffect, useState, useRef } from "react";

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
  1: "#71717A", // Gray - Recovery
  2: "#38BDF8", // Cyan - Endurance
  3: "#34D399", // Emerald - Tempo
  4: "#FACC15", // Amber - Sweet Spot / Threshold
  5: "#FB923C", // Orange - VO2 Max
  6: "#F87171", // Rose - Anaerobic
  7: "#C084FC"  // Purple - Neuromuscular
};

const ZONE_NAMES: Record<number, string> = {
  1: "Z1 Active Recovery",
  2: "Z2 Endurance",
  3: "Z3 Tempo",
  4: "Z4 Threshold",
  5: "Z5 VO2 Max",
  6: "Z6 Anaerobic",
  7: "Z7 Neuromuscular"
};

export default function SpectatorPage() {
  const [telemetry, setTelemetry] = useState<Telemetry | null>(null);
  const [isLive, setIsLive] = useState(false);
  const [demoMode, setDemoMode] = useState(false);
  const [cheers, setCheers] = useState<Array<{ id: string; message: string; sender: string }>>([]);
  const [cheerNotice, setCheerNotice] = useState<string | null>(null);
  const canvasRef = useRef<HTMLCanvasElement | null>(null);

  // Poll live telemetry from /api/telemetry every 1000ms
  useEffect(() => {
    let timer: NodeJS.Timeout;

    const poll = async () => {
      if (demoMode) return;
      try {
        const res = await fetch("/api/telemetry");
        if (res.ok) {
          const data = await res.json();
          setIsLive(data.live);
          if (data.telemetry) {
            setTelemetry(data.telemetry);
          }
        }
      } catch {
        // Offline or connection drop
      }
    };

    poll();
    timer = setInterval(poll, 1000);
    return () => clearInterval(timer);
  }, [demoMode]);

  // Demo simulation mode when rider is not connected
  useEffect(() => {
    if (!demoMode) return;
    setIsLive(true);

    let sec = 0;
    const interval = setInterval(() => {
      sec++;
      const baseWatts = 220 + Math.sin(sec * 0.2) * 45;
      const baseBpm = 145 + Math.sin(sec * 0.1) * 15;
      const baseRpm = 88 + Math.cos(sec * 0.3) * 6;
      const grade = 2.0 + Math.sin(sec * 0.05) * 3.5;

      setTelemetry({
        riderName: "Richie (Demo Live)",
        timestampMs: Date.now(),
        powerWatts: Math.round(baseWatts),
        heartRateBpm: Math.round(baseBpm),
        cadenceRpm: Math.round(baseRpm),
        speedKmh: 34.2 + (baseWatts - 200) * 0.06,
        distanceKm: 8.4 + sec * 0.009,
        slopeGradePercent: parseFloat(grade.toFixed(1)),
        normalizedPowerWatts: 238,
        powerZone: baseWatts > 250 ? 4 : 3,
        hrZone: baseBpm > 155 ? 4 : 3,
        caloriesKcal: 280 + Math.round(sec * 0.25),
        routeName: "Watopia Hilly Route",
        lat: -11.645 + sec * 0.0001,
        lon: 166.953 + sec * 0.0001,
        elevationMeters: 142 + Math.sin(sec * 0.1) * 35,
        workoutActive: true,
        workoutName: "Sweet Spot 2x20",
        workoutStepName: "Sweet Spot Block 1",
        workoutTargetWatts: 240,
        workoutRemainingSec: Math.max(0, 600 - sec)
      });
    }, 1000);

    return () => clearInterval(interval);
  }, [demoMode]);

  // Render elevation & course canvas
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const w = canvas.width;
    const h = canvas.height;
    ctx.clearRect(0, 0, w, h);

    // Grid lines
    ctx.strokeStyle = "#1F2937";
    ctx.lineWidth = 1;
    for (let y = 20; y < h; y += 30) {
      ctx.beginPath();
      ctx.moveTo(0, y);
      ctx.lineTo(w, y);
      ctx.stroke();
    }

    // Synthetic profile curve
    ctx.beginPath();
    ctx.strokeStyle = "#F97316";
    ctx.lineWidth = 3;
    const points: [number, number][] = [];
    for (let x = 0; x <= w; x += 10) {
      const ele = Math.sin(x * 0.02) * 25 + Math.cos(x * 0.008) * 15 + h / 2;
      points.push([x, ele]);
      if (x === 0) ctx.moveTo(x, ele);
      else ctx.lineTo(x, ele);
    }
    ctx.stroke();

    // Area fill gradient
    const grad = ctx.createLinearGradient(0, 0, 0, h);
    grad.addColorStop(0, "rgba(249, 115, 22, 0.35)");
    grad.addColorStop(1, "rgba(249, 115, 22, 0.0)");
    ctx.fillStyle = grad;
    ctx.lineTo(w, h);
    ctx.lineTo(0, h);
    ctx.closePath();
    ctx.fill();

    // Rider position beacon
    const distRatio = telemetry ? (telemetry.distanceKm % 10) / 10 : 0.45;
    const rx = distRatio * w;
    const ry = Math.sin(rx * 0.02) * 25 + Math.cos(rx * 0.008) * 15 + h / 2;

    // Glowing ring
    ctx.beginPath();
    ctx.arc(rx, ry, 9, 0, Math.PI * 2);
    ctx.fillStyle = "#34D399";
    ctx.fill();
    ctx.strokeStyle = "#FFFFFF";
    ctx.lineWidth = 2;
    ctx.stroke();

    // Rider tooltip
    ctx.fillStyle = "#09090B";
    ctx.fillRect(rx - 30, ry - 32, 60, 20);
    ctx.strokeStyle = "#34D399";
    ctx.strokeRect(rx - 30, ry - 32, 60, 20);
    ctx.fillStyle = "#34D399";
    ctx.font = "bold 10px monospace";
    ctx.textAlign = "center";
    ctx.fillText(`${telemetry?.powerWatts || 0}W`, rx, ry - 18);
  }, [telemetry]);

  const sendCheer = async (msg: string) => {
    try {
      await fetch("/api/cheer", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ sender: "Fan", message: msg })
      });
      setCheerNotice(`Sent cheer: ${msg}`);
      setTimeout(() => setCheerNotice(null), 3000);
    } catch {
      // Fallback
    }
  };

  const zoneColor = ZONE_COLORS[telemetry?.powerZone || 1] || "#71717A";
  const zoneName = ZONE_NAMES[telemetry?.powerZone || 1] || "Z1 Active Recovery";

  return (
    <main className="min-h-screen bg-[#09090B] text-zinc-100 p-4 md:p-8 flex flex-col items-center justify-start">
      <div className="w-full max-w-5xl space-y-6">
        {/* TOP BAR / BRAND HEADER */}
        <header className="flex flex-col sm:flex-row items-center justify-between gap-4 border-b border-zinc-800 pb-4">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-orange-600 flex items-center justify-center font-black text-xl text-white shadow-lg shadow-orange-500/20">
              ⚡
            </div>
            <div>
              <h1 className="text-xl md:text-2xl font-black tracking-tight flex items-center gap-2">
                LAZYFITBIKE <span className="text-orange-500 font-mono text-sm font-semibold">SPECTATOR</span>
              </h1>
              <p className="text-xs text-zinc-400">Live Telemetry &amp; Real-time Companion Stream</p>
            </div>
          </div>

          <div className="flex items-center gap-3">
            {/* Live Indicator Pill */}
            <div
              className={`flex items-center gap-2 px-3 py-1.5 rounded-full border text-xs font-bold ${
                isLive
                  ? "bg-emerald-950/80 border-emerald-500 text-emerald-400"
                  : "bg-zinc-900 border-zinc-700 text-zinc-400"
              }`}
            >
              <span
                className={`w-2.5 h-2.5 rounded-full ${
                  isLive ? "bg-emerald-400 animate-pulse" : "bg-zinc-600"
                }`}
              />
              {isLive ? "LIVE BROADCAST" : "AWAITING RIDER"}
            </div>

            {/* Demo Mode Switch */}
            <button
              onClick={() => setDemoMode(!demoMode)}
              className="text-xs px-3 py-1.5 rounded-lg border border-zinc-700 bg-zinc-900 hover:bg-zinc-800 text-zinc-300 font-medium transition"
            >
              {demoMode ? "Exit Demo" : "Test Demo"}
            </button>
          </div>
        </header>

        {/* WORKOUT INTERVAL CARD (If Workout Active) */}
        {telemetry?.workoutActive && (
          <div className="hud-pod p-4 border border-orange-500/40 bg-gradient-to-r from-orange-950/40 via-zinc-900/60 to-zinc-900/40 flex flex-wrap items-center justify-between gap-4">
            <div className="flex items-center gap-3">
              <span className="text-2xl">🔥</span>
              <div>
                <div className="text-xs text-orange-400 uppercase tracking-wider font-bold">
                  {telemetry.workoutName || "Workout Active"}
                </div>
                <div className="text-base font-black text-white">{telemetry.workoutStepName}</div>
              </div>
            </div>

            <div className="flex items-center gap-6 font-mono">
              <div>
                <div className="text-[10px] text-zinc-400 uppercase">Target</div>
                <div className="text-lg font-bold text-orange-400">{telemetry.workoutTargetWatts}W</div>
              </div>
              <div>
                <div className="text-[10px] text-zinc-400 uppercase">Remaining</div>
                <div className="text-lg font-bold text-zinc-200">
                  {Math.floor(telemetry.workoutRemainingSec / 60)}:
                  {(telemetry.workoutRemainingSec % 60).toString().padStart(2, "0")}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* PRIMARY TELEMETRY GRID (4 HUD DIALS) */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          {/* POWER POD */}
          <div
            className="hud-pod p-5 flex flex-col justify-between border-t-4 transition-all"
            style={{ borderTopColor: zoneColor }}
          >
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-zinc-400">Power</span>
              <span
                className="text-[10px] px-2 py-0.5 rounded font-black uppercase text-black"
                style={{ backgroundColor: zoneColor }}
              >
                {zoneName.split(" ")[0]}
              </span>
            </div>
            <div className="my-3">
              <div className="text-5xl md:text-6xl font-black tracking-tight tabular-nums text-white">
                {telemetry?.powerWatts ?? 0}
              </div>
              <div className="text-xs font-mono text-zinc-400 mt-1">
                NP: <span className="text-zinc-200 font-bold">{telemetry?.normalizedPowerWatts ?? 0}W</span>
              </div>
            </div>
            <div className="text-[11px] text-zinc-400 font-medium truncate">{zoneName}</div>
          </div>

          {/* HEART RATE POD */}
          <div className="hud-pod p-5 flex flex-col justify-between border-t-4 border-t-rose-500">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-zinc-400">Heart Rate</span>
              <span className="text-xs">❤️</span>
            </div>
            <div className="my-3">
              <div className="text-5xl md:text-6xl font-black tracking-tight tabular-nums text-rose-400">
                {telemetry?.heartRateBpm ?? 0}
              </div>
              <div className="text-xs font-mono text-zinc-400 mt-1">BPM</div>
            </div>
            <div className="text-[11px] text-zinc-400 font-medium">Zone {telemetry?.hrZone ?? 1} Cardiac Load</div>
          </div>

          {/* CADENCE POD */}
          <div className="hud-pod p-5 flex flex-col justify-between border-t-4 border-t-emerald-400">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-zinc-400">Cadence</span>
              <span className="text-xs">🔄</span>
            </div>
            <div className="my-3">
              <div className="text-5xl md:text-6xl font-black tracking-tight tabular-nums text-emerald-400">
                {telemetry?.cadenceRpm ?? 0}
              </div>
              <div className="text-xs font-mono text-zinc-400 mt-1">RPM</div>
            </div>
            <div className="text-[11px] text-zinc-400 font-medium">
              {telemetry && telemetry.cadenceRpm >= 85 && telemetry.cadenceRpm <= 95
                ? "Optimal cadence"
                : "Pedaling rhythm"}
            </div>
          </div>

          {/* SPEED & DISTANCE POD */}
          <div className="hud-pod p-5 flex flex-col justify-between border-t-4 border-t-orange-500">
            <div className="flex items-center justify-between">
              <span className="text-xs font-bold uppercase tracking-wider text-zinc-400">Speed</span>
              <span className="text-xs">🏁</span>
            </div>
            <div className="my-3">
              <div className="text-5xl md:text-6xl font-black tracking-tight tabular-nums text-white">
                {telemetry?.speedKmh?.toFixed(1) ?? "0.0"}
              </div>
              <div className="text-xs font-mono text-zinc-400 mt-1">KM/H</div>
            </div>
            <div className="text-[11px] text-zinc-400 font-mono">
              {telemetry?.distanceKm?.toFixed(2) ?? "0.00"} km · {telemetry?.caloriesKcal ?? 0} kcal
            </div>
          </div>
        </div>

        {/* 3D/2D COURSE & ELEVATION VIEW */}
        <div className="hud-pod p-5 space-y-3">
          <div className="flex items-center justify-between text-xs">
            <div className="flex items-center gap-2">
              <span className="font-bold uppercase tracking-wider text-zinc-300">Course Track &amp; Elevation</span>
              <span className="px-2 py-0.5 rounded bg-zinc-800 text-orange-400 font-mono font-bold">
                {telemetry?.routeName || "Watopia Course"}
              </span>
            </div>
            <div className="font-mono text-zinc-400 flex items-center gap-3">
              <span>Grade: <strong className="text-white">{telemetry?.slopeGradePercent ?? 0}%</strong></span>
              <span>Ele: <strong className="text-white">{telemetry?.elevationMeters?.toFixed(0) ?? 0}m</strong></span>
            </div>
          </div>

          <div className="relative w-full h-44 bg-zinc-950/80 rounded-lg overflow-hidden border border-zinc-800">
            <canvas ref={canvasRef} width={900} height={176} className="w-full h-full block" />
          </div>
        </div>

        {/* SPECTATOR CHEER / INTERACTIVE POD */}
        <div className="hud-pod p-5 flex flex-col sm:flex-row items-center justify-between gap-4">
          <div>
            <div className="text-sm font-bold text-white flex items-center gap-2">
              <span>📣</span> Send Real-time Cheer to Rider
            </div>
            <p className="text-xs text-zinc-400">Send instant encouragement directly to the athlete&apos;s HUD.</p>
          </div>

          <div className="flex items-center gap-2 flex-wrap">
            <button
              onClick={() => sendCheer("Ride On! 🚴‍♂️🔔")}
              className="px-3.5 py-2 rounded-lg bg-orange-600 hover:bg-orange-500 font-bold text-xs text-white transition flex items-center gap-1.5 shadow-md shadow-orange-600/20"
            >
              <span>🔔</span> Ride On!
            </button>
            <button
              onClick={() => sendCheer("Sprint for the line! ⚡")}
              className="px-3.5 py-2 rounded-lg bg-zinc-800 hover:bg-zinc-700 font-bold text-xs text-zinc-200 border border-zinc-700 transition flex items-center gap-1.5"
            >
              <span>⚡</span> Sprint!
            </button>
            <button
              onClick={() => sendCheer("Allez Allez! 🏆")}
              className="px-3.5 py-2 rounded-lg bg-zinc-800 hover:bg-zinc-700 font-bold text-xs text-zinc-200 border border-zinc-700 transition flex items-center gap-1.5"
            >
              <span>🏆</span> Allez!
            </button>
          </div>
        </div>

        {cheerNotice && (
          <div className="p-3 bg-emerald-950 border border-emerald-500/60 rounded-lg text-emerald-300 text-xs text-center font-bold animate-fade-in">
            {cheerNotice}
          </div>
        )}

        {/* SETUP INSTRUCTIONS FOOTER */}
        <div className="p-4 rounded-xl border border-zinc-800/80 bg-zinc-900/30 text-xs text-zinc-400 space-y-1">
          <div className="font-bold text-zinc-300">How to Connect LazyFitBike App:</div>
          <p>
            1. In LazyFitBike Android App, tap <strong>Profile</strong> &rarr; enter this spectator URL in{" "}
            <strong>Live Spectator URL</strong>.
          </p>
          <p>
            2. When deployed to Vercel, use your production endpoint:{" "}
            <code className="text-orange-400 bg-zinc-950 px-1.5 py-0.5 rounded font-mono">
              https://&lt;your-project&gt;.vercel.app/api/telemetry
            </code>
          </p>
        </div>
      </div>
    </main>
  );
}
