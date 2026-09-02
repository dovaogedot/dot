package dot

import weaver.Expectations.Helpers.{failure, success}
import weaver.{Expectations, SourceLocation}

/** Passes if the condition is true. The hint is shown on failure. */
def check(cond: Boolean, hint: => String)(using SourceLocation): Expectations =
  if cond then success else failure(hint)

extension (text: String) {

  /** Passes if the text contains needle. A failure shows the whole text. */
  def has(needle: String)(using SourceLocation): Expectations =
    check(text.contains(needle), s"«$needle» missing from:\n$text")

  /** Passes if the text does not contain needle. A failure shows the whole text. */
  def lacks(needle: String)(using SourceLocation): Expectations =
    check(!text.contains(needle), s"«$needle» present in:\n$text")
}
