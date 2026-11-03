import { Alert, Badge, Button, Checkbox, Code, CopyButton, FileInput, Group, Modal, PasswordInput, Radio, Select, SimpleGrid, Stack, Table, Tabs, Text, Textarea, TextInput, Title } from "@mantine/core";
import { notifications } from "@mantine/notifications";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api, call, type Schemas } from "../api/client";
import { ErrorAlert, Loading } from "../components/Feedback";
import { lines } from "../components/files";
import { formatDate } from "../format";

type Company = Schemas["CompanySettingsDto"];

function CompanyForm({ initial }: { initial: Company }) {
  const client = useQueryClient();
  const [c, setC] = useState<Company>(initial);
  const [addresses, setAddresses] = useState(initial.outboundAddresses?.join("\n") ?? "");
  const save = useMutation({
    mutationFn: () => call(api.PUT("/api/v1/settings", { body: { ...c, outboundAddresses: lines(addresses) } })),
    onSuccess: (s) => { client.setQueryData(["settings"], s); notifications.show({ color: "teal", message: "Einstellungen gespeichert" }); },
  });
  const text = (k: keyof Company) => ({ value: (c[k] as string | null | undefined) ?? "", onChange: (e: React.ChangeEvent<HTMLInputElement>) => { const v = e.currentTarget.value; setC((x) => ({ ...x, [k]: v })); } });
  const check = (k: keyof Company, label: string) => (
    <Checkbox label={label} checked={c[k] === true} onChange={(e) => { const v = e.currentTarget.checked; setC((x) => ({ ...x, [k]: v })); }} />
  );
  return (
    <form onSubmit={(e) => { e.preventDefault(); save.mutate(); }}>
      <Stack maw={760}>
        <TextInput label="Firmenname" required {...text("name")} />
        <SimpleGrid cols={{ base: 1, sm: 3 }}>
          <TextInput label="Straße" {...text("street")} />
          <TextInput label="PLZ" {...text("postalCode")} />
          <TextInput label="Ort" {...text("city")} />
        </SimpleGrid>
        <SimpleGrid cols={{ base: 1, sm: 2 }}>
          <TextInput label="ITU Carrier Code" required {...text("carrierCode")} />
          <TextInput label="Betriebs-E-Mail" type="email" required {...text("contactEmail")} />
        </SimpleGrid>
        <Stack gap="xs">
          <Text size="sm" fw={500}>Rollen und Optionen</Text>
          {check("roleAuftraggeber", "Auftraggeber")}
          {check("roleLeistungserbringer", "Leistungserbringer")}
          {check("estEnabled", "Entstörung (EST)")}
          {check("diagEnabled", "Diagnose (DIAG)")}
          {check("connectivityEnabled", "Konnektivitätsaufträge")}
          {check("thirdPartyOrdersEnabled", "Aufträge für Dritte")}
        </Stack>
        <Select label="Vorabstimmung (WBCI)" allowDeselect={false} value={c.wbciMode ?? "SERVICE_PROVIDER"} onChange={(v) => setC((x) => ({ ...x, wbciMode: (v ?? "SERVICE_PROVIDER") as Company["wbciMode"] }))}
          data={[{ value: "SERVICE_PROVIDER", label: "über einen Dienstleister" }, { value: "OWN_INTERFACE", label: "eigene WBCI-Schnittstelle" }, { value: "NONE", label: "keine Vorabstimmung" }]} />
        <TextInput label="Öffentliche Adresse unseres S/PRI-Endpunkts" description="steht in der WSDL für Partner" {...text("publicSpriEndpoint")} />
        <Textarea label="Feste Outbound-IP-Adressen" description="eine pro Zeile; Partner schalten sie frei" autosize minRows={2} value={addresses} onChange={(e) => setAddresses(e.currentTarget.value)} />
        <ErrorAlert error={save.error} />
        <Group justify="flex-end"><Button type="submit" loading={save.isPending}>Speichern</Button></Group>
      </Stack>
    </form>
  );
}

async function fileToBase64(file: File): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

function SigningKey() {
  const client = useQueryClient();
  const key = useQuery({ queryKey: ["signing-key"], queryFn: () => call(api.GET("/api/v1/settings/signing-key")) });
  const [open, setOpen] = useState(false);
  const [mode, setMode] = useState("generate");
  const [cn, setCn] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [password, setPassword] = useState("");
  const renew = useMutation({
    mutationFn: async () => call(api.POST("/api/v1/settings/signing-key", {
      body: mode === "generate" ? { commonName: cn || null } : { pkcs12Base64: file ? await fileToBase64(file) : null, pkcs12Password: password },
    })),
    onSuccess: (k) => { client.setQueryData(["signing-key"], k); setOpen(false); notifications.show({ color: "teal", message: "Neuer Signaturschlüssel aktiv" }); },
  });
  if (key.isPending) return <Loading />;
  if (key.isError) return <ErrorAlert error={key.error} />;
  const k = key.data;
  return (
    <Stack maw={860}>
      {k.source === "TEMPORARY" && <Alert color="red" title="Vorläufiger Schlüssel">Der Schlüssel ist nur vorläufig und geht beim Neustart verloren. Bitte einen dauerhaften Schlüssel erzeugen oder übernehmen.</Alert>}
      <Table variant="vertical">
        <Table.Tbody>
          <Table.Tr><Table.Th w={180}>Inhaber</Table.Th><Table.Td className="mono">{k.subject}</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>Aussteller</Table.Th><Table.Td className="mono">{k.issuer}</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>Seriennummer</Table.Th><Table.Td className="mono">{k.serialNumber}</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>Schlüssellänge</Table.Th><Table.Td>{k.keyBits} Bit</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>gültig bis</Table.Th><Table.Td>{formatDate(k.notAfter)}</Table.Td></Table.Tr>
          <Table.Tr><Table.Th>Herkunft</Table.Th><Table.Td>{{ KEYSTORE_FILE: "Keystore-Datei", DATABASE: "Datenbank (verschlüsselt)", TEMPORARY: "vorläufig" }[k.source] ?? k.source}</Table.Td></Table.Tr>
        </Table.Tbody>
      </Table>
      <Code block>{k.certificatePem}</Code>
      <Group justify="flex-end">
        <CopyButton value={k.certificatePem}>{({ copied, copy }) => <Button variant="default" onClick={copy}>{copied ? "Kopiert" : "Zertifikat kopieren"}</Button>}</CopyButton>
        {k.source !== "KEYSTORE_FILE" && <Button color="orange" onClick={() => setOpen(true)}>Schlüssel wechseln</Button>}
      </Group>
      <Modal opened={open} onClose={() => setOpen(false)} title="Signaturschlüssel wechseln" size="lg">
        <Stack>
          <Alert color="orange">Der neue Schlüssel gilt sofort für alle neuen Nachrichten. Partner müssen das neue Zertifikat vorher bei sich hinterlegen, sonst weisen sie unsere Nachrichten ab.</Alert>
          <Radio.Group value={mode} onChange={setMode} label="Neuer Schlüssel">
            <Stack gap="xs" mt={4}><Radio value="generate" label="erzeugen (RSA 3072, selbstsigniert)" /><Radio value="import" label="PKCS#12-Keystore übernehmen" /></Stack>
          </Radio.Group>
          {mode === "generate" ? <TextInput label="Name im Zertifikat" value={cn} onChange={(e) => setCn(e.currentTarget.value)} /> : (
            <>
              <FileInput label="PKCS#12-Datei" accept=".p12,.pfx" value={file} onChange={setFile} clearable />
              <PasswordInput label="Passwort des Keystores" value={password} onChange={(e) => setPassword(e.currentTarget.value)} />
            </>
          )}
          <ErrorAlert error={renew.error} />
          <Group justify="flex-end"><Button color="orange" onClick={() => renew.mutate()} loading={renew.isPending} disabled={mode === "import" && !file}>Wechseln</Button></Group>
        </Stack>
      </Modal>
    </Stack>
  );
}

type Check = Schemas["SelfTestCheckDto"];

function CheckTable({ checks }: { checks: Check[] }) {
  return (
    <Table>
      <Table.Tbody>
        {checks.map((c) => (
          <Table.Tr key={c.id}>
            <Table.Td w={120}>
              <Badge color={!c.ok ? "red" : c.warning ? "orange" : "teal"} variant="light">{!c.ok ? "Fehler" : c.warning ? "Hinweis" : "OK"}</Badge>
            </Table.Td>
            <Table.Td>{c.detail}</Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

/** Self-tests of the public address (AUFGABE 7.4) and the outbound IP (7.6); run on request, they call out. */
function SelfTests() {
  const tls = useMutation({ mutationFn: () => call(api.GET("/api/v1/selftest/tls")) });
  const ip = useMutation({ mutationFn: () => call(api.GET("/api/v1/selftest/outbound-ip")) });
  return (
    <Stack maw={900}>
      <Title order={3}>TLS-Zertifikat</Title>
      <Text size="sm">Ruft unseren öffentlichen S/PRI-Endpunkt so auf wie ein Partner und prüft das Zertifikat nach dem AK-Sicherheitsdokument (TLS ≥ 1.2, öffentliche CA, ECC ≥ 256 oder RSA ≥ 3072 Bit, Restlaufzeit).</Text>
      <Group><Button variant="default" onClick={() => tls.mutate()} loading={tls.isPending}>TLS prüfen</Button></Group>
      <ErrorAlert error={tls.error} />
      {tls.data && (
        <>
          <Alert color={tls.data.ok ? "teal" : "red"} title={tls.data.ok ? "Zertifikat in Ordnung" : "Zertifikat nicht für den Partnerbetrieb geeignet"}>
            {tls.data.url}{tls.data.notAfter && <> · gültig bis {formatDate(tls.data.notAfter)}</>}
          </Alert>
          <CheckTable checks={tls.data.checks} />
        </>
      )}
      <Title order={3}>Outbound-IP</Title>
      <Text size="sm">Prüft, von welcher Adresse unsere Anfragen bei Partnern ankommen, und vergleicht sie mit den eingetragenen Outbound-Adressen. Voraussetzung: <Code>OPENSPRI_SELFTEST_IP_ECHO_URL</Code>.</Text>
      <Group><Button variant="default" onClick={() => ip.mutate()} loading={ip.isPending}>Outbound-IP prüfen</Button></Group>
      <ErrorAlert error={ip.error} />
      {ip.data && (
        <>
          <Alert color={ip.data.ok ? "teal" : "red"} title={ip.data.detected ? `Erkannt: ${ip.data.detected}` : "Keine Adresse ermittelt"}>
            Eingetragen: {ip.data.configured.length > 0 ? ip.data.configured.join(", ") : "keine"}
          </Alert>
          <CheckTable checks={ip.data.checks} />
        </>
      )}
    </Stack>
  );
}

/** Settings (AUFGABE 8.6): company data, roles and options, WS-Security signing key, self-tests. */
export function SettingsPage() {
  const settings = useQuery({ queryKey: ["settings"], queryFn: () => call(api.GET("/api/v1/settings")) });
  return (
    <Stack>
      <Title order={2}>Einstellungen</Title>
      <Tabs defaultValue="company" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="company">Firma und Optionen</Tabs.Tab>
          <Tabs.Tab value="key">Signaturschlüssel</Tabs.Tab>
          <Tabs.Tab value="selftest">Selbsttests</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="company" pt="md">
          <ErrorAlert error={settings.error} />
          {settings.isPending ? <Loading /> : settings.data && <CompanyForm initial={settings.data} />}
        </Tabs.Panel>
        <Tabs.Panel value="key" pt="md"><SigningKey /></Tabs.Panel>
        <Tabs.Panel value="selftest" pt="md"><SelfTests /></Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
