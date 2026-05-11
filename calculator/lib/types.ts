export type BazaarPrice = {
  productId: string;
  buyPrice: number;
  sellPrice: number;
  buyVolume: number;
  sellVolume: number;
  weeklyMovingSellVolume: number;
};

export type FarmInput = {
  shardId: string;
  shardsPerHour: number;
};

export type CalcConfig = {
  capitalLimit: number;
  bazaarTax: number;
  minVolumeMultiplier: number;
  fusionCooldownPerHour: number;
};

export type FusionRecipe = {
  id: string;
  outputShard: string;
  outputQty: number;
  inputs: Array<{ shardId: string; qty: number }>;
};

export type VolumeStatus = "ok" | "warning" | "blocked";

export type CalcResult = {
  rank: number;
  label: string;
  coinsPerHour: number;
  capitalRequired: number;
  volumeStatus: VolumeStatus;
  notes: string[];
  math: {
    kind: "direct" | "fusion";
    farmedShard: string;
    shardsPerHour: number;
    recipeId?: string;
    outputShard?: string;
    outputQty?: number;
    fusionsPerHour?: number;
    partnerCost?: number;
    revenuePerFusion?: number;
    profitPerFusion?: number;
    sellPrice: number;
    bazaarTax: number;
    weeklyMovingSellVolume?: number;
  };
};
