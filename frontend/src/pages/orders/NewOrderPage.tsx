import {
  Alert, Button, Card, Checkbox, Group, JsonInput, List, Radio, Select, SimpleGrid, Stack, TagsInput, Text, Textarea, TextInput, Title,
} from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { useLocation, useNavigate } from "react-router";
import { api, call, type Schemas } from "../../api/client";
import { ErrorAlert } from "../../components/Feedback";
import {
  emptyOrderForm, fromContract, ON_LINE, ORDER_CASES, toComposeRequest, WITH_PRODUCTS, WITH_SITE, WITHOUT_DATE, type OrderForm,
} from "./orderForm";

/** Prefill handed over by the availability search or the inventory (router state). */
export interface NewOrderState {
  prefill?: Partial<OrderForm>;
  hints?: string[];
  source?: string;
}

const DIAG_OPERATIONS = ["BBdown", "BBup", "RESET"];

function useOrderPrefill(): NewOrderState {
  const location = useLocation();
  return (location.state as NewOrderState | null) ?? {};
}

/**
 * New order as Auftraggeber (AUFGABE 8.6: wizard per business case, prefilled from the availability search or the
 * inventory). open-spri composes the XSD structure (POST /orders/compose); the order is saved as a draft (POST /orders)
 * and sent from the order page.
 */
export function NewOrderPage() {
  const state = useOrderPrefill();
  const navigate = useNavigate();
  const client = useQueryClient();
  const form = useForm<OrderForm>({ defaultValues: { ...emptyOrderForm, ...state.prefill } });
  const { register, control, watch, setValue, getValues } = form;
  const [preview, setPreview] = useState<{ text: string; missing: string[] } | null>(null);
  const values = watch();
  const gf = values.businessCase;
  const partnerId = values.partnerId ? Number(values.partnerId) : undefined;

  const partners = useQuery({ queryKey: ["partners"], queryFn: () => call(api.GET("/api/v1/partners")) });
  const catalog = useQuery({
    queryKey: ["products", { partnerId, role: "AUFTRAGGEBER" }],
    queryFn: () => call(api.GET("/api/v1/products", { params: { query: { partnerId, role: "AUFTRAGGEBER" } } })),
    enabled: partnerId !== undefined && WITH_PRODUCTS.has(gf),
  });
  const contracts = useQuery({
    queryKey: ["inventory", { partnerId, role: "AUFTRAGGEBER", active: true }],
    queryFn: () => call(api.GET("/api/v1/inventory", { params: { query: { partnerId, role: "AUFTRAGGEBER", active: true, limit: 200 } } })),
    enabled: partnerId !== undefined && ON_LINE.has(gf),
  });

  const compose = useMutation({
    mutationFn: () => call(api.POST("/api/v1/orders/compose", { body: toComposeRequest(getValues()) })),
    onSuccess: (r) => setPreview({ text: JSON.stringify(r.auftrag, null, 2), missing: r.missing }),
  });
  const save = useMutation({
    mutationFn: (auftrag: unknown) => call(api.POST("/api/v1/orders", { body: { partnerId: Number(getValues().partnerId), auftrag: auftrag as Schemas["JsonNode"] } })),
    onSuccess: async (detail) => {
      await client.invalidateQueries({ queryKey: ["orders"] });
      navigate(`/orders/${detail.order.id}`);
    },
  });
  let parsed: unknown = null;
  try { parsed = preview ? JSON.parse(preview.text) : null; } catch { parsed = null; }
  const changed = () => preview && setPreview(null);

  return (
    <Stack maw={960}>
      <Title order={2}>Neuer Auftrag</Title>
      {state.source && <Alert color="teal" title="Vorbefüllt">{state.source}</Alert>}
      {state.hints && state.hints.length > 0 && (
        <Alert color="blue" title="Hinweise aus dem Replikat"><List size="sm">{state.hints.map((h) => <List.Item key={h}>{h}</List.Item>)}</List></Alert>
      )}
      <form onChange={changed} onSubmit={(e) => { e.preventDefault(); compose.mutate(); }} noValidate>
        <Stack>
          <Card withBorder component="section" aria-labelledby="case-title">
            <Title order={3} size="h5" id="case-title" mb="sm">Partner und Geschäftsfall</Title>
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <Controller control={control} name="partnerId" render={({ field }) => (
                <Select label="Partner (Leistungserbringer)" required searchable data={(partners.data ?? []).filter((p) => p.enabled).map((p) => ({ value: String(p.id), label: `${p.name} (${p.carrierCode})` }))}
                  value={field.value || null} onChange={(v) => { field.onChange(v ?? ""); changed(); }} />
              )} />
              <Controller control={control} name="businessCase" render={({ field }) => (
                <Select label="Geschäftsfall" required data={[...ORDER_CASES]} allowDeselect={false} value={field.value} onChange={(v) => { field.onChange(v ?? "NEU"); changed(); }} />
              )} />
            </SimpleGrid>
          </Card>

          {ON_LINE.has(gf) && (
            <Card withBorder component="section" aria-labelledby="line-title">
              <Title order={3} size="h5" id="line-title" mb="sm">Leitung</Title>
              <Stack>
                <Select label="Aus dem Bestand übernehmen" placeholder={partnerId ? "Vertrag wählen" : "zuerst Partner wählen"} searchable clearable disabled={!partnerId}
                  data={(contracts.data ?? []).map((c) => ({ value: String(c.id), label: `${c.contractNumber} · ${c.lineId} · ${c.products.join(", ")}` }))}
                  onChange={(id) => {
                    const c = contracts.data?.find((x) => String(x.id) === id);
                    if (c) for (const [k, v] of Object.entries(fromContract(c))) setValue(k as keyof OrderForm, v as never);
                    changed();
                  }} />
                <SimpleGrid cols={{ base: 1, sm: 2 }}>
                  <TextInput label="Vertragsnummer" required {...register("contractNumber")} />
                  <TextInput label="LineID" required {...register("lineId")} />
                </SimpleGrid>
              </Stack>
            </Card>
          )}

          <Card withBorder component="section" aria-labelledby="order-title">
            <Title order={3} size="h5" id="order-title" mb="sm">Auftrag</Title>
            <SimpleGrid cols={{ base: 1, sm: 3 }}>
              <TextInput label="Externe Auftragsnummer" description="unsere Nummer, je Partner eindeutig" required {...register("externalOrderNumber")} />
              <TextInput label="Auftraggebernummer" required {...register("clientNumber")} />
              <TextInput label="Leistungsnummer" required {...register("serviceNumber")} />
              {!WITHOUT_DATE.has(gf) && <TextInput type="date" label="Wunschtermin" required {...register("desiredDate")} />}
              {!WITHOUT_DATE.has(gf) && <TextInput label="Zeitfenster" description="1 bis 20, optional" {...register("timeWindow")} />}
              {["NEU", "KUE-AG", "PV"].includes(gf) && <TextInput label="VorabstimmungsID (WBCI)" required={gf === "PV"} {...register("preAgreementId")} />}
              {gf === "PV" && <TextInput label="LineID der bisherigen Leitung" required {...register("lineId")} />}
            </SimpleGrid>
            {!gf.startsWith("DIAG") && (
              <SimpleGrid cols={{ base: 1, sm: 2 }} mt="sm">
                <TextInput label="Ansprechpartner Auftragsmanagement" required {...register("contactName")} />
                <TextInput label="Telefon Auftragsmanagement" required {...register("contactPhone")} />
              </SimpleGrid>
            )}
          </Card>

          {WITH_PRODUCTS.has(gf) && (
            <Card withBorder component="section" aria-labelledby="products-title">
              <Title order={3} size="h5" id="products-title" mb="sm">Produkte</Title>
              <Controller control={control} name="products" render={({ field }) => (
                <TagsInput label={gf === "AEN-LMAE" ? "Produkt und hinzuzufügende Leistungsmerkmale" : "Produkt und weitere Positionen"}
                  description="das erste ist das Produkt der Auftragsposition; Vorschläge aus dem Produktkatalog des Partners"
                  data={(catalog.data ?? []).filter((p) => p.active).map((p) => p.identifier)} value={field.value} onChange={(v) => { field.onChange(v); changed(); }} clearable />
              )} />
            </Card>
          )}

          {WITH_SITE.has(gf) && (
            <Card withBorder component="section" aria-labelledby="site-title">
              <Title order={3} size="h5" id="site-title" mb="sm">Endkunde und Standort</Title>
              <Stack>
                <Controller control={control} name="siteKind" render={({ field }) => (
                  <Radio.Group label="Endkunde ist" value={field.value} onChange={(v) => { field.onChange(v); changed(); }}>
                    <Group mt={4}><Radio value="person" label="Person" /><Radio value="company" label="Firma" /></Group>
                  </Radio.Group>
                )} />
                {values.siteKind === "company" ? (
                  <SimpleGrid cols={{ base: 1, sm: 2 }}>
                    <TextInput label="Firmenname" required {...register("company")} />
                    <TextInput label="Telefon" {...register("sitePhone")} />
                  </SimpleGrid>
                ) : (
                  <SimpleGrid cols={{ base: 1, sm: 4 }}>
                    <Controller control={control} name="salutation" render={({ field }) => (
                      <Select label="Anrede" data={[{ value: "2", label: "Frau" }, { value: "1", label: "Herr" }, { value: "9", label: "keine" }]} allowDeselect={false} value={field.value} onChange={(v) => field.onChange(v ?? "9")} />
                    )} />
                    <TextInput label="Vorname" {...register("firstName")} />
                    <TextInput label="Nachname" required {...register("lastName")} />
                    <TextInput label="Telefon" {...register("sitePhone")} />
                  </SimpleGrid>
                )}
                <SimpleGrid cols={{ base: 1, sm: 4 }}>
                  <TextInput label="Straße" required {...register("street")} />
                  <TextInput label="Hausnummer" required {...register("houseNumber")} />
                  <TextInput label="Zusatz" {...register("houseNumberSuffix")} />
                  <TextInput label="Gebäudeteil" {...register("buildingPart")} />
                  <TextInput label="PLZ" required {...register("postalCode")} />
                  <TextInput label="Ort" required {...register("city")} />
                  <TextInput label="Ortsteil" {...register("district")} />
                  <TextInput label="HomeID" {...register("homeId")} />
                </SimpleGrid>
              </Stack>
            </Card>
          )}

          {gf === "EST" && (
            <Card withBorder component="section" aria-labelledby="fault-title">
              <Title order={3} size="h5" id="fault-title" mb="sm">Störung</Title>
              <Stack>
                <TextInput label="Störungsnummer (unsere)" required {...register("faultNumber")} />
                <Textarea label="Störungsbeschreibung" required autosize minRows={2} {...register("faultDescription")} />
                <Controller control={control} name="preChecked" render={({ field }) => (
                  <Checkbox label="Vorprüfung erfolgt" checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} />
                )} />
                <SimpleGrid cols={{ base: 1, sm: 3 }}>
                  <TextInput label="Kontakt Endkunde: Vorname" {...register("customerFirstName")} />
                  <TextInput label="Nachname" {...register("customerLastName")} />
                  <TextInput label="Telefon" {...register("customerPhone")} />
                </SimpleGrid>
                <Controller control={control} name="contactWanted" render={({ field }) => (
                  <Checkbox label="Kontaktaufnahme mit dem Endkunden erwünscht" checked={field.value} onChange={(e) => field.onChange(e.currentTarget.checked)} />
                )} />
              </Stack>
            </Card>
          )}

          {gf.startsWith("DIAG") && (
            <Card withBorder component="section" aria-labelledby="diag-title">
              <Title order={3} size="h5" id="diag-title" mb="sm">Diagnose</Title>
              <Controller control={control} name="operations" render={({ field }) => (
                <TagsInput label="Operationen" required data={DIAG_OPERATIONS} value={field.value} onChange={(v) => { field.onChange(v); changed(); }} />
              )} />
            </Card>
          )}

          <ErrorAlert error={compose.error} />
          <Group justify="flex-end"><Button type="submit" variant={preview ? "default" : "filled"} loading={compose.isPending}>Prüfen und Vorschau</Button></Group>
        </Stack>
      </form>

      {preview && (
        <Card withBorder component="section" aria-labelledby="preview-title">
          <Title order={3} size="h5" id="preview-title" mb="sm">Vorschau</Title>
          <Stack>
            {preview.missing.length > 0 ? (
              <Alert color="orange" title="Es fehlen noch Angaben">
                <List size="sm">{preview.missing.map((m) => <List.Item key={m} className="mono">{m}</List.Item>)}</List>
              </Alert>
            ) : <Text size="sm">Alle Angaben des Geschäftsfalls sind vorhanden. Beim Speichern prüft open-spri gegen die XSD und Anlage 1.</Text>}
            <JsonInput label="Auftrag (XSD-JSON)" description="kann vor dem Speichern noch geändert werden" autosize minRows={8} maxRows={30}
              value={preview.text} onChange={(text) => setPreview({ ...preview, text })} error={parsed === null ? "kein gültiges JSON" : undefined} />
            <ErrorAlert error={save.error} title="Der Entwurf wurde nicht gespeichert" />
            <Group justify="flex-end">
              <Button onClick={() => save.mutate(parsed)} loading={save.isPending} disabled={parsed === null || !values.partnerId}>Als Entwurf speichern</Button>
            </Group>
          </Stack>
        </Card>
      )}
    </Stack>
  );
}
