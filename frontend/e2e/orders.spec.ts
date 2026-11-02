import { expect, test } from "@playwright/test";
import { expectAccessible } from "./a11y";
import { apiPost, createPartner, newOrder, uniqueSuffix } from "./api";

test("Entwurf ansehen und senden, Verlauf, gesendetes XML, Storno nur laut allowedActions", async ({ page }) => {
  const suffix = uniqueSuffix();
  const partnerId = await createPartner(page, suffix);
  const number = `AG-E2E-${suffix}`;
  await apiPost(page, "/api/v1/orders", newOrder(partnerId, number));

  await page.goto("/orders");
  await page.getByLabel("Suche").fill(number);
  await page.getByRole("link", { name: number }).click();
  await expect(page.getByRole("heading", { name: `Auftrag ${number}` })).toBeVisible();
  await expect(page.getByText("Entwurf", { exact: true }).first()).toBeVisible();
  await expect(page.getByLabel("Auftrag (XSD-JSON)")).toHaveValue(new RegExp(number));
  await expectAccessible(page, "Auftrag (Entwurf)");

  await page.getByRole("button", { name: "Senden" }).click();
  await expect(page.getByText("gesendet / empfangen")).toBeVisible();
  const history = page.getByRole("region", { name: "Verlauf" });
  await expect(history.getByText("an den Partner")).toBeVisible();

  const deliveries = page.getByRole("region", { name: "Ausgehende Nachrichten" });
  await expect(deliveries.getByText("annehmenAuftrag")).toBeVisible();
  await deliveries.getByRole("button", { name: "XML" }).first().click();
  await expect(page.getByTestId("raw-xml")).toContainText(number);
  await page.keyboard.press("Escape");

  const actions = page.getByRole("region", { name: "Nächste Schritte" });
  await expect(actions.getByRole("button")).toHaveText(["Storno"]);
  await actions.getByRole("button", { name: "Storno" }).click();
  await expectAccessible(page, "Storno-Dialog");
  await page.getByRole("dialog").getByRole("button", { name: "Storno senden" }).click();
  await expect(history.getByText("Änderung (Storno)")).toBeVisible();
  await expect(page.getByText("Storno offen")).toBeVisible();
  await expectAccessible(page, "Auftrag (gesendet)");
});

test("Arbeitsvorrat, Providerwechsel-Anfragen und KDLE-Formular", async ({ page }) => {
  await page.goto("/work-queue");
  await expect(page.getByRole("heading", { name: "Arbeitsvorrat Leistungserbringer" })).toBeVisible();
  await page.getByRole("link", { name: "Kündigung durch uns (KDLE)" }).click();
  await page.getByRole("button", { name: "KDLE senden" }).click();
  await expect(page.getByText("Partner wählen")).toBeVisible();
  await expect(page.getByText("mindestens einen Meldungscode wählen")).toBeVisible();
  await expectAccessible(page, "/terminations/new");

  await page.goto("/pv-requests");
  await expect(page.getByRole("heading", { name: "Providerwechsel-Anfragen" })).toBeVisible();
  await expect(page.getByRole("switch", { name: "nur unbeantwortete" })).toBeChecked();
});
