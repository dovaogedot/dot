#!/bin/sh
# Smoke of conflict handling: the flag surface, the non-terminal force rule,
# and the interactive menu's choices.
set -eu

S="$(mktemp -d /tmp/dot-test-XXXXXX)"
trap 'rm -rf "$S"' EXIT
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

conflict() {
  printf 'host change %s\n' "$1" > "$S/home/.bashrc"
  printf 'repo change %s\n' "$1" > "$S/dothome/repo/files/.bashrc"
  git -C "$S/dothome/repo" commit -qam "change $1 from another host"
  git -C "$S/dothome/repo" push -q origin main
}

echo '== bind + add commit locally; the first sync pushes'
run bind "$S/remote.git" > /dev/null
printf 'original\n' > "$S/home/.bashrc"
run add "$S/home/.bashrc" > /dev/null
[ "$(git -C "$S/remote.git" rev-list --count --all)" = 0 ] || { echo 'FAIL: add pushed'; exit 1; }
run status | grep -q 'up to date    ~/.bashrc' || { echo 'FAIL: status lacks up to date'; exit 1; }
run status | grep -q 'to push' || { echo 'FAIL: status lacks pending pushes'; exit 1; }
run sync > /dev/null
[ "$(git -C "$S/remote.git" rev-list --count --all)" -gt 0 ] || { echo 'FAIL: sync did not push'; exit 1; }
run status | grep -q 'to push' && { echo 'FAIL: status reports pushes when remote is current'; exit 1; }
echo 'ok: add stayed local, sync pushed, status tracked both states'

echo '== status reports a host-side modification'
printf 'tweak\n' > "$S/home/.bashrc"
run status | grep -q 'modified      ~/.bashrc (dot sync: host -> repo)' || { echo 'FAIL: no modified line'; exit 1; }
run sync > /dev/null

echo '== flag surface: unknown flags are rejected'
for flag in -x -m --merge; do
  if run sync "$flag" 2> "$S/err.txt"; then
    echo "FAIL: sync $flag accepted"; exit 1
  fi
done
echo 'ok: -x, -m, --merge rejected'

echo '== non-terminal stdin resolves a conflict like --force'
conflict one
run status | grep -q 'conflict      ~/.bashrc' || { echo 'FAIL: status lacks conflict'; exit 1; }
out="$(run sync)"
echo "$out" | grep -q 'host copy kept' || { echo 'FAIL: host copy not kept'; echo "$out"; exit 1; }
[ "$(cat "$S/dothome/repo/files/.bashrc")" = 'host change one' ] || { echo 'FAIL: repo side wrong'; exit 1; }
echo 'ok: host copy kept without prompting'

echo '== menu offers [l/r/s] only; d is rejected; r keeps the repo copy'
conflict two
out="$(tty_run 'd
r
')"
echo "$out" | grep -q '\[l\] keep local' || { echo 'FAIL: menu not shown'; echo "$out"; exit 1; }
echo "$out" | grep -q '\[d\]' && { echo 'FAIL: menu offers [d]'; exit 1; }
echo "$out" | grep -q 'choose \[l/r/s\]: choose \[l/r/s\]:' || { echo 'FAIL: d not re-prompted'; echo "$out"; exit 1; }
echo "$out" | grep -q 'repo copy kept' || { echo 'FAIL: r not honored'; echo "$out"; exit 1; }
[ "$(cat "$S/home/.bashrc")" = 'repo change two' ] || { echo 'FAIL: host copy not overwritten'; exit 1; }
echo 'ok: menu gated to l/r/s, repo choice works'

echo '== remove commits locally; sync pushes it'
before=$(git -C "$S/remote.git" rev-list --count --all)
run remove "$S/home/.bashrc" > /dev/null
[ "$(git -C "$S/remote.git" rev-list --count --all)" = "$before" ] || { echo 'FAIL: remove pushed'; exit 1; }
run sync > /dev/null
[ "$(git -C "$S/remote.git" rev-list --count --all)" -gt "$before" ] || { echo 'FAIL: sync did not push the removal'; exit 1; }
echo 'ok: remove stayed local, sync pushed'

echo '== all conflict checks passed'
