import {
  Alert, Button, Checkbox, Container, Fieldset, FileInput, Group, Paper, PasswordInput, Radio, Select, SimpleGrid, Stack, Stepper, Text, Textarea, TextInput, Title,
} from "@mantine/core";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm, type Control, type FieldPath } from "react-hook-form";
import { Link, Navigate } from "react-router";
import { api, ApiError, call } from "../api/client";
import { setupQueryKey, useSetupStatus } from "../auth/session";
import { ErrorAlert } from "../components/Feedback";
import { setupDefaults, setupSchema, STEP_FIELDS, toSetupRequest, type SetupForm } from "./setupForm";

async function fileToBase64(file: File): Promise<string> {
  const bytes = new Uint8Array(await file.arrayBuffer());
  let binary = "";
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

type BooleanField = Extract<FieldPath<SetupForm>, `company.${"role" | "est" | "diag" | "connectivity" | "thirdParty"}${string}`>;

function CheckboxField({ control, name, label }: { control: Control<SetupForm>; name: BooleanField; label: string }) {
  return (
    <Controller control={control} name={name} render={({ field }) => (
      <Checkbox label={label} checked={field.value === true} onChange={(e) => field.onChange(e.currentTarget.checked)} onBlur={field.onBlur} ref={field.ref} />
    )} />
  );
}

const WBCI_OPTIONS = [
  { value: "SERVICE_PROVIDER", label: "über einen Dienstleister" },
  { value: "OWN_INTERFACE", label: "eigene WBCI-Schnittstelle" },
  { value: "NONE", label: "keine Vorabstimmung" },
];

/** First setup (AUFGABE 5.1): token from the log, company, administrator, signing key. */
export function SetupPage() {
  const status = useSetupStatus();
  const [step, setStep] = useState(0);
  const [done, setDone] = useState(false);
  const client = useQueryClient();
  const form = useForm<SetupForm>({ resolver: zodResolver(setupSchema), defaultValues: setupDefaults, mode: "onTouched" });
  const { register, control, formState: { errors }, watch } = form;
  const keyFromFile = status.data?.signingKeySource === "KEYSTORE_FILE";

  const complete = useMutation({
    mutationFn: (values: SetupForm) =>
      call(api.POST("/api/v1/setup", { params: { header: { "X-Setup-Token": values.setupToken.trim() } }, body: toSetupRequest(values) })),
    onSuccess: (result) => {
      setDone(true);
      client.setQueryData(setupQueryKey, result);
    },
    onError: (e) => {
      if (e instanceof ApiError && e.status === 403) setStep(0);
    },
  });

  async function next() {
    const fields = STEP_FIELDS[step];
    if (fields && !(await form.trigger(fields as unknown as Parameters<typeof form.trigger>[0]))) return;
    setStep((s) => s + 1);
  }

  const values = watch();
  if (status.data?.completed && !done) return <Navigate to="/login" replace />;
  if (done) {
    return (
      <Container size="sm" py="xl" component="main">
        <Stack>
          <Title order={1} size="h2">Einrichtung abgeschlossen</Title>
          <Text>open-spri ist eingerichtet. Melden Sie sich mit dem eben angelegten Administrator „{values.admin.username}“ an.</Text>
          <Text size="sm" c="dimmed">Als Nächstes: Partner anlegen und ihre Zertifikate und IP-Adressen eintragen (docs/betrieb.md).</Text>
          <Group><Button component={Link} to="/login">Zur Anmeldung</Button></Group>
        </Stack>
      </Container>
    );
  }
  const tokenRejected = complete.error instanceof ApiError && complete.error.status === 403;

  return (
    <Container size="sm" py="xl" component="main">
      <Stack>
        <Title order={1} size="h2">Ersteinrichtung</Title>
        <Text c="dimmed">open-spri ist noch nicht eingerichtet. Der Assistent legt Firmendaten, den ersten Administrator und den Signaturschlüssel an.</Text>
        <Paper withBorder p="lg">
          <form onSubmit={form.handleSubmit((v) => complete.mutate(v))} noValidate>
            <Stepper active={step} onStepClick={(s) => s < step && setStep(s)} size="sm" allowNextStepsSelect={false}>
              <Stepper.Step label="Setup-Token">
                <Stack mt="md">
                  {tokenRejected && <Alert color="red" role="alert">Das Setup-Token passt nicht. Es steht im Log des Containers „api“ und gilt nur bis zum nächsten Neustart.</Alert>}
                  <Text size="sm">Das einmalige Setup-Token steht im Log des API-Containers: <code>docker compose logs api | grep -A3 "setup token"</code></Text>
                  <TextInput label="Setup-Token" autoComplete="off" required {...register("setupToken")} error={errors.setupToken?.message} />
                </Stack>
              </Stepper.Step>

              <Stepper.Step label="Firma">
                <Stack mt="md">
                  <TextInput label="Firmenname" required {...register("company.name")} error={errors.company?.name?.message} />
                  <SimpleGrid cols={{ base: 1, xs: 3 }}>
                    <TextInput label="Straße" {...register("company.street")} />
                    <TextInput label="PLZ" {...register("company.postalCode")} />
                    <TextInput label="Ort" {...register("company.city")} />
                  </SimpleGrid>
                  <SimpleGrid cols={{ base: 1, xs: 2 }}>
                    <TextInput label="ITU Carrier Code" description="steht in jeder S/PRI-Nachricht und im Replikat" required {...register("company.carrierCode")} error={errors.company?.carrierCode?.message} />
                    <TextInput label="Betriebs-E-Mail" description="für Ausfall- und Wartungsmeldungen" type="email" required {...register("company.contactEmail")} error={errors.company?.contactEmail?.message} />
                  </SimpleGrid>
                  <Fieldset legend="Rollen" variant="unstyled">
                    <Stack gap="xs">
                      <CheckboxField control={control} name="company.roleAuftraggeber" label="Auftraggeber: wir bestellen bei Partnern" />
                      <CheckboxField control={control} name="company.roleLeistungserbringer" label="Leistungserbringer: wir bieten eigene Leitungen an" />
                      {errors.company?.roleAuftraggeber?.message && <Text size="sm" c="var(--mantine-color-error)" role="alert">{errors.company.roleAuftraggeber.message}</Text>}
                    </Stack>
                  </Fieldset>
                  <Fieldset legend="Optionen" variant="unstyled">
                    <Stack gap="xs">
                      <CheckboxField control={control} name="company.estEnabled" label="Entstörung (EST)" />
                      <CheckboxField control={control} name="company.diagEnabled" label="Diagnose (DIAG)" />
                      <CheckboxField control={control} name="company.connectivityEnabled" label="Konnektivitätsaufträge" />
                      <CheckboxField control={control} name="company.thirdPartyOrdersEnabled" label="Aufträge für Dritte" />
                    </Stack>
                  </Fieldset>
                  <Controller control={control} name="company.wbciMode" render={({ field }) => (
                    <Select label="Vorabstimmung beim Providerwechsel (WBCI)" data={WBCI_OPTIONS} allowDeselect={false} value={field.value} onChange={(v) => field.onChange(v ?? "SERVICE_PROVIDER")} />
                  )} />
                  <TextInput label="Öffentliche Adresse unseres S/PRI-Endpunkts" placeholder="https://spri.example.de/spri/v4" description="für die WSDL an Partner; später änderbar"
                    {...register("company.publicSpriEndpoint")} error={errors.company?.publicSpriEndpoint?.message} />
                  <Textarea label="Feste Outbound-IP-Adressen" description="Partner schalten sie frei; eine pro Zeile" autosize minRows={2} {...register("company.outboundAddresses")} />
                </Stack>
              </Stepper.Step>

              <Stepper.Step label="Administrator">
                <Stack mt="md">
                  <SimpleGrid cols={{ base: 1, xs: 2 }}>
                    <TextInput label="Benutzername" autoComplete="username" required {...register("admin.username")} error={errors.admin?.username?.message} />
                    <TextInput label="Anzeigename" required {...register("admin.displayName")} error={errors.admin?.displayName?.message} />
                  </SimpleGrid>
                  <PasswordInput label="Passwort" description="mindestens 12 Zeichen" autoComplete="new-password" required {...register("admin.password")} error={errors.admin?.password?.message} />
                  <PasswordInput label="Passwort wiederholen" autoComplete="new-password" required {...register("admin.passwordRepeat")} error={errors.admin?.passwordRepeat?.message} />
                </Stack>
              </Stepper.Step>

              <Stepper.Step label="Signaturschlüssel">
                <Stack mt="md">
                  {keyFromFile ? (
                    <Alert color="teal">Der Signaturschlüssel kommt aus der Keystore-Datei (OPENSPRI_SOAP_SIGNING_KEYSTORE). Hier ist nichts zu tun.</Alert>
                  ) : (
                    <>
                      <Controller control={control} name="signingKey.generate" render={({ field }) => (
                        <Radio.Group label="WS-Security-Signatur" value={field.value ? "generate" : "import"} onChange={(v) => field.onChange(v === "generate")}>
                          <Stack gap="xs" mt={4}>
                            <Radio value="generate" label="Neuen Schlüssel erzeugen (RSA 3072, selbstsigniert)" />
                            <Radio value="import" label="Vorhandenen PKCS#12-Keystore übernehmen" />
                          </Stack>
                        </Radio.Group>
                      )} />
                      {values.signingKey.generate ? (
                        <TextInput label="Name im Zertifikat" placeholder={`S/PRI ${values.company.name || "Firma"}`} {...register("signingKey.commonName")} />
                      ) : (
                        <>
                          <Controller control={control} name="signingKey.pkcs12Base64" render={({ field }) => (
                            <FileInput label="PKCS#12-Datei (.p12, .pfx)" accept=".p12,.pfx,application/x-pkcs12" required clearable
                              error={errors.signingKey?.pkcs12Base64?.message}
                              onChange={async (file) => field.onChange(file ? await fileToBase64(file) : "")} />
                          )} />
                          <PasswordInput label="Passwort des Keystores" autoComplete="off" {...register("signingKey.pkcs12Password")} />
                        </>
                      )}
                      <Text size="sm" c="dimmed">Der Schlüssel wird mit OPENSPRI_MASTER_KEY verschlüsselt in der Datenbank gespeichert.</Text>
                    </>
                  )}
                </Stack>
              </Stepper.Step>

              <Stepper.Completed>
                <Stack mt="md">
                  <Title order={2} size="h4">Zusammenfassung</Title>
                  <Text><b>{values.company.name}</b> ({values.company.carrierCode.toUpperCase()}), {values.company.contactEmail}</Text>
                  <Text>Rollen: {[values.company.roleAuftraggeber && "Auftraggeber", values.company.roleLeistungserbringer && "Leistungserbringer"].filter(Boolean).join(", ")}</Text>
                  <Text>Administrator: {values.admin.username}</Text>
                  <Text>Signaturschlüssel: {keyFromFile ? "aus der Keystore-Datei" : values.signingKey.generate ? "neu erzeugen" : "PKCS#12 übernehmen"}</Text>
                  {complete.isPending && <Text size="sm" aria-live="polite">Die Einrichtung läuft. Einen neuen Signaturschlüssel zu erzeugen dauert bis zu einer halben Minute.</Text>}
                  {!tokenRejected && <ErrorAlert error={complete.error} />}
                </Stack>
              </Stepper.Completed>
            </Stepper>

            <Group justify="space-between" mt="xl">
              <Button variant="default" onClick={() => setStep((s) => Math.max(0, s - 1))} disabled={step === 0 || complete.isPending}>Zurück</Button>
              {/* separate keys: React must not turn the clicked "Weiter" into the submit button during the click */}
              {step < 4 ? (
                <Button key="next" onClick={(e) => { e.preventDefault(); void next(); }}>Weiter</Button>
              ) : (
                <Button key="submit" type="submit" loading={complete.isPending}>Einrichtung abschließen</Button>
              )}
            </Group>
          </form>
        </Paper>
      </Stack>
    </Container>
  );
}
