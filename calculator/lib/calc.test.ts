import { describe, expect, it } from "vitest";
import { calculate } from "./calc";
import type { BazaarPrice, CalcConfig, FusionRecipe } from "./types";

const baseConfig: CalcConfig = {
  capitalLimit: 50_000_000,
  bazaarTax: 0.0125,
  minVolumeMultiplier: 100,
  fusionCooldownPerHour: 3600,
};

function priceMap(prices: Record<string, Partial<BazaarPrice>>) {
  return (id: string): BazaarPrice | null => {
    const p = prices[id];
    if (!p) return null;
    return {
      productId: id,
      buyPrice: p.buyPrice ?? 0,
      sellPrice: p.sellPrice ?? 0,
      buyVolume: p.buyVolume ?? 0,
      sellVolume: p.sellVolume ?? 0,
      weeklyMovingSellVolume: p.weeklyMovingSellVolume ?? 10_000_000,
    };
  };
}

describe("calculate", () => {
  it("returns only the direct-sell method when no recipe consumes the farmed shard", () => {
    const farms = [{ shardId: "SHARD_A", shardsPerHour: 1000 }];
    const recipes: FusionRecipe[] = [];
    const prices = priceMap({ SHARD_A: { buyPrice: 50, sellPrice: 40 } });

    const results = calculate({ farms, config: baseConfig, recipes, prices });

    expect(results).toHaveLength(1);
    expect(results[0].math.kind).toBe("direct");
    expect(results[0].math.farmedShard).toBe("SHARD_A");
    // 1000 * 40 * (1 - 0.0125) = 39_500
    expect(results[0].coinsPerHour).toBeCloseTo(39_500, 5);
    expect(results[0].capitalRequired).toBe(0);
    expect(results[0].volumeStatus).toBe("ok");
    expect(results[0].rank).toBe(1);
  });

  it("ranks a more profitable fusion above direct sell with correct math", () => {
    const farms = [{ shardId: "SHARD_A", shardsPerHour: 1000 }];
    const recipes: FusionRecipe[] = [
      {
        id: "fusion_ab",
        outputShard: "SHARD_C",
        outputQty: 1,
        inputs: [
          { shardId: "SHARD_A", qty: 2 },
          { shardId: "SHARD_B", qty: 2 },
        ],
      },
    ];
    const prices = priceMap({
      SHARD_A: { buyPrice: 50, sellPrice: 40 },
      SHARD_B: { buyPrice: 100, sellPrice: 90 },
      SHARD_C: { buyPrice: 1000, sellPrice: 900, weeklyMovingSellVolume: 10_000_000 },
    });

    const results = calculate({ farms, config: baseConfig, recipes, prices });

    // Direct: 1000 * 40 * 0.9875 = 39_500
    // Fusion: maxFusions = min(1000/2, 3600) = 500
    //         partnerCost = 100 * 2 = 200
    //         revenue = 900 * 1 * 0.9875 = 888.75
    //         profitPerFusion = 888.75 - 200 = 688.75
    //         coinsPerHour = 688.75 * 500 = 344_375
    //         capital = 200 * 500 = 100_000
    expect(results).toHaveLength(2);
    expect(results[0].math.kind).toBe("fusion");
    expect(results[0].math.recipeId).toBe("fusion_ab");
    expect(results[0].coinsPerHour).toBeCloseTo(344_375, 3);
    expect(results[0].capitalRequired).toBeCloseTo(100_000, 3);
    expect(results[0].math.fusionsPerHour).toBe(500);
    expect(results[0].math.partnerCost).toBeCloseTo(200, 3);
    expect(results[0].rank).toBe(1);

    expect(results[1].math.kind).toBe("direct");
    expect(results[1].coinsPerHour).toBeCloseTo(39_500, 3);
    expect(results[1].rank).toBe(2);
  });

  it("filters out fusion methods whose capital requirement exceeds the configured limit", () => {
    const farms = [{ shardId: "SHARD_A", shardsPerHour: 1000 }];
    const recipes: FusionRecipe[] = [
      {
        id: "fusion_ab",
        outputShard: "SHARD_C",
        outputQty: 1,
        inputs: [
          { shardId: "SHARD_A", qty: 2 },
          { shardId: "SHARD_B", qty: 2 },
        ],
      },
    ];
    const prices = priceMap({
      SHARD_A: { buyPrice: 50, sellPrice: 40 },
      SHARD_B: { buyPrice: 100, sellPrice: 90 },
      SHARD_C: { buyPrice: 1000, sellPrice: 900 },
    });
    // Required capital is 100_000; cap below it.
    const config = { ...baseConfig, capitalLimit: 50_000 };

    const results = calculate({ farms, config, recipes, prices });

    expect(results.map((r) => r.math.kind)).toEqual(["direct"]);
  });

  it("flags a volume warning when output weekly volume is below the multiplier threshold", () => {
    const farms = [{ shardId: "SHARD_A", shardsPerHour: 1000 }];
    const recipes: FusionRecipe[] = [
      {
        id: "fusion_ab",
        outputShard: "SHARD_C",
        outputQty: 1,
        inputs: [
          { shardId: "SHARD_A", qty: 2 },
          { shardId: "SHARD_B", qty: 2 },
        ],
      },
    ];
    // hourly output = 500. threshold = 500 * 1 * 100 = 50_000.
    // Set weekly volume to 49_000 (under threshold) but non-zero so it's "warning" not "blocked".
    const prices = priceMap({
      SHARD_A: { buyPrice: 50, sellPrice: 40 },
      SHARD_B: { buyPrice: 100, sellPrice: 90 },
      SHARD_C: { buyPrice: 1000, sellPrice: 900, weeklyMovingSellVolume: 49_000 },
    });

    const results = calculate({ farms, config: baseConfig, recipes, prices });
    const fusion = results.find((r) => r.math.kind === "fusion");
    expect(fusion).toBeDefined();
    expect(fusion!.volumeStatus).toBe("warning");
  });

  it("marks the fusion blocked when the output has zero weekly sell volume", () => {
    const farms = [{ shardId: "SHARD_A", shardsPerHour: 1000 }];
    const recipes: FusionRecipe[] = [
      {
        id: "fusion_ab",
        outputShard: "SHARD_C",
        outputQty: 1,
        inputs: [
          { shardId: "SHARD_A", qty: 2 },
          { shardId: "SHARD_B", qty: 2 },
        ],
      },
    ];
    const prices = priceMap({
      SHARD_A: { buyPrice: 50, sellPrice: 40 },
      SHARD_B: { buyPrice: 100, sellPrice: 90 },
      SHARD_C: { buyPrice: 1000, sellPrice: 900, weeklyMovingSellVolume: 0 },
    });

    const results = calculate({ farms, config: baseConfig, recipes, prices });
    const fusion = results.find((r) => r.math.kind === "fusion");
    expect(fusion).toBeDefined();
    expect(fusion!.volumeStatus).toBe("blocked");
  });

  it("skips fusion paths whose output is missing from bazaar data", () => {
    const farms = [{ shardId: "SHARD_A", shardsPerHour: 1000 }];
    const recipes: FusionRecipe[] = [
      {
        id: "fusion_ab",
        outputShard: "SHARD_UNTRADED",
        outputQty: 1,
        inputs: [
          { shardId: "SHARD_A", qty: 2 },
          { shardId: "SHARD_B", qty: 2 },
        ],
      },
    ];
    const prices = priceMap({
      SHARD_A: { buyPrice: 50, sellPrice: 40 },
      SHARD_B: { buyPrice: 100, sellPrice: 90 },
    });

    const results = calculate({ farms, config: baseConfig, recipes, prices });
    expect(results.map((r) => r.math.kind)).toEqual(["direct"]);
  });

  it("caps fusions per hour by fusionCooldownPerHour when the farm rate exceeds it", () => {
    const farms = [{ shardId: "SHARD_A", shardsPerHour: 1_000_000 }];
    const recipes: FusionRecipe[] = [
      {
        id: "fusion_ab",
        outputShard: "SHARD_C",
        outputQty: 1,
        inputs: [
          { shardId: "SHARD_A", qty: 1 },
          { shardId: "SHARD_B", qty: 1 },
        ],
      },
    ];
    const prices = priceMap({
      SHARD_A: { buyPrice: 50, sellPrice: 40 },
      SHARD_B: { buyPrice: 10, sellPrice: 9 },
      SHARD_C: { buyPrice: 1000, sellPrice: 500, weeklyMovingSellVolume: 1_000_000_000 },
    });

    const results = calculate({ farms, config: baseConfig, recipes, prices });
    const fusion = results.find((r) => r.math.kind === "fusion")!;
    expect(fusion.math.fusionsPerHour).toBe(3600);
  });

  it("returns at most ten ranked results", () => {
    const farms = [{ shardId: "SHARD_A", shardsPerHour: 1000 }];
    // Build 15 recipes each producing a distinct profitable output.
    const recipes: FusionRecipe[] = Array.from({ length: 15 }, (_, i) => ({
      id: `r${i}`,
      outputShard: `OUT_${i}`,
      outputQty: 1,
      inputs: [
        { shardId: "SHARD_A", qty: 1 },
        { shardId: "SHARD_B", qty: 1 },
      ],
    }));
    const priceTable: Record<string, Partial<BazaarPrice>> = {
      SHARD_A: { buyPrice: 50, sellPrice: 40 },
      SHARD_B: { buyPrice: 10, sellPrice: 9 },
    };
    for (let i = 0; i < 15; i++) {
      priceTable[`OUT_${i}`] = {
        buyPrice: 1000,
        sellPrice: 100 + i, // ascending profitability
        weeklyMovingSellVolume: 1_000_000_000,
      };
    }

    const results = calculate({
      farms,
      config: baseConfig,
      recipes,
      prices: priceMap(priceTable),
    });

    expect(results).toHaveLength(10);
    // Sorted descending by coinsPerHour.
    for (let i = 1; i < results.length; i++) {
      expect(results[i - 1].coinsPerHour).toBeGreaterThanOrEqual(results[i].coinsPerHour);
    }
  });
});
