/** File-system effects wrapped as Tasks. */

import { Task } from "./task.ts";
import { type DotError, ioError } from "./errors.ts";
import { dirname } from "./path.ts";

export const stat = (path: string): Task<Deno.FileInfo | null, DotError> =>
  Task.attempt(async () => {
    try {
      return await Deno.stat(path);
    } catch (u) {
      if (u instanceof Deno.errors.NotFound) return null;
      throw u;
    }
  }, (u) => ioError("stat", path, u));

export const readBytesIfExists = (
  path: string,
): Task<Uint8Array | null, DotError> =>
  Task.attempt(async () => {
    try {
      return await Deno.readFile(path);
    } catch (u) {
      if (u instanceof Deno.errors.NotFound) return null;
      throw u;
    }
  }, (u) => ioError("read", path, u));

export const readTextIfExists = (
  path: string,
): Task<string | null, DotError> =>
  readBytesIfExists(path).map((b) =>
    b === null ? null : new TextDecoder().decode(b)
  );

export const ensureDir = (path: string): Task<void, DotError> =>
  Task.attempt(async () => {
    await Deno.mkdir(path, { recursive: true });
  }, (u) => ioError("mkdir", path, u));

export const writeText = (path: string, text: string): Task<void, DotError> =>
  ensureDir(dirname(path)).andThen(() =>
    Task.attempt(
      () => Deno.writeTextFile(path, text),
      (u) => ioError("write", path, u),
    )
  );

/** Copies content and, on POSIX systems, the permission bits of the source. */
export const copyFile = (src: string, dst: string): Task<void, DotError> =>
  ensureDir(dirname(dst)).andThen(() =>
    Task.attempt(
      () => Deno.copyFile(src, dst),
      (u) => ioError("copy", `${src} -> ${dst}`, u),
    )
  );

export const removeIfExists = (path: string): Task<void, DotError> =>
  Task.attempt(async () => {
    try {
      await Deno.remove(path);
    } catch (u) {
      if (u instanceof Deno.errors.NotFound) return;
      throw u;
    }
  }, (u) => ioError("remove", path, u));

/** Lists every regular file under dir, sorted; skips symlinks and ".git" trees. */
export const walkFiles = (dir: string): Task<string[], DotError> =>
  Task.attempt(async () => {
    const out: string[] = [];
    const visit = async (d: string): Promise<void> => {
      for await (const entry of Deno.readDir(d)) {
        if (entry.name === ".git") continue;
        const p = d + "/" + entry.name;
        if (entry.isDirectory) await visit(p);
        else if (entry.isFile) out.push(p);
      }
    };
    await visit(dir);
    return out.sort();
  }, (u) => ioError("walk", dir, u));

export const sha256 = (bytes: Uint8Array): Task<string, DotError> =>
  Task.attempt(async () => {
    const digest = await crypto.subtle.digest(
      "SHA-256",
      bytes as BufferSource,
    );
    let hex = "";
    for (const b of new Uint8Array(digest)) {
      hex += b.toString(16).padStart(2, "0");
    }
    return hex;
  }, (u) => ioError("hash", "<memory>", u));
