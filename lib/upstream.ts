// Upstream client for Envato's official MCP endpoint.
// Boundary: the ONLY Envato host this codebase contacts is mcp.envato.com.
// No retries, no caching of results — schema introspection only.

export const UPSTREAM_URL = "https://mcp.envato.com/mcp";

// Tests may point at a localhost mock; any other override is ignored.
function endpoint(): string {
  const override = process.env.UPSTREAM_TEST_URL;
  if (override && ["localhost", "127.0.0.1"].includes(new URL(override).hostname)) return override;
  return UPSTREAM_URL;
}

export type UpstreamTool = {
  name: string;
  description?: string;
  inputSchema?: { properties?: Record<string, any> };
};

export class UpstreamError extends Error {}

let nextId = 1;

export async function rpc(method: string, params: object = {}): Promise<any> {
  const id = nextId++;
  const res = await fetch(endpoint(), {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id, method, params }),
  });
  const text = await res.text();
  if (!res.ok) {
    const challenge = res.headers.get("www-authenticate");
    throw new UpstreamError(
      `Envato upstream ${method} failed: HTTP ${res.status}${challenge ? ` (auth challenge: ${challenge})` : ""}. ` +
        "Fall back to the bare Envato connector.",
    );
  }
  const msg = parseReply(text, res.headers.get("content-type") || "", id);
  if (!msg) throw new UpstreamError(`Envato upstream ${method}: no JSON-RPC reply in response body`);
  if (msg.error) throw new UpstreamError(`Envato upstream ${method} error ${msg.error.code}: ${msg.error.message}`);
  return msg.result;
}

// Streamable HTTP may answer as plain JSON or as SSE `data:` frames.
export function parseReply(text: string, contentType: string, id: number): any {
  if (contentType.includes("text/event-stream")) {
    for (const line of text.split("\n").filter((l) => l.startsWith("data:")).reverse()) {
      try {
        const msg = JSON.parse(line.slice(5).trim());
        if (msg.id === id) return msg;
      } catch {}
    }
    return null;
  }
  try {
    return JSON.parse(text);
  } catch {
    return null;
  }
}

// Cold-start introspection: started once per instance, awaited by find_assets.
// A failed attempt is forgotten so the next tool call makes one fresh attempt.
let toolsPromise: Promise<Map<string, UpstreamTool>> | null = null;

export function upstreamTools(): Promise<Map<string, UpstreamTool>> {
  toolsPromise ??= (async () => {
    await rpc("initialize", {
      protocolVersion: "2025-03-26",
      capabilities: {},
      clientInfo: { name: "envato-relay-harness", version: "0.1.0" },
    });
    const { tools = [] } = await rpc("tools/list");
    return new Map((tools as UpstreamTool[]).map((t) => [t.name, t]));
  })().catch((e) => {
    toolsPromise = null;
    throw e;
  });
  return toolsPromise;
}

export async function searchTool(category: string): Promise<UpstreamTool> {
  const tools = await upstreamTools();
  const tool = tools.get(`search_${category}`);
  if (tool) return tool;
  const available = [...tools.keys()]
    .filter((n) => n.startsWith("search_") && n !== "search_items")
    .map((n) => n.slice("search_".length));
  throw new UpstreamError(`Unknown category "${category}". Categories currently available upstream: ${available.join(", ")}`);
}
