import { expect, test } from "@playwright/test";
import { expectAccessible } from "./a11y";

/**
 * Principle 4 (API-first): every request of the frontend goes to the public REST API /api/v1 or loads a static file
 * of the frontend itself. And every page meets WCAG 2.1 AA as far as axe-core can check it. Against the built frontend
 * (compose stack) the Content-Security-Policy of deploy/frontend is active: no page may violate it.
 */
test("alle Seiten: nur /api/v1 und barrierearm (axe, WCAG 2.1 AA, hell und dunkel)", async ({ page, baseURL }) => {
  test.setTimeout(180_000);
  const origin = new URL(baseURL!).origin;
  const foreign: string[] = [];
  const violations: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error" && /Content Security Policy/i.test(message.text())) violations.push(message.text());
  });
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.origin !== origin) { foreign.push(request.url()); return; }
    const api = url.pathname.startsWith("/api/");
    if (api && !url.pathname.startsWith("/api/v1/")) foreign.push(url.pathname);
    if (!api && request.resourceType() === "fetch") foreign.push(url.pathname);
  });

  await page.goto("/");
  await expect(page.getByRole("heading", { name: "Dashboard" })).toBeVisible();
  const links = await page.getByRole("navigation", { name: "Hauptnavigation" }).getByRole("link").all();
  const paths = await Promise.all(links.map(async (l) => new URL((await l.getAttribute("href"))!, origin).pathname));
  expect(paths.length).toBeGreaterThan(1);

  for (const colorScheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme });
    for (const path of paths) {
      await page.goto(path);
      await expect(page.getByRole("heading", { level: 2 }).first()).toBeVisible();
      await expectAccessible(page, `${path} (${colorScheme})`);
    }
  }
  expect(foreign).toEqual([]);
  expect(violations).toEqual([]);
});
