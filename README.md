# polio

Sync config files between machines through a git repository. Written in Scala 3
with Cats Effect, fs2, Circe and Decline. Built with the `scala` runner from a
plain `project.scala`, without sbt. The binary needs only git at run time.

## Install

With npm, on Linux (x64, arm64) or macOS (arm64, x64):

```sh
npm install -g polio    # or run it without installing: npx polio status
```

On Arch Linux, from the AUR:

```sh
yay -S polio-bin
```

From source, with the `scala` runner (3.5 or newer) and git installed:

```sh
git clone https://github.com/dovaogedot/polio && cd polio && scala run . -M dot.Install
```

The installer builds a native binary (GraalVM native-image) and puts it in
`~/.local/bin/polio`. If `~/.local/bin` is not on `PATH`, it adds the export
line to the config file of the current shell (bash, zsh or fish). The compiler,
the libraries and the native-image toolchain are downloaded automatically. The
binary runs without the clone and without a JVM.

## Use

```sh
polio bind git@github.com:you/dotfiles.git  # once per machine; clones into ~/.dot/repo
polio add ~/.bashrc                         # track a file (a directory tracks every file inside)
polio sync                                  # pull, reconcile, push (-f: conflicts keep the host copy)
polio sync --abort                          # discard parked conflicts; both sides stay as they are
polio status                                # every tracked file and what sync would do; reads local state only
polio -q <command>                          # --quiet: suppress stdout; -s / --shush suppresses stderr too
polio remove ~/.bashrc                      # untrack (the host copy stays)
```

## How sync works

- Sync pulls the repo. Then it compares each tracked file on the host with the
  copy in the repo. The content hash saved at the last sync tells which side
  changed.
- If one side changed, that side wins. If both sides changed, polio asks for each
  file: keep the local copy, keep the repo copy, or skip and park the conflict.
  `-f` / `--force` keeps the local copy without asking. When stdin is not a
  terminal, sync acts like `--force`. A kept local copy replaces the repo copy.
  The old repo copy stays in git history, and sync prints the command that
  shows it.
- Skip parks a copy of the file with conflict markers under
  `~/.dot/conflicts/<repo path>`. Both sides stay as they are. Edit the parked
  copy until all `<<<<<<<` markers are gone. The next `polio sync` applies it to
  both the host and the repo. While markers remain, sync reports the file and
  does not ask again. A parked file also wins over `-f`. `polio sync --abort`
  deletes every parked copy. If file permissions block a write to the host copy,
  sync fails and prints the `sudo cp` command that does it by hand.
- A file missing on the host is installed from the repo. Every change made on
  the host is committed, so the repo holds the latest state of every host. Sync
  pushes only when the remote is missing commits. A sync that changes nothing
  stays local.
- `sync` is the only command that talks to the remote. `bind` also does, once,
  to clone it. `add` and `remove` commit locally. The next `polio sync` pushes
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
directory is not tracked automatically. Run `polio add` on it.

## Development

`scala compile .` type-checks the project. `scala fmt .` formats it with
`.scalafmt.conf`. `scala test .` runs the weaver suites under `test/`. The
end-to-end tests run the installed binary inside a temporary home with its own
bare remote, one per test. `DOT_BIN=<path>` points them at another build.
`--test-only dot.ParkSuite` runs one suite.

## Releasing

1. Set `VERSION` in `Main.scala` to the new version, commit, and tag the commit
   `v<version>`. Push the branch and the tag.
2. The release workflow builds the binaries for Linux (x64, arm64) and macOS
   (arm64, x64), attaches them to a GitHub release together with `SHA256SUMS`
   and a rendered `PKGBUILD`, and publishes the npm packages `polio` and
   `polio-<platform>`. Publishing needs an `NPM_TOKEN` repository secret with
   publish rights.
3. For the AUR, copy the `PKGBUILD` from the release into the `polio-bin` AUR
   repository, run `makepkg --printsrcinfo > .SRCINFO`, commit and push.
