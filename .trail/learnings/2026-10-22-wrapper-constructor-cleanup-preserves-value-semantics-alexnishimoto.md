---
title: Wrapper constructor cleanup preserves value semantics
author: Alexnishimoto
date: 2026-10-22
branch: modernize-and-harden
id: l-45f7e0b9
scope: repo
status: active
touches:
  - src/main/java/net/vhati/modmanager/core/ComparableVersion.java
  - src/main/java/net/vhati/modmanager/core/ModUtilities.java
  - src/main/java/net/vhati/modmanager/core/XMLPatcher.java
  - src/main/java/net/vhati/modmanager/ui/table/ChecklistTableModel.java
  - src/main/java/net/vhati/modmanager/ui/table/TableRowTransferHandler.java
---

## Decided
Replaced the six deprecated Integer/Character/Boolean constructors with valueOf without changing stored types. ComparableVersion hashes a value-based List<Integer>; XMLPatcher orders Integer keys in a TreeMap; validation deduplicates Characters using List.contains; checkbox values are unboxed; drag row comparisons unbox against int values. No wrapper identity dependency was found.

## Tried and ruled out
No unrelated UI, generics, dependency, or warning suppression changes. Focused tests passed 32/32; clean verify passed 112/112 with no skips on macOS. Three new ChecklistTableModel tests cover initial states, both selection setters, and reordering in both directions.

## Watch out
Jackson fields() and Swing viewToModel() warnings remain separate priorities in gh_actions.md. Agent report is local-only at agent_actions/02-wrapper-constructors.md; do not commit owner-local planning files or unrelated existing work.
