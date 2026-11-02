import { Anchor, Badge, Card, Group, SimpleGrid, Stack, Table, Text, Title } from "@mantine/core";
import { useQueries, useQuery } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { api, call, type Schemas } from "../api/client";
import { hasScope, Scope } from "../auth/scopes";
import { useMe } from "../auth/session";
import { ErrorAlert, Loading } from "../components/Feedback";
import { daysUntil, describeBuild, formatDate, formatDateTime } from "../format";

/** Delivery states that need someone to look at them (docs/betrieb.md, Zustellmonitor). */
export const DELIVERY_PROBLEMS = ["UNDELIVERABLE", "REJECTED", "UNCLEAR", "DELIVERED_WITH_ERROR"] as const;

const ROLE_SHORT: Record<string, string> = { AUFTRAGGEBER: "AG", LEISTUNGSERBRINGER: "LE", ABGEBENDER_EKP: "EKPab" };

function Panel({ title, count, children, testId }: { title: string; count?: number | string; children: ReactNode; testId: string }) {
  return (
    <Card withBorder padding="md" component="section" aria-labelledby={`${testId}-title`} data-testid={testId}>
      <Group justify="space-between" mb="sm" wrap="nowrap">
        <Title order={2} size="h4" id={`${testId}-title`}>{title}</Title>
        {count !== undefined && <Badge size="lg" variant="light" aria-label={`Anzahl: ${count}`}>{count}</Badge>}
      </Group>
      {children}
    </Card>
  );
}

function DeadlineBadge({ date }: { date: string | null | undefined }) {
  if (!date) return <Text size="sm" c="dimmed">ohne Frist</Text>;
  const days = daysUntil(date);
  const color = days < 0 ? "red" : days <= 2 ? "orange" : "gray";
  const text = days < 0 ? `überfällig seit ${-days} Tag(en)` : days === 0 ? "heute" : `in ${days} Tag(en)`;
  return <Badge color={color} variant="light">{formatDate(date)} · {text}</Badge>;
}

/** Answers to the PV requests (AKM-PV) we have to send as abgebender EKP, earliest deadline first. */
function PvDeadlines() {
  const query = useQuery({ queryKey: ["pv-requests", { open: true }], queryFn: () => call(api.GET("/api/v1/pv-requests", { params: { query: { open: true, limit: 100 } } })) });
  if (query.isPending) return <Panel title="Providerwechsel: RUEM-PV fällig" testId="pv"><Loading /></Panel>;
  if (query.isError) return <Panel title="Providerwechsel: RUEM-PV fällig" testId="pv"><ErrorAlert error={query.error} /></Panel>;
  const due = query.data.filter((r) => r.replyDue).sort((a, b) => (a.replyDeadline ?? "9999").localeCompare(b.replyDeadline ?? "9999"));
  return (
    <Panel title="Providerwechsel: RUEM-PV fällig" count={due.length} testId="pv">
      {due.length === 0 ? <Text size="sm" c="dimmed">Keine offenen Anfragen.</Text> : (
        <Table>
          <Table.Thead><Table.Tr><Table.Th>Auftrag</Table.Th><Table.Th>Neuer Anbieter</Table.Th><Table.Th>Antwortfrist</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            {due.slice(0, 8).map((r) => (
              <Table.Tr key={r.order.id}>
                <Table.Td className="mono">{r.order.externalOrderNumber}</Table.Td>
                <Table.Td>{r.newProvider ?? "–"}</Table.Td>
                <Table.Td><DeadlineBadge date={r.replyDeadline} /></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Panel>
  );
}

/** Orders waiting for a new appointment (TAM/MTAM, R-08). As AG we must answer with a TV; as LE we wait for it. */
function AppointmentRequests() {
  const query = useQuery({
    queryKey: ["orders", { phase: "APPOINTMENT_REQUESTED", open: true }],
    queryFn: () => call(api.GET("/api/v1/orders", { params: { query: { phase: "APPOINTMENT_REQUESTED", open: true, limit: 100 } } })),
  });
  const title = "Terminabstimmung offen (TAM/MTAM)";
  if (query.isPending) return <Panel title={title} testId="tam"><Loading /></Panel>;
  if (query.isError) return <Panel title={title} testId="tam"><ErrorAlert error={query.error} /></Panel>;
  const orders = [...query.data].sort((a, b) => a.updatedAt.localeCompare(b.updatedAt));
  return (
    <Panel title={title} count={orders.length} testId="tam">
      {orders.length === 0 ? <Text size="sm" c="dimmed">Keine offenen Terminanfragen.</Text> : (
        <Table>
          <Table.Thead><Table.Tr><Table.Th>Auftrag</Table.Th><Table.Th>Rolle</Table.Th><Table.Th>seit</Table.Th><Table.Th>Nächster Schritt</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            {orders.slice(0, 8).map((o) => (
              <Table.Tr key={o.id}>
                <Table.Td className="mono">{o.externalOrderNumber}</Table.Td>
                <Table.Td>{ROLE_SHORT[o.role] ?? o.role}</Table.Td>
                <Table.Td>{formatDateTime(o.updatedAt)}</Table.Td>
                <Table.Td>{o.role === "AUFTRAGGEBER" ? "Terminverschiebung senden" : "auf TV warten, sonst MTAM"}</Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Panel>
  );
}

/** Open orders per role: the work queue of the Leistungserbringer and our own orders. */
function OpenOrders() {
  const roles = ["LEISTUNGSERBRINGER", "AUFTRAGGEBER"] as const;
  const results = useQueries({
    queries: roles.map((role) => ({
      queryKey: ["orders", { role, open: true }],
      queryFn: () => call(api.GET("/api/v1/orders", { params: { query: { role, open: true, limit: 100 } } })),
    })),
  });
  const count = (list: Schemas["OrderDto"][] | undefined) => (list === undefined ? "…" : list.length >= 100 ? "100+" : String(list.length));
  const error = results.find((r) => r.isError)?.error;
  return (
    <Panel title="Offene Aufträge" testId="open-orders">
      {error ? <ErrorAlert error={error} /> : (
        <SimpleGrid cols={2}>
          <Stack gap={0}>
            <Text size="xl" fw={700} data-testid="open-le">{count(results[0]?.data)}</Text>
            <Text size="sm" c="dimmed">Arbeitsvorrat Leistungserbringer</Text>
          </Stack>
          <Stack gap={0}>
            <Text size="xl" fw={700} data-testid="open-ag">{count(results[1]?.data)}</Text>
            <Text size="sm" c="dimmed">eigene Aufträge als Auftraggeber</Text>
          </Stack>
        </SimpleGrid>
      )}
    </Panel>
  );
}

/** Outbound messages that were not delivered or not accepted. */
function DeliveryProblems() {
  const results = useQueries({
    queries: DELIVERY_PROBLEMS.map((status) => ({
      queryKey: ["deliveries", { status }],
      queryFn: () => call(api.GET("/api/v1/deliveries", { params: { query: { status, limit: 50 } } })),
    })),
  });
  const title = "Zustellfehler";
  if (results.some((r) => r.isPending)) return <Panel title={title} testId="deliveries"><Loading /></Panel>;
  const error = results.find((r) => r.isError)?.error;
  if (error) return <Panel title={title} testId="deliveries"><ErrorAlert error={error} /></Panel>;
  const problems = results.flatMap((r) => r.data ?? []).sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  return (
    <Panel title={title} count={problems.length} testId="deliveries">
      {problems.length === 0 ? <Text size="sm" c="dimmed">Alle Nachrichten wurden zugestellt.</Text> : (
        <Table>
          <Table.Thead><Table.Tr><Table.Th>Auftrag</Table.Th><Table.Th>Status</Table.Th><Table.Th>Fehler</Table.Th></Table.Tr></Table.Thead>
          <Table.Tbody>
            {problems.slice(0, 8).map((d) => (
              <Table.Tr key={d.id}>
                <Table.Td className="mono">{d.externalOrderNumber ?? `#${d.id}`}</Table.Td>
                <Table.Td><Badge color="red" variant="light">{d.status}</Badge></Table.Td>
                <Table.Td><Text size="sm" lineClamp={2}>{d.lastError ?? ""}</Text></Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Panel>
  );
}

/** Dashboard (AUFGABE 8.6): open tasks, deadlines TAM/MTAM/AKM-PV, delivery errors. */
export function DashboardPage() {
  const me = useMe();
  const scopes = me.data?.scopes ?? [];
  const about = useQuery({ queryKey: ["about"], queryFn: () => call(api.GET("/api/v1/about")), staleTime: Infinity });
  const orders = hasScope(scopes, Scope.ordersRead);
  return (
    <Stack>
      <Group justify="space-between" align="baseline">
        <Title order={2}>Dashboard</Title>
        {about.data && <Text size="sm" c="dimmed">{describeBuild(about.data)}</Text>}
      </Group>
      <SimpleGrid cols={{ base: 1, lg: 2 }}>
        {orders && <OpenOrders />}
        {hasScope(scopes, Scope.deliveriesRead) && <DeliveryProblems />}
        {orders && <AppointmentRequests />}
        {orders && <PvDeadlines />}
      </SimpleGrid>
      {!orders && !hasScope(scopes, Scope.deliveriesRead) && (
        <Text c="dimmed">Mit Ihren Berechtigungen gibt es hier nichts anzuzeigen. Die <Anchor href="/api/docs">REST-API-Dokumentation</Anchor> beschreibt alle Funktionen.</Text>
      )}
    </Stack>
  );
}
