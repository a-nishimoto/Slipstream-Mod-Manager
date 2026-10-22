---
title: "Fix #4: WINEPREFIX is an environment variable"
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
touches:
  - src/main/java/net/vhati/modmanager/core/FTLUtilities.java
  - ".gemini/settings.json"
  - ".github/copilot-instructions.md"
  - AGENTS.md
  - GEMINI.md
  - TODO.md
  - opencode.json
---

FTLUtilities.findDatsDir now reads WINEPREFIX via System.getenv, not System.getProperty -- getProperty never sees environment variables, so every custom Wine prefix silently fell back to ~/.wine and Wine-installed GOG/Humble FTL was never found. No test: findDatsDir reads env/properties itself with no injection point, and the JVM forbids setting its own environment; a testable seam would need a candidate-list builder taking (env, props, home) as arguments -- deliberately not introduced for a one-liner. Verified no launcher script or docs pass -DWINEPREFIX, so nobody depended on the broken property read. Fix #8 will extend the same candidate list (libraryfolders.vdf, flatpak roots) -- that one should build the candidate list in a pure helper so ordering gets tested.
