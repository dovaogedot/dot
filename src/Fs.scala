package dot

import cats.effect.IO
import cats.syntax.all.*
import fs2.Stream
import fs2.hashing.{HashAlgorithm, Hashing}
import fs2.io.file.{CopyFlag, CopyFlags, Files, Path}
import java.nio.file.{AccessDeniedException, NoSuchFileException}

/** File-system effects over fs2 Files; failures surface as DotError.Io. */

enum FileKind {

  /** Nothing exists at the path. */
  case Missing

  /** A regular file, or a symlink to one. */
  case RegularFile

  /** A directory, or a symlink to one. */
  case Directory
}

extension (path: Path) {

  def fileKind: IO[FileKind] =
    Files[IO]
      .exists(path)
      .ifM(
        Files[IO].isDirectory(path).ifF(FileKind.Directory, FileKind.RegularFile),
        IO.pure(FileKind.Missing),
      )
      .orIoError("stat", path.toString)

  def isPresent: IO[Boolean] = Files[IO].exists(path).orIoError("stat", path.toString)

  def readTextIfExists: IO[Option[String]] =
    Files[IO]
      .readUtf8(path)
      .compile
      .string
      .map(Some(_))
      .recover { case _: NoSuchFileException => None }
      .orIoError("read", path.toString)

  def ensureDir: IO[Unit] = Files[IO].createDirectories(path).orIoError("mkdir", path.toString)

  def writeText(text: String): IO[Unit] = {
    val write = Stream.emit(text).through(Files[IO].writeUtf8(path)).compile.drain
    path.parent.traverse_(_.ensureDir)
      *> write.orIoError("write", path.toString)
  }

  /** Copies content and, on POSIX systems, the attributes of the source. */
  def copyTo(dst: Path): IO[Unit] = {
    val flags = CopyFlags(CopyFlag.ReplaceExisting, CopyFlag.CopyAttributes)
    val copy  = Files[IO]
      .copy(path, dst, flags)
      .adaptError { case _: AccessDeniedException => DotError.Io("copy", s"$path -> $dst", "permission denied") }
      .orIoError("copy", s"$path -> $dst")
    dst.parent.traverse_(_.ensureDir)
      *> copy
  }

  def removeIfExists: IO[Unit] = Files[IO].deleteIfExists(path).void.orIoError("remove", path.toString)

  def removeTreeIfExists: IO[Unit] =
    Files[IO]
      .deleteRecursively(path)
      .recover { case _: NoSuchFileException => () }
      .orIoError("remove", path.toString)

  /** Lists every regular file under this directory, sorted; skips symlinks and ".git" trees. */
  def walkFiles: IO[List[Path]] = {
    def outsideGitTree(p: Path): Boolean =
      !path.relativize(p).names.dropRight(1).exists(_.toString == ".git")
    Files[IO]
      .walkWithAttributes(path)
      .filter(info => info.attributes.isRegularFile && !info.attributes.isSymbolicLink)
      .map(_.path)
      .filter(outsideGitTree)
      .compile
      .toList
      .map(_.sortBy(_.toString))
      .orIoError("walk", path.toString)
  }

  /** Streams the file into a SHA-256 digest, hex-encoded; None when the file is missing. */
  def sha256IfExists: IO[Option[String]] =
    Files[IO]
      .readAll(path)
      .through(Hashing[IO].hash(HashAlgorithm.SHA256))
      .compile
      .lastOrError
      .map(d => Some(d.bytes.toArray.map(b => f"${b & 0xff}%02x").mkString))
      .recover { case _: NoSuchFileException => None }
      .orIoError("hash", path.toString)

  /** The SHA-256 hash of the file as a hex string. A missing file is an Io error. */
  def sha256: IO[String] = {
    val missing = DotError.Io("hash", path.toString, "no such file")
    sha256IfExists.flatMap(IO.fromOption(_)(missing))
  }
}
