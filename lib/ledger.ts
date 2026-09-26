// Ledger of the owner's own asset choices. Stores ONLY what the user chose —
// never Envato catalog data or search results.
// Vercel Blob (private) in production; a local JSON file when no Blob token is set.

import { get, put } from "@vercel/blob";
import { readFile, writeFile } from "node:fs/promises";

export type Choice = {
  item_id: string;
  title: string;
  url: string;
  project: string;
  licensed: boolean;
  note?: string;
  chosenAt: string;
};

const PATHNAME = "ledger.json";
const useBlob = () => Boolean(process.env.BLOB_READ_WRITE_TOKEN);
const localFile = () => process.env.LEDGER_FILE || ".ledger.local.json";

async function readLedger(): Promise<Choice[]> {
  if (!useBlob()) {
    try {
      return JSON.parse(await readFile(localFile(), "utf8"));
    } catch {
      return [];
    }
  }
  const res = await get(PATHNAME, { access: "private", useCache: false }).catch((e) => {
    if (e?.name === "BlobNotFoundError") return null;
    throw e;
  });
  if (!res || res.statusCode !== 200) return [];
  return JSON.parse(await new Response(res.stream).text());
}

async function writeLedger(entries: Choice[]): Promise<void> {
  const body = JSON.stringify(entries, null, 2);
  if (!useBlob()) return writeFile(localFile(), body);
  await put(PATHNAME, body, { access: "private", allowOverwrite: true, addRandomSuffix: false, contentType: "application/json" });
}

// Read-modify-write; last write wins (acceptable at single-owner scale).
export async function recordChoice(input: Omit<Choice, "chosenAt" | "licensed"> & { licensed?: boolean }): Promise<Choice> {
  const entry: Choice = { ...input, licensed: input.licensed ?? false, chosenAt: new Date().toISOString() };
  const entries = await readLedger();
  entries.push(entry);
  await writeLedger(entries);
  return entry;
}

export async function listChoices(project?: string): Promise<Choice[]> {
  const entries = await readLedger();
  return project ? entries.filter((e) => e.project === project) : entries;
}
