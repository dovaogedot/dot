package dot

import cats.effect.IO
import cats.syntax.all.*
import mouse.all.*

/** dot status: every tracked file and what dot sync would do; reads local state only. */

extension (facts: Facts) {

  private def freshLine: String = facts.decide match
    case Detected.Clean =>
      s"up to date    ${facts.target}"
    case Detected.Missing =>
      s"missing       ${facts.target} (gone on host and in repo; dot remove to untrack)"
    case Detected.Conflict =>
      s"conflict      ${facts.target} (both sides changed; dot sync asks)"
    case Detected.ToRepo =>
      if facts.repoHash.isEmpty
      then s"added         ${facts.target} (dot sync copies it to the repo)"
      else s"modified      ${facts.target} (dot sync: host -> repo)"
    case Detected.ToHost =>
      if facts.hostHash.isEmpty
      then s"missing       ${facts.target} (gone from the host; dot sync reinstalls it — dot remove to untrack)"
      else s"modified      ${facts.target} (dot sync: repo -> host)"

  /** The status line, honoring a parked copy over the fresh comparison. */
  private def statusLine(layout: Layout): IO[String] = {
    val conflictsDisplay = layout.display(layout.conflictsDir)
    layout.parkedFile(facts.repoPath).readTextIfExists.map:
      case None         => facts.freshLine
      case Some(parked) =>
        if parked.hasConflictMarkers
        then s"parked        ${facts.target} (resolve $conflictsDisplay/${facts.repoPath}, then dot sync)"
        else s"resolved      ${facts.target} (dot sync applies it to both sides)"
  }
}

def status: IO[String] = {
  for
    layout   <- Layout.resolve
    _        <- layout.requireBound
    manifest <- Manifest.load(layout)
    state    <- SyncState.load(layout)

    tracked <- manifest.files.toList.traverse: (repoPath, target) =>
      Facts.gather(layout, state, repoPath, target).flatMap: facts =>
        facts.statusLine(layout).map(target -> _)

    repo = Git.in(layout.repo)
    branch  <- repo.currentBranch
    pushed  <- Manifest.atOrigin(layout, branch)
    pending <- repo.pendingPushes(branch)

    // dot remove drops the manifest entry and commits, so a target origin still
    // tracks and this manifest does not is an untracking waiting to be pushed.
    removed = manifest.droppedFrom(pushed).map: target =>
      target -> s"removed       $target (untracked; dot sync pushes the removal)"

    shown   = tracked ::: removed
    lines   = shown.sortBy(_._1).map(_._2)
    nothing = manifest.files.isEmpty.option("nothing tracked — dot add <path>").toList
    pushes  = Option.when(pending > 0)(s"$pending commit(s) to push — dot sync pushes them").toList
    all     = lines ::: nothing ::: pushes
  yield all.mkString("\n")
}
