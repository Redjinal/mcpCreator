// find_assets: relay one search to Envato's search_<category> tool and shape
// the results. Results live in memory for this request only.
// Preview URLs are passed through as strings — the harness never fetches them.

import { mapConstraints, filterFields } from "./mapping";
import { rpc, searchTool } from "./upstream";

export type Asset = { id: string; title: string; url: string; previewUrl: string | null; category: string; whyItFits: string };

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, "");

const pick = (o: any, keys: string[]): string | undefined => {
  for (const k of keys) {
    const v = k.split(".").reduce((x, p) => x?.[p], o);
    if (typeof v === "string" && v) return v;
    if (typeof v === "number") return String(v);
  }
};

// Find the list of result objects wherever the upstream puts it.
function findItems(payload: any): any[] {
  if (Array.isArray(payload)) return payload.filter((x) => x && typeof x === "object");
  if (!payload || typeof payload !== "object") return [];
  for (const k of ["items", "results", "data", "matches", "hits"]) {
    const found = findItems(payload[k]);
    if (found.length) return found;
  }
  for (const v of Object.values(payload)) {
    const found = typeof v === "object" ? findItems(v) : [];
    if (found.length) return found;
  }
  return [];
}

function resultPayload(result: any): any {
  // Envato returns the clean item data as JSON in a text block. structuredContent
  // is a UI "prefab" render tree ($prefab/view → layout nodes), not data — so the
  // text block wins, and structuredContent is only a fallback when it isn't a prefab.
  for (const block of result?.content ?? []) {
    if (block.type !== "text") continue;
    try {
      return JSON.parse(block.text);
    } catch {}
  }
  const sc = result?.structuredContent;
  return sc && !sc.$prefab ? sc : null;
}

// Envato items carry no explicit id; the stable identifier is the trailing token
// of the item URL (e.g. …editorial-serif-font-T4AFBVU → T4AFBVU), else the slug.
function deriveId(url: string): string {
  try {
    const slug = new URL(url).pathname.split("/").filter(Boolean).pop() ?? "";
    return /-([A-Za-z0-9]{5,})$/.exec(slug)?.[1] ?? slug;
  } catch {
    return "";
  }
}

function previewOf(item: any): string | null {
  const direct = pick(item, [
    "previewUrl", "preview_url", "thumbnailUrl", "thumbnail_url", "coverImageUrl", "coverImage", "cover_image",
    "previews.thumbnail.url", "previews.preview.url", "preview.url", "thumbnail.url", "image.url", "imageUrl",
  ]);
  if (direct) return direct;
  const guess = Object.entries(item).find(([k, v]) => /preview|thumb|image|cover/i.test(k) && typeof v === "string" && /^https?:/.test(v));
  return (guess?.[1] as string) ?? null;
}

export async function findAssets(args: { category: string; brief: string; constraints?: Record<string, unknown>; limit?: number }): Promise<Asset[]> {
  const limit = Math.min(Math.max(args.limit ?? 8, 1), 20);
  const tool = await searchTool(args.category);
  const props = tool.inputSchema?.properties ?? {};
  const mapped = mapConstraints(args.constraints ?? {}, filterFields(props.filters));

  const briefWords = new Set(args.brief.split(/\s+/).map(norm));
  const extra = mapped.keywords.filter((k) => !briefWords.has(norm(k)));
  const params: Record<string, unknown> = { searchTerms: [args.brief, ...extra].join(" ").trim() };
  if ("perPage" in props) params.perPage = limit;
  if ("filters" in props && Object.keys(mapped.filters).length) params.filters = mapped.filters;

  const result = await rpc("tools/call", { name: tool.name, arguments: params });
  if (result?.isError) {
    const text = (result.content ?? []).map((c: any) => c.text).filter(Boolean).join(" ");
    throw new Error(`Envato ${tool.name} returned an error: ${text || "no details"}`);
  }

  return findItems(resultPayload(result)).slice(0, limit).map((item) => {
    const title = pick(item, ["title", "name"]) ?? "Untitled";
    const haystack = norm(JSON.stringify([title, item.description, item.tags, item.attributes]));
    const hits = mapped.keywords.filter((k) => haystack.includes(norm(k)));
    const why = [
      mapped.matched.length ? `filters ${mapped.matched.join(", ")}` : "",
      hits.length ? `mentions ${hits.join(", ")}` : "",
    ].filter(Boolean).join("; ");
    const url = pick(item, ["url", "itemUrl", "item_url", "link", "permalink"]) ?? "";
    return {
      id: pick(item, ["id", "itemId", "item_id", "uuid", "humaneId"]) ?? deriveId(url),
      title,
      url,
      previewUrl: previewOf(item),
      category: args.category,
      whyItFits: why || `keyword match for "${args.brief}"`,
    };
  });
}
