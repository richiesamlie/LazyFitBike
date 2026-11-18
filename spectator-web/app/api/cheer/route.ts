import { NextRequest, NextResponse } from "next/server";
import { appendCheer, readRecentCheers, SpectatorCheer } from "@/lib/spectator-store";

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

export async function OPTIONS() {
  return new NextResponse(null, {
    status: 204,
    headers: CORS_HEADERS
  });
}

export async function GET() {
  try {
    const now = Date.now();
    const recent = await readRecentCheers(now);
    return NextResponse.json({ cheers: recent }, { headers: NO_STORE_HEADERS });
  } catch (err: unknown) {
    console.error("GET /api/cheer failed:", err);
    return NextResponse.json(
      { error: "Failed to read cheers" },
      { status: 500, headers: NO_STORE_HEADERS }
    );
  }
}

export async function POST(req: NextRequest) {
  try {
    const body = (await req.json()) as { sender?: string; message?: string } | null;
    if (!body || typeof body !== "object") {
      return NextResponse.json(
        { error: "Failed to send cheer" },
        { status: 400, headers: CORS_HEADERS }
      );
    }

    const cheer: SpectatorCheer = {
      id: Math.random().toString(36).substring(2, 9),
      sender: body.sender?.trim() || "Spectator",
      message: body.message?.trim() || "Ride On!",
      timestamp: Date.now()
    };

    await appendCheer(cheer);

    return NextResponse.json({ ok: true, cheer }, { headers: NO_STORE_HEADERS });
  } catch (err: unknown) {
    console.error("POST /api/cheer failed:", err);
    return NextResponse.json(
      { error: "Failed to send cheer" },
      { status: 400, headers: CORS_HEADERS }
    );
  }
}
