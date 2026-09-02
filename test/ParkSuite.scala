package dot

import cats.effect.IO
import cats.syntax.all.*

/**
 * Parked conflicts and push gating. A broken pushurl tells an attempted push
 * (a warning line) from a skipped one (silence).
 */
object ParkSuite extends SandboxSuite {

  private val rc       = ".bashrc"
  private val original = "line a\nline b\nline c\n"
  private val onHost   = "line a HOST\nline b\nline c\n"
  private val onRemote = "line a\nline b\nline c REPO\n"
  private val merged   = "line a HOST\nline b\nline c REPO\n"

  /** Tracks .bashrc, edits it on both sides, breaks pushes, and parks the conflict by skipping it at the menu. */
  private def parked(sb: Sandbox): IO[String] =
    sb.track(rc, original)
      *> sb.diverge(rc, onHost, onRemote)
      *> sb.breakPushes
      *> sb.tty("s\n")

  sandboxed("skip parks the conflict; nothing pushed or even attempted") { sb =>
    for
      out    <- parked(sb)
      marked <- sb.parked(rc).readText
      host   <- sb.host(rc).readText
      repo   <- sb.repoCopy(rc).readText
      status <- sb.dot("status")
    yield
      out.has("parked")
        && out.lacks("pushed")
        && out.lacks("warning: push failed")
        && check(marked.linesIterator.exists(_.startsWith("<<<<<<< host")), s"parked copy lacks markers:\n$marked")
        && expect.same(onHost, host)
        && expect.same(onRemote, repo)
        && status.has("parked        ~/.bashrc")
  }

  sandboxed("a parked file holds across syncs without re-asking, on a pipe too") { sb =>
    for
      _   <- parked(sb)
      out <- sb.dot("sync")
    yield
      out.has("parked")
        && out.lacks("host copy kept")
  }

  sandboxed("a hand-resolved parked copy lands on both sides; the push is attempted") { sb =>
    for
      _      <- parked(sb)
      _      <- sb.parked(rc).writeText(merged)
      status <- sb.dot("status")
      out    <- sb.dot("sync")
      host   <- sb.host(rc).readText
      repo   <- sb.repoCopy(rc).readText
      left   <- sb.parked(rc).isPresent
    yield
      status.has("resolved      ~/.bashrc")
        && out.has("resolved")
        && out.has("warning: push failed")
        && expect.same(merged, host)
        && expect.same(merged, repo)
        && check(!left, "parked copy not removed")
  }

  sandboxed("a stranded commit pushes on the next sync once the remote is reachable") { sb =>
    for
      _   <- parked(sb)
      _   <- sb.parked(rc).writeText(merged)
      _   <- sb.dot("sync")
      _   <- sb.restorePushes
      out <- sb.dot("sync")
    yield check(out.linesIterator.contains("pushed"), s"stranded commit not pushed:\n$out")
  }

  sandboxed("an up-to-date sync attempts no push") { sb =>
    for
      _   <- sb.track(rc, original)
      _   <- sb.breakPushes
      out <- sb.dot("sync")
    yield
      out.lacks("warning: push failed")
        && out.lacks("pushed")
  }

  sandboxed("sync --abort discards parked copies and reports counts") { sb =>
    for
      _       <- sb.track(rc, original)
      _       <- sb.diverge(rc, "host2\nline b\nline c\n", "line a\nline b\nrepo2\n")
      _       <- sb.tty("s\n")
      parked  <- sb.parked(rc).isPresent
      out     <- sb.dot("sync", "--abort")
      dirLeft <- sb.conflicts.isPresent
      host    <- sb.host(rc).readText
      repo    <- sb.repoCopy(rc).readText
      again   <- sb.dot("sync", "--abort")
    yield
      check(parked, "setup parking failed")
        && out.has("discarded 1 parked conflict")
        && check(!dirLeft, "conflicts dir remains")
        && host.has("host2")
        && repo.has("repo2")
        && again.has("no parked conflicts")
  }
}
