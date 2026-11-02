import createClient, { type Middleware } from "openapi-fetch";
import type { components, paths } from "./schema";

/** All schemas of the public REST API, generated from docs/api/openapi.json (`pnpm generate:api`). */
export type Schemas = components["schemas"];
export type About = Schemas["AboutResponse"];

/** Reads a cookie of this origin, e.g. the CSRF token `XSRF-TOKEN` of the session. */
export function readCookie(name: string, cookies: string = document.cookie): string | undefined {
  for (const part of cookies.split(";")) {
    const [key, ...value] = part.trim().split("=");
    if (key === name) return decodeURIComponent(value.join("="));
  }
  return undefined;
}

const SAFE_METHODS = new Set(["GET", "HEAD", "OPTIONS"]);

/**
 * The web interface logs in with a session cookie. Changing requests need the CSRF token from the cookie
 * `XSRF-TOKEN` in the header `X-XSRF-TOKEN` (docs/api/auth.md).
 */
export const csrfMiddleware: Middleware = {
  onRequest({ request }) {
    if (!SAFE_METHODS.has(request.method)) {
      const token = readCookie("XSRF-TOKEN");
      if (token) request.headers.set("X-XSRF-TOKEN", token);
    }
    return request;
  },
};

/**
 * Typed client for the public REST API. Paths and types are generated from docs/api/openapi.json;
 * never hand-write endpoint URLs (CLAUDE.md, principle 4).
 */
// fetch is looked up on each call, so tests can replace it
export const api = createClient<paths>({ baseUrl: globalThis.location?.origin ?? "/", credentials: "same-origin", fetch: (request) => globalThis.fetch(request) });
api.use(csrfMiddleware);

/** Error answer of the API (RFC 9457 problem details). */
export interface Problem {
  title?: string;
  status?: number;
  detail?: string;
  errors?: string[];
}

export class ApiError extends Error {
  readonly status: number;
  readonly problem: Problem;

  constructor(status: number, problem: Problem) {
    super(describeProblem(status, problem));
    this.name = "ApiError";
    this.status = status;
    this.problem = problem;
  }
}

/** A readable German message for an error answer. */
export function describeProblem(status: number, problem: Problem): string {
  if (problem.detail) return problem.detail;
  switch (status) {
    case 401: return "Nicht angemeldet.";
    case 403: return "Dafür fehlt die Berechtigung.";
    case 404: return "Nicht gefunden.";
    case 409: return problem.title ?? "Konflikt mit dem aktuellen Zustand.";
    default: return problem.title ?? `Fehler ${status}`;
  }
}

function asProblem(error: unknown): Problem {
  if (error && typeof error === "object") return error as Problem;
  if (typeof error === "string" && error.length > 0) return { detail: error };
  return {};
}

/** Unwraps an openapi-fetch result: the data, or an [ApiError]. */
export async function call<T>(request: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  let result: { data?: T; error?: unknown; response: Response };
  try {
    result = await request;
  } catch {
    throw new ApiError(0, { detail: "Die API ist nicht erreichbar." });
  }
  if (!result.response.ok) throw new ApiError(result.response.status, asProblem(result.error));
  return result.data as T;
}
