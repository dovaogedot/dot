package dot

import cats.effect.IO
import cats.syntax.all.*

/** dot add <path>: start tracking a file, or every file inside a directory. */

private final case class Added(repoPath: String, target: String, hash: String)

private def trackOne(layout: Layout, abs: String): IO[Added] = {
  val target   = abs.contractTarget(layout.home)
  val repoPath = target.repoPathFor
  copyFile(abs, layout.filesDir + "/" + repoPath)
  *> sha256IfExists(abs).flatMap:
    case None       => IO.raiseError(DotError.Usage(s"no such file: $abs"))
    case Some(hash) => IO.pure(Added(repoPath, target, hash))
}

private def listFiles(layout: Layout, abs: String): IO[List[String]] = {
  val ownData = abs == layout.root || abs.startsWith(layout.root + "/")
  fileKind(abs).flatMap:
    case FileKind.Missing => IO.raiseError(DotError.Usage(s"no such path: $abs"))
    case kind             =>
      val files = kind match
        case FileKind.Directory => walkFiles(abs)
        case _                  => IO.pure(List(abs))
      IO.raiseWhen(ownData)(DotError.Usage(s"cannot track dot's own data directory: $abs"))
        *> files
}

private def addReport(
  added: List[Added],
  skipped: List[Added],
  pushWarning: Option[String],
): String = {
  val pushed =
    if added.isEmpty then Nil
    else List(pushWarning.getOrElse(s"committed and pushed ${added.length} file(s)"))
  val tracking = added.map: a =>
    s"tracking ${a.target}"
  val already = skipped.map: s =>
    s"already tracked: ${s.target}"
  val lines = tracking ::: already ::: pushed
  lines.mkString("\n")
}

private def commitAdded(
  layout: Layout,
  manifest: Manifest,
  entries: List[Added],
  added: List[Added],
  skipped: List[Added],
): IO[String] = {
  val files  = manifest.files ++ added.map(e => e.repoPath -> e.target)
  val hashes = entries.map(e => e.repoPath -> e.hash)
  val labels = added.map(_.target).mkString(", ")
  for
    _     <- saveManifest(layout, Manifest(files))
    state <- loadState(layout)
    _     <- saveState(layout, SyncState(state.files ++ hashes))
    _     <- Git.in(layout.repo).commitIfChanged(s"dot: add $labels")

    warning <- Git.in(layout.repo).pushBestEffort
  yield addReport(added, skipped, warning)
}

def add(raw: String): IO[String] = {
  for
    layout   <- resolveLayout
    manifest <- loadManifest(layout)
    paths    <- listFiles(layout, raw.resolvePath(layout.home))

    entries <- paths.traverse: path =>
      trackOne(layout, path)

    (skipped, added) = entries.partition(e => manifest.files.contains(e.repoPath))

    report <-
      if added.isEmpty then IO.pure(addReport(added, skipped, None))
      else commitAdded(layout, manifest, entries, added, skipped)
  yield report
}
