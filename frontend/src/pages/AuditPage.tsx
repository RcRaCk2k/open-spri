import { Button, Group, Stack, Table, Text, TextInput, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { useInfiniteQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api, call } from "../api/client";
import { ErrorAlert, Loading } from "../components/Feedback";
import { formatDateTime } from "../format";

const PAGE = 50;

/** Audit log (NF-10): who changed what, newest first. */
export function AuditPage() {
  const [filter, setFilter] = useState({ actor: "", action: "", target: "" });
  const [debounced] = useDebouncedValue(filter, 300);
  const query = useInfiniteQuery({
    queryKey: ["audit", debounced],
    initialPageParam: undefined as number | undefined,
    queryFn: ({ pageParam }) =>
      call(api.GET("/api/v1/audit", {
        params: { query: { actor: debounced.actor || undefined, action: debounced.action || undefined, target: debounced.target || undefined, cursor: pageParam, limit: PAGE } },
      })),
    getNextPageParam: (last) => (last.length === PAGE ? last[last.length - 1]?.id : undefined),
  });
  const entries = query.data?.pages.flat() ?? [];
  const set = (key: keyof typeof filter) => (e: React.ChangeEvent<HTMLInputElement>) => {
    const value = e.currentTarget.value; // read now: currentTarget is null once the handler has returned
    setFilter((f) => ({ ...f, [key]: value }));
  };

  return (
    <Stack>
      <Title order={2}>Audit-Log</Title>
      <Group grow align="flex-end" role="search">
        <TextInput label="Akteur" placeholder="user:admin" value={filter.actor} onChange={set("actor")} />
        <TextInput label="Aktion (Anfang)" placeholder="order." value={filter.action} onChange={set("action")} />
        <TextInput label="Ziel" placeholder="partner:7" value={filter.target} onChange={set("target")} />
      </Group>
      <ErrorAlert error={query.error} />
      {query.isPending ? <Loading /> : (
        <Table.ScrollContainer minWidth={700}>
          <Table striped>
            <Table.Thead>
              <Table.Tr><Table.Th>Zeit</Table.Th><Table.Th>Akteur</Table.Th><Table.Th>Aktion</Table.Th><Table.Th>Ziel</Table.Th><Table.Th>Details</Table.Th></Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {entries.map((e) => (
                <Table.Tr key={e.id}>
                  <Table.Td style={{ whiteSpace: "nowrap" }}>{formatDateTime(e.at)}</Table.Td>
                  <Table.Td className="mono">{e.actor}</Table.Td>
                  <Table.Td className="mono">{e.action}</Table.Td>
                  <Table.Td className="mono">{e.target}</Table.Td>
                  <Table.Td><Text size="sm">{e.details ?? ""}</Text></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      {!query.isPending && entries.length === 0 && <Text c="dimmed">Keine Einträge.</Text>}
      {query.hasNextPage && (
        <Group justify="center">
          <Button variant="default" onClick={() => query.fetchNextPage()} loading={query.isFetchingNextPage}>Weitere laden</Button>
        </Group>
      )}
    </Stack>
  );
}
