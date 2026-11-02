import { Button, Group, MultiSelect, Paper, Select, SimpleGrid, Stack, Text, TextInput, Title } from "@mantine/core";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { useNavigate, useSearchParams } from "react-router";
import { z } from "zod";
import { api, call } from "../../api/client";
import { ErrorAlert } from "../../components/Feedback";
import { newIdempotencyKey } from "../../hooks";
import { useCodeOptions } from "./ActionDialogs";

const schema = z.object({
  partnerId: z.string().min(1, "Partner wählen"),
  externalOrderNumber: z.string().trim().min(1, "Nummer vergeben"),
  contractNumber: z.string().trim().min(1, "Vertragsnummer angeben"),
  clientNumber: z.string().trim().min(1, "Auftraggebernummer angeben"),
  shutdownDate: z.string().min(1, "Abschalttermin angeben"),
  reason: z.string().trim(),
  lineId: z.string().trim(),
  codes: z.array(z.string()).min(1, "mindestens einen Meldungscode wählen"),
});
type Form = z.infer<typeof schema>;

/** Termination by us as Leistungserbringer (KUE-LE): sends the KDLE; prefilled from the inventory via `?lineId=…&contractNumber=…`. */
export function TerminationPage() {
  const [params] = useSearchParams();
  const navigate = useNavigate();
  const client = useQueryClient();
  const [key] = useState(newIdempotencyKey);
  const partners = useQuery({ queryKey: ["partners"], queryFn: () => call(api.GET("/api/v1/partners")) });
  const { options, error: codeError } = useCodeOptions("KDLE", "KUE-LE");
  const form = useForm<Form>({
    resolver: zodResolver(schema),
    defaultValues: {
      partnerId: params.get("partnerId") ?? "", externalOrderNumber: "", contractNumber: params.get("contractNumber") ?? "",
      clientNumber: params.get("clientNumber") ?? "", shutdownDate: "", reason: "", lineId: params.get("lineId") ?? "", codes: [],
    },
  });
  const send = useMutation({
    mutationFn: (v: Form) => call(api.POST("/api/v1/terminations", {
      headers: { "Idempotency-Key": key },
      body: {
        partnerId: Number(v.partnerId), externalOrderNumber: v.externalOrderNumber, contractNumber: v.contractNumber, clientNumber: v.clientNumber,
        shutdownDate: v.shutdownDate, reason: v.reason || null, lineId: v.lineId || null, codes: v.codes.map((code) => ({ code })),
      },
    })),
    onSuccess: async (detail) => {
      await client.invalidateQueries({ queryKey: ["orders"] });
      navigate(`/orders/${detail.order.id}`);
    },
  });
  const { errors } = form.formState;
  return (
    <Stack maw={720}>
      <Title order={2}>Kündigung durch uns (KDLE)</Title>
      <Text c="dimmed">Wir kündigen als Leistungserbringer eine Leitung des Partners. open-spri legt den Auftrag KUE-LE an und sendet die KDLE; ERLM und ENTM folgen im Auftrag.</Text>
      <Paper withBorder p="md">
        <form onSubmit={form.handleSubmit((v) => send.mutate(v))} noValidate>
          <Stack>
            <Controller control={form.control} name="partnerId" render={({ field }) => (
              <Select label="Partner (Auftraggeber der Leitung)" required searchable data={(partners.data ?? []).map((p) => ({ value: String(p.id), label: `${p.name} (${p.carrierCode})` }))}
                value={field.value || null} onChange={(v) => field.onChange(v ?? "")} error={errors.partnerId?.message} />
            )} />
            <SimpleGrid cols={{ base: 1, sm: 2 }}>
              <TextInput label="Externe Auftragsnummer" description="von uns vergeben, je Partner eindeutig" required className="mono" {...form.register("externalOrderNumber")} error={errors.externalOrderNumber?.message} />
              <TextInput label="Abschalttermin" type="date" required {...form.register("shutdownDate")} error={errors.shutdownDate?.message} />
              <TextInput label="Vertragsnummer" required {...form.register("contractNumber")} error={errors.contractNumber?.message} />
              <TextInput label="Auftraggebernummer" required {...form.register("clientNumber")} error={errors.clientNumber?.message} />
              <TextInput label="LineID" description="nicht Teil der KDLE, ordnet den Postausgang zu" {...form.register("lineId")} />
              <TextInput label="Abschaltgrund" {...form.register("reason")} />
            </SimpleGrid>
            <ErrorAlert error={codeError} />
            <Controller control={form.control} name="codes" render={({ field }) => (
              <MultiSelect label="Meldungscodes" required searchable data={options} value={field.value} onChange={field.onChange} error={errors.codes?.message} />
            )} />
            <ErrorAlert error={send.error} title="KDLE wurde nicht gesendet" />
            <Group justify="flex-end"><Button type="submit" loading={send.isPending}>KDLE senden</Button></Group>
          </Stack>
        </form>
      </Paper>
    </Stack>
  );
}
