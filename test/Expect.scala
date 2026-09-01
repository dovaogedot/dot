package dot

import weaver.Expectations.Helpers.{failure, success}
import weaver.{Expectations, SourceLocation}

/** Passes when the condition holds; the hint explains a failure. */
def check(cond: Boolean, hint: => String)(using SourceLocation): Expectations =
  if cond then success else failure(hint)

extension (text: String) {

  /** Passes when needle occurs in the text; a failure shows the whole text. */
  def has(needle: String)(using SourceLocation): Expectations =
    check(text.contains(needle), s"«$needle» missing from:\n$text")

  /** Passes when needle is absent from the text; a failure shows the whole text. */
  def lacks(needle: String)(using SourceLocation): Expectations =
    check(!text.contains(needle), s"«$needle» present in:\n$text")
}
