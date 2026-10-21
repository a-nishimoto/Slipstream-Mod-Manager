---
title: trail wiring graph misses cross-class static calls; cache is local
author: Alexnishimoto
date: 2026-10-21
branch: modernize-and-harden
touches:
  - ".gemini/settings.json"
  - ".github/copilot-instructions.md"
  - AGENTS.md
  - GEMINI.md
  - TODO.md
  - opencode.json
---

Verified the trail toolset end-to-end (freshness, repo_map, find_code, find_all, file_api, trace_calls, check, note, build). Two things to know: (1) The Java extractor resolves same-class calls and instance calls (via local var types) but NOT cross-class static calls written as ClassName.method(...) -- proven on pre-existing code: ModPatchThread.java:325 calls ModUtilities.isJunkFile and there is no calls-edge, and isDatsDirValid/validateModFile/findDatsDir show only same-class callers. So trail callers / trace_calls under-reports blast radius for static utility classes (FTLUtilities, ModUtilities, PermissionProbe -- this repo's dominant pattern). Use trail grep / trail_find_all (text search, finds every call site correctly) whenever a static utility is involved; trust trace_calls for instance-call topology. (2) .trail/cache/ is git-ignored local state: after any pull or big change run trail build (deterministic, $0) before trusting trail check freshness. The graft/meaning tier (trail build --deep) is optional and currently 0% -- structure queries and learnings work without it.
