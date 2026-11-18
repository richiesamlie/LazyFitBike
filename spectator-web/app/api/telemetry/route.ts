import { NextRequest, NextResponse } from "next/server";
import {
  readTelemetryState,
  SpectatorTelemetry,
  writeTelemetrySample
} from "@/lib/spectator-store";

export const dynamic = "force-dynamic";

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, User-Agent, X-LazyFit-Device"
};

const NO_STORE_HEADERS = {
  ...CORS_HEADERS,
  "Cache-Control": "no-cache, no-store, must-revalidate"
};

function finiteNum(value: unknown, fallback = 0): number {
  return typeof value === "number" && Number.isFinite(value) ? value : fallback;
}

export async function OPTIONS() {
  return new NextResponse(null, {
    status: 204,
    headers: CORS_HEADERS
  });
}

export async function GET() {
  try {
    const now = Date.now();
    const state = await readTelemetryState();
    const isLive =
      state.latestTelemetry != null &&
      now - (state.latestTelemetry.receivedAtMs ?? 0) < 12000;

    return NextResponse.json(
      {
        live: isLive,
        telemetry: state.latestTelemetry,
        history: state.history.slice(-60),
        serverTime: now
      },
      {
        status: 200,
        headers: NO_STORE_HEADERS
      }
    );
  } catch (err: unknown) {
    console.error("GET /api/telemetry failed:", err);
    return NextResponse.json(
      { error: "Failed to read telemetry" },
      { status: 500, headers: NO_STORE_HEADERS }
    );
  }
}

export async function POST(req: NextRequest) {
  try {
    const raw = (await req.json()) as Partial<SpectatorTelemetry> | null;
    if (!raw || typeof raw !== "object" || Array.isArray(raw)) {
      return NextResponse.json(
        { error: "Invalid telemetry payload" },
        { status: 400, headers: CORS_HEADERS }
      );
    }

    const now = Date.now();
    const sanitized: SpectatorTelemetry = {
      riderName: typeof raw.riderName === "string" && raw.riderName.trim() ? raw.riderName.trim() : "LazyFit Cyclist",
      timestampMs: finiteNum(raw.timestampMs, now),
      powerWatts: finiteNum(raw.powerWatts),
      heartRateBpm: finiteNum(raw.heartRateBpm),
      cadenceRpm: finiteNum(raw.cadenceRpm),
      speedKmh: finiteNum(raw.speedKmh),
      distanceKm: finiteNum(raw.distanceKm),
      slopeGradePercent: finiteNum(raw.slopeGradePercent),
      normalizedPowerWatts: finiteNum(raw.normalizedPowerWatts),
      powerZone: finiteNum(raw.powerZone, 1),
      hrZone: finiteNum(raw.hrZone, 1),
      caloriesKcal: finiteNum(raw.caloriesKcal),
      routeName: typeof raw.routeName === "string" && raw.routeName.trim() ? raw.routeName.trim() : "Watopia Flat",
      lat: finiteNum(raw.lat),
      lon: finiteNum(raw.lon),
      elevationMeters: finiteNum(raw.elevationMeters),
      workoutActive: Boolean(raw.workoutActive),
      workoutName: typeof raw.workoutName === "string" ? raw.workoutName : "",
      workoutStepName: typeof raw.workoutStepName === "string" ? raw.workoutStepName : "",
      workoutTargetWatts: finiteNum(raw.workoutTargetWatts),
      workoutRemainingSec: finiteNum(raw.workoutRemainingSec)
    };

    await writeTelemetrySample(sanitized, now);

    return NextResponse.json(
      {
        ok: true,
        receivedAt: now,
        rider: sanitized.riderName
      },
      {
        status: 200,
        headers: NO_STORE_HEADERS
      }
    );
  } catch (err: unknown) {
    console.error("POST /api/telemetry failed:", err);
    const details = err instanceof Error ? err.message : undefined;
    return NextResponse.json(
      { error: "Invalid telemetry payload", details },
      { status: 400, headers: CORS_HEADERS }
    );
  }
}
