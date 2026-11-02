/** Reading a Replikat record (`ds`, field names of Replikat.xsd 1.7, docs/api/verfuegbarkeit.md) for display. */

export interface ProductRow { product: string; technology: string; down: string; up: string; days: string }
export interface TechnologyRow { technology: string; status: string; orderKind: string; homeIds: string[]; ownerData: boolean | null }

type Json = Record<string, unknown>;
const obj = (v: unknown): Json => (v && typeof v === "object" && !Array.isArray(v) ? (v as Json) : {});
const str = (v: unknown): string => (typeof v === "string" || typeof v === "number" ? String(v) : "");
const arr = (v: unknown): unknown[] => (Array.isArray(v) ? v : []);

export function address(ds: unknown): string {
  const d = obj(ds);
  const house = [str(d.h), str(d.hz)].filter(Boolean).join(" ");
  const city = [str(d.plz), str(d.o), str(d.ot) && `(${str(d.ot)})`].filter(Boolean).join(" ");
  return [`${str(d.s)} ${house}`.trim(), str(d.gebaeudeteil), city].filter(Boolean).join(", ");
}

export function products(ds: unknown): ProductRow[] {
  return arr(obj(ds).pl).map((p) => {
    const o = obj(p);
    const bb = obj(o.erwarteteBandbreite);
    return { product: str(o.produkt), technology: str(o.technologie), down: str(bb.downBB), up: str(bb.upBB), days: str(o.bereitstellungszeit) };
  });
}

export function technologies(ds: unknown): TechnologyRow[] {
  return arr(obj(ds).tl).map((t) => {
    const o = obj(t);
    return {
      technology: str(o.technologie), status: str(o.ausbaustatus), orderKind: str(o.auftragsart),
      homeIds: arr(o.hidl).map(str).filter(Boolean), ownerData: typeof o.eigentuemerdaten === "boolean" ? o.eigentuemerdaten : null,
    };
  });
}

export function homeIds(ds: unknown): string[] {
  return [...new Set(technologies(ds).flatMap((t) => t.homeIds))];
}
