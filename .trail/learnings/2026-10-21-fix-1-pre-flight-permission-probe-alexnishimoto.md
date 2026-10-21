---
title: "Fix #1: pre-flight permission probe design and choke points"
author: Alexnishimoto
date: 2026-10-21
branch: modernize-and-harden
touches:
  - src/main/java/net/vhati/modmanager/cli/SlipstreamCLI.java
  - src/main/java/net/vhati/modmanager/core/ModPatchThread.java
  - src/main/java/net/vhati/modmanager/ui/DatExtractDialog.java
  - src/main/java/net/vhati/modmanager/ui/ModPatchDialog.java
  - src/main/java/net/vhati/modmanager/ui/ProgressDialog.java
  - ".gemini/settings.json"
  - ".github/copilot-instructions.md"
  - AGENTS.md
  - GEMINI.md
  - opencode.json
---

PermissionProbe (core) is now the single write-check choke point. probe() runs at the top of ModPatchThread.patch() so GUI and CLI both get it; probeExtractDir() covers --extract-dats and the GUI Extract Dats. Decisions: (1) File.canWrite() is not trusted -- the probe reproduces the real operations (File.createTempFile in each dir, RandomAccessFile(f,"rw") on each existing dat), so a passing probe means the real write passes, and Windows file locks surface at the same open FTLPack/PkgPack will do. (2) The pre-existing setWritable(true) loop runs BEFORE the probe, so owned read-only dats are auto-fixed and the probe only refuses what cannot be fixed -- a chmod-444 smoke test on an owned file passes by design, not by bug. (3) The probe never creates datsDir (a wrong config path must not silently become an empty dir) but does create backupDir/extractDir, mirroring AtomicFileOutput. (4) describe() gives OS-specific advice: Windows -> modman_admin.exe; POSIX -> owner via Files.getOwner (walking to the nearest existing ancestor for DIR_UNCREATABLE), chmod -R u+w when owner==user else sudo chown; macOS adds a Full Disk Access hint under Documents/Desktop. (5) Strictly patch-time: user ruled out startup warnings. Error display: ProgressDialog.describeException() shows getMessage() not toString() so multi-line advice is not buried; the CLI reprints the failure message at the end via SilentPatchObserver.getException().
