import { NextResponse } from "next/server";
import { getBazaarMeta, getBazaarPrice, listProductIds } from "@/lib/bazaar";

export const dynamic = "force-dynamic";

export async function GET(req: Request) {
  const { searchParams } = new URL(req.url);
  const product = searchParams.get("product");

  try {
    if (product) {
      const price = await getBazaarPrice(product);
      if (!price) {
        return NextResponse.json(
          { error: "product_not_found", productId: product },
          { status: 404 },
        );
      }
      const meta = await getBazaarMeta();
      return NextResponse.json({ product: price, meta });
    }

    const [ids, meta] = await Promise.all([listProductIds(), getBazaarMeta()]);
    return NextResponse.json({ productIds: ids, meta });
  } catch (err) {
    const message = err instanceof Error ? err.message : "unknown_error";
    return NextResponse.json({ error: "bazaar_unavailable", message }, { status: 502 });
  }
}
