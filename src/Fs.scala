package dot

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import fs2.hashing.{HashAlgorithm, Hashing}
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}
import java.nio.file.{AccessDeniedException, NoSuchFileException}

/** File-system effects over fs2 Files; failures surface as DotError.Io. */

enum FileKind {
  case Missing, RegularFile, Directory
}

def fileKind(path: String): IO[FileKind] = {
  val p = Path(path)
  Files[IO]
    .exists(p)
    .flatMap { there =>
      if !there then
        IO.pure(FileKind.Missing)
      else
        Files[IO].isDirectory(p).map(dir => if dir then FileKind.Directory else FileKind.RegularFile)
    }
    .orIoError("stat", path)
}

def exists(path: String): IO[Boolean] =
  Files[IO].exists(Path(path)).orIoError("stat", path)

def readTextIfExists(path: String): IO[Option[String]] =
  Files[IO]
    .readUtf8(Path(path))
    .compile
    .string
    .map(Some(_))
    .recover { case _: NoSuchFileException => None }
    .orIoError("read", path)

def ensureDir(path: String): IO[Unit] =
  Files[IO].createDirectories(Path(path)).orIoError("mkdir", path)

def writeText(path: String, text: String): IO[Unit] = {
  val write = Stream.emit(text).through(Files[IO].writeUtf8(Path(path))).compile.drain
  ensureDir(path.dirname) *> write.orIoError("write", path)
}

/** Copies content and, on POSIX systems, the attributes of the source. */
def copyFile(src: String, dst: String): IO[Unit] = {
  val flags = CopyFlags(CopyFlag.ReplaceExisting, CopyFlag.CopyAttributes)
  val copy  = Files[IO]
    .copy(Path(src), Path(dst), flags)
    .adaptError { case _: AccessDeniedException => DotError.Io("copy", s"$src -> $dst", "permission denied") }
    .orIoError("copy", s"$src -> $dst")
  ensureDir(dst.dirname) *> copy
}

def removeIfExists(path: String): IO[Unit] =
  Files[IO].deleteIfExists(Path(path)).void.orIoError("remove", path)

def removeTreeIfExists(path: String): IO[Unit] =
  Files[IO]
    .deleteRecursively(Path(path))
    .recover { case _: NoSuchFileException => () }
    .orIoError("remove", path)

/** Lists every regular file under dir, sorted; skips symlinks and ".git" trees. */
def walkFiles(dir: String): IO[List[String]] = {
  val root = Path(dir)
  def outsideGitTree(p: Path): Boolean =
    !root.relativize(p).names.dropRight(1).exists(_.toString == ".git")
  Files[IO]
    .walkWithAttributes(root)
    .filter(info => info.attributes.isRegularFile && !info.attributes.isSymbolicLink)
    .map(_.path)
    .filter(outsideGitTree)
    .map(p => p.toString.toSlash)
    .compile
    .toList
    .map(_.sorted)
    .orIoError("walk", dir)
}

/** Streams the file into a SHA-256 digest, hex-encoded; None when the file is missing. */
def sha256IfExists(path: String): IO[Option[String]] =
  Files[IO]
    .readAll(Path(path))
    .through(Hashing[IO].hash(HashAlgorithm.SHA256))
    .compile
    .lastOrError
    .map(d => Some(d.bytes.toArray.map(b => f"${b & 0xff}%02x").mkString))
    .recover { case _: NoSuchFileException => None }
    .orIoError("hash", path)
