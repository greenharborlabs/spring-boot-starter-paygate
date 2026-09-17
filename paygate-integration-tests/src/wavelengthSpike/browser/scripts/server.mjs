import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import { readFile, stat } from "node:fs/promises";
import { createServer } from "node:http";
import { dirname, extname, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

import { RUNTIME_ASSETS } from "./runtime-manifest.mjs";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const publicRoot = resolve(root, "build/public");

export async function startHarnessServer(runtimeDirectory, port = 0, live = false) {
  const runtimeRoot = resolve(runtimeDirectory);
  await validateRuntime(runtimeRoot);
  const server = createServer((request, response) => {
    void serve(request.url ?? "/", response, runtimeRoot, live);
  });
  await new Promise((resolveListening, reject) => {
    server.once("error", reject);
    server.listen(port, "127.0.0.1", resolveListening);
  });
  const address = server.address();
  if (address === null || typeof address === "string") {
    throw new Error("Harness server address is unavailable");
  }
  return {
    origin: `http://127.0.0.1:${address.port}`,
    close: () =>
      new Promise((resolveClose, reject) => {
        server.close((error) => (error ? reject(error) : resolveClose()));
        server.closeAllConnections();
      }),
  };
}

async function serve(rawUrl, response, runtimeRoot, live) {
  try {
    const url = new URL(rawUrl, "http://127.0.0.1");
    const mapping = mapPath(url.pathname, runtimeRoot, live);
    if (mapping === null) {
      send(response, 404, "text/plain; charset=utf-8", "Not found");
      return;
    }
    const details = await stat(mapping.path);
    if (!details.isFile()) {
      send(response, 404, "text/plain; charset=utf-8", "Not found");
      return;
    }
    setSecurityHeaders(response, live);
    response.setHeader("Content-Type", contentType(mapping.path));
    response.setHeader(
      "Cache-Control",
      mapping.runtime ? "public, max-age=31536000, immutable" : "no-store",
    );
    response.setHeader("Content-Length", details.size);
    response.writeHead(200);
    createReadStream(mapping.path).pipe(response);
  } catch {
    send(response, 404, "text/plain; charset=utf-8", "Not found");
  }
}

function mapPath(pathname, runtimeRoot, live) {
  if (live && pathname === "/live") {
    return { path: resolve(publicRoot, "live.html"), runtime: false };
  }
  if (pathname === "/") {
    return { path: resolve(publicRoot, "index.html"), runtime: false };
  }
  if (pathname.startsWith("/assets/")) {
    return safeMapping(publicRoot, pathname.slice(1), false);
  }
  const prefix = "/runtime/v0.1.1/";
  if (pathname.startsWith(prefix)) {
    return safeMapping(runtimeRoot, pathname.slice(prefix.length), true);
  }
  return null;
}

function safeMapping(base, relative, runtime) {
  let decoded;
  try {
    decoded = decodeURIComponent(relative);
  } catch {
    return null;
  }
  const candidate = resolve(base, decoded);
  if (candidate !== base && !candidate.startsWith(`${base}${sep}`)) {
    return null;
  }
  return { path: candidate, runtime };
}

function setSecurityHeaders(response, live = false) {
  const dependencies = live ? " https://signet.wavelength-rest.lightning.finance https://signet.swapd-rest.lightning.finance https://mempool-signet.testnet.lightningcluster.com" : "";
  response.setHeader(
    "Content-Security-Policy",
    `default-src 'none'; script-src 'self' 'wasm-unsafe-eval'; worker-src 'self'; connect-src 'self'${dependencies}; img-src 'self'; style-src 'self'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'`,
  );
  response.setHeader("Cross-Origin-Embedder-Policy", "require-corp");
  response.setHeader("Cross-Origin-Opener-Policy", "same-origin");
  response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
  response.setHeader("Origin-Agent-Cluster", "?1");
  response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
  response.setHeader("Referrer-Policy", "no-referrer");
  response.setHeader("X-Content-Type-Options", "nosniff");
}

function send(response, status, type, body) {
  setSecurityHeaders(response);
  response.writeHead(status, { "Content-Type": type, "Cache-Control": "no-store" });
  response.end(body);
}

function contentType(path) {
  if (path.endsWith(".wasm")) return "application/wasm";
  if (path.endsWith(".wasm.gz")) return "application/gzip";
  if (extname(path) === ".js") return "text/javascript; charset=utf-8";
  if (extname(path) === ".html") return "text/html; charset=utf-8";
  return "application/octet-stream";
}

async function validateRuntime(runtimeRoot) {
  for (const [name, expected] of Object.entries(RUNTIME_ASSETS)) {
    const bytes = await readFile(resolve(runtimeRoot, name));
    const actual = createHash("sha256").update(bytes).digest("hex");
    if (actual !== expected) {
      throw new Error("Pinned Wavelength runtime asset validation failed");
    }
  }
}
