#!/usr/bin/env node
// Runs the native polio binary for this machine. The binary lives in the optional dependency
// polio-<platform>-<arch>; npm installs only the one that matches the machine.
"use strict";

const { spawnSync } = require("node:child_process");

const pkg = `polio-${process.platform}-${process.arch}`;
let bin;
try {
  bin = require.resolve(`${pkg}/bin/polio`);
} catch {
  console.error(`polio: no binary for ${process.platform}-${process.arch} (package ${pkg} is not installed)`);
  process.exit(1);
}

const run = spawnSync(bin, process.argv.slice(2), { stdio: "inherit" });
if (run.error) {
  console.error(`polio: ${run.error.message}`);
  process.exit(1);
}
if (run.signal) process.kill(process.pid, run.signal);
process.exit(run.status === null ? 1 : run.status);
