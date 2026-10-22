---
title: "Config path robustness: canonicalize ftl_dats_path on load (audit finding #9)"
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
touches:
  - src/main/java/net/vhati/modmanager/FTLModManager.java
  - src/main/java/net/vhati/modmanager/cli/SlipstreamCLI.java
  - src/main/java/net/vhati/modmanager/core/SlipstreamConfig.java
  - ".gemini/settings.json"
  - ".github/copilot-instructions.md"
  - AGENTS.md
  - GEMINI.md
  - TODO.md
  - opencode.json
  - src/test/java/net/vhati/modmanager/core/SlipstreamConfigTest.java
---

SlipstreamConfig.canonicalizeDatsPath(props) now runs right after props.load() in both config entry points (FTLModManager GUI, SlipstreamCLI.getConfig) — one shared static, because the GUI and CLI must never disagree about which directory the same config names. Rationale: ftl_dats_path is hand-edited and copied between machines, so relative segments, doubled separators and symlinks used to flow into every later path comparison (datsDir.exists, isDatsDirValid, the config-vs-detected prompt, config-vs---dats-dir), letting one directory look like two paths.

Deliberate boundary: only an *existing* path is rewritten; absent or blank values, and anything whose canonical path won't resolve (symlink loop -> IOException), are left byte-identical so validation and the reconfigure prompt report what the user actually wrote, not a normalized guess. Equality check means an already-canonical value is never re-set.

Companion doc fix: writeConfig's ftl_dats_path comment now warns that Properties files treat a lone backslash as an escape (D:\games decodes to D:games), so Windows users should use forward slashes or doubled backslashes — the GUI's own store via Properties.store always doubles them, which is why GUI-written configs always worked while hand-edited ones mangled.

Verified SlipstreamConfigTest (5 tests: messy path canonicalized, symlink resolved (POSIX-guarded), nonexistent left verbatim, canonical unchanged, absent/blank untouched) by neutering the helper body: exactly the messy-path and symlink tests fail. Full suite 109/109.
