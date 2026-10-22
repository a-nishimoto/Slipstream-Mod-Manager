---
title: "Fix #7: validate rejects '..' path segments"
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

validateModFile now ERRORs any entry with a whole path segment equal to '..' (dotDotSegmentPtn = (?:^|/)[.][.](?:/|$) -- anchored to segments so ..foo.txt stays legal). Placed right AFTER the backslash normalization so data\..\x is caught too -- that ordering is load-bearing, the test pins it with a win-style entry. Decisions: patch() untouched -- FolderPack's canonical startsWith containment already makes escape impossible and the other packs resolve such keys to odd-but-harmless names; the point is that an archive nobody can predict the install path of is broken, and validation must say so (audit: 'data/../x' used to pass). Segment-equality not substring: '..' as a name component, not inside names. Test asserts exactly 3 flags across slash/backslash/nested forms while ..weird.txt goes unflagged.
