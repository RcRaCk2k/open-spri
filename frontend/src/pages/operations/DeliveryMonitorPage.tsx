import { Anchor, Badge, Button, Group, Modal, MultiSelect, Select, Stack, Table, Tabs, Text, Textarea, TextInput, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { api, call, type Schemas } from "../../api/client";
import { hasScope, Scope } from "../../auth/scopes";
import { ErrorAlert, Loading } from "../../components/Feedback";
import { formatDateTime } from "../../format";
import { usePartnerNames, useScopes } from "../../hooks";
import { RawXmlModal, type RawSource } from "../orders/RawXml";

const PAGE = 50;
const DELIVERY_STATUS = ["PENDING", "DELIVERED", "DELIVERED_WITH_ERROR", "REJECTED", "UNCLEAR", "UNDELIVERABLE", "SENT_BY_EMAIL", "CANCELLED"];
const STATUS_COLORS: Record<string, string> = { PENDING: "blue", DELIVERED: "teal", SENT_BY_EMAIL: "teal", CANCELLED: "gray" };

/** Outbox (docs/api/zustellung.md): strict order per line or order; a stuck message blocks the ones after it. */
function Outbox({ onRaw }: { onRaw: (s: RawSource) => void }) {
  const client = useQueryClient();
  const scopes = useScopes();
  const partners = usePartnerNames();
  const [status, setStatus] = useState<string | null>("UNDELIVERABLE");
  const query = useInfiniteQuery({
    queryKey: ["deliveries", { status }],
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => call(api.GET("/api/v1/deliveries", { params: { query: { status: status ?? undefined, cursor: pageParam, limit: PAGE } } })),
    getNextPageParam: (last) => (last.length === PAGE ? Math.min(...last.map((d) => d.id)) : undefined),
  });
  const action = useMutation({
    mutationFn: ({ id, kind }: { id: number; kind: "retry" | "cancel" | "sent-by-email" }) => {
      const params = { params: { path: { id } } };
      if (kind === "retry") return call(api.POST("/api/v1/deliveries/{id}/retry", params));
      if (kind === "cancel") return call(api.POST("/api/v1/deliveries/{id}/cancel", params));
      return call(api.POST("/api/v1/deliveries/{id}/sent-by-email", params));
    },
    onSuccess: async (d) => { await client.invalidateQueries({ queryKey: ["deliveries"] }); notifications.show({ message: `Nachricht ${d.id}: ${d.status}` }); },
  });
  const write = hasScope(scopes, Scope.deliveriesWrite);
  const rows = query.data?.pages.flat() ?? [];
  return (
    <Stack>
      <Select label="Status" data={DELIVERY_STATUS} value={status} onChange={setStatus} clearable placeholder="alle" w={260} />
      <ErrorAlert error={query.error ?? action.error} />
      {query.isPending ? <Loading /> : rows.length === 0 ? <Text c="dimmed">Keine Nachrichten.</Text> : (
        <Table.ScrollContainer minWidth={1000}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Erstellt</Table.Th><Table.Th>Partner</Table.Th><Table.Th>Auftrag</Table.Th><Table.Th>Operation</Table.Th><Table.Th>Status</Table.Th><Table.Th>Versuche</Table.Th><Table.Th>Nächster Versuch</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {rows.map((d) => {
                const stuck = ["UNDELIVERABLE", "UNCLEAR"].includes(d.status);
                return (
                  <Table.Tr key={d.id}>
                    <Table.Td style={{ whiteSpace: "nowrap" }}>{formatDateTime(d.createdAt)}</Table.Td>
                    <Table.Td>{partners.get(d.partnerId) ?? `#${d.partnerId}`}</Table.Td>
                    <Table.Td className="mono">{d.orderId ? <Anchor component={Link} to={`/orders/${d.orderId}`}>{d.externalOrderNumber ?? d.orderId}</Anchor> : d.externalOrderNumber}</Table.Td>
                    <Table.Td>{d.operation}</Table.Td>
                    <Table.Td>
                      <Badge color={STATUS_COLORS[d.status] ?? "red"} variant="light">{d.status}</Badge>{d.teqCode && <Text size="xs">TEQ {d.teqCode}</Text>}
                      {d.lastError && <Text size="xs" c="var(--mantine-color-error)" lineClamp={2}>{d.lastError}</Text>}
                    </Table.Td>
                    <Table.Td>{d.attempts}</Table.Td>
                    <Table.Td style={{ whiteSpace: "nowrap" }}>{d.status === "PENDING" ? formatDateTime(d.nextAttemptAt) : ""}</Table.Td>
                    <Table.Td>
                      <Group gap={4} wrap="nowrap">
                        <Button size="compact-xs" variant="subtle" onClick={() => onRaw({ kind: "delivery", id: d.id, title: `Nachricht ${d.id} – Inhalt` })}>XML</Button>
                        {write && stuck && <Button size="compact-xs" variant="subtle" onClick={() => action.mutate({ id: d.id, kind: "retry" })}>Erneut senden</Button>}
                        {write && stuck && <Button size="compact-xs" variant="subtle" onClick={() => action.mutate({ id: d.id, kind: "sent-by-email" })}>Per Mail gesendet</Button>}
                        {write && (stuck || d.status === "PENDING") && <Button size="compact-xs" variant="subtle" color="red" onClick={() => action.mutate({ id: d.id, kind: "cancel" })}>Abbrechen</Button>}
                      </Group>
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {query.hasNextPage && <Group justify="center"><Button variant="default" onClick={() => query.fetchNextPage()} loading={query.isFetchingNextPage}>Weitere laden</Button></Group>}
    </Stack>
  );
}

/** Inbound queue: received messages and their processing; FAILED blocks further messages of the order. */
function Inbox({ onRaw }: { onRaw: (s: RawSource) => void }) {
  const client = useQueryClient();
  const scopes = useScopes();
  const partners = usePartnerNames();
  const [status, setStatus] = useState<string | null>("FAILED");
  const inbox = useQuery({ queryKey: ["inbox", { status }], queryFn: () => call(api.GET("/api/v1/inbox", { params: { query: { status: status ?? undefined, limit: 100 } } })) });
  const reprocess = useMutation({
    mutationFn: (id: number) => call(api.POST("/api/v1/inbox/{id}/reprocess", { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ["inbox"] }),
  });
  return (
    <Stack>
      <Select label="Status" data={["RECEIVED", "PROCESSED", "FAILED"]} value={status} onChange={setStatus} clearable placeholder="alle" w={260} />
      <ErrorAlert error={inbox.error ?? reprocess.error} />
      {inbox.isPending ? <Loading /> : inbox.data?.length === 0 ? <Text c="dimmed">Keine Nachrichten.</Text> : (
        <Table.ScrollContainer minWidth={900}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Empfangen</Table.Th><Table.Th>Partner</Table.Th><Table.Th>Auftrag</Table.Th><Table.Th>Operation</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {inbox.data?.map((m: Schemas["InboxDto"]) => (
                <Table.Tr key={m.id}>
                  <Table.Td style={{ whiteSpace: "nowrap" }}>{formatDateTime(m.receivedAt)}</Table.Td>
                  <Table.Td>{partners.get(m.partnerId) ?? `#${m.partnerId}`}</Table.Td>
                  <Table.Td className="mono">{m.externalOrderNumber}</Table.Td>
                  <Table.Td>{m.operation}</Table.Td>
                  <Table.Td>
                    <Badge color={m.status === "FAILED" ? "red" : m.status === "PROCESSED" ? "teal" : "blue"} variant="light">{m.status}</Badge>
                    {m.error && <Text size="xs" c="var(--mantine-color-error)" lineClamp={2}>{m.error}</Text>}
                    {m.findings && <Text size="xs" c="dimmed" lineClamp={2}>{m.findings}</Text>}
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      <Button size="compact-xs" variant="subtle" onClick={() => onRaw({ kind: "archive", id: m.archiveId, title: `Eingang ${m.id} – Rohnachricht` })}>Roh-XML</Button>
                      {m.status === "FAILED" && hasScope(scopes, Scope.deliveriesWrite) && <Button size="compact-xs" variant="subtle" onClick={() => reprocess.mutate(m.id)}>Erneut verarbeiten</Button>}
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}

/** Maintenance announcement to the technical contacts of the partners (NF-08: at least 5 working days ahead). */
function Maintenance() {
  const client = useQueryClient();
  const scopes = useScopes();
  const partners = usePartnerNames();
  const [open, setOpen] = useState(false);
  const [form, setForm] = useState({ subject: "", text: "", start: "", end: "", partnerIds: [] as string[] });
  const list = useQuery({ queryKey: ["maintenance"], queryFn: () => call(api.GET("/api/v1/maintenance-announcements", { params: { query: { limit: 50 } } })) });
  const send = useMutation({
    mutationFn: () => call(api.POST("/api/v1/maintenance-announcements", {
      body: { subject: form.subject, text: form.text, start: new Date(form.start).toISOString(), end: new Date(form.end).toISOString(), partnerIds: form.partnerIds.length ? form.partnerIds.map(Number) : null },
    })),
    onSuccess: async (a) => {
      await client.invalidateQueries({ queryKey: ["maintenance"] });
      setOpen(false);
      notifications.show({ color: a.warnings.length ? "orange" : "teal", message: `An ${a.recipients} Partner gesendet${a.warnings.length ? `: ${a.warnings.join("; ")}` : ""}` });
    },
  });
  const set = (k: "subject" | "start" | "end") => (e: React.ChangeEvent<HTMLInputElement>) => { const v = e.currentTarget.value; setForm((f) => ({ ...f, [k]: v })); };
  return (
    <Stack>
      {hasScope(scopes, Scope.partnersAdmin) && <Group justify="flex-end"><Button onClick={() => setOpen(true)}>Wartung ankündigen</Button></Group>}
      <ErrorAlert error={list.error} />
      {list.isPending ? <Loading /> : list.data?.length === 0 ? <Text c="dimmed">Noch keine Ankündigungen.</Text> : (
        <Table striped>
          <Table.Thead><Table.Tr><Table.Th>Zeitraum</Table.Th><Table.Th>Betreff</Table.Th><Table.Th>Empfänger</Table.Th><Table.Th>Hinweise</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            {list.data?.map((a) => (
              <Table.Tr key={a.id}>
                <Table.Td style={{ whiteSpace: "nowrap" }}>{formatDateTime(a.start)} – {formatDateTime(a.end)}</Table.Td>
                <Table.Td>{a.subject}</Table.Td>
                <Table.Td>{a.recipients}</Table.Td>
                <Table.Td><Text size="sm">{a.warnings.join("; ")}</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
      <Modal opened={open} onClose={() => setOpen(false)} title="Wartung ankündigen" size="lg">
        <form onSubmit={(e) => { e.preventDefault(); send.mutate(); }}>
          <Stack>
            <TextInput label="Betreff" required value={form.subject} onChange={set("subject")} />
            <Group grow>
              <TextInput label="Beginn" type="datetime-local" required value={form.start} onChange={set("start")} />
              <TextInput label="Ende" type="datetime-local" required value={form.end} onChange={set("end")} />
            </Group>
            <Textarea label="Text" description="Grund und Auswirkung" required autosize minRows={3} value={form.text} onChange={(e) => { const v = e.currentTarget.value; setForm((f) => ({ ...f, text: v })); }} />
            <MultiSelect label="Nur an diese Partner" description="leer = alle aktiven Partner" data={[...partners].map(([id, name]) => ({ value: String(id), label: name }))}
              value={form.partnerIds} onChange={(partnerIds) => setForm((f) => ({ ...f, partnerIds }))} clearable searchable />
            <ErrorAlert error={send.error} />
            <Group justify="flex-end"><Button type="submit" loading={send.isPending}>Senden</Button></Group>
          </Stack>
        </form>
      </Modal>
    </Stack>
  );
}

/** Delivery monitor (AUFGABE 8.6): outbox, inbound queue, maintenance announcements. */
export function DeliveryMonitorPage() {
  const [raw, setRaw] = useState<RawSource | null>(null);
  return (
    <Stack>
      <Title order={2}>Zustellmonitor</Title>
      <Tabs defaultValue="outbox" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="outbox">Postausgang</Tabs.Tab>
          <Tabs.Tab value="inbox">Eingang</Tabs.Tab>
          <Tabs.Tab value="maintenance">Wartung</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="outbox" pt="md"><Outbox onRaw={setRaw} /></Tabs.Panel>
        <Tabs.Panel value="inbox" pt="md"><Inbox onRaw={setRaw} /></Tabs.Panel>
        <Tabs.Panel value="maintenance" pt="md"><Maintenance /></Tabs.Panel>
      </Tabs>
      <RawXmlModal source={raw} onClose={() => setRaw(null)} />
    </Stack>
  );
}
