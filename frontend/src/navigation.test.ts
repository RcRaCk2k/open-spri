import { describe, expect, it } from "vitest";
import { activeEntry, NAVIGATION, visibleNavigation } from "./navigation";

describe("visibleNavigation", () => {
  it("shows only pages the scopes allow, in groups", () => {
    const viewer = visibleNavigation(["orders:read", "audit:read"]);
    expect([...viewer.values()].flat().map((e) => e.path)).toEqual(["/", "/orders", "/work-queue", "/pv-requests", "/inventory", "/message-codes", "/audit"]);
    const nothing = visibleNavigation([]);
    expect([...nothing.values()].flat().map((e) => e.path)).toEqual(["/"]);
  });

  it("has unique paths", () => {
    expect(new Set(NAVIGATION.map((e) => e.path)).size).toBe(NAVIGATION.length);
  });
});

describe("activeEntry", () => {
  it("picks the most specific page", () => {
    expect(activeEntry("/availability/manage")?.path).toBe("/availability/manage");
    expect(activeEntry("/availability")?.path).toBe("/availability");
    expect(activeEntry("/orders/42")?.path).toBe("/orders");
    expect(activeEntry("/")?.path).toBe("/");
    expect(activeEntry("/ordersX")).toBeUndefined();
  });
});
