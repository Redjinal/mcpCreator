#!/usr/bin/env node
// Task 1: snapshot Envato's live filter schemas for drift checks.
// Talks ONLY to mcp.envato.com/mcp. Saves OUR schema snapshot, not catalog data.
// Run: node scripts/inspect-filters.mjs
import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";

const ENDPOINT = "https://mcp.envato.com/mcp";
const CATEGORIES = ["fonts", "graphics", "photos", "graphic_templates"];

async function rpc(method, params, id) {
  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id, method, params }),
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method}: HTTP ${res.status} ${text.slice(0, 200)}`);
  if ((res.headers.get("content-type") || "").includes("text/event-stream")) {
    for (const line of text.split("\n").filter((l) => l.startsWith("data:")).reverse()) {
      try {
        const msg = JSON.parse(line.slice(5).trim());
        if (msg.id === id) return msg.result;
      } catch {}
    }
    throw new Error(`${method}: no JSON-RPC reply in SSE body`);
  }
  return JSON.parse(text).result;
}

const init = await rpc("initialize", {
  protocolVersion: "2025-03-26",
  capabilities: {},
  clientInfo: { name: "harness-filter-inspect", version: "0.1" },
}, 1);
const { tools } = await rpc("tools/list", {}, 2);

const fp = JSON.stringify(tools.map((t) => [t.name, Object.keys(t.inputSchema?.properties || {}).sort()]));
const fingerprint = createHash("sha256").update(fp).digest("hex").slice(0, 16);

const snapshot = {
  capturedAt: new Date().toISOString(),
  server: init.serverInfo,
  fingerprint,
  tools: tools.map((t) => t.name),
  schemas: Object.fromEntries(
    CATEGORIES.map((c) => {
      const tool = tools.find((t) => t.name === `search_${c}`);
      return [`search_${c}`, tool ? tool.inputSchema : null];
    }),
  ),
};

const out = `docs/envato-filter-schemas-${snapshot.capturedAt.slice(0, 10)}.json`;
await mkdir("docs", { recursive: true });
await writeFile(out, JSON.stringify(snapshot, null, 2) + "\n");
console.log(`Server: ${JSON.stringify(init.serverInfo)}  fingerprint: ${fingerprint}`);
// Filters live under filters.anyOf[0].properties (an [object, null] anyOf), and each
// field is itself an [enum, null] anyOf — dig through both to print the enum values.
const enumValues = (s) => s?.enum || s?.items?.enum || s?.anyOf?.map((o) => o.enum || o.items?.enum).find(Boolean);
const filterProps = (schema) =>
  schema?.properties?.filters?.properties || schema?.properties?.filters?.anyOf?.find((o) => o.properties)?.properties || {};
for (const [name, schema] of Object.entries(snapshot.schemas)) {
  console.log(`\n${name}:`);
  for (const [f, s] of Object.entries(filterProps(schema))) {
    const values = enumValues(s);
    const type = s.type || s.anyOf?.map((o) => o.type).filter((t) => t && t !== "null").join("|") || "?";
    console.log(`  ${f}: ${values ? values.join(" | ") : type}`);
  }
}
console.log(`\nSaved ${out}`);
