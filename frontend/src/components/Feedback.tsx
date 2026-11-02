import { Alert, Center, List, Loader } from "@mantine/core";
import { ApiError } from "../api/client";

/** Error of a request, with the line errors of a 400 answer (e.g. XSD findings). */
export function ErrorAlert({ error, title = "Das hat nicht geklappt" }: { error: unknown; title?: string }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  const details = error instanceof ApiError ? (error.problem.errors ?? []) : [];
  return (
    <Alert color="red" title={title} role="alert">
      {message}
      {details.length > 0 && (
        <List size="sm" mt="xs">
          {details.slice(0, 20).map((d) => <List.Item key={d}>{d}</List.Item>)}
        </List>
      )}
    </Alert>
  );
}

export function Loading({ label = "Wird geladen …" }: { label?: string }) {
  return (
    <Center py="xl" aria-live="polite">
      <Loader size="sm" aria-label={label} />
    </Center>
  );
}
