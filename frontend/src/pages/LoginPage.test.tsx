import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { routes } from "../App";
import { fakeFetch, json, renderRoutes } from "../test/render";
import { safeNext } from "./LoginPage";

describe("safeNext", () => {
  it("allows only paths of this application", () => {
    expect(safeNext("/audit?x=1")).toBe("/audit?x=1");
    expect(safeNext("//evil.example")).toBe("/");
    expect(safeNext("https://evil.example")).toBe("/");
    expect(safeNext(null)).toBe("/");
  });
});

describe("LoginPage", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("reports a wrong password, then logs in and opens the requested page", async () => {
    let loggedIn = false;
    const api = fakeFetch({
      "GET /api/v1/setup": () => json({ completed: true, signingKeySource: "DATABASE" }),
      "GET /api/v1/auth/me": () => (loggedIn ? json({ kind: "USER", name: "admin", scopes: ["audit:read"] }) : json({ title: "Unauthorized" }, 401)),
      "POST /api/v1/auth/login": async (request) => {
        const body = await request.json();
        if (body.password !== "richtiges-passwort") return json({ title: "Unauthorized", status: 401 }, 401);
        loggedIn = true;
        return json({ kind: "USER", name: "admin", scopes: ["audit:read"] });
      },
      "GET /api/v1/audit": () => json([{ id: 1, at: "2026-11-02T10:00:00Z", actor: "user:admin", action: "user.create", target: "user:erika", details: null }]),
    });
    vi.stubGlobal("fetch", api.fetch);
    const user = userEvent.setup();
    const { router } = renderRoutes(routes, "/audit");

    await waitFor(() => expect(router.state.location.pathname).toBe("/login"));
    expect(router.state.location.search).toBe("?next=%2Faudit");
    await user.type(await screen.findByRole("textbox", { name: /^Benutzername/ }), "admin");
    await user.type(screen.getByLabelText(/^Passwort/, { selector: "input" }), "falsch");
    await user.click(screen.getByRole("button", { name: "Anmelden" }));
    expect(await screen.findByText("Benutzername oder Passwort ist falsch.")).toBeInTheDocument();

    await user.clear(screen.getByLabelText(/^Passwort/, { selector: "input" }));
    await user.type(screen.getByLabelText(/^Passwort/, { selector: "input" }), "richtiges-passwort");
    await user.click(screen.getByRole("button", { name: "Anmelden" }));
    expect(await screen.findByRole("heading", { name: "Audit-Log" })).toBeInTheDocument();
    expect(await screen.findByText("user.create")).toBeInTheDocument();
    expect(router.state.location.pathname).toBe("/audit");
  });

  it("validates before sending", async () => {
    const api = fakeFetch({
      "GET /api/v1/setup": () => json({ completed: true, signingKeySource: "DATABASE" }),
      "GET /api/v1/auth/me": () => json({}, 401),
    });
    vi.stubGlobal("fetch", api.fetch);
    renderRoutes(routes, "/login");
    await userEvent.click(await screen.findByRole("button", { name: "Anmelden" }));
    expect(await screen.findByText("Benutzername angeben")).toBeInTheDocument();
    expect(api.calls.some((c) => c.method === "POST")).toBe(false);
  });
});
