import { NextRequest, NextResponse } from "next/server";

export const dynamic = "force-dynamic";

interface Cheer {
  id: string;
  sender: string;
  message: string;
  timestamp: number;
}

const activeCheers: Cheer[] = [];

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
  // Return cheers from last 30 seconds
  const recent = activeCheers.filter(c => now - c.timestamp < 30000);
  return NextResponse.json({ cheers: recent }, { headers: CORS_HEADERS });
}

export async function POST(req: NextRequest) {
  try {
    const body = await req.json();
    const cheer: Cheer = {
      id: Math.random().toString(36).substring(2, 9),
      sender: body.sender?.trim() || "Spectator",
      message: body.message?.trim() || "Ride On! 🚴‍♂️💨",
      timestamp: Date.now()
    };

    activeCheers.push(cheer);
    if (activeCheers.length > 50) {
      activeCheers.shift();
    }

    return NextResponse.json({ ok: true, cheer }, { headers: CORS_HEADERS });
  } catch (err: any) {
    return NextResponse.json({ error: "Failed to send cheer" }, { status: 400, headers: CORS_HEADERS });
  }
}
