---
title: Jackson properties iteration preserves tolerant update parsing
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
id: l-4e1dae47
scope: repo
status: active
touches:
  - src/main/java/net/vhati/modmanager/json/JacksonAutoUpdateReader.java
---

## Decided
Replace JacksonAutoUpdateReader.parse's fields() with properties().iterator() without dependency, import, or exception-handling changes. The new JacksonAutoUpdateReaderTest passed against the old iteration first, then clean verify passed with the replacement: 115 tests, zero failures/errors/skips.
## Tried and ruled out
Context7 returned Jackson 3 source examples; do not copy tools.jackson imports. Compilation against the pinned Jackson 2 dependency confirms the API. No stricter feed validation or unrelated Swing fixes belong here.
## Watch out
Empty object, array, null, number, and string urls currently produce an empty URL map; a missing urls property returns no data via RuntimeException handling. Preserve this tolerance. Tests also cover multiple OS URLs, version, notice, changelog order/content, and hidden releases. MalformedFeedTest remains the malformed-root regression suite.
