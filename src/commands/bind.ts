/** dot bind <repo>: point this host at the git remote that stores the config files. */

import { Task } from "../task.ts";
import type { ConfigError, GitError, IoError } from "../errors.ts";
import {
  emptyManifest,
  type Layout,
  resolveLayout,
  saveManifest,
} from "../config.ts";
import { commitIfChanged, git } from "../git.ts";
import { ensureDir, stat } from "../fs.ts";

const rebind = (layout: Layout, url: string): Task<string, GitError> =>
  git(layout.repo, ["remote", "get-url", "origin"])
    .flatMap(() => git(layout.repo, ["remote", "set-url", "origin", url]))
    .orElse(() => git(layout.repo, ["remote", "add", "origin", url]))
    .flatMap(() => git(layout.repo, ["fetch", "origin"]))
    .map(() => `bound ${url}\nrepo: ${layout.repo}`);

/** Ensures the cloned repo carries a manifest, committing one when the remote had none. */
const ensureManifest = (layout: Layout): Task<void, IoError | GitError> =>
  stat(layout.manifestPath).flatMap((info): Task<void, IoError | GitError> =>
    info !== null
      ? Task.of<void>(undefined)
      : saveManifest(layout, emptyManifest)
        .flatMap(() => commitIfChanged(layout.repo, "dot: init manifest"))
        .map(() => undefined)
  );

const clone = (layout: Layout, url: string): Task<string, IoError | GitError> =>
  ensureDir(layout.root)
    .flatMap(() => git(null, ["clone", url, layout.repo]))
    .flatMap(() => ensureManifest(layout))
    .map(() => `bound ${url}\nrepo: ${layout.repo}\nrun: dot sync`);

export const bind = (
  url: string,
): Task<string, ConfigError | IoError | GitError> =>
  Task.fromResult(resolveLayout()).flatMap((layout) =>
    stat(layout.repo + "/.git").flatMap((
      info,
    ): Task<string, IoError | GitError> =>
      info === null ? clone(layout, url) : rebind(layout, url)
    )
  );
