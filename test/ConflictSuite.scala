package dot

import cats.syntax.all.*

/** Conflict handling end to end: the flag surface, the non-terminal force rule, and the interactive menu. */
object ConflictSuite extends SandboxSuite {

  private val rc = ".bashrc"

  sandboxed("bind and add commit locally; the first sync pushes") { sb =>
    for
      _        <- sb.dot("bind", sb.remote)
      _        <- sb.host(rc).writeText("original\n")
      _        <- sb.dot("add", sb.host(rc))
      unsynced <- sb.remoteCommits
      pending  <- sb.dot("status")
      _        <- sb.dot("sync")
      synced   <- sb.remoteCommits
      current  <- sb.dot("status")
    yield
      expect.same(0, unsynced)
        && pending.has("up to date    ~/.bashrc")
        && pending.has("to push")
        && expect(synced > 0)
        && current.lacks("to push")
  }

  sandboxed("status reports a host-side modification") { sb =>
    for
      _      <- sb.track(rc, "original\n")
      _      <- sb.host(rc).writeText("tweak\n")
      status <- sb.dot("status")
    yield status.has("modified      ~/.bashrc (dot sync: host -> repo)")
  }

  sandboxed("unknown flags are rejected") { sb =>
    List("-x", "-m", "--merge").traverse(rejected(sb)).map(_.combineAll)
  }

  private def rejected(sb: Sandbox)(flag: String) =
    sb.tryDot("sync", flag).map(run => check(run.code != 0, s"sync $flag accepted"))

  sandboxed("-q suppresses stdout; -s suppresses stderr as well") { sb =>
    for
      _        <- sb.track(rc, "original\n")
      leading  <- sb.dot("-q", "status")
      trailing <- sb.dot("status", "--quiet")
      quiet    <- sb.tryDot("-q", "sync", "-x")
      shushed  <- sb.tryDot("-s", "sync", "-x")
      status   <- sb.dot("-s", "status")
    yield
      expect.same("", leading)
        && expect.same("", trailing)
        && check(quiet.code != 0, "-q sync -x accepted")
        && check(quiet.err.nonEmpty, "-q silenced stderr")
        && check(shushed.code != 0, "-s sync -x accepted")
        && expect.same("", shushed.err)
        && expect.same("", status)
  }

  sandboxed("non-terminal stdin resolves a conflict like --force") { sb =>
    for
      _      <- sb.track(rc, "original\n")
      _      <- sb.diverge(rc, "host change one\n", "repo change one\n")
      status <- sb.dot("status")
      out    <- sb.dot("sync")
      repo   <- sb.repoCopy(rc).readText
    yield
      status.has("conflict      ~/.bashrc")
        && out.has("host copy kept")
        && expect.same("host change one\n", repo)
  }

  sandboxed("menu offers [l/r/s] only; d is rejected; r keeps the repo copy") { sb =>
    for
      _    <- sb.track(rc, "original\n")
      _    <- sb.diverge(rc, "host change two\n", "repo change two\n")
      out  <- sb.tty("d\nr\n")
      host <- sb.host(rc).readText
    yield
      out.has("[l] keep local")
        && out.lacks("[d]")
        && out.has("choose [l/r/s]: choose [l/r/s]:")
        && out.has("repo copy kept")
        && expect.same("repo change two\n", host)
  }

  sandboxed("remove commits locally; status names it; sync pushes it") { sb =>
    for
      _       <- sb.track(rc, "original\n")
      before  <- sb.remoteCommits
      _       <- sb.dot("remove", sb.host(rc))
      removed <- sb.remoteCommits
      pending <- sb.dot("status")
      _       <- sb.dot("sync")
      after   <- sb.remoteCommits
      settled <- sb.dot("status")
    yield
      expect.same(before, removed)
        && pending.has("removed       ~/.bashrc (untracked; dot sync pushes the removal)")
        && expect(after > before)
        && settled.lacks("removed")
  }

  sandboxed("a host copy deleted by hand reads as missing, not removed") { sb =>
    for
      _      <- sb.track(rc, "original\n")
      _      <- sb.host(rc).removeIfExists
      status <- sb.dot("status")
      out    <- sb.dot("sync")
      host   <- sb.host(rc).readText
    yield
      status.has("missing       ~/.bashrc (gone from the host; dot sync reinstalls it — dot remove to untrack)")
        && status.lacks("removed")
        && out.has("repo -> host  ~/.bashrc")
        && expect.same("original\n", host)
  }
}
