import {
  Alert, Badge, Button, CopyButton, Group, Modal, MultiSelect, PasswordInput, Select, Stack, Switch, Table, Tabs, Text, TextInput, Title,
} from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { notifications } from "@mantine/notifications";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { Controller, useForm } from "react-hook-form";
import { z } from "zod";
import { api, call, type Schemas } from "../api/client";
import { Scope } from "../auth/scopes";
import { ErrorAlert, Loading } from "../components/Feedback";
import { formatDateTime, ROLE_LABELS } from "../format";

type Role = Schemas["UserResponse"]["role"];
const ROLES = Object.keys(ROLE_LABELS) as Role[];
const roleOptions = (roles: Role[]) => roles.map((r) => ({ value: r, label: ROLE_LABELS[r] ?? r }));
/** Scopes an administrator may put into a token; orders:override only explicitly (ADR-0008). */
const TOKEN_SCOPES = [...Object.values(Scope), "orders:override", "partner:availability"];

const userSchema = z.object({
  username: z.string().trim().min(1, "Benutzernamen angeben"),
  displayName: z.string().trim().min(1, "Anzeigenamen angeben"),
  role: z.enum(ROLES as [Role, ...Role[]]),
  password: z.string().min(12, "mindestens 12 Zeichen"),
});
type UserForm = z.infer<typeof userSchema>;

function CreateUser({ onClose }: { onClose: () => void }) {
  const client = useQueryClient();
  const form = useForm<UserForm>({ resolver: zodResolver(userSchema), defaultValues: { username: "", displayName: "", role: "OPERATOR_AG", password: "" } });
  const create = useMutation({
    mutationFn: (body: UserForm) => call(api.POST("/api/v1/users", { body })),
    onSuccess: async (user) => {
      await client.invalidateQueries({ queryKey: ["users"] });
      notifications.show({ color: "teal", message: `Benutzer ${user.username} angelegt` });
      onClose();
    },
  });
  const { errors } = form.formState;
  return (
    <form onSubmit={form.handleSubmit((v) => create.mutate(v))} noValidate>
      <Stack>
        <ErrorAlert error={create.error} />
        <TextInput label="Benutzername" required autoComplete="off" {...form.register("username")} error={errors.username?.message} />
        <TextInput label="Anzeigename" required {...form.register("displayName")} error={errors.displayName?.message} />
        <Controller control={form.control} name="role" render={({ field }) => (
          <Select label="Rolle" required data={roleOptions(ROLES.filter((r) => r !== "PARTNER"))} allowDeselect={false} value={field.value} onChange={(v) => field.onChange(v)} />
        )} />
        <PasswordInput label="Passwort" description="mindestens 12 Zeichen" required autoComplete="new-password" {...form.register("password")} error={errors.password?.message} />
        <Group justify="flex-end"><Button type="submit" loading={create.isPending}>Anlegen</Button></Group>
      </Stack>
    </form>
  );
}

function Users() {
  const client = useQueryClient();
  const [opened, { open, close }] = useDisclosure();
  const users = useQuery({ queryKey: ["users"], queryFn: () => call(api.GET("/api/v1/users")) });
  const update = useMutation({
    mutationFn: ({ id, body }: { id: number; body: Schemas["UpdateUserRequest"] }) => call(api.PATCH("/api/v1/users/{id}", { params: { path: { id } }, body })),
    onSuccess: () => client.invalidateQueries({ queryKey: ["users"] }),
  });
  return (
    <Stack>
      <Group justify="flex-end"><Button onClick={open}>Benutzer anlegen</Button></Group>
      <ErrorAlert error={users.error ?? update.error} />
      {users.isPending ? <Loading /> : (
        <Table.ScrollContainer minWidth={600}>
          <Table>
            <Table.Thead><Table.Tr><Table.Th>Benutzer</Table.Th><Table.Th>Rolle</Table.Th><Table.Th>Letzte Anmeldung</Table.Th><Table.Th>Aktiv</Table.Th></Table.Tr></Table.Thead>
            <Table.Tbody>
              {users.data?.map((u) => (
                <Table.Tr key={u.id}>
                  <Table.Td><Text fw={500}>{u.displayName}</Text><Text size="sm" c="dimmed" className="mono">{u.username}</Text></Table.Td>
                  <Table.Td>
                    <Select aria-label={`Rolle von ${u.username}`} data={roleOptions(ROLES.filter((r) => r !== "PARTNER"))} value={u.role} allowDeselect={false}
                      onChange={(role) => role && role !== u.role && update.mutate({ id: u.id, body: { role: role as Role } })} />
                  </Table.Td>
                  <Table.Td>{formatDateTime(u.lastLoginAt) || "nie"}</Table.Td>
                  <Table.Td>
                    <Switch aria-label={`${u.username} aktiv`} checked={u.enabled} onChange={(e) => update.mutate({ id: u.id, body: { enabled: e.currentTarget.checked } })} />
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Modal opened={opened} onClose={close} title="Benutzer anlegen">
        <CreateUser onClose={close} />
      </Modal>
    </Stack>
  );
}

const tokenSchema = z
  .object({
    name: z.string().trim().min(1, "Namen angeben"),
    role: z.enum(ROLES as [Role, ...Role[]]),
    scopes: z.array(z.string()),
    expiresAt: z.string(),
    partnerId: z.string(),
  })
  .refine((v) => v.role !== "PARTNER" || v.partnerId !== "", { path: ["partnerId"], message: "Partner wählen" });
type TokenForm = z.infer<typeof tokenSchema>;

function CreateToken({ onCreated }: { onCreated: (token: Schemas["CreatedTokenResponse"]) => void }) {
  const client = useQueryClient();
  const form = useForm<TokenForm>({ resolver: zodResolver(tokenSchema), defaultValues: { name: "", role: "INTEGRATION", scopes: [], expiresAt: "", partnerId: "" } });
  const role = form.watch("role");
  const partners = useQuery({ queryKey: ["partners"], queryFn: () => call(api.GET("/api/v1/partners")), enabled: role === "PARTNER" });
  const create = useMutation({
    mutationFn: (v: TokenForm) => call(api.POST("/api/v1/tokens", {
      body: {
        name: v.name.trim(), role: v.role, scopes: v.scopes.length > 0 ? v.scopes : null,
        expiresAt: v.expiresAt ? new Date(`${v.expiresAt}T23:59:59`).toISOString() : null,
        partnerId: v.role === "PARTNER" ? Number(v.partnerId) : null,
      },
    })),
    onSuccess: async (created) => {
      await client.invalidateQueries({ queryKey: ["tokens"] });
      onCreated(created);
    },
  });
  const { errors } = form.formState;
  return (
    <form onSubmit={form.handleSubmit((v) => create.mutate(v))} noValidate>
      <Stack>
        <ErrorAlert error={create.error} />
        <TextInput label="Name" description="z. B. das Fremdsystem" required {...form.register("name")} error={errors.name?.message} />
        <Controller control={form.control} name="role" render={({ field }) => (
          <Select label="Rolle" required data={roleOptions(ROLES)} allowDeselect={false} value={field.value} onChange={(v) => field.onChange(v)} />
        )} />
        {role === "PARTNER" && (
          <Controller control={form.control} name="partnerId" render={({ field }) => (
            <Select label="Partner" required searchable data={(partners.data ?? []).map((p) => ({ value: String(p.id), label: `${p.name} (${p.carrierCode})` }))}
              value={field.value || null} onChange={(v) => field.onChange(v ?? "")} error={errors.partnerId?.message} />
          )} />
        )}
        <Controller control={form.control} name="scopes" render={({ field }) => (
          <MultiSelect label="Berechtigungen einschränken" description="leer = alle Berechtigungen der Rolle" data={TOKEN_SCOPES} value={field.value} onChange={field.onChange} clearable searchable />
        )} />
        <TextInput label="Gültig bis" type="date" description="leer = bis zum Widerruf" {...form.register("expiresAt")} />
        <Group justify="flex-end"><Button type="submit" loading={create.isPending}>Ausstellen</Button></Group>
      </Stack>
    </form>
  );
}

function Tokens() {
  const client = useQueryClient();
  const [opened, { open, close }] = useDisclosure();
  const [created, setCreated] = useState<Schemas["CreatedTokenResponse"] | null>(null);
  const tokens = useQuery({ queryKey: ["tokens"], queryFn: () => call(api.GET("/api/v1/tokens")) });
  const revoke = useMutation({
    mutationFn: (id: number) => call(api.DELETE("/api/v1/tokens/{id}", { params: { path: { id } } })),
    onSuccess: () => client.invalidateQueries({ queryKey: ["tokens"] }),
  });
  return (
    <Stack>
      <Group justify="flex-end"><Button onClick={() => { setCreated(null); open(); }}>Token ausstellen</Button></Group>
      <ErrorAlert error={tokens.error ?? revoke.error} />
      {tokens.isPending ? <Loading /> : (
        <Table.ScrollContainer minWidth={700}>
          <Table>
            <Table.Thead><Table.Tr><Table.Th>Name</Table.Th><Table.Th>Rolle</Table.Th><Table.Th>Gültig bis</Table.Th><Table.Th>Zuletzt benutzt</Table.Th><Table.Th>Status</Table.Th><Table.Th /></Table.Tr></Table.Thead>
            <Table.Tbody>
              {tokens.data?.map((t) => (
                <Table.Tr key={t.id}>
                  <Table.Td><Text fw={500}>{t.name}</Text><Text size="sm" c="dimmed" className="mono">spri_{t.prefix}_…</Text></Table.Td>
                  <Table.Td>{ROLE_LABELS[t.role] ?? t.role}</Table.Td>
                  <Table.Td>{formatDateTime(t.expiresAt) || "unbegrenzt"}</Table.Td>
                  <Table.Td>{t.lastUsedAt ? `${formatDateTime(t.lastUsedAt)} (${t.lastUsedIp ?? "?"})` : "nie"}</Table.Td>
                  <Table.Td>{t.revokedAt ? <Badge color="gray">widerrufen</Badge> : <Badge color="teal" variant="light">aktiv</Badge>}</Table.Td>
                  <Table.Td>
                    {!t.revokedAt && <Button size="xs" color="red" variant="light" onClick={() => revoke.mutate(t.id)} aria-label={`Token ${t.name} widerrufen`}>Widerrufen</Button>}
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      )}
      <Modal opened={opened} onClose={close} title={created ? "Token ausgestellt" : "Token ausstellen"} size="lg">
        {created ? (
          <Stack>
            <Alert color="orange" title="Nur jetzt sichtbar">Das Geheimnis wird nur in dieser Antwort angezeigt und nicht gespeichert.</Alert>
            <Text className="mono" data-testid="token-secret">{created.token}</Text>
            <Group justify="flex-end">
              <CopyButton value={created.token}>{({ copied, copy }) => <Button onClick={copy} variant="default">{copied ? "Kopiert" : "Kopieren"}</Button>}</CopyButton>
              <Button onClick={close}>Fertig</Button>
            </Group>
          </Stack>
        ) : <CreateToken onCreated={setCreated} />}
      </Modal>
    </Stack>
  );
}

/** Users of the web interface and API tokens for other systems (docs/api/auth.md). */
export function UsersPage() {
  return (
    <Stack>
      <Title order={2}>Benutzer und Tokens</Title>
      <Tabs defaultValue="users" keepMounted={false}>
        <Tabs.List>
          <Tabs.Tab value="users">Benutzer</Tabs.Tab>
          <Tabs.Tab value="tokens">API-Tokens</Tabs.Tab>
        </Tabs.List>
        <Tabs.Panel value="users" pt="md"><Users /></Tabs.Panel>
        <Tabs.Panel value="tokens" pt="md"><Tokens /></Tabs.Panel>
      </Tabs>
    </Stack>
  );
}
