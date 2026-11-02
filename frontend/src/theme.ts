import { createTheme, PasswordInput, type CSSVariablesResolver } from "@mantine/core";

/**
 * Teal as in the M0 page. Contrast meets WCAG 2.1 AA (4.5:1, checked with axe in the E2E tests): the primary color
 * (white text on filled buttons) uses the darkest teal in both schemes, "dimmed" text and the light variant are darker than Mantine's defaults.
 */
export const theme = createTheme({
  primaryColor: "teal",
  primaryShade: { light: 9, dark: 9 },
  fontFamily: 'system-ui, -apple-system, "Segoe UI", sans-serif',
  fontFamilyMonospace: 'ui-monospace, "SFMono-Regular", Menlo, monospace',
  defaultRadius: "sm",
  components: {
    // German labels for Mantine's built-in controls (the interface is German, AUFGABE 8.6)
    PasswordInput: PasswordInput.extend({ defaultProps: { visibilityToggleButtonProps: { "aria-label": "Passwort anzeigen" } } }),
  },
});

export const cssVariablesResolver: CSSVariablesResolver = (t) => ({
  variables: {},
  light: {
    "--mantine-color-dimmed": t.colors.gray[7]!,
    "--mantine-color-teal-light": t.colors.teal[0]!,
    "--mantine-color-teal-light-color": t.colors.teal[9]!,
  },
  dark: {
    "--mantine-color-dimmed": t.colors.dark[1]!,
  },
});
