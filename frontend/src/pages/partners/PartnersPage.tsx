import { Alert, Anchor, Badge, Button, Code, CopyButton, Group, Modal, Stack, Table, Text, Textarea, TextInput, Title } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { Link, useNavigate } from "react-router";
import { z } from "zod";
import { api, call } from "../../api/client";
import { hasScope, Scope } from "../../auth/scopes";
import { ErrorAlert, Loading } from "../../components/Feedback";
import { lines, saveFile } from "../../components/files";
import { useScopes } from "../../hooks";

const schema = z.object({
  name: z.string().trim().min(1, "Namen angeben"),
  carrierCode: z.string().trim().toUpperCase().regex(/^[A-Z0-9]{1,6}$/, "1 bis 6 Großbuchstaben oder Ziffern"),
  endpointUrl: z.union([z.literal(""), z.url({ protocol: /^https$/, message: "https-Adresse angeben" })]),
  technicalContactEmail: z.union([z.literal(""), z.email("gültige E-Mail-Adresse angeben")]),
  networks: z.string(),
  certificate: z.string(),
});
type Form = z.input<typeof schema>;

function CreatePartner({ onClose }: { onClose: () => void }) {
  const client = useQueryClient();
  const navigate = useNavigate();
  const form = useForm<Form>({ resolver: zodResolver(schema), defaultValues: { name: "", carrierCode: "", endpointUrl: "", technicalContactEmail: "", networks: "", certificate: "" } });
  const create = useMutation({
    mutationFn: (v: Form) => call(api.POST("/api/v1/partners", {
      body: {
        name: v.name.trim(), carrierCode: v.carrierCode.trim().toUpperCase(), endpointUrl: v.endpointUrl || null, technicalContactEmail: v.technicalContactEmail || null,
        allowedSourceNetworks: lines(v.networks), certificatesPem: v.certificate.trim() ? [v.certificate.trim()] : [],
      },
    })),
    onSuccess: async (p) => { await client.invalidateQueries({ queryKey: ["partners"] }); onClose(); navigate(`/partners/${p.id}`); },
  });
  const { errors } = form.formState;
  return (
    <form onSubmit={form.handleSubmit((v) => create.mutate(v))} noValidate>
      <Stack>
        <ErrorAlert error={create.error} />
        <TextInput label="Name" required {...form.register("name")} error={errors.name?.message} />
        <TextInput label="ITU Carrier Code" required {...form.register("carrierCode")} error={errors.carrierCode?.message} />
        <TextInput label="S/PRI-Endpunkt des Partners" placeholder="https://spri.partner.example/spri/v4" description="aus seiner WSDL; nötig, wenn wir bei ihm bestellen" {...form.register("endpointUrl")} error={errors.endpointUrl?.message} />
        <TextInput label="Technischer Kontakt (E-Mail)" description="erhält die Ausfall-Mail" {...form.register("technicalContactEmail")} error={errors.technicalContactEmail?.message} />
        <Textarea label="Freigeschaltete Quell-IPs (CIDR)" description="eine pro Zeile, z. B. 203.0.113.10/32; ohne Eintrag weist der S/PRI-Endpunkt jede Adresse ab" autosize minRows={2} {...form.register("networks")} />
        <Textarea label="Signaturzertifikat (PEM)" description="RSA ≥ 3072; weitere über die Partnerseite" autosize minRows={3} className="mono" {...form.register("certificate")} />
        <Group justify="flex-end"><Button type="submit" loading={create.isPending}>Anlegen</Button></Group>
      </Stack>
    </form>
  );
}

/** What a new partner needs from us (GET /partners/onboarding-sheet) and our WSDL. */
function Onboarding() {
  const sheet = useQuery({ queryKey: ["onboarding-sheet"], queryFn: () => call(api.GET("/api/v1/partners/onboarding-sheet")) });
  const wsdl = useMutation({
    mutationFn: async () => (await call(api.GET("/api/v1/partners/wsdl", { parseAs: "text" }))) as unknown as string,
    onSuccess: (text) => saveFile(text, "open-spri.wsdl", "text/xml"),
  });
  if (sheet.isPending) return <Loading />;
  if (sheet.isError) return <ErrorAlert error={sheet.error} />;
  const s = sheet.data;
  return (
    <Stack>
      <Table variant="vertical">
        <Table.Tbody>
          <Table.Tr><Table.Th w={200}>Firma</Table.Th><Table.Td>{s.company}</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>ITU Carrier Code</Table.Th><Table.Td className="mono">{s.carrierCode}</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>S/PRI-Version</Table.Th><Table.Td>{s.spriVersion}</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>S/PRI-Endpunkt</Table.Th><Table.Td className="mono">{s.spriEndpoint ?? "nicht gesetzt (Einstellungen)"}</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>Outbound-IPs</Table.Th><Table.Td className="mono">{s.outboundAddresses.join(", ") || "nicht gesetzt"}</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>Technischer Kontakt</Table.Th><Table.Td>{s.technicalContact}</Table.Td></Table.Tr>
        </Table.Tbody>
      </Table>
      <Text size="sm" fw={500}>Signaturzertifikat</Text>
      <Code block className="mono">{s.signingCertificatePem}</Code>
      <ErrorAlert error={wsdl.error} />
      <Group justify="flex-end">
        <CopyButton value={s.signingCertificatePem}>{({ copied, copy }) => <Button variant="default" onClick={copy}>{copied ? "Kopiert" : "Zertifikat kopieren"}</Button>}</CopyButton>
        <Button onClick={() => wsdl.mutate()} loading={wsdl.isPending}>WSDL herunterladen</Button>
      </Group>
    </Stack>
  );
}

/** Partners (AUFGABE 8.6): who we exchange S/PRI messages with. */
export function PartnersPage() {
  const scopes = useScopes();
  const [creating, create] = useDisclosure();
  const [onboarding, showOnboarding] = useDisclosure();
  const partners = useQuery({ queryKey: ["partners"], queryFn: () => call(api.GET("/api/v1/partners")) });
  const now = Date.now();
  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Partner</Title>
        <Group>
          <Button variant="default" onClick={showOnboarding.open}>Unsere Angaben für Partner</Button>
          {hasScope(scopes, Scope.partnersAdmin) && <Button onClick={create.open}>Partner anlegen</Button>}
        </Group>
      </Group>
      <ErrorAlert error={partners.error} />
      {partners.isPending ? <Loading /> : partners.data?.length === 0 ? <Text c="dimmed">Noch keine Partner.</Text> : (
        <Table.ScrollContainer minWidth={800}>
          <Table striped>
            <Table.Thead><Table.Tr><Table.Th>Name</Table.Th><Table.Th>Carrier Code</Table.Th><Table.Th>Endpunkt</Table.Th><Table.Th>Zertifikate</Table.Th><Table.Th>Quell-IPs</Table.Th><Table.Th>Status</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {partners.data?.map((p) => {
                const soon = p.certificates.filter((c) => !c.expired && new Date(c.notAfter).getTime() - now < 30 * 86_400_000).length;
                const weak = p.certificates.filter((c) => c.keyBits < 3072).length;
                return (
                  <Table.Tr key={p.id}>
                    <Table.Td><Anchor component={Link} to={`/partners/${p.id}`}>{p.name}</Anchor></Table.Td>
                    <Table.Td className="mono">{p.carrierCode}</Table.Td>
                    <Table.Td className="mono"><Text size="sm" lineClamp={1}>{p.endpointUrl ?? "–"}</Text></Table.Td>
                    <Table.Td>
                      <Group gap={4}>
                        {p.certificates.length}
                        {soon > 0 && <Badge color="orange" variant="light">läuft bald ab</Badge>}
                        {weak > 0 && <Badge color="orange" variant="light">&lt; 3072 Bit</Badge>}
                        {p.certificates.some((c) => c.expired) && <Badge color="red" variant="light">abgelaufen</Badge>}
                      </Group>
                    </Table.Td>
                    <Table.Td>{p.allowedSourceNetworks.length === 0 ? <Badge color="gray" variant="light">keine</Badge> : p.allowedSourceNetworks.length}</Table.Td>
                    <Table.Td>{p.enabled ? <Badge color="teal" variant="light">aktiv</Badge> : <Badge color="gray">deaktiviert</Badge>}</Table.Td>
                  </Table.Tr>
                );
              })}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Modal opened={creating} onClose={create.close} title="Partner anlegen" size="lg"><CreatePartner onClose={create.close} /></Modal>
      <Modal opened={onboarding} onClose={showOnboarding.close} title="Unsere Angaben für Partner" size="xl">
        <Stack>
          <Alert color="blue">Diese Angaben braucht ein neuer Partner, um uns freizuschalten und unsere Signatur zu prüfen.</Alert>
          <Onboarding />
        </Stack>
      </Modal>
    </Stack>
  );
}
