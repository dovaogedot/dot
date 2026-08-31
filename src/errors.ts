/** Typed failures surfaced by the CLI. */

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

export const renderError = (e: DotError): string => {
  switch (e.kind) {
    case "usage":
      return `dot: ${e.message}`;
    case "io":
      return `dot: ${e.op} ${e.path}: ${e.message}`;
    case "git":
      return `dot: git ${e.args.join(" ")}\n  ${
        e.message.split("\n").join("\n  ")
      }`;
    case "config":
      return `dot: ${e.message}`;
  }
};
