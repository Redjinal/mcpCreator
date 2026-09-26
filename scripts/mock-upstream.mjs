// Localhost stand-in for the upstream endpoint, used ONLY by local-test.mjs.
// Shapes mirror the REAL Envato MCP responses captured in
// docs/envato-filter-schemas-*.json and a live tools/call:
//   - filters live under filters.anyOf[0].properties, each an [enum,null] anyOf
//   - tools/call returns clean data in a text block ({items,pagination,...})
//     while structuredContent is a UI "prefab" render tree, NOT data
//   - items have no `id`; the identifier lives in `item_url`, preview in `cover_image`
import { createServer } from "node:http";

const fontsSchema = {
  type: "object",
  additionalProperties: false,
  required: ["searchTerms"],
  properties: {
    searchTerms: { type: "string", minLength: 1, maxLength: 60 },
    page: { type: "integer", default: 1, minimum: 1 },
    perPage: { type: "integer", default: 20, minimum: 1, maximum: 20 },
    sortBy: { type: "string", default: "popular", enum: ["relevance", "popular", "latest"] },
    languageCode: { type: "string", default: "en", enum: ["en", "es", "fr", "de", "pt-BR"] },
    filters: {
      default: null,
      anyOf: [
        {
          type: "object",
          additionalProperties: false,
          properties: {
            categories: {
              default: null,
              description: "Font style classification",
              anyOf: [
                { type: "string", enum: ["serif", "sans_serif", "script", "decorative_display", "monospace", "handwritten_brush", "blackletter", "symbols", "duo"] },
                { type: "null" },
              ],
            },
            spacing: {
              default: null,
              anyOf: [{ type: "string", enum: ["Normal", "Monospace", "Condensed", "Expanded"] }, { type: "null" }],
            },
          },
        },
        { type: "null" },
      ],
    },
  },
};
const tools = ["fonts", "photos", "graphics"].map((c) => ({ name: `search_${c}`, inputSchema: fontsSchema }));

// Envato wraps results in a UI prefab tree under structuredContent — no item data,
// just layout nodes. The parseable data is always in the text block.
const prefabOf = (items) => ({
  $prefab: { version: "0.2" },
  view: {
    type: "Div",
    children: [
      { type: "Text", content: `${items.length} results` },
      { type: "Grid", children: items.slice(0, 3).map((it) => ({ type: "Card", children: [{ type: "Image", src: it.cover_image, alt: it.title }] })) },
    ],
  },
});

export function startMock(port) {
  const calls = [];
  const server = createServer(async (req, res) => {
    let body = "";
    for await (const c of req) body += c;
    const msg = JSON.parse(body);
    calls.push(msg);
    const reply = (result) => {
      res.writeHead(200, { "Content-Type": "text/event-stream" });
      res.end(`event: message\ndata: ${JSON.stringify({ jsonrpc: "2.0", id: msg.id, result })}\n\n`);
    };
    if (msg.method === "initialize") return reply({ serverInfo: { name: "mock", version: "0" }, capabilities: {} });
    if (msg.method === "tools/list") return reply({ tools });
    if (msg.method === "tools/call") {
      if (msg.params.arguments.searchTerms.includes("boom")) {
        res.writeHead(503);
        return res.end("unavailable");
      }
      // Fixture URLs use example.test hosts (not real Envato hosts) so the
      // check-hosts guard stays meaningful; shaping/id logic is host-agnostic.
      const items = Array.from({ length: 30 }, (_, i) => ({
        title: `Test Serif ${i}`,
        item_type: "fonts",
        author: "tester",
        cover_image: `https://cdn.example.test/preview-${i}.jpg`,
        aspect_ratio: 1.5,
        item_url: `https://assets.example.test/test-serif-font-${i}-ABC${i}XYZ`,
      }));
      return reply({
        content: [{ type: "text", text: JSON.stringify({ items, pagination: { page: 1, perPage: msg.params.arguments.perPage ?? 20, total: items.length }, applied_filters: msg.params.arguments.filters ?? {} }) }],
        structuredContent: prefabOf(items),
        isError: false,
      });
    }
    res.writeHead(400);
    res.end();
  });
  return new Promise((resolve) => server.listen(port, () => resolve({ server, calls })));
}
