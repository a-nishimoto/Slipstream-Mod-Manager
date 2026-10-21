---
title: Red-team audit of install-folder permissions and path handling
author: Alexnishimoto
date: 2026-10-21
branch: modernize-and-harden
touches:
  - ".gemini/settings.json"
  - ".github/copilot-instructions.md"
  - AGENTS.md
  - GEMINI.md
  - opencode.json
---

## Decided
Permission "validation" is existence-only: FTLUtilities.isDatsDirValid (L31) checks dat presence, never writability. The only permission code in the app is ModPatchThread.patch L136-139 `setWritable(true)` (return ignored). Everything else surfaces as raw IOException via ModPatchDialog "Patching failed: <e.toString()>". Internal FTL paths correctly use "/" everywhere (dat format); OS paths are built with "/" string concat which Win32 accepts — FolderPack.getFile is the only File.separator-aware comparison and it canonicalizes both sides (correct on Windows).

## Tried and ruled out
- FolderPack traversal defense is sound (canonical startsWith + separator); zip ".." segments can't escape, but validateModFile still accepts "data/../x" keys.
- Properties config round-trips Windows backslashes via store(), but hand-edited single backslashes corrupt (\t, \r).

## Watch out
- FTLUtilities L65: System.getProperty("WINEPREFIX") — must be getenv; custom Wine prefixes never found.
- junkFilePtn (ModUtilities L57) is case-sensitive: "thumbs[.]db" never matches Windows "Thumbs.db"; desktop.ini and AppleDouble ._ files also unfiltered.
- setWritable(true) is owner-only on POSIX and attribute-only on Windows — silent no-op for root-owned dats (Linux Steam, macOS /Applications) and ACL denies (Program Files).
- AtomicFileOutput.commit catches only AtomicMoveNotSupportedException; Windows Defender/game lock throws AccessDeniedException on Files.move.
- validateModFile rejects backslash paths but patch() silently converts them — validate and install disagree.
