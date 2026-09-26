# Project: Envato Elements Relay Harness

Read `HANDOFF.md` in full before doing anything. It contains the decision, the spec, the task list, and the compliance boundaries.

## Hard rules (from HANDOFF.md §3 — do not relax)
- Only Envato host allowed anywhere in this codebase: `mcp.envato.com`. Grep for `envato.com` before every commit; any other Envato hostname is a bug.
- No caching, mirroring, or persistence of Envato catalog data. Ledger stores the user's own choices only.
- No download, fetch-file, preview-fetch, or licensing automation. Ever.
- Exactly three MCP tools: `find_assets`, `record_choice`, `list_choices`. Adding a fourth requires the user to run a Council `revisit` first.
- Never hard-code Envato tool names; discover them via `tools/list` at cold start.
- No retries against the upstream endpoint.

## Order of work
Follow HANDOFF.md §5 in sequence. Task 1 (enum inspection) comes before any `find_assets` design.

## Verification before handing back
- `tools/list` on the deployed URL returns exactly three tools.
- A `find_assets` call for fonts returns items with `url` and `previewUrl`.
- README contains the five boundaries verbatim.
- `envato-mcp-access-test.mjs` still passes against upstream; note the fingerprint in the README.
