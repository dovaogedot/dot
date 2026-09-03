# Publishing

A release is a git tag `v<version>` on `main`. The `release` workflow in
`.github/workflows/release.yml` does the rest.

## Cut a release

1. Set `VERSION` in `Main.scala`, commit, and tag the commit:
   `git tag -a v<version> -m "polio <version>"`.
2. Push the branch and the tag together: `git push origin main v<version>`. A
   tag that already exists on the remote moves only with
   `git push --force origin v<version>`.
3. The workflow checks that the tag matches `VERSION`, builds the native binary
   on four runners (Linux x64 and arm64, macOS arm64 and Intel), creates the
   GitHub release with the binaries, `SHA256SUMS` and a rendered `PKGBUILD`,
   and publishes the npm packages. When a release for the tag already exists,
   the workflow updates it and replaces the files with the same names.
4. The Actions tab has a "Run workflow" button for a manual run. Start it on
   the tag, not on `main`: the version comes from the ref name.

## npm

Five packages. `@dovaogedot/polio` holds the shim `bin/polio.js`; the scope is
required because npm rejects the bare name as too similar to other packages. `polio-linux-x64`,
`polio-linux-arm64`, `polio-darwin-arm64` and `polio-darwin-x64` each hold one
binary and declare `os` and `cpu`, so npm installs only the one that matches
the machine. `node npm/assemble.mjs <version> <artifacts> <out>` builds all
five from the release artifacts.

Publishing uses npm trusted publishing. The job signs in with the OIDC token
GitHub issues for the workflow, so no npm token is stored anywhere. It needs
npm 11.5.1 or newer, which the job installs. Each package needs a trusted
publisher once, on npmjs.com under the package's Settings, "Trusted
publishing", provider GitHub Actions: user `dovaogedot`, repository `polio`,
workflow filename `release.yml`, allowed action `npm publish`. Then set the
package to "Require two-factor authentication and disallow tokens"; trusted
publishers keep working.

A package that does not exist on npm yet cannot have a trusted publisher. Its
first version is published from a developer machine after the GitHub release
exists: `npm login`, then `sh npm/publish-local.sh <version>` from the repo
root. npm asks for the 2FA code. The npm job of that first run fails; every
later release publishes from the workflow. Publishing is idempotent: a package
already on the registry at that version is skipped.

## AUR

`packaging/aur/PKGBUILD` is the template for `polio-bin`. The workflow fills in
the version and the checksums and attaches the result to the release. To
publish a version: copy the `PKGBUILD` from the release into a clone of
`ssh://aur@aur.archlinux.org/polio-bin.git`, run
`makepkg --printsrcinfo > .SRCINFO`, test with `makepkg -si`, commit and push.
The package installs `/usr/bin/polio` and the license, for x86_64 and
aarch64, and depends on `glibc`, `zlib` and `git`.
