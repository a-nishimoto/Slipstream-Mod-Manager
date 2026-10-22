<!-- trail:start -->
## Trail — repo context graph

This repo is indexed in `.trail/cache/`: small linked markdown nodes that explain each
system and carry exact file:line spans, kept in sync with the code through git.

For ANY task here — understanding how something works, finding where code lives,
or scoping a change — get context from the graph before grepping or opening
source files. Re-ask freely (it's cheap) and reuse literal identifiers you
already have (symbol, error string, file name) as the query. New to this repo?
Run `trail map` first — a token-budgeted orientation (dir clusters, hubs,
hotspots), no LLM, no key.

- Run `trail ask "<your question>" --source` → ranked nodes with the relevant
  code spans inlined (each hit's ≤8-line crux by default; `--full` for whole
  definitions when the crux isn't enough). Match the tool to the task shape:
  for understanding or editing, the top node IS the answer — cite its
  `covers:` file:line spans and edit straight from `--source`. For
  exhaustive tasks ("every occurrence / every caller of this pattern"), ranked
  results are top-N, not complete — run `trail grep "<literal>"` instead
  (exhaustive over indexed files, grouped by enclosing symbol), falling back
  to raw `grep -rn` only for unindexed files.
- `trail skeleton <file>` → every definition's signature + span, ~10× cheaper
  than reading the file; use it to skim an API surface.
- `trail callers <symbol>` gives precomputed, exact edges — who calls this.
  Add `--direction out` for what it calls, or `--depth N` to walk
  transitively for the full blast radius. For structural questions, skip
  ranking and use this directly.
- Or browse: `.trail/cache/INDEX.md` lists every node; follow the links.
- Monorepos and folders of multiple repos rank fairly across sub-projects —
  hits carry `[scope/]` labels naming which one they're from. Narrow with
  `trail ask "<task>" --in <scope>/` once you know where you're working.

If a returned span is truncated ("+N more lines"), open the file at that exact
range before finalizing. Only open source files when a node genuinely lacks a
needed detail, and then at the exact file:line the node points to — never
re-read whole files.

After big code changes, refresh the graph with `trail build` (deterministic,
no API key, $0).
<!-- trail:end -->

## Trail — MCP tools & session memory

`opencode.json` wires `trail mcp`, so the same graph is served as tools. Prefer a
tool over shelling out when one is available; other hosts namespace them
(`mcp__trail__trail_find_code`).

| MCP tool | CLI equivalent | Use for |
| --- | --- | --- |
| `trail_find_code` | `trail ask` | ranked hits with the code inlined — the default for any task |
| `trail_find_all` | `trail grep` | every occurrence, grouped by enclosing symbol |
| `trail_trace_calls` | `trail callers` | callers and callees; blast radius before a rename |
| `trail_file_api` | `trail skeleton` | one file's API — signatures and spans |
| `trail_repo_map` | `trail map` | orienting in an unfamiliar repo |
| `trail_check_freshness` | `trail check` | whether the graph still matches the code |

Start a task with `trail_find_code` / `trail ask`; reach for `trail map` only to
orient in a layout you don't already know.

### Session memory

- Read `.trail/learnings/` before exploring a subsystem — past sessions record
  what was tried, decided and ruled out.
- When a task took real digging, or someone corrects how something is done
  here, save it with `trail note` and commit it with the change it came from.
  Short summaries, never transcripts.
- Run `trail check` before committing, pushing or opening a PR.
