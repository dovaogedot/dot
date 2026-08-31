/** dot remove <path>: stop tracking a file or directory; host copies stay in place. */

import { Task } from "../task.ts";
import { type DotError, usageError } from "../errors.ts";
import { contractTarget, resolvePath } from "../path.ts";
import {
  layout,
  loadManifest,
  loadState,
  saveManifest,
  saveState,
} from "../config.ts";
import { removeIfExists } from "../fs.ts";
import { commitIfChanged, pushBestEffort } from "../git.ts";

export const remove = (raw: string): Task<string, DotError> =>
  Task.fromResult(layout()).andThen((l) =>
    loadManifest(l).andThen((manifest) => {
      const portable = contractTarget(resolvePath(raw, l.home), l.home);
      const doomed = Object.entries(manifest.files).filter(([, target]) =>
        target === portable || target.startsWith(portable + "/")
      );
      if (doomed.length === 0) {
        return Task.fail<DotError, string>(
          usageError(`not tracked: ${portable}`),
        );
      }
      const files: Record<string, string> = {};
      for (const [repoPath, target] of Object.entries(manifest.files)) {
        if (!doomed.some(([d]) => d === repoPath)) files[repoPath] = target;
      }
      return Task.traverse(
        doomed,
        ([repoPath]) => removeIfExists(l.filesDir + "/" + repoPath),
      )
        .andThen(() => saveManifest(l, { version: 1, files }))
        .andThen(() => loadState(l))
        .andThen((state) => {
          const hashes: Record<string, string> = {};
          for (const [repoPath, hash] of Object.entries(state.files)) {
            if (files[repoPath] !== undefined) hashes[repoPath] = hash;
          }
          return saveState(l, { version: 1, files: hashes });
        })
        .andThen(() =>
          commitIfChanged(
            l.repo,
            `dot: remove ${doomed.map(([, t]) => t).join(", ")}`,
          )
        )
        .andThen(() => pushBestEffort(l.repo))
        .map((warning) => {
          const lines = doomed.map(([, t]) =>
            `untracked ${t} (host copy kept)`
          );
          lines.push(warning ?? "committed and pushed");
          return lines.join("\n");
        });
    })
  );
