package dot

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import fs2.io.file.{Files, Path}
import fs2.io.process.ProcessBuilder
import fs2.{Stream, text}
import scala.util.control.NoStackTrace
import weaver.{Expectations, SimpleIOSuite, SourceLocation, TestName}

/** Exit status and captured streams of one finished process. */
final case class Run(code: Int, out: String, err: String)

/** Failures of the harness itself, as opposed to failed expectations. */
enum HarnessError extends RuntimeException with NoStackTrace {
  case NoBinary(path: Path)
  case Leaky(home: String)
  case Exited(command: String, run: Run)
  case Missing(path: Path)

  override def getMessage: String = this match
    case NoBinary(path)       => s"no dot binary at $path: install it, or point DOT_BIN at one"
    case Leaky(home)          => s"spawned processes see HOME=$home instead of the sandbox"
    case Exited(command, run) => s"$command exited ${run.code}\n${run.out}${run.err}"
    case Missing(path)        => s"no such file: $path"
}

extension (path: Path) {

  /** Reads a whole file; fails when it is missing. */
  def readText: IO[String] =
    path.readTextIfExists.flatMap: content =>
      IO.fromOption(content)(HarnessError.Missing(path))
}

/**
 * An isolated home, dot data directory and bare remote for one scenario. Every
 * process spawned through it runs under that home with a git identity of its
 * own, so the developer's real files stay out of reach.
 */
final case class Sandbox(root: Path, bin: Path) {
  val home      = root / "home"
  val dotHome   = root / "dothome"
  val remote    = root / "remote.git"
  val repo      = dotHome / "repo"
  val filesDir  = repo / "files"
  val conflicts = dotHome / "conflicts"

  private val gitconfig = root / "gitconfig"

  private val env = Map(
    "HOME"              -> home.toString,
    "DOT_HOME"          -> dotHome.toString,
    "GIT_CONFIG_GLOBAL" -> gitconfig.toString,
    "GIT_CONFIG_SYSTEM" -> "/dev/null",
  )

  /** The host copy of a file tracked at rel under home. */
  def host(rel: String): Path = home / rel

  /** The repo copy of a file tracked at rel. */
  def repoCopy(rel: String): Path = filesDir / rel

  /** The parked conflict copy of a file tracked at rel. */
  def parked(rel: String): Path = conflicts / rel

  /** Runs a command to completion under the sandbox environment, with input on its stdin. */
  def exec(command: String, args: List[String], input: String = ""): IO[Run] =
    ProcessBuilder(command, args).withExtraEnv(env).spawn[IO].use { p =>
      val feed = Stream.emit(input).through(text.utf8.encode).through(p.stdin).compile.drain
      val out  = p.stdout.through(text.utf8.decode).compile.string
      val err  = p.stderr.through(text.utf8.decode).compile.string
      (feed, out, err).parTupled.flatMap: (_, stdout, stderr) =>
        p.exitValue.map(Run(_, stdout, stderr))
    }

  /** Runs the binary; a non-zero exit aborts the scenario. Resolves to its stdout. */
  def dot(args: (String | Path)*): IO[String] = tryDot(args*) >>= succeeded(s"dot ${args.mkString(" ")}")

  /** Runs the binary and reports how it went, whatever the exit status. */
  def tryDot(args: (String | Path)*): IO[Run] = exec(bin.toString, args.map(_.toString).toList)

  /** Runs dot sync on a pseudo-terminal, typing keys at its prompts; resolves to everything it printed. */
  def tty(keys: String): IO[String] =
    exec("script", List("-qec", s"$bin sync", "/dev/null"), keys) >>= succeeded("dot sync on a tty")

  /** Runs git inside the synced clone, the way another host editing the remote would. */
  def git(args: String*): IO[String] = gitIn(repo, args*)

  /** Commits reachable on the remote; add and remove leave the count alone until a sync. */
  def remoteCommits: IO[Int] = gitIn(remote, "rev-list", "--count", "--all").map(_.toInt)

  /** Binds to the remote, then tracks the file at rel under home with content and syncs it up. */
  def track(rel: String, content: String): IO[Unit] =
    dot("bind", remote)
      *> host(rel).writeText(content)
      *> dot("add", host(rel))
      *> dot("sync").void

  /** Edits the tracked file on the host and lands a different edit on the remote, so the next sync conflicts. */
  def diverge(rel: String, onHost: String, onRemote: String): IO[Unit] =
    host(rel).writeText(onHost)
      *> repoCopy(rel).writeText(onRemote)
      *> git("commit", "-qam", s"change $rel from another host")
      *> git("push", "-q", "origin", "main").void

  /** Points pushes at a path that does not exist: an attempted push warns, a skipped one stays silent. */
  def breakPushes: IO[Unit] = git("config", "remote.origin.pushurl", (root / "nowhere").toString).void

  def restorePushes: IO[Unit] = git("config", "--unset", "remote.origin.pushurl").void

  /** Lays out the home, the git identity and the bare remote, then proves spawned processes see them. */
  private def prepared: IO[Sandbox] =
    home.ensureDir
      *> gitconfig.writeText(Sandbox.gitIdentity)
      *> gitIn(root, "init", "--quiet", "--bare", remote.toString)
      *> probe.as(this)

  /** Fails unless the environment reaches spawned processes; a leak would put the real home in play. */
  private def probe: IO[Unit] =
    exec("sh", List("-c", "printf %s \"$HOME\"")).flatMap: run =>
      IO.raiseUnless(run.out == home.toString)(HarnessError.Leaky(run.out))

  private def gitIn(dir: Path, args: String*): IO[String] = {
    val out = exec("git", "-C" :: dir.toString :: args.toList) >>= succeeded(s"git ${args.mkString(" ")}")
    out.map(_.trim)
  }

  private def succeeded(command: String)(run: Run): IO[String] =
    IO.raiseWhen(run.code != 0)(HarnessError.Exited(command, run)).as(run.out)
}

object Sandbox {

  private val gitIdentity = "[user]\n\tname = smoke\n\temail = smoke@test\n[init]\n\tdefaultBranch = main\n"

  /** The binary under test: DOT_BIN, or the installed one under the real home. */
  private def binary: IO[Path] =
    IO.fromEither(homeDir).flatMap { home =>
      val bin = envGet("DOT_BIN").map(Path(_)).getOrElse(home / ".local/bin/dot")
      bin.isPresent.ifM(IO.pure(bin), IO.raiseError(HarnessError.NoBinary(bin)))
    }

  /** A fresh sandbox: empty home, git identity, bare remote; the directory goes away on release. */
  def make: Resource[IO, Sandbox] = {
    for
      bin  <- Resource.eval(binary)
      root <- Files[IO].tempDirectory(None, "dot-test-", None)
      sb   <- Resource.eval(Sandbox(root, bin).prepared)
    yield sb
  }
}

/** A suite whose tests each run against a sandbox of their own. */
trait SandboxSuite extends SimpleIOSuite {
  def sandboxed(name: String)(body: Sandbox => IO[Expectations])(using loc: SourceLocation): Unit =
    test(TestName(name, loc, Set.empty))(Sandbox.make.use(body))
}
