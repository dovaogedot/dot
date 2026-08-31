/** dot sync: pull, reconcile every tracked file with the host, commit, push. */

import { Task } from "../task.ts";
import { configError, type DotError } from "../errors.ts";
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
import { commitIfChanged, git, gitRaw, pushBestEffort } from "../git.ts";
import { stat } from "../fs.ts";

type Facts = {
  readonly repoPath: string;
  readonly target: string;
  readonly hostPath: string;
  readonly hostHash: string | null;
  readonly repoHash: string | null;
  readonly baseHash: string | null;
};

type Plan = "clean" | "toRepo" | "toHost" | "conflict" | "missing";

/**
 * Three-way comparison against the hash recorded at the last sync. When both
 * sides changed, the host copy wins: the repo copy stays in git history, while
 * an overwritten host copy would be gone for good.
 */
const decide = (f: Facts): Plan => {
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

const apply = (l: Layout, f: Facts): Task<Outcome, DotError> => {
  const repoFile = l.filesDir + "/" + f.repoPath;
  const plan = decide(f);
  const done = (hash: string | null): Outcome => ({
    plan,
    repoPath: f.repoPath,
    target: f.target,
    hash,
  });
  switch (plan) {
    case "clean":
      return Task.of(done(f.hostHash));
    case "missing":
      return Task.of(done(null));
    case "toHost":
      return copyFile(repoFile, f.hostPath).map(() => done(f.repoHash));
    case "toRepo":
    case "conflict":
      return copyFile(f.hostPath, repoFile).map(() => done(f.hostHash));
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

export const sync = (): Task<string, DotError> =>
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
              gather(l, state, repoPath, target).andThen((f) => apply(l, f)),
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
