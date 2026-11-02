import { Button, Code, CopyButton, Group, Modal, ScrollArea, Stack, Text } from "@mantine/core";
import { useQuery } from "@tanstack/react-query";
import { api, call } from "../../api/client";
import { ErrorAlert, Loading } from "../../components/Feedback";

/** Pretty-prints XML for reading; the downloaded original stays byte-exact. */
export function indentXml(xml: string): string {
  const tokens = xml.replace(/>\s+</g, "><").replace(/></g, ">\n<").split("\n");
  let depth = 0;
  return tokens.map((line) => {
    if (/^<\//.test(line)) depth = Math.max(0, depth - 1);
    const out = "  ".repeat(depth) + line;
    if (/^<[^!?/][^>]*>$/.test(line) && !line.endsWith("/>")) depth++;
    return out;
  }).join("\n");
}

export type RawSource = { kind: "archive"; id: number; title: string } | { kind: "delivery"; id: number; title: string };

/**
 * Raw message: inbound from the archive (SOAP envelope as received), outbound the body as signed and sent.
 * Both contain end customer data; the API records every access in the audit log.
 */
export function RawXmlModal({ source, onClose }: { source: RawSource | null; onClose: () => void }) {
  const query = useQuery({
    queryKey: ["raw", source],
    enabled: source !== null,
    queryFn: async () => {
      if (!source) return "";
      const request = source.kind === "archive"
        ? api.GET("/api/v1/messages/{id}/raw", { params: { path: { id: source.id } }, parseAs: "text" })
        : api.GET("/api/v1/deliveries/{id}/payload", { params: { path: { id: source.id } }, parseAs: "text" });
      return (await call(request)) as unknown as string;
    },
    staleTime: Infinity,
  });
  return (
    <Modal opened={source !== null} onClose={onClose} title={source?.title ?? ""} size="xl">
      <Stack>
        <Text size="sm" c="dimmed">Enthält Endkundendaten. Der Abruf steht im Audit-Log.</Text>
        <ErrorAlert error={query.error} />
        {query.isPending ? <Loading /> : query.data !== undefined && (
          <>
            <ScrollArea.Autosize mah="60vh">
              <Code block data-testid="raw-xml">{indentXml(query.data)}</Code>
            </ScrollArea.Autosize>
            <Group justify="flex-end">
              <CopyButton value={query.data}>{({ copied, copy }) => <Button variant="default" onClick={copy}>{copied ? "Kopiert" : "Original kopieren"}</Button>}</CopyButton>
            </Group>
          </>
        )}
      </Stack>
    </Modal>
  );
}
