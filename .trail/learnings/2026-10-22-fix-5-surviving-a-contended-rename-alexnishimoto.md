---
title: "Fix #5: surviving a contended rename in AtomicFileOutput.commit"
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
touches:
  - src/main/java/net/vhati/util/AtomicFileOutput.java
  - src/test/java/net/vhati/util/AtomicFileOutputTest.java
  - ".gemini/settings.json"
  - ".github/copilot-instructions.md"
  - AGENTS.md
  - GEMINI.md
  - TODO.md
  - opencode.json
---

commit() previously caught only AtomicMoveNotSupportedException; on Windows an AV scan of the just-staged file or the running game holding the destination makes Files.move throw AccessDeniedException, failing the whole patch after the payload was fully written. Now: moveIntoPlace(atomic) retries AccessDeniedException 5x at 250ms, commit() wraps the atomic/plain-replace pair so BOTH moves share the retry, and any final move IOException sets moveFailed so close() spares the staged file instead of deleting finished work (a delayed cleanup or the user can move it once unlocked; on Windows it usually cannot even be deleted while locked). Decisions: retry count/delay kept small (1.25s worst case) because PermissionProbe (Fix #1) already refuses patches where a lock is permanent -- by commit-time a denial is almost always transient; interrupt during a retry-sleep rethrows the AccessDeniedException rather than pretending success. Tests simulate the lock POSIX-style with a read-only parent dir (Files.move then gets EACCES); the lockWrites helper PROVES the denial took effect (root ignores mode bits) and skips the test otherwise, and leaves the dir locked -- callers unlock in finally or @TempDir cleanup breaks. Verified both tests fail against the pre-fix class (1 error, 1 failure).
