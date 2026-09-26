# Envato Elements Relay Harness — Handoff to Claude Code

**Date:** 26 September 2026
**Status:** Decided (provisional). Implementation pending.
**Origin:** Two Council deliberations in claude.ai (Evaluate on 25 Sep, Decide on 26 Sep). This file is the complete brief; nothing else is needed from the chat.

---

## 1. What we're building, in one paragraph

A thin, self-owned MCP server ("the harness") that sits between Claude and Envato's official MCP endpoint (`https://mcp.envato.com/mcp`). It relays Envato's search tools, translates design-system constraints into Envato's typed filters, and keeps a small ledger of which assets were chosen for which project. It is deployed on Vercel as a streamable-HTTP MCP, protected by a bearer token, and added to claude.ai as a custom connector so it's available inside Claude Design sessions. Target: under ~200 lines of application code.

## 2. Why (decision summary)

- **Need:** Envato Elements assets (fonts, imagery, mockups) inside Claude design-system work, without leaving the chat, with a record of what was licensed per project.
- **Elements has no public API.** The only sanctioned programmatic access is the official MCP server (blog post 3 Jul 2026, updated 14 Jul).
- **Access test passed (26 Sep 2026):** endpoint is anonymous, stateless (no session ID), reachable server-to-server, exposes 15 typed per-category search tools, no download/license tools. Server identifies as `SEO MCP Server` v3.2.4. Schema fingerprint: `43d3e68a1a595478`.
- **Alternatives rejected:** direct/scraped Elements access (breaches Acceptable Use Policy §6(e)); full multi-source "asset brain" (deferred to a later phase); bare connector only (fallback if the harness proves unused).

## 3. Compliance boundaries — NON-NEGOTIABLE

Envato's Acceptable Use Policy (revised 2 Sep 2026, §6(e)) prohibits any script, bot, API or automated tool to access, search, browse, copy, scrape, download or retrieve Envato sites or assets, *except where expressly made available or authorised by Envato*. The official MCP endpoint is expressly made available. Staying inside that exception means:

1. **The harness contacts exactly one Envato host: `mcp.envato.com`.** Never `elements.envato.com` or any other Envato domain, for any purpose, including previews or thumbnails. Preview URLs returned by Envato are passed through to Claude as strings, never fetched by the harness.
2. **No persistence of catalog data.** Search results live in memory for the duration of one request. No caching layer, no mirroring, no pre-fetching.
3. **No download or licensing automation.** Every result carries an Envato item URL; the user licenses and downloads on Envato's site. The harness never adds a "download" or "fetch file" tool.
4. **Nothing from Envato is used for model training or fine-tuning** (AUP §4(m), material breach).
5. **The harness is private.** Bearer-token auth on our route so only the owner's Claude account can call it.

Put these five points verbatim in the repo README. If any proposed feature conflicts with them, the feature loses.

## 4. Specification

### Runtime
- Vercel serverless route, streamable-HTTP MCP via the `mcp-handler` package (verify current package name/version on npm before installing; do not assume).
- Auth: `Authorization: Bearer <HARNESS_TOKEN>` on every request; reject otherwise. Token in Vercel env var.
- Upstream client: plain `fetch` JSON-RPC to `https://mcp.envato.com/mcp` with `Accept: application/json, text/event-stream`. Responses arrive as SSE frames (`data:` lines) or plain JSON; handle both. See `envato-mcp-access-test.mjs` for a working parser.
- **Introspect upstream at cold start:** call `tools/list`, discover `search_<category>` tool names and their `inputSchema` (including enum-constrained `filters`). Never hard-code Envato tool names; the schema is actively evolving (v3.2.4, `search_items` already deprecated).

### Tool 1 — `find_assets`
```
find_assets(category: string, brief: string, constraints?: object, limit?: number)
```
- `category`: one of Envato's categories (fonts, graphics, photos, graphic_templates, presentation_templates, stock_video, music, sound_effects, video_templates, web_templates, 3d, add_ons, wordpress, cms_templates). Map to `search_<category>`.
- `brief`: natural-language description from Claude → `searchTerms`.
- `constraints`: design-system-derived hints Claude passes in (e.g. `{ fontClassification: "sans-serif", mood: ["editorial","minimal"], orientation: "landscape", colours: ["#1a1a1a","#f5f0e8"] }`). **Map only onto enum filters that actually exist in the live schema**; fold everything unmappable into `searchTerms` as keywords. Never invent filters.
- Returns: array of `{ id, title, url, previewUrl, category, whyItFits }` where `whyItFits` is one line derived from which constraints matched. Default `limit` 8, max 20.
- The harness does NOT hold the design system. Claude already has the active design system loaded and passes constraints per call.

### Tool 2 — `record_choice`
```
record_choice(item_id: string, title: string, url: string, project: string, licensed?: boolean = false, note?: string)
```
- Appends `{ ...fields, chosenAt: ISO date }` to the ledger. Returns the entry.
- Storage: one JSON file in a Vercel Blob store (owner's data only). Read-modify-write is acceptable at this scale; last-write-wins.

### Tool 3 — `list_choices` (read-only companion)
```
list_choices(project?: string)
```
- Returns ledger entries, optionally filtered by project. This is the only tool beyond the two; do not add more without a Council `revisit`.

### Error behaviour
- Upstream 4xx/5xx or auth challenge → return a clear error naming the upstream status; **no retries** (avoid hammering an anonymous endpoint). Claude falls back to the bare Envato connector.
- Upstream schema missing an expected category → error listing the categories currently available.

## 5. Task list

1. **Enum inspection (first, before designing `find_assets`).** Script: connect to upstream, `tools/list`, dump the full `inputSchema.filters` enums for `search_fonts`, `search_graphics`, `search_photos`, `search_graphic_templates`. Save output to `docs/envato-filter-schemas-2026-09-26.json` in the repo (this is *our* schema snapshot for drift checks, not catalog data). Decide the constraint→filter mapping from what's actually there.
2. **Scaffold** the Vercel project: route, token auth, upstream client with SSE/JSON parsing, cold-start introspection.
3. **Implement** `find_assets`, `record_choice`, `list_choices`. Blob store for the ledger.
4. **Local test** with a generic MCP client (the access-test script can be adapted to point at localhost) — verify `tools/list` shows exactly three tools and a `find_assets` call round-trips.
5. **Deploy** to Vercel; set `HARNESS_TOKEN` and Blob credentials. Confirm the deployed URL responds to `initialize`.
6. **README** with the five compliance boundaries verbatim, setup steps, and the "add as custom connector in claude.ai" instructions (Customize → Connectors → + → name + URL; token via Advanced settings if the UI supports a header, otherwise as a query-string-free bearer per current claude.ai docs — verify).
7. **Hand back** to the user: deployed URL, token location, and a one-line test prompt for Claude ("Find three editorial serif fonts for the Meridian project").

## 6. Success signals / exit conditions

- Success: in a Claude Design session, `find_assets` returns usable results with previews and links; two `record_choice` entries exist after one real session; 30-day fingerprint recheck (`43d3e68a1a595478`) still matches or the relay's introspection absorbs the change.
- Delete the harness if: it's unused after six weeks; Envato adds per-user OAuth or publishes intermediary restrictions; or any feature request would breach §3 above.
- Revisit (expand ledger) if: Envato ships licensing/download tools in the official server.

## 7. Evidence references

- Envato MCP announcement: https://elements.envato.com/learn/envato-mcp-server
- Acceptable Use Policy: https://help.elements.envato.com/hc/en-us/articles/31035788503321-Acceptable-Use-Policy
- Elements License terms: https://help.elements.envato.com/hc/en-us/articles/360000628966-License-Terms
- Access test script and output: `envato-mcp-access-test.mjs` in this folder; output reproduced in §8.

## 8. Access test output (26 Sep 2026, 07:24 UTC)

```
→ initialize: HTTP 200 (text/event-stream)
   Server: {"name":"SEO MCP Server","version":"3.2.4"}
   Session id issued: no
→ tools/list: HTTP 200 (text/event-stream)
Tools exposed: 15
  search_photos, search_stock_video, search_music, search_sound_effects,
  search_graphics, search_graphic_templates, search_fonts,
  search_presentation_templates, search_video_templates, search_web_templates,
  search_3d, search_add_ons, search_wordpress, search_cms_templates,
  search_items (DEPRECATED — use search_<itemType>)
All search tools take: searchTerms, page, perPage, sortBy, languageCode, filters
Schema fingerprint: 43d3e68a1a595478
Download/license tools present: no
```

## 9. Open questions for the implementer (answer from evidence, not assumption)

- Does `mcp-handler` (or Vercel's current recommended MCP package) support streamable-HTTP with custom auth middleware? Check npm and Vercel docs.
- Do Envato's `filters` enums for fonts include a classification field (serif/sans/display)? Task 1 answers this.
- Does claude.ai's custom-connector UI allow a static bearer header? If not, implement the token as an OAuth-less shared secret the way current claude.ai docs recommend for remote MCP without OAuth.
