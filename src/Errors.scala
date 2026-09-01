package dot

import cats.effect.IO
import cats.syntax.all.*
import scala.util.control.NoStackTrace

/** Typed failures surfaced by the CLI; anything else escaping to the edge is a defect. */
enum DotError extends RuntimeException with NoStackTrace {
  case Usage(reason: String)
  case Io(op: String, path: String, cause: String)
  case Git(args: List[String], output: String)
  case Config(reason: String)

  def render: String = this match
    case Usage(reason)       => s"dot: $reason"
    case Io(op, path, cause) => s"dot: $op $path: $cause"
    case Config(reason)      => s"dot: $reason"
    case Git(args, output)   =>
      s"dot: git ${args.mkString(" ")}\n  ${output.split("\n", -1).mkString("\n  ")}"
}

def describe(t: Throwable): String = Option(t.getMessage).getOrElse(t.toString)

extension [A](io: IO[A]) {

  /**
   * Adapts any raised failure into an Io error carrying the operation and
   * path. Apply at the effect boundary, where failures are still raw
   * exceptions.
   */
  def orIoError(op: String, path: String): IO[A] =
    io.adaptError:
      case t => DotError.Io(op, path, describe(t))
}
