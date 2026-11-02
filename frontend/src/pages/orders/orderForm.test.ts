import { describe, expect, it } from "vitest";
import { address, homeIds, products, technologies } from "../availability/replikat";
import { emptyOrderForm, fromAvailabilityTemplate, fromContract, toComposeRequest } from "./orderForm";

describe("toComposeRequest", () => {
  it("sends only the fields of the business case", () => {
    const form = { ...emptyOrderForm, businessCase: "KUE-AG", externalOrderNumber: " K-1 ", contractNumber: "V1", lineId: "L1", desiredDate: "2026-12-31",
      contactName: "AM", products: ["FTTH 100"], street: "Musterstraße", lastName: "Musterfrau", operations: ["RESET"] };
    const r = toComposeRequest(form);
    expect(r).toMatchObject({ businessCase: "KUE-AG", externalOrderNumber: "K-1", contractNumber: "V1", lineId: "L1", desiredDate: "2026-12-31", products: [], operations: [] });
    expect(r.site).toBeNull();
    expect(r.siteParty).toBeNull();
    expect(r.fault).toBeNull();
    expect(r.contact).toEqual({ salutation: "9", lastName: "AM", phone: null });
  });

  it("sends a company as site party and the site for NEU; no date for DIAG", () => {
    const neu = toComposeRequest({ ...emptyOrderForm, siteKind: "company", company: "Beispiel GmbH", street: "Weg", houseNumber: "1" });
    expect(neu.siteParty).toEqual({ company: "Beispiel GmbH", phone: null });
    expect(neu.site).toMatchObject({ street: "Weg", houseNumber: "1", postalCode: null });
    expect(toComposeRequest({ ...emptyOrderForm, businessCase: "DIAG-GET", desiredDate: "2026-12-31", operations: ["BBdown"] })).toMatchObject({ desiredDate: null, operations: ["BBdown"] });
  });
});

describe("prefill", () => {
  it("takes site, HomeID, products and time window from the availability order template", () => {
    const auftrag = {
      geschaeftsfall: {
        termine: { auftraggeberWunschtermin: { zeitfenster: "3" } },
        auftragsposition: {
          produkt: { bezeichner: "FTTH 100" },
          position: [{ produkt: { bezeichner: "Konnektivität" } }],
          geschaeftsfallProdukt: {
            standortA: { strasse: { strassenname: "Musterstraße", hausnummer: "16", hausnummernZusatz: "a" }, postleitzahl: "12345", ort: { ortsname: "Musterstadt", ortsteil: "Mitte" }, gebaeudeteil: { gebaeudeteilName: "HH" } },
            vormieter: { homeId: { homeIdNummer: "HID2" } },
          },
        },
      },
    };
    expect(fromAvailabilityTemplate(auftrag)).toEqual({
      businessCase: "NEU", products: ["FTTH 100", "Konnektivität"], homeId: "HID2", street: "Musterstraße", houseNumber: "16", houseNumberSuffix: "a",
      postalCode: "12345", city: "Musterstadt", district: "Mitte", buildingPart: "HH", timeWindow: "3",
    });
    expect(fromAvailabilityTemplate({}).products).toEqual([]);
  });

  it("takes contract, line, numbers and products from the inventory", () => {
    expect(fromContract({ id: 1, role: "AUFTRAGGEBER", partnerId: 7, contractNumber: "V1", lineId: "L1", clientNumber: null, serviceNumber: "LN", products: ["FTTH 100"], orderId: null, startedAt: "2026-01-01T00:00:00Z", endedAt: null }))
      .toEqual({ partnerId: "7", contractNumber: "V1", lineId: "L1", clientNumber: "", serviceNumber: "LN", products: ["FTTH 100"] });
  });
});

describe("Replikat record", () => {
  const ds = {
    plz: "12345", o: "Musterstadt", ot: "Mitte", s: "Musterstraße", h: "1", hz: "a",
    pl: [{ produkt: "FTTH 100", technologie: "FTTH", erwarteteBandbreite: { downBB: "100", upBB: "40" }, bereitstellungszeit: "5" }],
    tl: [{ technologie: "FTTH", ausbaustatus: "homesConnected", auftragsart: "KONNEKTIVITAET", hidl: ["H1", "H2"], eigentuemerdaten: true }, { technologie: "FTTB", hidl: ["H2"] }],
  };
  it("is readable for display", () => {
    expect(address(ds)).toBe("Musterstraße 1 a, 12345 Musterstadt (Mitte)");
    expect(products(ds)).toEqual([{ product: "FTTH 100", technology: "FTTH", down: "100", up: "40", days: "5" }]);
    expect(technologies(ds)[0]).toEqual({ technology: "FTTH", status: "homesConnected", orderKind: "KONNEKTIVITAET", homeIds: ["H1", "H2"], ownerData: true });
    expect(homeIds(ds)).toEqual(["H1", "H2"]);
    expect(products({})).toEqual([]);
  });
});
