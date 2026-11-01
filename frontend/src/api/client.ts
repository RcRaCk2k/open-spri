import createClient from "openapi-fetch";
import type { paths } from "./schema";

/**
 * Typed client for the public REST API. The types are generated from docs/api/openapi.json
 * (`pnpm generate:api`); never hand-write endpoint URLs (CLAUDE.md, principle 4).
 */
export const api = createClient<paths>({ baseUrl: "/" });

export type About = paths["/api/v1/about"]["get"]["responses"]["200"]["content"]["application/json"];
