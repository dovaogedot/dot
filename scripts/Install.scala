package dot

import cats.effect.std.Console
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all.*
import fs2.Stream
import fs2.io.process.ProcessBuilder
import java.io.File

/**
 * Installer: packages dot into a self-contained native binary in
 * ~/.local/bin and, when that directory is not on PATH, appends the export
 * to the current shell's config. Runs from the repo root:
 * scala run . -M dot.Install
 */

/** Builds the native binary, relaying the scala runner's own progress output. */
private def compileNative(out: String): IO[Unit] = {
  val builder = ProcessBuilder(
    "scala",
    List(
      "--power",
      "package",
      "--native-image",
      ".",
      "-o",
      out,
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
  build.orIoError("compile", out).flatMap: code =>
    IO.raiseWhen(code != 0)(DotError.Io("compile", out, s"scala package exited with code $code"))
}

/** The path as the config line spells it: under home it travels as $HOME. */
private def portableEntry(dir: String, home: String): String =
  if dir == home then
    "$HOME"
  else if dir.startsWith(home + "/") then
    "$HOME/" + dir.drop(home.length + 1)
  else
    dir

private def exportLine(entry: String): String = "export PATH=\"" + entry + ":$PATH\""

private final case class RcEdit(rc: String, line: String)

/** Config file and PATH line per shell, keyed by the basename of $SHELL. */
private val RC_BY_SHELL: Map[String, (String, String) => RcEdit] = Map(
  "bash" -> ((home, entry) => RcEdit(home + "/.bashrc", exportLine(entry))),
  "zsh"  -> { (home, entry) =>
    val zdot = envGet("ZDOTDIR").filter(_.nonEmpty) match
      case Some(raw) => raw.toSlash.normalize
      case None      => home
    RcEdit(zdot + "/.zshrc", exportLine(entry))
  },
  "fish" ->
    ((home, entry) =>
      RcEdit(home + "/.config/fish/config.fish", s"""fish_add_path "$entry"""")
    ),
)

/** Appends the line at the end of the file, on its own line. */
private def appendLine(text: Option[String], line: String): String =
  text.filter(_.nonEmpty) match
    case None                        => line + "\n"
    case Some(t) if t.endsWith("\n") => t + line + "\n"
    case Some(t)                     => t + "\n" + line + "\n"

/**
 * Makes dir reachable from new shells: no-op when PATH already has it,
 * otherwise appends the export to the shell's config. Resolves to a note for
 * the user; a config file that cannot be written degrades to a warning.
 */
private def ensureOnPath(home: String, dir: String): IO[String] = {
  val entries = envGet("PATH").getOrElse("").split(File.pathSeparator)
  if entries.contains(dir) then
    IO.pure("")
  else
    val shellPath = envGet("SHELL").getOrElse("").toSlash
    RC_BY_SHELL.get(shellPath.drop(shellPath.lastIndexOf('/') + 1)) match
      case None        => IO.pure(s"add $dir to PATH to run dot from any directory")
      case Some(build) =>
        val entry            = portableEntry(dir, home)
        val RcEdit(rc, line) = build(home, entry)
        val rcDisplay        = rc.contractTarget(home)
        val reload           = s"restart the shell, or: source $rcDisplay"
        readTextIfExists(rc)
          .flatMap { text =>
            if text.exists(t => t.contains(entry) || t.contains(dir)) then
              IO.pure(s"$rcDisplay already puts $entry on PATH — $reload")
            else
              writeText(rc, appendLine(text, line)).as(s"added to $rcDisplay: $line — $reload")
          }
          .recover:
            case e: DotError.Io =>
              s"warning: could not update $rcDisplay (${e.cause}); add $dir to PATH manually"
}

private def install: IO[String] = {
  for
    home <- IO.fromEither(homeDir)

    binDir  = home + "/.local/bin"
    display = binDir.contractTarget(home)

    _    <- ensureDir(binDir)
    _    <- compileNative(binDir + "/dot")
    note <- ensureOnPath(home, binDir)
  yield
    if note.isEmpty then s"installed dot to $display" else s"installed dot to $display\n$note"
}

object Install extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    install.attemptNarrow[DotError].flatMap:
      case Right(message) => IO.println(message).as(ExitCode.Success)
      case Left(error)    => Console[IO].errorln(error.render).as(ExitCode.Error)
}
