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
      s"removed       ${facts.target} (on host; dot sync reinstalls it)"
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

    lines <- manifest.files.toList.sortBy(_._2).traverse: (repoPath, target) =>
      gather(layout, state, repoPath, target).flatMap: facts =>
        statusLine(layout, facts)

    branch  <- Git.in(layout.repo).run("symbolic-ref", "--short", "HEAD")
    pending <- pendingPushes(layout, branch)

    tracked = if lines.isEmpty then List("nothing tracked — dot add <path>") else Nil
    pushes  = if pending > 0 then List(s"$pending commit(s) to push — dot sync pushes them") else Nil
    all     = lines ::: tracked ::: pushes
  yield all.mkString("\n")
}
