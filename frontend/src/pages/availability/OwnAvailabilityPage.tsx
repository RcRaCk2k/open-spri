import { Alert, Badge, Button, FileButton, Group, JsonInput, List, Modal, MultiSelect, PasswordInput, Select, SimpleGrid, Stack, Switch, Table, Tabs, TagsInput, Text, TextInput, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, call, type Schemas } from "../../api/client";
import { hasScope, Scope } from "../../auth/scopes";
import { ErrorAlert, Loading } from "../../components/Feedback";
import { rawBody } from "../../components/files";
import { formatDate, formatDateTime } from "../../format";
import { usePartnerNames, useScopes } from "../../hooks";
import { address, homeIds, products } from "./replikat";

/** Optional Replikat fields a release can leave out (Replikat.xsd 1.7). */
const OPTIONAL_FIELDS = ["aid", "geo", "ot", "hz", "gebaeudeteil", "kont", "hidl", "ausbaustatus", "bereitstellungszeit", "erwarteteBandbreite", "auftragsart", "eigentuemerdaten", "zl"];

const EXAMPLE_SITE = JSON.stringify({ plz: "12345", o: "Musterstadt", s: "Musterstraße", h: "1", pl: [{ produkt: "FTTH 100", technologie: "FTTH" }], tl: [{ technologie: "FTTH", ausbaustatus: "homesConnected", auftragsart: "STANDARD" }] }, null, 2);

/** Our own locations (D-13), maintained here or imported as Replikat (XML/ZIP) or CSV. */
function Sites({ admin }: { admin: boolean }) {
  const client = useQueryClient();
  const [filter, setFilter] = useState({ plz: "", s: "" });
  const [debounced] = useDebouncedValue(filter, 300);
  const [editing, setEditing] = useState<{ id: number | null; text: string } | null>(null);
  const [mode, setMode] = useState("merge");
  const sites = useQuery({
    queryKey: ["sites", debounced],
    queryFn: () => call(api.GET("/api/v1/availability/sites", { params: { query: { plz: debounced.plz || undefined, s: debounced.s || undefined, limit: 100 } } })),
  });
  const refresh = () => client.invalidateQueries({ queryKey: ["sites"] });
  const save = useMutation({
    mutationFn: ({ id, ds }: { id: number | null; ds: unknown }) => (id
      ? call(api.PUT("/api/v1/availability/sites/{id}", { params: { path: { id } }, body: ds as Schemas["JsonNode"] }))
      : call(api.POST("/api/v1/availability/sites", { body: ds as Schemas["JsonNode"] }))),
    onSuccess: async () => { await refresh(); setEditing(null); },
  });
  const remove = useMutation({ mutationFn: (id: number) => call(api.DELETE("/api/v1/availability/sites/{id}", { params: { path: { id } } })), onSuccess: refresh });
  const importFile = useMutation({
    mutationFn: async (file: File) => {
      const csv = file.name.toLowerCase().endsWith(".csv");
      const type = csv ? "text/csv" : file.name.toLowerCase().endsWith(".zip") ? "application/zip" : "application/xml";
      return call(api.POST("/api/v1/availability/sites/import", {
        params: { query: { mode } }, body: (csv ? await file.text() : await file.arrayBuffer()) as never, headers: { "Content-Type": type }, ...rawBody,
      }));
    },
    onSuccess: async (r) => { await refresh(); notifications.show({ color: "teal", message: `Import: ${r.added} neu, ${r.changed} geändert, ${r.deleted} entfernt, ${r.total} Standorte` }); },
  });
  let parsed: unknown = null;
  try { parsed = editing ? JSON.parse(editing.text) : null; } catch { parsed = null; }
  return (
    <Stack>
      <Group justify="space-between" align="flex-end">
        <Group role="search">
          <TextInput label="PLZ" value={filter.plz} onChange={(e) => { const v = e.currentTarget.value; setFilter((f) => ({ ...f, plz: v })); }} w={120} />
          <TextInput label="Straße (Anfang)" value={filter.s} onChange={(e) => { const v = e.currentTarget.value; setFilter((f) => ({ ...f, s: v })); }} />
        </Group>
        {admin && (
          <Group align="flex-end">
            <Select label="Import" data={[{ value: "merge", label: "hinzufügen/ersetzen" }, { value: "replace", label: "Bestand ersetzen" }]} allowDeselect={false} value={mode} onChange={(v) => setMode(v ?? "merge")} w={200} />
            <FileButton accept=".xml,.zip,.csv" onChange={(f) => f && importFile.mutate(f)}>
              {(props) => <Button {...props} variant="default" loading={importFile.isPending}>Datei importieren</Button>}
            </FileButton>
            <Button onClick={() => setEditing({ id: null, text: EXAMPLE_SITE })}>Standort anlegen</Button>
          </Group>
        )}
      </Group>
      <Text size="sm" c="dimmed">Import: Replikat (XML oder ZIP nach Replikat.xsd 1.7) oder CSV mit den Spalten aus docs/api/verfuegbarkeit.md. „Bestand ersetzen“ entfernt alle Standorte, die nicht in der Datei stehen.</Text>
      <ErrorAlert error={sites.error ?? remove.error ?? importFile.error} title={importFile.error ? "Import abgewiesen" : undefined} />
      {sites.isPending ? <Loading /> : sites.data?.length === 0 ? <Text c="dimmed">Keine Standorte.</Text> : (
        <Table.ScrollContainer minWidth={800}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Standort</Table.Th><Table.Th>Produkte</Table.Th><Table.Th>HomeIDs</Table.Th><Table.Th>Geändert</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {sites.data?.map((site) => (
                <Table.Tr key={site.id}>
                  <Table.Td>{address(site.ds)}</Table.Td>
                  <Table.Td><Text size="sm">{products(site.ds).map((p) => p.product).join(", ")}</Text></Table.Td>
                  <Table.Td className="mono"><Text size="sm">{homeIds(site.ds).join(", ")}</Text></Table.Td>
                  <Table.Td style={{ whiteSpace: "nowrap" }}>{formatDateTime(site.updatedAt)}</Table.Td>
                  <Table.Td>
                    {admin && (
                      <Group gap={4} wrap="nowrap">
                        <Button size="compact-xs" variant="subtle" onClick={() => setEditing({ id: site.id, text: JSON.stringify(site.ds, null, 2) })}>Ändern</Button>
                        <Button size="compact-xs" variant="subtle" color="red" onClick={() => remove.mutate(site.id)}>Löschen</Button>
                      </Group>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Modal opened={editing !== null} onClose={() => setEditing(null)} title={editing?.id ? "Standort ändern" : "Standort anlegen"} size="xl">
        {editing && (
          <Stack>
            <Text size="sm">Ein Datensatz <code>ds</code> der Replikat.xsd 1.7 als JSON (Feldnamen: <code>plz</code>, <code>o</code>, <code>s</code>, <code>h</code>, <code>pl</code>, <code>tl</code> …). open-spri prüft ihn gegen die XSD.</Text>
            <JsonInput label="Datensatz" autosize minRows={10} value={editing.text} onChange={(text) => setEditing({ ...editing, text })} error={parsed === null ? "kein gültiges JSON" : undefined} />
            <ErrorAlert error={save.error} />
            <Group justify="flex-end"><Button onClick={() => save.mutate({ id: editing.id, ds: parsed })} loading={save.isPending} disabled={parsed === null}>Speichern</Button></Group>
          </Stack>
        )}
      </Modal>
    </Stack>
  );
}

/** Which partner receives our Replikat, with which fields and quotas (Replikat 1.7). */
function Releases({ admin }: { admin: boolean }) {
  const client = useQueryClient();
  const partners = usePartnerNames();
  const [editing, setEditing] = useState<Schemas["ReleaseDto"] | null>(null);
  const [files, setFiles] = useState<number | null>(null);
  const releases = useQuery({ queryKey: ["releases"], queryFn: () => call(api.GET("/api/v1/replikat/releases")) });
  const fileList = useQuery({
    queryKey: ["replikat-files", files],
    queryFn: () => call(api.GET("/api/v1/replikat/releases/{partnerId}/files", { params: { path: { partnerId: files! } } })),
    enabled: files !== null,
  });
  const save = useMutation({
    mutationFn: (r: Schemas["ReleaseDto"]) => call(api.PUT("/api/v1/replikat/releases/{partnerId}", { params: { path: { partnerId: r.partnerId } }, body: r })),
    onSuccess: async () => { await client.invalidateQueries({ queryKey: ["releases"] }); setEditing(null); },
  });
  const remove = useMutation({ mutationFn: (partnerId: number) => call(api.DELETE("/api/v1/replikat/releases/{partnerId}", { params: { path: { partnerId } } })), onSuccess: () => client.invalidateQueries({ queryKey: ["releases"] }) });
  const run = useMutation({
    mutationFn: (partnerId: number) => call(api.POST("/api/v1/replikat/releases/{partnerId}/run", { params: { path: { partnerId } } })),
    onSuccess: async (r, partnerId) => { await client.invalidateQueries({ queryKey: ["replikat-files", partnerId] }); setFiles(partnerId); notifications.show({ color: "teal", message: `${r.length} Datei(en) erzeugt` }); },
  });
  return (
    <Stack>
      <Text size="sm" c="dimmed">Freigegebene Partner erhalten täglich (Standard 03:00) ein Vollreplikat und auf Wunsch ein Deltareplikat unter /replikat/ und können online abfragen. Sie brauchen dafür ein Partner-Token (Benutzer und Tokens).</Text>
      {admin && <Group justify="flex-end"><Button onClick={() => setEditing({ partnerId: 0, delta: true, enabled: true, omittedFields: [], quotas: [] })}>Partner freigeben</Button></Group>}
      <ErrorAlert error={releases.error ?? remove.error ?? run.error} />
      {releases.isPending ? <Loading /> : releases.data?.length === 0 ? <Text c="dimmed">Keine Freigaben.</Text> : (
        <Table striped>
          <Table.Thead><Table.Tr><Table.Th>Partner</Table.Th><Table.Th>Delta</Table.Th><Table.Th>ohne Felder</Table.Th><Table.Th>Kontingente</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr></Table.Thead>
          <Table.Tbody>
            {releases.data?.map((r) => (
              <Table.Tr key={r.partnerId}>
                <Table.Td>{partners.get(r.partnerId) ?? `#${r.partnerId}`}</Table.Td>
                <Table.Td>{r.delta ? "ja" : "nein"}</Table.Td>
                <Table.Td><Text size="sm">{r.omittedFields?.join(", ") || "–"}</Text></Table.Td>
                <Table.Td><Text size="sm">{r.quotas?.join(", ") || "alle"}</Text></Table.Td>
                <Table.Td>{r.enabled ? <Badge color="teal" variant="light">aktiv</Badge> : <Badge color="gray">pausiert</Badge>}</Table.Td>
                <Table.Td>
                  <Group gap={4} wrap="nowrap">
                    <Button size="compact-xs" variant="subtle" onClick={() => setFiles(r.partnerId)}>Dateien</Button>
                    {admin && <Button size="compact-xs" variant="subtle" onClick={() => run.mutate(r.partnerId)} loading={run.isPending && run.variables === r.partnerId}>Jetzt erzeugen</Button>}
                    {admin && <Button size="compact-xs" variant="subtle" onClick={() => setEditing(r)}>Ändern</Button>}
                    {admin && <Button size="compact-xs" variant="subtle" color="red" onClick={() => remove.mutate(r.partnerId)}>Entziehen</Button>}
                  </Group>
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <Modal opened={editing !== null} onClose={() => setEditing(null)} title="Replikat-Freigabe">
        {editing && (
          <Stack>
            <Select label="Partner" required searchable data={[...partners].map(([id, name]) => ({ value: String(id), label: name }))} value={editing.partnerId ? String(editing.partnerId) : null}
              onChange={(v) => setEditing({ ...editing, partnerId: Number(v ?? 0) })} />
            <Switch label="zusätzlich Deltareplikat" checked={editing.delta ?? false} onChange={(e) => { const v = e.currentTarget.checked; setEditing((x) => x && { ...x, delta: v }); }} />
            <MultiSelect label="Felder weglassen" description="optionale Felder, die nicht vereinbart sind" data={OPTIONAL_FIELDS} value={editing.omittedFields ?? []} onChange={(omittedFields) => setEditing({ ...editing, omittedFields })} clearable />
            <TagsInput label="Nur Kontingente (kont)" description="leer = alle Standorte; Eingabe mit Enter übernehmen" value={editing.quotas ?? []} onChange={(quotas) => setEditing({ ...editing, quotas })} clearable />
            <Switch label="aktiv" checked={editing.enabled ?? true} onChange={(e) => { const v = e.currentTarget.checked; setEditing((x) => x && { ...x, enabled: v }); }} />
            <ErrorAlert error={save.error} />
            <Group justify="flex-end"><Button onClick={() => save.mutate(editing)} loading={save.isPending} disabled={!editing.partnerId}>Speichern</Button></Group>
          </Stack>
        )}
      </Modal>
      <Modal opened={files !== null} onClose={() => setFiles(null)} title={`Replikate für ${files !== null ? partners.get(files) ?? files : ""}`} size="xl">
        <ErrorAlert error={fileList.error} />
        {fileList.isPending ? <Loading /> : fileList.data?.length === 0 ? <Text c="dimmed">Noch keine Dateien.</Text> : (
          <Table>
            <Table.Thead><Table.Tr><Table.Th>Datum</Table.Th><Table.Th>Datei</Table.Th><Table.Th>Art</Table.Th><Table.Th>Datensätze</Table.Th><Table.Th>MD5</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {fileList.data?.map((f) => (
                <Table.Tr key={f.fileName}><Table.Td>{formatDate(f.date)}</Table.Td><Table.Td className="mono">{f.fileName}</Table.Td><Table.Td>{f.kind === "DELTA" ? "Delta" : "Voll"}</Table.Td><Table.Td>{f.records}</Table.Td><Table.Td className="mono">{f.md5}</Table.Td></Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Modal>
    </Stack>
  );
}

/** Where we get the availability of our partners from (Auftraggeber): Replikat download or online query. */
function Sources({ admin }: { admin: boolean }) {
  const client = useQueryClient();
  const partners = usePartnerNames();
  const [editing, setEditing] = useState<Schemas["SourceDto"] | null>(null);
  const [history, setHistory] = useState<number | null>(null);
  const sources = useQuery({ queryKey: ["sources"], queryFn: () => call(api.GET("/api/v1/replikat/sources")) });
  const imports = useQuery({
    queryKey: ["imports", history],
    queryFn: () => call(api.GET("/api/v1/replikat/sources/{id}/imports", { params: { path: { id: history! }, query: { limit: 30 } } })),
    enabled: history !== null,
  });
  const save = useMutation({
    mutationFn: (s: Schemas["SourceDto"]) => (s.id ? call(api.PUT("/api/v1/replikat/sources/{id}", { params: { path: { id: s.id } }, body: s })) : call(api.POST("/api/v1/replikat/sources", { body: s }))),
    onSuccess: async () => { await client.invalidateQueries({ queryKey: ["sources"] }); setEditing(null); },
  });
  const remove = useMutation({ mutationFn: (id: number) => call(api.DELETE("/api/v1/replikat/sources/{id}", { params: { path: { id } } })), onSuccess: () => client.invalidateQueries({ queryKey: ["sources"] }) });
  const run = useMutation({
    mutationFn: (id: number) => call(api.POST("/api/v1/replikat/sources/{id}/run", { params: { path: { id } } })),
    onSuccess: async (r, id) => {
      await Promise.all([client.invalidateQueries({ queryKey: ["sources"] }), client.invalidateQueries({ queryKey: ["imports", id] })]);
      notifications.show({ color: r.status === "OK" ? "teal" : "red", message: r.status === "OK" ? `Import: ${r.records} Datensätze` : `Import fehlgeschlagen: ${r.error}` });
    },
  });
  return (
    <Stack>
      <Text size="sm" c="dimmed">Replikat-Quellen werden täglich importiert (Standard 07:00); bei Online-Quellen fragt die Suche die Instanz des Partners direkt.</Text>
      {admin && <Group justify="flex-end"><Button onClick={() => setEditing({ partnerId: 0, kind: "REPLIKAT", url: "", delta: true, enabled: true })}>Quelle anlegen</Button></Group>}
      <ErrorAlert error={sources.error ?? remove.error ?? run.error} />
      {sources.isPending ? <Loading /> : sources.data?.length === 0 ? <Text c="dimmed">Keine Quellen.</Text> : (
        <Table.ScrollContainer minWidth={800}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Partner</Table.Th><Table.Th>Art</Table.Th><Table.Th>URL</Table.Th><Table.Th>Standorte</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {sources.data?.map((s) => (
                <Table.Tr key={s.id}>
                  <Table.Td>{partners.get(s.partnerId) ?? `#${s.partnerId}`}</Table.Td>
                  <Table.Td>{s.kind === "ONLINE" ? "Online-Abfrage" : `Replikat${s.delta ? " + Delta" : ""}`}</Table.Td>
                  <Table.Td className="mono"><Text size="sm">{s.url}</Text>{s.hasSecret && <Text size="xs" c="dimmed">mit Zugangsdaten</Text>}</Table.Td>
                  <Table.Td>{s.locations ?? "–"}</Table.Td>
                  <Table.Td>{s.enabled ? <Badge color="teal" variant="light">aktiv</Badge> : <Badge color="gray">pausiert</Badge>}</Table.Td>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      {s.kind === "REPLIKAT" && <Button size="compact-xs" variant="subtle" onClick={() => setHistory(s.id!)}>Importe</Button>}
                      {admin && s.kind === "REPLIKAT" && <Button size="compact-xs" variant="subtle" onClick={() => run.mutate(s.id!)} loading={run.isPending && run.variables === s.id}>Jetzt importieren</Button>}
                      {admin && <Button size="compact-xs" variant="subtle" onClick={() => setEditing({ ...s, secret: null })}>Ändern</Button>}
                      {admin && <Button size="compact-xs" variant="subtle" color="red" onClick={() => remove.mutate(s.id!)}>Löschen</Button>}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Modal opened={editing !== null} onClose={() => setEditing(null)} title={editing?.id ? "Quelle ändern" : "Quelle anlegen"} size="lg">
        {editing && (
          <Stack>
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <Select label="Partner" required searchable data={[...partners].map(([id, name]) => ({ value: String(id), label: name }))} value={editing.partnerId ? String(editing.partnerId) : null}
                onChange={(v) => setEditing({ ...editing, partnerId: Number(v ?? 0) })} />
              <Select label="Art" allowDeselect={false} data={[{ value: "REPLIKAT", label: "Replikat (täglicher Download)" }, { value: "ONLINE", label: "Online-Abfrage (open-spri)" }]} value={editing.kind} onChange={(v) => setEditing({ ...editing, kind: v ?? "REPLIKAT" })} />
            </SimpleGrid>
            <TextInput label="URL" required placeholder={editing.kind === "ONLINE" ? "https://spri.partner.example" : "https://spri.partner.example/replikat/"} value={editing.url}
              onChange={(e) => { const v = e.currentTarget.value; setEditing((x) => x && { ...x, url: v }); }} />
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <TextInput label="Benutzer (Basic-Auth)" description="leer = Geheimnis als Bearer-Token" value={editing.username ?? ""} onChange={(e) => { const v = e.currentTarget.value; setEditing((x) => x && { ...x, username: v || null }); }} />
              <PasswordInput label="Passwort bzw. Token" description={editing.hasSecret ? "leer lassen = hinterlegtes behalten" : "wird verschlüsselt gespeichert"} value={editing.secret ?? ""}
                onChange={(e) => { const v = e.currentTarget.value; setEditing((x) => x && { ...x, secret: v || null }); }} />
            </SimpleGrid>
            {editing.kind === "REPLIKAT" && <Switch label="Deltareplikat anwenden" checked={editing.delta ?? false} onChange={(e) => { const v = e.currentTarget.checked; setEditing((x) => x && { ...x, delta: v }); }} />}
            <Switch label="aktiv" checked={editing.enabled ?? true} onChange={(e) => { const v = e.currentTarget.checked; setEditing((x) => x && { ...x, enabled: v }); }} />
            <ErrorAlert error={save.error} />
            <Group justify="flex-end"><Button onClick={() => save.mutate(editing)} loading={save.isPending} disabled={!editing.partnerId || !editing.url}>Speichern</Button></Group>
          </Stack>
        )}
      </Modal>
      <Modal opened={history !== null} onClose={() => setHistory(null)} title="Importläufe" size="xl">
        <ErrorAlert error={imports.error} />
        {imports.isPending ? <Loading /> : imports.data?.length === 0 ? <Text c="dimmed">Noch kein Import.</Text> : (
          <Table>
            <Table.Thead><Table.Tr><Table.Th>Zeit</Table.Th><Table.Th>Datei</Table.Th><Table.Th>Ergebnis</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {imports.data?.map((i) => (
                <Table.Tr key={i.id}>
                  <Table.Td style={{ whiteSpace: "nowrap" }}>{formatDateTime(i.startedAt)}</Table.Td>
                  <Table.Td className="mono">{i.fileName ?? "–"}</Table.Td>
                  <Table.Td>
                    {i.status === "OK" ? <Badge color="teal" variant="light">OK</Badge> : <Badge color="red" variant="light">fehlgeschlagen</Badge>}
                    {i.status === "OK" && <Text size="xs">{i.records} Datensätze: {i.added} neu, {i.changed} geändert, {i.deleted} gelöscht</Text>}
                    {i.error && <Text size="xs" c="var(--mantine-color-error)">{i.error}</Text>}
                    {i.warnings.length > 0 && <List size="xs">{i.warnings.map((w) => <List.Item key={w}>{w}</List.Item>)}</List>}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </Modal>
    </Stack>
  );
}

/** Own availability (AUFGABE 5.4): our locations, Replikat releases for partners, sources of the partners. */
export function OwnAvailabilityPage() {
  const admin = hasScope(useScopes(), Scope.availabilityAdmin);
  return (
    <Stack>
      <Title order={2}>Verfügbarkeit verwalten</Title>
      {!admin && <Alert color="gray">Nur lesen: Ändern braucht die Berechtigung availability:admin.</Alert>}
      <Tabs defaultValue="sites" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="sites">Eigene Standorte</Tabs.Tab>
          <Tabs.Tab value="releases">Replikat für Partner</Tabs.Tab>
          <Tabs.Tab value="sources">Quellen der Partner</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="sites" pt="md"><Sites admin={admin} /></Tabs.Panel>
        <Tabs.Panel value="releases" pt="md"><Releases admin={admin} /></Tabs.Panel>
        <Tabs.Panel value="sources" pt="md"><Sources admin={admin} /></Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
