package dot

import cats.effect.IO
import cats.syntax.all.*
import java.io.{FileDescriptor, FileInputStream}
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets.UTF_8

/** dot sync: pull, reconcile every tracked file with the host, commit, push. */

private final case class Facts(
  repoPath: String,
  target: String,
  hostPath: String,
  hostHash: Option[String],
  repoHash: Option[String],
  baseHash: Option[String],
)

/** What the three-way comparison detects for one tracked file. */
private enum Detected {
  case Clean, ToRepo, ToHost, Conflict, Missing
}

/**
 * How one tracked file was handled. A both-sides-changed file resolves into
 * Conflict (host copy kept), ConflictRepo (repo copy kept), or Parked (a
 * conflict-marked copy waits under conflictsDir; both sides stay untouched).
 * Resolved is a hand-edited parked copy applied to both sides.
 */
private enum Plan {
  case Clean, ToRepo, ToHost, Conflict, ConflictRepo, Parked, Resolved, Missing
}

/**
 * How every both-sides-changed file is resolved: Ask prompts per file, Force
 * keeps the host copy without asking.
 */
enum ConflictMode {
  case Ask, Force
}

/**
 * Three-way comparison against the hash recorded at the last sync: an
 * unchanged side yields to the changed one; a change on both sides is a
 * conflict.
 */
private def decide(facts: Facts): Detected = (facts.repoHash, facts.hostHash) match
  case (None, None)                                     => Detected.Missing
  case (None, Some(_))                                  => Detected.ToRepo
  case (Some(_), None)                                  => Detected.ToHost
  case (Some(repo), Some(host)) if repo == host         => Detected.Clean
  case (Some(repo), _) if facts.baseHash.contains(repo) => Detected.ToRepo
  case (_, Some(host)) if facts.baseHash.contains(host) => Detected.ToHost
  case _                                                => Detected.Conflict

private def gather(
  layout: Layout,
  state: SyncState,
  repoPath: String,
  target: String,
): IO[Facts] = {
  val hostPath = target.expandTarget(layout.home)
  for
    hostHash <- sha256IfExists(hostPath)
    repoHash <- sha256IfExists(layout.filesDir + "/" + repoPath)
  yield Facts(repoPath, target, hostPath, hostHash, repoHash, state.files.get(repoPath))
}

private def repoFileFor(layout: Layout, facts: Facts): String =
  layout.filesDir + "/" + facts.repoPath

private def parkedFileFor(layout: Layout, facts: Facts): String =
  layout.conflictsDir + "/" + facts.repoPath

private final case class Outcome(
  plan: Plan,
  repoPath: String,
  target: String,
  /** Hash both sides hold after the action; None drops the state entry. */
  hash: Option[String],
)

/** Everything constant across one sync run that conflict handling needs. */
private final case class SyncCtx(
  layout: Layout,
  mode: ConflictMode,
  interactive: Boolean,
)

private enum Choice {
  case Local, Repo, Skip
}

private val CHOICES: Map[String, Choice] = Map(
  "l"     -> Choice.Local,
  "local" -> Choice.Local,
  "r"     -> Choice.Repo,
  "repo"  -> Choice.Repo,
  "s"     -> Choice.Skip,
  "skip"  -> Choice.Skip,
)

private def menu(target: String): String = {
  s"conflict: $target changed both on this host and in the repo\n"
    + "  [l] keep local — the host copy wins; the repo copy stays in git history\n"
    + "  [r] keep repo  — overwrites the host copy\n"
    + "  [s] skip — park a conflict-marked copy to resolve by hand; both sides stay"
}

/** Stdin without the JVM's buffering, so reads consume only what they return. */
private val rawStdin = FileInputStream(FileDescriptor.in)

/**
 * Reads one input line, one byte per read so nothing past the newline is
 * consumed — a later prompt still sees lines the user typed or pasted ahead.
 * None at end of input.
 */
private def readLineRaw: Option[String] = {
  val bytes = scala.collection.mutable.ArrayBuffer.empty[Byte]
  var done = false
  var eof  = false
  while !done do
    val b = rawStdin.read
    if b < 0 then
      if bytes.isEmpty then eof = true
      done = true
    else if b == 10 then done = true
    else if b != 13 then bytes += b.toByte
  if eof then None else Some(String(bytes.toArray, UTF_8))
}

/** Reads one resolution from the terminal; end of input counts as skip. */
private def askChoice(target: String): IO[Choice] =
  IO.blocking {
    println(menu(target))
    var chosen: Option[Choice] = None
    var eof                    = false
    while chosen.isEmpty && !eof do
      print("choose [l/r/s]: ")
      System.out.flush
      readLineRaw match
        case None      => eof = true
        case Some(raw) => chosen = CHOICES.get(raw.trim.toLowerCase)
    chosen.getOrElse(Choice.Skip)
  }.orIoError("prompt", target)

/**
 * Whether stdin is attached to a terminal, probed through a child process
 * that inherits the descriptor; an unprobeable platform falls back to the
 * JVM console check.
 */
private val stdinIsTerminal: IO[Boolean] = IO.blocking {
  try
    val pb = new ProcessBuilder("test", "-t", "0")
    pb.redirectInput(Redirect.INHERIT)
    pb.start.waitFor == 0
  catch
    case _: Exception => System.console != null
}

/**
 * Writes a conflict-marked merge of the host and repo copies (empty merge
 * base, so shared lines pass through and differing regions become marked
 * hunks) to the parked path. Both originals stay untouched.
 */
private def park(layout: Layout, facts: Facts): IO[Unit] = {
  val base = layout.root + "/tmp-merge-base"
  val args = List(
    "merge-file",
    "-p",
    "-L",
    s"host: ${facts.target}",
    "-L",
    "base: empty",
    "-L",
    s"repo: files/${facts.repoPath}",
    facts.hostPath,
    base,
    repoFileFor(layout, facts),
  )
  for
    _   <- writeText(base, "")
    out <- Git.anywhere.raw(args*)

    // merge-file exits with the conflict count (capped at 127); >127 is an error.
    _ <- IO.raiseWhen(out.code > 127)(DotError.Git(args, out.errorText))

    _ <- writeText(parkedFileFor(layout, facts), out.stdout)
    _ <- removeIfExists(base)
  yield ()
}

/**
 * A both-sides-changed file: Force (or a non-terminal stdin under Ask) keeps
 * the host copy, Ask lets the user pick the resolution per file. The host
 * copy is the only side git history cannot restore, so every path that
 * discards it is an explicit choice.
 */
private def resolveConflict(ctx: SyncCtx, facts: Facts): IO[Outcome] = {
  val repoFile = repoFileFor(ctx.layout, facts)
  def outcome(plan: Plan, hash: Option[String]): Outcome =
    Outcome(plan, facts.repoPath, facts.target, hash)
  val keepLocal = copyFile(facts.hostPath, repoFile).as(outcome(Plan.Conflict, facts.hostHash))
  ctx.mode match
    case ConflictMode.Force => keepLocal
    case ConflictMode.Ask   =>
      if !ctx.interactive then keepLocal
      else
        askChoice(facts.target).flatMap:
          case Choice.Local => keepLocal
          case Choice.Repo  =>
            copyFile(repoFile, facts.hostPath).as(outcome(Plan.ConflictRepo, facts.repoHash))
          case Choice.Skip =>
            park(ctx.layout, facts).as(outcome(Plan.Parked, facts.baseHash))
}

private def hasConflictMarkers(text: String): Boolean =
  text.split("\n", -1).exists(_.startsWith("<<<<<<<"))

/**
 * A parked file overrides every mode: markers gone means the user resolved
 * it, so the content lands on both sides; markers still present hold the
 * conflict without re-asking. A parked file whose conflict no longer exists
 * is dropped.
 */
private def applyOne(ctx: SyncCtx, facts: Facts): IO[Outcome] = {
  val repoFile   = repoFileFor(ctx.layout, facts)
  val parkedFile = parkedFileFor(ctx.layout, facts)
  def outcome(plan: Plan, hash: Option[String]): Outcome =
    Outcome(plan, facts.repoPath, facts.target, hash)
  def fresh: IO[Outcome] = decide(facts) match
    case Detected.Clean    => IO.pure(outcome(Plan.Clean, facts.hostHash))
    case Detected.Missing  => IO.pure(outcome(Plan.Missing, None))
    case Detected.Conflict => resolveConflict(ctx, facts)
    case Detected.ToHost   =>
      copyFile(repoFile, facts.hostPath).as(outcome(Plan.ToHost, facts.repoHash))
    case Detected.ToRepo =>
      copyFile(facts.hostPath, repoFile).as(outcome(Plan.ToRepo, facts.hostHash))
  readTextIfExists(parkedFile).flatMap:
    case None         => fresh
    case Some(parked) =>
      if !hasConflictMarkers(parked) then
        for
          hash <- sha256IfExists(parkedFile)

          _ <- copyFile(parkedFile, facts.hostPath)
          _ <- copyFile(parkedFile, repoFile)
          _ <- removeIfExists(parkedFile)
        yield outcome(Plan.Resolved, hash)
      else if decide(facts) == Detected.Conflict then
        IO.pure(outcome(Plan.Parked, facts.baseHash))
      else
        removeIfExists(parkedFile) *> fresh
}

private def lineFor(outcome: Outcome, recover: Option[String], parkedAt: String): Option[String] =
  outcome.plan match
    case Plan.Clean    => None
    case Plan.ToRepo   => Some(s"host -> repo  ${outcome.target}")
    case Plan.ToHost   => Some(s"repo -> host  ${outcome.target}")
    case Plan.Conflict =>
      Some(
        s"host -> repo  ${outcome.target} (both sides changed: host copy kept)"
          + recover.map(r => s"\n  overwritten repo copy: $r").getOrElse(""),
      )
    case Plan.ConflictRepo =>
      Some(s"repo -> host  ${outcome.target} (both sides changed: repo copy kept, host copy overwritten)")
    case Plan.Parked =>
      Some(s"parked        ${outcome.target} (resolve $parkedAt, then dot sync; or dot sync --abort)")
    case Plan.Resolved =>
      Some(s"resolved      ${outcome.target} (parked copy applied to both sides)")
    case Plan.Missing =>
      Some(s"missing       ${outcome.target} (gone on host and in repo; dot remove to untrack)")

/**
 * Whether local commits exist that origin/<branch> lacks. A missing remote
 * ref (nothing pushed yet) or an unreadable count reports ahead, so the push
 * is attempted rather than silently withheld.
 */
private def aheadOfRemote(layout: Layout, branch: String): IO[Boolean] =
  Git.in(layout.repo)
    .raw("rev-list", "--count", s"origin/$branch..HEAD")
    .map(out => out.code != 0 || out.stdout.trim.toIntOption.exists(_ > 0))
    .handleError(_ => true)

/** Pulls with rebase; an empty remote (nothing pushed yet) counts as up to date. */
private def pull(layout: Layout, branch: String): IO[Boolean] = {
  val args = List("pull", "--rebase", "--autostash", "origin", branch)
  Git.in(layout.repo).raw(args*).flatMap: out =>
    if out.code == 0 then IO.pure(true)
    else if out.stderr.contains("couldn't find remote ref") then IO.pure(false)
    else IO.raiseError(DotError.Git(args, out.errorText))
}

private def requireBound(layout: Layout): IO[Unit] = {
  val checkRemote = Git.in(layout.repo).run("remote", "get-url", "origin").void.adaptError:
    case _ => DotError.Config("no remote configured — run: dot bind <repo>")
  exists(layout.repo + "/.git").flatMap: bound =>
    IO.raiseUnless(bound)(DotError.Config("not bound — run: dot bind <repo>"))
      *> checkRemote
}

private def summarize(
  layout: Layout,
  outcomes: List[Outcome],
  pushed: Boolean,
  pushWarning: Option[String],
  preSync: Option[String],
): String = {
  val repoDisplay      = layout.repo.contractTarget(layout.home)
  val conflictsDisplay = layout.conflictsDir.contractTarget(layout.home)
  val fileLines        = outcomes.flatMap { outcome =>
    val recover = preSync.filter(_ => outcome.plan == Plan.Conflict).map { ref =>
      val spec   = s"$ref:files/${outcome.repoPath}"
      val quoted = if spec.exists(_.isWhitespace) then s"\"$spec\"" else spec
      s"git -C $repoDisplay show $quoted"
    }
    lineFor(outcome, recover, conflictsDisplay + "/" + outcome.repoPath)
  }
  val clean     = outcomes.count(_.plan == Plan.Clean)
  val cleanLine =
    if clean > 0 then List(s"up to date: $clean file(s)") else Nil
  val nothingTracked =
    if outcomes.isEmpty then List("nothing tracked — dot add <path>") else Nil
  val pushLine = pushWarning match
    case Some(warning) => List(warning)
    case None          => if pushed then List("pushed") else Nil
  val lines =
    fileLines
      ::: cleanLine
      ::: nothingTracked
      ::: pushLine
  lines.mkString("\n")
}

def sync(mode: ConflictMode): IO[String] = {
  for
    layout      <- resolveLayout
    _           <- requireBound(layout)
    interactive <- stdinIsTerminal
    branch      <- Git.in(layout.repo).run("symbolic-ref", "--short", "HEAD")
    _           <- pull(layout, branch)
    manifest    <- loadManifest(layout)
    state       <- loadState(layout)

    ctx = SyncCtx(layout, mode, interactive)

    outcomes <- manifest.files.toList.sortBy(_._2).traverse: (repoPath, target) =>
      gather(layout, state, repoPath, target).flatMap: facts =>
        applyOne(ctx, facts)

    hashes = outcomes.flatMap: o =>
      o.hash.map(o.repoPath -> _)

    _         <- saveState(layout, SyncState(hashes.toMap))
    committed <- Git.in(layout.repo).commitIfChanged(s"dot: sync from $hostLabel")

    // The sync commit's parent holds the repo copies that conflicts
    // overwrote; its hash pins the printed retrieval command.
    preSync <-
      if committed && outcomes.exists(_.plan == Plan.Conflict) then
        Git.in(layout.repo).run("rev-parse", "--short", "HEAD^").redeem(_ => None, Some(_))
      else
        IO.pure(None)

    // Push only when the remote lacks commits: a new one from this sync, or
    // one stranded by an earlier failed push.
    wanted  <- if committed then IO.pure(true) else aheadOfRemote(layout, branch)
    warning <- if wanted then Git.in(layout.repo).pushBestEffort else IO.pure(None)
  yield summarize(layout, outcomes, wanted && warning.isEmpty, warning, preSync)
}

/** dot sync --abort: discards every parked conflict; host and repo copies stay as they are. */
def syncAbort: IO[String] = {
  for
    layout <- resolveLayout
    there  <- exists(layout.conflictsDir)

    message <-
      if !there then IO.pure("no parked conflicts")
      else
        walkFiles(layout.conflictsDir).flatMap: files =>
          removeTreeIfExists(layout.conflictsDir).as:
            if files.isEmpty then "no parked conflicts"
            else s"discarded ${files.length} parked conflict(s)"
  yield message
}
