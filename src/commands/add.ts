/** dot add <path>: start tracking a file, or every file inside a directory. */

import { Task } from "../task.ts";
import {
  type DotError,
  type IoError,
  type UsageError,
  usageError,
} from "../errors.ts";
import { contractTarget, repoPathFor, resolvePath } from "../path.ts";
import {
  type Layout,
  layout,
  loadManifest,
  loadState,
  type Manifest,
  saveManifest,
  saveState,
} from "../config.ts";
import { copyFile, readBytesIfExists, sha256, stat, walkFiles } from "../fs.ts";
import { commitIfChanged, pushBestEffort } from "../git.ts";

type Added = {
  readonly repoPath: string;
  readonly target: string;
  readonly hash: string;
};

const trackOne = (
  l: Layout,
  abs: string,
): Task<Added, IoError | UsageError> => {
  const target = contractTarget(abs, l.home);
  const repoPath = repoPathFor(target);
  return copyFile(abs, l.filesDir + "/" + repoPath)
    .andThen(() => readBytesIfExists(abs))
    .andThen((bytes): Task<Added, IoError | UsageError> =>
      bytes === null
        ? Task.fail(usageError(`no such file: ${abs}`))
        : sha256(bytes).map((hash) => ({ repoPath, target, hash }))
    );
};

const listFiles = (
  l: Layout,
  abs: string,
): Task<string[], IoError | UsageError> =>
  stat(abs).andThen((info): Task<string[], IoError | UsageError> => {
    if (info === null) {
      return Task.fail(usageError(`no such path: ${abs}`));
    }
    if (abs === l.root || abs.startsWith(l.root + "/")) {
      return Task.fail(
        usageError(`cannot track dot's own data directory: ${abs}`),
      );
    }
    return info.isDirectory ? walkFiles(abs) : Task.of<string[]>([abs]);
  });

const report = (
  added: readonly Added[],
  skipped: readonly Added[],
  pushWarning: string | null,
): string =>
  [
    ...added.map((a) => `tracking ${a.target}`),
    ...skipped.map((s) => `already tracked: ${s.target}`),
    ...(added.length > 0
      ? [pushWarning ?? `committed and pushed ${added.length} file(s)`]
      : []),
  ].join("\n");

export const add = (raw: string): Task<string, DotError> =>
  Task.fromResult(layout()).andThen((l) =>
    loadManifest(l).andThen((manifest) =>
      listFiles(l, resolvePath(raw, l.home)).andThen((paths) =>
        Task.traverse(paths, (p) => trackOne(l, p)).andThen(
          (entries): Task<string, DotError> => {
            const skipped = entries.filter((e) =>
              manifest.files[e.repoPath] !== undefined
            );
            const added = entries.filter((e) =>
              manifest.files[e.repoPath] === undefined
            );
            if (added.length === 0) {
              return Task.of(report(added, skipped, null));
            }
            const next: Manifest = {
              version: 1,
              files: {
                ...manifest.files,
                ...Object.fromEntries(
                  added.map((e) => [e.repoPath, e.target] as const),
                ),
              },
            };
            return saveManifest(l, next)
              .andThen(() => loadState(l))
              .andThen((state) =>
                saveState(l, {
                  version: 1,
                  files: {
                    ...state.files,
                    ...Object.fromEntries(
                      entries.map((e) => [e.repoPath, e.hash] as const),
                    ),
                  },
                })
              )
              .andThen(() =>
                commitIfChanged(
                  l.repo,
                  `dot: add ${added.map((e) => e.target).join(", ")}`,
                )
              )
              .andThen(() => pushBestEffort(l.repo))
              .map((warning) => report(added, skipped, warning));
          },
        )
      )
    )
  );
