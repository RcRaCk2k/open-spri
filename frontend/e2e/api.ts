import { expect, type Page } from "@playwright/test";

/**
 * Test data through the public REST API with the session of the browser (cookie + CSRF header), like an
 * external system would create it. Only fictitious data.
 */
export async function apiPost<T>(page: Page, path: string, body: unknown): Promise<T> {
  const cookies = await page.context().cookies();
  if (!cookies.some((c) => c.name === "XSRF-TOKEN")) await page.request.get("/api/v1/auth/me");
  const csrf = (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")?.value ?? "";
  const response = await page.request.post(path, { data: body, headers: { "X-XSRF-TOKEN": csrf } });
  expect(response.status(), `${path}: ${await response.text()}`).toBeLessThan(300);
  return (await response.json()) as T;
}

export function uniqueSuffix(): string {
  return Date.now().toString(36).toUpperCase().slice(-6);
}

/** A partner with an unreachable endpoint: outgoing messages stay in the outbox (retry). */
export async function createPartner(page: Page, suffix: string): Promise<number> {
  const partner = await apiPost<{ id: number }>(page, "/api/v1/partners", {
    name: `Glasfaser Test ${suffix}`, carrierCode: `G${suffix}`.slice(0, 6), endpointUrl: "https://spri.partner.invalid/spri/v4",
  });
  return partner.id;
}

/** A NEU order as draft, in the JSON structure of the AK XSD (docs/api/auftraege.md). */
export function newOrder(partnerId: number, externalOrderNumber: string) {
  return {
    partnerId,
    auftrag: {
      externeAuftragsnummer: externalOrderNumber,
      auftraggeber: { auftraggebernummer: "AG4711", leistungsnummer: "LN0815" },
      geschaeftsfall: {
        "@type": "BereitstellungType",
        ansprechpartner: { auftragsmanagement: { anrede: "9", nachname: "Auftragsmanagement", telefonnummer: "0123 456789" } },
        termine: { auftraggeberWunschtermin: { datum: new Date(Date.now() + 30 * 86_400_000).toISOString().slice(0, 10) } },
        auftragsposition: {
          produkt: { "@type": "ProduktFTTXType", bezeichner: "FTTH 100" },
          geschaeftsfallProdukt: {
            "@type": "FTTXBereitstellungType",
            standortA: {
              person: { anrede: "1", vorname: "Erika", nachname: "Musterfrau" },
              strasse: { strassenname: "Musterstraße", hausnummer: "1" },
              postleitzahl: "12345",
              ort: { ortsname: "Musterstadt" },
            },
          },
        },
      },
      geschaeftsfallArt: "Bereitstellung",
      aenderungskennzeichen: "Standard",
    },
  };
}
