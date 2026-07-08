# re-frame2-pair — Design

> **Skill-internal meta-doc.** Design rationale + author notes for the `re-frame2-pair` skill itself — not part of the user-facing or AI-facing skill contract. Not loaded during normal skill operation; exists to re-author the skill from inputs. For the skill contract, see [`SKILL.md`](../SKILL.md).

The design rationale and locked decisions for the `re-frame2-pair` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help an AI **pair-program with a live, running re-frame2 application**. The app is running in a browser tab behind `shadow-cljs watch`. The AI's job is to help the developer understand, debug, and modify the app **by operating on the live runtime** — inspecting any frame's `app-db`, dispatching events, hot-swapping handlers, walking the trace stream and the per-frame epoch ring — not just by reading source files.

Crucially, the skill consumes **re-frame2's own Tool-Pair contract** (per `spec/Tool-Pair.md` and `spec/009-Instrumentation.md`). There is **no re-frame-10x dependency** — time-travel, trace streams, and epoch records are first-class re-frame2 surfaces. The skill is one of the principal downstream consumers of those surfaces.

**Verification posture — agent-executes-against-live-runtime (by design).** Among the skill family, `re-frame2-pair` is the one skill whose posture is *the agent itself executes operations against a live runtime* — it dispatches events, mutates `app-db`, hot-swaps handlers, and reads back the resulting epoch / trace state, all through the Tool-Pair contract. Feedback is immediate and first-class: the agent grounds every claim in a live read (Pillar 4). This is the deliberate counterpart to the per-skill posture spread across the family — `re-frame-migration` runs nothing in the author's env (trust boundary), `re-frame2` authoring emits recipes a human pastes (no runtime the agent drives), and `re-frame2-implementor` runs only a narrow per-EP slice gate against the port's own scripts. Posture follows role; `re-frame2-pair`'s role *is* driving a live runtime, so executing against it is the whole point, not an exception.

## 2. Pillars (locked)

1. **Correctness — structured ops over `repl/eval`.** Every operation is a named structured call that returns edn (`{:ok? true ...}` / `{:ok? false :reason ...}`). The skill teaches the AI to compose forms and read structured results; the raw `repl/eval` escape hatch exists for probes that don't fit the catalogue.
2. **Idiomaticness — speak re-frame2's vocabulary.** Dispatch, reg-event, reg-sub, reg-machine, frame, epoch, sub-cache. The AI never invents alternate vocabulary for the same concept.
3. **Context economy — router skill + on-demand references.** SKILL.md is the always-loaded router and connect-first rules (kept well under Anthropic's 500-line ceiling); nine references carry per-task depth, loaded at most two at a time.
4. **Read before you write.** The AI grounds a hypothesis in live data (an epoch, a snapshot, a render entry) **before** proposing a change. Speculation without evidence is the single largest anti-pattern; the skill calls it out repeatedly.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve them unless explicitly unlocked by Mike.

### L1 — Three coupled primitives, no more

Agency runs through three primitives, all in re-frame2's Tool-Pair contract:

1. **The REPL** — a shadow-cljs nREPL session connected to the browser runtime.
2. **The trace stream** — `(re-frame.trace.tooling/register-listener! id cb)` for live events; `(re-frame.trace.tooling/trace-buffer frame-id)` for the retain-N ring. (The facade form is the stream-parameterized `(rf/register-listener! :trace id cb)` — a stream-dispatching wrapper, not a same-signature re-export of the 2-arg tooling fn; `trace-buffer` is a JVM-only `rf/` alias, so CLJS callers use the `re-frame.trace.tooling` form — frame-id first, `(trace-buffer frame-id opts)` for filters.)
3. **The epoch history** — `(rf/epoch-history frame-id)`, `(rf/register-listener! :epoch id cb)`, and `(rf/restore-epoch! ...)`.

Every op the skill teaches eventually becomes a ClojureScript form evaluated through the REPL, usually against a helper in the `re-frame2-pair.runtime` namespace the consumer app preloads via shadow-cljs `:devtools :preloads` (see `SKILL.md` §Setup).

### L2 — No re-frame-10x dependency

Time-travel, trace-stream consumption, and epoch records ride on `re-frame2`'s native Tool-Pair surfaces. The skill never proposes fixes that route through `re-frame-10x`. This is L1 of the sibling `re-frame2-pair-retro` skill too — consistent across the pair family.

### L3 — MCP is the only skill-facing transport

- **MCP transport** — `mcp__re-frame2-pair__*` tools. Single persistent nREPL connection per session. The **only** transport the skill exposes. The server ships **30** tools and **all 30 are allow-listed** (the frontmatter `allowed-tools:` carries no shell tool). The two write-authority tools (`restore-epoch`, `replace-app-db`) are the canonical named-write path; they are gated behind the server's default-OFF `--allow-writes` flag (refusing with `:rf.error/writes-disabled` against a gate-OFF server) — the server's gate, not the allow-list, is the write boundary, so allow-listing them is safe. The eval forms (`(rf/restore-epoch! …)` / `app-db-reset!`) are the backstop for a gate-OFF server (`eval-cljs` is default-ON and outside `--allow-writes`).
- **Bash shims** — `scripts/discover-app.sh` and friends predate the MCP server and are **retired from the skill's tool surface**. They remain on disk only for the project's own e2e test harness and ad-hoc shell use; no shell tool is in `allowed-tools:`, so the skill cannot reach them.

The MCP tool reference lives in `references/mcp-transport.md`.

### L4 — Two modes of changing the app

- **REPL changes** are ephemeral; survive hot-reloads of unaffected namespaces but lost on full page reload. Use for probes / experiments / throwaway fixes.
- **Source edits** are permanent; after any source edit, the AI **must** call `hot-reload/wait` before dispatching or tracing. Otherwise it interacts with pre-reload code and reports misleading results.

This dichotomy is a cardinal rule in SKILL.md. The strict source-edit protocol lives in `references/ops.md` §Hot-reload coordination.

### L5 — Connect first, every session

Before any op, `discover-app` runs. This locates the nREPL port, connects, verifies `interop/debug-enabled?` is true, and probes the load-time marker installed by the preloaded `re-frame2-pair.runtime` namespace. Failures return structured edn (`{:ok? false :reason ...}`); the most common precondition failure is `:reason :runtime-loaded-but-preload-missing` (the normal missing-preload verdict — `:runtime-not-preloaded` is the degradation fallback the ladder returns only if it errors mid-diagnosis), fixed by adding the two-line preload entry in `SKILL.md` §Setup. The skill reports failures verbatim, doesn't guess at workarounds. `references/errors.md` carries the full failure-mode catalogue.

### L6 — Multi-frame model, operating-frame selection

re-frame2 apps may run multiple named frames (Spec 002). The session caches an operating frame; ops refuse with `:ambiguous-frame` if more than one app frame is registered and none selected. Read ops refuse too — the read helpers return `:reason :ambiguous-frame` rather than silently reading `:rf/default`. This matches Spec 002 §Frame-presets / lifecycle convention.

### L7 — One trace listener and one epoch listener per skill

The skill registers exactly one trace listener under `:re-frame2-pair` and one epoch listener under `:re-frame2-pair-epoch`. Multi-tool coexistence is the expected default — Xray, user-installed listeners, and legacy 10x sessions don't interfere because per Spec 009 §Listener ordering, ordering is not contract.

### L8 — Use the assembled epoch stream by default; reach for the raw trace stream when the projection drops detail

`:sub-runs`, `:renders`, `:effects` are the structured projections — the routing surface. `:trace-events` is the escape hatch when you need detail the projection drops (e.g. per-interceptor timing, or the raw fx `:args` the `:effects` projection redacts off-box).

### L9 — No bead-ids in user-facing skill content

`SKILL.md` + `references/` + `scripts/` carry no `rf2-XXXX` references. The `spec/` folder may; user-facing content does not.

### L10 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — never commit `ai/` or `findings/`. This skill's commits contain only `skills/re-frame2-pair/**`.

### L11 — Resolve UI references to source first

When the user mentions a button / view / panel / "the thing I clicked", the AI runs `dom/source-at` **before** speculating about behaviour. Reporting `re-com/button at app/cart/view.cljs:84` grounds the conversation; *"probably the Save button somewhere in the profile view"* doesn't. This is a Style-Guidance rule in SKILL.md.

### L12 — Surface restore limits

Before any time-travel experiment, the AI walks the cascade's effects and tells the user which effects already fired and cannot be reversed. `rf/restore-epoch!` (the MCP tool exposes it as `restore-epoch`) is first-class but it rewinds the frame's WHOLE frame-state — both app-db AND runtime-db, from `:frame-state-after` via `replace-frame-state!` — not external side effects (HTTP requests already dispatched, navigation that already happened, etc.).

## 4. Audience and scope

### In scope

- Developers running a re-frame2 app under `shadow-cljs watch` who want pair-programming agency.
- Inspecting any frame's `app-db`, walking the trace stream, walking the epoch history.
- Dispatching events from the REPL; hot-swapping handlers; experimenting with reg-* replacements.
- DOM bridge ops (`dom/source-at`, `dom/fire-click-at-src`).
- Time-travel (`epoch/restore`).
- Watch / stream / narrate-live workflows.

### Out of scope

- **Greenfield setup** — `skills/re-frame2-setup/`.
- **Authoring re-frame2 code from scratch** (vs. modifying live) — `skills/re-frame2/`.
- **v1→v2 migration** — `skills/re-frame-migration/`.
- **Porting re-frame2 to a different host** — `skills/re-frame2-implementor/`.
- **Retrospecting on the pair tool itself** — `skills/re-frame2-pair-retro/`.
- **Apps not using re-frame2's Tool-Pair contract** (e.g. v1 apps, custom adapters that don't install trace-cb / epoch-cb hooks) — out of scope; the skill returns `:missing :re-frame2-tool-pair` on connect.

## 5. File structure (locked)

```
skills/re-frame2-pair/
├── SKILL.md                            (router; ~130 lines)
├── README.md                           (human-facing intro)
├── LICENSE                             (MIT)
├── RELEASING.md                        (npm + plugin release notes)
├── STATUS.md                           (development status)
├── package.json                        (npm metadata)
├── .claude-plugin/plugin.json          (Claude Code plugin metadata)
├── references/
│   ├── ops.md                          (op catalogue — read/write/trace/DOM/watch/hot-reload/time-travel + v1 surface-map appendix)
│   ├── recipes.md                      (named procedures — "explain this dispatch", post-mortem, etc.)
│   ├── errors.md                       (structured error → English + recovery)
│   ├── mcp-transport.md                (MCP install + transport reference — the only transport)
│   ├── vocabulary.md                   (flat glossary + privacy posture)
│   ├── streaming-subscriptions.md      (push-mode subscribe/unsubscribe)
│   ├── wire-size-budget.md             (de-dupe decoding + size-conscious args)
│   ├── stories.md                      (live-session story-mcp tools)
│   └── variant-as-frame.md             (driving Story variants from a pair session)
├── scripts/                            (bash shims — retired from the skill surface; on disk for the e2e harness only)
├── tests/                              (skill smoke tests)
├── docs/                               (developer docs for the skill maintainer)
└── spec/
    ├── design.md                       (this file)
    ├── inputs.md                       (canonical inputs)
    └── authoring-prompt.md             (one-shot reauthor prompt)
```

Typical session reads SKILL.md (the always-loaded router) + one or two references. Leaves are single-concept and kept ideally ≤250 lines / ≤16 KB; the two catalogue leaves (`ops.md`, `recipes.md`) run longer where a split would not reduce tokens-per-session. The hot-reload protocol and v1 → v2 surface-map both live in `ops.md` as sections rather than separate leaves.

## 6. Discovery surface (frontmatter `description`)

The `description` is "pushy" and lists every surface the live-app workflow exposes: `re-frame2`, `app-db`, `dispatch`, `subscribe`, `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, `frame`, `epoch`, `interceptor`, `sub-cache`, `trace-buffer`, `register-listener!`, `register-epoch-listener!`, `restore-epoch`, plus the toolchain (`re-com`, `shadow-cljs`). The framing is *"pair-program with a live re-frame2 application"* — discriminates against the authoring-only `re-frame2` skill (which triggers on the same surfaces but in code-writing prose).

## 7. Anti-patterns the skill explicitly resists

- **Speculating without evidence.** Style-guidance rule + L8 + L11.
- **Using `reset!` of a frame's app-db when not surgically needed.** Mentioned explicitly in SKILL.md style guidance.
- **Routing fixes through re-frame-10x.** L2.
- **Skipping `discover-app`.** L5; every op starts by checking the session sentinel.
- **Skipping `hot-reload/wait` after a source edit.** L4; the protocol leaf is the canonical reference.
- **Inventing alternate vocabulary** for re-frame2 concepts (e.g. "state graph" for "frame", "transition log" for "epoch ring"). Pillar 2.
- **Asserting completion without grounding in a read.** SKILL.md style guidance — "Validate before proposing".

## 8. Why this design diverges from `re-frame2`

- **No patterns/ directory.** The skill is an op catalogue and a recipe library, not a pattern catalogue.
- **No decision-trees/ directory.** The decisions are operational ("which op for which task?") and live in the `references/ops.md` and `references/recipes.md` tables.
- **First-class `allowed-tools` frontmatter.** The MCP transport requires explicit tool listing — **all 30** server tools are allow-listed, including the two `--allow-writes`-gated write tools (`restore-epoch` / `replace-app-db`), which the server's gate fences at runtime rather than the allow-list excluding.
- **`scripts/` directory.** Holds the retired bash shims — kept on disk for the project's e2e harness, not a skill-facing transport.
- **`STATUS.md` + `RELEASING.md`** — the skill ships as both a Claude plugin (`.claude-plugin/plugin.json`) and an npm package (`package.json`), so per-release metadata is load-bearing.

## 9. Open questions (deferred to Mike)

### OQ1 — Should the retired bash shims leave the tree entirely?

**Resolved for the skill surface:** L3 is now MCP-only — the bash shims are already retired from the skill's tool surface (no shell tool in `allowed-tools:`). What remains open is whether the `scripts/*.sh` files should also leave the repo or stay on disk for the project's e2e harness + ad-hoc shell use. Status: kept on disk for the harness; no removal target. If they are deleted, strip `scripts/` from the file-structure blocks above and from `references/mcp-transport.md` / `STATUS.md`.

### OQ2 — Should recipes carry severity / leverage tagging?

Currently recipes are listed; an "if you only learn three procedures, learn these three" tier would help new users. Status: deferred — adding tiers risks ranking-by-aesthetics rather than ranking-by-evidence. A future audit against session logs could surface the actual top-N.

### OQ3 — Should the skill ship eval cases (smoke tests for the AI's responses)?

`tests/` exists but only carries connection smoke tests. AI-response evals (e.g. "given this trace, the AI should report X") would tighten the regression-test surface. Status: deferred.
