package polio

import cats.effect.IO
import cats.syntax.all.*
import mouse.all.*
import fs2.io.file.Path
import io.circe.syntax.*
import io.circe.{Codec, Printer, parser}
import scala.collection.immutable.SortedMap

/** The data directory layout, the committed manifest, and the sync state of this host. */

/** Reads an environment variable. None if it is not set. */
def envGet(name: String): Option[String] = sys.env.get(name)

/** The home directory of the user. A Config error if the environment does not name one. */
def homeDir: Either[PolioError, Path] = {
  val raw  = envGet("HOME") <+> envGet("USERPROFILE")
  val home = raw.filter(_.nonEmpty).map(Path(_).normalize)
  home.toRight(PolioError.Config("cannot locate the home directory: HOME / USERPROFILE is unset or unreadable"))
}

/** Where polio keeps its data on this host. */
final case class Layout(
  /** User home directory. */
  home: Path,
  /** Data root: $POLIO_HOME, or ~/.polio. */
  root: Path,
  /** The git clone that holds the manifest and the files. */
  repo: Path,
  /** The tracked file contents, stored by repo path. */
  filesDir: Path,
  /** The committed manifest inside the repo. */
  manifestPath: Path,
  /** The state of this host. It lives outside the repo, so it is never committed. */
  statePath: Path,
  /** Parked conflict copies that wait for a manual fix, stored by repo path. */
  conflictsDir: Path,
) {

  /** The repo copy of the file tracked at repoPath. */
  def repoFile(repoPath: String): Path = filesDir / repoPath

  /** The parked conflict copy of the file tracked at repoPath. */
  def parkedFile(repoPath: String): Path = conflictsDir / repoPath

  /** The .git directory of the repo. If it exists, the repo is cloned. */
  def gitDir: Path = repo / ".git"

  /** Whether the repo is cloned. */
  def isBound: IO[Boolean] = gitDir.isPresent

  /** Fails if the repo is not cloned or has no origin remote. */
  def requireBound: IO[Unit] = {
    val checkRemote = Git.in(repo).originUrl.void.adaptError:
      case _ => PolioError.Config("no remote configured — run: polio bind <repo>")
    isBound.flatMap: bound =>
      IO.raiseUnless(bound)(PolioError.Config("not bound — run: polio bind <repo>"))
        *> checkRemote
  }

  /** The absolute location for a path the user typed. A leading "~" means the home directory. */
  def locate(raw: String): Path =
    if raw == "~" then
      home
    else if raw.startsWith("~/") then
      home / raw.drop(2)
    else
      Path(raw).absolute.normalize

  /** A location as shown to the user. A location under home is shown as "~/...". */
  def display(location: Path): String = Target.contract(location, home).value
}

object Layout {

  /** The layout of this host. The data root is POLIO_HOME, or .polio in the home directory. */
  def resolve: IO[Layout] =
    IO.fromEither(homeDir).map { home =>
      val root = envGet("POLIO_HOME").filter(_.nonEmpty) match
        case Some(raw) => Path(raw).absolute.normalize
        case None      => home / ".polio"
      Layout(
        home = home,
        root = root,
        repo = root / "repo",
        filesDir = root / "repo/files",
        manifestPath = root / "repo/polio.json",
        statePath = root / "state.json",
        conflictsDir = root / "conflicts",
      )
    }
}

/** The shape of polio.json and state.json on disk. */
private final case class Doc(version: Int, files: Map[String, String]) derives Codec.AsObject

private object Doc {

  /** Prints JSON with a two-space indent and no space before the colon. */
  private val printer = Printer.spaces2.copy(colonLeft = "")

  /**
   * The files listed in the document text. A broken document or an unsupported version is a Config
   * error that names what.
   */
  def decode(text: String, what: String): Either[PolioError, Map[String, String]] =
    for
      doc <- parser.decode[Doc](text).leftMap: e =>
        PolioError.Config(s"$what: ${e.getMessage}")

      files <- Either.cond(doc.version == 1, doc.files, PolioError.Config(s"$what: unsupported version ${doc.version}"))
    yield files

  /** The document text for the files. Keys are sorted, and the text ends with a newline. */
  def render(files: Map[String, String]): String =
    SortedMap.from(files)
      |> (Doc(1, _).asJson)
      |> printer.print
      |> (_ + "\n")
}

/**
 * The manifest. It is committed at the repo root as polio.json and shared by every host. It maps each
 * repo path under files/ to the target where the file is installed.
 */
final case class Manifest(files: Map[String, Target]) {

  /** Whether the target is tracked. */
  def tracks(target: Target): Boolean = files.contains(target.repoPath)

  /** Writes the manifest into the repo. */
  def save(layout: Layout): IO[Unit] =
    files.view.mapValues(_.value).toMap
      |> Doc.render
      |> layout.manifestPath.writeText

  /** The targets that the other manifest tracks and this one does not. */
  def droppedFrom(other: Manifest): List[Target] =
    other.files.toList.collect:
      case (repoPath, target) if !files.contains(repoPath) => target
}

object Manifest {

  /** A manifest tracking nothing. */
  val empty: Manifest = Manifest(Map.empty)

  /** Decodes manifest text. A broken document or an unsupported version is a Config error. */
  def parse(text: String): Either[PolioError, Manifest] =
    Doc.decode(text, "polio.json").map: files =>
      Manifest(files.view.mapValues(Target(_)).toMap)

  /** The manifest in the repo. A Config error if there is none. */
  def load(layout: Layout): IO[Manifest] = {
    val missing = PolioError.Config(s"no manifest at ${layout.manifestPath} — run: polio bind <repo>")
    layout.manifestPath.readTextIfExists.flatMap:
      case None       => IO.raiseError(missing)
      case Some(text) => IO.fromEither(parse(text))
  }

  /** The manifest on the origin branch. Empty if the branch was never pushed or the text does not decode. */
  def atOrigin(layout: Layout, branch: String): IO[Manifest] =
    Git.in(layout.repo).show(s"origin/$branch", "polio.json").map: text =>
      text.flatMap(parse(_).toOption).getOrElse(empty)
}

/** The record of this host: for each file, the content hash both sides had after the last sync. */
final case class SyncState(files: Map[String, String]) {

  /** Writes the state for this host. */
  def save(layout: Layout): IO[Unit] =
    Doc.render(files)
      |> layout.statePath.writeText
}

object SyncState {

  /** The state before any sync. */
  val empty: SyncState = SyncState(Map.empty)

  /**
   * The state of this host. A missing or invalid state file gives the empty state, because the state
   * is only a cache.
   */
  def load(layout: Layout): IO[SyncState] =
    layout.statePath.readTextIfExists
      .map { text =>
        val decoded = text.flatMap: t =>
          Doc.decode(t, "state.json").toOption
        decoded.fold(empty)(SyncState(_))
      }
      .handleError(_ => empty)
}

/** The host name used in sync commit messages. */
def hostLabel: String =
  try
    java.net.InetAddress.getLocalHost.getHostName
  catch
    case _: Exception =>
      val fallback = envGet("HOSTNAME") <+> envGet("COMPUTERNAME")
      fallback.getOrElse("unknown-host")
