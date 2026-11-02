import { Button, Group, MultiSelect, Radio, Stack, Text, TextInput } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, call } from "../../api/client";
import { ErrorAlert } from "../../components/Feedback";
import { newIdempotencyKey } from "../../hooks";
import { useCodeOptions } from "./ActionDialogs";

/** RUEM-PV: consent or refusal to a provider change (AKM-PV); numbers and `anschluss` come from the AKM-PV. */
export function PvReplyForm({ orderId, onDone }: { orderId: number; onDone: () => void }) {
  const client = useQueryClient();
  const [consent, setConsent] = useState<"J" | "N" | "">("");
  const [codes, setCodes] = useState<string[]>([]);
  const [providerName, setProviderName] = useState("");
  const [answerCode, setAnswerCode] = useState("");
  const [answerText, setAnswerText] = useState("");
  const [key] = useState(newIdempotencyKey);
  const { options, error } = useCodeOptions("RUEM-PV", "PV");
  const reply = useMutation({
    mutationFn: () => call(api.POST("/api/v1/pv-requests/{id}/reply", {
      params: { path: { id: orderId } },
      headers: { "Idempotency-Key": key },
      body: {
        consent: consent === "J", codes: codes.map((code) => ({ code })),
        providerName: providerName || null, answerCode: answerCode || null, answerText: answerText || null,
      },
    })),
    onSuccess: async (detail) => {
      client.setQueryData(["order", orderId], detail);
      await Promise.all(["pv-requests", "orders", "history", "order"].map((k) => client.invalidateQueries({ queryKey: [k] })));
      notifications.show({ color: "teal", message: "RUEM-PV in den Postausgang gelegt" });
      onDone();
    },
  });
  return (
    <Stack>
      <Text size="sm">Vertrags- und Auftragsnummern, VorabstimmungsID und Anschluss übernimmt open-spri aus der AKM-PV.</Text>
      <Radio.Group label="Zustimmung zum Providerwechsel" required value={consent} onChange={(v) => setConsent(v as "J" | "N")}>
        <Group mt={4}><Radio value="J" label="Zustimmung (J)" /><Radio value="N" label="Ablehnung (N)" /></Group>
      </Radio.Group>
      <ErrorAlert error={error} />
      <MultiSelect label="Meldungscodes" required searchable data={options} value={codes} onChange={setCodes} />
      <TextInput label="Providername (unser Name)" value={providerName} onChange={(e) => setProviderName(e.currentTarget.value)} />
      {consent === "N" && (
        <Group grow>
          <TextInput label="Antwortcode" value={answerCode} onChange={(e) => setAnswerCode(e.currentTarget.value)} />
          <TextInput label="Antworttext" value={answerText} onChange={(e) => setAnswerText(e.currentTarget.value)} />
        </Group>
      )}
      <ErrorAlert error={reply.error} title="RUEM-PV wurde nicht gesendet" />
      <Group justify="flex-end"><Button onClick={() => reply.mutate()} loading={reply.isPending} disabled={consent === "" || codes.length === 0}>RUEM-PV senden</Button></Group>
    </Stack>
  );
}
