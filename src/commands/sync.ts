/** dot sync: pull, reconcile every tracked file with the host, commit, push. */

import { Task } from "../task.ts";
import {
  type ConfigError,
  configError,
  type GitError,
  type IoError,
  ioError,
} from "../errors.ts";
import { matchValue } from "../match.ts";
import { contractTarget, expandTarget } from "../path.ts";
import {
  hostLabel,
  type Layout,
  layout,
  loadManifest,
  loadState,
  saveState,
  type SyncState,
} from "../config.ts";
import { copyFile, readBytesIfExists, sha256, stat } from "../fs.ts";
import {
  commitIfChanged,
  git,
  gitInteractive,
  gitRaw,
  pushBestEffort,
} from "../git.ts";

type Facts = {
  readonly repoPath: string;
  readonly target: string;
  readonly hostPath: string;
  readonly hostHash: string | null;
  readonly repoHash: string | null;
  readonly baseHash: string | null;
};

/** What the three-way comparison detects for one tracked file. */
type Detected = "clean" | "toRepo" | "toHost" | "conflict" | "missing";

/**
 * How one tracked file was handled. A both-sides-changed file resolves into
 * "conflict" (host copy kept), "conflictRepo" (repo copy kept), "merged"
 * (a diff tool left both sides identical), or "skipped" (both sides left
 * untouched).
 */
type Plan = Detected | "conflictRepo" | "merged" | "skipped";

/**
 * Three-way comparison against the hash recorded at the last sync: an
 * unchanged side yields to the changed one; a change on both sides is a
 * conflict.
 */
const decide = (f: Facts): Detected => {
  if (f.repoHash === null && f.hostHash === null) return "missing";
  if (f.repoHash === null) return "toRepo";
  if (f.hostHash === null) return "toHost";
  if (f.repoHash === f.hostHash) return "clean";
  if (f.baseHash === f.repoHash) return "toRepo";
  if (f.baseHash === f.hostHash) return "toHost";
  return "conflict";
};

const hashOrNull = (
  bytes: Uint8Array | null,
): Task<string | null, IoError> =>
  bytes === null
    ? Task.of<string | null>(null)
    : sha256(bytes).map((h): string | null => h);

const gather = (
  l: Layout,
  state: SyncState,
  repoPath: string,
  target: string,
): Task<Facts, IoError> => {
  const hostPath = expandTarget(target, l.home);
  return readBytesIfExists(hostPath).andThen((host) =>
    readBytesIfExists(l.filesDir + "/" + repoPath).andThen((repo) =>
      hashOrNull(host).andThen((hostHash) =>
        hashOrNull(repo).map((repoHash): Facts => ({
          repoPath,
          target,
          hostPath,
          hostHash,
          repoHash,
          baseHash: state.files[repoPath] ?? null,
        }))
      )
    )
  );
};

type Outcome = {
  readonly plan: Plan;
  readonly repoPath: string;
  readonly target: string;
  /** Hash both sides hold after the action; null drops the state entry. */
  readonly hash: string | null;
};

const outcomeFor =
  (f: Facts) => (plan: Plan, hash: string | null): Outcome => ({
    plan,
    repoPath: f.repoPath,
    target: f.target,
    hash,
  });

const CHOICE_BY_INPUT = {
  l: "local",
  local: "local",
  r: "repo",
  repo: "repo",
  d: "tool",
  diff: "tool",
  tool: "tool",
  s: "skip",
  skip: "skip",
} as const;

type Choice = (typeof CHOICE_BY_INPUT)[keyof typeof CHOICE_BY_INPUT];

const CHOICES: Readonly<Record<string, Choice>> = CHOICE_BY_INPUT;

const menu = (target: string): string =>
  `conflict: ${target} changed both on this host and in the repo
  [l] keep local — the host copy wins; the repo copy stays in git history
  [r] keep repo  — overwrites the host copy
  [d] resolve in diff tool (git difftool)
  [s] skip — leave both sides as they are`;

/**
 * Reads one input line, one byte per read so nothing past the newline is
 * consumed — a later prompt still sees lines the user typed or pasted ahead.
 * Null at end of input.
 */
const readLine = (): string | null => {
  const buf = new Uint8Array(1);
  const bytes: number[] = [];
  for (;;) {
    const n = Deno.stdin.readSync(buf);
    if (n === null) {
      if (bytes.length === 0) return null;
      break;
    }
    const b = buf[0];
    if (n === 0 || b === undefined) continue;
    if (b === 10) break;
    if (b !== 13) bytes.push(b);
  }
  return new TextDecoder().decode(new Uint8Array(bytes));
};

/** Reads one resolution from the terminal; end of input counts as skip. */
const askChoice = (target: string): Task<Choice, IoError> =>
  Task.attempt(() => {
    console.log(menu(target));
    const ask = new TextEncoder().encode("choose [l/r/d/s]: ");
    for (;;) {
      Deno.stdout.writeSync(ask);
      const raw = readLine();
      if (raw === null) return Promise.resolve<Choice>("skip");
      const choice = CHOICES[raw.trim().toLowerCase()];
      if (choice !== undefined) return Promise.resolve(choice);
    }
  }, (u) => ioError("prompt", target, u));

/**
 * Opens git difftool on the host and repo copies; resolves to their common
 * hash when the tool leaves both sides identical, null otherwise.
 */
const mergeInTool = (
  f: Facts,
  repoFile: string,
): Task<string | null, GitError | IoError> =>
  gitInteractive(null, ["difftool", "-y", "--no-index", f.hostPath, repoFile])
    .andThen(() => readBytesIfExists(f.hostPath))
    .andThen((host) =>
      readBytesIfExists(repoFile).andThen(
        (repo): Task<string | null, IoError> =>
          host === null || repo === null
            ? Task.of<string | null>(null)
            : sha256(host).andThen((hostHash) =>
              sha256(repo).map((repoHash): string | null =>
                hostHash === repoHash ? hostHash : null
              )
            ),
      )
    );

/**
 * A both-sides-changed file: --force or a non-terminal stdin keeps the host
 * copy; otherwise the user picks the resolution per file. The host copy is
 * the only side git history cannot restore, so every path that discards it
 * is an explicit choice.
 */
const resolveConflict = (
  f: Facts,
  repoFile: string,
  force: boolean,
): Task<Outcome, IoError | GitError> => {
  const outcome = outcomeFor(f);
  const keepLocal = copyFile(f.hostPath, repoFile).map(() =>
    outcome("conflict", f.hostHash)
  );
  if (force || !Deno.stdin.isTerminal()) return keepLocal;
  return askChoice(f.target).andThen(
    (choice): Task<Outcome, IoError | GitError> =>
      matchValue(choice, {
        local: () => keepLocal,
        repo: () =>
          copyFile(repoFile, f.hostPath).map(() =>
            outcome("conflictRepo", f.repoHash)
          ),
        tool: () =>
          mergeInTool(f, repoFile).andThen(
            (merged): Task<Outcome, IoError | GitError> =>
              merged === null
                ? resolveConflict(f, repoFile, false)
                : Task.of<Outcome>(outcome("merged", merged)),
          ),
        skip: () => Task.of<Outcome>(outcome("skipped", f.baseHash)),
      }),
  );
};

const apply = (
  l: Layout,
  force: boolean,
  f: Facts,
): Task<Outcome, IoError | GitError> => {
  const repoFile = l.filesDir + "/" + f.repoPath;
  const outcome = outcomeFor(f);
  return matchValue(decide(f), {
    clean: () => Task.of<Outcome>(outcome("clean", f.hostHash)),
    missing: () => Task.of<Outcome>(outcome("missing", null)),
    toHost: () =>
      copyFile(repoFile, f.hostPath).map(() => outcome("toHost", f.repoHash)),
    toRepo: () =>
      copyFile(f.hostPath, repoFile).map(() => outcome("toRepo", f.hostHash)),
    conflict: () => resolveConflict(f, repoFile, force),
  });
};

const line = (o: Outcome, recover: string | null): string | null =>
  matchValue(o.plan, {
    clean: () => null,
    toRepo: () => `host -> repo  ${o.target}`,
    toHost: () => `repo -> host  ${o.target}`,
    conflict: () =>
      `host -> repo  ${o.target} (both sides changed: host copy kept)` +
      (recover === null ? "" : `\n  overwritten repo copy: ${recover}`),
    conflictRepo: () =>
      `repo -> host  ${o.target} (both sides changed: repo copy kept, host copy overwritten)`,
    merged: () =>
      `merged        ${o.target} (diff tool result kept on both sides)`,
    skipped: () =>
      `skipped       ${o.target} (conflict unresolved; rerun dot sync, or -f to keep the host copy)`,
    missing: () =>
      `missing       ${o.target} (gone on host and in repo; dot remove to untrack)`,
  });

/** Pulls with rebase; an empty remote (nothing pushed yet) counts as up to date. */
const pull = (l: Layout, branch: string): Task<boolean, GitError> =>
  gitRaw(l.repo, ["pull", "--rebase", "--autostash", "origin", branch])
    .andThen((out): Task<boolean, GitError> => {
      if (out.code === 0) return Task.of(true);
      if (out.stderr.includes("couldn't find remote ref")) {
        return Task.of(false);
      }
      return Task.fail({
        kind: "git",
        args: ["pull", "--rebase", "--autostash", "origin", branch],
        message: out.stderr.trim() || out.stdout.trim() ||
          `exit code ${out.code}`,
      });
    });

const requireBound = (l: Layout): Task<Layout, IoError | ConfigError> =>
  stat(l.repo + "/.git").andThen((info): Task<Layout, ConfigError> =>
    info === null
      ? Task.fail(configError("not bound — run: dot bind <repo>"))
      : git(l.repo, ["remote", "get-url", "origin"]).mapErr(() =>
        configError("no remote configured — run: dot bind <repo>")
      ).map(() => l)
  );

const summarize = (
  l: Layout,
  outcomes: readonly Outcome[],
  committed: boolean,
  pushWarning: string | null,
  preSync: string | null,
): string => {
  const repoDisplay = contractTarget(l.repo, l.home);
  const fileLines = outcomes.flatMap((o) => {
    const spec = `${preSync}:files/${o.repoPath}`;
    const recover = o.plan === "conflict" && preSync !== null
      ? `git -C ${repoDisplay} show ${/\s/.test(spec) ? `"${spec}"` : spec}`
      : null;
    const rendered = line(o, recover);
    return rendered === null ? [] : [rendered];
  });
  const clean = outcomes.filter((o) => o.plan === "clean").length;
  return [
    ...fileLines,
    ...(clean > 0 ? [`up to date: ${clean} file(s)`] : []),
    ...(outcomes.length === 0 ? ["nothing tracked — dot add <path>"] : []),
    ...(pushWarning !== null ? [pushWarning] : committed ? ["pushed"] : []),
  ].join("\n");
};

export const sync = (
  force: boolean,
): Task<string, ConfigError | IoError | GitError> =>
  Task.fromResult(layout()).andThen((l) =>
    requireBound(l)
      .andThen(() => git(l.repo, ["symbolic-ref", "--short", "HEAD"]))
      .andThen((branch) => pull(l, branch))
      .andThen(() => loadManifest(l))
      .andThen((manifest) =>
        loadState(l).andThen((state) => {
          const entries = Object.entries(manifest.files)
            .sort(([, a], [, b]) => a < b ? -1 : a > b ? 1 : 0);
          return Task.traverse(
            entries,
            ([repoPath, target]) =>
              gather(l, state, repoPath, target).andThen((f) =>
                apply(l, force, f)
              ),
          ).andThen((outcomes) => {
            const hashes = Object.fromEntries(
              outcomes.flatMap((o) =>
                o.hash === null ? [] : [[o.repoPath, o.hash] as const]
              ),
            );
            return saveState(l, { version: 1, files: hashes })
              .andThen(() =>
                commitIfChanged(l.repo, `dot: sync from ${hostLabel()}`)
              )
              .andThen((committed) => {
                // The sync commit's parent holds the repo copies that conflicts
                // overwrote; its hash pins the printed retrieval command.
                const preSync = committed &&
                    outcomes.some((o) => o.plan === "conflict")
                  ? git(l.repo, ["rev-parse", "--short", "HEAD^"])
                    .map((h): string | null => h)
                    .orElse(() => Task.of<string | null>(null))
                  : Task.of<string | null>(null);
                return preSync.andThen((ref) =>
                  pushBestEffort(l.repo).map((warning) =>
                    summarize(l, outcomes, committed, warning, ref)
                  )
                );
              });
          });
        })
      )
  );
