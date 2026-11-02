import type { Schemas } from "../../api/client";

/** Business cases the Auftraggeber orders with a wizard (KUE-LE starts with the KDLE of the Leistungserbringer). */
export const ORDER_CASES = [
  { value: "NEU", label: "NEU – Bereitstellung" },
  { value: "KUE-AG", label: "KUE-AG – Kündigung" },
  { value: "LAE", label: "LAE – Leistungsänderung (Produktwechsel)" },
  { value: "AEN-LMAE", label: "AEN-LMAE – Leistungsmerkmale ändern" },
  { value: "PV", label: "PV – Providerwechsel" },
  { value: "EST", label: "EST – Entstörung" },
  { value: "DIAG-GET", label: "DIAG-GET – Diagnose abfragen" },
  { value: "DIAG-SET", label: "DIAG-SET – Diagnose ausführen" },
] as const;

/** Business cases on an existing line: contract and LineID come from the inventory. */
export const ON_LINE = new Set(["KUE-AG", "LAE", "AEN-LMAE", "EST", "DIAG-GET", "DIAG-SET"]);
export const WITH_SITE = new Set(["NEU", "PV"]);
export const WITH_PRODUCTS = new Set(["NEU", "PV", "LAE", "AEN-LMAE"]);
export const WITHOUT_DATE = new Set(["DIAG-GET", "DIAG-SET"]);

export interface OrderForm {
  partnerId: string;
  businessCase: string;
  externalOrderNumber: string;
  clientNumber: string;
  serviceNumber: string;
  desiredDate: string;
  timeWindow: string;
  contactName: string;
  contactPhone: string;
  contractNumber: string;
  lineId: string;
  preAgreementId: string;
  products: string[];
  homeId: string;
  siteKind: "person" | "company";
  salutation: string;
  firstName: string;
  lastName: string;
  company: string;
  sitePhone: string;
  street: string;
  houseNumber: string;
  houseNumberSuffix: string;
  postalCode: string;
  city: string;
  district: string;
  buildingPart: string;
  faultNumber: string;
  faultDescription: string;
  preChecked: boolean;
  customerFirstName: string;
  customerLastName: string;
  customerPhone: string;
  contactWanted: boolean;
  operations: string[];
}

export const emptyOrderForm: OrderForm = {
  partnerId: "", businessCase: "NEU", externalOrderNumber: "", clientNumber: "", serviceNumber: "", desiredDate: "", timeWindow: "",
  contactName: "", contactPhone: "", contractNumber: "", lineId: "", preAgreementId: "", products: [], homeId: "",
  siteKind: "person", salutation: "9", firstName: "", lastName: "", company: "", sitePhone: "",
  street: "", houseNumber: "", houseNumberSuffix: "", postalCode: "", city: "", district: "", buildingPart: "",
  faultNumber: "", faultDescription: "", preChecked: false, customerFirstName: "", customerLastName: "", customerPhone: "", contactWanted: false,
  operations: [],
};

const opt = (v: string) => (v.trim() === "" ? null : v.trim());

/** The body of POST /orders/compose: only the fields of the business case. */
export function toComposeRequest(f: OrderForm): Schemas["ComposeOrderRequest"] {
  const site = WITH_SITE.has(f.businessCase);
  return {
    businessCase: f.businessCase,
    externalOrderNumber: opt(f.externalOrderNumber),
    clientNumber: opt(f.clientNumber),
    serviceNumber: opt(f.serviceNumber),
    desiredDate: WITHOUT_DATE.has(f.businessCase) ? null : opt(f.desiredDate),
    timeWindow: WITHOUT_DATE.has(f.businessCase) ? null : opt(f.timeWindow),
    contact: f.contactName.trim() ? { salutation: "9", lastName: f.contactName.trim(), phone: opt(f.contactPhone) } : null,
    contractNumber: ON_LINE.has(f.businessCase) ? opt(f.contractNumber) : null,
    lineId: ON_LINE.has(f.businessCase) || f.businessCase === "PV" ? opt(f.lineId) : null,
    preAgreementId: ["NEU", "KUE-AG", "PV"].includes(f.businessCase) ? opt(f.preAgreementId) : null,
    products: WITH_PRODUCTS.has(f.businessCase) ? f.products : [],
    homeId: site ? opt(f.homeId) : null,
    siteParty: !site ? null : f.siteKind === "company"
      ? { company: opt(f.company), phone: opt(f.sitePhone) }
      : { salutation: f.salutation, firstName: opt(f.firstName), lastName: opt(f.lastName), phone: opt(f.sitePhone) },
    site: !site ? null : {
      street: opt(f.street), houseNumber: opt(f.houseNumber), houseNumberSuffix: opt(f.houseNumberSuffix), postalCode: opt(f.postalCode),
      city: opt(f.city), district: opt(f.district), buildingPart: opt(f.buildingPart),
    },
    fault: f.businessCase !== "EST" ? null : {
      number: opt(f.faultNumber), description: opt(f.faultDescription), preChecked: f.preChecked, contactWanted: f.contactWanted,
      customer: f.customerLastName.trim() ? { salutation: "9", firstName: opt(f.customerFirstName), lastName: f.customerLastName.trim(), phone: opt(f.customerPhone) } : null,
    },
    operations: f.businessCase.startsWith("DIAG") ? f.operations : [],
  };
}

type Json = Record<string, unknown>;
const obj = (v: unknown): Json => (v && typeof v === "object" && !Array.isArray(v) ? (v as Json) : {});
const str = (v: unknown): string => (typeof v === "string" ? v : "");
const list = (v: unknown): unknown[] => (Array.isArray(v) ? v : v === undefined || v === null ? [] : [v]);

/**
 * Form fields from the order template of the availability search (POST /availability/order-template, Replikat 1.7
 * chapter 4): site, HomeID, products including "Konnektivität", time window.
 */
export function fromAvailabilityTemplate(auftrag: unknown): Partial<OrderForm> {
  const gf = obj(obj(auftrag).geschaeftsfall);
  const position = obj(gf.auftragsposition);
  const fttx = obj(position.geschaeftsfallProdukt);
  const standort = obj(fttx.standortA);
  const strasse = obj(standort.strasse);
  const ort = obj(standort.ort);
  const products = [str(obj(position.produkt).bezeichner), ...list(position.position).map((p) => str(obj(obj(p).produkt).bezeichner))].filter(Boolean);
  return {
    businessCase: "NEU",
    products,
    homeId: str(obj(obj(fttx.vormieter).homeId).homeIdNummer),
    street: str(strasse.strassenname),
    houseNumber: str(strasse.hausnummer),
    houseNumberSuffix: str(strasse.hausnummernZusatz),
    postalCode: str(standort.postleitzahl),
    city: str(ort.ortsname),
    district: str(ort.ortsteil),
    buildingPart: str(obj(standort.gebaeudeteil).gebaeudeteilName),
    timeWindow: str(obj(obj(gf.termine).auftraggeberWunschtermin).zeitfenster),
  };
}

/** Form fields from a contract of the inventory (KUE-AG, LAE, AEN-LMAE, EST, DIAG). */
export function fromContract(c: Schemas["InventoryContractDto"]): Partial<OrderForm> {
  return {
    partnerId: String(c.partnerId), contractNumber: c.contractNumber, lineId: c.lineId,
    clientNumber: c.clientNumber ?? "", serviceNumber: c.serviceNumber ?? "", products: c.products,
  };
}
