package polio

import cats.effect.IO
import cats.syntax.all.*
import scala.util.control.NoStackTrace

/** The failures the CLI reports. Any other exception that reaches the top is a bug. */
enum PolioError extends RuntimeException with NoStackTrace {

  /** The command line asked for something impossible. reason says what. */
  case Usage(reason: String)

  /** A file system operation on path failed. cause is the error message. */
  case Io(op: String, path: String, cause: String)

  /** A git command failed or could not start. output is what git printed. */
  case Git(args: List[String], output: String)

  /** The data directory, manifest, or remote is not in a usable state. */
  case Config(reason: String)

  /** The message the CLI prints for the failure. */
  def render: String = this match
    case Usage(reason)       => s"polio: $reason"
    case Io(op, path, cause) => s"polio: $op $path: $cause"
    case Config(reason)      => s"polio: $reason"
    case Git(args, output)   =>
      s"polio: git ${args.mkString(" ")}\n  ${output.split("\n", -1).mkString("\n  ")}"
}

/** The message of a throwable, or its class name if it has no message. */
def describe(t: Throwable): String = Option(t.getMessage).getOrElse(t.toString)

extension [A](io: IO[A]) {

  /**
   * Turns any failure into an Io error with the operation and the path. Use it where failures are
   * still raw exceptions. A failure that is already a PolioError passes through unchanged.
   */
  def orIoError(op: String, path: String): IO[A] =
    io.adaptError:
      case t if !t.isInstanceOf[PolioError] => PolioError.Io(op, path, describe(t))
}
