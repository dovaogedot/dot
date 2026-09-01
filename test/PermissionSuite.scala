package dot

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.io.file.{Files, Path, PosixPermissions}

/**
 * The permission hint: a host copy the file system rejects names the sudo
 * command that applies the parked resolution by hand.
 */
object PermissionSuite extends SandboxSuite {

  private val conf = "cfg/conf"

  /** Tracks cfg/conf, parks a conflict on it, and resolves the parked copy by hand. */
  private def resolved(sb: Sandbox): IO[Unit] =
    sb.track(conf, "original\n")
      *> sb.diverge(conf, "host change\n", "repo change\n")
      *> sb.tty("s\n").void
      *> writeText(sb.conflicts / conf, "resolved\n")

  /** Sets the mode of a path from its octal form. */
  private def chmod(path: String, octal: String): IO[Unit] =
    IO.fromOption(PosixPermissions.fromOctal(octal))(IllegalArgumentException(octal)).flatMap: mode =>
      Files[IO].setPosixPermissions(Path(path), mode)

  /** Holds the directory read-only for the duration of use; the writable mode comes back on release. */
  private def readOnly(dir: String): Resource[IO, Unit] =
    Resource.make(chmod(dir, "555"))(_ => chmod(dir, "755"))

  sandboxed("a resolution the host directory rejects prints the sudo command") { sb =>
    for
      _   <- resolved(sb)
      run <- readOnly(sb.home / "cfg").surround(sb.tryDot("sync"))
    yield
      check(run.code != 0, "sync succeeded through a read-only directory")
        && run.err.has(s"permission denied — run: sudo cp ${sb.conflicts / conf} ${sb.home / conf}")
  }

  sandboxed("with permissions restored the resolution applies") { sb =>
    for
      _    <- resolved(sb)
      _    <- readOnly(sb.home / "cfg").surround(sb.tryDot("sync"))
      out  <- sb.dot("sync")
      host <- readText(sb.home / conf)
    yield
      out.has("resolved")
        && expect.same("resolved\n", host)
  }
}
