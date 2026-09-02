# dot

Sync config files between machines through a git repository. Written in Scala 3
with Cats Effect, fs2, Circe and Decline. Built with the `scala` runner from a
plain `project.scala`, without sbt. The installed binary needs only git at run
time.

## Install

```sh
git clone <this repo> && cd dot && scala run . -M dot.Install
```

The installer builds a native binary (GraalVM native-image) and puts it in
`~/.local/bin`. The binary runs without the clone and without a JVM. If
`~/.local/bin` is not on `PATH`, the installer adds the export line to the
config file of the current shell (bash, zsh or fish). The build needs the
`scala` runner (3.5 or newer) and git. The compiler, the libraries and the
native-image toolchain are downloaded automatically.

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

- Sync pulls the repo. Then it compares each tracked file on the host with the
  copy in the repo. The content hash saved at the last sync tells which side
  changed.
- If one side changed, that side wins. If both sides changed, dot asks for each
  file: keep the local copy, keep the repo copy, or skip and park the conflict.
  `-f` / `--force` keeps the local copy without asking. When stdin is not a
  terminal, sync acts like `--force`. A kept local copy replaces the repo copy.
  The old repo copy stays in git history, and sync prints the command that
  shows it.
- Skip parks a copy of the file with conflict markers under
  `~/.dot/conflicts/<repo path>`. Both sides stay as they are. Edit the parked
  copy until all `<<<<<<<` markers are gone. The next `dot sync` applies it to
  both the host and the repo. While markers remain, sync reports the file and
  does not ask again. A parked file also wins over `-f`. `dot sync --abort`
  deletes every parked copy. If file permissions block a write to the host copy,
  sync fails and prints the `sudo cp` command that does it by hand.
- A file missing on the host is installed from the repo. Every change made on
  the host is committed, so the repo holds the latest state of every host. Sync
  pushes only when the remote is missing commits. A sync that changes nothing
  stays local.
- `sync` is the only command that talks to the remote. `bind` also does, once,
  to clone it. `add` and `remove` commit locally. The next `dot sync` pushes
  their commits.

## Layout

Data lives in `~/.dot`. Set `DOT_HOME` to use another directory. The clone is
at `~/.dot/repo`, the sync state of this host at `~/.dot/state.json`, and parked
conflicts under `~/.dot/conflicts`. The file `dot.json` in the repo maps each
repo file to its place on the host:

```json
{
  "version": 1,
  "files": {
    ".bashrc": "~/.bashrc",
    ".config/git/config": "~/.config/git/config"
  }
}
```

A target under the home directory is written as `~/...`, so hosts with
different user names share one manifest. A file created later inside a tracked
directory is not tracked automatically. Run `dot add` on it.

## Development

`scala compile .` type-checks the project. `scala fmt .` formats it with
`.scalafmt.conf`. `scala test .` runs the weaver suites under `test/`. The
end-to-end tests run the installed binary inside a temporary home with its own
bare remote, one per test. `DOT_BIN=<path>` points them at another build.
`--test-only dot.ParkSuite` runs one suite.
