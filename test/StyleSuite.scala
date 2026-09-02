package dot

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import weaver.SimpleIOSuite

/**
 * Style gate: an if whose then-branch sits inline on the if line keeps its
 * else on the same line; a multi-line if puts then at the end of the line with
 * each branch below.
 */
object StyleSuite extends SimpleIOSuite {

  private val elseOpens  = """^\s*else(\s|$)""".r
  private val ifKeyword  = """(^|[^A-Za-z0-9_])if\s""".r
  private val inlineThen = """\sthen\s+(\S)""".r
  private val elseAfter  = """(^|\s)else(\s|$)""".r

  /** An if line whose then-branch starts on the same line without an else there. */
  private def inlineIf(line: String): Boolean =
    ifKeyword.findFirstIn(line).isDefined && inlineThen.findFirstMatchIn(line).exists: m =>
      elseAfter.findFirstIn(line.substring(m.start(1))).isEmpty

  /** Line numbers where an else opens the line right after an inline if-then. */
  private def offenders(lines: List[String]): List[Int] =
    lines.zip(lines.drop(1)).zipWithIndex.collect:
      case ((prev, cur), i) if inlineIf(prev) && elseOpens.findFirstIn(cur).isDefined => i + 2

  private def report(file: Path): IO[List[String]] =
    file.readText.map: source =>
      offenders(source.linesIterator.toList).map(n => s"$file:$n: else on a new line after an inline if-then")

  /** Every Scala source of the project, relative to the working directory. */
  private def sources: IO[List[Path]] =
    List("src", "scripts", "test").flatTraverse(dir => Path(dir).walkFiles).map: files =>
      Path("Main.scala") :: files.filter(_.toString.endsWith(".scala"))

  test("no asymmetric if/else layouts") {
    for
      files <- sources
      found <- files.flatTraverse(report)
    yield check(found.isEmpty, found.mkString("\n"))
  }
}
