import { describe, expect, it } from "vitest";
import { setupDefaults, setupSchema, splitList, toSetupRequest, type SetupForm } from "./setupForm";

const valid: SetupForm = {
  ...setupDefaults,
  setupToken: "token",
  company: { ...setupDefaults.company, name: "Testnetz GmbH", carrierCode: "tstnz", contactEmail: "noc@testnetz.example" },
  admin: { ...setupDefaults.admin, password: "ein-sehr-langes-passwort", passwordRepeat: "ein-sehr-langes-passwort" },
};

describe("setupSchema", () => {
  it("accepts a complete setup", () => {
    const result = setupSchema.safeParse(valid);
    expect(result.error?.issues ?? []).toEqual([]);
  });

  it("checks carrier code, roles, password repeat and the PKCS#12 file", () => {
    const issues = (form: SetupForm) => setupSchema.safeParse(form).error?.issues.map((i) => i.path.join(".")) ?? [];
    expect(issues({ ...valid, company: { ...valid.company, carrierCode: "zu-lang-1" } })).toEqual(["company.carrierCode"]);
    expect(issues({ ...valid, company: { ...valid.company, roleAuftraggeber: false, roleLeistungserbringer: false } })).toEqual(["company.roleAuftraggeber"]);
    expect(issues({ ...valid, admin: { ...valid.admin, passwordRepeat: "anders" } })).toEqual(["admin.passwordRepeat"]);
    expect(issues({ ...valid, signingKey: { ...valid.signingKey, generate: false } })).toEqual(["signingKey.pkcs12Base64"]);
    expect(issues({ ...valid, company: { ...valid.company, publicSpriEndpoint: "http://spri.example.de" } })).toEqual(["company.publicSpriEndpoint"]);
  });
});

describe("toSetupRequest", () => {
  it("normalizes the carrier code, lists and empty fields", () => {
    const request = toSetupRequest({ ...valid, company: { ...valid.company, outboundAddresses: "203.0.113.10\n203.0.113.11, " } });
    expect(request.company.carrierCode).toBe("TSTNZ");
    expect(request.company.outboundAddresses).toEqual(["203.0.113.10", "203.0.113.11"]);
    expect(request.company.street).toBeNull();
    expect(request.signingKey).toEqual({ generate: true, commonName: null });
    expect(request.admin).not.toHaveProperty("passwordRepeat");
  });

  it("splits lists on lines, commas and semicolons", () => {
    expect(splitList(" a, b;c\n\n d ")).toEqual(["a", "b", "c", "d"]);
  });
});
