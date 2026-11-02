/** Fictitious data of the E2E tests (CLAUDE.md: only invented data). */
export const ADMIN = {
  username: process.env.E2E_ADMIN_USER ?? "admin",
  password: process.env.E2E_ADMIN_PASSWORD ?? "e2e-ein-sehr-langes-passwort",
};
export const SETUP_TOKEN = process.env.E2E_SETUP_TOKEN ?? "";
export const COMPANY = { name: "Testnetz E2E GmbH", carrierCode: "TSTE2E", email: "betrieb@testnetz.example" };
