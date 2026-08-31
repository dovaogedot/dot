/** Typed failures surfaced by the CLI. */

import { matchTag } from "./match.ts";

export type UsageError = { readonly kind: "usage"; readonly message: string };

export type IoError = {
  readonly kind: "io";
  readonly op: string;
  readonly path: string;
  readonly message: string;
};

export type GitError = {
  readonly kind: "git";
  readonly args: readonly string[];
  readonly message: string;
};

export type ConfigError = {
  readonly kind: "config";
  readonly message: string;
};

/** The full union; each function signature carries only the variants it can produce. */
export type DotError = UsageError | IoError | GitError | ConfigError;

export const usageError = (message: string): UsageError => ({
  kind: "usage",
  message,
});

export const configError = (message: string): ConfigError => ({
  kind: "config",
  message,
});

export const ioError = (op: string, path: string, cause: unknown): IoError => ({
  kind: "io",
  op,
  path,
  message: describe(cause),
});

export const describe = (u: unknown): string =>
  u instanceof Error ? u.message : String(u);

export const renderError = (e: DotError): string =>
  matchTag(e, {
    usage: (u) => `dot: ${u.message}`,
    io: (u) => `dot: ${u.op} ${u.path}: ${u.message}`,
    git: (u) =>
      `dot: git ${u.args.join(" ")}\n  ${u.message.split("\n").join("\n  ")}`,
    config: (u) => `dot: ${u.message}`,
  });
