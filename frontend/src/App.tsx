import { Navigate, Outlet, createBrowserRouter, useLocation, type RouteObject } from "react-router";
import { Center, Text } from "@mantine/core";
import { useMe, useSetupStatus } from "./auth/session";
import { ErrorAlert, Loading } from "./components/Feedback";
import { Layout } from "./layout/Layout";
import { AuditPage } from "./pages/AuditPage";
import { DashboardPage } from "./pages/DashboardPage";
import { ErrorPage } from "./pages/ErrorPage";
import { LoginPage } from "./pages/LoginPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { SetupPage } from "./pages/SetupPage";
import { UsersPage } from "./pages/UsersPage";

/** Until the first setup is completed, every page leads to the setup wizard. */
function SetupGate() {
  const setup = useSetupStatus();
  const location = useLocation();
  if (setup.isPending) return <Loading />;
  if (setup.isError) return <Center p="xl"><ErrorAlert error={setup.error} title="Die API ist nicht erreichbar" /></Center>;
  // after the setup the wizard itself leads to the login (SetupPage)
  if (!setup.data.completed && location.pathname !== "/setup") return <Navigate to="/setup" replace />;
  return <Outlet />;
}

/** Pages behind the login; the target is kept in `?next=`. */
function SessionGate() {
  const me = useMe();
  const location = useLocation();
  if (me.isPending) return <Loading />;
  if (me.isError) return <Center p="xl"><ErrorAlert error={me.error} /></Center>;
  if (!me.data) return <Navigate to={`/login?next=${encodeURIComponent(location.pathname + location.search)}`} replace />;
  if (me.data.kind === "SETUP") return <Center p="xl"><Text>Die Ersteinrichtung ist noch nicht abgeschlossen.</Text></Center>;
  return <Layout me={me.data} />;
}

export const routes: RouteObject[] = [
  {
    element: <SetupGate />,
    errorElement: <ErrorPage />,
    children: [
      { path: "/setup", element: <SetupPage /> },
      { path: "/login", element: <LoginPage /> },
      {
        element: <SessionGate />,
        children: [
          { index: true, element: <DashboardPage /> },
          { path: "/audit", element: <AuditPage /> },
          { path: "/users", element: <UsersPage /> },
          { path: "*", element: <NotFoundPage /> },
        ],
      },
    ],
  },
];

export function createRouter() {
  return createBrowserRouter(routes);
}
