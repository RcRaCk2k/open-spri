import { Alert, Button, Center, Paper, PasswordInput, Stack, Text, TextInput, Title } from "@mantine/core";
import { zodResolver } from "@hookform/resolvers/zod";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useForm } from "react-hook-form";
import { Navigate, useNavigate, useSearchParams } from "react-router";
import { z } from "zod";
import { api, ApiError, call } from "../api/client";
import { meQueryKey, useMe } from "../auth/session";
import { ErrorAlert } from "../components/Feedback";

const schema = z.object({
  username: z.string().trim().min(1, "Benutzername angeben"),
  password: z.string().min(1, "Passwort angeben"),
});
type LoginForm = z.infer<typeof schema>;

/** Only relative targets inside the application, never another origin. */
export function safeNext(next: string | null): string {
  return next && next.startsWith("/") && !next.startsWith("//") ? next : "/";
}

export function LoginPage() {
  const [params] = useSearchParams();
  const next = safeNext(params.get("next"));
  const navigate = useNavigate();
  const client = useQueryClient();
  const me = useMe();
  const form = useForm<LoginForm>({ resolver: zodResolver(schema), defaultValues: { username: "", password: "" } });
  const login = useMutation({
    mutationFn: (values: LoginForm) => call(api.POST("/api/v1/auth/login", { body: values })),
    onSuccess: (data) => {
      client.setQueryData(meQueryKey, data);
      navigate(next, { replace: true });
    },
  });

  if (me.data && me.data.kind !== "SETUP") return <Navigate to={next} replace />;
  const wrongPassword = login.error instanceof ApiError && login.error.status === 401;

  return (
    <Center mih="100vh" p="md">
      <Paper withBorder p="xl" w="100%" maw={420} component="main">
        <form onSubmit={form.handleSubmit((values) => login.mutate(values))} noValidate>
          <Stack>
            <Title order={1} size="h2">Anmelden</Title>
            <Text size="sm" c="dimmed">open-spri – S/PRI-Schnittstelle</Text>
            {wrongPassword ? (
              <Alert color="red" role="alert">Benutzername oder Passwort ist falsch.</Alert>
            ) : (
              <ErrorAlert error={login.error} />
            )}
            <TextInput label="Benutzername" autoComplete="username" required {...form.register("username")} error={form.formState.errors.username?.message} />
            <PasswordInput label="Passwort" autoComplete="current-password" required {...form.register("password")} error={form.formState.errors.password?.message} />
            <Button type="submit" loading={login.isPending}>Anmelden</Button>
          </Stack>
        </form>
      </Paper>
    </Center>
  );
}
