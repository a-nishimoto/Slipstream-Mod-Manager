---
title: "trail's managed block owns only the marker-fenced region"
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
id: l-05875231
scope: repo
status: active
touches:
  - AGENTS.md
  - GEMINI.md
  - ".github/copilot-instructions.md"
  - opencode.json
---

Repo-specific trail guidance must live AFTER the `<!-- trail:end -->` marker, never inside the fence. trail owns exactly the region between `<!-- trail:start -->` and `<!-- trail:end -->` and preserves every line outside it (dist/hosts/sections.js: "Marker-fenced section upsert. Graft owns exactly the region between the markers; everything else in the file belongs to the user and is preserved."). The fenced body itself is generated verbatim by instructionBody() in dist/hosts/instructions.js, so an edit inside it is a local divergence that the next `trail init` (or an upgrade that re-syncs) silently rewrites — the reason the MCP-tool table and the session-memory loop were appended outside the fence in b94d3ef instead of patching the generated bullet list.

Consequence to keep: the three host files (AGENTS.md, GEMINI.md, .github/copilot-instructions.md) are byte-identical, fence plus appended section, so any wording change has to land in all three or they drift. Keeping them identical also means one diff/checksum check proves the set is consistent. Changing the managed block text itself is an upstream fix to trail, not something to patch per-repo.
