/** dot sync: pull, reconcile every tracked file with the host, commit, push. */

import { Task } from "../task.ts";
import { configError, type DotError, ioError } from "../errors.ts";
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
import { copyFile, readBytesIfExists, sha256 } from "../fs.ts";
import {
  commitIfChanged,
  git,
  gitInteractive,
  gitRaw,
  pushBestEffort,
} from "../git.ts";
import { stat } from "../fs.ts";

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
): Task<string | null, DotError> =>
  bytes === null
    ? Task.of<string | null, DotError>(null)
    : sha256(bytes).map((h): string | null => h);

const gather = (
  l: Layout,
  state: SyncState,
  repoPath: string,
  target: string,
): Task<Facts, DotError> => {
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

type Choice = "local" | "repo" | "tool" | "skip";

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
const askChoice = (target: string): Task<Choice, DotError> =>
  Task.attempt(() => {
    console.log(menu(target));
    const ask = new TextEncoder().encode("choose [l/r/d/s]: ");
    for (;;) {
      Deno.stdout.writeSync(ask);
      const raw = readLine();
      if (raw === null) return Promise.resolve<Choice>("skip");
      switch (raw.trim().toLowerCase()) {
        case "l":
        case "local":
          return Promise.resolve<Choice>("local");
        case "r":
        case "repo":
          return Promise.resolve<Choice>("repo");
        case "d":
        case "diff":
        case "tool":
          return Promise.resolve<Choice>("tool");
        case "s":
        case "skip":
          return Promise.resolve<Choice>("skip");
      }
    }
  }, (u) => ioError("prompt", target, u));

/**
 * Opens git difftool on the host and repo copies; resolves to their common
 * hash when the tool leaves both sides identical, null otherwise.
 */
const mergeInTool = (
  f: Facts,
  repoFile: string,
): Task<string | null, DotError> =>
  gitInteractive(null, ["difftool", "-y", "--no-index", f.hostPath, repoFile])
    .andThen(() => readBytesIfExists(f.hostPath))
    .andThen((host) =>
      readBytesIfExists(repoFile).andThen((repo) =>
        host === null || repo === null
          ? Task.of<string | null, DotError>(null)
          : sha256(host).andThen((hostHash) =>
            sha256(repo).map((repoHash): string | null =>
              hostHash === repoHash ? hostHash : null
            )
          )
      )
    );

/**
 * A both-sides-changed file: --force keeps the host copy, a non-terminal
 * stdin skips, and otherwise the user picks the resolution per file. The
 * host copy is the only side git history cannot restore, so every path that
 * discards it is an explicit choice.
 */
const resolveConflict = (
  f: Facts,
  repoFile: string,
  force: boolean,
): Task<Outcome, DotError> => {
  const outcome = outcomeFor(f);
  const keepLocal = copyFile(f.hostPath, repoFile).map(() =>
    outcome("conflict", f.hostHash)
  );
  if (force) return keepLocal;
  if (!Deno.stdin.isTerminal()) {
    return Task.of(outcome("skipped", f.baseHash));
  }
  return askChoice(f.target).andThen((choice) => {
    switch (choice) {
      case "local":
        return keepLocal;
      case "repo":
        return copyFile(repoFile, f.hostPath).map(() =>
          outcome("conflictRepo", f.repoHash)
        );
      case "tool":
        return mergeInTool(f, repoFile).andThen((merged) =>
          merged === null
            ? resolveConflict(f, repoFile, false)
            : Task.of(outcome("merged", merged))
        );
      case "skip":
        return Task.of(outcome("skipped", f.baseHash));
    }
  });
};

const apply = (
  l: Layout,
  force: boolean,
  f: Facts,
): Task<Outcome, DotError> => {
  const repoFile = l.filesDir + "/" + f.repoPath;
  const outcome = outcomeFor(f);
  switch (decide(f)) {
    case "clean":
      return Task.of(outcome("clean", f.hostHash));
    case "missing":
      return Task.of(outcome("missing", null));
    case "toHost":
      return copyFile(repoFile, f.hostPath).map(() =>
        outcome("toHost", f.repoHash)
      );
    case "toRepo":
      return copyFile(f.hostPath, repoFile).map(() =>
        outcome("toRepo", f.hostHash)
      );
    case "conflict":
      return resolveConflict(f, repoFile, force);
  }
};

const line = (o: Outcome, recover: string | null): string | null => {
  switch (o.plan) {
    case "clean":
      return null;
    case "toRepo":
      return `host -> repo  ${o.target}`;
    case "toHost":
      return `repo -> host  ${o.target}`;
    case "conflict":
      return `host -> repo  ${o.target} (both sides changed: host copy kept)` +
        (recover === null ? "" : `\n  overwritten repo copy: ${recover}`);
    case "conflictRepo":
      return `repo -> host  ${o.target} (both sides changed: repo copy kept, host copy overwritten)`;
    case "merged":
      return `merged        ${o.target} (diff tool result kept on both sides)`;
    case "skipped":
      return `skipped       ${o.target} (conflict unresolved; rerun dot sync, or -f to keep the host copy)`;
    case "missing":
      return `missing       ${o.target} (gone on host and in repo; dot remove to untrack)`;
  }
};

/** Pulls with rebase; an empty remote (nothing pushed yet) counts as up to date. */
const pull = (l: Layout, branch: string): Task<boolean, DotError> =>
  gitRaw(l.repo, ["pull", "--rebase", "--autostash", "origin", branch])
    .andThen((out) => {
      if (out.code === 0) return Task.of<boolean, DotError>(true);
      if (out.stderr.includes("couldn't find remote ref")) {
        return Task.of<boolean, DotError>(false);
      }
      return Task.fail<DotError, boolean>({
        kind: "git",
        args: ["pull", "--rebase", "--autostash", "origin", branch],
        message: out.stderr.trim() || out.stdout.trim() ||
          `exit code ${out.code}`,
      });
    });

const requireBound = (l: Layout): Task<Layout, DotError> =>
  stat(l.repo + "/.git").andThen((info) =>
    info === null
      ? Task.fail<DotError, Layout>(
        configError("not bound — run: dot bind <repo>"),
      )
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
  const lines: string[] = [];
  for (const o of outcomes) {
    const spec = `${preSync}:files/${o.repoPath}`;
    const recover = o.plan === "conflict" && preSync !== null
      ? `git -C ${repoDisplay} show ${/\s/.test(spec) ? `"${spec}"` : spec}`
      : null;
    const rendered = line(o, recover);
    if (rendered !== null) lines.push(rendered);
  }
  const clean = outcomes.filter((o) => o.plan === "clean").length;
  if (clean > 0) lines.push(`up to date: ${clean} file(s)`);
  if (outcomes.length === 0) lines.push("nothing tracked — dot add <path>");
  if (pushWarning !== null) lines.push(pushWarning);
  else if (committed) lines.push("pushed");
  return lines.join("\n");
};

export const sync = (force: boolean): Task<string, DotError> =>
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
            const hashes: Record<string, string> = {};
            for (let i = 0; i < entries.length; i++) {
              const entry = entries[i];
              const outcome = outcomes[i];
              if (entry === undefined || outcome === undefined) continue;
              if (outcome.hash !== null) hashes[entry[0]] = outcome.hash;
            }
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
                    .orElse(() => Task.of<string | null, DotError>(null))
                  : Task.of<string | null, DotError>(null);
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
