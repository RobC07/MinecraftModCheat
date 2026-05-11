import { NextResponse } from "next/server";
import { getAllRecipes } from "@/lib/recipes";

export async function GET() {
  return NextResponse.json({ recipes: getAllRecipes() });
}
