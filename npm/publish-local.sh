#!/bin/sh
# Publishes the npm packages of one release from this machine: downloads the four binaries from the
# GitHub release, assembles the packages, and runs npm publish for each one. npm asks for the 2FA
# code. Run it from the repo root, logged in with npm login. It serves the first release of a
# package, before the package exists on npm and can get a trusted publisher.
# Usage: sh npm/publish-local.sh <version>
set -eu
version=${1:?usage: sh npm/publish-local.sh <version>}
work=$(mktemp -d)
for target in linux-x64 linux-arm64 darwin-arm64 darwin-x64; do
  mkdir -p "$work/artifacts/polio-$target"
  curl -fsSL "https://github.com/dovaogedot/polio/releases/download/v$version/polio-$target" \
    -o "$work/artifacts/polio-$target/polio"
done
node npm/assemble.mjs "$version" "$work/artifacts" "$work/dist"
# A package already on the registry at this version is skipped, so a run can be repeated.
publish() {
  name=$(node -p "require('$1/package.json').name")
  if npm view "$name@$version" version > /dev/null 2>&1
  then echo "$name@$version is already published"
  else npm publish "$1" --access public
  fi
}
for pkg in "$work"/dist/polio-*; do publish "$pkg"; done
publish "$work/dist/polio"
