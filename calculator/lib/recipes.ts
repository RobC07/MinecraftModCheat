import recipesData from "@/data/recipes.json";
import type { FusionRecipe } from "./types";

const recipes = recipesData as FusionRecipe[];

export function getAllRecipes(): FusionRecipe[] {
  return recipes;
}

export function getRecipesUsingInput(shardId: string): FusionRecipe[] {
  return recipes.filter((r) => r.inputs.some((i) => i.shardId === shardId));
}
