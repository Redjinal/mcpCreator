#!/usr/bin/env node
// Envato MCP access test — decides Council path A vs B.
// Run:  node envato-mcp-access-test.mjs
// Optional bearer token if the endpoint asks for one:
//       ENVATO_MCP_TOKEN=xxx node envato-mcp-access-test.mjs
// Requires Node 18+ (built-in fetch). No packages needed.
//
// Boundary: this script talks ONLY to mcp.envato.com/mcp, stores nothing,
// downloads nothing. It performs an MCP initialize handshake, lists tools,
// and prints the schema so we can judge stability.

const ENDPOINT = process.env.ENVATO_MCP_URL || "https://mcp.envato.com/mcp";
const TOKEN = process.env.ENVATO_MCP_TOKEN || "";

const baseHeaders = {
  "Content-Type": "application/json",
  Accept: "application/json, text/event-stream",
  ...(TOKEN ? { Authorization: `Bearer ${TOKEN}` } : {}),
};

let sessionId = null;

async function rpc(method, params = {}, id = 1) {
  const res = await fetch(ENDPOINT, {
    method: "POST",
    headers: { ...baseHeaders, ...(sessionId ? { "Mcp-Session-Id": sessionId } : {}) },
    body: JSON.stringify({ jsonrpc: "2.0", id, method, params }),
  });
  const sid = res.headers.get("mcp-session-id");
  if (sid) sessionId = sid;

  const ctype = res.headers.get("content-type") || "";
  const text = await res.text();

  console.log(`\n→ ${method}: HTTP ${res.status} (${ctype.split(";")[0] || "no content-type"})`);
  if (res.status === 401 || res.status === 403) {
    console.log("   Auth challenge / refusal. Headers of interest:");
    for (const h of ["www-authenticate", "x-deny-reason", "cf-mitigated"]) {
      const v = res.headers.get(h);
      if (v) console.log(`   ${h}: ${v}`);
    }
    console.log("   Body:", text.slice(0, 400));
    return null;
  }
  if (!res.ok) {
    console.log("   Body:", text.slice(0, 400));
    return null;
  }

  // Streamable HTTP may answer as plain JSON or as SSE frames.
  if (ctype.includes("text/event-stream")) {
    const dataLines = text.split("\n").filter((l) => l.startsWith("data:"));
    for (const line of dataLines.reverse()) {
      try {
        const msg = JSON.parse(line.slice(5).trim());
        if (msg.id === id) return msg;
      } catch {}
    }
    console.log("   SSE received but no matching JSON-RPC reply. Raw head:", text.slice(0, 300));
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    console.log("   Non-JSON body:", text.slice(0, 300));
    return null;
  }
}

async function notify(method, params = {}) {
  await fetch(ENDPOINT, {
    method: "POST",
    headers: { ...baseHeaders, ...(sessionId ? { "Mcp-Session-Id": sessionId } : {}) },
    body: JSON.stringify({ jsonrpc: "2.0", method, params }),
  }).catch(() => {});
}

(async () => {
  console.log(`Envato MCP access test — ${new Date().toISOString()}`);
  console.log(`Endpoint: ${ENDPOINT}   Token supplied: ${TOKEN ? "yes" : "no"}`);

  const init = await rpc("initialize", {
    protocolVersion: "2025-03-26",
    capabilities: {},
    clientInfo: { name: "harness-access-test", version: "0.1" },
  });

  if (!init) {
    console.log("\nRESULT: endpoint not usable from a generic client with current settings.");
    console.log("If you saw a WWW-Authenticate header above, the endpoint wants OAuth/token auth.");
    console.log("Council path: A (bare endpoint in Claude + standalone ledger).");
    process.exit(2);
  }

  console.log("   Server:", JSON.stringify(init.result?.serverInfo || init.result, null, 0));
  console.log("   Session id issued:", sessionId ? "yes" : "no");
  if (init.result?.instructions) {
    console.log("\n   Server instructions (may contain usage terms — read these):\n");
    console.log(init.result.instructions);
  }

  await notify("notifications/initialized");

  const tools = await rpc("tools/list", {}, 2);
  const list = tools?.result?.tools || [];
  console.log(`\nTools exposed: ${list.length}`);
  for (const t of list) {
    console.log(`\n • ${t.name}`);
    if (t.description) console.log(`   ${t.description.split("\n")[0]}`);
    const props = t.inputSchema?.properties ? Object.keys(t.inputSchema.properties) : [];
    if (props.length) console.log(`   params: ${props.join(", ")}`);
  }

  // Schema fingerprint for the 30-day stability check.
  const fp = JSON.stringify(list.map((t) => [t.name, Object.keys(t.inputSchema?.properties || {}).sort()]));
  const { createHash } = await import("node:crypto");
  console.log(`\nSchema fingerprint (save this): ${createHash("sha256").update(fp).digest("hex").slice(0, 16)}`);

  const hasDownload = list.some((t) => /download|license|purchase/i.test(t.name + " " + (t.description || "")));
  console.log(`Download/license tools present: ${hasDownload ? "YES — changes the harness value case" : "no (search/metadata only, as documented)"}`);

  console.log("\nRESULT: endpoint reachable server-to-server.");
  console.log("Council path: B is viable (minimal relay harness). Paste this output back to Claude.");
})().catch((e) => {
  console.error("\nNetwork/runtime error:", e.message);
  console.log("If this is a DNS or TLS failure, the host may be unreachable from your network.");
  process.exit(1);
});
