# dot

Sync config files across machines through a git repository. Zero dependencies;
requires Deno and git.

## Install

```sh
git clone <this repo> && cd dot && deno task install
```

## Use

```sh
dot bind git@github.com:you/dotfiles.git  # once per machine; clones into ~/.dot/repo
dot add ~/.bashrc                         # track a file (a directory tracks every file inside)
dot sync                                  # pull, reconcile, push (-f: conflicts keep the host copy)
dot remove ~/.bashrc                      # untrack (the host copy stays)
```

## How sync works

- Pulls the repo, then compares each tracked file on the host with the repo
  copy, using the content hash recorded at the previous sync to tell which side
  changed.
- One side changed: that side wins. Both sides changed: dot asks per file — keep
  the local copy, keep the repo copy, resolve in `git difftool`, or skip. `-f` /
  `--force` keeps the local copy without asking; without a terminal, conflicts
  are skipped. A kept local copy overwrites the repo copy, which stays in git
  history — the sync prints the command that retrieves it.
- Missing on the host: installed from the repo. Anything the host changed is
  committed and pushed, so the repo holds the latest state of every host.

## Layout

Data lives in `~/.dot` (override with `DOT_HOME`): the clone at `~/.dot/repo`,
per-host sync state at `~/.dot/state.json`. The repo maps its files to host
destinations in `dot.json`:

```json
{
  "version": 1,
  "files": {
    ".bashrc": "~/.bashrc",
    ".config/git/config": "~/.config/git/config"
  }
}
```

Targets under the home directory travel as `~/...`, so hosts with different
usernames share one manifest. Files created later inside a tracked directory are
not picked up automatically — run `dot add` on them.
