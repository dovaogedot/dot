package dot

import cats.effect.std.Console
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.file.Path
import fs2.io.process.ProcessBuilder
import java.io.File

/**
 * Installer. Builds dot as a native binary in ~/.local/bin. If that directory is not on PATH, it adds
 * the PATH export to the config file of the current shell. Run it from the repo root: scala run . -M
 * dot.Install
 */

/** Builds the native binary and shows the progress output of the scala runner. */
private def compileNative(out: Path): IO[Unit] = {
  val builder = ProcessBuilder(
    "scala",
    List(
      "--power",
      "package",
      "--native-image",
      ".",
      "-o",
      out.toString,
      "-f",
      "--graalvm-args",
      "--no-fallback",
      // The builder heap is capped so image generation fits beside the
      // resident build server instead of aborting on low memory.
      "--graalvm-args",
      "-J-Xmx3g",
    ),
  )
  val build = builder.spawn[IO].use { p =>
    val closeIn = Stream.empty.through(p.stdin).compile.drain
    val relay   = (
      p.stdout.through(fs2.io.stdout[IO]).compile.drain,
      p.stderr.through(fs2.io.stderr[IO]).compile.drain,
    ).parTupled
    closeIn *> relay *> p.exitValue
  }
  build.orIoError("compile", out.toString).flatMap: code =>
    IO.raiseWhen(code != 0)(DotError.Io("compile", out.toString, s"scala package exited with code $code"))
}

extension (dir: Path) {

  /** The directory as written in the config line. A directory under home is written with $HOME. */
  private def portableEntry(home: Path): String =
    if dir == home then
      "$HOME"
    else if dir.startsWith(home) then
      "$HOME/" + home.relativize(dir)
    else
      dir.toString
}

private def exportLine(entry: String): String = "export PATH=\"" + entry + ":$PATH\""

/** A line to append to a shell config file. */
private final case class RcEdit(rc: Path, line: String)

private def bashRc(home: Path, entry: String): RcEdit = RcEdit(home / ".bashrc", exportLine(entry))

private def zshRc(home: Path, entry: String): RcEdit = {
  val zdot = envGet("ZDOTDIR").filter(_.nonEmpty).map(Path(_)).getOrElse(home)
  RcEdit(zdot / ".zshrc", exportLine(entry))
}

private def fishRc(home: Path, entry: String): RcEdit =
  RcEdit(home / ".config/fish/config.fish", s"""fish_add_path "$entry"""")

/** The config file and PATH line for each shell. The key is the file name of $SHELL. */
private val RC_BY_SHELL: Map[String, (Path, String) => RcEdit] = Map(
  "bash" -> bashRc,
  "zsh"  -> zshRc,
  "fish" -> fishRc,
)

/** Adds the line at the end of the text, on its own line. */
private def appendLine(text: Option[String], line: String): String =
  text.filter(_.nonEmpty) match
    case None                        => line + "\n"
    case Some(t) if t.endsWith("\n") => t + line + "\n"
    case Some(t)                     => t + "\n" + line + "\n"

/**
 * Makes dir available in new shells. Does nothing if PATH already has it. Otherwise adds the export
 * line to the shell config. Returns a note for the user. If the config file cannot be written, the
 * note is a warning.
 */
private def ensureOnPath(home: Path, dir: Path): IO[String] = {
  val entries = envGet("PATH").getOrElse("").split(File.pathSeparator)
  if entries.contains(dir.toString) then
    IO.pure("")
  else
    val shell = envGet("SHELL").map(Path(_).fileName.toString).getOrElse("")
    RC_BY_SHELL.get(shell) match
      case None        => IO.pure(s"add $dir to PATH to run dot from any directory")
      case Some(build) =>
        val entry            = dir.portableEntry(home)
        val RcEdit(rc, line) = build(home, entry)
        val rcDisplay        = Target.contract(rc, home).value
        val reload           = s"restart the shell, or: source $rcDisplay"
        def mentionsDir(text: String): Boolean = text.contains(entry) || text.contains(dir.toString)
        val updated = rc.readTextIfExists.flatMap: text =>
          if text.exists(mentionsDir) then
            IO.pure(s"$rcDisplay already puts $entry on PATH — $reload")
          else
            val content = appendLine(text, line)
            rc.writeText(content).as(s"added to $rcDisplay: $line — $reload")
        updated.recover:
          case e: DotError.Io =>
            s"warning: could not update $rcDisplay (${e.cause}); add $dir to PATH manually"
}

private def install: IO[String] =
  for
    home <- IO.fromEither(homeDir)

    binDir  = home / ".local/bin"
    display = Target.contract(binDir, home).value

    _    <- binDir.ensureDir
    _    <- compileNative(binDir / "dot")
    note <- ensureOnPath(home, binDir)
  yield
    if note.isEmpty then s"installed dot to $display" else s"installed dot to $display\n$note"

/** Entry point of the installer. Prints where dot was installed, or the error. */
object Install extends IOApp {

  /** Runs the installer. Arguments are ignored. */
  def run(args: List[String]): IO[ExitCode] =
    install.attemptNarrow[DotError].flatMap:
      case Right(message) => IO.println(message).as(ExitCode.Success)
      case Left(error)    => Console[IO].errorln(error.render).as(ExitCode.Error)
}
