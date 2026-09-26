// The harness: exactly three tools. Adding a fourth requires a Council `revisit`.

import { createMcpHandler } from "mcp-handler";
import { timingSafeEqual } from "node:crypto";
import { z } from "zod";
import { findAssets } from "./assets.js";
import { listChoices, recordChoice } from "./ledger.js";
import { upstreamTools } from "./upstream.js";

const KNOWN_CATEGORIES =
  "fonts, graphics, photos, graphic_templates, presentation_templates, stock_video, music, sound_effects, " +
  "video_templates, web_templates, 3d, add_ons, wordpress, cms_templates";

const json = (value: unknown) => ({ content: [{ type: "text" as const, text: JSON.stringify(value, null, 2) }] });
const fail = (e: unknown) => ({ isError: true, content: [{ type: "text" as const, text: e instanceof Error ? e.message : String(e) }] });

const mcp = createMcpHandler(
  (server) => {
    server.registerTool(
      "find_assets",
      {
        title: "Find Envato assets",
        description:
          "Search Envato Elements via Envato's official MCP server. Pass design-system constraints " +
          "(e.g. fontClassification, mood, orientation, colours); they are mapped onto Envato's live " +
          "enum filters where one exists, otherwise used as keywords. Returns item links and preview URLs; " +
          "licensing and downloading happen on Envato's site.",
        inputSchema: z.object({
          category: z.string().describe(`Envato category, e.g. ${KNOWN_CATEGORIES}`),
          brief: z.string().min(1).describe("Natural-language description of what is needed"),
          constraints: z.record(z.string(), z.unknown()).optional().describe("Design-system hints"),
          limit: z.number().int().min(1).max(20).optional().describe("Max results (default 8, max 20)"),
        }),
      },
      async (args) => {
        try {
          return json(await findAssets(args));
        } catch (e) {
          return fail(e);
        }
      },
    );

    server.registerTool(
      "record_choice",
      {
        title: "Record asset choice",
        description: "Append an Envato asset the user chose to the project ledger.",
        inputSchema: z.object({
          item_id: z.string().min(1),
          title: z.string().min(1),
          url: z.string().url(),
          project: z.string().min(1),
          licensed: z.boolean().optional().describe("Whether the user has licensed it on Envato (default false)"),
          note: z.string().optional(),
        }),
      },
      async (args) => {
        try {
          return json(await recordChoice(args));
        } catch (e) {
          return fail(e);
        }
      },
    );

    server.registerTool(
      "list_choices",
      {
        title: "List asset choices",
        description: "List recorded asset choices, optionally for one project.",
        inputSchema: z.object({ project: z.string().optional() }),
        annotations: { readOnlyHint: true },
      },
      async ({ project }) => {
        try {
          return json(await listChoices(project));
        } catch (e) {
          return fail(e);
        }
      },
    );
  },
  { serverInfo: { name: "envato-relay-harness", version: "0.1.0" } },
);

function authorized(req: Request): boolean {
  const expected = process.env.HARNESS_TOKEN;
  const match = /^Bearer\s+(.+)$/i.exec(req.headers.get("authorization") ?? "");
  if (!expected || !match) return false;
  const a = Buffer.from(match[1].trim()), b = Buffer.from(expected);
  return a.length === b.length && timingSafeEqual(a, b);
}

export async function handler(req: Request): Promise<Response> {
  if (!authorized(req)) {
    return new Response(JSON.stringify({ error: "unauthorized" }), {
      status: 401,
      headers: { "Content-Type": "application/json", "WWW-Authenticate": 'Bearer realm="envato-relay-harness"' },
    });
  }
  return mcp(req);
}

// Cold-start introspection; errors surface later on find_assets.
upstreamTools().catch(() => {});
