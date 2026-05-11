import type { BazaarPrice } from "./types";

const BAZAAR_URL = "https://api.hypixel.net/v2/skyblock/bazaar";
const CACHE_TTL_MS = 90_000;

type RawSummary = { amount: number; pricePerUnit: number; orders: number };
type RawQuickStatus = {
  productId: string;
  sellPrice: number;
  sellVolume: number;
  sellMovingWeek: number;
  sellOrders: number;
  buyPrice: number;
  buyVolume: number;
  buyMovingWeek: number;
  buyOrders: number;
};
type RawProduct = {
  product_id: string;
  sell_summary: RawSummary[];
  buy_summary: RawSummary[];
  quick_status: RawQuickStatus;
};
type RawBazaar = {
  success: boolean;
  lastUpdated: number;
  products: Record<string, RawProduct>;
};

type Snapshot = {
  fetchedAt: number;
  lastUpdated: number;
  products: Record<string, RawProduct>;
};

let snapshot: Snapshot | null = null;
let inflight: Promise<Snapshot> | null = null;

async function fetchSnapshot(): Promise<Snapshot> {
  const res = await fetch(BAZAAR_URL, { cache: "no-store" });
  if (!res.ok) {
    throw new Error(`Bazaar API responded ${res.status}`);
  }
  const body = (await res.json()) as RawBazaar;
  if (!body.success) {
    throw new Error("Bazaar API returned success=false");
  }
  return {
    fetchedAt: Date.now(),
    lastUpdated: body.lastUpdated,
    products: body.products,
  };
}

async function getSnapshot(): Promise<Snapshot> {
  const now = Date.now();
  if (snapshot && now - snapshot.fetchedAt < CACHE_TTL_MS) {
    return snapshot;
  }
  if (inflight) return inflight;
  inflight = (async () => {
    try {
      const fresh = await fetchSnapshot();
      snapshot = fresh;
      return fresh;
    } catch (err) {
      if (snapshot) {
        // eslint-disable-next-line no-console
        console.error("Bazaar fetch failed, serving stale snapshot:", err);
        return snapshot;
      }
      throw err;
    } finally {
      inflight = null;
    }
  })();
  return inflight;
}

export async function getBazaarPrice(productId: string): Promise<BazaarPrice | null> {
  const snap = await getSnapshot();
  const product = snap.products[productId];
  if (!product) return null;
  const buyPrice = product.buy_summary[0]?.pricePerUnit ?? 0;
  const sellPrice = product.sell_summary[0]?.pricePerUnit ?? 0;
  return {
    productId,
    buyPrice,
    sellPrice,
    buyVolume: product.quick_status.buyVolume,
    sellVolume: product.quick_status.sellVolume,
    weeklyMovingSellVolume: product.quick_status.sellMovingWeek,
  };
}

export async function getBazaarMeta(): Promise<{ lastUpdated: number; fetchedAt: number }> {
  const snap = await getSnapshot();
  return { lastUpdated: snap.lastUpdated, fetchedAt: snap.fetchedAt };
}

export async function listProductIds(): Promise<string[]> {
  const snap = await getSnapshot();
  return Object.keys(snap.products);
}
