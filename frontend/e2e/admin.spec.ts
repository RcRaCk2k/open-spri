import { expect, test } from "@playwright/test";

test("Dashboard zeigt Aufgaben, Fristen und Zustellfehler", async ({ page }) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  for (const name of ["Offene Aufträge", "Zustellfehler", "Terminabstimmung offen (TAM/MTAM)", "Providerwechsel: RUEM-PV fällig"]) {
    await expect(page.getByRole("heading", { name })).toBeVisible();
  }
  await expect(page.getByText("Alle Nachrichten wurden zugestellt.")).toBeVisible();
});

test("Benutzer anlegen, Token ausstellen und widerrufen, alles im Audit-Log", async ({ page }) => {
  const suffix = Date.now().toString(36);
  await page.goto("/users");

  await page.getByRole("button", { name: "Benutzer anlegen" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Benutzername").fill(`e2e.${suffix}`);
  await dialog.getByLabel("Anzeigename").fill(`Erika E2E ${suffix}`);
  await dialog.getByRole("textbox", { name: "Passwort", exact: true }).fill("kurz");
  await dialog.getByRole("button", { name: "Anlegen" }).click();
  await expect(dialog.getByText("mindestens 12 Zeichen").last()).toBeVisible();
  await dialog.getByRole("textbox", { name: "Passwort", exact: true }).fill("e2e-passwort-fuer-erika");
  await dialog.getByRole("button", { name: "Anlegen" }).click();
  await expect(page.getByRole("cell", { name: new RegExp(`Erika E2E ${suffix}`) })).toBeVisible();

  await page.getByRole("tab", { name: "API-Tokens" }).click();
  await page.getByRole("button", { name: "Token ausstellen" }).click();
  await page.getByRole("dialog").getByLabel("Name").fill(`CRM ${suffix}`);
  await page.getByRole("dialog").getByRole("button", { name: "Ausstellen" }).click();
  await expect(page.getByTestId("token-secret")).toHaveText(/^spri_/);
  await page.getByRole("button", { name: "Fertig" }).click();
  await page.getByRole("button", { name: `Token CRM ${suffix} widerrufen` }).click();
  await expect(page.getByRole("row", { name: new RegExp(`CRM ${suffix}`) }).getByText("widerrufen")).toBeVisible();

  await page.getByRole("link", { name: "Audit-Log" }).click();
  await page.getByLabel("Aktion (Anfang)").fill("token.");
  await expect(page.getByRole("cell", { name: "token.revoke" }).first()).toBeVisible();
  await expect(page.getByRole("cell", { name: "token.create" }).first()).toBeVisible();
  await page.getByLabel("Aktion (Anfang)").fill("user.create");
  await expect(page.getByRole("cell", { name: `user:e2e.${suffix}` })).toBeVisible();
});
