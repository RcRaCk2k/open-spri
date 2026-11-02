import { Alert, Button, Group, JsonInput, MultiSelect, Stack, Text, TextInput } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, call, type Schemas } from "../../api/client";
import { ErrorAlert } from "../../components/Feedback";
import { newIdempotencyKey } from "../../hooks";
import { DATE_ATTRIBUTES } from "./labels";

type Order = Schemas["OrderDto"];
type Detail = Schemas["OrderDetailDto"];

/** After an action: the order, its history and lists show the new state. */
function useAfterAction(orderId: number, onDone: () => void) {
  const client = useQueryClient();
  return async (detail: Detail, message: string) => {
    client.setQueryData(["order", orderId], detail);
    await Promise.all([
      client.invalidateQueries({ queryKey: ["order", orderId] }),
      client.invalidateQueries({ queryKey: ["history", orderId] }),
      client.invalidateQueries({ queryKey: ["orders"] }),
      client.invalidateQueries({ queryKey: ["deliveries"] }),
    ]);
    notifications.show({ color: "teal", message });
    onDone();
  };
}

/** Codes of the active table that fit the message type and business case (R-13); the API checks again. */
export function useCodeOptions(messageType: string, businessCase: string) {
  const codes = useQuery({
    queryKey: ["message-codes", messageType],
    queryFn: () => call(api.GET("/api/v1/message-codes", { params: { query: { messageType } } })),
    staleTime: 300_000,
  });
  const options = (codes.data?.codes ?? [])
    .filter((c) => c.businessCases.length === 0 || c.businessCases.includes(businessCase))
    .map((c) => ({ value: c.code, label: `${c.code} – ${c.text}` }));
  return { options, error: codes.error };
}

function parseAttributes(text: string): Record<string, unknown> | null {
  if (text.trim() === "") return {};
  try {
    const value: unknown = JSON.parse(text);
    return value && typeof value === "object" && !Array.isArray(value) ? (value as Record<string, unknown>) : null;
  } catch {
    return null;
  }
}

/** A message on the order (QEB, ABM, TAM, ERLM …, or ZWM-AG, TBK-AG, ERLM-K as Auftraggeber). */
export function MessageForm({ order, messageType, changeIndicator, onDone }: { order: Order; messageType: string; changeIndicator: string | null; onDone: () => void }) {
  const [codes, setCodes] = useState<string[]>([]);
  const [dates, setDates] = useState<Record<string, string>>({});
  const [attributes, setAttributes] = useState("");
  const [key] = useState(newIdempotencyKey);
  const { options, error: codeError } = useCodeOptions(messageType, order.businessCase);
  const after = useAfterAction(order.id, onDone);
  const parsed = parseAttributes(attributes);
  const send = useMutation({
    mutationFn: () => call(api.POST("/api/v1/orders/{id}/messages", {
      params: { path: { id: order.id } },
      headers: { "Idempotency-Key": key },
      body: {
        type: messageType,
        changeIndicator: changeIndicator ?? "Standard",
        codes: codes.map((code) => ({ code })),
        attributes: { ...parsed, ...Object.fromEntries(Object.entries(dates).filter(([, v]) => v !== "")) } as Schemas["JsonNode"],
      },
    })),
    onSuccess: (detail) => after(detail, `${messageType} in den Postausgang gelegt`),
  });
  const dateFields = DATE_ATTRIBUTES[messageType] ?? [];
  return (
    <Stack>
      <Text size="sm">Antwort auf den Auftrag mit Änderungskennzeichen <b>{changeIndicator ?? "Standard"}</b>. Kopf, Auftrags- und Vertragsnummern übernimmt open-spri aus dem Auftrag.</Text>
      <ErrorAlert error={codeError} />
      <MultiSelect label="Meldungscodes" required searchable data={options} value={codes} onChange={setCodes}
        description="aus der aktiven Meldungscodetabelle, passend zu Meldungstyp und Geschäftsfall" />
      {dateFields.map((f) => (
        <TextInput key={f.name} type="date" label={f.label} value={dates[f.name] ?? ""} onChange={(e) => { const v = e.currentTarget.value; setDates((d) => ({ ...d, [f.name]: v })); }} />
      ))}
      <JsonInput label="Weitere Meldungsattribute (JSON)" description={`Feldnamen wie in Meldungsattribute${messageType.replace("-", "")}Type der XSD`}
        placeholder='{"vorabstimmungId": "…"}' autosize minRows={3} value={attributes} onChange={setAttributes}
        error={parsed === null ? "kein gültiges JSON-Objekt" : undefined} />
      <ErrorAlert error={send.error} title={`${messageType} wurde nicht gesendet`} />
      <Group justify="flex-end">
        <Button onClick={() => send.mutate()} loading={send.isPending} disabled={codes.length === 0 || parsed === null}>{messageType} senden</Button>
      </Group>
    </Stack>
  );
}

export function CancelForm({ order, onDone }: { order: Order; onDone: () => void }) {
  const [key] = useState(newIdempotencyKey);
  const after = useAfterAction(order.id, onDone);
  const cancel = useMutation({
    mutationFn: () => call(api.POST("/api/v1/orders/{id}/cancel", { params: { path: { id: order.id } }, headers: { "Idempotency-Key": key } })),
    onSuccess: (detail) => after(detail, "Storno in den Postausgang gelegt"),
  });
  return (
    <Stack>
      <Text>Den Auftrag <b className="mono">{order.externalOrderNumber}</b> stornieren? open-spri leitet den Storno aus dem ursprünglichen Auftrag ab (Spezifikation 5.1.3).</Text>
      <ErrorAlert error={cancel.error} title="Storno wurde nicht gesendet" />
      <Group justify="flex-end"><Button color="red" onClick={() => cancel.mutate()} loading={cancel.isPending}>Storno senden</Button></Group>
    </Stack>
  );
}

export function RescheduleForm({ order, onDone }: { order: Order; onDone: () => void }) {
  const [date, setDate] = useState("");
  const [key] = useState(newIdempotencyKey);
  const after = useAfterAction(order.id, onDone);
  const reschedule = useMutation({
    mutationFn: () => call(api.POST("/api/v1/orders/{id}/reschedule", { params: { path: { id: order.id } }, headers: { "Idempotency-Key": key }, body: { newDate: date } })),
    onSuccess: (detail) => after(detail, "Terminverschiebung in den Postausgang gelegt"),
  });
  return (
    <Stack>
      <TextInput type="date" label="Neuer Termin (terminNeu)" required value={date} onChange={(e) => setDate(e.currentTarget.value)} />
      <ErrorAlert error={reschedule.error} title="Terminverschiebung wurde nicht gesendet" />
      <Group justify="flex-end"><Button onClick={() => reschedule.mutate()} loading={reschedule.isPending} disabled={date === ""}>Terminverschiebung senden</Button></Group>
    </Stack>
  );
}

/** Reklamation of a completed Entstörung (6.1.5): a complete order with the element `reklamation`, in XSD JSON. */
export function ComplaintForm({ order, onDone }: { order: Order; onDone: () => void }) {
  const [text, setText] = useState("");
  const [key] = useState(newIdempotencyKey);
  const after = useAfterAction(order.id, onDone);
  const parsed = parseAttributes(text);
  const complaint = useMutation({
    mutationFn: () => call(api.POST("/api/v1/orders/{id}/complaint", { params: { path: { id: order.id } }, headers: { "Idempotency-Key": key }, body: { auftrag: parsed as Schemas["JsonNode"] } })),
    onSuccess: (detail) => after(detail, "Reklamation in den Postausgang gelegt"),
  });
  return (
    <Stack>
      <Text size="sm">Ein vollständiger Entstörauftrag mit dem Element <code>reklamation</code>, Feldnamen wie in der XSD (docs/api/auftraege.md).</Text>
      <JsonInput label="auftrag (JSON)" required autosize minRows={8} value={text} onChange={setText} error={parsed === null ? "kein gültiges JSON-Objekt" : undefined} />
      <ErrorAlert error={complaint.error} title="Reklamation wurde nicht gesendet" />
      <Group justify="flex-end"><Button onClick={() => complaint.mutate()} loading={complaint.isPending} disabled={!parsed || Object.keys(parsed).length === 0}>Reklamation senden</Button></Group>
    </Stack>
  );
}

/** Draft: show and edit the stored order, send it or discard it. */
export function DraftPanel({ order, onDeleted }: { order: Order; onDeleted: () => void }) {
  const client = useQueryClient();
  const draft = useQuery({ queryKey: ["draft", order.id], queryFn: () => call(api.GET("/api/v1/orders/{id}/draft", { params: { path: { id: order.id } } })) });
  const [text, setText] = useState<string | null>(null);
  const [key] = useState(newIdempotencyKey);
  const after = useAfterAction(order.id, () => {});
  const shown = text ?? (draft.data ? JSON.stringify(draft.data.auftrag, null, 2) : "");
  const parsed = parseAttributes(shown);
  const save = useMutation({
    mutationFn: () => call(api.PUT("/api/v1/orders/{id}", { params: { path: { id: order.id } }, body: { partnerId: order.partnerId, auftrag: parsed as Schemas["JsonNode"] } })),
    onSuccess: async (detail) => { setText(null); await client.invalidateQueries({ queryKey: ["draft", order.id] }); await after(detail, "Entwurf gespeichert"); },
  });
  const send = useMutation({
    mutationFn: () => call(api.POST("/api/v1/orders/{id}/send", { params: { path: { id: order.id } }, headers: { "Idempotency-Key": key } })),
    onSuccess: (detail) => after(detail, "Auftrag in den Postausgang gelegt"),
  });
  const remove = useMutation({
    mutationFn: () => call(api.DELETE("/api/v1/orders/{id}", { params: { path: { id: order.id } } })),
    onSuccess: async () => { await client.invalidateQueries({ queryKey: ["orders"] }); notifications.show({ message: "Entwurf verworfen" }); onDeleted(); },
  });
  return (
    <Stack>
      <Alert color="gray" title="Entwurf">Der Auftrag ist noch nicht gesendet. Beim Senden prüft open-spri die Auftragsregeln (z. B. Wunschtermin) und baut den Kopf mit dem aktuellen Signaturschlüssel neu.</Alert>
      <ErrorAlert error={draft.error} />
      <JsonInput label="Auftrag (XSD-JSON)" autosize minRows={10} maxRows={30} value={shown} onChange={setText} error={parsed === null ? "kein gültiges JSON-Objekt" : undefined} />
      <ErrorAlert error={save.error ?? send.error ?? remove.error} />
      <Group justify="space-between">
        <Button color="red" variant="light" onClick={() => remove.mutate()} loading={remove.isPending}>Entwurf verwerfen</Button>
        <Group>
          <Button variant="default" onClick={() => save.mutate()} loading={save.isPending} disabled={text === null || parsed === null}>Änderungen speichern</Button>
          <Button onClick={() => send.mutate()} loading={send.isPending} disabled={text !== null}>Senden</Button>
        </Group>
      </Group>
    </Stack>
  );
}
