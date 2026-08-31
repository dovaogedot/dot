/** Git invocations wrapped as Tasks. Git is the only external dependency. */

import { Task } from "./task.ts";
import { describe, type DotError } from "./errors.ts";

export type GitOutput = {
  readonly code: number;
  readonly stdout: string;
  readonly stderr: string;
};

/** Runs git with the given args; fails only if git itself cannot be spawned. */
export const gitRaw = (
  cwd: string | null,
  args: readonly string[],
): Task<GitOutput, DotError> =>
  Task.attempt(async () => {
    const command = new Deno.Command("git", {
      args: [...args],
      cwd: cwd ?? undefined,
      stdout: "piped",
      stderr: "piped",
      stdin: "null",
    });
    const { code, stdout, stderr } = await command.output();
    const dec = new TextDecoder();
    return { code, stdout: dec.decode(stdout), stderr: dec.decode(stderr) };
  }, (u) => ({
    kind: "git",
    args,
    message: u instanceof Deno.errors.NotFound
      ? "git executable not found on PATH"
      : describe(u),
  }));

/** Runs git, failing on a non-zero exit; resolves to trimmed stdout. */
export const git = (
  cwd: string | null,
  args: readonly string[],
): Task<string, DotError> =>
  gitRaw(cwd, args).andThen((out) =>
    out.code === 0 ? Task.of<string, DotError>(out.stdout.trim()) : Task.fail<
      DotError,
      string
    >({
      kind: "git",
      args,
      message: out.stderr.trim() || out.stdout.trim() ||
        `exit code ${out.code}`,
    })
  );

/** Stages everything and commits if the tree changed; resolves to whether a commit was made. */
export const commitIfChanged = (
  repo: string,
  message: string,
): Task<boolean, DotError> =>
  git(repo, ["add", "-A"])
    .andThen(() => git(repo, ["status", "--porcelain"]))
    .andThen((status) =>
      status === "" ? Task.of<boolean, DotError>(false) : git(repo, [
        "commit",
        "-m",
        message,
      ]).map(() => true)
    );

/** Pushes HEAD to origin; a failure degrades to a warning line instead of an error. */
export const pushBestEffort = (repo: string): Task<string | null, DotError> =>
  git(repo, ["push", "origin", "HEAD"])
    .map((): string | null => null)
    .orElse((e) =>
      Task.of<string | null, DotError>(
        `warning: push failed, the commit stays local until the next sync: ${
          e.message.split("\n")[0] ?? ""
        }`,
      )
    );
