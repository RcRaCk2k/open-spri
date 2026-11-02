import { Alert, Badge, Button, Card, Checkbox, Group, List, Modal, NumberInput, Select, SimpleGrid, Stack, Table, Tabs, Text, TextInput, Title } from "@mantine/core";
import { useMutation, useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { useNavigate } from "react-router";
import { api, call, type Schemas } from "../../api/client";
import type { paths } from "../../api/schema";
import { hasScope, Scope } from "../../auth/scopes";
import { ErrorAlert, Loading } from "../../components/Feedback";
import { usePartnerNames, useScopes } from "../../hooks";
import { fromAvailabilityTemplate, type OrderForm } from "../orders/orderForm";
import type { NewOrderState } from "../orders/NewOrderPage";
import { address, homeIds, products, technologies } from "./replikat";

type Hit = Schemas["HitDto"];
type Query = NonNullable<paths["/api/v1/availability/search"]["get"]["parameters"]["query"]>;

const STATUS: Record<string, string> = { homesPassed: "Homes passed", homesConnected: "Homes connected", homesActivated: "Homes activated", planned: "geplant", underConstruction: "im Bau" };

/** Choose products and HomeID of a hit; open-spri turns them into a NEU order (Replikat 1.7 chapter 4). */
function OrderFromHit({ hit, onClose }: { hit: Hit; onClose: () => void }) {
  const navigate = useNavigate();
  const offered = products(hit.ds);
  const ids = homeIds(hit.ds);
  const [chosen, setChosen] = useState<string[]>(offered[0] ? [offered[0].product] : []);
  const [hid, setHid] = useState<string | null>(ids.length === 1 ? ids[0]! : null);
  const template = useMutation({
    mutationFn: () => call(api.POST("/api/v1/availability/order-template", { body: { partnerId: hit.partnerId, ds: hit.ds, products: chosen, hid } })),
    onSuccess: (t) => {
      const prefill: Partial<OrderForm> = { ...fromAvailabilityTemplate(t.auftrag), partnerId: String(hit.partnerId) };
      const state: NewOrderState = { prefill, hints: t.hints, source: `Aus der Verfügbarkeit bei ${hit.partnerName}: ${address(hit.ds)}` };
      navigate("/orders/new", { state });
    },
  });
  return (
    <Stack>
      <Text size="sm">{address(hit.ds)} bei <b>{hit.partnerName}</b></Text>
      <Checkbox.Group label="Produkte" description="das erste gewählte wird Produkt der Auftragsposition" value={chosen} onChange={setChosen}>
        <Stack gap={4} mt={4}>{offered.map((p) => <Checkbox key={p.product} value={p.product} label={`${p.product}${p.down ? ` (${p.down}/${p.up} Mbit/s)` : ""}`} />)}</Stack>
      </Checkbox.Group>
      {ids.length > 0 && <Select label="HomeID" data={ids} value={hid} onChange={setHid} clearable={ids.length > 1} />}
      <ErrorAlert error={template.error} />
      <Group justify="flex-end">
        <Button variant="default" onClick={onClose}>Abbrechen</Button>
        <Button onClick={() => template.mutate()} loading={template.isPending} disabled={chosen.length === 0}>Weiter zum Auftrag</Button>
      </Group>
    </Stack>
  );
}

/** Availability at the partners (AUFGABE 5.4): imported Replikate and online queries of other open-spri instances. */
export function AvailabilityPage() {
  const scopes = useScopes();
  const partners = usePartnerNames();
  const [mode, setMode] = useState<string | null>("address");
  const [form, setForm] = useState({ plz: "", o: "", s: "", h: "", hz: "", hid: "", aid: "", lat: "", long: "", radius: 50, partnerId: "" });
  const [query, setQuery] = useState<Query | null>(null);
  const [ordering, setOrdering] = useState<Hit | null>(null);
  const result = useQuery({
    queryKey: ["availability-search", query],
    queryFn: () => call(api.GET("/api/v1/availability/search", { params: { query: query! } })),
    enabled: query !== null,
  });
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => { const v = e.currentTarget.value; setForm((f) => ({ ...f, [k]: v })); };
  const blank = (v: string) => (v.trim() === "" ? undefined : v.trim());

  function search(e: React.FormEvent) {
    e.preventDefault();
    const partnerId = form.partnerId ? Number(form.partnerId) : undefined;
    if (mode === "address") setQuery({ plz: blank(form.plz), o: blank(form.o), s: blank(form.s), h: blank(form.h), hz: blank(form.hz), partnerId });
    else if (mode === "ids") setQuery({ hid: blank(form.hid), aid: blank(form.aid), partnerId });
    else setQuery({ lat: Number(form.lat.replace(",", ".")), long: Number(form.long.replace(",", ".")), radius: form.radius, partnerId });
  }

  return (
    <Stack>
      <Title order={2}>Verfügbarkeit bei Partnern</Title>
      <Text c="dimmed">Sucht in den importierten Replikaten und fragt Partner mit Online-Abfrage direkt.</Text>
      <Card withBorder component="form" onSubmit={search} role="search" aria-label="Verfügbarkeit suchen">
        <Tabs value={mode} onChange={setMode}>
          <Tabs.List>
            <Tabs.Tab value="address">Adresse</Tabs.Tab>
            <Tabs.Tab value="ids">HomeID / Adress-ID</Tabs.Tab>
            <Tabs.Tab value="geo">Koordinaten</Tabs.Tab>
          </Tabs.List>
          <Tabs.Panel value="address" pt="sm">
            <SimpleGrid cols={{ base: 1, sm: 5 }}>
              <TextInput label="PLZ" value={form.plz} onChange={set("plz")} />
              <TextInput label="Ort" description="Anfang genügt" value={form.o} onChange={set("o")} />
              <TextInput label="Straße" description="Anfang genügt" value={form.s} onChange={set("s")} />
              <TextInput label="Hausnummer" value={form.h} onChange={set("h")} />
              <TextInput label="Zusatz" value={form.hz} onChange={set("hz")} />
            </SimpleGrid>
          </Tabs.Panel>
          <Tabs.Panel value="ids" pt="sm">
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <TextInput label="HomeID" value={form.hid} onChange={set("hid")} />
              <TextInput label="Adress-ID" value={form.aid} onChange={set("aid")} />
            </SimpleGrid>
          </Tabs.Panel>
          <Tabs.Panel value="geo" pt="sm">
            <SimpleGrid cols={{ base: 1, sm: 3 }}>
              <TextInput label="Breite (lat)" placeholder="52.5263" value={form.lat} onChange={set("lat")} />
              <TextInput label="Länge (long)" placeholder="13.3143" value={form.long} onChange={set("long")} />
              <NumberInput label="Umkreis in Metern" min={1} max={5000} value={form.radius} onChange={(v) => setForm((f) => ({ ...f, radius: Number(v) || 50 }))} />
            </SimpleGrid>
            <Text size="xs" c="dimmed" mt={4}>Nur in importierten Replikaten; die Online-Abfrage kennt keine Koordinaten.</Text>
          </Tabs.Panel>
        </Tabs>
        <Group justify="space-between" mt="md" align="flex-end">
          <Select label="Nur bei Partner" data={[...partners].map(([id, name]) => ({ value: String(id), label: name }))} value={form.partnerId || null}
            onChange={(v) => setForm((f) => ({ ...f, partnerId: v ?? "" }))} clearable placeholder="alle" w={260} />
          <Button type="submit" loading={result.isFetching}>Suchen</Button>
        </Group>
      </Card>

      <ErrorAlert error={result.error} />
      {result.data && Object.keys(result.data.errors).length > 0 && (
        <Alert color="orange" title="Nicht alle Partner haben geantwortet">
          <List size="sm">{Object.entries(result.data.errors).map(([id, e]) => <List.Item key={id}>{partners.get(Number(id)) ?? `#${id}`}: {e}</List.Item>)}</List>
        </Alert>
      )}
      {query && result.isPending && <Loading />}
      {result.data && (result.data.hits.length === 0 ? <Text c="dimmed">Keine Treffer.</Text> : (
        <Table.ScrollContainer minWidth={900}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Partner</Table.Th><Table.Th>Standort</Table.Th><Table.Th>Produkte</Table.Th><Table.Th>Ausbau</Table.Th><Table.Th>HomeIDs</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {result.data.hits.map((hit, i) => (
                <Table.Tr key={i}>
                  <Table.Td>{hit.partnerName}<br /><Badge size="xs" variant="light">{hit.source === "ONLINE" ? "online" : "Replikat"}</Badge></Table.Td>
                  <Table.Td>{address(hit.ds)}</Table.Td>
                  <Table.Td>
                    <List size="sm">{products(hit.ds).map((p) => <List.Item key={p.product}>{p.product}{p.down && ` · ${p.down}/${p.up} Mbit/s`}{p.days && ` · ${p.days} Tage`}</List.Item>)}</List>
                  </Table.Td>
                  <Table.Td>
                    {technologies(hit.ds).map((t) => (
                      <Text size="sm" key={t.technology}>{t.technology}: {STATUS[t.status] ?? t.status}{t.orderKind === "KONNEKTIVITAET" && " · Konnektivität"}{t.ownerData && " · Eigentümerdaten"}</Text>
                    ))}
                  </Table.Td>
                  <Table.Td className="mono">{homeIds(hit.ds).join(", ")}</Table.Td>
                  <Table.Td>{hasScope(scopes, Scope.ordersWrite) && <Button size="xs" onClick={() => setOrdering(hit)}>Auftrag anlegen</Button>}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      ))}
      <Modal opened={ordering !== null} onClose={() => setOrdering(null)} title="Auftrag aus Treffer anlegen" size="lg">
        {ordering && <OrderFromHit hit={ordering} onClose={() => setOrdering(null)} />}
      </Modal>
    </Stack>
  );
}
