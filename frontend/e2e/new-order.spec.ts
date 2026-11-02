import { expect, test } from "@playwright/test";
import { expectAccessible } from "./a11y";
import { apiPost, createPartner, uniqueSuffix } from "./api";

test("Assistent: KUE-AG aus Angaben zusammensetzen, fehlende Angaben sehen, als Entwurf speichern", async ({ page }) => {
  const suffix = uniqueSuffix();
  const partnerId = await createPartner(page, suffix);
  await page.goto("/orders");
  await page.getByRole("link", { name: "Neuer Auftrag" }).click();
  await expect(page.getByRole("heading", { name: "Neuer Auftrag" })).toBeVisible();

  await page.getByRole("combobox", { name: "Partner (Leistungserbringer)" }).click();
  await page.getByRole("option", { name: new RegExp(`Glasfaser Test ${suffix}`) }).click();
  await page.getByRole("combobox", { name: "Geschäftsfall" }).click();
  await page.getByRole("option", { name: "KUE-AG – Kündigung" }).click();
  await page.getByLabel("Externe Auftragsnummer").fill(`KUE-E2E-${suffix}`);
  await page.getByRole("button", { name: "Prüfen und Vorschau" }).click();
  await expect(page.getByText("Es fehlen noch Angaben")).toBeVisible();
  await expect(page.getByText("geschaeftsfall/vertragsnummer")).toBeVisible();
  await expectAccessible(page, "/orders/new");

  await page.getByRole("textbox", { name: "Vertragsnummer", exact: true }).fill("V-E2E-1");
  await page.getByRole("textbox", { name: "LineID", exact: true }).fill(`DEU.E2E.${suffix}`);
  await page.getByLabel("Auftraggebernummer").fill("AG4711");
  await page.getByLabel("Leistungsnummer").fill("LN0815");
  await page.getByLabel("Wunschtermin").fill(new Date(Date.now() + 40 * 86_400_000).toISOString().slice(0, 10));
  await page.getByLabel("Ansprechpartner Auftragsmanagement").fill("Auftragsmanagement");
  await page.getByLabel("Telefon Auftragsmanagement").fill("0123 456789");
  await page.getByRole("button", { name: "Prüfen und Vorschau" }).click();
  await expect(page.getByText("Alle Angaben des Geschäftsfalls sind vorhanden")).toBeVisible();
  await expect(page.getByLabel("Auftrag (XSD-JSON)")).toHaveValue(/KuendigungType/);
  await page.getByRole("button", { name: "Als Entwurf speichern" }).click();

  await expect(page.getByRole("heading", { name: `Auftrag KUE-E2E-${suffix}` })).toBeVisible();
  await expect(page.getByText("Entwurf", { exact: true }).first()).toBeVisible();
  expect(page.url()).toMatch(/\/orders\/\d+$/);
  void partnerId;
});

/**
 * The instance offers its own location and queries itself online, as a partner with open-spri would: own site,
 * release, partner token, online source (docs/api/verfuegbarkeit.md). Then search, order template, wizard, draft.
 */
test("Verfügbarkeit suchen und Treffer als NEU-Auftrag übernehmen", async ({ page }) => {
  const suffix = uniqueSuffix();
  const street = `E2E-Weg ${suffix}`;
  await apiPost(page, "/api/v1/availability/sites", {
    plz: "12345", o: "Musterstadt", s: street, h: "7",
    pl: [{ produkt: "FTTH 100", technologie: "FTTH", erwarteteBandbreite: { downBB: "100", upBB: "40" } }],
    tl: [{ technologie: "FTTH", ausbaustatus: "homesConnected", auftragsart: "STANDARD", hidl: [`HID${suffix}`] }],
  });
  const self = await apiPost<{ id: number }>(page, "/api/v1/partners", {
    name: `Selbst ${suffix}`, carrierCode: `S${suffix}`.slice(0, 6), allowedSourceNetworks: ["127.0.0.1/32", "::1/128"],
  });
  const release = await page.request.put(`/api/v1/replikat/releases/${self.id}`, {
    data: { partnerId: self.id }, headers: { "X-XSRF-TOKEN": (await page.context().cookies()).find((c) => c.name === "XSRF-TOKEN")!.value },
  });
  expect(release.status()).toBe(200);
  const token = await apiPost<{ token: string }>(page, "/api/v1/tokens", { name: `Online ${suffix}`, role: "PARTNER", partnerId: self.id });
  await apiPost(page, "/api/v1/replikat/sources", { partnerId: self.id, kind: "ONLINE", url: "http://127.0.0.1:8080", secret: token.token });

  await page.goto("/availability");
  await page.getByLabel("PLZ").fill("12345");
  await page.getByLabel("Straße").fill(street);
  await page.getByRole("button", { name: "Suchen" }).click();
  // earlier runs leave other "Selbst" partners that answer for the same site
  const row = page.getByRole("row").filter({ hasText: street }).filter({ hasText: `Selbst ${suffix}` });
  await expect(row).toContainText("FTTH 100");
  await expect(row).toContainText(`HID${suffix}`);
  await expectAccessible(page, "/availability (Treffer)");

  await row.getByRole("button", { name: "Auftrag anlegen" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Weiter zum Auftrag" }).click();
  await expect(page.getByRole("heading", { name: "Neuer Auftrag" })).toBeVisible();
  await expect(page.getByText(`Aus der Verfügbarkeit bei Selbst ${suffix}`)).toBeVisible();
  await expect(page.getByLabel("Straße")).toHaveValue(street);
  await expect(page.getByLabel("HomeID")).toHaveValue(`HID${suffix}`);
  await expect(page.getByRole("combobox", { name: "Geschäftsfall" })).toHaveValue("NEU – Bereitstellung");
});
