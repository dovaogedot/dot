package dot

import cats.effect.IO
import cats.syntax.all.*

/** dot bind <repo>: point this host at the git remote that stores the config files. */

private def rebind(layout: Layout, url: String): IO[String] = {
  val repo      = Git.in(layout.repo)
  val setUrl    = repo.originUrl *> repo.setOriginUrl(url)
  val reconnect = setUrl.orElse(repo.addOrigin(url))
  reconnect *> repo.fetchOrigin.as(s"bound $url\nrepo: ${layout.repo}")
}

/** Ensures the cloned repo carries a manifest, committing one when the remote had none. */
private def ensureManifest(layout: Layout): IO[Unit] = {
  val init = Manifest.empty.save(layout)
    *> Git.in(layout.repo).commitIfChanged("dot: init manifest").void
  layout.manifestPath.isPresent >>= init.unlessA
}

private def cloneRepo(layout: Layout, url: String): IO[String] = {
  layout.root.ensureDir
    *> Git.anywhere.clone(url, layout.repo)
    *> ensureManifest(layout).as(s"bound $url\nrepo: ${layout.repo}\nrun: dot sync")
}

def bind(url: String): IO[String] =
  Layout.resolve.flatMap: layout =>
    layout.isBound.ifM(rebind(layout, url), cloneRepo(layout, url))
