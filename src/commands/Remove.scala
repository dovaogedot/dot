package dot

import cats.effect.IO
import cats.syntax.all.*

/** dot remove <path>: stop tracking a file or directory; host copies stay in place. */

def remove(raw: String): IO[String] = {
  for
    layout   <- Layout.resolve
    manifest <- Manifest.load(layout)

    portable = Target.contract(layout.locate(raw), layout.home)
    doomed   = manifest.files.toList.filter: (_, target) =>
      target.within(portable)

    _ <- IO.raiseWhen(doomed.isEmpty)(DotError.Usage(s"not tracked: $portable"))

    files = manifest.files.filterNot((repoPath, _) => doomed.exists(_._1 == repoPath))

    _     <- doomed.traverse_((repoPath, _) => layout.repoFile(repoPath).removeIfExists)
    _     <- Manifest(files).save(layout)
    state <- SyncState.load(layout)
    _     <- SyncState(state.files.filter((repoPath, _) => files.contains(repoPath))).save(layout)
    _     <- Git.in(layout.repo).commitIfChanged(s"dot: remove ${doomed.map(_._2).mkString(", ")}")

    untracked = doomed.map: (_, target) =>
      s"untracked $target (host copy kept)"

    lines = untracked :+ "committed — dot sync pushes"
  yield lines.mkString("\n")
}
