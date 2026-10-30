import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

interface SpectatorTelemetry {
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
  receivedAtMs?: number;
}

// Global in-memory telemetry store for Edge/Serverless runtime
let latestTelemetry: SpectatorTelemetry | null = null;
const historyBuffer: Array<{ time: number; watts: number; bpm: number; speed: number }> = [];

const CORS_HEADERS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, User-Agent, X-LazyFit-Device"
};

export async function OPTIONS() {
  return new NextResponse(null, {
    status: 204,
    headers: CORS_HEADERS
  });
}

export async function GET() {
  const now = Date.now();
  const isLive = latestTelemetry != null && now - (latestTelemetry.receivedAtMs ?? 0) < 12000;

  return NextResponse.json(
    {
      live: isLive,
      telemetry: latestTelemetry,
      history: historyBuffer.slice(-60),
      serverTime: now
    },
    {
      status: 200,
      headers: {
        ...CORS_HEADERS,
        "Cache-Control": "no-cache, no-store, must-revalidate"
      }
    }
  );
}

export async function POST(req: NextRequest) {
  try {
    const body: SpectatorTelemetry = await req.json();
    const now = Date.now();

    latestTelemetry = {
      ...body,
      receivedAtMs: now
    };

    historyBuffer.push({
      time: now,
      watts: body.powerWatts ?? 0,
      bpm: body.heartRateBpm ?? 0,
      speed: body.speedKmh ?? 0
    });

    if (historyBuffer.length > 300) {
      historyBuffer.shift();
    }

    return NextResponse.json(
      {
        ok: true,
        receivedAt: now,
        rider: body.riderName ?? "LazyFit Cyclist"
      },
      {
        status: 200,
        headers: CORS_HEADERS
      }
    );
  } catch (err: any) {
    return NextResponse.json(
      { error: "Invalid telemetry payload", details: err?.message },
      { status: 400, headers: CORS_HEADERS }
    );
  }
}
