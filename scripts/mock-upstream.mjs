// Localhost stand-in for the upstream endpoint, used ONLY by local-test.mjs.
// The schema below is INVENTED for tests; the real enums come from
// scripts/inspect-filters.mjs (docs/envato-filter-schemas-*.json).
import { createServer } from "node:http";

const fontsSchema = {
  type: "object",
  properties: {
    searchTerms: { type: "string" }, page: { type: "number" }, perPage: { type: "number" },
    sortBy: { type: "string" }, languageCode: { type: "string" },
    filters: {
      type: "object",
      properties: {
        classification: { type: "array", items: { type: "string", enum: ["serif", "sans-serif", "display", "script"] } },
        orientation: { type: "string", enum: ["landscape", "portrait", "square"] },
      },
    },
  },
};
const tools = ["fonts", "photos", "graphics"].map((c) => ({ name: `search_${c}`, inputSchema: fontsSchema }));

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
      const items = Array.from({ length: 30 }, (_, i) => ({
        id: `item-${i}`, name: `Test Serif ${i}`, url: `https://items.example.test/item-${i}`,
        description: i % 2 ? "an editorial typeface" : "a display face",
        previews: { thumbnail: { url: `https://previews.example.test/${i}.jpg` } },
      }));
      return reply({ content: [{ type: "text", text: JSON.stringify({ results: items }) }] });
    }
    res.writeHead(400);
    res.end();
  });
  return new Promise((resolve) => server.listen(port, () => resolve({ server, calls })));
}
