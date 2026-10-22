---
title: "Fix #3: junk-file filter case-insensitive, covers Desktop.ini and AppleDouble"
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
touches:
  - src/main/java/net/vhati/modmanager/core/ModUtilities.java
  - src/test/java/net/vhati/modmanager/core/ValidateModFileTest.java
  - ".gemini/settings.json"
  - ".github/copilot-instructions.md"
  - AGENTS.md
  - GEMINI.md
  - TODO.md
  - opencode.json
---

junkFilePtn (ModUtilities) now compiles with Pattern.CASE_INSENSITIVE and gained two branches: desktop[.]ini$ and (?:^|/)[.][_].* (macOS AppleDouble ._ files). Both validate (validateModFile's Junk file ERROR) and patch (ModPatchThread's isJunkFile skip) share this one pattern, so one edit fixed both the miscounted validation and the junk actually packed into dats. Decisions: kept the check order in validateModFile -- junk is checked only under valid roots, so a __MACOSX/ tree still reports "Unsupported top-level folder" rather than "Junk file"; both are ERROR, escape/leak behavior is identical, and patch() ignores such entries anyway because no pack claims the root. Did not add a literal __MACOSX rule: every file macOS puts in that tree is AppleDouble-named and caught by the ._ rule; only the folder entry itself remains, which is correctly an unsupported-root error. Evidence the old bug was real: with the pre-fix pattern, img/Thumbs.db and friends validated clean and img/._ship.png fell into the PNG-check branch and logged 'Error while validating'. ValidateModFileTest now pins case-variants (Thumbs.db, thumbs.DB, Desktop.ini, DESKTOP.INI, ._ftl, ._ship.png) and the __MACOSX path.
