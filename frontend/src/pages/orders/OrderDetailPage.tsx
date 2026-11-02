import { Anchor, Badge, Button, Card, Group, List, Modal, SimpleGrid, Stack, Table, Text, Timeline, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate, useParams } from "react-router";
import { api, call, type Schemas } from "../../api/client";
import { hasScope, Scope } from "../../auth/scopes";
import { ErrorAlert, Loading } from "../../components/Feedback";
import { PhaseBadge } from "../../components/OrderBadges";
import { formatDate, formatDateTime } from "../../format";
import { usePartnerNames, useScopes } from "../../hooks";
import { CancelForm, ComplaintForm, DraftPanel, MessageForm, RescheduleForm } from "./ActionDialogs";
import { HANDOVER_LABELS, REQUEST_LABELS, ROLE_NAMES } from "./labels";
import { PvReplyForm } from "./PvReplyForm";
import { RawXmlModal, type RawSource } from "./RawXml";

type Action = Schemas["ActionDto"];
type Order = Schemas["OrderDto"];

export function actionLabel(a: Action): string {
  if (a.kind === "MESSAGE") return `${a.messageType}${a.changeIndicator && a.changeIndicator !== "Standard" ? ` (${a.changeIndicator})` : ""}`;
  if (a.kind === "REQUEST") return REQUEST_LABELS[a.request ?? ""] ?? a.request ?? "";
  return "Senden";
}

/** Which scope an action needs (docs/api/auth.md); without it the button is not shown. */
export function actionScope(order: Order, a: Action) {
  return a.kind === "MESSAGE" && order.role === "LEISTUNGSERBRINGER" ? Scope.leMessagesWrite : Scope.ordersWrite;
}

function ActionForm({ order, action, onDone }: { order: Order; action: Action; onDone: () => void }) {
  if (action.kind === "MESSAGE" && action.messageType === "RUEM-PV") return <PvReplyForm orderId={order.id} onDone={onDone} />;
  if (action.kind === "MESSAGE") return <MessageForm order={order} messageType={action.messageType ?? ""} changeIndicator={action.changeIndicator ?? null} onDone={onDone} />;
  if (action.request === "STORNO") return <CancelForm order={order} onDone={onDone} />;
  if (action.request === "TERMINVERSCHIEBUNG") return <RescheduleForm order={order} onDone={onDone} />;
  if (action.request === "REKLAMATION") return <ComplaintForm order={order} onDone={onDone} />;
  return null;
}

function Facts({ order, partner }: { order: Order; partner: string }) {
  const rows: [string, string | null | undefined][] = [
    ["Partner", partner],
    ["Unsere Rolle", ROLE_NAMES[order.role] ?? order.role],
    ["Geschäftsfall", order.businessCase],
    ["Auftraggebernummer", order.clientNumber],
    ["Leistungsnummer", order.serviceNumber],
    ["Vertragsnummer", order.contractNumber],
    ["LineID", order.lineId],
    ["HomeID", order.homeId],
    ["Wunschtermin", formatDate(order.desiredDate)],
    ["Produkte", order.products.join(", ")],
    ["Providerwechsel", order.handoverPhase ? HANDOVER_LABELS[order.handoverPhase] ?? order.handoverPhase : null],
    ["Angelegt", formatDateTime(order.createdAt)],
    ["Geändert", formatDateTime(order.updatedAt)],
  ];
  return (
    <Table variant="vertical" layout="fixed">
      <Table.Tbody>
        {rows.filter(([, v]) => v).map(([k, v]) => (
          <Table.Tr key={k}><Table.Th w={180}>{k}</Table.Th><Table.Td className={k.endsWith("nummer") || k.endsWith("ID") ? "mono" : undefined}>{v}</Table.Td></Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

function History({ orderId, onRaw }: { orderId: number; onRaw: (s: RawSource) => void }) {
  const scopes = useScopes();
  const history = useQuery({ queryKey: ["history", orderId], queryFn: () => call(api.GET("/api/v1/orders/{id}/history", { params: { path: { id: orderId } } })) });
  const codes = useQuery({ queryKey: ["message-codes", "all"], queryFn: () => call(api.GET("/api/v1/message-codes")), staleTime: 300_000 });
  const text = new Map((codes.data?.codes ?? []).map((c) => [c.code, c.text]));
  if (history.isPending) return <Loading />;
  if (history.isError) return <ErrorAlert error={history.error} />;
  if (history.data.length === 0) return <Text c="dimmed">Noch kein Verlauf (Entwurf).</Text>;
  return (
    <Timeline bulletSize={22} lineWidth={2} active={history.data.length - 1}>
      {history.data.map((h, i) => {
        const what = h.kind === "MESSAGE" ? h.messageType : h.kind === "ORDER" ? "Auftrag" : `Änderung (${h.changeIndicator})`;
        const inbound = h.direction === "INBOUND";
        return (
          <Timeline.Item key={i} bullet={<Text size="xs" fw={700} aria-hidden>{inbound ? "←" : "→"}</Text>}
            title={<Group gap="xs"><Text fw={600}>{what}</Text><Badge size="sm" variant="light" color={inbound ? "blue" : "teal"}>{inbound ? "vom Partner" : "an den Partner"}</Badge>{h.changeIndicator !== "Standard" && h.kind !== "REQUEST" && <Badge size="sm" variant="outline">{h.changeIndicator}</Badge>}</Group>}>
            <Text size="sm" c="dimmed">{formatDateTime(h.at)}</Text>
            {h.codes.length > 0 && (
              <List size="sm" mt={4}>{h.codes.map((c) => <List.Item key={c}><span className="mono">{c}</span>{text.get(c) ? ` – ${text.get(c)}` : ""}</List.Item>)}</List>
            )}
            {h.findings.length > 0 && (
              <List size="sm" mt={4} c="var(--mantine-color-orange-light-color)">{h.findings.map((f) => <List.Item key={f}>{f}</List.Item>)}</List>
            )}
            {h.archiveId != null && hasScope(scopes, Scope.ordersRead) && (
              <Button size="compact-xs" variant="subtle" mt={4} onClick={() => onRaw({ kind: "archive", id: h.archiveId!, title: `${what} – Rohnachricht` })}>Roh-XML</Button>
            )}
          </Timeline.Item>
        );
      })}
    </Timeline>
  );
}

function Deliveries({ orderId, onRaw }: { orderId: number; onRaw: (s: RawSource) => void }) {
  const deliveries = useQuery({ queryKey: ["deliveries", { orderId }], queryFn: () => call(api.GET("/api/v1/deliveries", { params: { query: { orderId, limit: 100 } } })) });
  if (deliveries.isPending) return <Loading />;
  if (deliveries.isError) return <ErrorAlert error={deliveries.error} />;
  if (deliveries.data.length === 0) return <Text c="dimmed">Keine ausgehenden Nachrichten.</Text>;
  return (
    <Table.ScrollContainer minWidth={600}>
      <Table>
        <Table.Thead><Table.Tr><Table.Th>Erstellt</Table.Th><Table.Th>Operation</Table.Th><Table.Th>Status</Table.Th><Table.Th>Versuche</Table.Th><Table.Th /></Table.Tr></Table.Thead>
        <Table.Tbody>
          {deliveries.data.map((d) => (
            <Table.Tr key={d.id}>
              <Table.Td style={{ whiteSpace: "nowrap" }}>{formatDateTime(d.createdAt)}</Table.Td>
              <Table.Td>{d.operation}</Table.Td>
              <Table.Td><Badge variant="light" color={d.status === "DELIVERED" ? "teal" : d.status === "PENDING" ? "blue" : "red"}>{d.status}</Badge>{d.lastError && <Text size="xs" c="var(--mantine-color-error)">{d.lastError}</Text>}</Table.Td>
              <Table.Td>{d.attempts}</Table.Td>
              <Table.Td><Button size="compact-xs" variant="subtle" onClick={() => onRaw({ kind: "delivery", id: d.id, title: `${d.operation} – gesendeter Inhalt` })}>XML</Button></Table.Td>
            </Table.Tr>
          ))}
        </Table.Tbody>
      </Table>
    </Table.ScrollContainer>
  );
}

/** One order: facts, permitted actions (only from `allowedActions`), history with raw XML, outbound deliveries. */
export function OrderDetailPage() {
  const id = Number(useParams().id);
  const navigate = useNavigate();
  const scopes = useScopes();
  const partners = usePartnerNames();
  const [action, setAction] = useState<Action | null>(null);
  const [raw, setRaw] = useState<RawSource | null>(null);
  const detail = useQuery({ queryKey: ["order", id], queryFn: () => call(api.GET("/api/v1/orders/{id}", { params: { path: { id } } })), enabled: Number.isFinite(id) });

  if (detail.isPending) return <Loading />;
  if (detail.isError) return <Stack><Title order={2}>Auftrag</Title><ErrorAlert error={detail.error} /></Stack>;
  const { order, allowedActions } = detail.data;
  const draft = order.phase === "DRAFT";
  const actions = allowedActions.filter((a) => a.kind !== "SEND" && hasScope(scopes, actionScope(order, a)));

  return (
    <Stack>
      <Anchor component={Link} to={order.role === "LEISTUNGSERBRINGER" ? "/work-queue" : "/orders"} size="sm">← zur Liste</Anchor>
      <Group justify="space-between" align="flex-start">
        <Stack gap={4}>
          <Title order={2}>Auftrag <span className="mono">{order.externalOrderNumber}</span></Title>
          <Group gap="xs">
            <PhaseBadge phase={order.phase} />
            {order.cancelled && <Badge color="red" variant="outline">storniert</Badge>}
            {order.openRequests.map((r) => <Badge key={r} color="orange" variant="outline">{REQUEST_LABELS[r] ?? r} offen</Badge>)}
          </Group>
        </Stack>
      </Group>

      {!draft && (
        <Card withBorder component="section" aria-labelledby="actions-title">
          <Title order={3} size="h5" id="actions-title" mb="xs">Nächste Schritte</Title>
          {actions.length === 0 ? (
            <Text size="sm" c="dimmed">{order.finished ? "Der Auftrag ist abgeschlossen." : "Im Moment ist von unserer Seite nichts zu tun; wir warten auf den Partner."}</Text>
          ) : (
            <Group gap="xs">
              {actions.map((a) => (
                <Button key={`${a.kind}-${a.messageType}-${a.changeIndicator}-${a.request}`} variant={a.request === "STORNO" ? "light" : "filled"} color={a.request === "STORNO" ? "red" : undefined} onClick={() => setAction(a)}>
                  {actionLabel(a)}
                </Button>
              ))}
            </Group>
          )}
        </Card>
      )}

      {draft && hasScope(scopes, Scope.ordersWrite) && <DraftPanel order={order} onDeleted={() => navigate("/orders")} />}

      <SimpleGrid cols={{ base: 1, lg: 2 }}>
        <Card withBorder component="section" aria-labelledby="facts-title">
          <Title order={3} size="h5" id="facts-title" mb="xs">Daten</Title>
          <Facts order={order} partner={partners.get(order.partnerId) ?? `#${order.partnerId}`} />
        </Card>
        <Card withBorder component="section" aria-labelledby="history-title">
          <Title order={3} size="h5" id="history-title" mb="xs">Verlauf</Title>
          <History orderId={order.id} onRaw={setRaw} />
        </Card>
      </SimpleGrid>

      {hasScope(scopes, Scope.deliveriesRead) && (
        <Card withBorder component="section" aria-labelledby="deliveries-title">
          <Title order={3} size="h5" id="deliveries-title" mb="xs">Ausgehende Nachrichten</Title>
          <Deliveries orderId={order.id} onRaw={setRaw} />
        </Card>
      )}

      <Modal opened={action !== null} onClose={() => setAction(null)} title={action ? actionLabel(action) : ""} size="lg">
        {action && <ActionForm order={order} action={action} onDone={() => setAction(null)} />}
      </Modal>
      <RawXmlModal source={raw} onClose={() => setRaw(null)} />
    </Stack>
  );
}
