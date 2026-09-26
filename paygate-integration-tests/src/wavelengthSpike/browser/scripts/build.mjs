import { cp, mkdir, rm } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import { build } from "esbuild";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const output = resolve(root, "build/public");
await rm(output, { recursive: true, force: true });
await mkdir(resolve(output, "assets"), { recursive: true });
await cp(resolve(root, "public/index.html"), resolve(output, "index.html"));
await cp(resolve(root, "public/live.html"), resolve(output, "live.html"));
await cp(resolve(root, "public/setup.html"), resolve(output, "setup.html"));
await cp(
  resolve(root, "node_modules/@lightninglabs/wavelength-web/dist/wavewalletdk-worker.js"),
  resolve(output, "assets/wavelength-worker.js"),
);
await build({
  entryPoints: [resolve(root, "src/browser-entry.ts"), resolve(root, "src/live-entry.ts"), resolve(root, "src/setup-entry.ts")],
  outdir: resolve(output, "assets"),
  bundle: true,
  format: "esm",
  platform: "browser",
  target: ["chrome120"],
  sourcemap: false,
  minify: false,
  legalComments: "none",
  logLevel: "warning",
});
