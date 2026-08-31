/** Data-directory layout, the committed manifest, and the per-host sync state. */

import { err, map, ok, type Result } from "./result.ts";
import { Task } from "./task.ts";
import { configError, describe, type DotError } from "./errors.ts";
import { envGet, homeDir, normalize, toSlash } from "./path.ts";
import { readTextIfExists, writeText } from "./fs.ts";

export type Layout = {
  /** User home directory, slash-normalized. */
  readonly home: string;
  /** Data root: $DOT_HOME, or ~/.dot. */
  readonly root: string;
  /** The git clone holding manifest and files. */
  readonly repo: string;
  /** Tracked file contents, laid out by repo path. */
  readonly filesDir: string;
  readonly manifestPath: string;
  /** Per-host state; lives outside the repo so it is never committed. */
  readonly statePath: string;
};

export const layout = (): Result<Layout, DotError> =>
  map(homeDir(), (home) => {
    const envRoot = envGet("DOT_HOME");
    const root = envRoot !== null && envRoot !== ""
      ? normalize(toSlash(envRoot))
      : home + "/.dot";
    return {
      home,
      root,
      repo: root + "/repo",
      filesDir: root + "/repo/files",
      manifestPath: root + "/repo/dot.json",
      statePath: root + "/state.json",
    };
  });

/** Committed at the repo root as dot.json; shared by every host. */
export type Manifest = {
  readonly version: 1;
  /** Repo path under files/ -> portable target path ("~/..." or absolute). */
  readonly files: Readonly<Record<string, string>>;
};

/** Per-host record of the content hash both sides held after the last sync. */
export type SyncState = {
  readonly version: 1;
  readonly files: Readonly<Record<string, string>>;
};

export const emptyManifest: Manifest = { version: 1, files: {} };
export const emptyState: SyncState = { version: 1, files: {} };

const decodeDoc = (
  text: string,
  what: string,
): Result<{ version: 1; files: Record<string, string> }, DotError> => {
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch (u) {
    return err(configError(`${what}: invalid JSON: ${describe(u)}`));
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) {
    return err(configError(`${what}: must be a JSON object`));
  }
  const doc = parsed as Record<string, unknown>;
  if (doc.version !== 1) {
    return err(configError(
      `${what}: unsupported version ${JSON.stringify(doc.version)}`,
    ));
  }
  const files = doc.files;
  if (typeof files !== "object" || files === null || Array.isArray(files)) {
    return err(configError(`${what}: "files" must be an object`));
  }
  const out: Record<string, string> = {};
  for (const [key, value] of Object.entries(files)) {
    if (typeof value !== "string") {
      return err(configError(
        `${what}: entry ${JSON.stringify(key)} must map to a string path`,
      ));
    }
    out[key] = value;
  }
  return ok({ version: 1, files: out });
};

const sortedFiles = (
  files: Readonly<Record<string, string>>,
): Record<string, string> => {
  const out: Record<string, string> = {};
  for (const key of Object.keys(files).sort()) {
    const value = files[key];
    if (value !== undefined) out[key] = value;
  }
  return out;
};

const serialize = (doc: Manifest | SyncState): string =>
  JSON.stringify(
    { version: doc.version, files: sortedFiles(doc.files) },
    null,
    2,
  ) +
  "\n";

export const loadManifest = (l: Layout): Task<Manifest, DotError> =>
  readTextIfExists(l.manifestPath).andThen((text) =>
    text === null
      ? Task.fail<DotError, Manifest>(configError(
        `no manifest at ${l.manifestPath} — run: dot bind <repo>`,
      ))
      : Task.fromResult(decodeDoc(text, "dot.json"))
  );

export const saveManifest = (l: Layout, m: Manifest): Task<void, DotError> =>
  writeText(l.manifestPath, serialize(m));

/** An unreadable or invalid state file degrades to the empty state: it is a cache. */
export const loadState = (l: Layout): Task<SyncState, DotError> =>
  readTextIfExists(l.statePath)
    .map((text) => {
      if (text === null) return emptyState;
      const decoded = decodeDoc(text, "state.json");
      return decoded.ok ? decoded.value : emptyState;
    })
    .orElse(() => Task.of(emptyState));

export const saveState = (l: Layout, s: SyncState): Task<void, DotError> =>
  writeText(l.statePath, serialize(s));

export const hostLabel = (): string => {
  try {
    return Deno.hostname();
  } catch {
    return envGet("HOSTNAME") ?? envGet("COMPUTERNAME") ?? "unknown-host";
  }
};
