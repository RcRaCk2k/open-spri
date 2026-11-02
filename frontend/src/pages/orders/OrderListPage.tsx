import { Anchor, Badge, Button, Group, Select, Stack, Switch, Table, Text, TextInput, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useInfiniteQuery } from "@tanstack/react-query";
import { Link, useSearchParams } from "react-router";
import { api, call } from "../../api/client";
import { ErrorAlert, Loading } from "../../components/Feedback";
import { PhaseBadge } from "../../components/OrderBadges";
import { formatDate, formatDateTime } from "../../format";
import { usePartnerNames } from "../../hooks";
import { BUSINESS_CASES, PHASE_LABELS, REQUEST_LABELS, ROLE_NAMES } from "./labels";

const PAGE = 50;

/**
 * Orders in all roles (`/orders`) and the work queue of the Leistungserbringer (`/work-queue`: role LE, only open).
 * The filters are kept in the URL, so a filtered list can be bookmarked.
 */
export function OrderListPage({ workQueue = false }: { workQueue?: boolean }) {
  const [params, setParams] = useSearchParams();
  const role = workQueue ? "LEISTUNGSERBRINGER" : params.get("role") ?? "";
  const filter = {
    role,
    businessCase: params.get("businessCase") ?? "",
    phase: params.get("phase") ?? "",
    q: params.get("q") ?? "",
    open: workQueue ? true : params.get("open") !== "false",
  };
  const [q] = useDebouncedValue(filter.q, 300);
  const partners = usePartnerNames();
  const query = useInfiniteQuery({
    queryKey: ["orders", { ...filter, q }],
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) => call(api.GET("/api/v1/orders", {
      params: { query: { role: filter.role || undefined, businessCase: filter.businessCase || undefined, phase: filter.phase || undefined, q: q || undefined, open: filter.open, cursor: pageParam, limit: PAGE } },
    })),
    getNextPageParam: (last) => (last.length === PAGE ? Math.min(...last.map((o) => o.id)) : undefined),
  });
  const set = (key: string, value: string | null) => setParams((p) => {
    const next = new URLSearchParams(p);
    if (value) next.set(key, value); else next.delete(key);
    return next;
  }, { replace: true });
  const orders = query.data?.pages.flat() ?? [];

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>{workQueue ? "Arbeitsvorrat Leistungserbringer" : "Aufträge"}</Title>
        {workQueue && <Button component={Link} to="/terminations/new" variant="default">Kündigung durch uns (KDLE)</Button>}
      </Group>
      {workQueue && <Text c="dimmed">Offene Aufträge unserer Partner an uns. Im Auftrag stehen die Meldungen, die jetzt zulässig sind.</Text>}
      <Group align="flex-end" role="search">
        <TextInput label="Suche" description="Auftragsnummer, LineID, Vertragsnummer (Anfang)" value={filter.q} onChange={(e) => set("q", e.currentTarget.value)} w={260} />
        {!workQueue && (
          <Select label="Rolle" data={Object.entries(ROLE_NAMES).map(([value, label]) => ({ value, label }))} value={filter.role || null} onChange={(v) => set("role", v)} clearable placeholder="alle" w={190} />
        )}
        <Select label="Geschäftsfall" data={[...BUSINESS_CASES]} value={filter.businessCase || null} onChange={(v) => set("businessCase", v)} clearable placeholder="alle" w={150} />
        <Select label="Phase" data={Object.entries(PHASE_LABELS).map(([value, label]) => ({ value, label }))} value={filter.phase || null} onChange={(v) => set("phase", v)} clearable placeholder="alle" w={230} />
        {!workQueue && <Switch label="nur offene" checked={filter.open} onChange={(e) => set("open", e.currentTarget.checked ? null : "false")} mb={8} />}
      </Group>
      <ErrorAlert error={query.error} />
      {query.isPending ? <Loading /> : orders.length === 0 ? <Text c="dimmed">Keine Aufträge gefunden.</Text> : (
        <Table.ScrollContainer minWidth={900}>
          <Table striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Auftragsnummer</Table.Th>
                <Table.Th>GF</Table.Th>
                {!workQueue && <Table.Th>Rolle</Table.Th>}
                <Table.Th>Partner</Table.Th>
                <Table.Th>Phase</Table.Th>
                <Table.Th>Wunschtermin</Table.Th>
                <Table.Th>Produkte</Table.Th>
                <Table.Th>Geändert</Table.Th>
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {orders.map((o) => (
                <Table.Tr key={o.id}>
                  <Table.Td><Anchor component={Link} to={`/orders/${o.id}`} className="mono">{o.externalOrderNumber}</Anchor></Table.Td>
                  <Table.Td>{o.businessCase}</Table.Td>
                  {!workQueue && <Table.Td>{ROLE_NAMES[o.role] ?? o.role}</Table.Td>}
                  <Table.Td>{partners.get(o.partnerId) ?? `#${o.partnerId}`}</Table.Td>
                  <Table.Td>
                    <Group gap={4}>
                      <PhaseBadge phase={o.phase} />
                      {o.openRequests.map((r) => <Badge key={r} color="orange" variant="outline">{REQUEST_LABELS[r] ?? r}</Badge>)}
                    </Group>
                  </Table.Td>
                  <Table.Td>{formatDate(o.desiredDate)}</Table.Td>
                  <Table.Td><Text size="sm" lineClamp={2}>{o.products.join(", ")}</Text></Table.Td>
                  <Table.Td style={{ whiteSpace: "nowrap" }}>{formatDateTime(o.updatedAt)}</Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {query.hasNextPage && (
        <Group justify="center"><Button variant="default" onClick={() => query.fetchNextPage()} loading={query.isFetchingNextPage}>Weitere laden</Button></Group>
      )}
    </Stack>
  );
}
