package dot

/**
 * Path handling on portable, slash-separated strings. Paths are normalized to
 * forward slashes everywhere; Java's file APIs accept them directly.
 */

extension (path: String) {
  def toSlash: String = path.replace('\\', '/')

  def isAbsolutePath: Boolean =
    path.startsWith("/")
      || (path.length >= 3 && path(0).isLetter && path(1) == ':' && path(2) == '/')

  /** Collapses "." and ".." segments and duplicate separators; expects slash-separated input. */
  def normalize: String = {
    val drive =
      if path.length >= 2 && path(0).isLetter && path(1) == ':' then path.take(2)
      else ""
    val rest  = path.drop(drive.length)
    val abs   = rest.startsWith("/")
    val parts = rest.split("/", -1).foldLeft(List.empty[String]): (out, part) =>
      if part.isEmpty || part == "." then out
      else if part != ".." then out :+ part
      else
        out.lastOption match
          case Some(last) if last != ".." => out.dropRight(1)
          case _                          => if abs then out else out :+ ".."
    drive + (if abs then "/" else "") + parts.mkString("/")
  }

  /** Joins a child path onto this one, slash-normalizing both sides. */
  def /(child: String): String = (path.toSlash + "/" + child.toSlash).normalize

  def dirname: String = {
    val i = path.lastIndexOf('/')
    if i < 0 then "."
    else if i == 0 then "/"
    else path.take(i)
  }

  /** Resolves user input to an absolute slash-separated path, expanding a leading "~". */
  def resolvePath(home: String): String = {
    val s = path.toSlash
    if s == "~" then home
    else if s.startsWith("~/") then home / s.drop(2)
    else if s.isAbsolutePath then s.normalize
    else System.getProperty("user.dir").toSlash / s
  }

  /** Rewrites an absolute path under the home directory to the portable "~/..." form. */
  def contractTarget(home: String): String =
    if path == home then "~"
    else if path.startsWith(home + "/") then "~/" + path.drop(home.length + 1)
    else path

  /** Expands a portable target ("~/..." or absolute) to an absolute path on this host. */
  def expandTarget(home: String): String =
    if path == "~" then home
    else if path.startsWith("~/") then home / path.drop(2)
    else path.toSlash.normalize

  /** Maps a portable target path to the file's path inside the repo's files/ tree. */
  def repoPathFor: String =
    if path.startsWith("~/") then path.drop(2)
    else "_root/" + path.stripPrefix("/").replaceFirst(":", "")
}

/** Reads an environment variable; None when unset. */
def envGet(name: String): Option[String] = sys.env.get(name)

def homeDir: Either[DotError, String] = {
  val raw = envGet("HOME").orElse(envGet("USERPROFILE")).filter(_.nonEmpty)
  raw match
    case Some(value) => Right(value.toSlash.normalize)
    case None        =>
      Left(DotError.Config(
        "cannot locate the home directory: HOME / USERPROFILE is unset or unreadable",
      ))
}
