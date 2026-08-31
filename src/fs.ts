/** File-system effects wrapped as Tasks. */

import { Task } from "./task.ts";
import { type IoError, ioError } from "./errors.ts";
import { dirname } from "./path.ts";

export const stat = (path: string): Task<Deno.FileInfo | null, IoError> =>
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
): Task<Uint8Array | null, IoError> =>
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
): Task<string | null, IoError> =>
  readBytesIfExists(path).map((b) =>
    b === null ? null : new TextDecoder().decode(b)
  );

export const ensureDir = (path: string): Task<void, IoError> =>
  Task.attempt(async () => {
    await Deno.mkdir(path, { recursive: true });
  }, (u) => ioError("mkdir", path, u));

export const writeText = (path: string, text: string): Task<void, IoError> =>
  ensureDir(dirname(path)).flatMap(() =>
    Task.attempt(
      () => Deno.writeTextFile(path, text),
      (u) => ioError("write", path, u),
    )
  );

/** Copies content and, on POSIX systems, the permission bits of the source. */
export const copyFile = (src: string, dst: string): Task<void, IoError> =>
  ensureDir(dirname(dst)).flatMap(() =>
    Task.attempt(
      () => Deno.copyFile(src, dst),
      (u) => ioError("copy", `${src} -> ${dst}`, u),
    )
  );

export const removeIfExists = (path: string): Task<void, IoError> =>
  Task.attempt(async () => {
    try {
      await Deno.remove(path);
    } catch (u) {
      if (u instanceof Deno.errors.NotFound) return;
      throw u;
    }
  }, (u) => ioError("remove", path, u));

const visit = (dir: string): Promise<string[]> =>
  Array.fromAsync(Deno.readDir(dir)).then((entries) =>
    Promise.all(
      entries
        .filter((entry) => entry.name !== ".git")
        .map((entry) =>
          entry.isDirectory
            ? visit(dir + "/" + entry.name)
            : Promise.resolve(entry.isFile ? [dir + "/" + entry.name] : [])
        ),
    ).then((nested) => nested.flat())
  );

/** Lists every regular file under dir, sorted; skips symlinks and ".git" trees. */
export const walkFiles = (dir: string): Task<string[], IoError> =>
  Task.attempt(
    () => visit(dir).then((files) => files.sort()),
    (u) => ioError("walk", dir, u),
  );

export const sha256 = (bytes: Uint8Array): Task<string, IoError> =>
  Task.attempt(async () => {
    const digest = await crypto.subtle.digest("SHA-256", bytes as BufferSource);
    return Array.from(
      new Uint8Array(digest),
      (b) => b.toString(16).padStart(2, "0"),
    ).join("");
  }, (u) => ioError("hash", "<memory>", u));
