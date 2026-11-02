import { AppShell, Anchor, Burger, Button, Group, NavLink, ScrollArea, Stack, Text, Title } from "@mantine/core";
import { useDisclosure } from "@mantine/hooks";
import { Link, Outlet, useLocation, useNavigate } from "react-router";
import { useLogout, type Me } from "../auth/session";
import { visibleNavigation } from "../navigation";

export function Layout({ me }: { me: Me }) {
  const [opened, { toggle, close }] = useDisclosure();
  const location = useLocation();
  const navigate = useNavigate();
  const logout = useLogout();
  const groups = visibleNavigation(me.scopes);

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 250, breakpoint: "sm", collapsed: { mobile: !opened } }} padding="md">
      <a href="#main" className="skip-link">Zum Inhalt springen</a>
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between" wrap="nowrap">
          <Group gap="sm" wrap="nowrap">
            <Burger opened={opened} onClick={toggle} hiddenFrom="sm" size="sm" aria-label="Navigation öffnen" />
            <Anchor component={Link} to="/" underline="never" c="inherit">
              <Title order={1} size="h3">open-spri</Title>
            </Anchor>
          </Group>
          <Group gap="sm" wrap="nowrap">
            <Text size="sm" visibleFrom="xs" data-testid="current-user">{me.name}</Text>
            <Button variant="default" size="xs" onClick={async () => { await logout(); navigate("/login"); }}>
              Abmelden
            </Button>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs" aria-label="Hauptnavigation">
        <ScrollArea>
          <Stack gap="md">
            {[...groups].map(([group, entries]) => (
              <nav key={group} aria-label={group}>
                <Text size="xs" fw={700} c="dimmed" tt="uppercase" px="sm" mb={4}>{group}</Text>
                {entries.map((entry) => {
                  const active = entry.path === "/" ? location.pathname === "/" : location.pathname.startsWith(entry.path);
                  return (
                    <NavLink
                      key={entry.path}
                      component={Link}
                      to={entry.path}
                      label={entry.label}
                      active={active}
                      aria-current={active ? "page" : undefined}
                      onClick={close}
                    />
                  );
                })}
              </nav>
            ))}
          </Stack>
        </ScrollArea>
      </AppShell.Navbar>
      <AppShell.Main id="main" tabIndex={-1}>
        <Outlet />
      </AppShell.Main>
    </AppShell>
  );
}
