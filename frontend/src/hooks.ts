import { useQuery } from "@tanstack/react-query";
import { api, call } from "./api/client";
import { hasScope, Scope } from "./auth/scopes";
import { useMe } from "./auth/session";

/** Partner names by id for lists; empty without `partners:read`. */
export function usePartnerNames(): Map<number, string> {
  const me = useMe();
  const allowed = hasScope(me.data?.scopes, Scope.partnersRead);
  const partners = useQuery({ queryKey: ["partners"], queryFn: () => call(api.GET("/api/v1/partners")), enabled: allowed, staleTime: 60_000 });
  return new Map((partners.data ?? []).map((p) => [p.id, p.name]));
}

export function useScopes(): readonly string[] {
  return useMe().data?.scopes ?? [];
}

/** A key per dialog: a retried submit of the same dialog is not sent twice (Idempotency-Key, docs/api/ereignisse.md). */
export function newIdempotencyKey(): string {
  return `ui-${crypto.randomUUID()}`;
}
