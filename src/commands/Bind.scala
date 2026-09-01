package dot

import cats.effect.IO
import cats.syntax.all.*

/** dot bind <repo>: point this host at the git remote that stores the config files. */

private def rebind(layout: Layout, url: String): IO[String] = {
  val repo      = Git.in(layout.repo)
  val reconnect = repo.run("remote", "get-url", "origin")
    *> repo.run("remote", "set-url", "origin", url)
  reconnect.orElse(repo.run("remote", "add", "origin", url))
    *> repo.run("fetch", "origin").as(s"bound $url\nrepo: ${layout.repo}")
}

/** Ensures the cloned repo carries a manifest, committing one when the remote had none. */
private def ensureManifest(layout: Layout): IO[Unit] = {
  val init = saveManifest(layout, emptyManifest)
    *> Git.in(layout.repo).commitIfChanged("dot: init manifest").void
  exists(layout.manifestPath) >>= init.unlessA
}

private def cloneRepo(layout: Layout, url: String): IO[String] = {
  ensureDir(layout.root)
    *> Git.anywhere.run("clone", url, layout.repo)
    *> ensureManifest(layout).as(s"bound $url\nrepo: ${layout.repo}\nrun: dot sync")
}

def bind(url: String): IO[String] =
  resolveLayout.flatMap: layout =>
    exists(layout.repo + "/.git").ifM(rebind(layout, url), cloneRepo(layout, url))
