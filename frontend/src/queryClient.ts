import { MutationCache, QueryCache, QueryClient } from "@tanstack/react-query";
import { ApiError } from "./api/client";
import { meQueryKey } from "./auth/session";

/** A 401 on any request means the session has ended: the login is shown again. */
export function createQueryClient(): QueryClient {
  const client: QueryClient = new QueryClient({
    queryCache: new QueryCache({ onError: (e) => sessionEnded(e) }),
    mutationCache: new MutationCache({ onError: (e) => sessionEnded(e) }),
    defaultOptions: {
      queries: { retry: (count, e) => !(e instanceof ApiError && e.status >= 400 && e.status < 500) && count < 2, refetchOnWindowFocus: false },
    },
  });
  function sessionEnded(e: unknown) {
    if (e instanceof ApiError && e.status === 401) client.setQueryData(meQueryKey, null);
  }
  return client;
}
