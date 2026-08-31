/** Typed failures surfaced by the CLI. */

import { matchTag } from "./match.ts";

export class UsageError {
  readonly kind = "usage";
  constructor(readonly message: string) {}
}

export class IoError {
  readonly kind = "io";
  readonly message: string;
  constructor(readonly op: string, readonly path: string, cause: unknown) {
    this.message = describe(cause);
  }
}

export class GitError {
  readonly kind = "git";
  constructor(
    readonly args: readonly string[],
    readonly message: string,
  ) {}
}

export class ConfigError {
  readonly kind = "config";
  constructor(readonly message: string) {}
}

/** The full union; each function signature carries only the variants it can produce. */
export type DotError = UsageError | IoError | GitError | ConfigError;

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
