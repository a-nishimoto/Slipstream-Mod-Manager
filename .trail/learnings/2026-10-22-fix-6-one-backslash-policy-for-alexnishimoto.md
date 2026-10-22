---
title: "Fix #6: one backslash policy for validate and patch"
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
touches:
  - src/main/java/net/vhati/modmanager/core/ModPatchThread.java
  - src/main/java/net/vhati/modmanager/core/ModUtilities.java
  - src/main/java/net/vhati/modmanager/xml/JDOMModMetadataReader.java
  - src/test/java/net/vhati/modmanager/core/ValidateModFileTest.java
  - ".gemini/settings.json"
  - ".github/copilot-instructions.md"
  - AGENTS.md
  - GEMINI.md
  - TODO.md
  - opencode.json
---

Backslash-in-path is now WARNING + normalize in validateModFile, matching what patch always did -- an archive that 'failed validation' but installs fine was a lie to the user. ModUtilities.normalizeInnerPath() is the single conversion; three copies (validateModFile, ModPatchThread.patch, JDOMModMetadataReader.read) now call it, so the paths cannot drift again. Policy rationale (from TODO): Win32-authored zips are common and \\ -> / is lossless, so accepting with a warning beats rejecting. Decisions: patch keeps converting silently (per-entry warnings would spam the patch report; validate is where per-entry detail belongs); validate's warning names the treatment ('converted to forward slashes') so users can fix their zipping tool. Watch out: JDOMModMetadataReader also normalizes -- metadata lookup for a backslash zip works only through the shared helper. Test pins that data\notes.txt yields '~ Backslashes', never '!', and no stray-folder complaints (proof the normalized path, not the raw one, went through the root check).
