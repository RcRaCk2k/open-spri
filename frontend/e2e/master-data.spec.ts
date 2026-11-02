import { readFileSync } from "node:fs";
import { expect, test } from "@playwright/test";
import { expectAccessible } from "./a11y";
import { uniqueSuffix } from "./api";

/** A fictitious self-signed certificate (RSA 3072, no private key in the repository). */
const CERTIFICATE = readFileSync(new URL("./fixtures/partner-signature.pem", import.meta.url), "utf8");

test("Partner anlegen, Zertifikat, IP-Freigaben, Rahmenvertrag und Projekt", async ({ page }) => {
  const suffix = uniqueSuffix();
  await page.goto("/partners");
  await page.getByRole("button", { name: "Partner anlegen" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Name").fill(`Netz ${suffix}`);
  await dialog.getByLabel("ITU Carrier Code").fill("viel-zu-lang");
  await dialog.getByRole("button", { name: "Anlegen" }).click();
  await expect(dialog.getByText("1 bis 6 Großbuchstaben oder Ziffern")).toBeVisible();
  await dialog.getByLabel("ITU Carrier Code").fill(`n${suffix}`.slice(0, 6));
  await dialog.getByLabel("Freigeschaltete Quell-IPs (CIDR)").fill("203.0.113.10/32");
  await dialog.getByLabel("Signaturzertifikat (PEM)").fill(CERTIFICATE);
  await dialog.getByRole("button", { name: "Anlegen" }).click();

  await expect(page.getByRole("heading", { name: `Netz ${suffix}` })).toBeVisible();
  await page.getByRole("tab", { name: /Zertifikate/ }).click();
  // self-signed: subject and issuer are the same
  await expect(page.getByText("CN=E2E Testpartner Signatur", { exact: false }).first()).toBeVisible();
  await expect(page.getByRole("cell", { name: "3072" })).toBeVisible();

  await page.getByRole("tab", { name: /IP-Freigaben/ }).click();
  await page.getByLabel("Quell-IPs (CIDR, eine pro Zeile)").fill("203.0.113.10/32\nkein-netz");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await page.getByLabel("Quell-IPs (CIDR, eine pro Zeile)").fill("203.0.113.10/32\n198.51.100.0/24");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("tab", { name: "IP-Freigaben (2)" })).toBeVisible();

  await page.getByRole("tab", { name: "Verträge und Vollmachten" }).click();
  await page.getByRole("button", { name: "Hinzufügen" }).first().click();
  await page.getByRole("dialog").getByLabel("Auftraggebernummer").fill("AG4711");
  await page.getByRole("dialog").getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("cell", { name: "AG4711" })).toBeVisible();
  await page.getByRole("button", { name: "Hinzufügen" }).last().click();
  await page.getByRole("dialog").getByLabel("Projektkenner").fill(`PRJ-${suffix}`);
  await page.getByRole("dialog").getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("cell", { name: `PRJ-${suffix}` })).toBeVisible();
  await expectAccessible(page, "Partner (Verträge)");

  await page.getByRole("link", { name: "← zur Liste" }).click();
  await page.getByRole("button", { name: "Unsere Angaben für Partner" }).click();
  await expect(page.getByRole("dialog").getByText("BEGIN CERTIFICATE")).toBeVisible();
  await expectAccessible(page, "Onboarding");
});

test("Produkt anlegen, Wechselmatrix pflegen und prüfen, Meldungscodes filtern", async ({ page }) => {
  const suffix = uniqueSuffix();
  await page.goto("/products");
  await page.getByRole("button", { name: "Produkt anlegen" }).click();
  await page.getByRole("dialog").getByLabel("Produktbezeichner").fill(`FTTH ${suffix}`);
  await page.getByRole("dialog").getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByText(`FTTH ${suffix}`)).toBeVisible();

  await page.getByRole("tab", { name: "Produktwechselmatrix" }).click();
  await page.getByRole("button", { name: "Zeile hinzufügen" }).click();
  const rows = await page.getByLabel(/^Bestandsprodukt/).count();
  await page.getByLabel(`Bestandsprodukt ${rows}`).fill(`FTTH ${suffix}`);
  await page.getByLabel(`Zielprodukt ${rows}`).fill(`FTTH ${suffix} Plus`);
  await page.getByRole("button", { name: "Matrix speichern" }).click();
  await expect(page.getByText(/Wechsel gespeichert/)).toBeVisible();
  await page.getByLabel("Wechsel prüfen: von").fill(`FTTH ${suffix}`);
  await page.getByLabel("nach").fill(`FTTH ${suffix} Plus`);
  await page.getByRole("button", { name: "Prüfen" }).click();
  await expect(page.getByText(`FTTH ${suffix} → FTTH ${suffix} Plus: LAE`)).toBeVisible();
  await expectAccessible(page, "Produktwechselmatrix");

  await page.goto("/message-codes");
  await page.getByLabel("Code oder Text").fill("Lokale Ergänzung");
  await expect(page.getByText("lokal").first()).toBeVisible();
});
