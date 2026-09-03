package polio

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.io.file.{Files, Path, PosixPermissions}

/**
 * The permission hint: when the file system refuses to write a host copy, the error names the sudo
 * command that applies the parked copy by hand.
 */
object PermissionSuite extends SandboxSuite {

  private val conf = "cfg/conf"

  /** Tracks cfg/conf, parks a conflict on it, and fixes the parked copy by hand. */
  private def resolved(sb: Sandbox): IO[Unit] =
    sb.track(conf, "original\n")
      *> sb.diverge(conf, "host change\n", "repo change\n")
      *> sb.tty("s\n").void
      *> sb.parked(conf).writeText("resolved\n")

  /** Sets the permissions of a path from an octal string. */
  private def chmod(path: Path, octal: String): IO[Unit] =
    IO.fromOption(PosixPermissions.fromOctal(octal))(IllegalArgumentException(octal)).flatMap: mode =>
      Files[IO].setPosixPermissions(path, mode)

  /** Makes the directory read-only while in use. The writable mode comes back on release. */
  private def readOnly(dir: Path): Resource[IO, Unit] = {
    val lock   = chmod(dir, "555")
    val unlock = chmod(dir, "755")
    Resource.make(lock)(_ => unlock)
  }

  sandboxed("a resolution the host directory rejects prints the sudo command") { sb =>
    for
      _   <- resolved(sb)
      run <- readOnly(sb.home / "cfg").surround(sb.tryPolio("sync"))
    yield
      check(run.code != 0, "sync succeeded through a read-only directory")
        && run.err.has(s"permission denied — run: sudo cp ${sb.parked(conf)} ${sb.host(conf)}")
  }

  sandboxed("with permissions restored the resolution applies") { sb =>
    for
      _    <- resolved(sb)
      _    <- readOnly(sb.home / "cfg").surround(sb.tryPolio("sync"))
      out  <- sb.polio("sync")
      host <- sb.host(conf).readText
    yield
      out.has("resolved")
        && expect.same("resolved\n", host)
  }
}
