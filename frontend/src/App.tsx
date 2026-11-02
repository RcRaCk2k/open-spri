import type React from "react";
import { Navigate, Outlet, createBrowserRouter, useLocation, type RouteObject } from "react-router";
import { Center, Text } from "@mantine/core";
import { useMe, useSetupStatus } from "./auth/session";
import { ErrorAlert, Loading } from "./components/Feedback";
import { Layout } from "./layout/Layout";
import { ErrorPage } from "./pages/ErrorPage";
import { LoginPage } from "./pages/LoginPage";
import { NotFoundPage } from "./pages/NotFoundPage";
import { SetupPage } from "./pages/SetupPage";

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

/** Pages are loaded on first use, so the login does not load the whole application. */
const page = (load: () => Promise<{ default: React.ComponentType }>): Pick<RouteObject, "lazy"> => ({
  lazy: async () => ({ Component: (await load()).default }),
});

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
          { index: true, ...page(() => import("./pages/DashboardPage").then((m) => ({ default: m.DashboardPage }))) },
          { path: "/orders", ...page(() => import("./pages/orders/OrderListPage").then((m) => ({ default: () => <m.OrderListPage /> }))) },
          { path: "/orders/new", ...page(() => import("./pages/orders/NewOrderPage").then((m) => ({ default: m.NewOrderPage }))) },
          { path: "/orders/:id", ...page(() => import("./pages/orders/OrderDetailPage").then((m) => ({ default: m.OrderDetailPage }))) },
          { path: "/work-queue", ...page(() => import("./pages/orders/OrderListPage").then((m) => ({ default: () => <m.OrderListPage workQueue /> }))) },
          { path: "/pv-requests", ...page(() => import("./pages/orders/PvRequestsPage").then((m) => ({ default: m.PvRequestsPage }))) },
          { path: "/terminations/new", ...page(() => import("./pages/orders/TerminationPage").then((m) => ({ default: m.TerminationPage }))) },
          { path: "/availability", ...page(() => import("./pages/availability/AvailabilityPage").then((m) => ({ default: m.AvailabilityPage }))) },
          { path: "/inventory", ...page(() => import("./pages/InventoryPage").then((m) => ({ default: m.InventoryPage }))) },
          { path: "/audit", ...page(() => import("./pages/AuditPage").then((m) => ({ default: m.AuditPage }))) },
          { path: "/users", ...page(() => import("./pages/UsersPage").then((m) => ({ default: m.UsersPage }))) },
          { path: "*", element: <NotFoundPage /> },
        ],
      },
    ],
  },
];

export function createRouter() {
  return createBrowserRouter(routes);
}
