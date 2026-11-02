/** Scopes of the REST API (docs/api/auth.md). The frontend only hides what the API would refuse anyway. */
export const Scope = {
  ordersRead: "orders:read",
  ordersWrite: "orders:write",
  leMessagesWrite: "le:messages:write",
  deliveriesRead: "deliveries:read",
  deliveriesWrite: "deliveries:write",
  partnersRead: "partners:read",
  partnersAdmin: "partners:admin",
  availabilityRead: "availability:read",
  availabilityAdmin: "availability:admin",
  eventsRead: "events:read",
  webhooksAdmin: "webhooks:admin",
  messageCodesAdmin: "message-codes:admin",
  usersAdmin: "users:admin",
  settingsAdmin: "settings:admin",
  auditRead: "audit:read",
} as const;

export type ScopeName = (typeof Scope)[keyof typeof Scope];

export function hasScope(scopes: readonly string[] | undefined, scope: ScopeName | undefined): boolean {
  return scope === undefined || (scopes ?? []).includes(scope);
}
