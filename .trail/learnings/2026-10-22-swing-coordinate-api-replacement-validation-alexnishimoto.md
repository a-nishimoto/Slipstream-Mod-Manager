---
title: Swing coordinate API replacement validation
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
id: l-d03b157a
scope: repo
status: active
touches:
  - src/main/java/net/vhati/modmanager/ui/ModInfoArea.java
---

## Decided
Replace only the three ModInfoArea viewToModel calls with viewToModel2D, retaining Java 17 and existing attribute/link logic. Full clean verify passed 115 tests. A displayed macOS JFrame exercised on the EDT matched old/new offsets at 18,786 points and verified hover, popup target/copy action, and empty/plain/outside sample behavior.
## Tried and ruled out
Did not launch an external browser; verified the URL attribute and traced the unchanged URI/Desktop.browse path instead. Temporary graphical harness avoids claiming headless tests prove rendered geometry.
## Watch out
Both APIs map to the nearest model position, not strict glyph bounds. Do not interpret this warning cleanup as introducing strict out-of-text hit testing. Detailed local evidence is in ignored agent_actions/03b-swing.md.
