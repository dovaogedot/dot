/**
 * Path handling on portable, slash-separated strings. Paths are normalized to
 * forward slashes everywhere; Windows file APIs accept them directly.
 */

import { Err, Ok, type Result } from "./result.ts";
import { ConfigError } from "./errors.ts";

/** Reads an environment variable, treating a denied permission as unset. */
export const envGet = (name: string): string | null => {
  try {
    return Deno.env.get(name) ?? null;
  } catch {
    return null;
  }
};

export const toSlash = (p: string): string => p.replaceAll("\\", "/");

export const isAbsolute = (p: string): boolean =>
  p.startsWith("/") || /^[A-Za-z]:\//.test(p);

/** Collapses "." and ".." segments and duplicate separators; expects slash-separated input. */
export const normalize = (path: string): string => {
  const drive = /^[A-Za-z]:/.test(path) ? path.slice(0, 2) : "";
  const rest = drive === "" ? path : path.slice(2);
  const abs = rest.startsWith("/");
  const parts = rest.split("/").reduce<string[]>((out, part) => {
    if (part === "" || part === ".") return out;
    if (part !== "..") return [...out, part];
    const last = out[out.length - 1];
    if (last !== undefined && last !== "..") return out.slice(0, -1);
    return abs ? out : [...out, ".."];
  }, []);
  return drive + (abs ? "/" : "") + parts.join("/");
};

export const join = (...parts: readonly string[]): string =>
  normalize(parts.map(toSlash).join("/"));

export const dirname = (path: string): string => {
  const i = path.lastIndexOf("/");
  if (i < 0) return ".";
  if (i === 0) return "/";
  return path.slice(0, i);
};

export const homeDir = (): Result<string, ConfigError> => {
  const raw = envGet("HOME") ?? envGet("USERPROFILE");
  if (raw === null || raw === "") {
    return new Err(
      new ConfigError(
        "cannot locate the home directory: HOME / USERPROFILE is unset or unreadable",
      ),
    );
  }
  return new Ok(normalize(toSlash(raw)));
};

/** Resolves user input to an absolute slash-separated path, expanding a leading "~". */
export const resolvePath = (raw: string, home: string): string => {
  const s = toSlash(raw);
  if (s === "~") return home;
  if (s.startsWith("~/")) return join(home, s.slice(2));
  if (isAbsolute(s)) return normalize(s);
  return join(toSlash(Deno.cwd()), s);
};

/** Rewrites an absolute path under the home directory to the portable "~/..." form. */
export const contractTarget = (abs: string, home: string): string => {
  if (abs === home) return "~";
  if (abs.startsWith(home + "/")) return "~/" + abs.slice(home.length + 1);
  return abs;
};

/** Expands a portable target ("~/..." or absolute) to an absolute path on this host. */
export const expandTarget = (portable: string, home: string): string => {
  if (portable === "~") return home;
  if (portable.startsWith("~/")) return join(home, portable.slice(2));
  return normalize(toSlash(portable));
};

/** Maps a portable target path to the file's path inside the repo's files/ tree. */
export const repoPathFor = (portable: string): string => {
  if (portable.startsWith("~/")) return portable.slice(2);
  return "_root/" + portable.replace(/^\//, "").replace(":", "");
};
