---
title: Portable test paths and cleanup on permission skips
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
id: l-b7a9d795
scope: repo
status: active
touches:
  - src/test/java/net/vhati/modmanager/core/FTLUtilitiesDatsDirTest.java
  - src/test/java/net/vhati/util/AtomicFileOutputTest.java
---

## Decided
- In src/test/java/net/vhati/modmanager/core/FTLUtilitiesDatsDirTest.java, countFlatpak normalizes backslashes to forward slashes before matching the Flatpak path fragment, so Windows counts both candidates.
- In src/test/java/net/vhati/util/AtomicFileOutputTest.java, both move-denial tests perform writes and permission assumptions inside try/finally cleanup. Cleanup restores directory writability and closes AtomicFileOutput even when an assumption skips the test; the transient test also joins its releaser thread.
- Refresh Trail after changes when requested; both fixes are reflected in the rebuilt graph.
## Tried and ruled out
- Maven tests could not run because mvn is not installed in this environment. git diff --check passed after the cleanup fix.
## Watch out
- An aborted assumption throws before subsequent statements execute. Any already-open staged output must be covered by cleanup before testing permission support, especially on Windows where open files prevent temporary-directory deletion.
