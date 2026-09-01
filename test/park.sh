#!/bin/sh
# Smoke of parked conflicts and push gating. A broken pushurl distinguishes
# "push attempted" (warning line) from "push skipped" (silence).
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

run bind "$S/remote.git" > /dev/null
printf 'line a\nline b\nline c\n' > "$S/home/.bashrc"
run add "$S/home/.bashrc" > /dev/null
run sync > /dev/null

printf 'line a HOST\nline b\nline c\n' > "$S/home/.bashrc"
printf 'line a\nline b\nline c REPO\n' > "$S/dothome/repo/files/.bashrc"
git -C "$S/dothome/repo" commit -qam 'change from another host'
git -C "$S/dothome/repo" push -q origin main
git -C "$S/dothome/repo" config remote.origin.pushurl "$S/nowhere"

echo '== skip parks the conflict; nothing pushed or even attempted'
out="$(tty_run 's
')"
echo "$out" | grep -q 'parked' || { echo "FAIL: no parked line"; echo "$out"; exit 1; }
echo "$out" | grep -q 'pushed' && { echo 'FAIL: pushed after skip-everything'; exit 1; }
echo "$out" | grep -q 'warning: push failed' && { echo 'FAIL: push attempted after skip-everything'; exit 1; }
parked="$S/dothome/conflicts/.bashrc"
grep -q '^<<<<<<< host' "$parked" || { echo 'FAIL: parked file lacks markers'; cat "$parked"; exit 1; }
[ "$(head -c 11 "$S/home/.bashrc")" = 'line a HOST' ] || { echo 'FAIL: host touched'; exit 1; }
grep -q 'REPO' "$S/dothome/repo/files/.bashrc" || { echo 'FAIL: repo touched'; exit 1; }
run status | grep -q 'parked        ~/.bashrc' || { echo 'FAIL: status lacks parked'; exit 1; }
echo 'ok: parked with markers, both sides untouched, no push attempt'

echo '== parked file holds across syncs without re-asking (non-terminal too)'
out="$(run sync)"
echo "$out" | grep -q 'parked' || { echo "FAIL: parked not reported"; echo "$out"; exit 1; }
echo "$out" | grep -q 'host copy kept' && { echo 'FAIL: non-terminal force-resolved a parked conflict'; exit 1; }
echo 'ok: parked survives, no prompt, no force-resolution'

echo '== hand-resolved parked copy lands on both sides; push attempted'
printf 'line a HOST\nline b\nline c REPO\n' > "$parked"
run status | grep -q 'resolved      ~/.bashrc' || { echo 'FAIL: status lacks resolved'; exit 1; }
out="$(run sync)"
echo "$out" | grep -q 'resolved' || { echo "FAIL: no resolved line"; echo "$out"; exit 1; }
echo "$out" | grep -q 'warning: push failed' || { echo 'FAIL: push not attempted after commit'; echo "$out"; exit 1; }
[ "$(cat "$S/home/.bashrc")" = "$(cat "$S/dothome/repo/files/.bashrc")" ] || { echo 'FAIL: sides differ'; exit 1; }
grep -q 'HOST' "$S/home/.bashrc" && grep -q 'REPO' "$S/home/.bashrc" || { echo 'FAIL: resolution content wrong'; exit 1; }
test ! -e "$parked" || { echo 'FAIL: parked file not removed'; exit 1; }
echo 'ok: resolution applied to both sides, parked copy removed, push tried'

echo '== stranded commit pushes on next sync once the remote is reachable'
git -C "$S/dothome/repo" config --unset remote.origin.pushurl
out="$(run sync)"
echo "$out" | grep -q '^pushed$' || { echo "FAIL: stranded commit not pushed"; echo "$out"; exit 1; }
echo 'ok: stranded commit pushed, "pushed" reported'

echo '== up-to-date sync attempts no push'
git -C "$S/dothome/repo" config remote.origin.pushurl "$S/nowhere"
out="$(run sync)"
echo "$out" | grep -q 'warning: push failed' && { echo 'FAIL: push attempted with nothing to push'; exit 1; }
echo "$out" | grep -q 'pushed' && { echo 'FAIL: pushed reported with nothing to push'; exit 1; }
git -C "$S/dothome/repo" config --unset remote.origin.pushurl
echo 'ok: no push attempt when remote is current'

echo '== sync --abort discards parked copies and reports counts'
printf 'host2\nline b\nline c\n' > "$S/home/.bashrc"
printf 'line a HOST\nline b\nrepo2\n' > "$S/dothome/repo/files/.bashrc"
git -C "$S/dothome/repo" commit -qam 'second change from another host'
git -C "$S/dothome/repo" push -q origin main
tty_run 's
' > /dev/null
test -e "$parked" || { echo 'FAIL: setup parking failed'; exit 1; }
out="$(run sync --abort)"
echo "$out" | grep -q 'discarded 1 parked conflict' || { echo "FAIL: abort message"; echo "$out"; exit 1; }
test ! -e "$S/dothome/conflicts" || { echo 'FAIL: conflicts dir remains'; exit 1; }
grep -q 'host2' "$S/home/.bashrc" || { echo 'FAIL: abort touched host'; exit 1; }
grep -q 'repo2' "$S/dothome/repo/files/.bashrc" || { echo 'FAIL: abort touched repo'; exit 1; }
out="$(run sync --abort)"
echo "$out" | grep -q 'no parked conflicts' || { echo "FAIL: empty abort message"; echo "$out"; exit 1; }
echo 'ok: abort discards parked copies, leaves both sides, empty abort reports'

echo '== all park/push smoke checks passed'
