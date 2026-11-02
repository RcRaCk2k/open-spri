import { createTheme, Modal, Notification, PasswordInput, type CSSVariablesResolver, type MantineColorsTuple } from "@mantine/core";

/**
 * Teal as in the M0 page. Contrast meets WCAG 2.1 AA (4.5:1, checked with axe in the E2E tests in both color schemes):
 * - the primary color (white text on filled buttons) is the darkest teal
 * - "dimmed" text and the text of the light and outline variants (badges, subtle buttons) are darker than Mantine's
 *   defaults, see [cssVariablesResolver]
 * Animations are switched off when the user asks for reduced motion.
 */
export const theme = createTheme({
  primaryColor: "teal",
  primaryShade: { light: 9, dark: 9 },
  fontFamily: 'system-ui, -apple-system, "Segoe UI", sans-serif',
  fontFamilyMonospace: 'ui-monospace, "SFMono-Regular", Menlo, monospace',
  defaultRadius: "sm",
  respectReducedMotion: true,
  components: {
    // German names for Mantine's built-in controls (the interface is German, AUFGABE 8.6)
    PasswordInput: PasswordInput.extend({ defaultProps: { visibilityToggleButtonProps: { "aria-label": "Passwort anzeigen" } } }),
    Modal: Modal.extend({ defaultProps: { closeButtonProps: { "aria-label": "Schließen" } } }),
    Notification: Notification.extend({ defaultProps: { closeButtonProps: { "aria-label": "Meldung schließen" } } }),
  },
});

/**
 * Text on light backgrounds: shade 9 darkened by a fifth, because shade 9 of orange, yellow and lime stays below 4.5:1
 * on white. On dark backgrounds shade 2.
 */
const onLight = (shade: string) => `color-mix(in srgb, ${shade} 80%, black)`;

export const cssVariablesResolver: CSSVariablesResolver = (t) => {
  const light: Record<string, string> = { "--mantine-color-dimmed": t.colors.gray[7]!, "--mantine-color-error": t.colors.red[9]! };
  const dark: Record<string, string> = { "--mantine-color-dimmed": t.colors.dark[1]! };
  for (const [name, shades] of Object.entries(t.colors) as [string, MantineColorsTuple][]) {
    if (name === "dark") continue;
    light[`--mantine-color-${name}-light-color`] = onLight(shades[9]!);
    light[`--mantine-color-${name}-outline`] = onLight(shades[9]!);
    light[`--mantine-color-${name}-text`] = onLight(shades[9]!);
    light[`--mantine-color-${name}-light`] = shades[0]!;
    // filled buttons use shade 9 (primaryShade); Mantine's hover would be the lighter shade 8 (3.9:1 with white)
    light[`--mantine-color-${name}-filled-hover`] = onLight(shades[9]!);
    dark[`--mantine-color-${name}-filled-hover`] = onLight(shades[9]!);
    dark[`--mantine-color-${name}-light-color`] = shades[2]!;
    dark[`--mantine-color-${name}-outline`] = shades[2]!;
    dark[`--mantine-color-${name}-text`] = shades[2]!;
  }
  return { variables: {}, light, dark };
};
