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
  loadManifest,
  loadState,
  resolveLayout,
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
const decide = (facts: Facts): Detected => {
  if (facts.repoHash === null && facts.hostHash === null) return "missing";
  if (facts.repoHash === null) return "toRepo";
  if (facts.hostHash === null) return "toHost";
  if (facts.repoHash === facts.hostHash) return "clean";
  if (facts.baseHash === facts.repoHash) return "toRepo";
  if (facts.baseHash === facts.hostHash) return "toHost";
  return "conflict";
};

const hashOrNull = (
  bytes: Uint8Array | null,
): Task<string | null, IoError> =>
  bytes === null
    ? Task.of<string | null>(null)
    : sha256(bytes).map((h): string | null => h);

const gather = (
  layout: Layout,
  state: SyncState,
  repoPath: string,
  target: string,
): Task<Facts, IoError> => {
  const hostPath = expandTarget(target, layout.home);
  return readBytesIfExists(hostPath).flatMap((host) =>
    readBytesIfExists(layout.filesDir + "/" + repoPath).flatMap((repo) =>
      hashOrNull(host).flatMap((hostHash) =>
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
  (facts: Facts) => (plan: Plan, hash: string | null): Outcome => ({
    plan,
    repoPath: facts.repoPath,
    target: facts.target,
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
    const count = Deno.stdin.readSync(buf);
    if (count === null) {
      if (bytes.length === 0) return null;
      break;
    }
    const byte = buf[0];
    if (count === 0 || byte === undefined) continue;
    if (byte === 10) break;
    if (byte !== 13) bytes.push(byte);
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
  facts: Facts,
  repoFile: string,
): Task<string | null, GitError | IoError> =>
  gitInteractive(null, [
    "difftool",
    "-y",
    "--no-index",
    facts.hostPath,
    repoFile,
  ])
    .flatMap(() => readBytesIfExists(facts.hostPath))
    .flatMap((host) =>
      readBytesIfExists(repoFile).flatMap(
        (repo): Task<string | null, IoError> =>
          host === null || repo === null
            ? Task.of<string | null>(null)
            : sha256(host).flatMap((hostHash) =>
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
  facts: Facts,
  repoFile: string,
  force: boolean,
): Task<Outcome, IoError | GitError> => {
  const outcome = outcomeFor(facts);
  const keepLocal = copyFile(facts.hostPath, repoFile).map(() =>
    outcome("conflict", facts.hostHash)
  );
  if (force || !Deno.stdin.isTerminal()) return keepLocal;
  return askChoice(facts.target).flatMap(
    (choice): Task<Outcome, IoError | GitError> =>
      matchValue(choice, {
        local: () => keepLocal,
        repo: () =>
          copyFile(repoFile, facts.hostPath).map(() =>
            outcome("conflictRepo", facts.repoHash)
          ),
        tool: () =>
          mergeInTool(facts, repoFile).flatMap(
            (merged): Task<Outcome, IoError | GitError> =>
              merged === null
                ? resolveConflict(facts, repoFile, false)
                : Task.of<Outcome>(outcome("merged", merged)),
          ),
        skip: () => Task.of<Outcome>(outcome("skipped", facts.baseHash)),
      }),
  );
};

const apply = (
  layout: Layout,
  force: boolean,
  facts: Facts,
): Task<Outcome, IoError | GitError> => {
  const repoFile = layout.filesDir + "/" + facts.repoPath;
  const outcome = outcomeFor(facts);
  return matchValue(decide(facts), {
    clean: () => Task.of<Outcome>(outcome("clean", facts.hostHash)),
    missing: () => Task.of<Outcome>(outcome("missing", null)),
    toHost: () =>
      copyFile(repoFile, facts.hostPath).map(() =>
        outcome("toHost", facts.repoHash)
      ),
    toRepo: () =>
      copyFile(facts.hostPath, repoFile).map(() =>
        outcome("toRepo", facts.hostHash)
      ),
    conflict: () => resolveConflict(facts, repoFile, force),
  });
};

const line = (outcome: Outcome, recover: string | null): string | null =>
  matchValue(outcome.plan, {
    clean: () => null,
    toRepo: () => `host -> repo  ${outcome.target}`,
    toHost: () => `repo -> host  ${outcome.target}`,
    conflict: () =>
      `host -> repo  ${outcome.target} (both sides changed: host copy kept)` +
      (recover === null ? "" : `\n  overwritten repo copy: ${recover}`),
    conflictRepo: () =>
      `repo -> host  ${outcome.target} (both sides changed: repo copy kept, host copy overwritten)`,
    merged: () =>
      `merged        ${outcome.target} (diff tool result kept on both sides)`,
    skipped: () =>
      `skipped       ${outcome.target} (conflict unresolved; rerun dot sync, or -f to keep the host copy)`,
    missing: () =>
      `missing       ${outcome.target} (gone on host and in repo; dot remove to untrack)`,
  });

/** Pulls with rebase; an empty remote (nothing pushed yet) counts as up to date. */
const pull = (layout: Layout, branch: string): Task<boolean, GitError> =>
  gitRaw(layout.repo, ["pull", "--rebase", "--autostash", "origin", branch])
    .flatMap((out): Task<boolean, GitError> => {
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

const requireBound = (layout: Layout): Task<Layout, IoError | ConfigError> =>
  stat(layout.repo + "/.git").flatMap((info): Task<Layout, ConfigError> =>
    info === null
      ? Task.fail(configError("not bound — run: dot bind <repo>"))
      : git(layout.repo, ["remote", "get-url", "origin"]).mapErr(() =>
        configError("no remote configured — run: dot bind <repo>")
      ).map(() => layout)
  );

const summarize = (
  layout: Layout,
  outcomes: readonly Outcome[],
  committed: boolean,
  pushWarning: string | null,
  preSync: string | null,
): string => {
  const repoDisplay = contractTarget(layout.repo, layout.home);
  const fileLines = outcomes.flatMap((outcome) => {
    const spec = `${preSync}:files/${outcome.repoPath}`;
    const recover = outcome.plan === "conflict" && preSync !== null
      ? `git -C ${repoDisplay} show ${/\s/.test(spec) ? `"${spec}"` : spec}`
      : null;
    const rendered = line(outcome, recover);
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
  Task.fromResult(resolveLayout()).flatMap((layout) =>
    requireBound(layout)
      .flatMap(() => git(layout.repo, ["symbolic-ref", "--short", "HEAD"]))
      .flatMap((branch) => pull(layout, branch))
      .flatMap(() => loadManifest(layout))
      .flatMap((manifest) =>
        loadState(layout).flatMap((state) => {
          const entries = Object.entries(manifest.files)
            .sort(([, a], [, b]) => a < b ? -1 : a > b ? 1 : 0);
          return Task.traverse(
            entries,
            ([repoPath, target]) =>
              gather(layout, state, repoPath, target).flatMap((facts) =>
                apply(layout, force, facts)
              ),
          ).flatMap((outcomes) => {
            const hashes = Object.fromEntries(
              outcomes.flatMap((o) =>
                o.hash === null ? [] : [[o.repoPath, o.hash] as const]
              ),
            );
            return saveState(layout, { version: 1, files: hashes })
              .flatMap(() =>
                commitIfChanged(layout.repo, `dot: sync from ${hostLabel()}`)
              )
              .flatMap((committed) => {
                // The sync commit's parent holds the repo copies that conflicts
                // overwrote; its hash pins the printed retrieval command.
                const preSync = committed &&
                    outcomes.some((o) => o.plan === "conflict")
                  ? git(layout.repo, ["rev-parse", "--short", "HEAD^"])
                    .map((h): string | null => h)
                    .orElse(() => Task.of<string | null>(null))
                  : Task.of<string | null>(null);
                return preSync.flatMap((ref) =>
                  pushBestEffort(layout.repo).map((warning) =>
                    summarize(layout, outcomes, committed, warning, ref)
                  )
                );
              });
          });
        })
      )
  );
