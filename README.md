# Envato Elements Relay Harness

A private MCP server that sits between Claude and Envato's official MCP endpoint (`https://mcp.envato.com/mcp`). It relays Envato's search tools, maps design-system constraints onto Envato's live filter enums, and keeps a small ledger of which assets were chosen for which project.

Full brief and decision record: [`HANDOFF.md`](HANDOFF.md).

## Compliance boundaries — NON-NEGOTIABLE

1. **The harness contacts exactly one Envato host: `mcp.envato.com`.** Never `elements.envato.com` or any other Envato domain, for any purpose, including previews or thumbnails. Preview URLs returned by Envato are passed through to Claude as strings, never fetched by the harness.
2. **No persistence of catalog data.** Search results live in memory for the duration of one request. No caching layer, no mirroring, no pre-fetching.
3. **No download or licensing automation.** Every result carries an Envato item URL; the user licenses and downloads on Envato's site. The harness never adds a "download" or "fetch file" tool.
4. **Nothing from Envato is used for model training or fine-tuning** (AUP §4(m), material breach).
5. **The harness is private.** Bearer-token auth on our route so only the owner's Claude account can call it.

If any proposed feature conflicts with these, the feature loses. `npm run check-hosts` fails if code references any Envato host other than `mcp.envato.com`; run it before every commit.

## Tools

Exactly three. Adding a fourth requires a Council `revisit` first.

| Tool | Arguments | Returns |
|---|---|---|
| `find_assets` | `category`, `brief`, `constraints?`, `limit?` (default 8, max 20) | `[{ id, title, url, previewUrl, category, whyItFits }]` |
| `record_choice` | `item_id`, `title`, `url`, `project`, `licensed?` (default false), `note?` | the ledger entry, with `chosenAt` |
| `list_choices` | `project?` | ledger entries |

**How `find_assets` maps constraints.** At cold start the harness calls upstream `tools/list` and reads each `search_<category>` tool's `inputSchema`. No Envato tool names or filter names are hard-coded. For each constraint value, the harness looks for a `filters` field whose enum contains that value (case and punctuation are ignored). It tries fields whose names resemble the constraint key first. A value that matches becomes a filter. A value that doesn't match is appended to `searchTerms` as a keyword. Hex colours are first reduced to a coarse colour word (`#1a1a1a` → `black`), so they can match a colour enum or serve as a keyword. `whyItFits` names the filters that matched and any keywords that appear in the item's text.

**Errors.** An upstream 4xx/5xx or auth challenge is reported with the upstream status and is never retried, so Claude can fall back to the bare Envato connector. An unknown category gets back the list of categories currently available upstream.

## Layout

```
api/mcp.ts          Vercel function (GET/POST/DELETE) → lib/server.ts
lib/server.ts       bearer auth + the three tool registrations (mcp-handler 2.x, MCP SDK v2)
lib/upstream.ts     JSON-RPC client for mcp.envato.com (SSE + JSON replies), cold-start introspection
lib/mapping.ts      constraint → live-enum filter mapping
lib/assets.ts       find_assets: call upstream, normalise results
lib/ledger.ts       ledger in a private Vercel Blob (local JSON file when no Blob token)
scripts/            access test, filter inspector, local test + localhost mock, host guard, dev server
docs/               upstream schema snapshots (our drift-check data, not catalog data)
```

## Setup

Requires Node 20+.

```bash
npm install
npm run typecheck
npm test               # local end-to-end test against a localhost mock of the upstream
npm run check-hosts
```

`npm run dev` serves the harness at `http://localhost:3000/mcp`. It needs `HARNESS_TOKEN` in the environment. Without `BLOB_READ_WRITE_TOKEN` the ledger is written to `LEDGER_FILE` (default `.ledger.local.json`, which is gitignored).

### Upstream checks (need network access to `mcp.envato.com`)

```bash
npm run access-test       # original access test; compare its fingerprint with the one below
npm run inspect-filters   # Task 1: writes docs/envato-filter-schemas-<date>.json and prints the filter enums
```

### Deploy to Vercel

1. Import this repo as a Vercel project. No framework preset is needed; `api/mcp.ts` is picked up automatically, and `vercel.json` rewrites `/mcp` to it.
2. Storage → create a **Blob** store and connect it to the project. This sets `BLOB_READ_WRITE_TOKEN`. The ledger is stored as a private blob, `ledger.json`.
3. Settings → Environment Variables → add `HARNESS_TOKEN` (generate one with `openssl rand -hex 32`).
4. Deploy, then check it: `HARNESS_URL=https://<app>.vercel.app/mcp HARNESS_TOKEN=<token> npm test`. Against a deployed URL, the test runs the token, `initialize`, `tools/list` and real `find_assets` checks. It does not write to the ledger.

### Add as a custom connector in claude.ai

Customize → Connectors → **+** / Add custom connector:

- **Name:** `Envato Relay`
- **URL:** `https://<app>.vercel.app/mcp`
- **Authentication:** *No sign-in*. Then under **Request headers**, add `Authorization` with the value `Bearer <HARNESS_TOKEN>`. Include the `Bearer ` prefix.

Static request headers are a beta feature of the custom-connector dialog (see [Anthropic's connector docs](https://claude.com/docs/connectors/custom/add-unlisted#authenticate-with-request-headers)). If your account doesn't offer them yet, the harness can't be added until they're available. Don't move the token into the URL, because the docs advise against secrets in URLs and query strings end up in logs.

Test prompt: *"Find three editorial serif fonts for the Meridian project."*

## Upstream fingerprint

| Date | Server | Fingerprint | Source |
|---|---|---|---|
| 2026-09-26 | `SEO MCP Server` v3.2.4 | `43d3e68a1a595478` | HANDOFF.md §8 (owner's access test) |

Re-run `npm run access-test` about 30 days later and add a row. A different fingerprint is fine as long as `find_assets` still works, because introspection absorbs schema changes.
