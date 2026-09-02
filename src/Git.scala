package dot

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import fs2.io.process.ProcessBuilder
import fs2.{Stream, text}
import java.io.IOException
import mouse.all.*

/** Git invocations bound to a working directory. Git is the only external dependency. */

private final case class GitOutput(code: Int, stdout: String, stderr: String) {

  def succeeded: Boolean = code == 0

  /** The most useful failure text the invocation produced. */
  def errorText: String =
    Some(stderr.trim).filter(_.nonEmpty)
      .orElse(Some(stdout.trim).filter(_.nonEmpty))
      .getOrElse(s"exit code $code")
}

private def spawnError(args: List[String])(t: Throwable): DotError = {
  val output = t match
    case io: IOException if String.valueOf(io.getMessage).contains("Cannot run program") =>
      "git executable not found on PATH"
    case other => describe(other)
  DotError.Git(args, output)
}

/** One side of a three-way merge: the file, and the label that marks its hunks in the output. */
final case class MergeSide(path: Path, label: String)

/** A git handle: the working directory its invocations run in. */
opaque type Git = Option[Path]

object Git {
  def in(repo: Path): Git = Some(repo)
  val anywhere: Git = None
}

extension (git: Git) {

  /** Runs git with the given args and a closed stdin; fails only if git itself cannot be spawned. */
  private def raw(args: String*): IO[GitOutput] = {
    val argList = args.toList
    val base    = ProcessBuilder("git", argList)
    val builder = git.fold(base)(base.withWorkingDirectory)
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
  private def run(args: String*): IO[String] =
    raw(args*).flatMap: out =>
      IO.raiseUnless(out.succeeded)(DotError.Git(args.toList, out.errorText)).as(out.stdout.trim)

  /** The branch HEAD points at. */
  def currentBranch: IO[String] = run("symbolic-ref", "--short", "HEAD")

  /** The URL of the origin remote; fails when none is configured. */
  def originUrl: IO[String] = run("remote", "get-url", "origin")

  def setOriginUrl(url: String): IO[Unit] = run("remote", "set-url", "origin", url).void

  def addOrigin(url: String): IO[Unit] = run("remote", "add", "origin", url).void

  def fetchOrigin: IO[Unit] = run("fetch", "origin").void

  /** Clones the repository at url into the directory. */
  def clone(url: String, into: Path): IO[Unit] = run("clone", url, into.toString).void

  /** The abbreviated hash of HEAD's parent; None when HEAD has none. */
  def parentOfHead: IO[Option[String]] = run("rev-parse", "--short", "HEAD^").redeem(_ => None, Some(_))

  /** The content of a repository file at a ref; None when the ref or the file is absent. */
  def show(ref: String, file: String): IO[Option[String]] =
    raw("show", s"$ref:$file").map(out => out.succeeded.option(out.stdout))

  /**
   * Merges ours and theirs over base, marking every conflicting hunk with the
   * sides' labels; resolves to the merged text.
   */
  def mergeFile(ours: MergeSide, base: MergeSide, theirs: MergeSide): IO[String] = {
    val args = List(
      "merge-file",
      "-p",
      "-L",
      ours.label,
      "-L",
      base.label,
      "-L",
      theirs.label,
      ours.path.toString,
      base.path.toString,
      theirs.path.toString,
    )
    raw(args*).flatMap: out =>
      // merge-file exits with the conflict count (capped at 127); >127 is an error.
      IO.raiseWhen(out.code > 127)(DotError.Git(args, out.errorText)).as(out.stdout)
  }

  /** Stages everything and commits if the tree changed; resolves to whether a commit was made. */
  def commitIfChanged(message: String): IO[Boolean] = {
    val add    = run("add", "-A")
    val commit = run("commit", "-m", message)
    val dirty  = run("status", "--porcelain").map(_.nonEmpty)
    add *> dirty.ifM(commit.as(true), IO.pure(false))
  }

  /** Pulls the branch with rebase; an empty remote (nothing pushed yet) counts as up to date. */
  def pull(branch: String): IO[Boolean] = {
    val args = List("pull", "--rebase", "--autostash", "origin", branch)
    raw(args*).flatMap: out =>
      if out.succeeded then
        IO.pure(true)
      else if out.stderr.contains("couldn't find remote ref") then
        IO.pure(false)
      else
        IO.raiseError(DotError.Git(args, out.errorText))
  }

  /** Commits the branch holds that origin lacks; with no remote ref yet, every commit counts. */
  def pendingPushes(branch: String): IO[Int] =
    raw("rev-list", "--count", s"origin/$branch..HEAD").flatMap: out =>
      if out.succeeded then
        IO.pure(out.stdout.trim.toIntOption.getOrElse(0))
      else
        run("rev-list", "--count", "HEAD").map(_.toIntOption.getOrElse(0))

  /** Pushes HEAD to origin; a failure degrades to a warning line instead of an error. */
  def pushBestEffort: IO[Option[String]] =
    run("push", "origin", "HEAD").as(None: Option[String]).recover:
      case e: DotError.Git =>
        Some(
          "warning: push failed, the commit stays local until the next sync: "
            + e.output.split("\n", -1).headOption.getOrElse(""),
        )
}
