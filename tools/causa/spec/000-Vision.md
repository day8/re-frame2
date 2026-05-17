# 000-Vision: Causa — the cascade you can see

## The claim

**Causa shows you what happens when an event fires.**

When a re-frame2 app misbehaves, the programmer points at one event and Causa
unfolds the full cascade behind it:

- every effect that ran,
- every subscription that recomputed,
- every component that re-rendered,
- every state mutation in `app-db`,
- every machine transition,
- every HTTP request issued and received,
- every flow that recomputed and every flow that was halted,
- every route navigation and every nav-token suppression,
- every SSR hydration mismatch, every schema violation,
- every cancellation that fell out of a parent-actor exit.

One event in; full insight out. Every artefact carries a source-coord chip;
click jumps the editor to the line. Every wall-clock thing (a `:after` timer,
an HTTP retry backoff, a streaming SSR boundary) carries the wall clock as a
visible axis. Every silent contract (a guard rejecting, a nav-token
suppressing, a fx skipped-on-platform, a flow cascade-halting) surfaces
loudly. Causa is the only place these contracts become legible.

re-frame-10x's claim was *"x-ray vision as tooling"*. Causa's claim is
the next-gen version of that vision — same depth on one event, plus the
cross-cutting machinery (machines · routes · SSR · managed effects) that
re-frame2 introduced and that no peer tool has the structured runtime data
to surface.

## How the claim cashes out

### One event at a time

Every Causa surface orients around **one focused event** — the spine sub
`:rf.causa/focus`. The user (or LIVE mode) picks an event in the L2 list;
every dependent panel rebinds atomically. Tabs are **lenses on that one
event**:

| Tab | What it shows for the focused event |
|---|---|
| **Event** (`e`) | The event vector + the six dominoes of its cascade. |
| **App-db** (`a`) | The diff `:db-before → :db-after` for this event. |
| **Views** (`v`) | The subs recomputed because of this event + the views that re-rendered. |
| **Trace** (`t`) | The raw trace stream filtered to this event's cascade. |
| **Machines** (`m`) | The transitions this event triggered + the spawn/destroy cascades it produced. |
| **Issues** (`i`) | The violations this event introduced — errors · warnings · schema · hydration · advisories. |

Plus the **Causality popover** (`c`) — invokable from any tab, showing the
graph of events that caused this event (ancestors) and the events this event
spawned (descendants).

This is the **single spine**: one selection, every surface rebinds. No
two-axis selection (`:selected-dispatch-id` × `:selected-epoch-id` from the
pre-spec drafts is gone). No panel reads `(peek history)`. No drift between
what the user is looking at and what the panel is rendering.

### Cross-cutting concerns extend the spine, never fragment it

re-frame2 introduces SSR, Routes, Machines, Managed-Fx — surfaces that
**unfold across wall-clock time and across boundaries** (server → client,
parent → child, request → response, nav-1 → nav-2). These are the cases
where stack traces are insufficient because the wall clock IS the bug
surface.

Causa does NOT fragment the chrome with per-area tabs (no Routes tab, no
SSR tab, no managed-effects tab). Instead, each existing tab grows
**deep specialised renderings** for the cross-cutting work that lands in it:

- The Event tab grows a per-fx **wire-boundary diff** for managed effects
  (request → wire → response → handler → app-db slice).
- The Trace tab grows a **wall-clock axis** for timer rings, retry
  waterfalls, deferred-dispatch arrivals.
- The Machines tab grows **timer countdown rings** on state nodes,
  **cancellation cascade visualisers**, **`:invoke-all` join inspectors**.
- The Issues tab grows **nav-token timelines** (swimlanes), **hydration
  mismatch bisectors**, **server error projection traces**.
- The Causality popover grows **machine-edge types** (spawn, final, join).

The five idioms — **wall-clock timelines · boundary diffs · cascade trees ·
schema explanations · lifecycle accounting** — repeat across all four areas
(SSR, Machines, Routes, Managed-Fx). One idiom, one rendering vocabulary;
every area picks up every idiom. See
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) for the
5-idioms × 4-areas matrix.

### Bug-driven, not feature-driven

Every Causa feature is grounded in a **concrete bug-class a programmer
hits**. The framing is uniform:

> **Bug class:** what the user is staring at when the question forms.
> **Example bug:** the vignette — "you dispatched X, expected Y, got Z because W."
> **Insight Causa provides:** what the user SEES that resolves the mystery.
> **Affordance:** the UI surface + interaction model.

No feature lands in the spec without a bug-class motivation. When in doubt,
add the bug; don't add the feature. The spec is auditable against this rule.

## The five canonical questions (the bar)

A programmer hits `Ctrl+Shift+C`, Causa lands on the freshest event
(`:rf.causa/focus` already points), and within five seconds they answer one
of:

1. **Why did this event fire?** — `c` popover; ancestor walk.
2. **What did this event change?** — App-db tab; diff `:db-before → :db-after`.
3. **Why is this subscription returning the wrong value?** — Views tab; per-sub
   invalidation chain + cache state.
4. **Why is this view re-rendering?** — Views tab; sub-driven attribution
   chain.
5. **What's currently broken?** — Issues tab; unified feed (errors · warnings ·
   schema violations · hydration mismatches · advisories).

If a surface doesn't help answer "why?" or "what happened?" or "what's
wrong?", it doesn't ship. Restraint is what keeps the tool habit-forming
instead of impressive.

## What it is

A re-frame2-native devtools surface that runs **in-app**, preloaded into
dev builds. The shell is the **4-layer chrome** (ribbon · event list · tab
bar · detail panel) — see
[`018-Event-Spine.md`](018-Event-Spine.md) for the full architectural
contract.

Every open lands on the latest cascade. The Event tab (default) shows the
event vector, source, handler return, db writes, fx, and the fx-handlers
that ran — the five canonical questions answer themselves on first paint;
deeper investigation is one tab away (`a` App-db · `v` Views · `t` Trace ·
`m` Machines · `i` Issues) or one keypress away (`c` Causality popover).

## What it isn't

- **Not the AI surface.** AI access to the running re-frame2 runtime goes
  through `tools/pair2-mcp/` (raw nREPL over MCP). Two doors, no compromises;
  no in-Causa AI co-pilot panel, no Causa-MCP server, no LLM chat rail.
- **Not a port of re-frame-10x.** The chrome is 10x-shaped at the layer
  level (events at top, detail at bottom, scrubber via ribbon+list,
  keyboard-first); the substrate is re-frame2's enriched trace bus, the
  multi-frame model, the machine and flow registries, and source-coord
  stamping.
- **Not a registrar, dispatch, effect, or component layer.** Causa is a
  downstream consumer of re-frame2's existing surfaces.
- **Not writeable into `app-db`.** Read-only forever (lock #3 in
  `DESIGN-RATIONALE.md`).
- **Not a session recorder.** No export, no import; sessions are ephemeral
  (lock #4). The recorder → `:play-script` export pipeline is a Story
  integration (see [`018-Event-Spine.md`](018-Event-Spine.md) §Recorder /
  Story integration); the running Causa session does not persist.
- **Not a mobile surface.** Desktop only; viewports below 600px refuse to
  mount (lock #5).
- **Not a Chrome extension.** In-app DOM injection plus a same-browser
  pop-out via `window.opener`. Remote-attach lives over pair2-mcp (lock #9).
- **Not part of any production bundle.** Per the bundle-isolation contract
  in `tools/README.md`, dependency arrows flow tool → implementation; Causa
  is invisible to consumer apps after elision.
- **Not a Stately editor / exporter.** No machine→xstate-json bridge; no
  Stately compatibility. Causa is the canonical rendering surface for
  re-frame2 machines.

## What re-frame2 unlocks

Each row is "new in re-frame2 → new tooling story Causa tells."

| re-frame2 capability | Causa surface |
|---|---|
| **Multi-frame** (Spec 002) | Per-frame inspection; single-select frame picker in ribbon; cross-frame causality via Causality popover. |
| **Machines** (Spec 005) | Stately-quality state-chart per machine; transition log; **`:after`-timer countdown rings** with scrubber-aware retro-replay; **`:invoke-all` parallel-child viz + join inspector**; UC1 interactive simulation; UC2 multi-instance Mode A/B/C; **cancellation-cascade visualiser**; **per-instance "why am I stuck" trace**; XState-parity supervision tree. ELK+SVG primitive ships Causa-internal. |
| **Flows** (Spec 013) | Surfaced in Views tab when a flow's downstream sub recomputed; **cascade-halt alarm** in Issues tab — names the downstream flows that did NOT run when an upstream flow's `:output` threw. |
| **Source-coord stamping** (Spec 001 + 006) | Click-to-source on every node, view, machine guard, transition, fx-handler, schema declaration. |
| **Trace bus** (Spec 009) | The substrate of everything. Causa does not invent its own trace shape. **Trace fattening** (carrying context-at-position on each event) enables the per-instance scrubber's Phase-5 replay-from-arbitrary-position affordance. |
| **Epoch history + `:rf/epoch-record` projections** (Tool-Pair) | First-class time-travel via the ribbon's `[◀ ▶ ⏭]` nav + the event list (L2). |
| **Six named restore failures** | Structured "this rewind won't work because X" rather than a silent no-op. |
| **`register-epoch-cb!`** | The per-cascade listener routes the Event tab + Issues tab + Causality popover. |
| **Schemas (Malli)** (Spec 010) | Schema-violation rows in the Issues tab; **per-violation drill** with full Malli explanation + recovery-mode classification + source-coord. |
| **SSR + hydration** (Spec 011) | **Hydration mismatch bisector** — canonical-EDN dfs to the first divergent node; server vs client side-by-side per `get-in` path; sub-attribution (which sub returned `nil` server / `:en-US` client + why). **Streaming SSR boundary timeline.** **Per-request response accumulator inspector.** **Head model inspector.** **Server error projection trace** (the security boundary visualised). |
| **Routing** (Spec 012) | Route is a sub-tree of app-db — surfaced in App-db tab; **nav-token timeline** (swimlanes) makes stale-clobber races literally visible; **`:on-match` chain explicit in Event tab**; **match-rank explainer**; **pending-navigation card**; **route-chain visualiser**. |
| **`:origin` opt on dispatch** | Filter by actor via ribbon IN/OUT pills. |
| **Data classification** (Spec 015) | Path-marked sensitive + large rendering: `[● REDACTED N]` magenta opaque + `[● ELIDED N]` yellow drillable. See [`018-Event-Spine.md`](018-Event-Spine.md) §12 for the per-surface rendering contract. |
| **Managed effects** (`spec/Managed-Effects.md` eight-property contract) | **Wire-boundary diff** in the Event tab's "fx handlers that ran" block — per fx, show request payload (post-elision) → wire transit (status / headers / timing waterfall) → response → handler dispatched → app-db slice touched. One template; five surfaces (HTTP, WebSocket, machine `:invoke`, SSR `:rf.server/*`, flows). **Active managed-effects dashboard** — Erlang-Observer for what the runtime is currently busy doing. **Stale-suppression badges** uniform across all four cross-cutting areas. |
| **Production-elision verifier** | Causa runs `npm run test:elision` and warns before deploy if a dev sentinel leaked. |

## The 6-tab inventory

The legacy 16-panel sidebar is dead. Causa ships a **6-tab detail panel**
(Layer 3 of the 4-layer chrome). Each tab is one projection of the focused
event; selection in the L2 event list rebinds every tab. Cross-cutting
concerns extend each tab; they do NOT add new tabs.

| # | Tab | Mnem | What it shows | Spec |
|---|---|---|---|---|
| 1 | **Event** | `e` | Whole event vector + arg-map + source · handler return · db writes · fx vector · fx-handlers that ran (with wire-boundary diff per managed fx). | [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Event-detail panel + [`018-Event-Spine.md`](018-Event-Spine.md) §5.1 |
| 2 | **App-db** | `a` | Diff `:db-before` vs `:db-after` — slice-first · pinned watches · full-tree disclosure · branch-aware diff (for story sim-clones) · cross-frame diff (for multi-frame shared substates). | [`004-App-DB-Diff.md`](004-App-DB-Diff.md) + [`018-Event-Spine.md`](018-Event-Spine.md) §5.2 |
| 3 | **Views** | `v` | Per-view rows: mounted / re-rendered / unmounted groups; **subs nested under each view row** showing return values; **per-sub invalidation-chain drill** (why did this sub re-run); replaces the legacy Subs panel. | [`012-Views.md`](012-Views.md) |
| 4 | **Trace** | `t` | Raw multi-axis trace stream filtered to the focused cascade; **wall-clock axis** for timer rings, retry waterfalls, deferred-dispatch arrivals; trace-type toggle row + IN/OUT pills + sensible defaults. | [`013-Trace-Bus.md`](013-Trace-Bus.md) + [`018-Event-Spine.md`](018-Event-Spine.md) §5.3 |
| 5 | **Machines** | `m` | UC1 (definition + sim) + UC2 (Mode A/B/C dynamic instances); supervision tree; **`:after`-timer countdown rings**; **cancellation cascade visualiser**; **`:invoke-all` join inspector**; **per-instance "why am I stuck" trace strip**; **hierarchical cascade animator**; **shift-click divergence highlighter**; **per-instance mini-scrubber**. MachineChart is Causa-internal. | [`003-Machine-Inspector.md`](003-Machine-Inspector.md) |
| 6 | **Issues** | `i` | JS exceptions + schema violations + sensitive-data warnings + hydration mismatches + perf-budget overruns + app console errors/warns + **flow cascade-halt alarms** + **open-redirect advisories** + **`:platforms` skip tallies** + **stale-suppression group**. | [`016-Auxiliary-Panels.md`](016-Auxiliary-Panels.md) §Issues ribbon + [`018-Event-Spine.md`](018-Event-Spine.md) §5.4 |

**Popovers** (transient overlays, invokable from any tab):

| Popover | Key | Content |
|---|---|---|
| **Causality** | `c` | Graph of events that led to this event (ancestors, LR layout) + events this event spawned (descendants, TB layout). Full hybrid LR+TB per-region layout. Resizable; per-session persisted size. Depth-disclosure text + per-segment hover tooltips. |
| **Nav-token timeline** | `r` | Horizontal swimlanes — each navigation is a bar; in-flight `:on-match` events ride above; stale-suppressed completions strike-through with carried-vs-current token visible. |
| **Wire-trace** | `f` | The active fx's wire-boundary diff popped out — same content as the Event-tab inline panel but floats over any tab. |

See [`018-Event-Spine.md`](018-Event-Spine.md) §10 for the popover invocation
contract.

## The bar

A programmer hits `Ctrl+Shift+C`, sees the cascade their last click triggered
in the Event tab — `:rf.causa/focus` already points at the freshest event —
asks "why?", and either reads the answer in the panel (event vector, source
coord, db writes, fx outcomes) or presses `c` to see the causality graph.
Five seconds.

If a tab doesn't help answer "why?" or "what happened?" or "what's wrong?",
it doesn't ship. Restraint is what keeps the tool habit-forming instead of
impressive.

## Audience

- **The re-frame programmer mid-feature.** Wants the event list in their
  face but unobtrusive; wants click-to-source from anywhere; wants to scrub.
- **The programmer reading an unfamiliar codebase.** "Why does clicking
  Save eventually re-render this card three modules over?" — wants the
  causality popover (`c`) and the Views tab.
- **The on-call programmer triaging a production-shaped repro.** Loads the
  app, scrubs via `[◀ ▶ ⏭]` + L2, finds the divergence.
- **The programmer debugging a streaming SSR / hydration mismatch.** Opens
  Causa, the Issues tab leads them to the divergent node; the bisector and
  the side-by-side answer "why does the server say `nil` and the client say
  `:en-US`?"
- **The programmer debugging a cancellation cascade.** Sees the parent
  machine's `:exit` ripple through three abort traces in one vertical
  waterfall, not as a confusing flurry across the Trace firehose.
- **The AI agent driving the runtime.** Doesn't open the UI; consumes the
  same data through the same primitives via `tools/pair2-mcp/` over raw
  nREPL. Causa and pair2-mcp are siblings; neither owns a registry; neither
  writes to app-db.

## Where Causa fits

Causa is one of two downstream consumers of the same re-frame2
instrumentation surface (the "two doors" split):

```
┌──────────────────────────────────────────────────────────────────────┐
│  HUMAN SURFACE                            AI / AGENT SURFACE          │
│                                                                       │
│  ┌────────────┐                          ┌────────────┐               │
│  │   Causa    │                          │ pair2-mcp  │               │
│  │  (this)    │                          │ (raw nREPL)│               │
│  └─────┬──────┘                          └──────┬─────┘               │
│        │                                        │                     │
│        └──── reads ──┬─── Spec 009 trace bus ───┴──── reads ──┐       │
│                      │   + Tool-Pair projections              │       │
│                      │   + Source-coord stamping              │       │
│                      │   + registries (sub/fx/cofx/machine)   │       │
│                      ▼                                        ▼       │
│                  ┌──────────────────────────────────────────┐         │
│                  │       implementation/ (the runtime)      │         │
│                  └──────────────────────────────────────────┘         │
└──────────────────────────────────────────────────────────────────────┘
```

**Causa and pair2-mcp are SIBLINGS.** Both read the same instrumentation;
neither owns a registry; neither writes to app-db. The split is intentional
and load-bearing:

- **Causa = human surface.** Visual chrome, keyboard-first power-user UX,
  painted UI, virtualisation, animation. The five-canonical-questions
  answer themselves on first paint.
- **pair2-mcp = AI surface.** Raw nREPL over MCP. Whatever the agent wants
  to do, it does by evaluating Clojure against the runtime — no curated
  AI-shaped facade in front.

This kills the false middle: a curated *Causa-MCP* would be a tax on both
surfaces (it duplicates pair2-mcp's responsibility and degrades Causa's
freedom to evolve its UI without breaking an AI contract). Drop it.

**Story (`tools/story/`)** — the component playground — embeds Causa's
Event tab as a per-variant observability ribbon (per the embedding contract
in [`008-Embedding-Contract.md`](008-Embedding-Contract.md)). The recorder
→ `:play-script` pipeline (per
[`018-Event-Spine.md`](018-Event-Spine.md) §Recorder / Story integration)
turns a captured Causa session into a Story-runnable script — the only
"export" Causa offers, and it lands in Story's persistent layer, not
Causa's.

The dependency arrows: Causa → `implementation/machines/` (directly; no
`tools/machines-viz/` hop). Story → Causa → `implementation/`. No cycles,
no shared registries, no parent/child relationships among the tools.

## Status

Pre-alpha. Substantial rewrite from the legacy 16-panel sidebar to the
4-layer chrome (see [`018-Event-Spine.md`](018-Event-Spine.md)). All
direction-setting decisions are locked — see
[`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md). Implementation work is
sequenced after spec ratification; the bead matrix carries the sequencing.

This spec describes the **destination** — the full vision Causa is
heading toward. Many features called out above are not in v1; they are
documented here so the spec reads as the place we're going, not the place
we've stopped. Where a section says "v1 ships X; future: Y", the **Y** is
the main read; the **X** is the staged-delivery callout.

For the cross-cutting vision — the 5-idioms × 4-areas (SSR · Machines ·
Routes · Managed-Fx) matrix — see
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md).
