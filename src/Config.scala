package dot

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Codec, Printer, parser}
import scala.collection.immutable.SortedMap

/** Data-directory layout, the committed manifest, and the per-host sync state. */

final case class Layout(
  /** User home directory, slash-normalized. */
  home: String,
  /** Data root: $DOT_HOME, or ~/.dot. */
  root: String,
  /** The git clone holding manifest and files. */
  repo: String,
  /** Tracked file contents, laid out by repo path. */
  filesDir: String,
  manifestPath: String,
  /** Per-host state; lives outside the repo so it is never committed. */
  statePath: String,
  /** Parked conflict copies awaiting hand-resolution, laid out by repo path. */
  conflictsDir: String,
)

def resolveLayout: IO[Layout] = {
  IO.fromEither(homeDir).map { home =>
    val root = envGet("DOT_HOME").filter(_.nonEmpty) match
      case Some(raw) => raw.toSlash.normalize
      case None      => home + "/.dot"
    Layout(
      home = home,
      root = root,
      repo = root + "/repo",
      filesDir = root + "/repo/files",
      manifestPath = root + "/repo/dot.json",
      statePath = root + "/state.json",
      conflictsDir = root + "/conflicts",
    )
  }
}

/**
 * Committed at the repo root as dot.json; shared by every host. Maps repo
 * paths under files/ to portable target paths ("~/..." or absolute).
 */
final case class Manifest(files: Map[String, String])

/** Per-host record of the content hash both sides held after the last sync. */
final case class SyncState(files: Map[String, String])

val emptyManifest: Manifest = Manifest(Map.empty)
val emptyState: SyncState   = SyncState(Map.empty)

/** The document shape both dot.json and state.json carry on disk. */
private final case class Doc(version: Int, files: Map[String, String]) derives Codec.AsObject

private def decodeDoc(text: String, what: String): Either[DotError, Map[String, String]] = {
  val doc = parser.decode[Doc](text).leftMap(e => DotError.Config(s"$what: ${e.getMessage}"))
  doc.flatMap: d =>
    if d.version == 1 then
      Right(d.files)
    else
      Left(DotError.Config(s"$what: unsupported version ${d.version}"))
}

/** Prints two-space indented JSON with JavaScript-style `"key": value` colons. */
private val printer = Printer.spaces2.copy(colonLeft = "")

private def serialize(files: Map[String, String]): String =
  printer.print(Doc(1, SortedMap.from(files)).asJson) + "\n"

/** Decodes manifest text; a malformed document or an unsupported version is a Config error. */
def parseManifest(text: String): Either[DotError, Manifest] = decodeDoc(text, "dot.json").map(Manifest(_))

def loadManifest(layout: Layout): IO[Manifest] = {
  val missing = DotError.Config(s"no manifest at ${layout.manifestPath} — run: dot bind <repo>")
  readTextIfExists(layout.manifestPath).flatMap:
    case None       => IO.raiseError(missing)
    case Some(text) => IO.fromEither(parseManifest(text))
}

def saveManifest(layout: Layout, m: Manifest): IO[Unit] =
  writeText(layout.manifestPath, serialize(m.files))

/** An unreadable or invalid state file degrades to the empty state: it is a cache. */
def loadState(layout: Layout): IO[SyncState] =
  readTextIfExists(layout.statePath)
    .map { text =>
      val decoded = text.flatMap(t => decodeDoc(t, "state.json").toOption)
      decoded.fold(emptyState)(SyncState(_))
    }
    .handleError(_ => emptyState)

def saveState(layout: Layout, s: SyncState): IO[Unit] =
  writeText(layout.statePath, serialize(s.files))

def hostLabel: String = {
  try
    java.net.InetAddress.getLocalHost.getHostName
  catch
    case _: Exception =>
      envGet("HOSTNAME").orElse(envGet("COMPUTERNAME")).getOrElse("unknown-host")
}
