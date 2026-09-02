package dot

import cats.effect.IO
import cats.syntax.all.*

/** Points an existing clone at url and fetches from it. */
private def rebind(layout: Layout, url: String): IO[String] = {
  val repo      = Git.in(layout.repo)
  val setUrl    = repo.originUrl *> repo.setOriginUrl(url)
  val reconnect = setUrl <+> repo.addOrigin(url)
  reconnect *> repo.fetchOrigin.as(s"bound $url\nrepo: ${layout.repo}")
}

/** Makes sure the cloned repo has a manifest. If the remote had none, an empty one is committed. */
private def ensureManifest(layout: Layout): IO[Unit] = {
  val init = Manifest.empty.save(layout)
    *> Git.in(layout.repo).commitIfChanged("dot: init manifest").void
  layout.manifestPath.isPresent >>= init.unlessA
}

private def cloneRepo(layout: Layout, url: String): IO[String] =
  layout.root.ensureDir
    *> Git.anywhere.clone(url, layout.repo)
    *> ensureManifest(layout).as(s"bound $url\nrepo: ${layout.repo}\nrun: dot sync")

/**
 * dot bind: connects this host to the git remote that stores the config files. Clones it if this host
 * has no clone yet.
 */
def bind(url: String): IO[String] =
  Layout.resolve.flatMap { layout =>
    val fresh = cloneRepo(layout, url)
    val again = rebind(layout, url)
    layout.isBound.ifM(again, fresh)
  }
