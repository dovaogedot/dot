// Builds the npm packages for one release: a package per platform that holds its binary, and the
// main package whose shim picks the right one. Usage: node npm/assemble.mjs <version> <artifacts> <out>
// where <artifacts> holds one directory per build artifact, polio-<platform>/polio.
import { chmodSync, copyFileSync, cpSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";

const [version, artifacts, out] = process.argv.slice(2);
if (!version || !artifacts || !out) {
  console.error("usage: node npm/assemble.mjs <version> <artifacts dir> <out dir>");
  process.exit(2);
}

const TARGETS = {
  "linux-x64": { os: "linux", cpu: "x64" },
  "linux-arm64": { os: "linux", cpu: "arm64" },
  "darwin-arm64": { os: "darwin", cpu: "arm64" },
  "darwin-x64": { os: "darwin", cpu: "x64" },
};

const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));
const writeJson = (path, value) => writeFileSync(path, JSON.stringify(value, null, 2) + "\n");

const platformTemplate = readJson("npm/platform/package.json");
for (const [target, { os, cpu }] of Object.entries(TARGETS)) {
  const dir = join(out, `polio-${target}`);
  mkdirSync(join(dir, "bin"), { recursive: true });
  copyFileSync(join(artifacts, `polio-${target}`, "polio"), join(dir, "bin", "polio"));
  chmodSync(join(dir, "bin", "polio"), 0o755);
  writeJson(join(dir, "package.json"), { ...platformTemplate, name: `polio-${target}`, version, os: [os], cpu: [cpu] });
}

const mainDir = join(out, "polio");
cpSync("npm/polio", mainDir, { recursive: true });
const main = readJson("npm/polio/package.json");
main.version = version;
main.optionalDependencies = Object.fromEntries(Object.keys(TARGETS).map((t) => [`polio-${t}`, version]));
writeJson(join(mainDir, "package.json"), main);
copyFileSync("README.md", join(mainDir, "README.md"));
copyFileSync("LICENSE", join(mainDir, "LICENSE"));
console.log(`assembled ${Object.keys(TARGETS).length + 1} packages in ${out}`);
