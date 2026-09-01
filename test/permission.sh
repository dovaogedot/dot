#!/bin/sh
# Smoke of the permission hint: a host copy the file system rejects names the
# sudo command that applies the parked resolution by hand.
set -eu

S="$(mktemp -d /tmp/dot-test-XXXXXX)"
trap 'chmod -R u+w "$S" 2>/dev/null; rm -rf "$S"' EXIT
mkdir -p "$S/home"

DOT_BIN="${DOT_BIN:-$HOME/.local/bin/dot}"
export HOME="$S/home"
export DOT_HOME="$S/dothome"
export GIT_CONFIG_GLOBAL="$S/gitconfig"
export GIT_CONFIG_SYSTEM=/dev/null

printf '[user]\n\tname = smoke\n\temail = smoke@test\n[init]\n\tdefaultBranch = main\n' > "$S/gitconfig"
git init --quiet --bare "$S/remote.git"

run() { "$DOT_BIN" "$@"; }
tty_run() { printf '%s' "$1" | script -qec "$DOT_BIN sync" /dev/null; }

echo '== setup: track a file and park a conflict on it'
run bind "$S/remote.git" > /dev/null
mkdir -p "$S/home/cfg"
printf 'original\n' > "$S/home/cfg/conf"
run add "$S/home/cfg/conf" > /dev/null
run sync > /dev/null
printf 'host change\n' > "$S/home/cfg/conf"
printf 'repo change\n' > "$S/dothome/repo/files/cfg/conf"
git -C "$S/dothome/repo" commit -qam 'change from another host'
git -C "$S/dothome/repo" push -q origin main
tty_run 's
' > /dev/null
parked="$S/dothome/conflicts/cfg/conf"
test -e "$parked" || { echo 'FAIL: parking failed'; exit 1; }

echo '== a resolution the host directory rejects prints the sudo command'
printf 'resolved\n' > "$parked"
chmod 555 "$S/home/cfg"
if run sync 2> "$S/err.txt"; then
  chmod 755 "$S/home/cfg"
  echo 'FAIL: sync succeeded through a read-only directory'
  exit 1
fi
chmod 755 "$S/home/cfg"
grep -q "permission denied — run: sudo cp $parked $S/home/cfg/conf" "$S/err.txt" \
  || { echo 'FAIL: no sudo hint'; cat "$S/err.txt"; exit 1; }
echo 'ok: failure names the exact sudo cp command'

echo '== with permissions restored the resolution applies'
out="$(run sync)"
echo "$out" | grep -q 'resolved' || { echo 'FAIL: not resolved'; echo "$out"; exit 1; }
[ "$(cat "$S/home/cfg/conf")" = 'resolved' ] || { echo 'FAIL: host content wrong'; exit 1; }
echo '== all permission checks passed'
