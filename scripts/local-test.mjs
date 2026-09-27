#!/usr/bin/env node
// End-to-end check of the harness over plain JSON-RPC.
//   Local (mock upstream):  npm test
//   Deployed:               HARNESS_URL=https://<app>.vercel.app/mcp HARNESS_TOKEN=... npm test
import { spawn } from "node:child_process";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import assert from "node:assert/strict";
import { startMock } from "./mock-upstream.mjs";

const remote = process.env.HARNESS_URL;
let url = remote, token = process.env.HARNESS_TOKEN, mock, dev;

if (!remote) {
  token = "test-token";
  mock = await startMock(3901);
  dev = spawn(process.execPath, ["--import", "tsx", "scripts/dev-server.ts"], {
    env: { ...process.env, PORT: "3900", HARNESS_TOKEN: token, UPSTREAM_TEST_URL: "http://127.0.0.1:3901/mcp",
      BLOB_READ_WRITE_TOKEN: "", LEDGER_FILE: join(mkdtempSync(join(tmpdir(), "ledger-")), "ledger.json") },
    stdio: ["ignore", "pipe", "inherit"],
  });
  await new Promise((r) => dev.stdout.on("data", (d) => d.toString().includes("listening") && r()));
  url = "http://localhost:3900/mcp";
}

let id = 0;
async function rpc(method, params = {}, auth = token) {
  const res = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream",
      ...(auth ? { Authorization: `Bearer ${auth}` } : {}) },
    body: JSON.stringify({ jsonrpc: "2.0", id: ++id, method, params }),
  });
  const text = await res.text();
  if (!res.ok) return { status: res.status };
  const line = (res.headers.get("content-type") || "").includes("event-stream")
    ? text.split("\n").filter((l) => l.startsWith("data:")).map((l) => l.slice(5)).pop() : text;
  return { status: res.status, ...JSON.parse(line) };
}
const call = async (name, args) => {
  const r = await rpc("tools/call", { name, arguments: args });
  return { isError: r.result.isError, value: r.result.isError ? r.result.content[0].text : JSON.parse(r.result.content[0].text) };
};
const ok = (m) => console.log(`  ✓ ${m}`);

try {
  assert.equal((await rpc("initialize", {}, null)).status, 401); ok("no token → 401");
  assert.equal((await rpc("initialize", {}, "wrong")).status, 401); ok("wrong token → 401");

  // Auth accepted from any claude.ai connector preset header, same token.
  const rawPost = (headers) => fetch(url, { method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream", ...headers },
    body: JSON.stringify({ jsonrpc: "2.0", id: ++id, method: "initialize", params: {} }) }).then((r) => r.status);
  for (const h of [{ "x-auth-token": token }, { "x-api-key": token }, { authorization: token }]) {
    assert.equal(await rawPost(h), 200, `${Object.keys(h)[0]} should authenticate`);
  }
  assert.equal(await rawPost({ "x-auth-token": "wrong" }), 401, "wrong x-auth-token → 401");
  ok("alt auth headers (x-auth-token, x-api-key, bare Authorization) → 200; wrong → 401");

  const init = await rpc("initialize", { protocolVersion: "2025-03-26", capabilities: {}, clientInfo: { name: "local-test", version: "0" } });
  assert.equal(init.result.serverInfo.name, "envato-relay-harness"); ok(`initialize → ${JSON.stringify(init.result.serverInfo)}`);

  const names = (await rpc("tools/list")).result.tools.map((t) => t.name).sort();
  assert.deepEqual(names, ["find_assets", "list_choices", "record_choice"]); ok(`tools/list → exactly 3: ${names.join(", ")}`);

  const found = await call("find_assets", { category: "fonts", brief: "editorial serif", limit: 3,
    constraints: { fontClassification: "serif", mood: ["editorial", "minimal"], colours: ["#1a1a1a"] } });
  assert.ok(!found.isError, found.value);
  assert.ok(found.value.length >= 1 && found.value.every((a) => a.id && a.url && a.previewUrl), "items need id + url + previewUrl");
  ok(`find_assets fonts → ${found.value.length} items, e.g. ${JSON.stringify(found.value[0])}`);

  if (mock) {
    assert.equal(found.value.length, 3);
    const sent = mock.calls.find((c) => c.method === "tools/call").params.arguments;
    assert.deepEqual(sent.filters, { categories: "serif" });
    assert.equal(sent.searchTerms, "editorial serif minimal black");
    assert.equal(sent.perPage, 3);
    ok(`constraint mapping → filters ${JSON.stringify(sent.filters)}, searchTerms "${sent.searchTerms}"`);

    const unknown = await call("find_assets", { category: "sculptures", brief: "x" });
    assert.ok(unknown.isError && /fonts, photos, graphics/.test(unknown.value)); ok(`unknown category → ${unknown.value}`);

    const before = mock.calls.length;
    const down = await call("find_assets", { category: "fonts", brief: "boom" });
    assert.ok(down.isError && /HTTP 503/.test(down.value));
    assert.equal(mock.calls.length, before + 1, "must not retry upstream");
    ok(`upstream 503 → error, no retry: ${down.value}`);

    const project = "Meridian";
    const rec = await call("record_choice", { item_id: "item-1", title: "Test Serif 1", url: "https://items.example.test/item-1", project });
    assert.equal(rec.value.licensed, false); assert.ok(rec.value.chosenAt); ok("record_choice → entry with chosenAt, licensed=false");
    await call("record_choice", { item_id: "p-2", title: "Photo", url: "https://items.example.test/p-2", project: "Other", licensed: true });
    assert.equal((await call("list_choices", { project })).value.length, 1);
    assert.equal((await call("list_choices", {})).value.length, 2); ok("list_choices filters by project");
  }
  console.log("\nAll checks passed.");
} finally {
  dev?.kill();
  mock?.server.closeAllConnections();
  mock?.server.close();
}
