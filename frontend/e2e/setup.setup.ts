import { expect, test as setup } from "@playwright/test";
import { expectAccessible } from "./a11y";
import { ADMIN, COMPANY, SETUP_TOKEN } from "./env";

/** First setup through the wizard (if still open), then the admin session for all other tests. */
setup("Ersteinrichtung und Anmeldung als Administrator", async ({ page }) => {
  setup.setTimeout(120_000);
  await page.goto("/");
  await page.waitForURL(/\/(setup|login)/);

  if (page.url().includes("/setup")) {
    expect(SETUP_TOKEN, "E2E_SETUP_TOKEN aus dem API-Log setzen").not.toBe("");
    await expect(page.getByRole("heading", { name: "Ersteinrichtung" })).toBeVisible();
    await expectAccessible(page, "/setup");

    // a wrong token is only noticed by the API at the end: the wizard returns to the first step
    await page.getByLabel("Setup-Token").fill("falsches-token");
    await page.getByRole("button", { name: "Weiter" }).click();

    await page.getByLabel("Firmenname").fill(COMPANY.name);
    await page.getByLabel("ITU Carrier Code").fill("zu-lang-1");
    await page.getByLabel("Betriebs-E-Mail").fill(COMPANY.email);
    await page.getByRole("button", { name: "Weiter" }).click();
    await expect(page.getByText("1 bis 6 Großbuchstaben oder Ziffern")).toBeVisible();
    await page.getByLabel("ITU Carrier Code").fill(COMPANY.carrierCode.toLowerCase());
    await page.getByRole("button", { name: "Weiter" }).click();

    await page.getByRole("textbox", { name: "Passwort", exact: true }).fill(ADMIN.password);
    await page.getByLabel("Passwort wiederholen").fill("etwas-anderes-passwort");
    await page.getByRole("button", { name: "Weiter" }).click();
    await expect(page.getByText("Passwörter stimmen nicht überein")).toBeVisible();
    await page.getByLabel("Passwort wiederholen").fill(ADMIN.password);
    await page.getByRole("button", { name: "Weiter" }).click();

    await page.getByRole("button", { name: "Weiter" }).click(); // signing key: generate
    await expect(page.getByText(`${COMPANY.name} (${COMPANY.carrierCode})`)).toBeVisible();
    await page.getByRole("button", { name: "Einrichtung abschließen" }).click();
    await expect(page.getByText("Das Setup-Token passt nicht")).toBeVisible();

    await page.getByLabel("Setup-Token").fill(SETUP_TOKEN);
    for (let i = 0; i < 4; i++) await page.getByRole("button", { name: "Weiter" }).click();
    await page.getByRole("button", { name: "Einrichtung abschließen" }).click();
    // generating the RSA 3072 signing key takes a while
    await expect(page.getByRole("heading", { name: "Einrichtung abgeschlossen" })).toBeVisible({ timeout: 60_000 });
    await page.getByRole("link", { name: "Zur Anmeldung" }).click();
  }

  await page.waitForURL(/\/login/);
  await page.getByLabel("Benutzername").fill(ADMIN.username);
  await page.getByRole("textbox", { name: "Passwort", exact: true }).fill(ADMIN.password);
  await page.getByRole("button", { name: "Anmelden" }).click();
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  await page.context().storageState({ path: "e2e/.auth/admin.json" });
});
