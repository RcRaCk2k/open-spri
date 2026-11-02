import { Anchor, Stack, Text, Title } from "@mantine/core";
import { Link } from "react-router";

export function NotFoundPage() {
  return (
    <Stack>
      <Title order={2}>Seite nicht gefunden</Title>
      <Text>Diese Seite gibt es nicht. <Anchor component={Link} to="/">Zum Dashboard</Anchor></Text>
    </Stack>
  );
}
