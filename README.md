# dot

Sync config files across machines through a git repository. Scala 3 + Cats
Effect + fs2 + Circe + Decline, built with the `scala` runner from a plain
`project.scala` — no sbt. The installed binary needs only git at runtime.

## Install

```sh
git clone <this repo> && cd dot && scala run . -M dot.Install
```

Packages a self-contained native binary (GraalVM native-image) into
`~/.local/bin`; the binary runs without the clone or a JVM. When
`~/.local/bin` is not on `PATH`, the installer appends the export to the
current shell's config (bash, zsh, or fish). Building requires the `scala`
runner (3.5+) and git; the compiler, libraries, and native-image toolchain
resolve automatically.

## Use

```sh
dot bind git@github.com:you/dotfiles.git  # once per machine; clones into ~/.dot/repo
dot add ~/.bashrc                         # track a file (a directory tracks every file inside)
dot sync                                  # pull, reconcile, push (-f: conflicts keep the host copy)
dot sync --abort                          # discard parked conflicts; both sides stay as they are
dot status                                # every tracked file and what sync would do; reads local state only
dot -q <command>                          # --quiet: suppress stdout; -s / --shush suppresses stderr too
dot remove ~/.bashrc                      # untrack (the host copy stays)
```

## How sync works

- Pulls the repo, then compares each tracked file on the host with the repo
  copy, using the content hash recorded at the previous sync to tell which side
  changed.
- One side changed: that side wins. Both sides changed: dot asks per file —
  keep the local copy, keep the repo copy, or skip (parking the conflict).
  `-f` / `--force` keeps the local copy without asking, and a non-terminal
  stdin behaves like `--force`. A kept local copy overwrites the repo copy,
  which stays in git history — the sync prints the command that retrieves it.
- Skipping parks a conflict-marked copy of the file under
  `~/.dot/conflicts/<repo path>` and leaves both sides untouched. Edit the
  parked copy until the `<<<<<<<` markers are gone; the next `dot sync` applies
  it to both host and repo. While markers remain, sync reports the file without
  asking again — a parked file also overrides `-f`. `dot sync --abort` discards
  every parked copy. A host copy the file system's permissions reject fails
  with the `sudo cp` command that applies it by hand.
- Missing on the host: installed from the repo. Anything the host changed is
  committed, so the repo holds the latest state of every host. The push runs
  only when the remote lacks commits — a sync that changes nothing stays
  local-only.
- `sync` is the only command that talks to the remote (besides `bind`, which
  clones it). `add` and `remove` commit locally; the next `dot sync` pushes
  their commits.

## Layout

Data lives in `~/.dot` (override with `DOT_HOME`): the clone at `~/.dot/repo`,
per-host sync state at `~/.dot/state.json`, parked conflicts under
`~/.dot/conflicts`. The repo maps its files to host destinations in `dot.json`:

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

## Development

`scala compile .` type-checks the project; `scala fmt .` formats it per
`.scalafmt.conf`. `sh test/run.sh` runs the end-to-end suites against the
installed binary (`DOT_BIN=<path>` points them at another build).
