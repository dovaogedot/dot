package dot

import cats.effect.IO
import cats.syntax.all.*
import mouse.all.*
import fs2.io.file.Path

/** dot add <path>: start tracking a file, or every file inside a directory. */

private final case class Added(repoPath: String, target: Target, hash: String)

private object Added {

  /** Copies the host file into the repo tree and records its hash. */
  def track(layout: Layout, location: Path): IO[Added] = {
    val target   = Target.contract(location, layout.home)
    val repoPath = target.repoPath
    location.copyTo(layout.repoFile(repoPath))
    *> location.sha256IfExists.flatMap:
      case None       => IO.raiseError(DotError.Usage(s"no such file: $location"))
      case Some(hash) => IO.pure(Added(repoPath, target, hash))
  }

  def report(added: List[Added], skipped: List[Added]): String = {
    val committed = added.nonEmpty.option(s"committed ${added.length} file(s) — dot sync pushes them").toList
    val tracking  = added.map(a => s"tracking ${a.target}")
    val already   = skipped.map(s => s"already tracked: ${s.target}")
    val lines     = tracking ::: already ::: committed
    lines.mkString("\n")
  }
}

extension (layout: Layout) {

  /** The files a location names — one regular file, or every file under a directory; dot's own data is refused. */
  private def filesToTrack(location: Path): IO[List[Path]] = {
    val ownData = location.startsWith(layout.root)
    location.fileKind.flatMap:
      case FileKind.Missing => IO.raiseError(DotError.Usage(s"no such path: $location"))
      case kind             =>
        val files = kind match
          case FileKind.Directory => location.walkFiles
          case _                  => IO.pure(List(location))
        IO.raiseWhen(ownData)(DotError.Usage(s"cannot track dot's own data directory: $location"))
          *> files
  }
}

/** Records the additions in manifest and state and commits them. */
private def commitAdded(layout: Layout, manifest: Manifest, entries: List[Added], added: List[Added]): IO[Unit] = {
  val files  = manifest.files ++ added.map(e => e.repoPath -> e.target)
  val hashes = entries.map(e => e.repoPath -> e.hash)
  val labels = added.map(_.target).mkString(", ")
  for
    _     <- Manifest(files).save(layout)
    state <- SyncState.load(layout)
    _     <- SyncState(state.files ++ hashes).save(layout)
    _     <- Git.in(layout.repo).commitIfChanged(s"dot: add $labels")
  yield ()
}

def add(raw: String): IO[String] = {
  for
    layout   <- Layout.resolve
    manifest <- Manifest.load(layout)
    paths    <- layout.filesToTrack(layout.locate(raw))

    entries <- paths.traverse: path =>
      Added.track(layout, path)

    (skipped, added) = entries.partition(e => manifest.files.contains(e.repoPath))

    _ <- commitAdded(layout, manifest, entries, added).unlessA(added.isEmpty)
  yield Added.report(added, skipped)
}
