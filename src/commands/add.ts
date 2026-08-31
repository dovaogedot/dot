/** dot add <path>: start tracking a file, or every file inside a directory. */

import { Task } from "../task.ts";
import { type DotError, usageError } from "../errors.ts";
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

const trackOne = (l: Layout, abs: string): Task<Added, DotError> => {
  const target = contractTarget(abs, l.home);
  const repoPath = repoPathFor(target);
  return copyFile(abs, l.filesDir + "/" + repoPath)
    .andThen(() => readBytesIfExists(abs))
    .andThen((bytes) =>
      bytes === null
        ? Task.fail<DotError, Added>(usageError(`no such file: ${abs}`))
        : sha256(bytes).map((hash) => ({ repoPath, target, hash }))
    );
};

const listFiles = (l: Layout, abs: string): Task<string[], DotError> =>
  stat(abs).andThen((info) => {
    if (info === null) {
      return Task.fail<DotError, string[]>(usageError(`no such path: ${abs}`));
    }
    if (abs === l.root || abs.startsWith(l.root + "/")) {
      return Task.fail<DotError, string[]>(
        usageError(`cannot track dot's own data directory: ${abs}`),
      );
    }
    return info.isDirectory
      ? walkFiles(abs)
      : Task.of<string[], DotError>([abs]);
  });

const report = (
  added: readonly Added[],
  skipped: readonly Added[],
  pushWarning: string | null,
): string => {
  const lines: string[] = [];
  for (const a of added) lines.push(`tracking ${a.target}`);
  for (const s of skipped) lines.push(`already tracked: ${s.target}`);
  if (added.length > 0) {
    lines.push(
      pushWarning ?? `committed and pushed ${added.length} file(s)`,
    );
  }
  return lines.join("\n");
};

export const add = (raw: string): Task<string, DotError> =>
  Task.fromResult(layout()).andThen((l) =>
    loadManifest(l).andThen((manifest) =>
      listFiles(l, resolvePath(raw, l.home)).andThen((paths) =>
        Task.traverse(paths, (p) => trackOne(l, p)).andThen((entries) => {
          const skipped = entries.filter((e) =>
            manifest.files[e.repoPath] !== undefined
          );
          const added = entries.filter((e) =>
            manifest.files[e.repoPath] === undefined
          );
          if (added.length === 0) {
            return Task.of<string, DotError>(report(added, skipped, null));
          }
          const files: Record<string, string> = { ...manifest.files };
          for (const e of added) files[e.repoPath] = e.target;
          const next: Manifest = { version: 1, files };
          return saveManifest(l, next)
            .andThen(() => loadState(l))
            .andThen((state) => {
              const hashes: Record<string, string> = { ...state.files };
              for (const e of entries) hashes[e.repoPath] = e.hash;
              return saveState(l, { version: 1, files: hashes });
            })
            .andThen(() =>
              commitIfChanged(
                l.repo,
                `dot: add ${added.map((e) => e.target).join(", ")}`,
              )
            )
            .andThen(() => pushBestEffort(l.repo))
            .map((warning) => report(added, skipped, warning));
        })
      )
    )
  );
