package dot

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import fs2.io.process.ProcessBuilder
import fs2.{Stream, text}
import java.io.IOException

/** Git invocations bound to a working directory. Git is the only external dependency. */

final case class GitOutput(code: Int, stdout: String, stderr: String)

extension (out: GitOutput) {

  /** The most useful failure text the invocation produced. */
  def errorText: String =
    Some(out.stderr.trim).filter(_.nonEmpty)
      .orElse(Some(out.stdout.trim).filter(_.nonEmpty))
      .getOrElse(s"exit code ${out.code}")
}

private def spawnError(args: List[String])(t: Throwable): DotError = {
  val output = t match
    case io: IOException if String.valueOf(io.getMessage).contains("Cannot run program") =>
      "git executable not found on PATH"
    case other => describe(other)
  DotError.Git(args, output)
}

/** A git handle: the working directory its invocations run in. */
opaque type Git = Option[String]

object Git {
  def in(repo: String): Git = Some(repo)
  val anywhere: Git = None
}

extension (git: Git) {

  /** Runs git with the given args and a closed stdin; fails only if git itself cannot be spawned. */
  def raw(args: String*): IO[GitOutput] = {
    val argList = args.toList
    val base    = ProcessBuilder("git", argList)
    val builder = git.fold(base)(cwd => base.withWorkingDirectory(Path(cwd)))
    val run     = builder.spawn[IO].use { p =>
      val closeIn = Stream.empty.through(p.stdin).compile.drain
      val out     = p.stdout.through(text.utf8.decode).compile.string
      val err     = p.stderr.through(text.utf8.decode).compile.string
      closeIn *> (out, err).parTupled.flatMap: (stdout, stderr) =>
        p.exitValue.map(GitOutput(_, stdout, stderr))
    }
    run.adaptError:
      case t => spawnError(argList)(t)
  }

  /** Runs git, failing on a non-zero exit; resolves to trimmed stdout. */
  def run(args: String*): IO[String] =
    raw(args*).flatMap: out =>
      IO.raiseUnless(out.code == 0)(DotError.Git(args.toList, out.errorText)).as(out.stdout.trim)

  /** Stages everything and commits if the tree changed; resolves to whether a commit was made. */
  def commitIfChanged(message: String): IO[Boolean] = {
    val commit = run("status", "--porcelain").flatMap: status =>
      if status.isEmpty then IO.pure(false)
      else run("commit", "-m", message).as(true)
    run("add", "-A") *> commit
  }

  /** Pushes HEAD to origin; a failure degrades to a warning line instead of an error. */
  def pushBestEffort: IO[Option[String]] =
    run("push", "origin", "HEAD").as(None: Option[String]).recover:
      case e: DotError.Git =>
        Some(
          "warning: push failed, the commit stays local until the next sync: "
            + e.output.split("\n", -1).headOption.getOrElse(""),
        )
}
