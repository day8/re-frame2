# re-frame-pair2 — Design

The design rationale and locked decisions for the `re-frame-pair2` skill. A future agent could re-author this skill from this folder alone.

## 1. Goal

Help an AI **pair-program with a live, running re-frame2 application**. The app is running in a browser tab behind `shadow-cljs watch`. The AI's job is to help the developer understand, debug, and modify the app **by operating on the live runtime** — inspecting any frame's `app-db`, dispatching events, hot-swapping handlers, walking the trace stream and the per-frame epoch ring — not just by reading source files.

Crucially, the skill consumes **re-frame2's own Tool-Pair contract** (per `spec/Tool-Pair.md` and `spec/009-Instrumentation.md`). There is **no re-frame-10x dependency** — time-travel, trace streams, and epoch records are first-class re-frame2 surfaces. The skill is one of the principal downstream consumers of those surfaces.

## 2. Pillars (locked)

1. **Correctness — structured ops over `repl/eval`.** Every operation is a named structured call that returns edn (`{:ok? true ...}` / `{:ok? false :reason ...}`). The skill teaches the AI to compose forms and read structured results; the raw `repl/eval` escape hatch exists for probes that don't fit the catalogue.
2. **Idiomaticness — speak re-frame2's vocabulary.** Dispatch, reg-event-fx, reg-sub, reg-machine, frame, epoch, sub-cache. The AI never invents alternate vocabulary for the same concept.
3. **Context economy — router skill + on-demand references.** SKILL.md (~130 lines) is the router and the connect-first rules; six references carry per-task depth, loaded at most two at a time.
4. **Read before you write.** The AI grounds a hypothesis in live data (an epoch, a snapshot, a render entry) **before** proposing a change. Speculation without evidence is the single largest anti-pattern; the skill calls it out repeatedly.

## 3. Locked decisions

These are not up for re-litigation. A future authoring pass MUST preserve them unless explicitly unlocked by Mike.

### L1 — Three coupled primitives, no more

Agency runs through three primitives, all in re-frame2's Tool-Pair contract:

1. **The REPL** — a shadow-cljs nREPL session connected to the browser runtime.
2. **The trace stream** — `(rf/register-trace-cb id cb)` for live events; `(rf/trace-buffer opts)` for the retain-N ring.
3. **The epoch history** — `(rf/epoch-history frame-id)`, `(rf/register-epoch-cb id cb)`, and `(rf/restore-epoch ...)`.

Every op the skill teaches eventually becomes a ClojureScript form evaluated through the REPL, usually against a helper in the `re-frame-pair2.runtime` namespace the skill injects on connect.

### L2 — No re-frame-10x dependency

Time-travel, trace-stream consumption, and epoch records ride on `re-frame2`'s native Tool-Pair surfaces. The skill never proposes fixes that route through `re-frame-10x`. This is L1 of the sibling `re-frame-pair-improver2` skill too — consistent across the pair family.

### L3 — Two transports, MCP preferred

- **MCP transport** — `mcp__re-frame-pair2__*` tools. Single persistent nREPL connection per session. Preferred.
- **Bash-shim transport** — `scripts/discover-app.sh` and friends. Deprecated; kept for back-compat sessions where the MCP server isn't installed.

The MCP-vs-shim mapping lives in `references/mcp-transport.md`. The frontmatter `allowed-tools` block lists both surfaces so the skill works either way.

### L4 — Two modes of changing the app

- **REPL changes** are ephemeral; survive hot-reloads of unaffected namespaces but lost on full page reload. Use for probes / experiments / throwaway fixes.
- **Source edits** are permanent; after any source edit, the AI **must** call `hot-reload/wait` before dispatching or tracing. Otherwise it interacts with pre-reload code and reports misleading results.

This dichotomy is a cardinal rule in SKILL.md. The strict source-edit protocol lives in `references/hot-reload-protocol.md`.

### L5 — Connect first, every session

Before any op, `discover-app` runs. This locates the nREPL port, connects, verifies `interop/debug-enabled?` is true, injects the `re-frame-pair2.runtime` namespace, and checks the session sentinel. Failures return structured edn (`{:ok? false :reason ...}`); the skill reports the failure verbatim, doesn't guess at workarounds. `references/errors.md` carries the failure-mode catalogue.

### L6 — Multi-frame model, operating-frame selection

re-frame2 apps may run multiple named frames (Spec 002). The session caches an operating frame; mutating ops refuse with `:ambiguous-frame` if more than one frame is registered and none selected. Read ops proceed against `:rf/default` after warning. This matches Spec 002 §Frame-presets / lifecycle convention.

### L7 — One trace listener and one epoch listener per skill

The skill registers exactly one trace listener under `:re-frame-pair2` and one epoch listener under `:re-frame-pair2-epoch`. Multi-tool coexistence is the expected default — other listeners (e.g. user-installed ones, 10x in legacy sessions) don't interfere because per Spec 009 §Listener ordering, ordering is not contract.

### L8 — Use the assembled epoch stream by default; reach for the raw trace stream when the projection drops detail

`:sub-runs`, `:renders`, `:effects` are the structured projections — the routing surface. `:trace-events` is the escape hatch when the projection is incomplete (e.g. successful-fx attribution, where the `:effects` projection records only outcomes and the raw stream carries the full picture).

### L9 — No bead-ids in user-facing skill content

`SKILL.md` + `references/` + `scripts/` carry no `rf2-XXXX` references. The `spec/` folder may; user-facing content does not.

### L10 — Findings stay local

Per Mike's standing memory rule "Findings is local-only" — never commit `ai/` or `findings/`. This skill's commits contain only `skills/re-frame-pair2/**`.

### L11 — Resolve UI references to source first

When the user mentions a button / view / panel / "the thing I clicked", the AI runs `dom/source-at` **before** speculating about behaviour. Reporting `re-com/button at app/cart/view.cljs:84` grounds the conversation; *"probably the Save button somewhere in the profile view"* doesn't. This is a Style-Guidance rule in SKILL.md.

### L12 — Surface restore limits

Before any time-travel experiment, the AI walks the cascade's effects and tells the user which effects already fired and cannot be reversed. `restore-epoch` is first-class but it rewinds `app-db`, not external side effects (HTTP requests already dispatched, navigation that already happened, etc.).

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
- **Improving the pair tool itself** — `skills/re-frame-pair-improver2/`.
- **Apps not using re-frame2's Tool-Pair contract** (e.g. v1 apps, custom adapters that don't install trace-cb / epoch-cb hooks) — out of scope; the skill returns `:missing :re-frame2-tool-pair` on connect.

## 5. File structure (locked)

```
skills/re-frame-pair2/
├── SKILL.md                            (router; ~130 lines)
├── README.md                           (human-facing intro)
├── LICENSE                             (MIT)
├── RELEASING.md                        (npm + plugin release notes)
├── STATUS.md                           (development status)
├── package.json                        (npm metadata)
├── .claude-plugin/plugin.json          (Claude Code plugin metadata)
├── references/
│   ├── ops.md                          (op catalogue — read/write/trace/DOM/watch/hot-reload/time-travel)
│   ├── recipes.md                      (named procedures — "explain this dispatch", post-mortem, etc.)
│   ├── errors.md                       (structured error → English + recovery)
│   ├── hot-reload-protocol.md          (source-edit → wait-for-reload → re-trace protocol)
│   ├── migration-from-v1.md            (re-frame-pair v1 → v2 surface map)
│   └── mcp-transport.md                (MCP install + bash-shim → MCP tool name map)
├── scripts/                            (bash shims — deprecated, kept for back-compat)
├── tests/                              (skill smoke tests)
├── docs/                               (developer docs for the skill maintainer)
└── spec/
    ├── design.md                       (this file)
    ├── inputs.md                       (canonical inputs)
    └── authoring-prompt.md             (one-shot reauthor prompt)
```

SKILL.md (~130) + 6 references (~1,000) + spec (~300) ≈ ~1,430 LoC. Typical session reads SKILL.md (~130) + one or two references (~150-300 each) = ~430-720 LoC.

## 6. Discovery surface (frontmatter `description`)

The `description` is "pushy" and lists every surface the live-app workflow exposes: `re-frame2`, `app-db`, `dispatch`, `subscribe`, `reg-event`, `reg-sub`, `reg-fx`, `reg-machine`, `frame`, `epoch`, `interceptor`, `sub-cache`, `trace-buffer`, `register-trace-cb`, `register-epoch-cb`, `restore-epoch`, plus the toolchain (`re-com`, `shadow-cljs`). The framing is *"pair-program with a live re-frame2 application"* — discriminates against the authoring-only `re-frame2` skill (which triggers on the same surfaces but in code-writing prose).

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
- **First-class `allowed-tools` frontmatter.** The MCP transport + bash-shim coexistence requires explicit tool listing.
- **`scripts/` directory.** The bash-shim transport is a first-class fallback, even though deprecated.
- **`STATUS.md` + `RELEASING.md`** — the skill ships as both a Claude plugin (`.claude-plugin/plugin.json`) and an npm package (`package.json`), so per-release metadata is load-bearing.

## 9. Open questions (deferred to Mike)

### OQ1 — When to retire the bash-shim transport entirely?

L3 keeps both; the MCP transport is preferred. Once the MCP server is installed in ≥95% of sessions, the bash shims can move to a `legacy/` folder or be removed. Status: monitored; no removal target.

### OQ2 — Should recipes carry severity / leverage tagging?

Currently recipes are listed; an "if you only learn three procedures, learn these three" tier would help new users. Status: deferred — adding tiers risks ranking-by-aesthetics rather than ranking-by-evidence. A future audit against session logs could surface the actual top-N.

### OQ3 — Should the skill ship eval cases (smoke tests for the AI's responses)?

`tests/` exists but only carries connection smoke tests. AI-response evals (e.g. "given this trace, the AI should report X") would tighten the regression-test surface. Status: deferred.
