# re-frame2-pair — Inputs

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-pair` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The canonical inputs the skill leans on. A re-authoring pass needs these to reproduce the leaves.

## 1. Primary input — re-frame2's Tool-Pair contract

Path: `spec/Tool-Pair.md` (the contract specification) + `spec/009-Instrumentation.md` (the trace-stream / epoch-record surfaces) + `spec/002-Frames.md` (the multi-frame model the skill operates against).

**This is the source of truth.** Every op the skill teaches is a structured call against one of the Tool-Pair surfaces:

- `(re-frame.trace.tooling/register-listener! id cb)` / `(re-frame.trace.tooling/trace-buffer frame-id)` — the trace stream. (The facade form is the stream-parameterized `(rf/register-listener! :trace id cb)` — a stream-dispatching wrapper, not a same-signature re-export of this 2-arg tooling fn; `trace-buffer` is a JVM-only `rf/` alias, so CLJS callers use the `re-frame.trace.tooling` form — frame-id first, `(trace-buffer frame-id opts)` for filters.)
- `(rf/register-listener! :epoch id cb)` / `(rf/epoch-history frame-id)` — the assembled epoch stream and per-frame ring.
- `(rf/restore-epoch! ...)` — first-class time-travel.
- `(rf/frame-ids)` / `(rf/frame-meta id)` — multi-frame inspection.
- `(re-frame.schemas/app-schemas)` / `(rf/handler-meta kind id)` — registrar reflection (source-coords). (`app-schemas` lives on `re-frame.schemas`, not the `re-frame.core` façade; `handler-meta` stays on `rf/`.)
- `(rf/configure! {:epoch-history {:depth N}})` — ring retention.

The skill is one of the principal downstream consumers of these surfaces.

## 2. Secondary input — `implementation/core/src/re_frame/**`

For verifying that the public surface in `spec/Tool-Pair.md` is wired up in the reference impl:

- `implementation/core/src/re_frame/core.cljc` — the public single-import API; `register-listener!`, `register-epoch-listener!`, `epoch-history`, `restore-epoch!` (the core-API name carries the bang — the MCP tool that calls it is named `restore-epoch`), `frame-ids`, `frame-meta`, `handler-meta`, `configure!` (the bang). (`trace-buffer` lives in `re-frame.trace.tooling` — `core.cljc` re-exports it on `rf/` JVM-side only; `app-schemas` lives on `re-frame.schemas`, the machine query helpers on `re-frame.machines`, `sub-cache-snapshot` on `re-frame.subs.tooling` — none re-exported on the facade.)
- `implementation/core/src/re_frame/trace.cljc` — the trace stream's internals; what op-types are emitted, how `:op-type :error` filtering works.
- `implementation/core/src/re_frame/epoch.cljc` — the per-frame epoch ring; what fields a `:rf/epoch-record` carries; the structured `:sub-runs` / `:renders` / `:effects` projections.
- `implementation/core/src/re_frame/frame.cljc` — frame lifecycle; `:rf/default` registration; per-frame router queues.

When `spec/Tool-Pair.md` and `implementation/**` disagree, the implementation wins and a `bd` bead gets filed against the spec.

## 3. Tertiary input — `re-frame2-pair.runtime` namespace

Path: `skills/re-frame2-pair/preload/re_frame2_pair/runtime.cljs` (the namespace `re-frame2-pair.runtime`, ships into the consumer app via shadow-cljs `:devtools :preloads` — see `SKILL.md` §Setup).

The namespace carries helper functions the structured ops compose against (`epoch-diff`, `find-where`, `find-all-where`, etc.). The skill's `references/ops.md` and `references/recipes.md` cite these helpers by name.

## 4. Transport inputs

- **MCP server** — the `mcp__re-frame2-pair__*` tool surface, the **only** transport the skill exposes. Lives in `tools/re-frame2-pair-mcp/` in the re-frame2 repo; the generated catalogue manifest is `tools/re-frame2-pair-mcp/tool-descriptors.edn` (**33** tools). The skill's frontmatter `allowed-tools` block lists **all 33**; the two write-authority tools (`restore-epoch`, `replace-app-db`) are the canonical named-write path and are gated behind the server's default-OFF `--allow-writes` flag (refusing with `:rf.error/writes-disabled` until an operator flips it at launch) — the server's gate, not the allow-list, is the write boundary. `references/mcp-transport.md` explains installation.
- **Bash shims** — `skills/re-frame2-pair/scripts/*.sh`. **Retired from the skill's tool surface** (no shell tool in `allowed-tools:`); on disk only for the project's own e2e harness and ad-hoc shell use. They consume the same transport-agnostic `re-frame2-pair.runtime` namespace but are not a skill-facing fallback transport.

## 5. Authoring-discipline inputs

These shape the skill's voice and structure but aren't quoted directly.

- **`skills/re-frame2/spec/design.md`** — the parent skill's locked design. This skill inherits the four pillars (recipe-shape, idiomaticness, context economy, training-knowledge assumption), the cardinal-rules format, the routing-table convention.
- **`skills/re-frame-migration/spec/`** + **`skills/re-frame2-implementor/spec/`** — the existing `spec/` triad pattern. Voice / shape mirror these.
- **`skills/re-frame2-pair-retro/SKILL.md`** — the sibling retro skill that consumes this skill's output. The two are coupled: the retro skill routes friction back into beads against the pair tool.
- Anthropic skills guidance — `name` ≤ 64 chars, lowercase + hyphens; `description` "pushy"; SKILL.md under 500 lines; reference files one level deep; `allowed-tools` listing required when the skill uses MCP tools beyond the defaults (this skill lists all 33 `mcp__re-frame2-pair__*` tools plus the editor tools — no `Bash(...)` entries, since the MCP server is the only transport).

## 6. What the skill does NOT consume

- **`docs/core/**`** — the narrative human guide. The skill is for AI-augmented developer sessions; the guide is for learners.
- **`spec/Pattern-*.md`** — application-authoring patterns. The pair tool operates on running apps; pattern selection is the `re-frame2` skill's concern.
- **`re-frame-10x`** — explicitly excluded per L2. The pair tool consumes re-frame2's native Tool-Pair surfaces, not 10x's projection.
- **`implementation/<feature>/**` per-feature artefacts** — except where they install Tool-Pair hooks (most don't; the Tool-Pair surface lives in `implementation/core/`).
- **`tests/**`** in the re-frame2 repo — the skill teaches operation against running apps, not how to test the framework itself.

## 7. Update procedure

When the Tool-Pair contract changes:

1. **A new trace event op-type ships** → update `references/ops.md`'s op catalogue (if the AI should explicitly query for it); update `references/recipes.md` if a new recipe exposes it.
2. **`:rf/epoch-record`'s projection set changes** (`:sub-runs` / `:renders` / `:effects` field additions) → update the recipes that walk those projections; L8 may need re-statement.
3. **`restore-epoch`'s failure modes change** (the Tool-Pair §Time-travel table currently lists **seven**) → update `references/errors.md`, the failure-mode count in `references/ops.md` §Time-travel + `STATUS.md`, and the time-travel recipe in `references/recipes.md`.
4. **A new structured op ships in the MCP server** → add it to `allowed-tools` in SKILL.md frontmatter — **including** a write tool gated behind `--allow-writes` (every server tool is allow-listed and fenced at the server; the server's launch gate, not the allow-list, is the write-authority boundary, so a gated write tool is allow-listed too, never excluded into `intentional_server_only`); add a row to `references/ops.md`; update the **33-tool** count wherever it is stated in prose (`SKILL.md`, `references/mcp-transport.md`, `STATUS.md`, and the meta docs). The catalogue manifest `tools/re-frame2-pair-mcp/tool-descriptors.edn` `:meta :tool-count` is the source of truth: `scripts/check_skill_mcp_drift.py` enforces the allow-list name SET against it, and the skill's own `tests/prompts/prompt_regression_test.clj` (`catalogue-count-matches-live-manifest`) anchors every count-stating doc's PROSE number to that live `:tool-count` and fails on a stale count. Re-run both gates.
5. **Transport surface** → the bash/babashka transport (`scripts/ops.clj` + shell wrappers) has been **removed**; the MCP server is the one implementation of all six operations, and the live connect/dispatch/trace/hot-reload coverage lives in `tools/re-frame2-pair-mcp/test/live-e2e-fixture.cjs`. Any re-authoring pass MUST NOT reintroduce a bash-shim transport or an `inject-runtime` tool (the runtime ships via shadow-cljs `:devtools :preloads`). `scripts/check_skill_pair_authoring_drift.py` fails if a retired name reappears here.
6. **A new failure mode appears in `discover-app`** → add to `references/errors.md`.
7. **`re-frame2` adds a new `reg-*` kind** (e.g. a future `reg-X`) → check whether the new kind needs a structured op (probably yes if it's user-facing); update `references/ops.md`.
