# re-frame-pair2 — Inputs

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — re-frame2's Tool-Pair contract

Path: `spec/Tool-Pair.md` (the contract specification) + `spec/009-Instrumentation.md` (the trace-stream / epoch-record surfaces) + `spec/002-Frames.md` (the multi-frame model the skill operates against).

**This is the source of truth.** Every op the skill teaches is a structured call against one of the Tool-Pair surfaces:

- `(rf/register-trace-cb id cb)` / `(rf/trace-buffer opts)` — the trace stream.
- `(rf/register-epoch-cb! id cb)` / `(rf/epoch-history frame-id)` — the assembled epoch stream and per-frame ring.
- `(rf/restore-epoch ...)` — first-class time-travel.
- `(rf/frame-ids)` / `(rf/frame-meta id)` — multi-frame inspection.
- `(rf/app-schemas)` / `(rf/handler-meta kind id)` — registrar reflection (source-coords).
- `(rf/configure :epoch-history {:depth N})` — ring retention.

The skill is one of the principal downstream consumers of these surfaces.

## 2. Secondary input — `implementation/core/src/re_frame/**`

For verifying that the public surface in `spec/Tool-Pair.md` is wired up in the reference impl:

- `implementation/core/src/re_frame/core.cljc` — the public single-import API; `register-trace-cb`, `trace-buffer`, `register-epoch-cb!`, `epoch-history`, `restore-epoch`, `frame-ids`, `frame-meta`, `app-schemas`, `handler-meta`, `configure`.
- `implementation/core/src/re_frame/trace.cljc` — the trace stream's internals; what op-types are emitted, how `:op-type :error` filtering works.
- `implementation/core/src/re_frame/epoch.cljc` — the per-frame epoch ring; what fields a `:rf/epoch-record` carries; the structured `:sub-runs` / `:renders` / `:effects` projections.
- `implementation/core/src/re_frame/frame.cljc` — frame lifecycle; `:rf/default` registration; per-frame router queues.

When `spec/Tool-Pair.md` and `implementation/**` disagree, the implementation wins and a `bd` bead gets filed against the spec.

## 3. Tertiary input — `re-frame-pair2.runtime` namespace

Path: `skills/re-frame-pair2/preload/re_frame_pair2/runtime.cljs` (the namespace `re-frame-pair2.runtime`, ships into the consumer app via shadow-cljs `:devtools :preloads` — see `SKILL.md` §Setup).

The namespace carries helper functions the structured ops compose against (`epoch-diff`, `find-where`, `find-all-where`, etc.). The skill's `references/ops.md` and `references/recipes.md` cite these helpers by name.

## 4. Transport inputs

- **MCP server** — the `mcp__re-frame-pair2__*` tool surface. Lives in `tools/pair2-mcp/` in the re-frame2 repo. The skill's frontmatter `allowed-tools` block lists every MCP tool; `references/mcp-transport.md` explains installation.
- **Bash shims** — `skills/re-frame-pair2/scripts/*.sh`. Deprecated but kept for back-compat. Each shim has a 1:1 MCP-tool counterpart documented in `references/mcp-transport.md`.

## 5. Authoring-discipline inputs

These shape the skill's voice and structure but aren't quoted directly.

- **`skills/re-frame2/spec/design.md`** — the parent skill's locked design. This skill inherits the four pillars (recipe-shape, idiomaticness, context economy, training-knowledge assumption), the cardinal-rules format, the routing-table convention.
- **`skills/re-frame-migration/spec/`** + **`skills/re-frame2-implementor/spec/`** — the existing `spec/` triad pattern. Voice / shape mirror these.
- **`skills/re-frame-pair-improver2/SKILL.md`** — the sibling improver skill that consumes this skill's output. The two are coupled: the improver routes friction back into beads against the pair tool.
- Anthropic skills guidance — `name` ≤ 64 chars, lowercase + hyphens; `description` "pushy"; SKILL.md under 500 lines; reference files one level deep; `allowed-tools` listing required when the skill uses MCP / Bash tools beyond the defaults.

## 6. What the skill does NOT consume

- **`docs/guide/**`** — the narrative human guide. The skill is for AI-augmented developer sessions; the guide is for learners.
- **`spec/Pattern-*.md`** — application-authoring patterns. The pair tool operates on running apps; pattern selection is the `re-frame2` skill's concern.
- **`re-frame-10x`** — explicitly excluded per L2. The pair tool consumes re-frame2's native Tool-Pair surfaces, not 10x's projection.
- **`implementation/<feature>/**` per-feature artefacts** — except where they install Tool-Pair hooks (most don't; the Tool-Pair surface lives in `implementation/core/`).
- **`tests/**`** in the re-frame2 repo — the skill teaches operation against running apps, not how to test the framework itself.

## 7. Update procedure

When the Tool-Pair contract changes:

1. **A new trace event op-type ships** → update `references/ops.md`'s op catalogue (if the AI should explicitly query for it); update `references/recipes.md` if a new recipe exposes it.
2. **`:rf/epoch-record`'s projection set changes** (`:sub-runs` / `:renders` / `:effects` field additions) → update the recipes that walk those projections; L8 may need re-statement.
3. **`restore-epoch`'s failure modes expand** → update `references/errors.md` and the time-travel recipe in `references/recipes.md`.
4. **A new structured op ships in the MCP server** → add to `allowed-tools` in SKILL.md frontmatter; add a row to `references/ops.md`; add the 1:1 bash-shim mapping to `references/mcp-transport.md`.
5. **A bash shim is removed** → update `references/mcp-transport.md`'s mapping table. (rf2-7dvg removed `inject-runtime.sh` along with the MCP `inject-runtime` tool — the runtime ships via shadow-cljs `:preloads` now.)
6. **A new failure mode appears in `discover-app`** → add to `references/errors.md`.
7. **`re-frame2` adds a new `reg-*` kind** (e.g. a future `reg-X`) → check whether the new kind needs a structured op (probably yes if it's user-facing); update `references/ops.md`.
