package dot

import cats.effect.IO
import cats.syntax.all.*

/** dot remove <path>: stop tracking a file or directory; host copies stay in place. */

def remove(raw: String): IO[String] = {
  for
    layout   <- resolveLayout
    manifest <- loadManifest(layout)

    portable = raw.resolvePath(layout.home).contractTarget(layout.home)
    doomed   = manifest.files.toList.filter: (_, target) =>
      target == portable || target.startsWith(portable + "/")

    _ <- IO.raiseWhen(doomed.isEmpty)(DotError.Usage(s"not tracked: $portable"))

    files = manifest.files.filterNot((repoPath, _) => doomed.exists(_._1 == repoPath))

    _     <- doomed.traverse_((repoPath, _) => removeIfExists(layout.filesDir + "/" + repoPath))
    _     <- saveManifest(layout, Manifest(files))
    state <- loadState(layout)
    _     <- saveState(layout, SyncState(state.files.filter((repoPath, _) => files.contains(repoPath))))
    _     <- Git.in(layout.repo).commitIfChanged(s"dot: remove ${doomed.map(_._2).mkString(", ")}")

    untracked = doomed.map: (_, target) =>
      s"untracked $target (host copy kept)"

    lines = untracked :+ "committed — dot sync pushes"
  yield lines.mkString("\n")
}
