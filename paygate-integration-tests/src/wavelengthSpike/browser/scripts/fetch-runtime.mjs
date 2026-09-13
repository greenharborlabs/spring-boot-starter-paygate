import { createHash } from "node:crypto";
import { execFile } from "node:child_process";
import { mkdtemp, mkdir, readFile, rm, writeFile, cp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

import {
  RUNTIME_ARCHIVE_SHA256,
  RUNTIME_ARCHIVE_URL,
  RUNTIME_ASSETS,
  RUNTIME_VERSION,
} from "./runtime-manifest.mjs";

const execute = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const destination = resolve(root, `build/runtime-${RUNTIME_VERSION}`);
const temporary = await mkdtemp(join(tmpdir(), "paygate-wavelength-runtime-"));
try {
  const response = await fetch(RUNTIME_ARCHIVE_URL, { redirect: "follow" });
  if (!response.ok) {
    throw new Error("Pinned Wavelength runtime download failed");
  }
  const archive = new Uint8Array(await response.arrayBuffer());
  if (sha256(archive) !== RUNTIME_ARCHIVE_SHA256) {
    throw new Error("Pinned Wavelength runtime archive hash mismatch");
  }
  const archivePath = join(temporary, "runtime.tar.gz");
  const unpacked = join(temporary, "unpacked");
  await writeFile(archivePath, archive);
  await mkdir(unpacked);
  await execute("tar", ["-xzf", archivePath, "-C", unpacked]);
  await rm(destination, { recursive: true, force: true });
  await mkdir(destination, { recursive: true });
  for (const [name, expected] of Object.entries(RUNTIME_ASSETS)) {
    const { stdout } = await execute("find", [unpacked, "-type", "f", "-name", name]);
    const matches = stdout.trim().split("\n").filter(Boolean);
    if (matches.length !== 1) {
      throw new Error("Pinned Wavelength runtime asset layout mismatch");
    }
    const bytes = await readFile(matches[0]);
    if (sha256(bytes) !== expected) {
      throw new Error("Pinned Wavelength runtime asset hash mismatch");
    }
    await cp(matches[0], join(destination, name));
  }
  console.log(destination);
} finally {
  await rm(temporary, { recursive: true, force: true });
}

function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}
