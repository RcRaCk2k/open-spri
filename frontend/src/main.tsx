import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { MantineProvider } from "@mantine/core";
import { Notifications } from "@mantine/notifications";
import { QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "react-router";
import "@mantine/core/styles.css";
import "@mantine/notifications/styles.css";
import { createRouter } from "./App";
import { createQueryClient } from "./queryClient";
import { cssVariablesResolver, theme } from "./theme";
import "./styles.css";

const queryClient = createQueryClient();
const router = createRouter();

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <MantineProvider theme={theme} cssVariablesResolver={cssVariablesResolver} defaultColorScheme="auto">
      <Notifications position="top-right" />
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>
  </StrictMode>,
);
