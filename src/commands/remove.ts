/** dot remove <path>: stop tracking a file or directory; host copies stay in place. */

import { Task } from "../task.ts";
import { type DotError, usageError } from "../errors.ts";
import { contractTarget, resolvePath } from "../path.ts";
import {
  loadManifest,
  loadState,
  resolveLayout,
  saveManifest,
  saveState,
} from "../config.ts";
import { removeIfExists } from "../fs.ts";
import { commitIfChanged, pushBestEffort } from "../git.ts";

export const remove = (raw: string): Task<string, DotError> =>
  Task.fromResult(resolveLayout()).flatMap((layout) =>
    loadManifest(layout).flatMap((manifest): Task<string, DotError> => {
      const portable = contractTarget(
        resolvePath(raw, layout.home),
        layout.home,
      );
      const doomed = Object.entries(manifest.files).filter(([, target]) =>
        target === portable || target.startsWith(portable + "/")
      );
      if (doomed.length === 0) {
        return Task.fail(usageError(`not tracked: ${portable}`));
      }
      const files = Object.fromEntries(
        Object.entries(manifest.files).filter(([repoPath]) =>
          !doomed.some(([d]) => d === repoPath)
        ),
      );
      return Task.traverse(
        doomed,
        ([repoPath]) => removeIfExists(layout.filesDir + "/" + repoPath),
      )
        .flatMap(() => saveManifest(layout, { version: 1, files }))
        .flatMap(() => loadState(layout))
        .flatMap((state) =>
          saveState(layout, {
            version: 1,
            files: Object.fromEntries(
              Object.entries(state.files).filter(([repoPath]) =>
                files[repoPath] !== undefined
              ),
            ),
          })
        )
        .flatMap(() =>
          commitIfChanged(
            layout.repo,
            `dot: remove ${doomed.map(([, t]) => t).join(", ")}`,
          )
        )
        .flatMap(() => pushBestEffort(layout.repo))
        .map((warning) =>
          [
            ...doomed.map(([, t]) => `untracked ${t} (host copy kept)`),
            warning ?? "committed and pushed",
          ].join("\n")
        );
    })
  );
