import { describe, expect, it } from "vitest";
import { ApiError, call, csrfMiddleware, describeProblem, readCookie } from "./client";

describe("readCookie", () => {
  it("finds a cookie among others and decodes it", () => {
    expect(readCookie("XSRF-TOKEN", "a=1; XSRF-TOKEN=ab%3Dc; b=2")).toBe("ab=c");
    expect(readCookie("XSRF-TOKEN", "a=1")).toBeUndefined();
  });
});

describe("csrfMiddleware", () => {
  const run = async (method: string) => {
    document.cookie = "XSRF-TOKEN=geheim";
    const request = new Request("http://localhost/api/v1/tokens", { method });
    const result = await csrfMiddleware.onRequest!({ request } as never);
    return (result as Request).headers.get("X-XSRF-TOKEN");
  };
  it("adds the token to changing requests only", async () => {
    expect(await run("POST")).toBe("geheim");
    expect(await run("DELETE")).toBe("geheim");
    expect(await run("GET")).toBeNull();
  });
});

describe("call", () => {
  const response = (status: number) => new Response(null, { status });
  it("returns the data of a success", async () => {
    await expect(call(Promise.resolve({ data: { a: 1 }, response: response(200) }))).resolves.toEqual({ a: 1 });
  });
  it("turns a problem answer into an ApiError with the detail and the line errors", async () => {
    const error = (await call(Promise.resolve({ error: { title: "Bad Request", detail: "plz ungültig", errors: ["Zeile 2: plz"] }, response: response(400) })).catch((e: unknown) => e)) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(400);
    expect(error.message).toBe("plz ungültig");
    expect(error.problem.errors).toEqual(["Zeile 2: plz"]);
  });
  it("reports an unreachable API", async () => {
    const error = (await call(Promise.reject(new TypeError("fetch failed"))).catch((e: unknown) => e)) as ApiError;
    expect(error.status).toBe(0);
    expect(error.message).toBe("Die API ist nicht erreichbar.");
  });
  it("has German messages without detail", () => {
    expect(describeProblem(403, {})).toBe("Dafür fehlt die Berechtigung.");
    expect(describeProblem(500, { title: "Internal Server Error" })).toBe("Internal Server Error");
  });
});
