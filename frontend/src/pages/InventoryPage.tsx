import { Anchor, Badge, Button, Group, Menu, Select, Stack, Switch, Table, Text, TextInput, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { Link, useNavigate } from "react-router";
import { api, call, type Schemas } from "../api/client";
import { hasScope, Scope } from "../auth/scopes";
import { ErrorAlert, Loading } from "../components/Feedback";
import { formatDate } from "../format";
import { usePartnerNames, useScopes } from "../hooks";
import { fromContract } from "./orders/orderForm";
import type { NewOrderState } from "./orders/NewOrderPage";

type Contract = Schemas["InventoryContractDto"];
const PAGE = 50;

/** Orders on an existing line of the partner (we are Auftraggeber). */
const LINE_ORDERS = [
  { case: "KUE-AG", label: "Kündigen (KUE-AG)" },
  { case: "LAE", label: "Produkt wechseln (LAE)" },
  { case: "AEN-LMAE", label: "Leistungsmerkmale ändern (AEN-LMAE)" },
  { case: "EST", label: "Störung melden (EST)" },
  { case: "DIAG-GET", label: "Diagnose abfragen (DIAG-GET)" },
  { case: "DIAG-SET", label: "Diagnose ausführen (DIAG-SET)" },
];

/** Inventory (AUFGABE 8.6): contracts from completed orders, starting point for orders on an existing line. */
export function InventoryPage() {
  const scopes = useScopes();
  const partners = usePartnerNames();
  const navigate = useNavigate();
  const [role, setRole] = useState<string | null>(null);
  const [lineId, setLineId] = useState("");
  const [active, setActive] = useState(true);
  const [line] = useDebouncedValue(lineId, 300);
  const query = useInfiniteQuery({
    queryKey: ["inventory", { role, line, active }],
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => call(api.GET("/api/v1/inventory", { params: { query: { role: role ?? undefined, lineId: line || undefined, active, cursor: pageParam, limit: PAGE } } })),
    getNextPageParam: (last) => (last.length === PAGE ? Math.min(...last.map((c) => c.id)) : undefined),
  });
  const contracts = query.data?.pages.flat() ?? [];
  const order = (c: Contract, businessCase: string) => {
    const state: NewOrderState = { prefill: { ...fromContract(c), businessCase }, source: `Leitung ${c.lineId} (Vertrag ${c.contractNumber}) aus dem Bestand` };
    navigate("/orders/new", { state });
  };
  const terminate = (c: Contract) => navigate(`/terminations/new?${new URLSearchParams({
    partnerId: String(c.partnerId), contractNumber: c.contractNumber, clientNumber: c.clientNumber ?? "", lineId: c.lineId,
  })}`);

  return (
    <Stack>
      <Title order={2}>Bestand</Title>
      <Text c="dimmed">Verträge aus abgeschlossenen Aufträgen: Leitungen, die wir bei Partnern nutzen, und Leitungen, die Partner bei uns nutzen.</Text>
      <Group align="flex-end" role="search">
        <TextInput label="LineID" value={lineId} onChange={(e) => setLineId(e.currentTarget.value)} w={260} />
        <Select label="Unsere Rolle" data={[{ value: "AUFTRAGGEBER", label: "Auftraggeber (Leitung des Partners)" }, { value: "LEISTUNGSERBRINGER", label: "Leistungserbringer (unsere Leitung)" }]}
          value={role} onChange={setRole} clearable placeholder="alle" w={300} />
        <Switch label="nur aktive" checked={active} onChange={(e) => setActive(e.currentTarget.checked)} mb={8} />
      </Group>
      <ErrorAlert error={query.error} />
      {query.isPending ? <Loading /> : contracts.length === 0 ? <Text c="dimmed">Keine Verträge.</Text> : (
        <Table.ScrollContainer minWidth={900}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>LineID</Table.Th><Table.Th>Vertrag</Table.Th><Table.Th>Partner</Table.Th><Table.Th>Rolle</Table.Th><Table.Th>Produkte</Table.Th><Table.Th>Laufzeit</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {contracts.map((c) => (
                <Table.Tr key={c.id}>
                  <Table.Td className="mono">{c.lineId}</Table.Td>
                  <Table.Td className="mono">{c.orderId ? <Anchor component={Link} to={`/orders/${c.orderId}`}>{c.contractNumber}</Anchor> : c.contractNumber}</Table.Td>
                  <Table.Td>{partners.get(c.partnerId) ?? `#${c.partnerId}`}</Table.Td>
                  <Table.Td>{c.role === "AUFTRAGGEBER" ? "AG" : "LE"}</Table.Td>
                  <Table.Td>{c.products.join(", ")}</Table.Td>
                  <Table.Td>{formatDate(c.startedAt)} – {c.endedAt ? formatDate(c.endedAt) : <Badge size="sm" variant="light" color="teal">aktiv</Badge>}</Table.Td>
                  <Table.Td>
                    {!c.endedAt && c.role === "AUFTRAGGEBER" && hasScope(scopes, Scope.ordersWrite) && (
                      <Menu position="bottom-end">
                        <Menu.Target><Button size="xs" variant="default" aria-label={`Auftrag zu ${c.lineId}`}>Auftrag …</Button></Menu.Target>
                        <Menu.Dropdown>{LINE_ORDERS.map((o) => <Menu.Item key={o.case} onClick={() => order(c, o.case)}>{o.label}</Menu.Item>)}</Menu.Dropdown>
                      </Menu>
                    )}
                    {!c.endedAt && c.role === "LEISTUNGSERBRINGER" && hasScope(scopes, Scope.leMessagesWrite) && (
                      <Button size="xs" variant="default" onClick={() => terminate(c)}>Kündigen (KDLE)</Button>
                    )}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {query.hasNextPage && <Group justify="center"><Button variant="default" onClick={() => query.fetchNextPage()} loading={query.isFetchingNextPage}>Weitere laden</Button></Group>}
    </Stack>
  );
}
