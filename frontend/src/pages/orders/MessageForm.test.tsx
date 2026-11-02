import { MantineProvider } from "@mantine/core";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { Schemas } from "../../api/client";
import { fakeFetch, json, testTheme } from "../../test/render";
import { MessageForm } from "./ActionDialogs";
import { actionLabel, actionScope } from "./OrderDetailPage";
import { indentXml } from "./RawXml";

const order: Schemas["OrderDto"] = {
  id: 77, partnerId: 7, role: "LEISTUNGSERBRINGER", externalOrderNumber: "AG-2026-0077", businessCase: "NEU", phase: "ACKNOWLEDGED",
  finished: false, openRequests: [], cancelled: false, handoverPhase: null, clientNumber: "AG4711", serviceNumber: "LN0815",
  desiredDate: "2026-12-15", contractNumber: null, lineId: null, homeId: null, products: ["FTTH 100"],
  createdAt: "2026-11-01T10:00:00Z", updatedAt: "2026-11-01T10:05:00Z",
};

describe("MessageForm", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("offers only codes of the business case and sends ABM with the binding date and an idempotency key", async () => {
    const sent: { body: unknown; key: string | null }[] = [];
    const api = fakeFetch({
      "GET /api/v1/message-codes": () => json({
        source: "test", sha256: "0",
        codes: [
          { code: "0000", text: "Auftrag bestätigt", messageTypes: ["ABM"], businessCases: [], localAddition: false },
          { code: "0300", text: "nur bei EST", messageTypes: ["ABM"], businessCases: ["EST"], localAddition: false },
        ],
      }),
      "POST /api/v1/orders/77/messages": async (request) => {
        sent.push({ body: await request.json(), key: request.headers.get("Idempotency-Key") });
        return json({ order: { ...order, phase: "CONFIRMED" }, allowedActions: [] }, 202);
      },
    });
    vi.stubGlobal("fetch", api.fetch);
    const done = vi.fn();
    const user = userEvent.setup();
    render(
      <MantineProvider theme={testTheme}>
        <QueryClientProvider client={new QueryClient()}>
          <MessageForm order={order} messageType="ABM" changeIndicator="Standard" onDone={done} />
        </QueryClientProvider>
      </MantineProvider>,
    );

    const send = screen.getByRole("button", { name: "ABM senden" });
    expect(send).toBeDisabled();
    await waitFor(() => expect(api.calls.map((c) => new URL(c.url).pathname)).toContain("/api/v1/message-codes"));
    const input = screen.getByLabelText(/Meldungscodes/, { selector: "input" });
    await user.type(input, "0");
    // the dropdown is positioned by floating-ui, which jsdom cannot lay out: the options count as hidden there
    const option = await screen.findByRole("option", { name: "0000 – Auftrag bestätigt", hidden: true });
    expect(screen.queryAllByRole("option", { hidden: true })).toHaveLength(1);
    await user.click(option);
    await user.type(screen.getByLabelText("Verbindlicher Liefertermin"), "2026-12-15");
    await user.click(send);

    await waitFor(() => expect(done).toHaveBeenCalled());
    expect(sent).toHaveLength(1);
    expect(sent[0]!.body).toEqual({ type: "ABM", changeIndicator: "Standard", codes: [{ code: "0000" }], attributes: { verbindlicherLiefertermin: "2026-12-15" } });
    expect(sent[0]!.key).toMatch(/^ui-/);
  });
});

describe("actions", () => {
  it("labels and scopes", () => {
    expect(actionLabel({ kind: "MESSAGE", messageType: "ERLM", changeIndicator: "Storno", request: null })).toBe("ERLM (Storno)");
    expect(actionLabel({ kind: "REQUEST", messageType: null, changeIndicator: null, request: "TERMINVERSCHIEBUNG" })).toBe("Terminverschiebung");
    expect(actionScope(order, { kind: "MESSAGE", messageType: "QEB", changeIndicator: "Standard", request: null })).toBe("le:messages:write");
    expect(actionScope({ ...order, role: "AUFTRAGGEBER" }, { kind: "MESSAGE", messageType: "ZWM-AG", changeIndicator: "Standard", request: null })).toBe("orders:write");
  });

  it("indents XML for reading", () => {
    expect(indentXml("<a><b>1</b><c/></a>")).toBe("<a>\n  <b>1</b>\n  <c/>\n</a>");
  });
});
