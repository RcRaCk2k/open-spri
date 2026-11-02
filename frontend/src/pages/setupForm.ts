import { z } from "zod";
import type { Schemas } from "../api/client";

/** Lines or commas → list; empty entries are dropped. */
export function splitList(value: string): string[] {
  return value.split(/[\s,;]+/).map((v) => v.trim()).filter((v) => v.length > 0);
}

/** Validation of the setup wizard, step by step; the API checks everything again. */
export const setupSchema = z
  .object({
    setupToken: z.string().trim().min(1, "Setup-Token aus dem Container-Log angeben"),
    company: z.object({
      name: z.string().trim().min(1, "Firmenname angeben"),
      street: z.string().trim(),
      postalCode: z.string().trim(),
      city: z.string().trim(),
      carrierCode: z.string().trim().toUpperCase().regex(/^[A-Z0-9]{1,6}$/, "1 bis 6 Großbuchstaben oder Ziffern (ITU Carrier Code)"),
      contactEmail: z.email("gültige E-Mail-Adresse angeben"),
      roleAuftraggeber: z.boolean(),
      roleLeistungserbringer: z.boolean(),
      estEnabled: z.boolean(),
      diagEnabled: z.boolean(),
      connectivityEnabled: z.boolean(),
      thirdPartyOrdersEnabled: z.boolean(),
      wbciMode: z.enum(["SERVICE_PROVIDER", "OWN_INTERFACE", "NONE"]),
      publicSpriEndpoint: z.union([z.literal(""), z.url({ protocol: /^https$/, message: "https-Adresse angeben" })]),
      outboundAddresses: z.string(),
    }),
    admin: z.object({
      username: z.string().trim().min(1, "Benutzernamen angeben"),
      displayName: z.string().trim().min(1, "Anzeigenamen angeben"),
      password: z.string().min(12, "mindestens 12 Zeichen"),
      passwordRepeat: z.string(),
    }),
    signingKey: z.object({
      generate: z.boolean(),
      commonName: z.string().trim(),
      pkcs12Base64: z.string(),
      pkcs12Password: z.string(),
    }),
  })
  .refine((v) => v.company.roleAuftraggeber || v.company.roleLeistungserbringer, {
    path: ["company", "roleAuftraggeber"],
    message: "Mindestens eine Rolle wählen",
  })
  .refine((v) => v.admin.password === v.admin.passwordRepeat, { path: ["admin", "passwordRepeat"], message: "Passwörter stimmen nicht überein" })
  .refine((v) => v.signingKey.generate || v.signingKey.pkcs12Base64.length > 0, {
    path: ["signingKey", "pkcs12Base64"],
    message: "PKCS#12-Datei wählen oder einen neuen Schlüssel erzeugen",
  });

export type SetupForm = z.input<typeof setupSchema>;

export const setupDefaults: SetupForm = {
  setupToken: "",
  company: {
    name: "", street: "", postalCode: "", city: "", carrierCode: "", contactEmail: "",
    roleAuftraggeber: true, roleLeistungserbringer: true,
    estEnabled: true, diagEnabled: true, connectivityEnabled: true, thirdPartyOrdersEnabled: true,
    wbciMode: "SERVICE_PROVIDER", publicSpriEndpoint: "", outboundAddresses: "",
  },
  admin: { username: "admin", displayName: "Administration", password: "", passwordRepeat: "" },
  signingKey: { generate: true, commonName: "", pkcs12Base64: "", pkcs12Password: "" },
};

/** The body of POST /setup. */
export function toSetupRequest(form: SetupForm): Schemas["SetupRequest"] {
  const c = form.company;
  const blank = (v: string) => (v.trim() === "" ? null : v.trim());
  return {
    admin: { username: form.admin.username.trim(), displayName: form.admin.displayName.trim(), password: form.admin.password },
    company: {
      name: c.name.trim(), street: blank(c.street), postalCode: blank(c.postalCode), city: blank(c.city),
      carrierCode: c.carrierCode.trim().toUpperCase(), contactEmail: c.contactEmail.trim(),
      roleAuftraggeber: c.roleAuftraggeber, roleLeistungserbringer: c.roleLeistungserbringer,
      estEnabled: c.estEnabled, diagEnabled: c.diagEnabled, connectivityEnabled: c.connectivityEnabled, thirdPartyOrdersEnabled: c.thirdPartyOrdersEnabled,
      wbciMode: c.wbciMode, publicSpriEndpoint: blank(c.publicSpriEndpoint), outboundAddresses: splitList(c.outboundAddresses),
    },
    signingKey: form.signingKey.generate
      ? { generate: true, commonName: blank(form.signingKey.commonName) }
      : { generate: false, pkcs12Base64: form.signingKey.pkcs12Base64, pkcs12Password: form.signingKey.pkcs12Password },
  };
}

/** The fields checked before the wizard moves on from a step. */
export const STEP_FIELDS = [
  ["setupToken"],
  ["company"],
  ["admin"],
  ["signingKey"],
] as const;
