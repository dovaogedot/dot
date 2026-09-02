package dot

import cats.effect.IO
import cats.syntax.all.*
import fs2.io.file.Path
import java.io.{FileDescriptor, FileInputStream}
import java.lang.ProcessBuilder.Redirect
import java.nio.charset.StandardCharsets.UTF_8
import mouse.all.*

/** dot sync: pull, reconcile every tracked file with the host, commit, push. */

/** What the three-way comparison detects for one tracked file. */
private enum Detected {

  /** Both copies hold the same content. */
  case Clean

  /** The host copy changed since the last sync, or the repo has no copy yet. */
  case ToRepo

  /** The repo copy changed since the last sync, or the host has no copy. */
  case ToHost

  /** Both copies changed since the last sync. */
  case Conflict

  /** Neither side has the file. */
  case Missing
}

/** How one tracked file was handled. */
private enum Plan {

  /** Nothing to do: both copies already match. */
  case Clean

  /** The host copy was copied into the repo. */
  case ToRepo

  /** The repo copy was installed on the host. */
  case ToHost

  /** Both sides had changed; the host copy was kept and overwrote the repo copy. */
  case Conflict

  /** Both sides had changed; the repo copy was kept and overwrote the host copy. */
  case ConflictRepo

  /** Both sides had changed; a conflict-marked copy waits under conflictsDir, both sides untouched. */
  case Parked

  /** A hand-edited parked copy was applied to both sides. */
  case Resolved

  /** Neither side has the file; the manifest entry is stale. */
  case Missing
}

/** How a both-sides-changed file is resolved. */
enum ConflictMode {

  /** Prompt per file when stdin is a terminal; keep the host copy otherwise. */
  case Ask

  /** Keep the host copy without asking. */
  case Force
}

/** What is known about one tracked file: both copies' hashes and the hash recorded at the last sync. */
private final case class Facts(
  repoPath: String,
  target: Target,
  hostPath: Path,
  hostHash: Option[String],
  repoHash: Option[String],
  baseHash: Option[String],
) {

  /**
   * Three-way comparison against the hash recorded at the last sync: an
   * unchanged side yields to the changed one; a change on both sides is a
   * conflict.
   */
  def decide: Detected = (repoHash, hostHash) match
    case (None, None)                               => Detected.Missing
    case (None, Some(_))                            => Detected.ToRepo
    case (Some(_), None)                            => Detected.ToHost
    case (Some(repo), Some(host)) if repo == host   => Detected.Clean
    case (Some(repo), _) if baseHash.contains(repo) => Detected.ToRepo
    case (_, Some(host)) if baseHash.contains(host) => Detected.ToHost
    case _                                          => Detected.Conflict

  def outcome(plan: Plan, hash: Option[String]): Outcome = Outcome(plan, repoPath, target, hash)
}

private object Facts {

  def gather(layout: Layout, state: SyncState, repoPath: String, target: Target): IO[Facts] = {
    val hostPath = target.expand(layout.home)
    for
      hostHash <- hostPath.sha256IfExists
      repoHash <- layout.repoFile(repoPath).sha256IfExists
    yield Facts(repoPath, target, hostPath, hostHash, repoHash, state.files.get(repoPath))
  }
}

private final case class Outcome(
  plan: Plan,
  repoPath: String,
  target: Target,
  /** Hash both sides hold after the action; None drops the state entry. */
  hash: Option[String],
) {

  /** The report line for this file; None when nothing changed. */
  def line(recover: Option[String], parkedAt: String): Option[String] = plan match
    case Plan.Clean    => None
    case Plan.ToRepo   => Some(s"host -> repo  $target")
    case Plan.ToHost   => Some(s"repo -> host  $target")
    case Plan.Conflict =>
      Some(
        s"host -> repo  $target (both sides changed: host copy kept)"
          + recover.map(r => s"\n  overwritten repo copy: $r").getOrElse(""),
      )
    case Plan.ConflictRepo =>
      Some(s"repo -> host  $target (both sides changed: repo copy kept, host copy overwritten)")
    case Plan.Parked =>
      Some(s"parked        $target (resolve $parkedAt, then dot sync; or dot sync --abort)")
    case Plan.Resolved =>
      Some(s"resolved      $target (parked copy applied to both sides)")
    case Plan.Missing =>
      Some(s"missing       $target (gone on host and in repo; dot remove to untrack)")
}

/** The process's standard input, read without the JVM's buffering so reads consume only what they return. */
private object Stdin {

  private val raw = FileInputStream(FileDescriptor.in)

  /**
   * Reads one line, one byte per read so nothing past the newline is consumed
   * — a later prompt still sees lines typed or pasted ahead. None at end of
   * input.
   */
  def readLine: Option[String] = {
    val bytes = scala.collection.mutable.ArrayBuffer.empty[Byte]
    var done = false
    var eof  = false
    while !done do
      val b = raw.read
      if b < 0 then
        if bytes.isEmpty then eof = true
        done = true
      else if b == 10 then
        done = true
      else if b != 13 then
        bytes += b.toByte
    if eof then None else Some(String(bytes.toArray, UTF_8))
  }

  /**
   * Whether the input is attached to a terminal, probed through a child
   * process that inherits the descriptor; an unprobeable platform falls back
   * to the JVM console check.
   */
  val isTerminal: IO[Boolean] = IO.blocking {
    try
      val pb = new ProcessBuilder("test", "-t", "0")
      pb.redirectInput(Redirect.INHERIT)
      pb.start.waitFor == 0
    catch
      case _: Exception => System.console != null
  }
}

/** The resolutions the conflict menu offers. */
private enum Choice {

  /** Keep the host copy; the repo copy stays retrievable from git history. */
  case Local

  /** Keep the repo copy; the host copy is overwritten. */
  case Repo

  /** Park a conflict-marked copy to resolve by hand; both sides stay. */
  case Skip
}

private object Choice {

  val byInput: Map[String, Choice] = Map(
    "l"     -> Local,
    "local" -> Local,
    "r"     -> Repo,
    "repo"  -> Repo,
    "s"     -> Skip,
    "skip"  -> Skip,
  )

  def menu(target: Target): String = {
    s"conflict: $target changed both on this host and in the repo\n"
      + "  [l] keep local — the host copy wins; the repo copy stays in git history\n"
      + "  [r] keep repo  — overwrites the host copy\n"
      + "  [s] skip — park a conflict-marked copy to resolve by hand; both sides stay"
  }

  /** Reads one resolution from the terminal; end of input counts as skip. */
  def ask(target: Target): IO[Choice] =
    IO.blocking {
      println(menu(target))
      var chosen: Option[Choice] = None
      var eof                    = false
      while chosen.isEmpty && !eof do
        print("choose [l/r/s]: ")
        System.out.flush
        Stdin.readLine match
          case None        => eof = true
          case Some(input) => chosen = byInput.get(input.trim.toLowerCase)
      chosen.getOrElse(Skip)
    }.orIoError("prompt", target.value)
}

extension (text: String) {
  private def hasConflictMarkers: Boolean = text.split("\n", -1).exists(_.startsWith("<<<<<<<"))
}

extension (src: Path) {

  /** Copies onto a host path; a permission failure names the sudo command that applies it by hand. */
  private def copyToHost(hostPath: Path): IO[Unit] =
    src.copyTo(hostPath).adaptError:
      case e: DotError.Io if e.cause == "permission denied" =>
        DotError.Io(e.op, e.path, s"permission denied — run: sudo cp $src $hostPath")
}

/** Everything constant across one sync run that reconciling a file needs. */
private final case class SyncCtx(layout: Layout, mode: ConflictMode, interactive: Boolean) {

  /**
   * Writes a conflict-marked merge of the host and repo copies (empty merge
   * base, so shared lines pass through and differing regions become marked
   * hunks) to the parked path. Both originals stay untouched.
   */
  def park(facts: Facts): IO[Unit] = {
    val base   = layout.root / "tmp-merge-base"
    val merged = Git.anywhere.mergeFile(
      ours = MergeSide(facts.hostPath, s"host: ${facts.target}"),
      base = MergeSide(base, "base: empty"),
      theirs = MergeSide(layout.repoFile(facts.repoPath), s"repo: files/${facts.repoPath}"),
    )
    for
      _    <- base.writeText("")
      text <- merged
      _    <- layout.parkedFile(facts.repoPath).writeText(text)
      _    <- base.removeIfExists
    yield ()
  }

  /**
   * A both-sides-changed file: Force (or a non-terminal stdin under Ask) keeps
   * the host copy, Ask lets the user pick the resolution per file. The host
   * copy is the only side git history cannot restore, so every path that
   * discards it is an explicit choice.
   */
  def resolveConflict(facts: Facts): IO[Outcome] = {
    val repoFile  = layout.repoFile(facts.repoPath)
    val keepLocal = facts.hostPath.copyTo(repoFile).as(facts.outcome(Plan.Conflict, facts.hostHash))
    mode match
      case ConflictMode.Force => keepLocal
      case ConflictMode.Ask   =>
        if !interactive then
          keepLocal
        else
          Choice.ask(facts.target).flatMap:
            case Choice.Local => keepLocal
            case Choice.Repo  =>
              repoFile.copyToHost(facts.hostPath).as(facts.outcome(Plan.ConflictRepo, facts.repoHash))
            case Choice.Skip =>
              park(facts).as(facts.outcome(Plan.Parked, facts.baseHash))
  }

  /**
   * A parked file overrides every mode: markers gone means the user resolved
   * it, so the content lands on both sides; markers still present hold the
   * conflict without re-asking. A parked file whose conflict no longer exists
   * is dropped.
   */
  def reconcile(facts: Facts): IO[Outcome] = {
    val repoFile   = layout.repoFile(facts.repoPath)
    val parkedFile = layout.parkedFile(facts.repoPath)
    def fresh: IO[Outcome] = facts.decide match
      case Detected.Clean    => IO.pure(facts.outcome(Plan.Clean, facts.hostHash))
      case Detected.Missing  => IO.pure(facts.outcome(Plan.Missing, None))
      case Detected.Conflict => resolveConflict(facts)
      case Detected.ToHost   =>
        repoFile.copyToHost(facts.hostPath).as(facts.outcome(Plan.ToHost, facts.repoHash))
      case Detected.ToRepo =>
        facts.hostPath.copyTo(repoFile).as(facts.outcome(Plan.ToRepo, facts.hostHash))
    parkedFile.readTextIfExists.flatMap:
      case None         => fresh
      case Some(parked) =>
        if !parked.hasConflictMarkers then
          for
            hash <- parkedFile.sha256IfExists

            _ <- parkedFile.copyToHost(facts.hostPath)
            _ <- parkedFile.copyTo(repoFile)
            _ <- parkedFile.removeIfExists
          yield facts.outcome(Plan.Resolved, hash)
        else if facts.decide == Detected.Conflict then
          IO.pure(facts.outcome(Plan.Parked, facts.baseHash))
        else
          parkedFile.removeIfExists *> fresh
  }
}

private def summarize(
  layout: Layout,
  outcomes: List[Outcome],
  pushed: Boolean,
  pushWarning: Option[String],
  preSync: Option[String],
): String = {
  val repoDisplay      = layout.display(layout.repo)
  val conflictsDisplay = layout.display(layout.conflictsDir)
  val fileLines        = outcomes.flatMap { outcome =>
    val recover = preSync.filter(_ => outcome.plan == Plan.Conflict).map { ref =>
      val spec   = s"$ref:files/${outcome.repoPath}"
      val quoted = if spec.exists(_.isWhitespace) then s"\"$spec\"" else spec
      s"git -C $repoDisplay show $quoted"
    }
    outcome.line(recover, conflictsDisplay + "/" + outcome.repoPath)
  }
  val clean          = outcomes.count(_.plan == Plan.Clean)
  val cleanLine      = Option.when(clean > 0)(s"up to date: $clean file(s)").toList
  val nothingTracked = outcomes.isEmpty.option("nothing tracked — dot add <path>").toList
  val pushLine       = pushWarning.orElse(pushed.option("pushed")).toList
  val lines          =
    fileLines
      ::: cleanLine
      ::: nothingTracked
      ::: pushLine
  lines.mkString("\n")
}

def sync(mode: ConflictMode): IO[String] = {
  for
    layout      <- Layout.resolve
    _           <- layout.requireBound
    interactive <- Stdin.isTerminal
    repo = Git.in(layout.repo)
    branch   <- repo.currentBranch
    _        <- repo.pull(branch)
    manifest <- Manifest.load(layout)
    state    <- SyncState.load(layout)

    ctx = SyncCtx(layout, mode, interactive)

    outcomes <- manifest.files.toList.sortBy(_._2).traverse: (repoPath, target) =>
      Facts.gather(layout, state, repoPath, target) >>= ctx.reconcile

    hashes = outcomes.flatMap: o =>
      o.hash.map(o.repoPath -> _)

    _         <- SyncState(hashes.toMap).save(layout)
    committed <- repo.commitIfChanged(s"dot: sync from $hostLabel")

    // The sync commit's parent holds the repo copies that conflicts
    // overwrote; its hash pins the printed retrieval command.
    preSync <-
      if committed && outcomes.exists(_.plan == Plan.Conflict)
      then repo.parentOfHead
      else IO.pure(None)

    // Push only when the remote lacks commits: a new one from this sync, or
    // one stranded by an earlier failed push.
    pending <-
      if committed
      then IO.pure(true)
      else repo.pendingPushes(branch).map(_ > 0)
    warning <-
      if pending
      then repo.pushBestEffort
      else IO.pure(None)
  yield summarize(layout, outcomes, pending && warning.isEmpty, warning, preSync)
}

/** dot sync --abort: discards every parked conflict; host and repo copies stay as they are. */
def syncAbort: IO[String] = {
  for
    layout <- Layout.resolve
    there  <- layout.conflictsDir.isPresent

    message <-
      if !there then
        IO.pure("no parked conflicts")
      else
        layout.conflictsDir.walkFiles.flatMap: files =>
          layout.conflictsDir.removeTreeIfExists.as:
            if files.isEmpty
            then "no parked conflicts"
            else s"discarded ${files.length} parked conflict(s)"
  yield message
}
