package dot

import fs2.io.file.Path

/**
 * A manifest target: where a tracked file lives on every host, spelled
 * "~/..." for a file under the home directory or as an absolute path, always
 * slash-separated so hosts with different usernames and platforms share one
 * manifest.
 */
opaque type Target = String

object Target {

  /** The manifest's portable spelling, taken as is. */
  def apply(portable: String): Target = portable

  /** The portable spelling of a location on this host: under home it travels as "~/...". */
  def contract(location: Path, home: Path): Target =
    if location == home then
      "~"
    else if location.startsWith(home) then
      "~/" + home.relativize(location).toString.replace('\\', '/')
    else
      location.toString.replace('\\', '/')

  given Ordering[Target] = Ordering.String
}

extension (target: Target) {

  def value: String = target

  /** The location this target names on this host. */
  def expand(home: Path): Path =
    if target == "~" then
      home
    else if target.startsWith("~/") then
      home / target.drop(2)
    else
      Path(target)

  /** Where the file sits inside the repo's files/ tree. */
  def repoPath: String =
    if target.startsWith("~/") then
      target.drop(2)
    else
      "_root/" + target.stripPrefix("/").replaceFirst(":", "")

  /** Whether this target is the other one or a file inside it. */
  def within(other: Target): Boolean = target == other || target.startsWith(other + "/")
}
