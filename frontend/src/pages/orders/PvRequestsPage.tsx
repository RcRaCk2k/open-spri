import { Anchor, Badge, Button, Group, Modal, Stack, Switch, Table, Text, Title } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link } from "react-router";
import { api, call } from "../../api/client";
import { hasScope, Scope } from "../../auth/scopes";
import { ErrorAlert, Loading } from "../../components/Feedback";
import { daysUntil, formatDate } from "../../format";
import { usePartnerNames, useScopes } from "../../hooks";
import { HANDOVER_LABELS } from "./labels";
import { PvReplyForm } from "./PvReplyForm";

/** Provider change requests (AKM-PV) we received as abgebender EKP; the RUEM-PV is due by the reply deadline. */
export function PvRequestsPage() {
  const [open, setOpen] = useState(true);
  const [replying, setReplying] = useState<number | null>(null);
  const scopes = useScopes();
  const partners = usePartnerNames();
  const requests = useQuery({ queryKey: ["pv-requests", { open }], queryFn: () => call(api.GET("/api/v1/pv-requests", { params: { query: { open, limit: 100 } } })) });
  const rows = [...(requests.data ?? [])].sort((a, b) => (a.replyDeadline ?? "9999").localeCompare(b.replyDeadline ?? "9999"));

  return (
    <Stack>
      <Title order={2}>Providerwechsel-Anfragen</Title>
      <Text c="dimmed">Ein anderer Provider übernimmt einen unserer Kunden. Bis zur Antwortfrist muss die RUEM-PV gesendet sein.</Text>
      <Switch label="nur unbeantwortete" checked={open} onChange={(e) => setOpen(e.currentTarget.checked)} />
      <ErrorAlert error={requests.error} />
      {requests.isPending ? <Loading /> : rows.length === 0 ? <Text c="dimmed">Keine Anfragen.</Text> : (
        <Table.ScrollContainer minWidth={800}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Auftrag</Table.Th><Table.Th>Partner (LE)</Table.Th><Table.Th>Neuer Provider</Table.Th><Table.Th>LineID</Table.Th><Table.Th>Übernahme geplant</Table.Th><Table.Th>Antwortfrist</Table.Th><Table.Th>Stand</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {rows.map((r) => {
                const days = r.replyDeadline ? daysUntil(r.replyDeadline) : null;
                return (
                  <Table.Tr key={r.order.id}>
                    <Table.Td><Anchor component={Link} to={`/orders/${r.order.id}`} className="mono">{r.order.externalOrderNumber}</Anchor></Table.Td>
                    <Table.Td>{partners.get(r.order.partnerId) ?? `#${r.order.partnerId}`}</Table.Td>
                    <Table.Td>{r.newProvider ?? "–"}</Table.Td>
                    <Table.Td className="mono">{r.lineId ?? ""}</Table.Td>
                    <Table.Td>{formatDate(r.plannedTransferDate)}</Table.Td>
                    <Table.Td>
                      {r.replyDeadline ? formatDate(r.replyDeadline) : "–"}
                      {r.replyDue && days !== null && days <= 2 && <Badge ml={6} color={days < 0 ? "red" : "orange"} variant="light">{days < 0 ? "überfällig" : days === 0 ? "heute" : `${days} Tag(e)`}</Badge>}
                    </Table.Td>
                    <Table.Td>{r.order.handoverPhase ? HANDOVER_LABELS[r.order.handoverPhase] ?? r.order.handoverPhase : ""}</Table.Td>
                    <Table.Td>
                      {r.replyDue && hasScope(scopes, Scope.ordersWrite) && <Button size="xs" onClick={() => setReplying(r.order.id)}>Beantworten</Button>}
                    </Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Modal opened={replying !== null} onClose={() => setReplying(null)} title="RUEM-PV senden" size="lg">
        {replying !== null && <PvReplyForm orderId={replying} onDone={() => setReplying(null)} />}
      </Modal>
      <Group />
    </Stack>
  );
}
