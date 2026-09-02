package dot

import fs2.io.file.Path

/**
 * A manifest target: the place of a tracked file on every host. A file under the home directory is
 * written as "~/...". Any other file is written as an absolute path. Separators are always slashes, so
 * hosts with different user names and platforms share one manifest.
 */
opaque type Target = String

object Target {

  /** A target from its manifest text, taken as is. */
  def apply(portable: String): Target = portable

  /** The target for a location on this host. A location under home becomes "~/...". */
  def contract(location: Path, home: Path): Target =
    if location == home then
      "~"
    else if location.startsWith(home) then
      "~/" + home.relativize(location).toString.replace('\\', '/')
    else
      location.toString.replace('\\', '/')

  /** Targets sort by their text. */
  given Ordering[Target] = Ordering.String
}

extension (target: Target) {

  /** The target text, as written in the manifest. */
  def value: String = target

  /** The location this target names on this host. */
  def expand(home: Path): Path =
    if target == "~" then
      home
    else if target.startsWith("~/") then
      home / target.drop(2)
    else
      Path(target)

  /** The path of the file inside the files/ directory of the repo. */
  def repoPath: String =
    if target.startsWith("~/") then
      target.drop(2)
    else
      "_root/" + target.stripPrefix("/").replaceFirst(":", "")

  /** True if this target is the other target, or a file inside it. */
  def within(other: Target): Boolean = target == other || target.startsWith(other + "/")
}
