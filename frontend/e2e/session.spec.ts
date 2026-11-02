import { expect, test } from "@playwright/test";
import { expectAccessible } from "./a11y";
import { ADMIN } from "./env";

test.describe("ohne Anmeldung", () => {
  test.use({ storageState: { cookies: [], origins: [] } });

  test("falsches Passwort wird gemeldet, nach der Anmeldung geht es zur angeforderten Seite", async ({ page }) => {
    await page.goto("/audit");
    await expect(page).toHaveURL(/\/login\?next=%2Faudit/);
    await expectAccessible(page, "/login");
    await page.getByLabel("Benutzername").fill(ADMIN.username);
    await page.getByRole("textbox", { name: "Passwort", exact: true }).fill("falsches-passwort-123");
    await page.getByRole("button", { name: "Anmelden" }).click();
    await expect(page.getByRole("alert")).toContainText("Benutzername oder Passwort ist falsch");

    await page.getByRole("textbox", { name: "Passwort", exact: true }).fill(ADMIN.password);
    await page.getByRole("button", { name: "Anmelden" }).click();
    await expect(page).toHaveURL(/\/audit$/);
    await expect(page.getByRole("heading", { name: "Audit-Log" })).toBeVisible();

    await page.getByRole("button", { name: "Abmelden" }).click();
    await expect(page).toHaveURL(/\/login/);
    await page.goto("/");
    await expect(page).toHaveURL(/\/login/);
  });

  test("der Setup-Assistent ist nach der Einrichtung nicht mehr erreichbar", async ({ page }) => {
    await page.goto("/setup");
    await expect(page).toHaveURL(/\/login/);
  });
});
