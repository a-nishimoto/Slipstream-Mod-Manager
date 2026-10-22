---
title: "Fix #8: Steam libraryfolders.vdf and flatpak in game discovery"
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
  - src/test/java/net/vhati/modmanager/core/FTLUtilitiesDatsDirTest.java
---

findDatsDir() split into: env reads + probe loop; buildDatsDirCandidates(home, xdgDataHome, winePrefix, pf86, pf, steamLibraries) pure ordered-list builder (package-private, directly tested); gatherSteamLibraries() merging vdf results; parseLibraryFoldersVdf() tolerant parser. Parser premise: every Steam format ever shipped (v1/v2 vdf blocks, v3 JSON-ish) puts the library in a "path" key optionally followed by a colon -- one line regex, doubled backslashes unescaped, never throws, dedup + empty-value skip; existence filtering happens in gatherSteamLibraries (skips unmounted drives). Library candidates expand to every layout shape (bare, /resources, /data, /data/resources, legacy SteamApps spelling, FTL.app/Contents/Resources) because which shape applies depends on the library's platform, not the current OS -- nonexistent ones cost one isDirectory check. ORDER IS THE CONTRACT: classic locations first, vdf libraries second, flatpak (~/.var/app/com.valvesoftware.Steam/.local/share/Steam) last, so existing installs resolve identically to before -- pinned by classicLocationsAreProbedBeforeDerivedOnes. Deliberately skipped: OneDrive-redirected Documents (in the audit Finding but not the Action) -- no Steam layout puts the game under Documents, and FTL installs (Program Files, libraries) don't move with Known Folder redirection; revisit only with a user report. trail check's 2 conflicts are the known Fix #7 false positives (audit learning restated as 'ruled out').
