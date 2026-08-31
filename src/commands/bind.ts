/** dot bind <repo>: point this host at the git remote that stores the config files. */

import { Task } from "../task.ts";
import type { DotError } from "../errors.ts";
import { emptyManifest, type Layout, layout, saveManifest } from "../config.ts";
import { commitIfChanged, git } from "../git.ts";
import { ensureDir, stat } from "../fs.ts";

const rebind = (l: Layout, url: string): Task<string, DotError> =>
  git(l.repo, ["remote", "get-url", "origin"])
    .andThen(() => git(l.repo, ["remote", "set-url", "origin", url]))
    .orElse(() => git(l.repo, ["remote", "add", "origin", url]))
    .andThen(() => git(l.repo, ["fetch", "origin"]))
    .map(() => `bound ${url}\nrepo: ${l.repo}`);

/** Ensures the cloned repo carries a manifest, committing one when the remote had none. */
const ensureManifest = (l: Layout): Task<void, DotError> =>
  stat(l.manifestPath).andThen((info) =>
    info !== null
      ? Task.of<void, DotError>(undefined)
      : saveManifest(l, emptyManifest)
        .andThen(() => commitIfChanged(l.repo, "dot: init manifest"))
        .map(() => undefined)
  );

const clone = (l: Layout, url: string): Task<string, DotError> =>
  ensureDir(l.root)
    .andThen(() => git(null, ["clone", url, l.repo]))
    .andThen(() => ensureManifest(l))
    .map(() => `bound ${url}\nrepo: ${l.repo}\nrun: dot sync`);

export const bind = (url: string): Task<string, DotError> =>
  Task.fromResult(layout()).andThen((l) =>
    stat(l.repo + "/.git").andThen((info) =>
      info === null ? clone(l, url) : rebind(l, url)
    )
  );
