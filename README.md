Slipstream Mod Manager (personal fork)
======================================

[![Build](https://github.com/a-nishimoto/Slipstream-Mod-Manager/actions/workflows/build.yml/badge.svg?branch=modernize-and-harden)](https://github.com/a-nishimoto/Slipstream-Mod-Manager/actions/workflows/build.yml)

Slipstream is a mod manager for [FTL: Faster Than Light](https://subsetgames.com/). It installs
several mods at once, applies their XML patches in order, and can revert the game to vanilla
afterwards.

**This is a personal fork.** The original is by Vhati:

* Upstream repository: https://github.com/Vhati/Slipstream-Mod-Manager
* Official binaries: https://sourceforge.net/projects/slipstreammodmanager/
* Forum thread: https://subsetgames.com/forum/viewtopic.php?f=12&t=17102
* Donate to Vhati: https://vhati.github.io/donate.html

All of the design and essentially all of the domain knowledge here are his. Slipstream is in turn
the successor to Grognak's Mod Manager
([GMM](https://subsetgames.com/forum/viewtopic.php?p=9994)), and inherits its `.ftl` mod format.

If you just want to manage FTL mods, **use the upstream release**. This fork exists to run the
tool on a current JDK and to fix defects found along the way; it is maintained for one person's
use and carries no release process, no support, and no guarantee it will be kept current.


What is different from upstream
-------------------------------

Upstream's last code change was in January 2018 and it no longer builds on any modern JDK. This
fork is versioned `1.9.2f` — the `f` marks it as a fork so it cannot be confused with an upstream
release.

* **Builds and runs on current JDKs.** Targets Java 17; CI covers 17, 21 and 25.
* **Dependencies updated.** All current, with zero known advisories, re-checked weekly.
* **Data-loss fixes.** A cancelled patch could corrupt `ftl.dat`; a missing backup could cause a
  modded `.dat` to be permanently recorded as the vanilla copy. Backups are now checksummed and
  files are written atomically.
* **Security fixes.** Mod archives could write outside the extraction directory, and mod XML could
  resolve external entities.
* **Patch-engine fixes.** `<mod:par op="AND">` did not intersect; character references above
  U+FFFF were corrupted; the reserved `xml:` prefix aborted patching.
* **Warnings reach the user.** Problems like "this mod patched nothing" previously went only to a
  log file that is truncated on every launch.
* **Tests.** There were none; there are now 79 across 12 classes.
* **The app-update check is off by default**, because its feed is upstream's and its downloads
  would replace this build. Mod-catalog updates still work.

`readme_changelog.txt` has the full list.


Requirements
------------

* **Java 17 or newer** — https://adoptium.net/
* **FTL** 1.01–1.6.3, any of Windows/macOS/Linux, Steam/GOG/standalone — https://subsetgames.com/


Running it
----------

Extract a built distribution, then from inside that folder:

```
java -jar modman.jar          # GUI
./modman.command              # GUI (macOS/Linux)
./modman-cli.sh --help        # command line
```

Slipstream refuses to start unless `mods/` exists in the working directory, so run it from the
extracted folder rather than pointing at the jar from elsewhere.

FTL is usually detected automatically. If not, you will be prompted for the folder containing
`ftl.dat` (or `data.dat` and `resource.dat` on FTL 1.5.13 and earlier). On macOS that folder is
inside `FTL.app/Contents/Resources`.


Building
--------

Requires a JDK 17 or newer. Nothing else — the Maven wrapper pins its own Maven.

```
./mvnw clean package          # mvnw.cmd on Windows
```

This produces, in `target/`:

* `modman.jar` — the runnable jar, with dependencies
* `SlipstreamModManager_1.9.2f-Unix.tar.gz`
* `SlipstreamModManager_1.9.2f-Win.zip`

`readme_developers.txt` covers the project layout and how the Windows launchers are generated.


Platform support, honestly
--------------------------

**macOS and Linux are verified.** The build, the test suite and the application have all been run
there, including against a real FTL 1.6 installation — patching, reverting and extracting the
game archives.

**Windows is partially verified, and one piece is not.**

*Verified:* CI builds and runs the full test suite on `windows-latest` against JDK 17, 21 and 25,
and smoke-tests the packaged jar there. This is not decorative — it has already caught two bugs
that macOS and Linux hid, including a file handle that leaked on a failed archive open and only
locked the file on Windows.

*Not verified:* **the bundled `modman.exe` and `modman_admin.exe` launchers.** These are prebuilt
binaries carried over from upstream. They locate a Java runtime by reading the legacy
`SOFTWARE\JavaSoft` registry keys, which current JDK distributions — Adoptium, Microsoft, Zulu —
do not create. On a machine with only a modern JDK installed, they may fail to find Java at all.

The Launch4j configuration in `skel_exe/` has been rewritten to fix this (it now searches
`%JAVA_HOME%` and `%PATH%`, and requires Java 17), but **the executables have not been
regenerated**, because doing so requires Launch4j on a Windows machine and I have not had one.
CI cannot substitute: its runners come with Java already configured, which is precisely the
condition the launchers fail under.

**If you are on Windows, run `java -jar modman.jar` directly.** That path is tested and works.
Treat the `.exe` files as untested until someone rebuilds them per `readme_developers.txt`.


License
-------

GPL-2.0, same as upstream. See `LICENSE`.

Copyright for the original work remains with David Millis (Vhati). This fork's changes are offered
under the same terms.
