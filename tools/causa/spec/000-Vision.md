# 000-Vision: Causa — the re-frame2 devtools panel

## Why it exists

When a re-frame app misbehaves, the programmer should be able to ask
five canonical questions and answer them in seconds, with click-to-source
on every artefact:

1. **Why did this event fire?** (causal-ancestor walk)
2. **What did that event change?** (`app-db` diff + flow recomputes + machine transitions)
3. **Why is this subscription returning the wrong value?** (input-chain trace + cache state)
4. **Why is this view re-rendering?** (subscription invalidation chain + render-key attribution)
5. **What's currently broken?** (errors, warnings, schema violations, hydration mismatches — surfaced as one feed)

v1 of re-frame-10x answered #1–#4 partially and #5 not really. Causa
answers all five, in seconds, with click-to-source for every artefact.

re-frame2's instrumentation surface (Spec 009 trace bus, Tool-Pair
epoch history, source-coord stamping, machine and flow registries)
emits structured data for every interesting moment. Causa's job is
*presentation*: render the runtime to the human in the runtime's own
terms.

## What it is

A re-frame2-native devtools panel that runs **in-app**, preloaded into
dev builds. The marquee surface is the **event-detail panel** — every
open lands here. The panel shows the current epoch's event vector, an
inline mini causality graph, the slices it touched, fx fired, subs
recomputed, renders, duration. The five canonical questions answer
themselves on first paint; deeper investigation is one click away.

The 16 panels (enumerated below) are all views over the same trace bus.
One substrate, many views.

## What it isn't

- **Not** a port of re-frame-10x v1. The shape is new. The lessons are
  carried forward; the data shape and the substrate are re-frame2's.
- **Not** a registrar, dispatch, effect, or component layer. Causa is a
  downstream consumer of re-frame2's existing surfaces — per the
  feedback that downstream EPs must not add new registries (this is
  not even an EP; it is a tool).
- **Not** writeable into `app-db`. Read-only forever. The runtime is
  the source of truth; pokes from the debugger are out of scope (lock
  #3 in `DESIGN-RATIONALE.md`).
- **Not** a session recorder. No export, no import. Sessions are
  ephemeral (lock #4).
- **Not** a mobile surface. Desktop only; viewports below 600px refuse
  to mount (lock #5).
- **Not** a Chrome extension. In-app DOM injection plus a same-browser
  pop-out via `window.opener`. Remote-attach lives over MCP, not over
  a custom WebSocket protocol (lock #9).
- **Not** part of any production bundle. Per the bundle-isolation
  contract in `tools/README.md`, dependency arrows flow tool →
  implementation; Causa is invisible to consumer apps after elision.

## What re-frame2 unlocks

Each row is "new in re-frame2 → new tooling story Causa must tell."

| re-frame2 capability | Causa surface |
|---|---|
| **Multi-frame** (Spec 002) | Per-frame panels; frame picker; cross-frame causality. |
| **Machines** (Spec 005) | Stately-quality state-chart per machine; transition log; `:after`-timer countdown; `:invoke-all` parallel-child viz. |
| **Flows** (Spec 013) | Flow dependency graph; per-flow recompute heatmap; `:rf.flow/skip` badge. |
| **Source-coord stamping** (Spec 001 + 006) | Click-to-source on every node, view, machine guard, transition. |
| **Trace bus** (Spec 009) | The substrate of everything. Causa does not invent its own trace shape. |
| **Epoch history + `:rf/epoch-record` projections** (Tool-Pair) | First-class time-travel; pre-folded `:sub-runs` / `:renders` / `:effects` mean Causa renders, doesn't refold. |
| **Six named restore failures** | Structured "this rewind won't work because X" rather than a silent no-op. |
| **`register-epoch-cb!`** | The per-cascade listener routes the causality graph and the event-detail panel. |
| **Schemas (Malli)** (Spec 010) | Real-time schema-violation feed with the five named recovery modes. |
| **SSR + hydration** (Spec 011) | Hydration-mismatch debugger; server-vs-client render-tree diff. |
| **Routing** (Spec 012) | URL ↔ frame state visualisation; nav-token timeline with stale-result suppression in flight. |
| **`:origin` opt on dispatch** | Filter by actor: "show me only the dispatches Claude issued." |
| **Production-elision verifier** | Causa runs `npm run test:elision` and warns before deploy if a dev sentinel leaked. |

## The 16-panel inventory

| Panel | What it does | Data source |
|---|---|---|
| **Event detail** *(hero)* | The canonical-question-answering panel. Event vector, source, `caused-by` link, inline mini-graph, db-diff, fx fired, subs recomputed, renders, duration. | `register-epoch-cb!` + `:rf/epoch-record` projections. |
| **Causality graph** *(peer)* | Vertical directed graph keyed by `:dispatch-id` / `:parent-dispatch-id`. Click node → detail. Drag time-range → filter. Find-root-cause button. | `(rf/trace-buffer {:op-type :event})` + `register-epoch-cb!`. |
| **Causality strip** | The flat horizontal version of the graph, pinned at the top of every panel. Last 20 dispatches as pills. Always visible. | Same as causality graph. |
| **Event log** | Per-frame list of dispatched events; group-by-cascade; right-click → "re-dispatch" (with `:origin :causa`). | `(rf/trace-buffer {:op-type :event :frame current-frame})`. |
| **App-db inspector** | Slice-centric; touched slices first, pinned slices second, full-tree as escape hatch. Read-only. | `(rf/get-frame-db frame-id)` + epoch's changed-paths set. |
| **Subscription graph** | Force-directed graph of sub dependencies; layer-1/2/3 grouping; click a sub → inputs / outputs / cached value. | `(rf/sub-cache frame-id)` (CLJS-only). |
| **Effect log** | Every fx invocation; filter by fx-id; outcome (`:ok` / `:error` / `:skipped-on-platform`). | `(rf/trace-buffer {:op-type :fx})` + `:effects` projection. |
| **Trace timeline** | Raw trace events with timing. Structured filter on `:op-type` / `:operation` / `:frame` / `:dispatch-id` / `:origin`. | `(rf/trace-buffer)` + `PerformanceObserver` on `rf:*` measures. |
| **Machine inspector** | State-chart per machine; live highlight; transition history scrub; source-coord jump. Embeds `tools/machines-viz/`. | `(rf/machines)` + `[:rf/machines <id>]` + `:rf.machine/*` traces. |
| **Flow graph** | DAG of registered flows; per-flow recompute heatmap; "marked dormant" indicator. | `(rf/registrations :flow)` + `:rf.flow/*` traces. |
| **Performance ribbon** | INP, long tasks, layout shifts, re-render counts per epoch. | `PerformanceObserver` watching `rf:*` + browser entries. |
| **Schema violation timeline** | Per-schema row; coloured dot per failure with recovery mode (skip / rollback / replaced-with-default / re-raised). | `(rf/trace-buffer {:operation :rf.error/schema-validation-failure})`. |
| **Issues ribbon** | Unified feed: errors, warnings, schema violations, hydration mismatches. Permanent ribbon, not a console line. | All `:op-type :error` / `:warning` traces (including `:operation :rf.ssr/hydration-mismatch`, which carries `:op-type :error`). |
| **Hydration debugger** | Server vs client render-tree side-by-side; divergent-node pulse; render-tree hash bisector. Only visible when SSR hydration ran. | `:rf.ssr/hydration-mismatch` + payload + first client render-tree hash. |
| **AI co-pilot** | Pull-only Q&A and slash commands; collapsed by default; ephemeral conversation. | Registrar + epoch history + trace buffer + user's LLM. |
| **Routing inspector** | `:rf/route` as breadcrumb; nav-token timeline with stale-result suppression. | `(rf/sub :rf/route)` + `:rf.route.nav-token/*` traces. |
| **Settings / filters** | Buffer depths, default frame, theme, AI provider key. Schema'd localStorage; corruption triggers clean reset. | localStorage with `:rf/version` stamp. |
| **Time-travel scrubber** | Bottom rail; passive scrubbing rebases the view of history; explicit `Rewind here` button calls `restore-epoch`. | `(rf/epoch-history frame-id)` + `restore-epoch` + `reset-frame-db!`. |

v1 of re-frame-10x had ~6 panels. The growth is structural — re-frame2
emits more, and Causa surfaces it. Sidebar grouping (always-active /
conditional-with-activity / dormant) keeps the surface from feeling
cluttered; see [`007-UX-IA.md`](./007-UX-IA.md).

## The bar

A programmer hits `Ctrl+Shift+C`, sees the cascade their last click
triggered in the event-detail panel, asks "why?" — either in their head
(answered via `caused-by` and the inline mini-graph) or out loud (to
the co-pilot) — and has the answer in 5 seconds.

If a panel doesn't help answer "why?" or "what happened?" or "what's
wrong?", it doesn't ship. Restraint is what keeps the tool
habit-forming instead of impressive.

## Audience

- **The re-frame programmer mid-feature.** Wants the trace ribbon in
  their face but unobtrusive; wants click-to-source from anywhere;
  wants to scrub.
- **The programmer reading an unfamiliar codebase.** "Why does clicking
  Save eventually re-render this card three modules over?" — wants the
  causality graph.
- **The on-call programmer triaging a production-shaped repro.** Loads
  a session, scrubs, finds the divergence.
- **The AI agent driving the runtime.** Doesn't open the UI; consumes
  the same data through the same primitives via the MCP server. Causa
  and the agent surfaces are siblings, not parent/child.

## Where Causa fits

Causa is one of three downstream consumers of the same re-frame2
instrumentation surface:

- **`tools/pair2-mcp/`** — the editor-driven agent surface. Pair-shaped
  AI workflows over nREPL.
- **`tools/story/`** — the component playground. Embeds Causa's
  event-detail panel as a per-variant observability ribbon (per the
  embedding contract in [`008-Embedding-Contract.md`](./008-Embedding-Contract.md)).
- **`tools/causa/`** *(this tool)* — the human-driven debugger. Embeds
  `tools/machines-viz/` for state-chart rendering.

The dependency arrows: Causa → `machines-viz` (for the chart) →
`implementation/`. Story → Causa (for the embedded panel) →
`implementation/`. No cycles, no shared registries, no parent/child
relationships among the tools.

## Status

In design. Spec corpus landed 2026-05-12. All 13 direction-setting
decisions are locked — see
[`DESIGN-RATIONALE.md`](./DESIGN-RATIONALE.md). Implementation work
begins after spec ratification.
