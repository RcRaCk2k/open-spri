import { expect, test } from "@playwright/test";
import { expectAccessible } from "./a11y";
import { apiPost, createPartner, newOrder, uniqueSuffix } from "./api";

test("Einstellungen und Signaturschlüssel, Webhook mit einmaligem Geheimnis", async ({ page }) => {
  await page.goto("/settings");
  await page.getByLabel("Ort").fill("Musterstadt");
  await page.getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByText("Einstellungen gespeichert")).toBeVisible();
  await page.getByRole("tab", { name: "Signaturschlüssel" }).click();
  await expect(page.getByRole("cell", { name: "3072 Bit" })).toBeVisible();
  await expect(page.getByText("BEGIN CERTIFICATE")).toBeVisible();
  await expectAccessible(page, "/settings (Schlüssel)");

  const suffix = uniqueSuffix();
  await page.goto("/webhooks");
  await page.getByRole("button", { name: "Webhook anlegen" }).click();
  await page.getByRole("dialog").getByLabel("URL des Empfängers").fill(`https://crm-${suffix.toLowerCase()}.example/events`);
  await page.getByRole("dialog").getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByTestId("webhook-secret")).not.toBeEmpty();
  await page.getByRole("button", { name: "Fertig" }).click();
  const row = page.getByRole("row").filter({ hasText: `crm-${suffix.toLowerCase()}` });
  await row.getByRole("button", { name: "Ändern" }).click();
  await page.getByRole("dialog").getByRole("switch", { name: "aktiv" }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Speichern" }).click();
  await expect(row.getByText("inaktiv")).toBeVisible();
  await row.getByRole("button", { name: "Löschen" }).click();
  await expect(row).toHaveCount(0);
});

test("Zustellmonitor: wartende Nachricht abbrechen, Eingang, Wartung ankündigen", async ({ page }) => {
  const suffix = uniqueSuffix();
  const partnerId = await createPartner(page, suffix);
  const order = await apiPost<{ order: { id: number } }>(page, "/api/v1/orders?send=true", newOrder(partnerId, `ZM-${suffix}`));
  expect(order.order.id).toBeGreaterThan(0);

  await page.goto("/deliveries");
  await page.getByRole("combobox", { name: "Status" }).click();
  await page.getByRole("option", { name: "PENDING", exact: true }).click();
  const row = page.getByRole("row").filter({ hasText: `ZM-${suffix}` });
  await expect(row).toBeVisible();
  await expectAccessible(page, "/deliveries");
  await row.getByRole("button", { name: "Abbrechen" }).click();
  await page.getByRole("combobox", { name: "Status" }).click();
  await page.getByRole("option", { name: "CANCELLED" }).click();
  await expect(page.getByRole("row").filter({ hasText: `ZM-${suffix}` })).toContainText("CANCELLED");

  await page.getByRole("tab", { name: "Eingang" }).click();
  await expect(page.getByRole("combobox", { name: "Status" })).toHaveValue("FAILED");

  await page.getByRole("tab", { name: "Wartung" }).click();
  await page.getByRole("button", { name: "Wartung ankündigen" }).click();
  const dialog = page.getByRole("dialog");
  const day = (d: number) => new Date(Date.now() + d * 86_400_000).toISOString().slice(0, 10);
  await dialog.getByLabel("Betreff").fill(`Wartung ${suffix}`);
  await dialog.getByLabel("Beginn").fill(`${day(10)}T22:00`);
  await dialog.getByLabel("Ende").fill(`${day(11)}T02:00`);
  await dialog.getByLabel("Text").fill("Einspielen einer neuen Version; der S/PRI-Endpunkt ist kurz nicht erreichbar.");
  await dialog.getByRole("button", { name: "Senden" }).click();
  await expect(page.getByRole("cell", { name: `Wartung ${suffix}` })).toBeVisible();
});

test("Eigene Verfügbarkeit: Standort anlegen, Partner freigeben, Replikat erzeugen", async ({ page }) => {
  const suffix = uniqueSuffix();
  const partnerId = await createPartner(page, suffix);
  await page.goto("/availability/manage");
  await page.getByRole("button", { name: "Standort anlegen" }).click();
  const site = JSON.stringify({ plz: "12345", o: "Musterstadt", s: `Freigabeweg ${suffix}`, h: "3", pl: [{ produkt: "FTTH 250", technologie: "FTTH" }], tl: [{ technologie: "FTTH", ausbaustatus: "homesPassed", auftragsart: "STANDARD" }] });
  await page.getByRole("dialog").getByLabel("Datensatz").fill(site.replace('"12345"', '"1234X"'));
  await page.getByRole("dialog").getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("dialog").getByRole("alert")).toBeVisible();
  await page.getByRole("dialog").getByLabel("Datensatz").fill(site);
  await page.getByRole("dialog").getByRole("button", { name: "Speichern" }).click();
  await expect(page.getByRole("cell", { name: new RegExp(`Freigabeweg ${suffix} 3`) })).toBeVisible();

  await page.getByRole("tab", { name: "Replikat für Partner" }).click();
  await page.getByRole("button", { name: "Partner freigeben" }).click();
  await page.getByRole("dialog").getByRole("combobox", { name: "Partner" }).click();
  await page.getByRole("option", { name: `Glasfaser Test ${suffix}` }).click();
  await page.getByRole("dialog").getByRole("button", { name: "Speichern" }).click();
  const row = page.getByRole("row").filter({ hasText: `Glasfaser Test ${suffix}` });
  await row.getByRole("button", { name: "Jetzt erzeugen" }).click();
  await expect(page.getByRole("dialog").getByRole("cell", { name: /\.zip$/ }).first()).toBeVisible();
  await expectAccessible(page, "Replikat-Dateien");
  void partnerId;
});
