import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => cleanup());

// jsdom lacks what Mantine uses for color scheme and layout
window.matchMedia ??= (query: string) => ({
  matches: false, media: query, onchange: null,
  addListener: () => {}, removeListener: () => {}, addEventListener: () => {}, removeEventListener: () => {}, dispatchEvent: () => false,
});
globalThis.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} };
