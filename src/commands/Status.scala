package dot

import cats.effect.IO
import cats.syntax.all.*
import mouse.all.*

extension (facts: Facts) {

  /** The status line based only on the three-way comparison. */
  private def freshLine: String = facts.decide match
    case Detected.Clean =>
      s"up to date    ${facts.target}"
    case Detected.Missing =>
      s"missing       ${facts.target} (gone on host and in repo; dotup remove to untrack)"
    case Detected.Conflict =>
      s"conflict      ${facts.target} (both sides changed; dotup sync asks)"
    case Detected.ToRepo =>
      if facts.repoHash.isEmpty
      then s"added         ${facts.target} (dotup sync copies it to the repo)"
      else s"modified      ${facts.target} (dotup sync: host -> repo)"
    case Detected.ToHost =>
      if facts.hostHash.isEmpty
      then s"missing       ${facts.target} (gone from the host; dotup sync reinstalls it — dotup remove to untrack)"
      else s"modified      ${facts.target} (dotup sync: repo -> host)"

  /** The status line. A parked copy, if there is one, decides it before the comparison does. */
  private def statusLine(layout: Layout): IO[String] = {
    val conflictsDisplay = layout.display(layout.conflictsDir)
    layout.parkedFile(facts.repoPath).readTextIfExists.map:
      case None         => facts.freshLine
      case Some(parked) =>
        if parked.hasConflictMarkers
        then s"parked        ${facts.target} (resolve $conflictsDisplay/${facts.repoPath}, then dotup sync)"
        else s"resolved      ${facts.target} (dotup sync applies it to both sides)"
  }
}

/** dotup status: lists every tracked file and what dotup sync would do with it. Reads local state only. */
def status: IO[String] =
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

    // dotup remove drops the manifest entry and commits, so a target origin still
    // tracks and this manifest does not is an untracking waiting to be pushed.
    removed = manifest.droppedFrom(pushed).map: target =>
      target -> s"removed       $target (untracked; dotup sync pushes the removal)"

    shown   = tracked ::: removed
    lines   = shown.sortBy(_._1).map(_._2)
    nothing = manifest.files.isEmpty.option("nothing tracked — dotup add <path>").toList
    pushes  = Option.when(pending > 0)(s"$pending commit(s) to push — dotup sync pushes them").toList
    all     = lines ::: nothing ::: pushes
  yield all.mkString("\n")
