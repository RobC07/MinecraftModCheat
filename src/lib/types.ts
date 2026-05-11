import type {
  ChatInputCommandInteraction,
  SlashCommandBuilder,
  SlashCommandOptionsOnlyBuilder,
  SlashCommandSubcommandsOnlyBuilder,
} from "discord.js";

export interface Command {
  data:
    | SlashCommandBuilder
    | SlashCommandOptionsOnlyBuilder
    | SlashCommandSubcommandsOnlyBuilder;
  execute: (interaction: ChatInputCommandInteraction) => Promise<void>;
}

export interface ShardPrice {
  buyPrice: number;
  sellPrice: number;
  sellVolume: number;
  weeklyMovingSellVolume: number;
}

export interface RecipeInput {
  shardId: string;
  qty: number;
}

export interface Recipe {
  id: string;
  outputShard: string;
  outputQty: number;
  inputs: RecipeInput[];
}

export interface BotConfig {
  farms: Record<string, number>;
  capitalLimit: number;
  bazaarTax: number;
  minVolumeMultiplier: number;
  fusionCooldownPerHour: number;
}
