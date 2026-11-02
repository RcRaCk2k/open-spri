import { MantineProvider, mergeThemeOverrides, Modal } from "@mantine/core";
import { QueryClientProvider } from "@tanstack/react-query";
import { render } from "@testing-library/react";
import { createMemoryRouter, RouterProvider, type RouteObject } from "react-router";
import { createQueryClient } from "../queryClient";
import { theme } from "../theme";

/** No animations in jsdom: dialogs are visible at once. */
export const testTheme = mergeThemeOverrides(theme, {
  components: { Modal: Modal.extend({ defaultProps: { transitionProps: { duration: 0 } } }) },
});

/** Renders routes like the application does, starting at [path]. */
export function renderRoutes(routes: RouteObject[], path: string) {
  const router = createMemoryRouter(routes, { initialEntries: [path] });
  const client = createQueryClient();
  client.setDefaultOptions({ queries: { retry: false } });
  render(
    <MantineProvider theme={testTheme}>
      <QueryClientProvider client={client}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </MantineProvider>,
  );
  return { router, client };
}

/** A fetch that answers by method and path; unknown requests fail the test. */
export function fakeFetch(answers: Record<string, (request: Request) => Response | Promise<Response>>) {
  const calls: Request[] = [];
  const fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
    const request = input instanceof Request ? input : new Request(new URL(String(input), "http://localhost"), init);
    calls.push(request);
    const key = `${request.method} ${new URL(request.url).pathname}`;
    const answer = answers[key];
    if (!answer) throw new Error(`unexpected request ${key}`);
    return answer(request);
  };
  return { fetch, calls };
}

export const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
