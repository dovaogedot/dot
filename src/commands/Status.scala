package dot

import cats.effect.IO
import cats.syntax.all.*

/** dot status: every tracked file and what dot sync would do; reads local state only. */

/** Commits the local branch holds that origin lacks; with no remote ref yet, every commit counts. */
private def pendingPushes(layout: Layout, branch: String): IO[Int] = {
  val repo = Git.in(layout.repo)
  repo.raw("rev-list", "--count", s"origin/$branch..HEAD").flatMap: out =>
    if out.code == 0 then
      IO.pure(out.stdout.trim.toIntOption.getOrElse(0))
    else
      repo.run("rev-list", "--count", "HEAD").map(_.toIntOption.getOrElse(0))
}

/**
 * The files origin's manifest tracks. Empty when the branch was never pushed
 * or its manifest does not decode, leaving nothing to compare against.
 */
private def pushedFiles(layout: Layout, branch: String): IO[Map[String, String]] =
  Git.in(layout.repo).raw("show", s"origin/$branch:dot.json").map: out =>
    parseManifest(out.stdout).map(_.files).getOrElse(Map.empty)

/**
 * Untrackings the remote has not seen: dot remove drops the manifest entry and
 * commits, so a target origin still tracks and this manifest does not is one
 * waiting to be pushed. Pairs each target with its line.
 */
private def removedLines(pushed: Map[String, String], manifest: Manifest): List[(String, String)] =
  pushed.toList.collect:
    case (repoPath, target) if !manifest.files.contains(repoPath) =>
      target -> s"removed       $target (untracked; dot sync pushes the removal)"

private def freshLine(facts: Facts): String = decide(facts) match
  case Detected.Clean =>
    s"up to date    ${facts.target}"
  case Detected.Missing =>
    s"missing       ${facts.target} (gone on host and in repo; dot remove to untrack)"
  case Detected.Conflict =>
    s"conflict      ${facts.target} (both sides changed; dot sync asks)"
  case Detected.ToRepo =>
    if facts.repoHash.isEmpty then
      s"added         ${facts.target} (dot sync copies it to the repo)"
    else
      s"modified      ${facts.target} (dot sync: host -> repo)"
  case Detected.ToHost =>
    if facts.hostHash.isEmpty then
      s"missing       ${facts.target} (gone from the host; dot sync reinstalls it — dot remove to untrack)"
    else
      s"modified      ${facts.target} (dot sync: repo -> host)"

private def statusLine(layout: Layout, facts: Facts): IO[String] = {
  val conflictsDisplay = layout.conflictsDir.contractTarget(layout.home)
  readTextIfExists(parkedFileFor(layout, facts)).map:
    case None         => freshLine(facts)
    case Some(parked) =>
      if hasConflictMarkers(parked) then
        s"parked        ${facts.target} (resolve $conflictsDisplay/${facts.repoPath}, then dot sync)"
      else
        s"resolved      ${facts.target} (dot sync applies it to both sides)"
}

def status: IO[String] = {
  for
    layout   <- resolveLayout
    _        <- requireBound(layout)
    manifest <- loadManifest(layout)
    state    <- loadState(layout)

    tracked <- manifest.files.toList.traverse: (repoPath, target) =>
      gather(layout, state, repoPath, target).flatMap: facts =>
        statusLine(layout, facts).map(target -> _)

    branch  <- Git.in(layout.repo).run("symbolic-ref", "--short", "HEAD")
    pushed  <- pushedFiles(layout, branch)
    pending <- pendingPushes(layout, branch)

    shown   = tracked ::: removedLines(pushed, manifest)
    lines   = shown.sortBy(_._1).map(_._2)
    nothing = if manifest.files.isEmpty then List("nothing tracked — dot add <path>") else Nil
    pushes  = if pending > 0 then List(s"$pending commit(s) to push — dot sync pushes them") else Nil
    all     = lines ::: nothing ::: pushes
  yield all.mkString("\n")
}
