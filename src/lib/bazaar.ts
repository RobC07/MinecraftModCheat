import { request } from "undici";
import type { ShardPrice } from "./types.js";

const BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
const CACHE_TTL_MS = 90_000;

interface QuickStatus {
  productId: string;
  buyPrice: number;
  sellPrice: number;
  sellVolume: number;
  sellMovingWeek: number;
}

interface BazaarProduct {
  product_id: string;
  quick_status: QuickStatus;
}

interface BazaarResponse {
  success: boolean;
  lastUpdated: number;
  products: Record<string, BazaarProduct>;
}

let cache: BazaarResponse | null = null;
let cachedAt = 0;
let inflight: Promise<void> | null = null;

async function fetchBazaar(): Promise<BazaarResponse> {
  const { statusCode, body } = await request(BAZAAR_URL);
  if (statusCode !== 200) {
    throw new Error(`Bazaar API returned status ${statusCode}`);
  }
  const json = (await body.json()) as BazaarResponse;
  if (!json.success) {
    throw new Error("Bazaar API returned success=false");
  }
  return json;
}

async function refresh(): Promise<void> {
  try {
    const fresh = await fetchBazaar();
    cache = fresh;
    cachedAt = Date.now();
  } catch (err) {
    if (cache) {
      console.warn("Bazaar refresh failed, serving stale cache:", err);
      return;
    }
    throw err;
  }
}

async function ensureFresh(): Promise<void> {
  const age = Date.now() - cachedAt;
  if (cache && age < CACHE_TTL_MS) return;
  if (inflight) {
    await inflight;
    return;
  }
  inflight = refresh().finally(() => {
    inflight = null;
  });
  await inflight;
}

export async function getPrice(productId: string): Promise<ShardPrice | null> {
  await ensureFresh();
  const product = cache?.products[productId];
  if (!product) return null;
  const q = product.quick_status;
  return {
    buyPrice: q.buyPrice,
    sellPrice: q.sellPrice,
    sellVolume: q.sellVolume,
    weeklyMovingSellVolume: q.sellMovingWeek,
  };
}

export async function forceRefresh(): Promise<void> {
  inflight = refresh().finally(() => {
    inflight = null;
  });
  await inflight;
}

export function getLastUpdated(): Date {
  return new Date(cachedAt);
}
