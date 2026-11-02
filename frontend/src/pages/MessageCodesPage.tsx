import { Alert, Badge, Button, FileButton, Group, Select, Stack, Table, Text, TextInput, Title } from "@mantine/core";
import { useDebouncedValue } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, call } from "../api/client";
import { hasScope, Scope } from "../auth/scopes";
import { ErrorAlert, Loading } from "../components/Feedback";
import { rawBody } from "../components/files";
import { useScopes } from "../hooks";

/**
 * The message code table (Anlage 5). It is not part of the repository (copyright of the AK): the operator imports
 * the file exported from the AK Confluence. Local additions are marked (docs/spec-abweichungen.md).
 */
export function MessageCodesPage() {
  const client = useQueryClient();
  const scopes = useScopes();
  const [type, setType] = useState<string | null>(null);
  const [text, setText] = useState("");
  const [search] = useDebouncedValue(text.trim().toLowerCase(), 200);
  const table = useQuery({ queryKey: ["message-codes", "all"], queryFn: () => call(api.GET("/api/v1/message-codes")), staleTime: 300_000 });
  const importFile = useMutation({
    mutationFn: async (file: File) => call(api.POST("/api/v1/message-codes/import", {
      params: { query: { filename: file.name } }, body: (await file.arrayBuffer()) as never, headers: { "Content-Type": "application/octet-stream" }, ...rawBody,
    })),
    onSuccess: async (t) => {
      client.setQueryData(["message-codes", "all"], t);
      await client.invalidateQueries({ queryKey: ["message-codes"] });
      notifications.show({ color: "teal", message: `${t.codes.length} Meldungscodes aus ${t.source} aktiv` });
    },
  });
  const codes = (table.data?.codes ?? []).filter((c) => (!type || c.messageTypes.includes(type)) && (!search || c.code.includes(search) || c.text.toLowerCase().includes(search)));
  const types = [...new Set((table.data?.codes ?? []).flatMap((c) => c.messageTypes))].sort();

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Meldungscodes</Title>
        {hasScope(scopes, Scope.messageCodesAdmin) && (
          <FileButton accept=".csv,.tsv,.txt,text/csv,text/tab-separated-values" onChange={(f) => f && importFile.mutate(f)}>
            {(props) => <Button {...props} loading={importFile.isPending}>Tabelle importieren</Button>}
          </FileButton>
        )}
      </Group>
      <ErrorAlert error={table.error ?? importFile.error} />
      {table.data && (
        <Alert color="gray">
          Aktive Tabelle: <span className="mono">{table.data.source}</span> (SHA-256 <span className="mono">{table.data.sha256.slice(0, 16)}…</span>), {table.data.codes.length} Codes.
          Die Tabelle stammt aus dem Confluence des AK und wird als CSV- oder TSV-Export importiert.
        </Alert>
      )}
      <Group align="flex-end" role="search">
        <Select label="Meldungstyp" data={types} value={type} onChange={setType} clearable searchable placeholder="alle" w={200} />
        <TextInput label="Code oder Text" value={text} onChange={(e) => setText(e.currentTarget.value)} w={300} />
      </Group>
      {table.isPending ? <Loading /> : codes.length === 0 ? <Text c="dimmed">Keine Codes.</Text> : (
        <Table.ScrollContainer minWidth={800}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Code</Table.Th><Table.Th>Text</Table.Th><Table.Th>Meldungstypen</Table.Th><Table.Th>Geschäftsfälle</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {codes.map((c) => (
                <Table.Tr key={c.code}>
                  <Table.Td className="mono">{c.code}{c.localAddition && <Badge ml={6} size="sm" color="orange" variant="light" title="Ergänzung von open-spri, siehe docs/spec-abweichungen.md">lokal</Badge>}</Table.Td>
                  <Table.Td>{c.text}</Table.Td>
                  <Table.Td><Text size="sm">{c.messageTypes.join(", ")}</Text></Table.Td>
                  <Table.Td><Text size="sm">{c.businessCases.length === 0 ? "alle" : c.businessCases.join(", ")}</Text></Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
    </Stack>
  );
}
