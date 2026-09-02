package dot

import cats.effect.IO
import cats.syntax.all.*
import mouse.all.*
import fs2.io.file.Path
import io.circe.syntax.*
import io.circe.{Codec, Printer, parser}
import scala.collection.immutable.SortedMap

/** Data-directory layout, the committed manifest, and the per-host sync state. */

/** Reads an environment variable; None when unset. */
def envGet(name: String): Option[String] = sys.env.get(name)

def homeDir: Either[DotError, Path] = {
  val raw = envGet("HOME").orElse(envGet("USERPROFILE")).filter(_.nonEmpty)
  Either.fromOption(
    raw.map(Path(_).normalize),
    DotError.Config("cannot locate the home directory: HOME / USERPROFILE is unset or unreadable"),
  )
}

final case class Layout(
  /** User home directory. */
  home: Path,
  /** Data root: $DOT_HOME, or ~/.dot. */
  root: Path,
  /** The git clone holding manifest and files. */
  repo: Path,
  /** Tracked file contents, laid out by repo path. */
  filesDir: Path,
  manifestPath: Path,
  /** Per-host state; lives outside the repo so it is never committed. */
  statePath: Path,
  /** Parked conflict copies awaiting hand-resolution, laid out by repo path. */
  conflictsDir: Path,
) {

  def repoFile(repoPath: String): Path = filesDir / repoPath

  def parkedFile(repoPath: String): Path = conflictsDir / repoPath

  def gitDir: Path = repo / ".git"

  def isBound: IO[Boolean] = gitDir.isPresent

  /** Fails unless the repo is cloned and points at an origin remote. */
  def requireBound: IO[Unit] = {
    val checkRemote = Git.in(repo).originUrl.void.adaptError:
      case _ => DotError.Config("no remote configured — run: dot bind <repo>")
    isBound.flatMap: bound =>
      IO.raiseUnless(bound)(DotError.Config("not bound — run: dot bind <repo>"))
        *> checkRemote
  }

  /** A path the user typed, as an absolute location; a leading "~" means home. */
  def locate(raw: String): Path =
    if raw == "~" then
      home
    else if raw.startsWith("~/") then
      home / raw.drop(2)
    else
      Path(raw).absolute.normalize

  /** A location rendered for the user, contracted to "~/..." under home. */
  def display(location: Path): String = Target.contract(location, home).value
}

object Layout {

  def resolve: IO[Layout] = {
    IO.fromEither(homeDir).map { home =>
      val root = envGet("DOT_HOME").filter(_.nonEmpty) match
        case Some(raw) => Path(raw).absolute.normalize
        case None      => home / ".dot"
      Layout(
        home = home,
        root = root,
        repo = root / "repo",
        filesDir = root / "repo/files",
        manifestPath = root / "repo/dot.json",
        statePath = root / "state.json",
        conflictsDir = root / "conflicts",
      )
    }
  }
}

/** The document shape both dot.json and state.json carry on disk. */
private final case class Doc(version: Int, files: Map[String, String]) derives Codec.AsObject

private object Doc {

  /** Prints two-space indented JSON with JavaScript-style `"key": value` colons. */
  private val printer = Printer.spaces2.copy(colonLeft = "")

  def decode(text: String, what: String): Either[DotError, Map[String, String]] = {
    parser.decode[Doc](text)
      .leftMap(e => DotError.Config(s"$what: ${e.getMessage}"))
      .flatMap: d =>
        Either.cond(d.version == 1, d.files, DotError.Config(s"$what: unsupported version ${d.version}"))
  }

  def render(files: Map[String, String]): String =
    SortedMap.from(files)
      |> (Doc(1, _).asJson)
      |> printer.print
      |> (_ + "\n")
}

/**
 * Committed at the repo root as dot.json; shared by every host. Maps repo
 * paths under files/ to the targets they are installed at.
 */
final case class Manifest(files: Map[String, Target]) {

  /** Whether the target is tracked. */
  def tracks(target: Target): Boolean = files.contains(target.repoPath)

  def save(layout: Layout): IO[Unit] =
    files.view.mapValues(_.value).toMap
      |> Doc.render
      |> layout.manifestPath.writeText

  /** Targets the other manifest tracks that this one no longer does. */
  def droppedFrom(other: Manifest): List[Target] =
    other.files.toList.collect:
      case (repoPath, target) if !files.contains(repoPath) => target
}

object Manifest {

  val empty: Manifest = Manifest(Map.empty)

  /** Decodes manifest text; a malformed document or an unsupported version is a Config error. */
  def parse(text: String): Either[DotError, Manifest] =
    Doc.decode(text, "dot.json").map: files =>
      Manifest(files.view.mapValues(Target(_)).toMap)

  def load(layout: Layout): IO[Manifest] = {
    val missing = DotError.Config(s"no manifest at ${layout.manifestPath} — run: dot bind <repo>")
    layout.manifestPath.readTextIfExists.flatMap:
      case None       => IO.raiseError(missing)
      case Some(text) => IO.fromEither(parse(text))
  }

  /** The manifest origin's branch holds; empty when the branch was never pushed or does not decode. */
  def atOrigin(layout: Layout, branch: String): IO[Manifest] =
    Git.in(layout.repo).show(s"origin/$branch", "dot.json").map: text =>
      text.flatMap(parse(_).toOption).getOrElse(empty)
}

/** Per-host record of the content hash both sides held after the last sync. */
final case class SyncState(files: Map[String, String]) {
  def save(layout: Layout): IO[Unit] =
    Doc.render(files)
      |> layout.statePath.writeText
}

object SyncState {

  val empty: SyncState = SyncState(Map.empty)

  /** An unreadable or invalid state file degrades to the empty state: it is a cache. */
  def load(layout: Layout): IO[SyncState] =
    layout.statePath.readTextIfExists
      .map { text =>
        val decoded = text.flatMap(t => Doc.decode(t, "state.json").toOption)
        decoded.fold(empty)(SyncState(_))
      }
      .handleError(_ => empty)
}

def hostLabel: String = {
  try
    java.net.InetAddress.getLocalHost.getHostName
  catch
    case _: Exception =>
      envGet("HOSTNAME").orElse(envGet("COMPUTERNAME")).getOrElse("unknown-host")
}
