// Maps design-system constraints onto enum filters that exist in the LIVE
// upstream schema. Nothing is hard-coded: a constraint value becomes a filter
// only if some filter field's enum contains it; everything else becomes a
// search keyword. Never invents filters.

type FilterField = { name: string; values: string[]; multi: boolean };

const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, "");

function enumOf(schema: any): { values: string[]; multi: boolean } | null {
  if (!schema || typeof schema !== "object") return null;
  if (Array.isArray(schema.enum)) return { values: schema.enum.map(String), multi: false };
  if (schema.type === "array" && schema.items) {
    const inner = enumOf(schema.items);
    return inner && { values: inner.values, multi: true };
  }
  for (const key of ["anyOf", "oneOf"]) {
    for (const option of schema[key] ?? []) {
      const found = enumOf(option);
      if (found) return found;
    }
  }
  return null;
}

export function filterFields(filtersSchema: any): FilterField[] {
  const props = filtersSchema?.properties ?? filtersSchema?.anyOf?.find((s: any) => s.properties)?.properties ?? {};
  return Object.entries(props).flatMap(([name, schema]) => {
    const e = enumOf(schema);
    return e ? [{ name, ...e }] : [];
  });
}

// Coarse hex → colour word, so "#1a1a1a" can match a colour enum or read as a keyword.
function colourName(hex: string): string | null {
  const m = /^#?([0-9a-f]{6}|[0-9a-f]{3})$/i.exec(hex.trim());
  if (!m) return null;
  const h = m[1].length === 3 ? [...m[1]].map((c) => c + c).join("") : m[1];
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
  const max = Math.max(r, g, b), min = Math.min(r, g, b), l = (max + min) / 2, d = max - min;
  if (d < 0.08) return l < 0.2 ? "black" : l > 0.85 ? "white" : "grey";
  if (l > 0.85 && d < 0.2) return "beige";
  let hue = max === r ? ((g - b) / d) % 6 : max === g ? (b - r) / d + 2 : (r - g) / d + 4;
  hue = (hue * 60 + 360) % 360;
  if (hue < 15 || hue >= 345) return "red";
  if (hue < 45) return l < 0.4 ? "brown" : "orange";
  if (hue < 70) return "yellow";
  if (hue < 170) return "green";
  if (hue < 200) return "teal";
  if (hue < 260) return "blue";
  if (hue < 300) return "purple";
  return "pink";
}

export type Mapped = { filters: Record<string, string | string[]>; keywords: string[]; matched: string[] };

export function mapConstraints(constraints: Record<string, unknown>, fields: FilterField[]): Mapped {
  const filters: Record<string, string | string[]> = {};
  const keywords: string[] = [];
  const matched: string[] = [];

  for (const [key, raw] of Object.entries(constraints)) {
    const values = (Array.isArray(raw) ? raw : [raw]).filter((v) => v != null && v !== "").map(String);
    for (const original of values) {
      const value = colourName(original) ?? original;
      // Prefer a field whose name resembles the constraint key, then any field.
      const candidates = [...fields].sort(
        (a, b) => Number(norm(b.name).includes(norm(key)) || norm(key).includes(norm(b.name))) -
          Number(norm(a.name).includes(norm(key)) || norm(key).includes(norm(a.name))),
      );
      let placed = false;
      for (const field of candidates) {
        const hit = field.values.find((v) => norm(v) === norm(value));
        if (!hit) continue;
        const current = filters[field.name];
        if (field.multi) filters[field.name] = [...((current as string[]) ?? []), hit];
        else if (current === undefined) filters[field.name] = hit;
        else continue; // single-value field already taken; try another field or fall through to keyword
        matched.push(`${field.name}=${hit}`);
        placed = true;
        break;
      }
      if (!placed) keywords.push(value);
    }
  }
  return { filters, keywords: [...new Set(keywords)], matched };
}
