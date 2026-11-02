import { Alert, Anchor, Badge, Button, FileButton, Group, Stack, Switch, Table, Tabs, Text, Textarea, TextInput, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useParams } from "react-router";
import { api, call, type Schemas } from "../../api/client";
import { hasScope, Scope } from "../../auth/scopes";
import { ErrorAlert, Loading } from "../../components/Feedback";
import { lines, rawBody, saveFile } from "../../components/files";
import { formatDate } from "../../format";
import { useScopes } from "../../hooks";
import { AgreementTable } from "./AgreementTable";

type Partner = Schemas["PartnerDto"];

function MasterData({ partner, editable }: { partner: Partner; editable: boolean }) {
  const client = useQueryClient();
  const [form, setForm] = useState({ name: partner.name, carrierCode: partner.carrierCode, endpointUrl: partner.endpointUrl ?? "", technicalContactEmail: partner.technicalContactEmail ?? "" });
  const save = useMutation({
    mutationFn: (body: Schemas["UpdatePartnerRequest"]) => call(api.PATCH("/api/v1/partners/{id}", { params: { path: { id: partner.id } }, body })),
    onSuccess: async (p) => { client.setQueryData(["partner", partner.id], p); await client.invalidateQueries({ queryKey: ["partners"] }); notifications.show({ color: "teal", message: "Gespeichert" }); },
  });
  const set = (k: keyof typeof form) => (e: React.ChangeEvent<HTMLInputElement>) => { const v = e.currentTarget.value; setForm((f) => ({ ...f, [k]: v })); };
  return (
    <form onSubmit={(e) => { e.preventDefault(); save.mutate({ name: form.name, carrierCode: form.carrierCode, endpointUrl: form.endpointUrl || null, technicalContactEmail: form.technicalContactEmail || null }); }}>
      <Stack maw={640}>
        <TextInput label="Name" required disabled={!editable} value={form.name} onChange={set("name")} />
        <TextInput label="ITU Carrier Code" required disabled={!editable} value={form.carrierCode} onChange={set("carrierCode")} />
        <TextInput label="S/PRI-Endpunkt" disabled={!editable} value={form.endpointUrl} onChange={set("endpointUrl")} />
        <TextInput label="Technischer Kontakt (E-Mail)" disabled={!editable} value={form.technicalContactEmail} onChange={set("technicalContactEmail")} />
        <Switch label="Partner aktiv" description="deaktiviert: keine Aufträge an ihn, eingehende Nachrichten werden abgewiesen" disabled={!editable || save.isPending}
          checked={partner.enabled} onChange={(e) => save.mutate({ enabled: e.currentTarget.checked })} />
        <ErrorAlert error={save.error} />
        {editable && <Group justify="flex-end"><Button type="submit" loading={save.isPending}>Speichern</Button></Group>}
      </Stack>
    </form>
  );
}

function Certificates({ partner, editable }: { partner: Partner; editable: boolean }) {
  const client = useQueryClient();
  const [pem, setPem] = useState("");
  const done = async (p: Partner | undefined) => { if (p) client.setQueryData(["partner", partner.id], p); await client.invalidateQueries({ queryKey: ["partner", partner.id] }); };
  const add = useMutation({
    mutationFn: () => call(api.POST("/api/v1/partners/{id}/certificates", { params: { path: { id: partner.id } }, body: { pem } })),
    onSuccess: async (p) => { setPem(""); await done(p); },
  });
  const remove = useMutation({
    mutationFn: (certificateId: number) => call(api.DELETE("/api/v1/partners/{id}/certificates/{certificateId}", { params: { path: { id: partner.id, certificateId } } })),
    onSuccess: () => done(undefined),
  });
  return (
    <Stack>
      <Text size="sm">Mit diesen Zertifikaten prüfen wir die WS-Security-Signatur des Partners. Beim Zertifikatswechsel zuerst das neue hinzufügen, das alte erst nach der Umstellung entfernen.</Text>
      {partner.certificates.length === 0 ? <Alert color="orange">Kein Zertifikat: Nachrichten des Partners werden abgewiesen.</Alert> : (
        <Table.ScrollContainer minWidth={700}>
          <Table>
            <Table.Thead><Table.Tr><Table.Th>Inhaber</Table.Th><Table.Th>Aussteller</Table.Th><Table.Th>Seriennummer</Table.Th><Table.Th>Bit</Table.Th><Table.Th>gültig bis</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {partner.certificates.map((c) => (
                <Table.Tr key={c.id}>
                  <Table.Td><Text size="sm" className="mono">{c.subject}</Text></Table.Td>
                  <Table.Td><Text size="sm" className="mono">{c.issuer}</Text></Table.Td>
                  <Table.Td className="mono">{c.serialNumber}</Table.Td>
                  <Table.Td>{c.keyBits}{c.keyBits < 3072 && <Badge ml={4} color="orange" variant="light">unter 3072</Badge>}</Table.Td>
                  <Table.Td>{formatDate(c.notAfter)}{c.expired && <Badge ml={4} color="red" variant="light">abgelaufen</Badge>}</Table.Td>
                  <Table.Td>{editable && <Button size="compact-xs" color="red" variant="subtle" onClick={() => remove.mutate(c.id)}>Entfernen</Button>}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <ErrorAlert error={remove.error} />
      {editable && (
        <Stack>
          <Textarea label="Zertifikat hinzufügen (PEM)" autosize minRows={4} className="mono" value={pem} onChange={(e) => setPem(e.currentTarget.value)} />
          <ErrorAlert error={add.error} />
          <Group justify="flex-end"><Button onClick={() => add.mutate()} loading={add.isPending} disabled={!pem.trim()}>Hinzufügen</Button></Group>
        </Stack>
      )}
    </Stack>
  );
}

function Networks({ partner, editable }: { partner: Partner; editable: boolean }) {
  const client = useQueryClient();
  const [text, setText] = useState(partner.allowedSourceNetworks.join("\n"));
  const save = useMutation({
    mutationFn: () => call(api.PUT("/api/v1/partners/{id}/source-networks", { params: { path: { id: partner.id } }, body: lines(text) })),
    onSuccess: async (p) => { client.setQueryData(["partner", partner.id], p); notifications.show({ color: "teal", message: "IP-Freigaben gespeichert" }); },
  });
  return (
    <Stack maw={640}>
      <Text size="sm">Nur aus diesen Netzen nimmt unser S/PRI-Endpunkt Nachrichten des Partners an (AK-Sicherheitsdokument 2.6). Sie gelten auch für den Replikat-Download und die Online-Abfrage.</Text>
      <Textarea label="Quell-IPs (CIDR, eine pro Zeile)" autosize minRows={4} className="mono" disabled={!editable} value={text} onChange={(e) => setText(e.currentTarget.value)} />
      <ErrorAlert error={save.error} />
      {editable && <Group justify="flex-end"><Button onClick={() => save.mutate()} loading={save.isPending}>Speichern</Button></Group>}
    </Stack>
  );
}

interface AgreementApi<T> {
  list: () => Promise<T[]>;
  create: (row: T) => Promise<unknown>;
  update: (id: number, row: T) => Promise<unknown>;
  remove: (id: number) => Promise<unknown>;
}

function useAgreement<T extends { id?: number | null }>(partnerId: number, kind: string, ops: AgreementApi<T>) {
  const client = useQueryClient();
  const key = ["agreements", partnerId, kind];
  const list = useQuery({ queryKey: key, queryFn: ops.list });
  const save = useMutation({
    mutationFn: ({ row }: { row: T; done: () => void }) => (row.id ? ops.update(row.id, row) : ops.create(row)),
    onSuccess: async (_, { done }) => { await client.invalidateQueries({ queryKey: key }); done(); },
  });
  const remove = useMutation({ mutationFn: ops.remove, onSuccess: () => client.invalidateQueries({ queryKey: key }) });
  return { list, save, remove };
}

function Agreements({ partnerId, editable }: { partnerId: number; editable: boolean }) {
  const path = { partnerId };
  const contracts = useAgreement<Schemas["ContractDto"]>(partnerId, "contracts", {
    list: () => call(api.GET("/api/v1/partners/{partnerId}/contracts", { params: { path } })),
    create: (body) => call(api.POST("/api/v1/partners/{partnerId}/contracts", { params: { path }, body })),
    update: (id, body) => call(api.PUT("/api/v1/partners/{partnerId}/contracts/{id}", { params: { path: { partnerId, id } }, body })),
    remove: (id) => call(api.DELETE("/api/v1/partners/{partnerId}/contracts/{id}", { params: { path: { partnerId, id } } })),
  });
  const powers = useAgreement<Schemas["PowerOfAttorneyDto"]>(partnerId, "powers-of-attorney", {
    list: () => call(api.GET("/api/v1/partners/{partnerId}/powers-of-attorney", { params: { path } })),
    create: (body) => call(api.POST("/api/v1/partners/{partnerId}/powers-of-attorney", { params: { path }, body })),
    update: (id, body) => call(api.PUT("/api/v1/partners/{partnerId}/powers-of-attorney/{id}", { params: { path: { partnerId, id } }, body })),
    remove: (id) => call(api.DELETE("/api/v1/partners/{partnerId}/powers-of-attorney/{id}", { params: { path: { partnerId, id } } })),
  });
  const projects = useAgreement<Schemas["ProjectDto"]>(partnerId, "projects", {
    list: () => call(api.GET("/api/v1/partners/{partnerId}/projects", { params: { path } })),
    create: (body) => call(api.POST("/api/v1/partners/{partnerId}/projects", { params: { path }, body })),
    update: (id, body) => call(api.PUT("/api/v1/partners/{partnerId}/projects/{id}", { params: { path: { partnerId, id } }, body })),
    remove: (id) => call(api.DELETE("/api/v1/partners/{partnerId}/projects/{id}", { params: { path: { partnerId, id } } })),
  });
  const client = useQueryClient();
  const upload = useMutation({
    mutationFn: ({ id, file }: { id: number; file: File }) => call(api.PUT("/api/v1/partners/{partnerId}/powers-of-attorney/{id}/document", {
      params: { path: { partnerId, id }, query: { name: file.name }, header: { "Content-Type": file.type } },
      body: file as never, ...rawBody,
    })),
    onSuccess: () => client.invalidateQueries({ queryKey: ["agreements", partnerId, "powers-of-attorney"] }),
  });
  const download = useMutation({
    mutationFn: async (p: Schemas["PowerOfAttorneyDto"]) => {
      const blob = (await call(api.GET("/api/v1/partners/{partnerId}/powers-of-attorney/{id}/document", { params: { path: { partnerId, id: p.id! } }, parseAs: "blob" }))) as unknown as Blob;
      saveFile(blob, p.documentName ?? "vollmacht", blob.type);
    },
  });
  return (
    <Stack gap="xl">
      <Text size="sm">Ohne passenden Rahmenvertrag, Vollmacht oder Projektvereinbarung weist open-spri Aufträge ab (R-11, R-12). Ohne Einträge gilt keine Einschränkung.</Text>
      <AgreementTable title="Rahmenverträge" rows={contracts.list.data} loading={contracts.list.isPending} error={contracts.list.error} editable={editable}
        fields={[{ key: "role", label: "Unsere Rolle", type: "role" }, { key: "clientNumber", label: "Auftraggebernummer", required: true, mono: true }, { key: "serviceNumber", label: "Leistungsnummer", mono: true }, { key: "validFrom", label: "gültig ab", type: "date" }, { key: "validTo", label: "gültig bis", type: "date" }, { key: "description", label: "Beschreibung" }]}
        onSave={(row, done) => contracts.save.mutate({ row, done })} onDelete={(row) => contracts.remove.mutate(row.id!)} saving={contracts.save.isPending} saveError={contracts.save.error} />
      <AgreementTable title="Vollmachten (Aufträge für Dritte)" rows={powers.list.data} loading={powers.list.isPending} error={powers.list.error ?? upload.error ?? download.error} editable={editable}
        fields={[{ key: "clientNumber", label: "AG-Nr. Vollmachtgeber", required: true, mono: true }, { key: "serviceNumber", label: "Leistungsnr. Vollmachtgeber", required: true, mono: true }, { key: "ordererClientNumber", label: "AG-Nr. Besteller", required: true, mono: true }, { key: "ordererServiceNumber", label: "Leistungsnr. Besteller", required: true, mono: true }, { key: "validFrom", label: "gültig ab", type: "date" }, { key: "validTo", label: "gültig bis", type: "date" }]}
        onSave={(row, done) => powers.save.mutate({ row, done })} onDelete={(row) => powers.remove.mutate(row.id!)} saving={powers.save.isPending} saveError={powers.save.error}
        extra={(p) => (
          <>
            {p.documentName && <Button size="compact-xs" variant="subtle" onClick={() => download.mutate(p)}>Dokument</Button>}
            {editable && (
              <FileButton accept="application/pdf,image/png,image/jpeg" onChange={(file) => file && upload.mutate({ id: p.id!, file })}>
                {(props) => <Button {...props} size="compact-xs" variant="subtle">{p.documentName ? "Ersetzen" : "Hochladen"}</Button>}
              </FileButton>
            )}
          </>
        )} />
      <AgreementTable title="Projektvereinbarungen" rows={projects.list.data} loading={projects.list.isPending} error={projects.list.error} editable={editable}
        fields={[{ key: "projectId", label: "Projektkenner", required: true, mono: true }, { key: "validFrom", label: "gültig ab", type: "date" }, { key: "validTo", label: "gültig bis", type: "date" }, { key: "description", label: "Beschreibung" }]}
        onSave={(row, done) => projects.save.mutate({ row, done })} onDelete={(row) => projects.remove.mutate(row.id!)} saving={projects.save.isPending} saveError={projects.save.error} />
    </Stack>
  );
}

/** One partner: master data, certificates, IP allowlist, agreements (docs/api/partner.md). */
export function PartnerDetailPage() {
  const id = Number(useParams().id);
  const scopes = useScopes();
  const editable = hasScope(scopes, Scope.partnersAdmin);
  const partner = useQuery({ queryKey: ["partner", id], queryFn: () => call(api.GET("/api/v1/partners/{id}", { params: { path: { id } } })) });
  if (partner.isPending) return <Loading />;
  if (partner.isError) return <Stack><Title order={2}>Partner</Title><ErrorAlert error={partner.error} /></Stack>;
  const p = partner.data;
  return (
    <Stack>
      <Anchor component={Link} to="/partners" size="sm">← zur Liste</Anchor>
      <Group gap="sm"><Title order={2}>{p.name}</Title><Badge variant="light" className="mono">{p.carrierCode}</Badge>{!p.enabled && <Badge color="gray">deaktiviert</Badge>}</Group>
      <Tabs defaultValue="data" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="data">Stammdaten</Tabs.Tab>
          <Tabs.Tab value="certificates">Zertifikate ({p.certificates.length})</Tabs.Tab>
          <Tabs.Tab value="networks">IP-Freigaben ({p.allowedSourceNetworks.length})</Tabs.Tab>
          <Tabs.Tab value="agreements">Verträge und Vollmachten</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="data" pt="md"><MasterData key={`${p.id}-${p.enabled}`} partner={p} editable={editable} /></Tabs.Panel>
        <Tabs.Panel value="certificates" pt="md"><Certificates partner={p} editable={editable} /></Tabs.Panel>
        <Tabs.Panel value="networks" pt="md"><Networks partner={p} editable={editable} /></Tabs.Panel>
        <Tabs.Panel value="agreements" pt="md"><Agreements partnerId={p.id} editable={editable} /></Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
