import { getCache } from "@vercel/functions";

export interface SpectatorTelemetry {
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

export interface TelemetryHistoryPoint {
  time: number;
  watts: number;
  bpm: number;
  speed: number;
}

export interface TelemetryStoreState {
  latestTelemetry: SpectatorTelemetry | null;
  history: TelemetryHistoryPoint[];
}

export interface SpectatorCheer {
  id: string;
  sender: string;
  message: string;
  timestamp: number;
}

const TELEMETRY_KEY = "telemetry:state";
const CHEERS_KEY = "cheers:active";
const TELEMETRY_TTL_SECONDS = 3600;
const CHEERS_TTL_SECONDS = 120;
const MAX_HISTORY_POINTS = 300;
const MAX_ACTIVE_CHEERS = 50;
const CHEER_WINDOW_MS = 30000;

interface FallbackMemoryStore {
  telemetryState: TelemetryStoreState | null;
  cheers: SpectatorCheer[];
}

const globalStore = globalThis as typeof globalThis & {
  __lazyfitSpectatorStore?: FallbackMemoryStore;
};

function getFallbackStore(): FallbackMemoryStore {
  if (!globalStore.__lazyfitSpectatorStore) {
    globalStore.__lazyfitSpectatorStore = {
      telemetryState: null,
      cheers: []
    };
  }
  return globalStore.__lazyfitSpectatorStore;
}

function getSpectatorCache() {
  return getCache({ namespace: "lazyfitbike-spectator" });
}

export async function readTelemetryState(): Promise<TelemetryStoreState> {
  try {
    const cached = (await getSpectatorCache().get(TELEMETRY_KEY)) as TelemetryStoreState | null;
    if (cached && typeof cached === "object") {
      return {
        latestTelemetry: cached.latestTelemetry ?? null,
        history: Array.isArray(cached.history) ? cached.history : []
      };
    }
  } catch (err) {
    console.error("Failed to read telemetry from Vercel Runtime Cache:", err);
  }

  return getFallbackStore().telemetryState ?? { latestTelemetry: null, history: [] };
}

export async function writeTelemetrySample(
  packet: SpectatorTelemetry,
  nowMs: number
): Promise<TelemetryStoreState> {
  const existing = await readTelemetryState();
  const nextHistory = [
    ...existing.history,
    {
      time: nowMs,
      watts: Number.isFinite(packet.powerWatts) ? packet.powerWatts : 0,
      bpm: Number.isFinite(packet.heartRateBpm) ? packet.heartRateBpm : 0,
      speed: Number.isFinite(packet.speedKmh) ? packet.speedKmh : 0
    }
  ].slice(-MAX_HISTORY_POINTS);

  const nextState: TelemetryStoreState = {
    latestTelemetry: {
      ...packet,
      receivedAtMs: nowMs
    },
    history: nextHistory
  };

  getFallbackStore().telemetryState = nextState;

  try {
    await getSpectatorCache().set(TELEMETRY_KEY, nextState, {
      ttl: TELEMETRY_TTL_SECONDS,
      name: "telemetry-state"
    });
  } catch (err) {
    console.error("Failed to write telemetry to Vercel Runtime Cache:", err);
  }

  return nextState;
}

export async function readRecentCheers(nowMs: number): Promise<SpectatorCheer[]> {
  let cheers: SpectatorCheer[] | null = null;
  try {
    const cached = await getSpectatorCache().get(CHEERS_KEY);
    if (Array.isArray(cached)) {
      cheers = cached as SpectatorCheer[];
    }
  } catch (err) {
    console.error("Failed to read cheers from Vercel Runtime Cache:", err);
  }

  const source = cheers ?? getFallbackStore().cheers;
  return source.filter((c) => nowMs - c.timestamp < CHEER_WINDOW_MS);
}

export async function appendCheer(cheer: SpectatorCheer): Promise<SpectatorCheer[]> {
  const recent = await readRecentCheers(cheer.timestamp);
  const nextCheers = [...recent, cheer].slice(-MAX_ACTIVE_CHEERS);
  getFallbackStore().cheers = nextCheers;

  try {
    await getSpectatorCache().set(CHEERS_KEY, nextCheers, {
      ttl: CHEERS_TTL_SECONDS,
      name: "active-cheers"
    });
  } catch (err) {
    console.error("Failed to write cheers to Vercel Runtime Cache:", err);
  }

  return nextCheers;
}
