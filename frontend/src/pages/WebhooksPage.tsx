import { Alert, Badge, Button, Group, Modal, MultiSelect, Stack, Switch, Table, Text, TextInput, Title } from "@mantine/core";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, call, type Schemas } from "../api/client";
import { ErrorAlert, Loading } from "../components/Feedback";
import { formatDateTime } from "../format";

/** Event types of the event feed (docs/api/ereignisse.md). */
const EVENT_TYPES = ["order.received", "request.received", "message.received", "order.submitted", "request.submitted", "message.submitted", "delivery.updated"];

type Webhook = Schemas["WebhookDto"];

function WebhookForm({ webhook, onSaved }: { webhook: Webhook | null; onSaved: (created?: Schemas["CreatedWebhookDto"]) => void }) {
  const client = useQueryClient();
  const [form, setForm] = useState<Schemas["WebhookRequest"]>({ url: webhook?.url ?? "", types: webhook?.types ?? [], description: webhook?.description ?? null, enabled: webhook?.enabled ?? true });
  const save = useMutation({
    mutationFn: async () => (webhook
      ? { updated: await call(api.PUT("/api/v1/webhooks/{id}", { params: { path: { id: webhook.id } }, body: form })) }
      : { created: await call(api.POST("/api/v1/webhooks", { body: form })) }),
    onSuccess: async (r) => { await client.invalidateQueries({ queryKey: ["webhooks"] }); onSaved("created" in r ? r.created : undefined); },
  });
  return (
    <form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <Stack>
        <TextInput label="URL des Empfängers" placeholder="https://crm.example.de/spri-events" required value={form.url} onChange={(e) => { const v = e.currentTarget.value; setForm((f) => ({ ...f, url: v })); }} />
        <MultiSelect label="Ereignistypen" description="leer = alle" data={EVENT_TYPES} value={form.types} onChange={(types) => setForm((f) => ({ ...f, types }))} clearable />
        <TextInput label="Beschreibung" value={form.description ?? ""} onChange={(e) => { const v = e.currentTarget.value; setForm((f) => ({ ...f, description: v || null })); }} />
        <Switch label="aktiv" checked={form.enabled ?? true} onChange={(e) => { const v = e.currentTarget.checked; setForm((f) => ({ ...f, enabled: v })); }} />
        <ErrorAlert error={save.error} />
        <Group justify="flex-end"><Button type="submit" loading={save.isPending}>Speichern</Button></Group>
      </Stack>
    </form>
  );
}

/** Webhooks (docs/api/ereignisse.md): events pushed to other systems, signed with HMAC-SHA256. */
export function WebhooksPage() {
  const client = useQueryClient();
  const [editing, setEditing] = useState<Webhook | "new" | null>(null);
  const [secret, setSecret] = useState<string | null>(null);
  const hooks = useQuery({ queryKey: ["webhooks"], queryFn: () => call(api.GET("/api/v1/webhooks")) });
  const remove = useMutation({
    mutationFn: (id: number) => call(api.DELETE("/api/v1/webhooks/{id}", { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ["webhooks"] }),
  });
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Webhooks</Title>
        <Button onClick={() => { setSecret(null); setEditing("new"); }}>Webhook anlegen</Button>
      </Group>
      <Text c="dimmed">Jedes Ereignis geht als POST mit der Signatur <code>X-OpenSpri-Signature</code> (HMAC-SHA256) an den Empfänger, in der Reihenfolge des Event-Feeds und mit Wiederholung bei Fehlern.</Text>
      <ErrorAlert error={hooks.error ?? remove.error} />
      {hooks.isPending ? <Loading /> : hooks.data?.length === 0 ? <Text c="dimmed">Keine Webhooks.</Text> : (
        <Table.ScrollContainer minWidth={800}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Empfänger</Table.Th><Table.Th>Ereignisse</Table.Th><Table.Th>Zuletzt zugestellt</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {hooks.data?.map((h) => (
                <Table.Tr key={h.id}>
                  <Table.Td><Text className="mono" size="sm">{h.url}</Text>{h.description && <Text size="sm" c="dimmed">{h.description}</Text>}</Table.Td>
                  <Table.Td><Text size="sm">{h.types.length === 0 ? "alle" : h.types.join(", ")}</Text></Table.Td>
                  <Table.Td>{formatDateTime(h.lastSuccessAt) || "noch nie"}<Text size="xs" c="dimmed">bis Ereignis {h.lastEventId}</Text></Table.Td>
                  <Table.Td>
                    {!h.enabled ? <Badge color="gray">inaktiv</Badge> : h.failures > 0 ? <Badge color="red" variant="light">{h.failures} Fehlversuche</Badge> : <Badge color="teal" variant="light">aktiv</Badge>}
                    {h.lastError && <Text size="xs" c="var(--mantine-color-error)" lineClamp={2}>{h.lastError}</Text>}
                  </Table.Td>
                  <Table.Td>
                    <Group gap={4} wrap="nowrap">
                      <Button size="compact-xs" variant="subtle" onClick={() => setEditing(h)}>Ändern</Button>
                      <Button size="compact-xs" variant="subtle" color="red" onClick={() => remove.mutate(h.id)}>Löschen</Button>
                    </Group>
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Modal opened={editing !== null} onClose={() => { setEditing(null); setSecret(null); }} title={secret ? "Webhook angelegt" : editing === "new" ? "Webhook anlegen" : "Webhook ändern"} size="lg">
        {secret ? (
          <Stack>
            <Alert color="orange" title="Nur jetzt sichtbar">Mit diesem Schlüssel prüft der Empfänger die Signatur. Er wird nicht wieder angezeigt.</Alert>
            <Text className="mono" data-testid="webhook-secret">{secret}</Text>
            <Group justify="flex-end"><Button onClick={() => { setEditing(null); setSecret(null); }}>Fertig</Button></Group>
          </Stack>
        ) : editing !== null && (
          <WebhookForm webhook={editing === "new" ? null : editing} onSaved={(created) => (created ? setSecret(created.secret) : setEditing(null))} />
        )}
      </Modal>
    </Stack>
  );
}
