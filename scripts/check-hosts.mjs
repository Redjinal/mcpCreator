#!/usr/bin/env node
// Compliance guard: mcp.envato.com is the only Envato host allowed in code.
// (README/HANDOFF may cite other Envato pages as evidence references.)
import { execSync } from "node:child_process";
import { readFileSync } from "node:fs";

const files = execSync("git ls-files -co --exclude-standard api lib scripts vercel.json package.json", { encoding: "utf8" })
  .split("\n").filter(Boolean);
const bad = files.flatMap((f) =>
  [...readFileSync(f, "utf8").matchAll(/[a-z0-9.-]*envato\.com/gi)]
    .filter((m) => m[0].toLowerCase() !== "mcp.envato.com")
    .map((m) => `${f}: ${m[0]}`),
);
if (bad.length) {
  console.error("Disallowed Envato hosts found:\n" + bad.join("\n"));
  process.exit(1);
}
console.log(`OK: only mcp.envato.com referenced across ${files.length} files`);
