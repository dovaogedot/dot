/** Typed failures surfaced by the CLI. */

import { matchTag } from "./match.ts";

export type DotError =
  | { readonly kind: "usage"; readonly message: string }
  | {
    readonly kind: "io";
    readonly op: string;
    readonly path: string;
    readonly message: string;
  }
  | {
    readonly kind: "git";
    readonly args: readonly string[];
    readonly message: string;
  }
  | { readonly kind: "config"; readonly message: string };

export const usageError = (message: string): DotError => ({
  kind: "usage",
  message,
});

export const configError = (message: string): DotError => ({
  kind: "config",
  message,
});

export const ioError = (
  op: string,
  path: string,
  cause: unknown,
): DotError => ({
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
