// Local dev server: serves the same handler Vercel runs, at http://localhost:3000/mcp.
import { createServer } from "node:http";
import { handler } from "../lib/server.js";

const port = Number(process.env.PORT || 3000);

createServer(async (req, res) => {
  const chunks: Buffer[] = [];
  for await (const c of req) chunks.push(c as Buffer);
  const request = new Request(`http://localhost:${port}${req.url}`, {
    method: req.method,
    headers: req.headers as Record<string, string>,
    body: ["GET", "HEAD"].includes(req.method!) ? undefined : Buffer.concat(chunks),
  });
  const response = await handler(request);
  res.writeHead(response.status, Object.fromEntries(response.headers));
  res.end(Buffer.from(await response.arrayBuffer()));
}).listen(port, () => console.log(`harness listening on http://localhost:${port}/mcp`));
