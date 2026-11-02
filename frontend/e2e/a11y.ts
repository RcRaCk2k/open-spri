import AxeBuilder from "@axe-core/playwright";
import { expect, type Page } from "@playwright/test";

/** WCAG 2.1 AA as far as axe-core can check it (AUFGABE 8.6: barrierearm). */
export async function expectAccessible(page: Page, label: string) {
  await page.waitForLoadState("networkidle");
  const result = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  expect(result.violations.map((v) => `${label}: ${v.id} (${v.nodes.length}) ${v.help} – ${v.nodes[0]?.target.join(" ")} – ${v.nodes[0]?.failureSummary?.replace(/\s+/g, " ")}`)).toEqual([]);
}
