import { useQuery, useQueryClient } from "@tanstack/react-query";
import { api, ApiError, call, type Schemas } from "../api/client";

export type Me = Schemas["MeResponse"];

export const meQueryKey = ["auth", "me"] as const;
export const setupQueryKey = ["setup"] as const;

/** The current login, or null if not logged in. */
export function useMe() {
  return useQuery({
    queryKey: meQueryKey,
    queryFn: async (): Promise<Me | null> => {
      try {
        return await call(api.GET("/api/v1/auth/me"));
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return null;
        throw e;
      }
    },
    staleTime: 60_000,
  });
}

/** Whether the first setup is completed (public endpoint). */
export function useSetupStatus() {
  return useQuery({ queryKey: setupQueryKey, queryFn: () => call(api.GET("/api/v1/setup")), staleTime: 60_000 });
}

export function useLogout() {
  const client = useQueryClient();
  return async () => {
    try {
      await call(api.POST("/api/v1/auth/logout"));
    } finally {
      client.clear();
      client.setQueryData(meQueryKey, null);
    }
  };
}
