import { Alert, Badge, Button, FileButton, Group, Modal, Select, Stack, Switch, Table, Tabs, Text, TextInput, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, call, type Schemas } from "../api/client";
import { hasScope, Scope } from "../auth/scopes";
import { ErrorAlert, Loading } from "../components/Feedback";
import { rawBody } from "../components/files";
import { usePartnerNames, useScopes } from "../hooks";

type Product = Schemas["ProductDto"];
type Change = Schemas["ChangeDto"];

const ROLES = [
  { value: "LEISTUNGSERBRINGER", label: "Unsere Produkte (Partner bestellen sie)" },
  { value: "AUFTRAGGEBER", label: "Produkte der Partner (wir bestellen sie)" },
];

function ProductForm({ product, partners, onClose }: { product: Product; partners: Map<number, string>; onClose: () => void }) {
  const client = useQueryClient();
  const [p, setP] = useState(product);
  const save = useMutation({
    mutationFn: () => (p.id ? call(api.PUT("/api/v1/products/{id}", { params: { path: { id: p.id } }, body: p })) : call(api.POST("/api/v1/products", { body: p }))),
    onSuccess: async () => { await client.invalidateQueries({ queryKey: ["products"] }); onClose(); },
  });
  return (
    <form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <Stack>
        <Select label="Art" data={ROLES} allowDeselect={false} value={p.role} onChange={(v) => setP({ ...p, role: v ?? "LEISTUNGSERBRINGER" })} />
        <TextInput label="Produktbezeichner" description="wie im Auftrag (produkt/bezeichner)" required value={p.identifier} onChange={(e) => { const v = e.currentTarget.value; setP((x) => ({ ...x, identifier: v })); }} />
        <TextInput label="Produktgruppe" placeholder="FTTH" value={p.productGroup ?? ""} onChange={(e) => { const v = e.currentTarget.value; setP((x) => ({ ...x, productGroup: v || null })); }} />
        <TextInput label="Beschreibung" value={p.description ?? ""} onChange={(e) => { const v = e.currentTarget.value; setP((x) => ({ ...x, description: v || null })); }} />
        <Select label="Partner" description="leer = gilt für alle Partner" data={[...partners].map(([id, name]) => ({ value: String(id), label: name }))} clearable
          value={p.partnerId ? String(p.partnerId) : null} onChange={(v) => setP({ ...p, partnerId: v ? Number(v) : null })} />
        <Switch label="aktiv" description="inaktive Produkte zählen bei der Prüfung eingehender Aufträge nicht" checked={p.active ?? true} onChange={(e) => { const v = e.currentTarget.checked; setP((x) => ({ ...x, active: v })); }} />
        <ErrorAlert error={save.error} />
        <Group justify="flex-end"><Button type="submit" loading={save.isPending}>Speichern</Button></Group>
      </Stack>
    </form>
  );
}

function Catalog({ editable }: { editable: boolean }) {
  const client = useQueryClient();
  const partners = usePartnerNames();
  const [role, setRole] = useState<string | null>(null);
  const [editing, setEditing] = useState<Product | null>(null);
  const products = useQuery({ queryKey: ["products", { role }], queryFn: () => call(api.GET("/api/v1/products", { params: { query: { role: role ?? undefined } } })) });
  const remove = useMutation({
    mutationFn: (id: number) => call(api.DELETE("/api/v1/products/{id}", { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ["products"] }),
  });
  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <Select label="Art" data={ROLES} value={role} onChange={setRole} clearable placeholder="alle" w={340} />
        {editable && <Button onClick={() => setEditing({ identifier: "", role: role ?? "LEISTUNGSERBRINGER", active: true })}>Produkt anlegen</Button>}
      </Group>
      <ErrorAlert error={products.error ?? remove.error} />
      {products.isPending ? <Loading /> : products.data?.length === 0 ? <Text c="dimmed">Keine Produkte.</Text> : (
        <Table.ScrollContainer minWidth={700}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Bezeichner</Table.Th><Table.Th>Gruppe</Table.Th><Table.Th>Art</Table.Th><Table.Th>Partner</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {products.data?.map((p) => (
                <Table.Tr key={p.id}>
                  <Table.Td><Text fw={500}>{p.identifier}</Text>{p.description && <Text size="sm" c="dimmed">{p.description}</Text>}</Table.Td>
                  <Table.Td>{p.productGroup ?? ""}</Table.Td>
                  <Table.Td>{p.role === "LEISTUNGSERBRINGER" ? "unser Produkt" : "Produkt des Partners"}</Table.Td>
                  <Table.Td>{p.partnerId ? partners.get(p.partnerId) ?? `#${p.partnerId}` : "alle"}</Table.Td>
                  <Table.Td>{p.active ? <Badge color="teal" variant="light">aktiv</Badge> : <Badge color="gray">inaktiv</Badge>}</Table.Td>
                  <Table.Td>
                    {editable && (
                      <Group gap={4} wrap="nowrap">
                        <Button size="compact-xs" variant="subtle" onClick={() => setEditing(p)}>Ändern</Button>
                        <Button size="compact-xs" variant="subtle" color="red" onClick={() => remove.mutate(p.id!)}>Löschen</Button>
                      </Group>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Modal opened={editing !== null} onClose={() => setEditing(null)} title={editing?.id ? "Produkt ändern" : "Produkt anlegen"}>
        {editing && <ProductForm product={editing} partners={partners} onClose={() => setEditing(null)} />}
      </Modal>
    </Stack>
  );
}

/** Anlage 6: which product change is a LAE, which an AEN-LMAE; without an entry only NEU + KUE-AG. */
function ChangeMatrix({ editable }: { editable: boolean }) {
  const client = useQueryClient();
  const partners = usePartnerNames();
  const [partner, setPartner] = useState<string | null>(null);
  const partnerId = partner ? Number(partner) : undefined;
  const [rows, setRows] = useState<Change[] | null>(null);
  const [lookup, setLookup] = useState({ from: "", to: "" });
  const matrix = useQuery({ queryKey: ["matrix", partnerId], queryFn: () => call(api.GET("/api/v1/product-change-matrix", { params: { query: { partnerId } } })) });
  const shown = rows ?? matrix.data?.changes ?? [];
  const done = async (m: Schemas["MatrixDto"]) => { client.setQueryData(["matrix", partnerId], m); setRows(null); notifications.show({ color: "teal", message: `${m.changes.length} Wechsel gespeichert` }); };
  const save = useMutation({ mutationFn: () => call(api.PUT("/api/v1/product-change-matrix", { params: { query: { partnerId } }, body: shown })), onSuccess: done });
  const importCsv = useMutation({
    mutationFn: async (file: File) => call(api.POST("/api/v1/product-change-matrix/import", {
      params: { query: { partnerId } }, body: (await file.text()) as never, headers: { "Content-Type": "text/csv" }, ...rawBody,
    })),
    onSuccess: done,
  });
  const result = useQuery({
    queryKey: ["matrix-lookup", partnerId, lookup],
    queryFn: () => call(api.GET("/api/v1/product-change-matrix/lookup", { params: { query: { partnerId, from: lookup.from, to: lookup.to } } })),
    enabled: false,
  });
  const update = (i: number, patch: Partial<Change>) => setRows(shown.map((r, j) => (j === i ? { ...r, ...patch } : r)));
  return (
    <Stack>
      <Group align="flex-end" justify="space-between">
        <Select label="Matrix" data={[...partners].map(([id, name]) => ({ value: String(id), label: `eigene Matrix: ${name}` }))} placeholder="Vorgabe für alle Partner" clearable value={partner}
          onChange={(v) => { setPartner(v); setRows(null); }} w={340} />
        {editable && (
          <FileButton accept=".csv,text/csv,text/plain" onChange={(f) => f && importCsv.mutate(f)}>
            {(props) => <Button {...props} variant="default" loading={importCsv.isPending}>CSV aus Anlage 6 importieren</Button>}
          </FileButton>
        )}
      </Group>
      <ErrorAlert error={matrix.error ?? save.error ?? importCsv.error} />
      {matrix.isPending ? <Loading /> : (
        <Table>
          <Table.Thead><Table.Tr><Table.Th>Bestandsprodukt</Table.Th><Table.Th>Zielprodukt</Table.Th><Table.Th>Geschäftsfall</Table.Th><Table.Th /></Table.Tr></Table.Thead>
          <Table.Tbody>
            {shown.map((c, i) => (
              <Table.Tr key={i}>
                <Table.Td>{editable ? <TextInput aria-label={`Bestandsprodukt ${i + 1}`} value={c.from} onChange={(e) => update(i, { from: e.currentTarget.value })} /> : c.from}</Table.Td>
                <Table.Td>{editable ? <TextInput aria-label={`Zielprodukt ${i + 1}`} value={c.to} onChange={(e) => update(i, { to: e.currentTarget.value })} /> : c.to}</Table.Td>
                <Table.Td>{editable ? <Select aria-label={`Geschäftsfall ${i + 1}`} data={["LAE", "AEN-LMAE"]} allowDeselect={false} value={c.businessCase} onChange={(v) => update(i, { businessCase: v ?? "LAE" })} /> : c.businessCase}</Table.Td>
                <Table.Td>{editable && <Button size="compact-xs" variant="subtle" color="red" onClick={() => setRows(shown.filter((_, j) => j !== i))}>Entfernen</Button>}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      {shown.length === 0 && !matrix.isPending && <Text size="sm" c="dimmed">Keine Einträge: Produktwechsel gehen nur mit NEU und KUE-AG.</Text>}
      {editable && (
        <Group justify="space-between">
          <Button variant="default" onClick={() => setRows([...shown, { from: "", to: "", businessCase: "LAE" }])}>Zeile hinzufügen</Button>
          <Button onClick={() => save.mutate()} loading={save.isPending} disabled={rows === null}>Matrix speichern</Button>
        </Group>
      )}
      <Group align="flex-end">
        <TextInput label="Wechsel prüfen: von" value={lookup.from} onChange={(e) => { const v = e.currentTarget.value; setLookup((l) => ({ ...l, from: v })); }} />
        <TextInput label="nach" value={lookup.to} onChange={(e) => { const v = e.currentTarget.value; setLookup((l) => ({ ...l, to: v })); }} />
        <Button variant="default" onClick={() => result.refetch()} disabled={!lookup.from || !lookup.to}>Prüfen</Button>
      </Group>
      {result.data && (
        <Alert color={result.data.businessCase ? "teal" : "orange"}>
          {result.data.businessCase ? `${result.data.from} → ${result.data.to}: ${result.data.businessCase}` : `${result.data.from} → ${result.data.to}: nur mit NEU und KUE-AG`} (Quelle: {result.data.source})
        </Alert>
      )}
      <ErrorAlert error={result.error} />
    </Stack>
  );
}

/** Product catalog and product change matrix (Anlage 4 and 6, docs/api/produkte.md). */
export function ProductsPage() {
  const editable = hasScope(useScopes(), Scope.partnersAdmin);
  return (
    <Stack>
      <Title order={2}>Produkte</Title>
      <Tabs defaultValue="catalog" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="catalog">Produktkatalog</Tabs.Tab>
          <Tabs.Tab value="matrix">Produktwechselmatrix</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="catalog" pt="md"><Catalog editable={editable} /></Tabs.Panel>
        <Tabs.Panel value="matrix" pt="md"><ChangeMatrix editable={editable} /></Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
