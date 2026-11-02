import { Anchor, Code, Container, Stack, Text, Title } from "@mantine/core";
import { isRouteErrorResponse, Link, useRouteError } from "react-router";

/** Shown instead of a page that failed while rendering; the details help with a bug report. */
export function ErrorPage() {
  const error = useRouteError();
  const detail = isRouteErrorResponse(error) ? `${error.status} ${error.statusText}` : error instanceof Error ? error.message : String(error);
  return (
    <Container size="sm" py="xl" component="main">
      <Stack>
        <Title order={1} size="h2">Hier ist ein Fehler aufgetreten</Title>
        <Text>Die Seite konnte nicht angezeigt werden. Laden Sie sie neu oder gehen Sie <Anchor component={Link} to="/">zum Dashboard</Anchor>.</Text>
        <Text size="sm">Wenn der Fehler bleibt, melden Sie ihn bitte mit diesem Text:</Text>
        <Code block>{detail}</Code>
      </Stack>
    </Container>
  );
}
