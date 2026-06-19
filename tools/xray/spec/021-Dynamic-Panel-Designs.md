# 021-Dynamic-Panel-Designs

Worker design doc for the Xray **Dynamic L4 panel redesign** (rf2-dur6w).
(File previously titled "Dynamic Panel Designs" — renamed to track the
locked Static/Dynamic linguistic pairing per the polished super-prompt.
"Dynamic" = what's happening across epochs; "Static" = what's registered
before any event fires.)
Co-drafted by Mike + mayor; this doc is the implementer's reference
for the **per-panel content layout**, **shared edn-inspector renderer**,
**locked decisions**, and **substrate gaps** that the redesign implies.

Canonical foundation: `ai/prompts/xray-interface-adjustments.md`
(local-only working doc; not in repo). The framing in §1 below is a
synthesis — the super-prompt remains the authoritative statement of intent.

Cross-refs:
- [`000-Vision.md`](000-Vision.md) — the five canonical questions
- [`007-UX-IA.md`](007-UX-IA.md) — chrome, palette, density (still load-bearing)
- [`013-Trace-Consumer.md`](013-Trace-Consumer.md) — substrate the panels read
- [`018-Event-Spine.md`](018-Event-Spine.md) — `:rf.xray/focus` contract
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) — 5 idioms × 4 areas

Owner: tools/xray.

---

## §0 Design principle: information density is binding

Quoted verbatim from the canonical super-prompt (B.0):

> Each L4 panel surfaces a lot of data per focused epoch. The design MUST
> embrace this density, not minimize it.
>
> - Developers debugging dynamics need to **see a lot at a glance** —
>   pipeline stages + values + paths + cause attributions all together.
> - Dispersing the same info across many panels or hiding it behind clicks
>   forces context-switching that breaks the debug flow.
> - Insight emerges from co-visibility of related details — that's the
>   "x-ray glasses with HUD" promise.
>
> Design for **competent developers comfortable with high information
> density**. Think trader workstation, not consumer app. Tight spacing,
> small font, every pixel earning its place.

**What this binds, mechanically:**

| Do | Don't |
|---|---|
| Dense default views; only the **deepest** data nesting gets lazy-tree collapse (§10) | Hide information behind "Show details" toggles by default |
| Inline annotations (`← was :idle` · `(input unchanged · skipped)`) | Defer to expand-to-see when the data could be shown inline |
| Use color, weight, and inline annotations to LAYER info without spreading | Generous whitespace idiomatic in consumer apps |
| Co-visible related details on one surface (pipeline stages + values + paths together) | Cross-panel scatter for things the operator wants to see together |

**Density baseline (resolved via tokens):** body 13px / mono 12px /
line-height 1.35, per `theme/tokens/type-scale` — already runs ~1px
below the spec's cosy baseline because Xray is an info-dense dev
surface. JetBrains Mono throughout per Xray convention (§007). Every
per-panel section below carries an explicit **density note** restating
this in-context — they are reminders, not exceptions.

This principle is binding on every panel; subsequent design decisions
(default-expanded steps in §2, inline diff annotation over side-by-side
in §10, footer-collapsed unchanged subs in §3.4) all derive from it.

---

## §1 Framing — the load-bearing model

Xray's chrome is two zones, one purpose each:

```
┌──────────────────────────────────────────────────────────────────────┐
│  L1 ribbon · L2 epoch timeline                ← MOVING BETWEEN epochs│
├──────────────────────────────────────────────────────────────────────┤
│  L4 panels (7 lenses on the focused epoch)    ← DEPTH INTO one epoch │
└──────────────────────────────────────────────────────────────────────┘
```

**Top** carries the only cross-epoch signal — the L2 epoch timeline + its
per-row badges (`⚠ ◆ 🌐 ⚡ 💧 🌊 ⏲`) and the dispatch-origin tag prefix
(`user / fx / route / hyd / ws / timer / tool / internal`). **Bottom** is
seven L4 panels each answering "what happened in this epoch?" through its
own lens. **No third axis. No cross-epoch L4 panels.**

### §1.1 The epoch — eight steps, two perspectives

Re-frame2 runs as a sequence of epochs. **One epoch = one dequeued
event's full chain** — the epoch boundary is the per-DEQUEUED-EVENT, not
the drain-settle (per [framework 002 §Drain versus event — the epoch
unit](../../../spec/002-Frames.md#drain-versus-event--the-epoch-unit) /
rf2-nj6p7). Each `:fx :dispatch` child event and each frame-init event is
its OWN epoch, carrying its own `:rf/epoch-record` (`:db-before` /
`:db-after` / `:trace-events` / `:dispatch-id`). A user gesture that
fans out to N child dispatches therefore produces N+1 epochs, each a
focusable L2 row — NOT one merged "settle" epoch. This is why every L4
panel can scope cleanly to "the focused epoch's record" (§1.2): the
record IS one event's complete cascade.

The split that organises the L4 panels is **handling vs reactive** —
state-mutating vs state-observing.

| Phase | Steps | What |
|---|---|---|
| **Handling** (state-mutating) | Dispatch → Coeffects → Handler → Flows recompute → Effects | The `Epoch` L4 panel renders the full computational timeline as a numbered vertical cascade (DISPATCH · COEFFECTS · HANDLER · FLOW · FX · SUBSCRIPTIONS · VIEWS; conditional rendering per §9.1.3). It supersedes the retired Event/Handler panel (§2 below, kept as historical reference) per rf2-5gl5r. Flows recompute at the outermost `:after`, reshaping the pending `:db` before it commits — so they precede the post-commit FX. |
| **Pivot** (the keystone) | — | Handling → reactive transition. The architectural inflection. App-db panel sits on this boundary. |
| **Reactive** (state-observing) | Subs recompute → Views re-render | The `View` L4 panel renders the cascade as a left → right graph (§3); the Epoch panel ALSO surfaces SUBSCRIPTIONS + VIEWS as the trailing steps of its numbered cascade. |

The pivot from handling to reactive is the architectural keystone (per A.3
super-prompt). All state mutation is left of the line; all state
observation is right of it. **Event** and **View** are PEERS — not
master + detail — bridged by **App-db**.

### §1.2 Scope rule — every L4 panel is focused-epoch-scoped

Every L4 panel answers "what happened in this epoch?" — each through its
own lens. **No exceptions.** The only cross-epoch signal lives on the L2
timeline as per-row badges (B.1.1 super-prompt; restated in §1 here).

This is binding: workers implementing per-panel beads MUST NOT introduce
"aggregate across epochs" subviews inside L4 — those go on L2 as badges,
or out-of-scope.

**Epoch-record correlation via `focus.epoch-id` (rf2-rly4a).** The
panels resolve "the focused epoch's record" by joining the spine's
`:rf.xray/focus` (carrying `:epoch-id`) with `:rf.xray/epoch-history`
(the framework's per-frame ring of `:rf/epoch-record` maps) through the
shared `panels.shared.focus-resolver` — which classifies the focus status
(`:no-focus` / `:focused` / `:epoch-evicted`, with the head-fallback) and
looks up the record. The cascade↔epoch correlation is backed by the
`:dispatch-id` slot on the epoch record: a cascade (L2 row) maps to its
settling epoch, and `focus.epoch-id` is the canonical key the
Views / Trace / App-DB Diff / Issues panels all scope by. This is what
keeps Views + Trace showing the SAME event's data (rf2-rly4a fixed a
regression where they could drift apart).

### §1.3 Inspect vs Rewind — non-destructive by default

When the operator clicks an L2 row, the gesture is **INSPECTION** — the
L4 panels rebind to that epoch's captured snapshots; app-db is NOT rolled
back, subs do NOT recompute, views do NOT re-render. Meta-epoch context
("machine state as of epoch #42") reads from historical snapshots.

A separate **REWIND** affordance (e.g. "⏪ Rewind to here" button in
the focused-epoch header, with confirmation dialog) is destructive: app-db
actually restores, events after the focused epoch are discarded, the
runtime keeps going from there. Sub/view re-fire happens as the runtime's
normal job — the natural runtime response to db change.

**Affordance principle: inspection-by-default · rewind-by-affordance.**
Idle scrubbing never accidentally mutates state.

### §1.4 Captured-not-replayed (substrate requirement)

Because inspection is non-destructive, every per-epoch datum must be
**captured at trace-bus emission time** and stored in the per-frame epoch
buffer — never derived on inspection by replay. This is a runtime
substrate concern (`re-frame.core` + per-tool `mcp-base`), not a Xray
panel-design concern; Xray reads what the substrate retains.

Per-epoch buffer eviction surfaces as **"Epoch evicted from buffer —
increase `:epoch-history` to retain more"** placeholder text in any panel
the operator scrubs onto for an evicted row (see §10.3 below).

### §1.5 Dispatch origin — the universal classifier

Every epoch carries a dispatch origin tag (per A.5 super-prompt):
`:user` `:router` `:websocket` `:http` `:ssr` `:fx-emit` `:timer`
`:test-harness` `:tool` `:internal`. The Epoch panel (§9.1) surfaces
this prominently on the DISPATCH step; the L2 timeline surfaces it as
a short prefix on each row. There is no such thing as a context-less
epoch.

### §1.6 Single-frame focus — the frame is a VIEW SCOPE (rf2-4vp5j)

Xray observes one target frame at a time, picked via the L1 chrome-ribbon
frame dropdown. The frame is a **single, defaulted VIEW SCOPE, not a
filter** (rf2-4vp5j): it defaults to the head epoch's frame, single-select,
is NOT persisted (resets on load — rf2-swclw), and is NOT counted as
"hidden by filters". The L2 timeline lists that frame's epoch stream; the
L4 panels read state from that frame. No per-frame split layouts, no
colour-coding-by-frame on the timeline. Multi-frame apps are inspected by
switching focus. See [`018-Event-Spine.md` §Frame picker is a view
scope](018-Event-Spine.md) + [`020-Filter-Predicates.md` §3](020-Filter-Predicates.md).

---

## §2 The Event panel (handling perspective) — RETIRED 2026-05-27 (rf2-5gl5r)

> **Status — retired.** rf2-5gl5r deleted `panels/event_detail.cljs`
> and the `:event` tab registration in favour of the §9.1 Epoch panel.
> The Epoch panel renders the full computational timeline (DISPATCH
> through VIEWS) as a numbered vertical cascade and supersedes this
> design as the canonical "what happened in this epoch" surface. The
> §2 design is kept here as historical reference for the operational-
> order pipeline (rf2-ynnre B+) — its sub-sections (§2.2 layout, §2.3
> queries, §2.4 cross-panel nav, §2.5 film-strip) DO NOT describe a
> live panel and are not normative post rf2-5gl5r. Working surfaces
> for the same questions live at:
>
> - **§9.1 Epoch panel** — the numbered cascade (DISPATCH · COEFFECTS ·
>   HANDLER · FLOW · FX · SUBSCRIPTIONS · VIEWS).
> - **§4 App-db panel** — the committed `:db` diff per epoch.
> - **§3 View panel** — the reactive cascade.

### §2.1 Question

> **"What did this event DO?"**

End-to-end mutation pipeline for the focused epoch.

**Density note** (per §0). Workstation feel: all sections render
default-expanded; the pipeline IS the punch and hiding it behind
"Show details" would undercut the lens. Target ~28-40 lines visible at
default density on a 1080p screen (the dense case in §2.2 lands at ~40
lines including the rail). Per-section collapse stays available via header
click for the operator who wants to focus, but is opt-out, not default.

### §2.2 Layout — numbered vertical-flow pipeline (operational order · rf2-ynnre B+)

> **Operational order — supersedes Figma-A** (rf2-ynnre, Mike's 2026-05-25 decision on
> rf2-5t9h0 = B+). The Figma-A order (HANDLER → APP-DB CHANGES → FLOWS → FX) was drawn
> BEFORE the atomicity contract was pinned (Mike 2026-05-24, [`013-Flows.md §Failure
> semantics`](../../../spec/013-Flows.md#failure-semantics)) and read
> "result-then-attribution". B+ swaps to operational order — the panel's section rhythm
> now teaches the contract for free: handler returns pending `:db` → flows reshape it
> (pre-commit) → atomic install → fx (post-commit, irreversible). The prior
> chevron/bare-label/`EFFECTS RETURNED+APPLIED` shape and the brief's outcome-badge
> proposal remain **superseded**.

The Event panel expresses its top-to-bottom one-way pipeline as a **thin vertical line down
the panel's left edge** with a small **numbered step circle** (`1`, `2`, …) at each section.
The line is muted (`var(--border-subtle)`); the circles are filled muted with white numerals.
Steps are numbered **dynamically** — an absent optional section consumes no number, so the
visible steps always read `1..N` contiguously. Section labels are uppercase caption-weight,
muted (`var(--text-secondary)`); the numbered circles + the rail carry the ordering.

The **mode-accent stripe** (the single GitHub-blue accent, both modes) sits at the panel's edge.

Optional sections (COEFFECTS, INTERCEPTORS, FLOWS) are **shown only when present** —
absence is conveyed by omission, not an empty-state line. A throwing handler therefore simply
has no APP-DB CHANGES / later sections; there is **no outcome badge** and **no "db committed"
footer**.

Section order (numbered; optional sections shown only when present):

  1. **DISPATCH** — the event vector + `FROM: <source>` (the dispatch-origin, a
     click-to-source link).
  2. **COEFFECTS** *(optional)* — user-injected coeffects: each id (click-to-source) + the
     value it added to context (`+ [:now] #inst…`).
  3. **INTERCEPTORS** *(optional · rf2-se9a9t · EP-0022 §11)* — the **authored interceptor
     chain** that wraps this event's handler, shown when the event carries authored
     (non-`:rf/default?`) interceptor refs. The authored chain WRAPS the handler — frame-refs
     then event-refs run `:before` on the way in — so it renders BEFORE EVENT HANDLER. Each
     row is the AUTHORED ref (a bare keyword `:auth/required` or an `[id arg]` factory ref
     `[:rf.interceptor/path [:cart]]`) as a click-to-source link, ENRICHED with the RESOLVED
     descriptor's hook shape (`before` / `after` / `before/after` / `factory`) read via
     `(handler-meta :interceptor id)`, a `ref` / `inline` badge, the factory `:arg`, and a
     `missing` chip for an unregistered ref (`:rf.error/unregistered-interceptor`). This is
     the **clean-chain** surfacing (EP-0022 §11 (a) authored refs + (b) resolved chain) — the
     gap the exception-only INTERCEPTOR step (rf2-yz57h, below) left. The framework
     auto-wrapper (`:rf/event-handler`, `:rf/default?`) is filtered out — it is not an
     authored program member; **hidden entirely when the event carries no authored refs**
     (the common case). The step is purely informational and does NOT inflate the epoch
     outcome. The authored refs are not on the trace stream (a clean chain emits no
     per-interceptor "ran" trace), so the panel reads them from the REGISTRY at render time;
     the pure projection threads a resolver in (`projection/authored-interceptors-step` ←
     `epoch-panel/resolve-event-interceptors`) and stays JVM-testable. **Override-substitution
     surfacing (§11 (c) · rf2-9vx0jk):** the per-dispatch `:interceptor-overrides` substitutions
     that ACTUALLY took effect (merged per-frame ++ per-call, per-call winning) now ride the
     `:rf.event/run-start` trace event's `:rf.interceptor/override-summary` tag (id/count-only —
     Spec 009 §`:tags` interceptor family). The panel PREFERS that per-dispatch trace fact when
     present: a row whose authored ref the summary reports as `:replaced` or `:removed` gains an
     `:override` slot rendered as a `replaced` / `removed` badge — showing the per-dispatch delta
     the registry read alone cannot. On the override-free hot path the tag is absent and the rows
     fall back to the registry-reconstructed AUTHORED + RESOLVED chain with no `:override` stamp.
  4. **EVENT HANDLER** — the verb (`reg-event`, the one public event-registration form after
     EP-0018; `reg-machine` for machine handlers) as a click-to-source
     link + the **syntax-highlighted handler source** in a code block + a **returned
     effects sub-block** (the t1 pre-commit observable: the pending `:db` VALUE + each
     entry of the returned `:fx` vector). Per rf2-ta0y7 (Mike 2026-05-25) the substrate
     stamps the full pending `:db` value onto the `:rf.event/db-pending` (t1) trace event
     under `:tags :rf.event/db`; the panel renders it as an inspectable EDN tree (same
     posture as `:rf.event/fx` — full value, no diff, PDS structural-sharing keeps the
     cost negligible). When the runtime is older than rf2-ta0y7 (no t1 on the stream)
     the block falls back gracefully to a presence-only placeholder pointing at APP-DB
     CHANGES for the committed diff.
  5. **FLOWS** *(optional)* — flows that recomputed + the db path they wrote (the t1→t2
     reshape · pre-commit). Flows fire at the outermost `:after` interceptor, right after
     the handler and any user `:after` interceptors — they reshape the pending `:db` BEFORE
     the single deferred install (the atomic commit), so they precede APP-DB CHANGES and run
     long before FX. **Hidden entirely when no flows fired this event** (the common case);
     the flow-less panel reads as the simpler HANDLER → APP-DB CHANGES → FX shape. Per
     rf2-ta0y7, when t2 (`:rf.event/db-pending-post-flow`) is present the section ends with
     a trailing **post-flow `:db` summary** rendering the full flow-augmented value — so
     the t1→t2 reshape reads naturally as "what the handler returned" (under EVENT HANDLER
     above) → "what flows reshaped" (the per-flow rows) → "the post-flow result" (the
     trailing summary). The framework does NOT precompute a diff; the values are full at
     both endpoints, and any client-side diff is cheap.

     > **The INTERCEPTOR (exception-only) step (rf2-yz57h / rf2-vew2n).** Distinct from the
     > authored-chain INTERCEPTORS step at (3) above, a SECOND, exception-only interceptor
     > surface exists: when a USER interceptor THROWS, an `INTERCEPTOR` step renders the
     > throwing interceptor (id + `:before`/`:after` phase chip + the shared exception card),
     > phase-split — a `:before` throw renders BEFORE EVENT HANDLER, an `:after` throw AFTER
     > it. It is conditional on a throw (the substrate emits no per-interceptor "ran" trace),
     > so a clean chain leaves it empty; the clean chain is surfaced by the INTERCEPTORS step
     > at (3) instead. *(The pre-rf2-se9a9t draft named a speculative "AFTER INTERCEPTORS"
     > step at this position that the implementation never carried — rf2-se9a9t replaced it
     > with the authored-chain INTERCEPTORS step at (3) + this exception-only note.)*
  6. **APP-DB CHANGES** — the **COMMITTED diff at t4** (the atomic install boundary). Carries
     a `committed diff (post-flow · atomic install boundary)` caption to keep users from
     reading the section as "what the handler returned" (the handler's pending effects map
     lives one step up). The diff: `~ [path] old → new` · `+ [path] value` · `- [path]`.
     When flows fired, includes the flow-driven slot mutations; when none fired, this
     equals the handler's pending `:db` diff.
  7. **FX** — the fx handlers that ran (`:dispatch → […]` · `:http-xhrio → {…}`). Carries
     a `post-commit · irreversible (fx throws don't wind app-db back)` caption — the
     atomic install boundary at t4 has crossed, side effects (http / nav / dispatch) may
     already have fired, and an fx-throw surfaces an error but leaves app-db committed
     (per [`013-Flows.md §Failure semantics`](../../../spec/013-Flows.md#failure-semantics)).

The cascade-id stays internal (`data-dispatch-id` on the lens root for tests/agents); not
shown. The sections form a one-way pipeline — **linear numbered flow, not a flat list.**

The sketches below match the numbered section order above +
`tools/xray/design-reference/xray_devtools_reference.cljs` (the `event-panel` component): a
thin vertical rail down the left edge with a small **numbered step circle** (`①②…`) at each
section, sections rendered top→bottom,
optional sections (COEFFECTS / INTERCEPTORS / FLOWS) shown only when present. The `↗`
glyphs mark click-to-source links (DISPATCH origin, each COEFFECT id, EVENT HANDLER, each
authored INTERCEPTORS ref id, each FLOW id). There is **no outcome badge** and **no "db committed" footer** —
absence of a step is conveyed by omission. Diff glyphs follow the cascade gutter (`~` modified ·
`+` added · `-` removed).

Dense case (default — focused epoch is a normal event with coeffects, authored interceptors,
flows, and fx):

```
┌─ EVENT · :counter-inc · epoch #42 ─────────────────── [◀ Prev] [Next ▶] ─┐
│▌ stripe: mode accent (one GitHub-blue accent, both modes)                 │
│                                                                          │
│  rail                                                                     │
│   │                                                                       │
│  ①  DISPATCH                                                              │
│   │    [:counter-inc]                                                     │
│   │    FROM: view ↗                                                       │
│   │                                                                       │
│  ②  COEFFECTS                              (optional · shown when present) │
│   │    :now ↗                                                             │
│   │      + [:now]      #inst "2026-05-23T12:30:05.123Z"                   │
│   │    :session ↗                                                         │
│   │      + [:session]  {:user-id 42, :token "..."}                       │
│   │                                                                       │
│  ③  INTERCEPTORS                           (optional · shown when present) │
│   │    authored chain (wraps the handler)                                 │
│   │    :auth/required ↗            before    ref                          │
│   │    :rf.interceptor/path ↗      factory   ref   [:counter]            │
│   │                                                                       │
│  ④  EVENT HANDLER ↗                                                       │
│   │    (rf/reg-event :counter-inc             ← syntax-highlighted        │
│   │      (fn [{:keys [db]} _]                                             │
│   │        {:db (update db :counter inc)                                  │
│   │         :fx [[:dispatch [:title/flow [:rf/init]]]]}))                 │
│   │    ↳ returned effects (pre-commit)                                    │
│   │       :db   pending — see APP-DB CHANGES below for committed diff     │
│   │       :fx   1 entry — see FX below for what ran                       │
│   │             [:dispatch [:title/flow [:rf/init]]]                      │
│   │                                                                       │
│  ⑤  FLOWS                                  (optional · shown when present) │
│   │    :totals-flow ↗  →  [:totals] recomputed                           │
│   │      + [:totals :sum]  42                                            │
│   │                                                                       │
│  ⑥  APP-DB CHANGES                                                        │
│   │    committed diff (post-flow · atomic install boundary)               │
│   │    ~ [:counter]       1 → 2                                           │
│   │    + [:last-updated]  #inst "2026-05-23T12:30:05"                     │
│   │                                                                       │
│  ⑦  FX                                                                    │
│        post-commit · irreversible (fx throws don't wind app-db back)      │
│        :dispatch    → [:title/flow [:rf/init]]                            │
│        :http-xhrio  → {:method :get, :uri "/api/data"}                    │
└──────────────────────────────────────────────────────────────────────────┘
```

Sparse case (focused epoch is a noisy timer — no coeffects, no authored interceptors, no flows;
the optional sections are simply omitted, so the visible steps renumber `①②③`):

```
┌─ EVENT · :poll/tick · epoch #87 ───────────────────── [◀ Prev] [Next ▶] ─┐
│▌ stripe: mode accent                                                      │
│                                                                          │
│   │                                                                       │
│  ①  DISPATCH                                                              │
│   │    [:poll/tick]                                                       │
│   │    FROM: timer ↗                                                      │
│   │                                                                       │
│  ②  EVENT HANDLER ↗                                                       │
│   │    (rf/reg-event :poll/tick …)           ← syntax-highlighted         │
│   │    ↳ returned effects (pre-commit)                                    │
│   │       :db   pending — see APP-DB CHANGES below for committed diff     │
│   │                                                                       │
│  ③  APP-DB CHANGES                                                        │
│   │    committed diff (post-flow · atomic install boundary)               │
│   │    ~ [:poll :n]  41 → 42                                              │
│   │                                                                       │
│  ④  FX                                                                    │
│        post-commit · irreversible (fx throws don't wind app-db back)      │
│        (none)                                                             │
└──────────────────────────────────────────────────────────────────────────┘
```

### §2.3 Queries (what the panel reads)

| From | Reads |
|---|---|
| Focused epoch record | `:rf.event/dispatched` (step 1), `:rf.cofx/*` (step 2 — coeffect injection), `:rf.event/run-start` / `:rf.event/run-end` (step 3 — handler), `:rf.event/db-pending` (step 3 — t1, the handler's pending `:db` VALUE feeds the returned-effects sub-block under EVENT HANDLER · rf2-ta0y7), `:rf.fx/do-fx` (step 3 — also feeds the returned-effects sub-block under the handler, via `:rf.event/fx` + `:rf.event/db-present?`), `:rf.flow/computed` (step 4 — flows reshape the pending `:db` at the outermost `:after`, before commit), `:rf.event/db-pending-post-flow` (step 4 — t2, the flow-augmented `:db` VALUE feeds the trailing post-flow summary under FLOWS when flows changed the value · rf2-ta0y7), `:rf.event/db-changed` (step 6 — the committed diff), `:rf.fx/handled` per fx-id (step 7 — effects applied) — all read from the focused epoch record's `:trace-events` (one epoch = one dequeued event, §1.1) |
| Registries | Handler metadata (`reg-event` form file:line, optional source string when DEBUG-gated). **INTERCEPTORS step (rf2-se9a9t · EP-0022 §11):** the authored interceptor chain is read from the registry at render time — `(rf/handler-meta :event event-id)` `:interceptors` for the authored refs (the framework `:rf/default?` wrapper filtered out), each ref resolved via `(rf/handler-meta :interceptor id)` for its descriptor hooks + source-coord. A clean chain emits no per-interceptor "ran" trace, so this is a REGISTRY read, not a trace read; the pure projection threads the resolver in via `epoch-panel/resolve-event-interceptors`. |
| App-db panel (bridge) | Inline diff renderer for the committed `:db` — the APP-DB CHANGES section (reuses the shared renderer §10) |

### §2.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| DISPATCH `FROM: <source>` link | Open-in-editor (Xray's existing `:rf.xray/open-in-editor`) at the dispatch-origin call-site |
| COEFFECTS id ↗ | Open-in-editor at the coeffect registration file:line |
| INTERCEPTORS ref id ↗ (rf2-se9a9t) | Open-in-editor at the authored interceptor's `reg-interceptor` registration file:line (resolved off `(rf/handler-meta :interceptor id)`; drops to plain text when no coord — the `reg-interceptor*` fn path / framework interceptor / production-elided coord) |
| EVENT HANDLER ↗ | Open-in-editor at handler file:line |
| SIDE EFFECTS `:db` row `→ app-db` marker (rf2-j630b) | Switch to the **App-db** panel for the focused epoch (`[:rf.xray/select-tab :app-db]`; the panel reads the same shared focus). The marker is a DESTINATION pointer — the committed db diff lives in the App-db panel, not duplicated in the ledger |
| SIDE EFFECTS `:fx` row fx-id ↗ (rf2-g1mfc) | Open-in-editor at the `reg-fx` registration file:line (shared `coord-chip`, parity with the HANDLER verb + SUBSCRIPTIONS / VIEWS rows; sources the absolute coord off `(rf/handler-meta :fx <fx-id>)`) |
| FX row | Switch to **Trace** panel, scrolled to the `:rf.fx/do-fx` / `:rf.fx/handled` op for that fx; if `:http/managed`, the badge offers the wire-trace popover |
| FLOWS row | Switch to **App-db** panel, scrolled to the path that flow wrote |
| Click any path segment in a COEFFECTS / DB CHANGES value | Cross-panel propagation per §10.5 (App-db ↔ View); no other value interactions |

### §2.5 Film-strip back/forward

Header `[◀ Prev] [Next ▶]` walks the L2 spine chronologically. MVP
semantics: **next chronological epoch** regardless of dispatch-origin
(per B.5 super-prompt). Stretch: per-panel filter (`Next epoch with same
dispatch-origin`).

Global keyboard: `←` / `→` always bound (matches L1 ribbon nav). Within
Event panel, `j` / `k` work too (consistent with L2 spine nav).

---

## §3 The View panel (reactive perspective · steps 7-8)

### §3.1 Question

> **"What RENDERED as a result?"**

Reactive sweep — sub cascade + view re-renders, scoped to the focused epoch.

**Rename history.** Original name: `Views`. Renamed to `Reactive`
(rf2-wyvf2 · §11.5) to align with the perspective split. Renamed
again to `View` per rf2-e33ad — the panel's primary subject is the
rendered **view** (hover a view-row, the rendered DOM highlights),
with the sub cascade as supporting context. Settled back on the
plural `Views` per Mike-direction 2026-05-21 (canonically ratified
rf2-5i8nn 2026-06-02): the all-plural-domain-noun convention aligns
the tab vocabulary — Views / Flows / Schemas / Routes / Machines are
all plural — and the Figma export (rf2-ad7zx) renders `Views`. The
internal panel-registry key stays `:views` (never a user contract).
The L4 tab label renders as `Views`.

### §3.1.1 Layout (rf2-e33ad · rf2-isun6)

Per Mike-direction 2026-05-21 the panel renders the cascade as two
bare-label pipeline sections (mirroring the rf2-n4ad0 numbered-cascade
rhythm now carried forward by the §9.1 Epoch panel — thin left rail +
downward chevrons):

  1. **SUBS THIS CASCADE (count)** — **one table**, one row per sub
     that *ran* this cascade (the union — formerly the "SUBS RAN"
     set). Columns:

     | column | meaning |
     | --- | --- |
     | `sub-id` | the sub's query-id (mode-accent keyword tone) |
     | `changed?` | ✓ when the sub's value changed (`sub-changed?` — `:value-changed?` / `:prev-value` ≠ `:value`) |
     | `cascaded?` | ✓ when an upstream sub drove the recompute (`sub-cascaded?` — `:cascade?` / `:cause-sub`); the upstream `:cause-sub` rides as muted `← :s/foo` secondary text on the ✓ |
     | `code` | the `[code]` source-coord chip — opens the sub's registration in the editor |

     **Each sub appears EXACTLY once.** The `changed?` / `cascaded?`
     dimensions are *columns*, not separate lists (rf2-isun6). This
     replaces the prior three overlapping sections — **SUBS RAN**,
     **SUBS WHOSE VALUE CHANGED**, **SUBS THAT CASCADED** — in which a
     sub that ran *and* changed *and* cascaded was repeated in all
     three (redundant). The dirty-check predicates (`sub-changed?` /
     `sub-cascaded?`) survive — they now drive the two flag columns
     rather than `filterv`-splitting the run-set into three lists.

  2. **VIEWS RE-RENDERED (count)** — entries named via the
     `reg-view :name` slot (fallback: var name) + `[code]` chip +
     hover-highlight on the rendered view's root DOM node

**testids.** The table carries `rf-xray-reactive-subs-table`; each
row is `rf-xray-reactive-sub-row-<slug>` and its code chip
`rf-xray-reactive-sub-code-<slug>` (slug = sub-id with non-alnum
flattened to `_`). The empty-but-focused placeholder is
`rf-xray-reactive-subs-empty`. (The prior per-section
`rf-xray-reactive-sub-ran` / `…-subs-{ran,changed,cascaded}-empty`
testids are retired with the three-list layout.)

**Hover-highlight contract.** Hovering a view-row stamps a subtle
background-only highlight (`var(--bg-3)`) on the rendered view's
root DOM node, matched via the `data-rf-view` attribute the
framework already stamps per Spec 006 §View tagging contract. The
highlight is background-only — NO border / outline / shadow that
would perturb layout. Cleared on mouseleave.

**Density note** (per §0). The cascade tree renders inline with full
attribution (`caused-by ← sub ← path`) on each leaf — no expand-to-see.
Unchanged subs are the **only** thing hidden by default (footer
disclosure per §3.4) because they're coverage signal, not signal-of-
the-moment. Target ~24-32 lines visible at default density; cascades
deeper than 4 levels rare enough that vertical scroll is acceptable.

### §3.2 Layout — DAG visualised as indented cascade

The reactive cascade is a DAG (§A.3 super-prompt).

**Layout — a left → right reactive-flow graph (Figma design — rf2-ad7zx).** Reconciled to
`tools/xray/design-reference/xray_devtools_reference.cljs` (the `views-panel` component), the
later iteration. The panel renders
the cascade as a **left → right node-and-edge graph** (an inline SVG canvas headed `REACTIVE
FLOW`), not the prior depth-first indented tree. Columns, left → right:

- **app-db** — a **single source node** at the left, with an edge to **each Level-1 sub** (every
  Level-1 sub reads app-db; the fan-out is a plain edge, no path detail — see §3.2 constraint 1).
- **Level-1 subs** (extractors) — each drawn with its changed/unchanged state.
- **Level-2 subs** (derived via `:<-`; *optional* layer; precise `:<-` edges).
- **Views** (right-most — **the focus**) — each tagged re-rendered + *why*. A view re-renders for
  exactly one of two reasons: a **subscription** it derefs changed value, or its **props** changed
  (the orthogonal `:rf/props` channel). The per-view cause sub-label attributes which (rf2-bhi3t):
  `← :sub-id` when `:rf.view/triggered-by` is present, `← props` on a re-render whose own subs all
  held value. A mount carries no cause — the `(mounted)` label conveys the first render. The parent
  is never named (rf2-8ve8z) — `props` is the attribution, not the specific parent component.

**Node + edge encoding (colour/edge first, per Visual encoding §022 — NOT glyphs):**

- **Changed / recomputed** node → filled tint + `changed` (mode accent) border + bold label;
  its outgoing edges are solid `changed` arrows that **propagate downstream**.
- **Unchanged / short-circuited** node → transparent fill + dashed `unchanged` (`dim`) outline +
  dim label; its edges are **dashed grey** and **visually cut** (downstream did not re-run).
- **View** node → `success`-tinted box labelled `(rerendered)`; it is the cascade's leaf and focus.
- **Shared subscription** → a sub with edges to **two or more views** is shared; the topology (two
  edges) carries the "one sub drives N views" fact (a small `×N` may annotate it).

**Cascade scope — flows are NOT in the reactive graph.** The graph is strictly **app-db → subs →
views**. Flows mutate state — they belong to the handling pipeline (the Epoch panel's FLOW step,
§9.1) — and they may **feed** the cascade by writing db-paths the subs watch, but they do not
appear as graph nodes. Quoted from the super-prompt (A.3):

> The View panel renders the reactive cascade (subs + views); the Epoch
> panel renders flows (alongside other handling steps).

The L2 row's `🌊 flow-recomputed` badge surfaces flows as a cross-epoch signal; per-epoch flow
detail lives in the Epoch panel's FLOW section (§9.1).

Below the graph, two list sections complete the panel (the prior SUB VALUES section retired in
rf2-uz3wm — the Epoch panel's SUBSCRIPTIONS table now carries per-cascade sub values + per-sub
diff chrome in cascade context, so the Reactive tab's value-listing is duplicate inventory):

- **UNMOUNTED VIEWS** — views whose component unmounted this epoch (one row each: view name +
  `unmounted` tag; a small `error`-tinted swatch as the row marker).
- **DESTROYED SUBSCRIPTIONS** — subs cleaned up when their last reader unmounted (one row each:
  sub-id + `no readers remaining` tag; a small `unchanged`/`dim`-tinted swatch). Caption below:
  "Subscriptions cleaned up when their last reader unmounted."

A legend closes the panel ("Views (right) are the focus — each: re-rendered + why (reactive vs
parent re-render)") with three swatches: `changed (propagates downstream)` · `no change
(short-circuits)` · `unmounted / destroyed`.

Dense case (focused epoch ripples through several subs into multiple views; one shared sub
fans out to two views; two Level-1 subs short-circuited):

```
┌─ VIEW · epoch #42 ───────────────────────────────── [◀ Prev] [Next ▶] ─┐
│▌ stripe: mode accent (one GitHub-blue accent, both modes)               │
│                                                                         │
│ REACTIVE FLOW                                            unchanged ┄┄    │
│ ┌─────────────────────────────────────────────────────────────────────┐ │
│ │            ╌╌╌▷ ::title-state  [no change]   (downstream cut)        │ │
│ │ ┌────────┐ ╌╌╌▷ ::settings     [no change]                          │ │
│ │ │ app-db │                                                          │ │
│ │ └───┬────┘ ───▶ ::counter ───▶ ::counter-parity ───▶ counter-view   │ │
│ │     │           [changed]        [changed]            (rerendered)   │ │
│ │     │                                          ┌────▶ header-view    │ │
│ │     └─────────▶ ::session ──────┤              │      (rerendered)   │ │
│ │                  [changed]       └─────────────┤ ×2 (shared)         │ │
│ │                                                └────▶ sidebar-view   │ │
│ │                                                       (rerendered)   │ │
│ │  changed = filled + accent border · no change = dashed dim outline   │ │
│ │  short-circuit = dashed-grey cut edge · shared sub = N edges (×N)    │ │
│ └─────────────────────────────────────────────────────────────────────┘ │
│                                                                         │
│ UNMOUNTED VIEWS                                                          │
│   ▪ modal-view                                              unmounted    │
│   ▪ tooltip-view                                            unmounted    │
│                                                                         │
│ DESTROYED SUBSCRIPTIONS                                                  │
│   ▪ ::modal-state                              no readers remaining      │
│   ▪ ::tooltip-position                         no readers remaining      │
│   Subscriptions cleaned up when their last reader unmounted             │
│                                                                         │
│ Legend  ■ changed (propagates)  ⬚ no change (short-circuits)            │
│         ▪ unmounted / destroyed                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

Sparse case (the epoch's db change touched no subscribed paths — common
for tool-frame internal events):

```
┌─ VIEW · epoch #87 ───────────────────────────────── [◀ Prev] [Next ▶] ─┐
│▌ stripe: mode accent                                                    │
│                                                                         │
│ REACTIVE FLOW                                                           │
│   No subs subscribed to changed paths · no views re-rendered.           │
│                                                                         │
│ This epoch produced no reactive cascade. State changed at the seed      │
│ path but nothing downstream was observing it.                           │
└─────────────────────────────────────────────────────────────────────────┘
```

> **Attribution detail (`caused-by ← sub ← path`).** The depth-first cause chain the prior
> indented tree carried per leaf — `CheckoutButton  caused-by ← :cart/can-submit? ← [:cart
> :state]` — survives as the **per-view causation tooltip / cross-panel chip** (§3.6), not as
> the panel's primary layout. The graph's edges ARE the causation; the chip spells it out for
> the click-to-App-db jump. (Whole-cascade `caused-by` attribution depends on the deref-subs
> sink — see §3.2 constraint 2; until it lands, the sub→view edge names only the triggering
> view of each recompute.)

### §3.3 Sub-layer placement (B.6 decision)

**Decision: (b) inside Reactive + (d) hover-over in App-db.**

- **In Reactive (b):** subs appear inline in the left → right reactive-flow graph (above)
  as the intermediate column between `app-db` and the view leaves, on the edge from app-db
  through the causing sub-chain to each view. Each re-rendered view carries its full
  causation chain in `caused-by ← sub ← path` form (as the cross-panel chip per §3.6).
- **In App-db (d):** hover any changed path → popover lists "subs
  depending on this path" (§4.4). No peer L4 Subs panel — keeps panel
  count stable; the sub layer is reached organically by drilling down
  from db-change or up from view-render.

If a future bead surfaces a strong case for a peer Subs panel (e.g. perf
profiler view), it lives behind a sub-tab inside Reactive — not a
ninth L4 tab. The L4 set is locked at 8.

### §3.4 Unchanged subs (B.10 sub-decision)

**Decision: collapsed disclosure by default, dim when expanded.**

- Default: footer line `[Show N unchanged subs ▾]` collapsed.
- Expanded: rendered inline within step 7 at 60% text opacity
  (`:text-tertiary` token).
- Operator can pin "always expand unchanged" via Settings → View → "Show
  unchanged subs in cascade" (default OFF).

Rationale: unchanged subs are coverage signal, not signal-of-the-moment.
Hiding by default keeps the dense view scannable; the toggle preserves
the "I want to see what DIDN'T fire" affordance.

### §3.5 Queries

| From | Reads |
|---|---|
| Focused epoch record | `:rf.sub/run`, `:rf.sub/skipped` (new — §11), `:rf.view/render` / `:rf.view/rendered` — read from the focused epoch record's `:trace-events` (rf2-rly4a — same `focus.epoch-id` scope as Trace, so Reactive + Trace stay correlated) |
| Registries | Sub metadata (input-paths, signal-fn), view metadata (file:line) |
| App-db | Seed-path resolution from the epoch's diff (§4) |

Recompute edges resolve from `:rf.sub/run`: **`:rf.sub/cause-sub`** is the sub→sub edge
(nil ⇒ Level-1, non-nil ⇒ Level-2) and **`:rf.sub/reader-render-key`** is the sub→view edge;
`:rf.sub/value-changed?` / `:rf.sub/prev-value` / `:rf.sub/value` drive the changed/unchanged
node state. The per-epoch aggregate `:rf.cascade/captured` (subs recomputed/skipped, flows
computed/skipped, views rendered) feeds the counts. The **UNMOUNTED VIEWS** + **DESTROYED
SUBSCRIPTIONS** sections read the view-unmount / sub-dispose ops from the same epoch slice.

**Constraints the graph layout is shaped around:**

1. **app-db → Level-1 is a plain fan-out, not path-wired.** re-frame subs read app-db
   imperatively; nothing records which paths a Level-1 sub reads (`sub-topology` reports
   `:input-kind :db` / `:inputs []` for Level-1; the trace records re-runs + value-changes,
   not paths). So `app-db` is one source node with an arrow to each Level-1 sub — no per-path
   edges. Only **flows** declare `:inputs` paths (precise app-db-path → flow), and flows are
   not in this graph. **Sub level partition keys off `:input-kind` (rf2-e3acps):** `:db` is
   Level-1; `:static` and `:parametric` are Level-2+. The STATIC topology draws `:<-` edges
   for `:static` subs (`:inputs` is the literal upstream query-vectors) but draws **no static
   edges** for `:parametric` subs — their realized edge set depends on the concrete outer
   query vector and is not statically enumerable (`sub-topology` reports `:inputs :parametric`).
   The REALIZED parametric edges surface only in the live/cascade view, sourced from the
   `:rf.sub/inputs` trace tag (the `(input-fn query-v)` result) and the live sub-cache's
   `:realized-inputs` slot. The graph must not fabricate un-materialized parametric edges.
2. **`:rf.view/rendered` carries no deref-subs list** (live: only render-key / id / frame /
   mount?). The sub→view link comes from `:rf.sub/reader-render-key`, which names only the
   **triggering** view of a recompute — not all readers. So BOTH the per-view
   "reactive-vs-parent" reason AND **shared-subscription detection** (which views read a
   given sub) need the deref-subs sink (spec'd, absent in the current build) or sub-cache
   reader introspection. Until it lands, the `×N (shared)` annotation + the precise
   "reactive vs parent re-render" tag render only for edges the triggering-view key resolves.
3. **`:rf.sub/skipped`** ("considered, didn't recompute") appears only when skips happen;
   `:rf.sub/value-changed? false` carries the changed/not story regardless, so the dashed
   `no change` node renders from the run-set alone.

### §3.6 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Sub row | Switch to **App-db**, scrolled + highlighted to that sub's input path |
| View row | Open-in-editor at view file:line |
| `caused-by ← sub ← path` chip | Each chip is clickable; "path" jumps to App-db panel at that path (cross-panel propagation per §10.5) |

### §3.7 Film-strip

Same `[◀ Prev] [Next ▶]` shape as Event. MVP chronological; stretch
filter "next epoch with view re-render" (skip the silent epochs).

---

## §4 The App-db panel (state bridge)

### §4.1 Question

> **"What does state LOOK LIKE — and what just changed?"**

App-db is the bridge between Event (writes) and Reactive (reads). It
anchors the cascade's seed paths.

**Density note** (per §0). The panel renders the complete app-db as **vertical sections, each
a cljs-devtools-style collapsible widget** (§10), sectioned by reserved `:rf/*` area. Each
section's value uses the shared lazy-tree renderer with App-db's depth heuristic
(depth-3-collapsed by default — see §10.4) so a 5-level-deep production db doesn't blow the
viewport. Diff is carried **inline as `← changed` annotations** on the changed nodes within
each section (not a separate zone). The hover popover (§4.4) is the canonical example of a
**hover affordance** in Xray: never replacing inline content, always augmenting.
Lines-per-screen target ~30-50 depending on db shape.

### §4.2 Layout (Figma design — rf2-ad7zx)

Reconciled to `tools/xray/design-reference/xray_devtools_reference.cljs` (the `app-db-panel`
component), the later iteration —
the **sectioned-by-reserved-area** model (the prior separate DIFF / STATE two-zone split is
superseded). The complete app-db renders as **vertical sections**, each headed by an uppercase
caption label and rendering its value as a collapsible widget; adjacent sections are separated
by a 1px hairline. Section order, top → bottom:

- **APP STATE** (TOP, always shown) — the app-db **minus** every reserved `:rf/*` key (the
  application's own state).
- **MACHINE `<id>`** — `:rf/machines` **fans out: one section per machine**, headed by the
  machine id (e.g. `MACHINE :title/flow`).
- **SPAWNED `<id>`** — `:rf/spawned` fans out the same way, one section per spawned instance.
- **ROUTE** — `:rf/route` is a **single section** (singleton — the current-route slice; NOT
  fanned out).
- **Other reserved singletons** — `SYSTEM-IDS` (`:rf/system-ids`), `PENDING-NAVIGATION`
  (`:rf/pending-navigation`), `ELISION` (`:rf/elision`) — one section each.

Every reserved area renders **even when absent/empty** (empty-state placeholder) so the
operator always sees the full reserved-key inventory. The mode-accent stripe sits at the
panel's left edge.

```
┌─ APP-DB · epoch #42 ────────────────────────────── [◀ Prev] [Next ▶] ─┐
│▌ stripe: mode accent (one GitHub-blue accent, both modes)             │
│                                                                       │
│ APP STATE                                                             │
│   ▾ {:counter 2, :user {:name "Alice" :role :admin}}                  │
│     · :counter  2             ← was 1                        │
│ ───────────────────────────────────────────────────────────────────  │
│ MACHINE :title/flow                                                   │
│   ▾ {:state :loaded, :context {:data [...]}}                          │
│ MACHINE :other/flow                                                   │
│   ▸ {:state :idle}                                                    │
│ ───────────────────────────────────────────────────────────────────  │
│ ROUTE                                                                 │
│   ▸ {:route-id :home, :params {}}                                     │
│ ───────────────────────────────────────────────────────────────────  │
│ SYSTEM-IDS                                                            │
│   ▸ #{:app :xray}                                                    │
│ PENDING-NAVIGATION   (empty this epoch)                               │
│ ELISION              (empty this epoch)                               │
│                                                                       │
│  Empty section state (reserved area absent):  shows the header + a    │
│  dim "(empty)" placeholder — the area renders even when unused.       │
└───────────────────────────────────────────────────────────────────────┘
```

> **Note (rf2-ad7zx):** Machines and Route also have their own dedicated L4 tabs (richer,
> specialized lenses). The app-db tab is deliberately the **raw, complete state** view — it
> shows everything, sectioned, including those reserved areas, with each value as a
> collapsible inspector widget.

### §4.3 The section renderer

Every section's value renders as a cljs-devtools-style collapsible inspector widget via the
shared lazy-tree + inline-diff + keyword-accent + clickable-paths renderer (§10) — App state,
each machine, each spawned instance, Route, and every reserved singleton alike. Diff is
annotation in place on the changed nodes within each section ("← was X"), with the
ancestor chain force-expanded so the operator never expands to find a change (§10.4).

**Three before-states per section (rf2-227cz).** The section model
(`current-state-sections`) tags each section / instance / singleton's
`:before` slot one of three ways, and `value-body` routes each to the
shared renderer accordingly:

| `:before` slot | Meaning | Renderer call |
|---|---|---|
| `no-diff` sentinel | No pre-image threaded (1-arity / cold boot, no focused epoch) | plain current-state — no `:before`, no `:added?` |
| real prior value | The slice existed in the focused epoch's `:db-before` | `:before` threaded → inline `← was X` diff in place |
| `added` sentinel | The slice is present in `:value` but **absent** in the focused epoch's `:db-before` — it came into existence this epoch | `:added? true` (the §10.0.13 first-run path) → whole subtree washes `:added` (green) |

The third row is the fix for rf2-227cz: previously an instance /
singleton absent in the focused epoch's pre-image was tagged `no-diff`
and rendered identically to an unchanged slice, so the one change that
should make each event visually distinct — a newly-created machine /
spawn / route appearing — carried no marker and the per-event diff was
near-invisible. An absent-in-`:before` slice now reads `:added` via the
same first-run `:added?` signal the SUBSCRIPTIONS step uses (§10.0.13).
The `added` sentinel is emitted ONLY in diff mode (a real pre-image is
present for the focused epoch); the cold-boot 1-arity path still tags
every slot `no-diff`. The TOP user-domain section needs no sentinel —
its `:before-top` is the whole prior user-domain map, so a NEW
user-domain key already classifies `:added` per-key inside the diff
engine.

### §4.4 Cascade overlay — downstream subs

Hover (or click) any changed path → popover lists subs and views
downstream of that path:

```
[:cart :state]              :idle → :submitting
   └─ Hover popover ──────────────────────────┐
      │ Subs depending on this path:           │
      │   :cart/state            (recomputed)  │
      │   :cart/can-submit?      (recomputed)  │
      │   :cart/eligibility      (skipped)     │
      │ Views rendered:                        │
      │   CheckoutButton  StateBanner          │
      │ ⤴ jump to Reactive panel               │
      └────────────────────────────────────────┘
```

Popover is a Xray-owned component (not a browser title), keyboard-
dismissable, click-through to Reactive panel via the `⤴` footer link.

### §4.5 Per-epoch delta, not cumulative (rf2-02j4r)

The panel shows the **selected epoch's OWN delta** — what THIS event
changed, compared to the immediately previous event, and nothing later.
The section model's two slots both come from the **focused epoch's
record**:

| Slot | Source | Meaning |
|---|---|---|
| `:value` | the focused epoch's **`:db-after`** | the post-state OF that event (its own result) |
| `:before` | the focused epoch's **`:db-before`** | the pre-state OF that event |

The inline diff is therefore exactly `db-before(N) → db-after(N)` —
epoch N's per-epoch delta, **independent of any later event**. Scrubbing
back to an earlier epoch N after later events occurred highlights ONLY
what N changed; a key added by a LATER event is absent from `db-after(N)`
and does not appear at N's selection.

> **rf2-02j4r reversal (2026-06-04).** This REVERSES the earlier rf2-yng0y
> design, which set `:value` = the observed frame's **LIVE app-db**
> ("constant as you scrub", a re-frame-10x current-state-inspector
> framing). Diffing the live db vs the focused `:db-before` equals the
> per-epoch delta ONLY when the focused epoch is HEAD (live == that
> epoch's `:db-after`); at any non-head epoch it became a CUMULATIVE
> diff — everything changed from the focused epoch forward to NOW — so
> later events' changes bled onto earlier selections (Mike, reproduced
> live on machine-epochs: focusing the `:media/deep` epoch wrongly lit
> `:media/shallow`, which a later event added). Pulling `:value` from the
> focused record's `:db-after` makes the diff the epoch's own delta at
> every scrub position, and STRENGTHENS the rf2-yng0y atomicity invariant
> (every slot from ONE record, never live-db + record-`:before`).
>
> The rf2-227cz `added`-sentinel behaviour (§4.3) is correct under the
> new baseline: a wholly-new instance at epoch N is absent in
> `db-before(N)` and present in `db-after(N)`, so it lights `:added`.

**At head (LIVE mode, no historical epoch focused)** the focused epoch
IS the head, so `:value` = head's `:db-after` = the current db. The
operator sees the most-recent epoch's state with its diff annotations —
same render shape, no second mode.

**Cold boot (no epoch focused at all)** — no cascade has settled, so
there is no focused record; `:value` falls back to the LIVE db and
`:before` is nil. The panel renders plain current-state with no diff
overlay.

### §4.6 Queries

| From | Reads |
|---|---|
| Trace bus | `:rf/epoch-record` `:db-before` + `:db-after` (existing); the focused epoch's `:db-after` is the panel's `:value` and `:db-before` is the diff pre-image (per-epoch delta · rf2-02j4r). Structural-sharing diff per §004 |
| Registries | Sub `:input-paths` for the "downstream subs" overlay |
| Reactive panel state | Re-rendered views set for the overlay popover |

### §4.7 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Changed-path row | Cross-panel propagation per §10.5: switches to **Reactive** and highlights subs + views downstream of that path. Same gesture as clicking any path segment in the renderer. |
| Hover overlay `⤴` | Switch to **Reactive**, scrolled to the listed views (same destination as the path-row click; the `⤴` is the explicit affordance label on the hover popover) |

### §4.8 Film-strip

`[◀ Prev] [Next ▶]` chronological. Stretch: "next epoch that changed
THIS path" — operator selects a path (sticky selection) then ▶ advances
to the next epoch that mutated it. Very high-value for state-evolution
tracing.

---

## §5 The Trace panel (per-epoch raw ops)

> **See also**: [`023-Trace-Panel.md`](023-Trace-Panel.md) — the dedicated Trace-panel redesign spec (the Figma-handoff target: the 4 phase bands, the full op-handling matrix, colour delegated to Figma). This §5 documents the v1-shipped layout; 023 is the direction-setting destination.

### §5.1 Question

> **"What raw trace events fired during this epoch?"**

Per-epoch raw trace ops ordered by emission time. The underlying stream
that Event + Reactive summarise. NOT aggregate across epochs (per §1.2).

**Density note** (per §0). Each op renders as a single mono row in **plain language**
(`relative-timestamp · op-family colour-band · readable description · duration`) — so a
30-op epoch reads as a 30-line scroll. The raw `:operation` + tag-map is **expandable detail**
via click, reusing the shared edn-inspector renderer's `browse` (cljs-devtools) variant (§10),
not the default line. Lines-per-screen target ~30-60.

### §5.2 Scope + layout (reworked — rf2-o6yqq + rf2-td380 + rf2-gkczt)

The Trace panel is **scoped to the focused epoch's `:trace-events`** —
the per-frame settling epoch record's raw trace slice, resolved via the
shared `panels.shared.focus-resolver` against `:rf.xray/focus` (its
`:epoch-id`) + `:rf.xray/epoch-history`, exactly as Issues / App-DB Diff
resolve theirs. This folds the COMPLETE domino trail for one event: both
the synchronous event-side rows (dispatch-id N) AND the async reactive
rows (`:rf.sub/run` / `:rf.view/render`, nil dispatch-id) that fire
post-cascade for that settling. (The prior shape scoped the global trace
bus by `:dispatch-id`, which DROPPED the async reactive rows — their
dispatch-id is nil — so the rendered trail was incomplete.) With
epoch-per-event (§1.1), one epoch = one event, so the focused epoch's
`:trace-events` IS the right scope.

**Removed (rf2-o6yqq + rf2-gkczt):**
- the **header row** with the duplicate `[◀ Prev] [Next ▶]` film-strip,
  the `X / Y in view` counts, and the `epoch #N · X ops` indicator — the
  L2 events list already owns spine focus navigation, and the L4 tab
  strip is the panel-name source-of-truth;
- **ALL filtering UI** — the `[op-type ▾] [tag ▾]` chip-filter rows, the
  per-row chip affordances, the clear-filters control. The focused epoch
  IS the scope; the per-row payload-expand affordance is the drill-down.
  (This makes the Trace panel the one L4 panel with NO film-strip header —
  the others retain it; see §2.5.)

**Readable rows (Figma design — rf2-ad7zx).** Reconciled to
`tools/xray/design-reference/xray_devtools_reference.cljs` (the `trace-panel` component), the
later iteration. Each op renders
as a **plain-language line**, not its raw op-type:
`dispatched [:counter-inc]` · `db changed [:counter] 1 → 2` · `fx :dispatch → [:title/flow …]`
· `machine :title/flow idle → loading`. Each row carries:

1. a **relative timestamp** (`t+0.0ms`) at the left,
2. a **3px coloured left-border banded by op-family** (dispatch = mode accent · db = `changed`
   · fx = `warning` · reactive = `dim` · machine = chart/machine tone), so the epoch's shape
   scans at a glance,
3. the readable description (mono), and
4. a **per-op duration** at the right (event-emit / `do-fx` rows carry elapsed timing; reactive
   point-in-time emits show a timestamp, not a bar).

The reactive aftermath (the many `:rf.sub/run` / `:rf.view/render`) **collapses under one
expandable group** — `▸ reactive aftermath (N subs, M renders)` — so the core dominoes stand
out (collapsible groups, not filter chrome). **Causal nesting:** child dispatches indent under
their parent (the cascade tree) so structure is visible even in the raw view. Clicking any row
expands its raw `:operation` + tag-map inline (no nav).

```
┌──────────────────────────────────────────────────────────────────────┐
│ ▔▔▔▔▔▔▔▔▔▔▔▔▔  3px cascade-status bar (lifecycle colour)  ▔▔▔▔▔▔▔▔▔▔▔ │
│ ▌ t+0.0ms  dispatched [:counter-inc]                          0.4 ms  │  ← accent band
│ ▌ t+0.1ms  db changed [:counter] 1 → 2                                 │  ← changed band
│ ▌ t+0.2ms  fx :dispatch → [:title/flow [:rf/init]]                     │  ← warning band
│ ▌ t+0.3ms  ▸ reactive aftermath (2 subs, 1 render)                     │  ← dim · collapsible
│ ▌ t+0.5ms  machine :title/flow idle → loading                         │  ← machine band
│      ▌ t+0.6ms  └─ dispatched [:title/loaded]   (child cascade)        │  ← indented
│  colour-banded by op-family · click a row → expand raw :operation + tags│
└──────────────────────────────────────────────────────────────────────┘
```

A **3px cascade-status timeline bar** (rf2-b76v4) above the ribbon fills
with the focused cascade's lifecycle-status colour (settled-success /
errored / stale / paused / in-flight), driven by the same
`event-status-colour` fn the L2 rows + Epoch panel header consume — one
lifecycle vocabulary across the whole devtool. Expanded payload uses the
edn-inspector renderer's `browse` variant (§10).

**Empty states** (focus-resolver statuses + the empty-epoch case):
`:no-events` ("No events.") · `:no-focus` ("No focused event.",
defensive) · `:epoch-evicted` ("This epoch has been evicted from the
history buffer.").

### §5.3 Queries

| Sub | Reads |
|---|---|
| `:rf.xray/trace-feed` | The focused epoch record's `:trace-events`, resolved via `:rf.xray/focus` (`:epoch-id`) + `:rf.xray/epoch-history`. No filtering. Returns `{:rows :nodes :total :rendered :epoch-id :empty-kind}` (`:rendered` = `:total` since there is no filter). `:rows` is the flat oldest-first projection (each row carries `:op-family` · `:rel-time` · `:duration-ms` · `:parent-dispatch-id`). `:nodes` is the structural display tree the view paints: contiguous reactive emits fold into one `:reactive-group` node (`{:summary {:subs N :renders M} :children [...]}`), and every node carries a causal-nesting `:depth` (rf2-ad7zx.8). |
| `:rf.xray/trace-expanded-row-ids` | The set of trace `:id`s whose payload is expanded inline (per-row click). |
| `:rf.xray/trace-expanded-group-ids` | The set of reactive-aftermath group ids that are expanded (group click — rf2-ad7zx.8). |

### §5.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Row → expand payload | Inline in panel (no nav — toggles membership in `:rf.xray/trace-expanded-row-ids`) |
| Reactive-aftermath group → expand | Inline in panel (no nav — toggles membership in `:rf.xray/trace-expanded-group-ids`, revealing the collapsed `:rf.sub/run` / `:rf.view/render` children; rf2-ad7zx.8) |
| Source-coord chip | Opens the source coord in the editor (`:rf.xray/open-in-editor`) |
| Right-click a destroy-event row / `⟲ cascade` button | Opens the cancellation-cascade popover for that row's dispatch-id |

(There is no op-type chip-filter row — filtering was removed, rf2-gkczt.)

### §5.5 No film-strip (rf2-o6yqq)

The Trace panel has **no film-strip header** — alone among the L4 panels.
Epoch navigation is owned by the L2 events list / events-ribbon nav
(`◀ ▶ ⏭`); the panel re-scopes whenever spine focus moves.

---

## §6 The Machines panel (topology + overlay)

### §6.1 Question

> **"What did this event do to my machines?"**

Topology-plus-overlay: full machine topology base, focused-epoch effect
overlaid.

**Density note** (per §0). Per-machine canvases are sized to fit their
own topology — small machines render compact (~120-180px tall); large
nested machines auto-fit to the available viewport via xyflow's
`fitView`. Multiple machines stack vertically (no horizontal split) so
the operator scans them like cards on a workstation. Guards, actions,
and cancellation cascade chips render below each canvas as dense text
rows — no extra modal or popout. (rf2-48fwsi retired the vestigial
Canvas/List view-mode toggle that once advertised a flat textual
fallback — it was dead after the rf2-g2axio events-as-nodes redesign,
with no view branching on the persisted mode. The xyflow canvas is the
sole render path; a chartless guards/actions-only list would be a
separate new feature.)

### §6.0 Implementation — xyflow path (B) LOCKED

Per B.4.1 of the polished super-prompt, the Machines panel's render
engine is **locked to path (B): xyflow + custom Xray-palette styling.**
Path (A) (embed Stately's `@statelyai/inspect`) is rejected for bundle
weight + loss of palette control; path (C) (native Reagent) is rejected
for the work cost of rebuilding auto-layout / zoom-pan-fit from scratch.

| Decision | Value |
|---|---|
| Library | **xyflow** (the new name for react-flow). https://reactflow.dev/ |
| Visual reference | **Stately's visualizer playground** — https://stately.ai/viz (we recreate this look in Xray's palette; we do NOT import Stately UI) |
| State-machine model reference | xstate — https://github.com/statelyai/xstate (model only; re-frame2's machine vocab stays its own per rf2-5r4q2) |
| License | MIT |
| Bundle cost | ~50-80KB gzipped depending on which xyflow submodules are imported |
| Mount mechanic | xyflow is a React component; Xray is Reagent. Use Reagent's React-component interop (`reagent/adapt-react-class` or `[:>` syntax) to mount xyflow inside the Xray Machines-panel Reagent component. |
| Adapter layer | The live render path is the shared machines-viz `MachineChart` (`day8.re-frame2-machines-viz.chart`), mounted via the Xray-side `panels/machine_canvas.cljs` wrapper (rf2-gpzb4 — 2026-05-21 the SVG renderer gave way to xyflow). The original design's standalone `tools/xray/src/.../machines/xyflow_adapter.cljs` ns was never created; a self-contained, JVM-portable pure projector + palette catalogue survive off the live path as a unit-tested fallback at `panels/machines/topology.cljs` (`project`) + `panels/machines/xyflow_style.cljs`. |

**Visual conventions to recreate (Stately reference, Xray palette):**

| Convention | xyflow implementation |
|---|---|
| Nested state containment | xyflow's group/parent-node mechanic. Parent state renders as a containing rect; child states are nested xyflow nodes whose `parentNode` references the parent. |
| Transition edge animation | xyflow's `animated: true` edge prop. Color via Xray palette (`:accent` — the single GitHub-blue accent — for "fired this epoch"; `:text-tertiary` for "registered but not fired this epoch"). |
| Current-state highlight pulse | Custom node CSS class that applies the `pulse` keyframe (~1.2s ease-in-out; CSS-variable interpolated through `--rf-xray-motion-scale` so `prefers-reduced-motion` collapses it). Pulse outline color = `:green` (the panel-domain accent). |
| Auto-layout | **elkjs** (the Eclipse Layout Kernel, `Layered` algorithm) runs as xyflow's layout backend inside `MachineChart` (2026-05-19 ELK lock — superseded the originally-sketched dagre `getLayoutedElements`). Async one-shot layout, cached per machine-id; recomputed only when topology changes. |
| Zoom + pan + fit | xyflow's built-in `<Controls>` component ONLY (mounted `{:showZoom true :showFitView true :showInteractive false}`; xyflow positions it bottom-left). The cluster ships **zoom-in `+` / zoom-out `−` / fit-view `⛶`** buttons — no `NN%` zoom-readout chip, no `Reset` button, no custom Xray toolbar. Default framing: fit-on-mount via `:fitViewOptions {:padding 0.1}`; Xray re-frames on panel-entry by bumping the orthogonal `:fit-signal` nonce (rf2-6tw7t). See spec/007 §"Controls" for the authoritative reconciliation. |
| Label-on-edge transitions | xyflow's `label` prop on edges; rendered inline on the edge, not in a side legend. Font: JetBrains Mono 10px (`:micro` size). |
| Parallel-state side-by-side | Parallel-region containers render as sibling group-nodes with a dashed border (`:border-default` at `dash-array: 4 4`). Inner states laid out independently per region. |
| Final states | Thick border ring (2px solid `:green` outer + 1px solid `:bg-2` inner gap, recreating Stately's double-ring convention). |

**Xray palette token mapping into xyflow style props** (per
rf2-z7ms8 — the operator must immediately recognise this as a Xray
panel, not a generic xyflow diagram):

```clojure
;; Sketch — applied via xyflow nodes' :style and edges' :style props.
{:state-node {:background (:bg-2 tokens)            ; "#1B1E24"
              :border     (str "1px solid " (:border-default tokens))
              :color      (:text-primary tokens)    ; "#E8EAF0"
              :font-family mono-stack
              :font-size  (:body-tight type-scale)}
 :state-node-current {:border (str "2px solid " (:green tokens))
                      :animation "rf-xray-machine-pulse 1.2s ease-in-out infinite"}
 :state-node-final   {:border (str "2px solid " (:green tokens))
                      :box-shadow (str "inset 0 0 0 1px " (:bg-2 tokens))}
 :region-container   {:background "transparent"
                      :border (str "1px dashed " (:border-default tokens))}
 :edge-registered    {:stroke (:text-tertiary tokens) :stroke-width 1}
 :edge-fired-this-epoch {:stroke (:accent tokens) :stroke-width 2  ; the single GitHub-blue accent
                         :animated true}
 :edge-label         {:fill (:text-secondary tokens)
                      :font-family mono-stack
                      :font-size (:micro type-scale)}}
```

The integration scope is **read-only render**: re-frame2's machine spec
is the source of truth; the xyflow JSON is a view-only projection
recomputed when topology or focused-epoch changes. xyflow's interactive
editing affordances (drag-to-create-edge, etc.) are disabled.

### §6.2 Layout

Three cases (per existing §003 + the refined topology-plus-overlay rule).
**Each case renders inside an xyflow canvas as described in §6.0** — the
ASCII below is what the operator sees once xyflow has laid out + styled
the nodes and edges with Xray palette tokens.

**Case A — no machines registered:**
```
┌─ MACHINES ─ no machines registered ───────────────────────────────────┐
│ This frame has no state machines.                                     │
└───────────────────────────────────────────────────────────────────────┘
```

**Case B — machines registered, focused epoch had no transition:**

```
┌─ MACHINES · epoch #87 ──────────────────────────── [◀ Prev] [Next ▶] ─┐
│▌ stripe: mode accent (one GitHub-blue accent, both modes)             │
│                                                                       │
│ machine :title/flow            (no activity this epoch · current ●)   │
│ ┌────────────────────────────────────────────────────────────────────┐│
│ │  ( idle ) ──→ (( loaded )) ──→ ( error )                            │
│ │                  ↑ current                                          │
│ │ ┌──────┐                                                            │
│ │ │ + − ⛶ │ ← xyflow <Controls> (bottom-left)                         │
│ │ └──────┘                                                            │
│ └────────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ machine :other/flow            (no activity this epoch · current ●)   │
│ ┌────────────────────────────────────────────────────────────────────┐ │
│ │  ( empty )  (( populated ))  ( submitting )  ( settled )           │ │
│ └────────────────────────────────────────────────────────────────────┘│
└───────────────────────────────────────────────────────────────────────┘
```

Topology stays visible — only the overlay (highlight on the transition
edge, `:after`-rings, action/guard labels) is absent.

**Case C — focused epoch triggered ≥1 transitions (Figma design — rf2-ad7zx).**

Reconciled to `tools/xray/design-reference/xray_devtools_reference.cljs` (the `machine-panel`
component), the later iteration —
the FROM/TO states are circle nodes inside a dashed **compound-state container** (`active
(compound)`); the FROM is dashed/dim, the TO is a **double-circle active node** in the mode
accent; the fired transition edge carries its **event label + guard + action inline**
(`[:rf/init]` · `guard: token? [pass]` · `do: fetch!`); the error state is `error`-toned; and a
**details row** below restates `guards · actions · after`:

```
┌─ MACHINES · epoch #42 (machine :title/flow [:rf/init]) ─ [◀ Prev] [Next ▶] ─┐
│▌ stripe: mode accent (one GitHub-blue accent, both modes)                   │
│ machine :title/flow                                                          │
│ ┌──────────────────────────────────────────────────────────────────────────┐│
│ │ ┌─ active (compound) ──────────────────────────────────┐                  ││
│ │ │  ( idle ) ════════▶ (( loading )) ───→ ( loaded )     │      ( error )   ││
│ │ │   FROM    [:rf/init]    TO/current                    │       ↑          ││
│ │ │           guard: token? [pass]                        │   loading→error  ││
│ │ │           do: fetch!                                  │                  ││
│ │ └───────────────────────────────────────────────────────┘                 ││
│ │  FROM = dashed/dim · TO = double-circle, mode-accent, pulse                 ││
│ │  fired edge = mode-accent 2px animated · registered = dim 1px · error = red ││
│ │ ┌──────┐                                                                    ││
│ │ │ + − ⛶ │ ← xyflow <Controls> (bottom-left)                                 ││
│ │ └──────┘                                                                    ││
│ └────────────────────────────────────────────────────────────────────────────┘│
│ guards: token? [pass]    actions: [fetch!]    after: ◴ 5s → :timeout         │
│ Cancellation cascade (none)                                                  │
└──────────────────────────────────────────────────────────────────────────────┘
```

Per §003, the interactive chart adapter (zoom / pan / fit) wraps each
per-machine canvas. **The xyflow surface described in §6.0 is the sole
render path** — rf2-48fwsi retired the vestigial Canvas/List view-mode
toggle (dead after rf2-g2axio; no view branched on the persisted mode).
A chartless guards/actions-only "List view" would be a separate new
feature, not a revert. The mode-accent fired-edge / double-circle
active-node / dashed compound container / inline guard+action labels +
the `after: ◴ Ns → :event` countdown ring are the visual elements the
Figma design fixes; xyflow renders them with the §6.0 palette mapping.

Layout direction: **top-to-bottom by default** (elk's `elk.direction
DOWN`, the `MachineChart` default Xray does not override). The original
sketch called for a left-to-right `rankdir: 'LR'` default; the elkjs
backend that shipped (`MachineChart`, 2026-05-19 ELK lock) maps `:lr` →
elk `RIGHT` / `:tb` → elk `DOWN` and defaults to `DOWN`. Operator-facing
direction flip (Settings → View → Machines layout direction) is deferred
to a follow-on bead. Default framing: fit-on-mount via xyflow's
`:fitViewOptions {:padding 0.1}` (a fractional viewport padding, not a
fixed pixel inset); Xray re-frames on panel-entry by bumping the
`:fit-signal` nonce (rf2-6tw7t).

### §6.3 Queries

| From | Reads |
|---|---|
| Focused epoch record | `:rf.machine/transition`, `:rf.machine.after/scheduled`, `:rf.machine.after/fired`, `:rf.machine/cancellation` — read from the focused epoch's `:trace-events` (correlated via `focus.epoch-id`; the cascade-wide tag is `:rf.trace/dispatch-id`) |
| Registries | Machine topology (`reg-machine`), guard / action metadata |
| Per-frame state | Current machine state (for the "current ●" annotation in case B) |

### §6.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Transition edge | (no-op MVP; stretch: scroll to the dispatching event in the Epoch panel) |
| Guard row | Inline source-glance (DEBUG-gated source string) |
| Action chip | Switch to **Epoch** panel, scroll to the FX step's row for that action |
| Canvas node | Set this state as the "selected" for filter-IN candidate |

### §6.5 Film-strip

`[◀ Prev] [Next ▶]` MVP chronological. **Stretch (high-value)**: "next
epoch that touched THIS machine" — already shipped per §003 (rf2-y9xmf).
Keep the per-machine filter as the default; chronological is the fallback
when no machine is highlighted.

---

## §7 The Routing panel (topology + overlay)

### §7.1 Question

> **"What did this event do to my routes?"**

Same topology-plus-overlay pattern as Machines.

**Density note** (per §0). Most route trees are shallow (≤ 4 levels) so the route table renders
inline as an indented tree with expand chevrons — no canvas needed. The three sections
(`Current route` · `Navigation this epoch` · `Route table`) are dense KV/row blocks, scannable,
no expand-to-see. Routing CAN escalate to xyflow if a future bead surfaces a route tree large
enough to demand auto-layout; until then, the textual tree is denser AND simpler. Lines-per-
screen target ~16-30.

### §7.2 Layout (Figma design — rf2-ad7zx)

Reconciled to `tools/xray/design-reference/xray_devtools_reference.cljs` (the `routes-panel`
component), the later iteration —
**three stacked sections** (the prior "Active route tree first + This-epoch KV" shape is
superseded). Section order, top → bottom, each separated by a 1px hairline:

1. **CURRENT ROUTE** (always shown) — the active route **id** (mode-accent, bold), its
   **params**, and the **matched path / URL**. The "where am I."
2. **NAVIGATION THIS EPOCH** (event-driven lens) — when the focused event navigated:
   **FROM route ──► TO route**, the **params**, and the **outcome** (*transitioned* ·
   *blocked* (+ reason) · *cancelled* · *not-found*; outcome coloured by result). Quiet/absent
   when the focused event isn't a navigation.
3. **ROUTE TABLE** — all **registered routes** (id → path pattern), drawn as a **tree when
   nested** (expand chevrons; else a flat list), with the **current route highlighted**
   (mode-accent row + `◀ current` marker) and the focused navigation's FROM→TO marked on it.
   Click a route → its definition / source coord. **Every registered route appears exactly
   once**, even when its `:parent` metadata is malformed: an orphan parent (points at an
   unregistered id) renders at depth 0, and a rootless cycle (a self-cycle, or a closed
   `A↔B` where every member's parent is registered) is surfaced at depth 0 with a `↻ cycle`
   badge rather than being silently dropped (rf2-m9rx6) — diagnosing malformed parent
   metadata is exactly what the topology view is for. The projection
   (`routing_helpers/project-topology`) terminates on any cycle via a global visited set.

```
┌─ ROUTING · epoch #38 ──────────────────────────── [◀ Prev] [Next ▶] ─┐
│▌ stripe: mode accent (one GitHub-blue accent, both modes)            │
│                                                                      │
│ CURRENT ROUTE                                                        │
│   :user/profile    params {:id 42}    /users/42                      │
│ ──────────────────────────────────────────────────────────────────  │
│ NAVIGATION THIS EPOCH        (event-driven · quiet when not a nav)   │
│   :dashboard ──► :user/profile   params {:id 42}  outcome: transitioned│
│ ──────────────────────────────────────────────────────────────────  │
│ ROUTE TABLE    (registered · current highlighted · tree when nested) │
│   :home               /                                              │
│   :dashboard          /dashboard                                     │
│   ▾ :users            /users                                         │
│       :user/profile   /users/:id            ◀ current                │
│   :settings           /settings                                      │
│                                                                      │
│ Empty (no route activity this epoch):  CURRENT ROUTE + ROUTE TABLE   │
│   still render; NAVIGATION THIS EPOCH reads "No route activity in    │
│   this epoch."                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

### §7.3 Queries

| From | Reads |
|---|---|
| Focused epoch record | `:rf.route/can-leave`, `:rf.route/can-enter`, `:rf.route/on-match`, `:rf.route/fragment-changed` — read from the focused epoch's `:trace-events` (correlated via `focus.epoch-id`; cascade-wide tag `:rf.trace/dispatch-id`) |
| Registries | Route tree (`reg-route`) |
| Per-frame state | Current active route + phase (for empty-state) |

### §7.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Route-table row | Open the route's definition / source coord in the editor (`:rf.xray/open-in-editor`); doubles as the filter-IN "selected route" candidate |
| Current-route id | (no-op MVP; stretch: filter-IN on the active route) |
| FROM / TO chip in NAVIGATION | Marks the corresponding nodes in the route table |

### §7.5 Film-strip

MVP chronological; stretch "next route activity" (skip silent epochs).

---

## §8 The Issues panel (per-epoch issues)

### §8.1 Question

> **"What's wrong in this epoch?"**

Per-epoch errors, warnings, schema violations, a11y violations.

**Density note** (per §0). Issues are rare per epoch but high-signal — each renders as a
**single row** (`severity · category · short description · timestamp · ↗source`) with a 3px
severity-coloured left border. No expand-to-see — the row reads inline so the operator sees the
punch at a glance. The `↗` opens the responsible handler at `file:line`; the row itself pivots
to the Epoch panel. Empty state is a single calm line. Lines-per-screen target ~6-24.

### §8.2 Layout (Figma design — rf2-ad7zx)

Reconciled to `tools/xray/design-reference/xray_devtools_reference.cljs` (the `issues-panel`
component), the later iteration —
**one row per issue** (the prior multi-row KV block per issue is superseded). Each row carries a
**3px left border coloured by severity** + an uppercase **severity badge** (in its severity
colour) + the **category** (muted) + the **short description** (primary) + the **timestamp**
(mono, muted) + an **↗source** affordance. Severity is **three tiers** (per
[022-Design-Tokens](022-Design-Tokens.md) — strongest first):

- **error** — handler/sub/fx exception, no-such-sub, flow-eval, schema violation, etc. → `error`
  (red), strongest.
- **warning** — plain-fn-under-non-default-frame, missing-doc, etc. → `warning` (amber).
- **advisory** — fx skipped-on-platform, SSR hydration mismatch, cofx skipped, etc. → `advisory`
  (cool blue; calm, ≠ warning).

Silent-by-default — a clean epoch shows a calm positive state, not an error look.

```
┌─ ISSUES · epoch #42 ────────────────────────────── [◀ Prev] [Next ▶] ─┐
│▌ stripe: mode accent (one GitHub-blue accent, both modes)             │
│                                                                       │
│ ▌ ERROR     handler-exception   :counter-inc threw …       12:30:05 ↗ │  ← red border
│ ▌ WARNING   missing-doc         sub ::counter has no :doc   12:30:05 ↗ │  ← amber border
│ ▌ ADVISORY  fx-skipped          :rf.nav/scroll skipped (node) 12:30:05 ↗│  ← advisory border
│ ─────────────────────────────────────────────────────────────────────│
│  click a row → Epoch panel (the cascade) · click ↗ → source file:line │
│                                                                       │
│  clean epoch →  "No issues in this epoch."                            │
└───────────────────────────────────────────────────────────────────────┘
```

### §8.3 Queries

| From | Reads | Severity |
|---|---|---|
| Focused epoch record | `:rf.error/*`, `:rf.schema/violation`, `:rf.a11y/violation` — read from the focused epoch's `:trace-events` (correlated via `focus.epoch-id`; cascade-wide tag `:rf.trace/dispatch-id`) | **error** |
| Focused epoch record | `:rf.warning/*` (plain-fn-under-non-default-frame, missing-doc, …) | **warning** |
| Focused epoch record | advisories — `:rf.fx/skipped-on-platform`, `:rf.ssr/*` (hydration mismatch), `:rf.cofx/*` (cofx skipped) | **advisory** |

Each error carries the responsible handler's source coord (`:rf.trace/trigger-handler`) for
jump-to-source, and rides the cascade's `:dispatch-id` so the row → Epoch pivot works.

The **short description** lifts a terse per-category detail from the trace event's `:tags`
(`issues-ribbon-helpers/short-description`, priority order): `:reason` → `:exception-message`
→ `:rf.event/v` → `:unresolved-input` → `:failing-id` → `:path` → bare-op fallback. The
`:unresolved-input` slot is the `:rf.error/no-such-sub` row's failing query-vector — re-synced
(rf2-qn9ss) from the legacy `:rf.sub/query-v` to spec/009's
`{:rf.sub/id :unresolved-input :resolved-inputs}` catalogue shape after agpv2.3 (#3107) re-shaped
the `re-frame.subs` emit; the legacy slot is no longer read.

### §8.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Issue row | Selects the parent dispatch and **pivots to the Epoch panel** (the full cascade that produced the issue) |
| `↗` source coord | Open the responsible handler at `file:line` (`:rf.xray/open-in-editor`) |

### §8.5 Film-strip

MVP chronological. **High-value stretch: "next epoch with ⚠ badge"** —
operator stepping through bug repro lands on issue-bearing epochs only.

---

## §9 The Chrome A11y panel — removed (rf2-4v67l)

The Xray Chrome A11y dogfood panel was removed. A11y dogfooding is
properly Story's domain, where it already ships:

- `re-frame.story.ui.chrome-a11y` (rf2-18t6p · `tools/story/src/re_frame/
  story/ui/chrome_a11y.cljs`) — axe-core scoped to the Story chrome.
- `re-frame.story.ui.a11y` (rf2-qgms1 · `tools/story/src/re_frame/story/
  ui/a11y.cljs`) — axe-core scoped to VARIANT trees.

A duplicate Xray-side panel was noise that flagged the Xray
events-list as a problem; the canonical "one source of truth" rule
keeps the dogfood in Story.

---

## §9.1 The Epoch panel (numbered cascade · rf2-sc3r1)

### §9.1.1 Question

> **"What happened in this epoch?"**

The Epoch panel renders the focused epoch's complete computational
timeline as a numbered vertical cascade — dispatch → coeffects →
handler → flow → fx → subscriptions → views. It is the canonical
"one timeline, one event" surface; every L4 panel answers a slice of
the same question, but the Epoch panel renders the WHOLE chain in
fire order.

### §9.1.2 Supersedes the retired Event/Handler panel (rf2-5gl5r)

Per rf2-sc3r1's pre-alpha posture the Epoch panel initially co-existed
with the Event/Handler panel (§2). rf2-5gl5r retired the older panel
once the Epoch panel reached parity: `panels/event_detail.cljs` is
deleted, the `:event` L4 tab registration is gone, and the Epoch tab
moves to `:order -1` (leftmost) so it claims the same default-landing
position the retired tab held. The retired §2 design is kept as
historical reference at the top of this doc; it is NOT normative.

The Epoch panel renders the full timeline as a delightful numbered
cascade including the reactive trailing edge (SUBSCRIPTIONS + VIEWS)
that the retired Event panel routed to its own Reactive tab (§3).

Tab placement: `:epoch`, mnemonic `e`, order -1 (leftmost — claims the
position the retired Event/Handler tab held). Registered against
`:dynamic` mode only.

### §9.1.3 Conditional cascade — only-the-steps-that-fired

The cascade is a **faithful projection of the trace stream**. A step
is rendered iff its driving trace events surfaced in this epoch:

| Step | Driving trace | Conditional |
|------|---------------|-------------|
| **DISPATCH** | `:rf.event/dispatched` | always (every epoch starts here) |
| **COEFFECT** | `:rf.cofx/run` (or `:rf.event/run-end` `:rf.event/coeffects` fallback) | **one COEFFECT step per user-injected coeffect** (rf2-s1jw4 · Mike pair-debug 2026-05-26, commit `ee9def224`). SYSTEM defaults `:db / :event / :frame / :source / :trace-id` are filtered at projection time (rf2-cq0ch). A cascade with 3 user cofx injections renders 3 numbered COEFFECT entries, not 1 entry containing 3 rows. A coeffect that THREW on injection (`:rf.error/coeffect-exception`) but produced no `:rf.cofx/run` gets a synthesised placeholder COEFFECT step (rf2-yz57h) so the exception card has a home. |
| **INTERCEPTORS** | registry read — `(handler-meta :event event-id)` `:interceptors` (rf2-se9a9t) + per-dispatch `:rf.interceptor/override-summary` on `:rf.event/run-start` (rf2-9vx0jk) | **only when the event carries authored (non-`:rf/default?`) interceptor refs.** The authored chain wraps the handler, so the step renders BEFORE HANDLER. One row per authored ref: id (click-to-source) + the resolved descriptor's hook shape (`before` / `after` / `before/after` / `factory`) + a `ref` / `inline` badge + the factory `:arg` + a `missing` chip for an unregistered ref. This is the clean-chain surfacing (EP-0022 §11 (a)+(b)); the authored + resolved chain is read from the REGISTRY, not the trace (a clean chain emits no per-interceptor trace). **Override delta (§11 (c) · rf2-9vx0jk):** when the cascade's `:rf.event/run-start` trace carries `:rf.interceptor/override-summary` (id/count-only — present only when this dispatch's merged per-frame ++ per-call `:interceptor-overrides` acted), the panel PREFERS that fact to stamp a `replaced` / `removed` badge on each affected row — the per-dispatch substitution the registry read alone cannot show. Purely informational — does NOT inflate the epoch outcome. |
| **INTERCEPTOR** | `:rf.error/interceptor-exception` (rf2-mszrz) | **only when a user interceptor threw** (rf2-yz57h). The substrate emits no per-interceptor "ran" trace (the chain runs as one unit), so a clean chain leaves nothing to show — the step is exception-only (the clean chain is the INTERCEPTORS step above). PHASE-SPLIT (rf2-vew2n): a `:before` throw renders its step BEFORE HANDLER, an `:after` throw AFTER HANDLER (execution order: COEFFECTS → :before → HANDLER → :after). One row per throwing interceptor: id + `:before`/`:after` phase chip + the shared exception card; the badge carries no "N threw" summary verb (rf2-oqi0c). |
| **HANDLER** | every epoch settles through a handler | always (but rendered as **SKIPPED** — rf2-yz57h — when an upstream `:before`-chain throw aborted the cascade before the handler ran) |
| **FLOW** | `:rf.flow/recomputed` | only when flows fired |
| **FX** | `:rf.fx/handled` / `:rf.fx/override-applied` / `:rf.fx/skipped-on-platform` | only when fx-handlers fired (rendered as **SKIPPED** when an upstream `:before`-chain throw aborted the cascade) |
| **SUBSCRIPTIONS** | `:rf.sub/run` / `:rf.sub/skip` | only when subs recomputed |
| **VIEWS** | `:rf.view/render` | only when views re-rendered |

Steps are numbered DYNAMICALLY 1..N — an absent OPTIONAL step
consumes no number; absence is conveyed by OMISSION, not an
empty-state line. This matches the §2.2 dynamic-numbering contract
from the Event lens — both panels share the same "absence is
silence" rhythm.

### §9.1.4 Badge taxonomy (the badge inventory)

Each step renders a uppercase badge pill at its numbered circle:

| Badge | Token | Hue family |
|-------|-------|------------|
| `:DISPATCH`           | `:text-tertiary` | muted grey |
| `:RECORDABLE-COFX`    | `:text-secondary` | muted (a step lighter than DISPATCH — rf2-9fyn40; reads as orienting causal-context metadata, EP-0010 provenance / EP-0017 §9 recordable coeffects, not a pipeline action) |
| `:COEFFECT`           | `:magenta`       | purple |
| `:INTERCEPTORS`       | `:accent`        | blue (rf2-se9a9t — the authored chain WRAPS the handler; same identity family as INTERCEPTOR + HANDLER) |
| `:INTERCEPTOR`        | `:accent`        | blue (rf2-yz57h — the chain WRAPS the handler; one identity family) |
| `:HANDLER`            | `:accent`        | blue (single Xray accent) |
| `:FLOW`               | `:accent`        | blue (paired with HANDLER) |
| `:SIDE-EFFECTS`       | `:orange`        | functional amber (post-commit irreversible; the pre-rf2-kt6js `:FX` step) |
| `:SUBSCRIPTIONS`      | `:magenta-pink`  | pink (rf2-cgm4f split from COEFFECT violet) |
| `:VIEWS`              | `:success`       | green |
| `:SCHEMA-HOT-RELOAD`  | `:warning`       | warning amber (rf2-17vxj · renamed rf2-xgeag for the narrowed hot-reload-only scope) |

> **Retired 2026-05-26 (pair-debug, rf2-zkiu5):** the prior `:CHILD-DISPATCHES`
> + `:APP-DB-DIFF` rows were dropped. CHILD DISPATCHES (originally rf2-yx1ae,
> Mike commit `eccb6db1b`) is redundant with the FX step, which already
> surfaces every `:dispatch` / `:dispatch-n` / `:dispatch-later` fx entry
> per row. APP-DB DIFF (originally rf2-rrykz, dropped earlier same session
> in commit `ee9def224`) is redundant with the HANDLER step's `:db`
> sub-section (§9.1.5.1, FULL+DIFF single rendering post-rf2-vv3m6),
> which surfaces the same data in-context. The projection's `badge-
> set` enforces the inventory (with rf2-yz57h's conditional
> exception-only `:INTERCEPTOR` + rf2-se9a9t's conditional authored-chain
> `:INTERCEPTORS`).

The inventory is LOCKED — the projection's `badge-set` enforces
that every emitted step's `:badge` is a member, and the view's
colour resolver bails to `:text-tertiary` on an unknown badge so a
future taxonomy extension paints something but tests catch the
omission.

### §9.1.5 Handler-type adaptation (rf2-82a0u prerequisites · rf2-u69j7 cascade redesign)

The HANDLER step's body adapts to the handler's flavour, discovered
from the trace stream:

- **`:db-only`** — `:db` diff only (the simplest case).
- **`:effectful`** — `:db` diff + per-fx-entry block.
- **`:reg-machine`** — **TIME-ORDERED MACHINE CASCADE** per rf2-u69j7.

> **EP-0018 note.** `:db-only` / `:effectful` are INTERNAL, behavior-based
> classification keywords describing the handler's OBSERVED EFFECT SHAPE
> (`:db`-only vs `:db`+`:fx`), discovered purely from the trace stream — never
> from the registration form. They name WHAT the handler returned, not HOW it
> was registered: the three public event registrars collapsed onto the one
> `reg-event` form (EP-0018), so the HANDLER step's VERB renders `reg-event`
> for both event flavours (`reg-machine` for machine handlers — see
> `handler-flavour-label`). The effect-shape discrimination below still drives
> which body sections render; the discriminator deliberately carries no
> registrar spelling.

**Flavour discriminator (`handler-flavour`, rf2-eue07).** The classifier is
a pure-data `cond` over the trace stream, machine-predicates FIRST (a machine
handler always rides a `:rf.fx/do-fx` for its snapshot write, so `do-fx` must
never win for a macrostep):

1. `:rf.machine/transition` present → `:reg-machine`. This is the
   AUTHORITATIVE macrostep marker — `commit-or-finalize` (machines ·
   lifecycle_fx · registration.cljc) emits ONE transition summary per
   macrostep UNCONDITIONALLY, for action-firing transitions, pure state
   moves, **entry-cascade-only transitions** (whose `:entry` actions are NOT
   traced as `:rf.machine/action-ran` — rf2-n9f4z), AND the bootstrap
   `:initial-entry` (rf2-t4582). Keying on the transition (not the narrow
   action-ran check) is what makes those action-less macrosteps render the
   machine section instead of the raw `:db` diff of the snapshot write.
2. `:rf.machine/action-ran` present → `:reg-machine` (subsumed by (1) for any
   real macrostep; retained for fixtures / defence in depth).
3. `:rf.machine.event/unhandled-no-op` present → `:reg-machine` (rf2-ugdas —
   see below; since rf2-coozg suppresses the no-change `{X}→{X}` commit
   transition at the source, a no-op-only cascade carries neither a
   transition nor an action, so it needs its own predicate).
4. `:rf.machine/started` present → `:reg-machine` (rf2-it4vt — an EAGER pure
   start emits the birth signal but NO transition / action / no-op rows;
   without this predicate the standalone start would fall through to
   `:effectful` (the machine handler always rides its snapshot-write
   do-fx) and render a raw `:db` diff instead of the `[START]` row).
5. `:rf.fx/do-fx` present → `:effectful`.
6. else → `:db-only`.

#### Machine cascade (rf2-u69j7)

**Stance.** The pre-rf2-u69j7 layout grouped machine activity into 7
category sub-sections (TRANSITION / GUARDS / LIFECYCLE / AFTER-TIMERS
/ DATA REDUCTION / SNAPSHOT DIFF / FX). That was a roll-up — the
operator had to scroll up/down across categories to reconstruct what
actually fired in what order. The redesign replaces the category-
grouped layout with a single **cascade view**: one row per substrate
emit, rendered in canonical phase order.

**Canonical phase order (rf2-tjqd8).** The rows are NOT rendered in raw
substrate emit order. The substrate's live emit order is `exit → entry
→ transition-LAST` — the `:rf.machine/transition` summary emit TRAILS
the exit + entry actions so its `:after` snapshot reflects the
accumulated data. Rendered verbatim, the TRANSITION lands AFTER the
entry action, which mis-reads the statechart (the operator expects
"leave the old state → change state → enter the new state"). The panel
therefore RE-SORTS rows into the canonical `(kind, phase)` order:

```
START → guard → exit → TRANSITION → entry → always → after-action → timer
```

via a STABLE sort keyed by `[cascade-row-rank :trace-index]` — rows in
the same rank keep their substrate emit order (multiple actions in one
phase keep their run order). The `:start` KIND leads everything (rank -1 —
the machine's birth — rf2-it4vt); the `:transition` KIND sits between the
exit-phase actions and the entry-phase actions; `:guard` follows START
(guards gate the transition); `:timer` trails (post-commit housekeeping).
`:transition`-phase actions rank WITH the transition (they fire as part
of the state change); `:initial-entry` ranks with `:entry`,
`:destroy-exit` with `:exit`. The `:step` ordinal is assigned 1..N over
the FINAL sorted order. See `projection/cascade-row-rank` +
`machine-cascade-rows`.

This is a PANEL-SIDE presentation re-sort ONLY. The substrate trace
order is untouched — reordering the `:rf.machine/transition` emit would
change trace order for every consumer (the alternative was considered
and rejected: the panel re-sort is localized and safe). The
`enrich-cascade-rows` pass (which stamps `:source-state` /
`:target-state` / `:event-id` from the surrounding transition) runs on
the trace-ordered rows BEFORE the re-sort, since that resolution is
emit-order-sensitive.

The projection walks `:trace-events` and surfaces every member of
`machine-cascade-trace-ops` as a row, then applies the canonical sort:

  | Trace op                            | Row `:kind`     |
  |-------------------------------------|------------------|
  | `:rf.machine/started`               | `:start`         |
  | `:rf.machine/guard-evaluated`       | `:guard`         |
  | `:rf.machine/action-ran`            | `:action`        |
  | `:rf.machine/transition`            | `:transition`    |
  | `:rf.machine.timer/cancelled`       | `:timer`         |
  | `:rf.machine.event/unhandled-no-op` | `:no-op`         |

**The `[START]` row (rf2-it4vt).** The `:start` row surfaces the machine's
BIRTH — the `:rf.machine/started` trace `maybe-boot` (machines · lifecycle_fx
· registration.cljc) emits per rf2-gl588 / F‴ on every successful
initial-entry cascade. It is the machine's single birth signal, in BOTH
creation paths, and renders the `[START]` badge at the FRONT of the cascade
(rank -1):

- **EAGER** — an explicit `[:machine-id [:rf.machine/start]]` dispatch gets
  its own epoch. The start is a PURE init-kick (rf2-gl588 — runs the
  initial-entry cascade then STOPS, emitting NO transition / action rows),
  so the `[START]` is the cascade's SOLE row (a standalone birth). The
  cascade's handler-flavour is forced `:reg-machine` by the
  `:rf.machine/started` presence even though no transition / action / no-op
  fired — otherwise the standalone start (which always rides the machine
  handler's snapshot-write do-fx) would mis-classify `:effectful` and
  render a raw `:db` diff instead of the `[START]` row.
- **LAZY** — when a machine is first reached by a REAL event, init folds
  into THAT event's epoch (`maybe-boot` runs ahead of the user event in the
  same handler call). So `[START]` renders at the FRONT of the cascade,
  AHEAD of the real event's guard / transition / action rows — telling the
  operator the machine was born THEN took its first step, in one epoch.

The row carries the machine's INITIAL logical `:state` (off the started
trace's `:state` tag — a keyword / path-vector for flat / compound, a
region→state map for parallel; rendered verbatim, so the badge covers flat /
compound / parallel machines uniformly) and INITIAL `:data` (the `:data`
tag). The header verb reads `<machine-id> started in {state}`; the initial
`:data` rides the body box (the same `edn-inspector` widget the transition
delta uses, rendered PLAIN — a birth has no prior state to diff against).

The row also carries a **CAUSE tag** (`format/start-cause-label`) off the
started trace's `:cause` enum — `explicit` / `lazy` / `spawned` — that tells
the operator HOW the machine came to life:

- `explicit` — a deliberate eager kick (xstate's `createActor(m).start()`).
- `lazy` — init folded into the first real event's epoch. This is an
  **ORDERING SMELL**: something dispatched to the machine before it was
  explicitly started. The view paints the `lazy` tag in the warning tone to
  flag it (`format/start-cause-smell?`); `explicit` / `spawned` ride the
  muted neutral tone (a clean, expected birth).
- `spawned` — the spawn fx pre-seeded the snapshot; init ran on the actor's
  first dispatch.

Because its trace op-type is `:rf.machine` (benign birth, not a severity),
the L2 pink-wash / issues-ribbon `issue-event?` predicate does not match it —
the event row stays un-washed. The `:start` kind pill reads `START` in the
`:success` (green) tone — a clean birth is a GOOD event, distinct from the
muted no-op, the blue action, and the magenta transition. The row carries NO
outcome chip and NO source-link spec-path key (a birth has no transition
outcome / spec call-site). A failed initial-entry (a thrown `:entry` action)
does NOT emit `:rf.machine/started` — `maybe-boot` short-circuits to the
EXCEPTION card via `trace-action-failure!` instead.

The `:no-op` row (rf2-ugdas) surfaces the benign unhandled-event no-op
(xstate-v5 parity — an event that matched no transition is ignored, NOT an
error). It fires ONLY for a genuine unknown **user** event: framework
lifecycle traffic (`:rf.machine/start`, `:rf.machine.spawn/spawned`,
stories-runtime pings, bare reserved-`:rf/*`) is carved out at the
substrate (rf2-t4582 — creation runs its `:initial-entry` cascade and is
NOT classified as an unhandled-user-event no-op; per rf2-gl588 the eager
start is a pure init-kick that never reaches the no-op site as a trigger),
so this row never stands in for a machine's birth. It ranks WITH the
`:transition` slot (it stands
in for "the state change that did not happen") and renders, collapsed to
the **CONSEQUENCE only** (rf2-iu3no), as **`[TRANSITION] [NO OP] staying in
{state}`** (rf2-yueoa). A no-op is still the **TRANSITION step** of the
cascade — a transition was ATTEMPTED (the door's `:may-close?` guard failed,
or the event matched no transition); it just produced no state change — so
the row carries the **SAME filled magenta `[TRANSITION]` badge a real
transition row uses** (`cascade-kind-pill :transition` — identical chrome +
hue + testid `rf-xray-epoch-machine-cascade-kind-transition`), then a
**`[NO OP]` QUALIFIER** chip that marks "this transition step resulted in no
state change", then the verb **`staying in {state}`** — the machine matched
no transition, so its state is unchanged. The qualifier is an OUTLINED muted
(`:text-tertiary`) chip — a refinement on the solid badge beside it, not a
second filled kind pill — reusing the `badge/cascade-kind-label :no-op`
string (`NO OP`, space not hyphen — rf2-iu3no). Since rf2-g2axio the
Machine tab renders this SAME shared cascade row (its bespoke
focused-event header + `[NO-OP]` badge were removed), so this qualifier
is now the single no-op grammar across both surfaces. It
carries the testid `rf-xray-epoch-machine-cascade-no-op-qualifier` (distinct
from a kind pill — it qualifies the transition step, it is NOT the row's
kind). The earlier rf2-yueoa-predecessor (rf2-iu3no) rendered a BARE `[NO
OP]` kind pill as the sole marker — but a bare no-op row dropped the
`[TRANSITION]` reading, making the no-op look like a different KIND of step
rather than a transition that produced nothing; rf2-yueoa restores the
`[TRANSITION]` badge so the row reads consistently with a real transition
(badge first, then the qualifier). The earlier rf2-ugdas sentence (`no-op —
<machine> received <event> in <state>, no transition`) stacked a pill + a
`no-op —` prefix + an event echo + a `, no transition` suffix — four
restatements of one fact; it stays collapsed away. The focused-epoch **Event
header already names the event**, so the verb does not echo it; the cascade
is a SINGLE row, so its `1..N` left-rail step ordinal is **suppressed**
(rf2-iu3no — it read as an unexplained leading "1"); and the row carries
**NO outcome chip** (the prior `ignored` chip was a third restatement). The
**machine name is kept ONLY when >1 machine is in play this epoch** (a
broadcast event hitting parallel regions / sibling machines) so the operator
can tell WHICH machine stood pat — `machine-cascade-rows` stamps
`:show-machine-name?` on the no-op row when the cascade spans more than one
distinct `:machine-id`; the single-machine case drops it (the EVENT HANDLER
section names the lone machine above), so the multi-machine render reads
`[TRANSITION] [NO OP] :hvac/controller staying in {state}`. This is
explicitly NOT the red exception card and NOT pink.
Because its trace op-type is `:rf.machine`
(machine-activity, not a severity), the L2 pink-wash / issues-ribbon
`issue-event?` predicate does not match it, so the event row stays
un-washed and the ribbon stays empty — the contrast with a `:*`
wildcard-action throw (`:rf.error/machine-action-exception`, which DOES go
pink + renders the EXCEPTION card) validates that the predicate correctly
distinguishes a benign no-op from a real error. A cascade renders either a
`:transition` OR a `:no-op`, never both — and this is now ENFORCED AT THE
SOURCE (rf2-coozg): on a no-op macrostep (`:before` == `:after`, empty
cascade, zero microsteps) `commit-or-finalize` (machines · lifecycle_fx ·
registration.cljc) suppresses the no-change `:rf.machine/transition` emit
entirely, so the projection never sees a contradictory `{X}→{X}`
0-microstep transition row beside the `:no-op`. The earlier tool-side
band-aid `drop-spurious-no-op-transition` (rf2-e6q97 — which dropped that
spurious row whenever a `:no-op` row was present) is therefore **RETIRED**
(rf2-it4vt): there is nothing left to drop. With rf2-gl588 the eager start
is a PURE init-kick that never feeds the marker into the transition step, so
there is no `before == after` self-transition for creation either. A genuine
self-transition (`:target :same-state` + `:reenter? true`, EXTERNAL — `:exit`
+ `:entry` fire, rf2-eicq0; or INTERNAL — omit `:target`, or a self-target
without `:reenter?` — the action runs) has a real `match`, so the
unhandled-no-op branch is never reached and NO `:no-op` row fires: its transition row (with
microsteps > 0 / an action cascade) is preserved untouched. The
no-op row also makes the cascade's handler-flavour `:reg-machine` even when
no action ran, so the EVENT HANDLER machine section renders the notice
rather than collapsing to a plain `reg-event` handler.

**Per-row chrome.** Each row carries:

- **Step ordinal** (1..N) — left-rail compact monospace chip.
  Suppressed for the single-row `:no-op` notice (rf2-iu3no — the lone
  "1" was unexplained noise).
- **Kind pill / merged action badge** — colour-coded. For `:guard` /
  `:transition` / `:timer` / `:start` it is a single KIND pill
  (`badge/cascade-kind-label` — see `badge.cljc` for the hue assignments;
  the `:start` pill reads `START` in the `:success` (green) tone, rf2-it4vt).
  **A `:no-op` row carries the `[TRANSITION]` KIND pill PLUS a `[NO OP]`
  QUALIFIER** (rf2-yueoa): a no-op is still the transition STEP of the
  cascade, so it renders the SAME magenta `[TRANSITION]` pill a real
  transition uses, followed by an OUTLINED muted `[NO OP]` qualifier chip
  (`cascade-no-op-qualifier`, testid `…-no-op-qualifier`, reusing the
  `badge/cascade-kind-label :no-op` `NO OP` string — space not hyphen,
  rf2-iu3no) — reading `[TRANSITION] [NO OP] staying in {state}`, NOT a bare
  `[NO OP]` row.
  **For `:action` rows the KIND pill + the separate phase chip MERGE into
  ONE descriptive badge** (rf2-2hj0h item 5 — `badge/cascade-action-badge-
  label`): `[EXIT ACTION]` / `[ENTRY ACTION]` / `[TRANSITION ACTION]` /
  `[ALWAYS ACTION]` / `[AFTER-ACTION ACTION]` / `[INITIAL-ENTRY ACTION]` /
  `[DESTROY-EXIT ACTION]`, painted in the ACTION-kind hue. A
  `TRANSITION ACTION` (the LCA action — a REAL action per Spec 005, running
  between exit + entry; resolved by Mike 2026-06-04) is DISTINCT from the
  state-change **TRANSITION ROW** (kind = `:transition`), which keeps its
  own single `[TRANSITION]` pill — both can appear in one cascade.
- **Cause tag** (`:start` rows only — rf2-it4vt) — `explicit` / `lazy` /
  `spawned`, off the `:rf.machine/started` trace's `:cause`. The `lazy`
  cause is the ordering-smell flag (warning tone); `explicit` / `spawned`
  ride the muted neutral tone.
- **`for <state>` clause** (`:action` AND `:guard` rows — rf2-2hj0h item 6
  + rf2-h710p item B) — after the kind pill / merged action badge the header
  reads ` for <state> ` then the verb (the action-id / guard-id), e.g.
  `[EXIT ACTION] for :closed :clear-hold` / `[ENTRY ACTION] for :open
  :count-open` / `[GUARD] for :open :may-close?`. `<state>` is the state the
  row BELONGS TO:
  - For an `:action` row — the EXITED (`:source-state`) state for an
    exit-phase action, the ENTERED (`:target-state`) state for an
    entry-phase action (the LCA `:transition`-phase action anchors on the
    source state); `format/cascade-action-for-state`.
  - For a `:guard` row — the GATED state, i.e. the transition's
    `:source-state` (the state whose `:on` map carries the guard);
    `format/cascade-guard-for-state`.

  Both read off the `:source-state` / `:target-state` slots
  `enrich-cascade-rows` stamps off the surrounding `:transition` row. The
  clause is OMITTED (no dangling `for`) when no state was stamped. Rendered
  by the kind-agnostic `cascade-for-state-clause` (the caller picks the
  resolved state per kind).
- **Verb** — the action-id / guard-id / transition headline / timer
  state, rendered as a click-to-source button when a `{:file :line}`
  coord resolves for the row (a named guard/action reads its co-located
  `:source-coords`; a reference-site `[:states ...]` key reads the
  `:source-coords` off the nearest enclosing `:states`-tree map node —
  rf2-vqja2). Falls back to a plain coloured span when no coord was
  captured. **For a `:guard` row the verb is the bare guard-id** (rf2-h710p
  item B) — the leading `guard` word that prefixed it pre-rf2-h710p was
  redundant (the `[GUARD]` kind pill already names the kind), so it is
  dropped; the gated state rides the `for <state>` clause above.
- **Duration chip** — right-aligned monospace; paints long-step
  warning chrome (`▲` + warning tone) when `:duration-ms` exceeds
  `projection/long-step-threshold-ms` (16ms — one 60Hz frame).
- **Outcome chip** — kind-specific. Right-aligned (in the duration-chip
  slot) for `:transition` / `:timer`; **INLINE** (in the header flow,
  straight after the verb + its click-to-source glyph) for `:guard`:
  - `:guard` → `✓ pass / ▲ fail / ✗ threw`, rendered **INLINE** after the
    guard name + source glyph (`[GUARD] for :open :may-close? ↗ pass` —
    rf2-h710p item C), NOT right-aligned. The guard pass/fail is MEANINGFUL
    (it decides the branch — distinct from the rf2-2hj0h item-7 ACTION
    ok-tick that was removed), so it stays; keeping it inline puts the
    verdict beside the predicate that produced it.
  - `:action` → **NO outcome chip** (rf2-2hj0h item 7 — success is CLEAN, no
    `✓ ok` tick; the prior tick was redundant chrome in the normal case).
    Failure is the **EXCEPTION BOX** below the code (item 8 — see §Per-row
    outcome detail), not a chip. (rf2-4yrr6 had already dropped the threw
    chip; item 7 drops the success `:ok` chip too, so the action row's
    outcome reads purely off presence/absence of the exception box.)
  - `:transition` → **NO outcome chip** (rf2-cdgva — the prior
    `N microstep(s)` summary was REDUNDANT: every `:always` microstep
    (N>0) is itself a first-class cascade row in this same mini-pipeline
    — its own `:rf.machine/transition` + nested exit/action/entry rows,
    post akvfe/2hj0h — so the count merely tallied rows already present;
    when N=0, the common case, it was pure noise. The prominent
    `<before> → <after>` header verb is the transition's whole story.)
  - `:timer` → `· cancelled (<reason>)`
  - `:no-op` → NO outcome chip (rf2-iu3no — the `NO OP` pill + the
    `staying in {state}` verb carry the whole notice; the rf2-ugdas
    `ignored` chip was a third restatement of the same fact)
  - `:start` → NO outcome chip (rf2-it4vt — the `START` pill + the
    `started in {state}` verb + the cause tag carry the whole birth
    notice; a birth has no transition outcome)

**Per-row body — interleaved source code (always visible).** Every
cascade row carries source visibility (rf2-wwc3j extends rf2-u69j7's
named-only coverage to inline-fn / transition / timer rows). The body
renders the source form pulled from the registered machine spec via
`edn/code-block` (clojure-syntax highlight; same widget the HANDLER
source block uses).

The machine spec is read off `(rf/handler-meta :event event-id)`'s
`:rf/machine` slot (rf2-ge6uj ISSUE 2) — the stamped spec whose
`:guards` / `:actions` entries co-locate `:source-code` (named
guard/action fn-form pr-str strings) + `:source-coords` (per-element
`{:file :line}`), and whose `:states`-tree map nodes (state-node /
transition map) each co-locate their own reference-site `:source-coords`
(rf2-vqja2, supersedes the flat `:rf.machine/state-coords` index of
rf2-npvsx / rf2-ypu5i / rf2-8bp3) **plus an inline-fn `:source-code` map**
keyed by inline slot (`{:entry "…" :exit "…"}` on a state-node,
`{:guard "…" :action "…"}` on a transition map; rf2-se70xj). The prior
code read `(rf/handler-meta :machine event-id)`, a NON-EXISTENT registrar
kind that always resolved nil — so `machine-spec-from-meta` saw no spec
and every exit / entry action + guard row rendered the `<source not yet
captured>` placeholder. Reading under `:event` (where the machine
handler is registered) surfaces the stamped spec so the interleaved
source code resolves for named handlers (off the co-located entry's
`:source-code`) AND for inline-fn rows (off the enclosing node's inline
`:source-code <slot>` — rf2-se70xj; before it, an inline action's body
fell through to the bare compiled fn and rendered `#object[Function]`,
while its `:source-coords` click-to-source still worked off the enclosing
node). Source-key dispatch (`projection/cascade-row-source-key`) returns
the spec-path tuple the view's `named-element-key` discriminator routes
between the two lookup families; the view's `cascade-row-source-form`
then resolves the SOURCE:

- **Named** `[:guards|:actions <id>]` keys → the co-located entry's
  `:source-code` string (fall back to the bare `:fn` in prod / fn-form
  fixtures).
- **Inline-fn** `[:states … <slot>]` keys → the enclosing node's inline
  `:source-code <slot>` string (the parent of the key, `(butlast k)`, +
  the slot `(last k)`; rf2-se70xj). Falls back to the bare slot value (a
  compiled fn / keyword reference) when no inline source was captured
  (prod, fn-form fixtures, value-registered non-`defmachine` specs).

Click-to-source COORDS for inline-fn rows resolve via
`projection/state-node-source-coords` (the `:source-coords` on the
nearest enclosing `:states`-tree map node — walk-up; rf2-vqja2),
distinct from the inline `:source-code` body above.

| Row kind | id flavour              | spec-path key                                         | source-body lookup | coord lookup |
|----------|-------------------------|-------------------------------------------------------|--------------------|--------------|
| `:action`| keyword `action-id`     | `[:actions <id>]`                                     | entry `:source-code` | entry `:source-coords` |
| `:action`| inline-fn, `:entry`     | `[:states <target-state>... :entry]`                  | enclosing state-node `:source-code :entry` | enclosing state-node `:source-coords` |
| `:action`| inline-fn, `:exit`      | `[:states <source-state>... :exit]`                   | enclosing state-node `:source-code :exit` | enclosing state-node `:source-coords` |
| `:action`| inline-fn, `:transition`| `<slot-prefix> :action` (exact, via `:transition-slot`; fallback `[:states <source-state>... :on <event> :action]`) | enclosing transition-map `:source-code :action` | enclosing transition-map `:source-coords` |
| `:action`| inline-fn, `:always`    | `<slot-prefix> :action` (exact, via `:transition-slot`; fallback `[:states <source-state>... :always :action]`) | enclosing `:always` map `:source-code :action` (legacy read-back also probes the index-0 vector path) | enclosing `:always` map / state-node `:source-coords` (walk-up) |
| `:action`| inline-fn, `:after-action`| `<slot-prefix> :action` (exact, via `:transition-slot`; fallback `[:states <source-state>... :after :action]`) | enclosing `:after` transition-map `:source-code :action` | enclosing `:after` map / state-node `:source-coords` (walk-up) |
| `:guard` | keyword `guard-id`      | `[:guards <id>]`                                      | entry `:source-code` | entry `:source-coords` |
| `:guard` | inline-fn               | exact carried `:spec-path` if present; else `[:states <source-state>... :on <event> :guard]` | enclosing transition-map `:source-code :guard` | enclosing transition-map `:source-coords` |
| `:transition` | —                  | `[:states <source-state>... :on <event>]`             | logical-state delta box (not source) | transition-map `:source-coords` |
| `:timer` | —                       | `[:states <state>...]` (parent state-node, D1 shape)  | (body elided) | state-node `:source-coords` |

The per-row `:source-state` / `:target-state` / `:event-id` slots are
stamped by `machine-cascade-rows`'s `enrich-cascade-rows` pass —
walks the cascade in trace order and for each non-transition row
copies the surrounding `:transition` row's `:from-state` / `:to-state`
/ `:event` (substrate emits actions BEFORE the macrostep's
`:transition` emit; the next transition ahead is the surrounding
one). Multi-microstep cascades carry one transition emit per
macrostep — intermediate-state inline-fns fall back to the
headline state; source-key resolution degrades to the macrostep's
source/target.

**Exact transition spec-path via `:transition-slot` (rf2-lai1qv).** The
reconstruct-from-`source-state`/`event`/`phase` path above is a FALLBACK.
The substrate (`re-frame.machines.transition`) now stamps the SELECTED
transition's exact spec-path DISCRIMINATOR on the `:rf.machine/action-ran`
trace under `:transition-slot`, and `action-cascade-row` carries it onto
the row. `cascade-row-source-key` builds the precise inline-source slot
from it (`transition-slot->spec-prefix` + the `:action` leaf), which WINS
over the reconstruction. The discriminator is:

```
{:decl-path     <state-path or []>   ;; [] = root / parallel-root :on
 :slot          :on | :always | :after
 :event-key     <matched :on key>     ;; exact / :ns/* / :* — nil for :always/:after
 :delay-key     <:after delay key>    ;; nil otherwise
 :candidate-idx <int or nil>          ;; nil = index-free (single-map / kw / vec-target)
 :root?         <bool>}               ;; true iff decl-path is []
```

This closes the four cases the reconstruction missed:

- **Candidate-vector `:on`** — `:candidate-idx` names the MATCHED
  candidate (`[:states <s> :on <ev> <i>]`), not the hardcoded index 0.
- **`:always` nonzero candidate** — `:candidate-idx` names the matched
  `:always` candidate (`[:states <s> :always <i>]`); the index-free
  single-map form carries `:candidate-idx nil` and resolves to the bare
  `[:states <s> :always]` slot (the rf2-k7yqod keying).
- **`:after`-action** — `:delay-key` names the exact `:after` slot
  (`[:states <s> :after <delay-key>]`); the reconstruction could only
  emit the bare `[:states <s> :after]` (delay-key unknown).
- **Root / parallel-root `:on`** — `:decl-path []` / `:root? true`
  resolves a root-relative `[:on <ev>]` slot OUTSIDE `:states`; the
  reconstruction assumed a `:states` prefix.

`:candidate-idx` is meaningful ONLY for the multi-candidate VECTOR value
form (the only form the inline-source macro keys per-index); the
single-map / keyword-target / vector-target forms carry `:candidate-idx
nil` so the slot resolves at the bare path, matching the macro's keying.
Boundary `:exit` / `:entry` actions are not transition actions — they
carry NO discriminator and resolve via the `:source-state` /
`:target-state` reconstruction. A guard's exact slot rides an explicit
`:spec-path` tag on the `:rf.machine/guard-evaluated` trace
(`guard-cascade-row` carries it; `cascade-row-source-key` prefers it).

For `:transition` rows, the body renders the **logical-state DELTA
box** (rf2-iwy0c — see "Transition row" below), NOT a source form;
the `[:states <source-state>... :on <event>]` source-key in the table
above still resolves the transition's click-to-source coord for the
verb-link, but the body itself is the `{:state :tags}` before→after
diff. For `:timer` rows, the body is elided (the parent state-node
is too verbose to render verbatim); the click-to-source chip on the
verb is the primary affordance, opening the operator on the state
that owns the `:after` slot.

Always visible by default per the bead body's "interleaved source
code" requirement — the operator reads what ran AND its code at the
same vertical position without expand/collapse gestures. **Source-
missing fallback (rf2-iwy0c part B).** When no source form is captured
for an `:action` / `:guard` row (production `goog.DEBUG=false` builds,
value-registered machines), the body renders the **machine id as a
click-to-source LINK to the machine definition** — NOT a dead `<source
not yet captured>` literal. The link (shared `coord-link`, `<id> ↗`
grammar) targets, per Mike's ruling, BOTH the reg-machine CALL-SITE and
the defmachine DEFINITION:

- **(i) reg-machine call-site** — the `:file` / `:line` captured TODAY
  on `(rf/handler-meta :event event-id)` (rf2-ge6uj). Implemented now.
- **(ii) defmachine definition** — RELATES to rf2-gwj8l (definition
  coord not yet stamped for value-registered machines). The link
  STRUCTURE lights up automatically once gwj8l stamps the coord; until
  then it DEGRADES GRACEFULLY to (i) alone, and to a plain
  non-clickable label when even the call-site coord is absent.

**Per-row outcome detail.** Action rows surface inline:

- **`↳ <edn-inspector value>`** — when the action returned a `:data`
  map, the delta the action contributed rendered through the
  **edn-inspector in DIFF mode** (rf2-5hjb5). The row reads
  `<arrow> <edn-inspector value>`: a light-grey `↳` arrow (rf2-fg3c4)
  into the inspector value, with **NO `data Δ` caption** (rf2-32kyr —
  the literal "data Δ" label was redundant chrome; the arrow into the
  inspector value carries the reading). The action's RETURNED `:data`
  (`:data-write`, the AFTER) renders with inline diff annotations
  against the action's INPUT `:data` (`:data-before`, lifted off the
  `:input {:data …}` snapshot), reusing the same `{:before <prior>}`
  posture the App-db panel ships (cf. `handler-db-diff-block`). A
  data-mutating action
  shows its delta inline (entry `:count-open`: `{:opened-count 0}` →
  `{:opened-count 1}` paints the changed leaf with the `~ … ← was 0`
  gutter chrome); a no-op action whose `:data` is unchanged (exit
  `:clear-hold`) renders the value with NO delta (the inspector's
  `:same` rows carry no gutter glyph). When no pre-image was captured
  the inspector mounts in browse mode. Supersedes the prior `ei/mini`
  one-liner.
- **`↳ fx`** — per-action fx-id chips for each effect the action
  emitted (same data the FX step's `:attributed-to` chip surfaces,
  now visible IN the action's row).
- **EXCEPTION BOX** (`:action` / `:guard` rows that THREW — rf2-2hj0h
  item 8) — when a guard or action body throws, an exception box renders
  directly below that step's code, **modeled on the OUTER pipeline's inline
  exception card** (`view/error-block` / `error-block-details`) and the way
  the fuse `:*` throw surfaces on the HANDLER step: the same `error-block-*`
  chrome — a `✗` glyph + the `Exception Thrown` title, the verbatim message
  (`ex-message` on the row's raw `:exception`), and the collapsible stack /
  ex-data disclosure (`<details>`). It lands IN the throwing cascade row so
  the operator reads the code AND its exception at one vertical position.
  The throwing step's verb-link already carries the click-to-source
  `<file:line>` (so the box does not re-render a coord line — matching the
  outer card, which dropped its redundant jump-to-source link in rf2-wnvid).
  Together with item 7 this defines the action/guard outcome display:
  **success = clean (no tick); failure = exception box.**

> **No-info-loss anchor (rf2-akvfe).** The `↳ data Δ` on the entry-action
> row (`:count-open` → `{:opened-count 1}`) is exactly the data-delta the
> retired up/down structured-cascade block (§Transition row, below) used to
> restate. With the block removed, this `↳ data Δ` is the single surviving
> place the entry action's data-delta renders — satisfying the rework's
> no-info-loss guard (the exit action, the entry action, and the data-delta
> all live on their own numbered pipeline rows).

**EVENT HANDLER section layout (rf2-akvfe · rf2-2hj0h).** The machine EVENT
HANDLER section renders as: (1) the structured **orientation line** under the
heading — `[TRIGGER] ‹vec› for [MACHINE] ‹id› in [STATE]
‹pre-transition-state›`, grey chip-labels + code-formatted values
(§9.1.6.4) — over (2) the cascade rows laid out as a **flat numbered stack**.

> **rf2-2hj0h (Mike door-deck review, 2026-06-04).** Two refinements over
> the akvfe layout:
> - The orientation line **DROPS the leading "Processing" word** (item 4) —
>   the chips + values are self-orienting; the verb was filler.
> - akvfe's nested-pipeline **vertical RAIL is REMOVED** (item 2) — the line
>   that ran behind the `[1][2][3]` ordinals (`machine-cascade-rail-style`),
>   together with the per-step source-body left **CONNECTOR** (the source
>   body's `border-left`), were the **two left vertical lines** this bead
>   retires. The numbered ordinal chips alone now carry the pipeline reading.
>   The **thin HORIZONTAL inter-step lines** are removed too (item 1 — the
>   cascade row's `border-bottom` + the rows-host `border-top`). The ordinal
>   numbering is retained.

> **Step content aligns to the BADGE left edge (rf2-2hj0h item 3, corrected
> by rf2-4b6im).** ALL of a step's subsequent content — the source body, the
> per-action outcome details (the `↳` data-delta + the `↳ fx` chips), any
> exception box, and any sub-line — left-aligns to the **LEFT EDGE of the
> badge** (e.g. the left of `[EXIT ACTION]` / `[GUARD]`), forming one
> left-aligned column under the badge. The `[N]` ordinal chip sits outdented
> to the badge's LEFT; the badge defines the content column for the whole
> step. This holds for **every** mini-step kind (`[EXIT ACTION]` /
> `[ENTRY ACTION]` / `[TRANSITION ACTION]` / `[TRANSITION]` / `[GUARD]` /
> `[NO OP]` / …). The indent (`view/cascade-info-indent`) equals the ordinal
> chip's outer width (`cascade-ordinal-box-width`, 21px — the chip carries
> `box-sizing: border-box` so its rendered width is exactly its declared
> width) **+** the header `:gap` (`cascade-header-gap`, 6px) = 27px.
>
> rf2-4b6im CORRECTS item 3's as-rendered behaviour: item 3 already specified
> the badge left edge, but the ordinal chip rendered ~29px wide under the
> default `content-box` (its 8px horizontal padding sat outside the 21px
> `min-width`), so the prior 27px constant under-shot — content hung at the
> ordinal RIGHT edge instead of the badge left. Pinning the chip to
> `border-box` makes 21px + 6px gap land exactly on the badge left edge.

A THREW action surfaces NO per-row threw chrome (rf2-4yrr6 retired the prior
`✗ threw — <message>` outcome-detail line AND the duplicate `✗ threw`
outcome chip). Its failure is rendered by the **per-row EXCEPTION BOX**
(rf2-2hj0h item 8 — see §Per-row outcome detail below) directly under the
throwing step's code. One signal: the box.

**Transition row — prominent header verb + logical-state DELTA box
(rf2-ge6uj header · rf2-iwy0c body).** The transition zone is a SINGLE
prominent row: the header carries `[#step] [TRANSITION badge]
<before-state → after-state>`, where the verb IS the state change
(rendered larger / bolder / magenta — the focal point of the collapsed
zone) and doubles as the click-to-source affordance. The redundant
leading "transition" word (the KIND pill already says TRANSITION), the
machine-name echo (already the cascade context), and the prior
repetitive lower-line `state {:from} → {:to}` + `event [...]` detail
block are all REMOVED.

The row's BODY is the **logical-state DELTA box** (rf2-iwy0c part A) —
an `ei/edn-inspector` DIFF (the SAME widget + `{:before <prior>}`
posture the per-action `↳ data Δ` uses, rf2-5hjb5) showing the
machine's `{:state :tags}` BEFORE → AFTER. This **REVERSES the
rf2-wwc3j transition-map "delight shape"** (intentional, per Mike): the
map literal merely restated the target state the headline verb already
names, whereas the delta box earns its place by carrying `:tags` + the
structured before→after state object.

- **Scope = `{:state :tags}` ONLY** (`projection/machine-logical-
  state`). `:data` is EXCLUDED — the per-action `↳ data Δ` already
  carries it (folding it in double-shows it); the framework-owned
  `:rf/*` snapshot slots (`:rf/spawn-counter`, after-epoch counters —
  Spec 005 §Reserved snapshot-internal keys) are EXCLUDED (not user
  state — a raw snapshot-diff would dump them). `select-keys [:state
  :tags]` filters everything else by construction.
- **Data source** — the transition row's `:before` / `:after` snapshots
  (hoisted off the `:rf.machine/transition` trace's `:before` / `:after`
  tags, which carry the full machine snapshot on either side per Spec
  005 §Trace events). Equivalently the epoch record's frame-state at
  `[:rf.runtime/machines :snapshots <machine-id>]` in runtime-db.
- **Parallel machines** — `:state` may be a region→state map; the box
  shows the structured map + the tag-union shift in one object (e.g.
  `{:vehicle :red :pedestrian :dont-walk}` / `#{:vehicle-stop
  :ped-stop}`), exactly what the single-region headline verb cannot
  convey.
- **Elision** — on a SELF / internal transition where neither `:state`
  nor `:tags` changed (`projection/machine-logical-state-changed?`
  false — only `:data` or `:rf/*` bookkeeping moved), the box is elided
  entirely; the headline verb stays.

**Transition row — the rf2-52u5n up/down structured-cascade BLOCK is
REMOVED (rf2-akvfe).** The transition row formerly rendered, below the
logical-state delta box, a **structured-cascade block** — the up/down
`↑ exited-state / <exit-action> / ↓ entered-state / <entry-action> /
{data-delta}` walk (`view/structured-cascade-body`). rf2-akvfe removes
that rendered block: it **duplicated the EVENT HANDLER cascade pipeline**.
The exit-phase and entry-phase actions are ALREADY their own numbered
cascade rows (`[1] :clear-hold` exit-action, `[2]` the TRANSITION,
`[3] :count-open` entry-action), each carrying its source and — for the
entry action — its `↳ data Δ` (e.g. `{:opened-count 1}`). So the exit
action, the entry action, AND the data-delta all survive on the pipeline
rows (the rf2-akvfe **no-info-loss guard**). The pipeline is the single
canonical place the cascade is shown.

**The structured `:cascade` data STAYS — as the projection's order
oracle.** The `:cascade` tag the substrate emits on the
`:rf.machine/transition` trace (rf2-n9f4z; Spec 005 §The structured
transition cascade) — a vector of self-describing step maps
`{:kind <:exit|:action|:entry|:microstep> :state <path> :region
<name-or-nil> :action <id-or-nil> :data-delta <changed-keys>}` in
EXECUTION order — is still threaded onto the transition row's `:cascade`
slot, and `projection/cascade-regions` / `cascade-microsteps` /
`parallel-cascade?` / `cascade-step-count` still group it. The
`machine_epochs` harness reads those projection helpers as the
**cascade-ORDER oracle** for its assertions (exit deepest-first →
transition `:action` @ LCA → entry shallowest-first + initial-descent →
one `:microstep` per `:always` iteration). Only the *rendered* up/down
view block is gone; the data layer + the order contract are unchanged.

> **Action-free boundaries.** The retired block could surface action-free
> boundaries (e.g. exiting `:idle` / `:off`, or the HVAC LCA walk) that the
> per-EMIT `:rf.machine/action-ran` stream cannot. Per rf2-akvfe (Mike,
> door-deck authority 2026-06-04) that block is removed wholesale as a
> duplicate of the pipeline; the structured `:cascade` remains queryable as
> projection data + the cascade-order oracle, and the Machine Inspector
> (003) carries the full configuration-walk view.

The cascade is the consumer of the rf2-n9f4z instrumentation contract per
[003-Machine-Inspector §The EVENT HANDLER machine cascade](003-Machine-Inspector.md).

The `machine_epochs` testbed (:8033) is now the **assertion-backed render
regression harness** for this whole cascade-render contract (rf2-g27vv;
runner-shaped rf2-kipb5; step-driver rewrite rf2-5sjbg). Driven through the
shared step-driver runner (`runner.core` — cursor in app-db `:step`, moved
by a `[:run-step n]` event that dispatches the step's machine event into the
host-frame, NO Reagent atom), its step matrix drives the full FEATURE ×
RENDER-SURFACE matrix — plain /
entry-exit / transition-action / guard pass+fail / internal / fx /
unhandled-no-op / root-`:on` resolution (door); parallel regions + history +
member-level tag-set delta (traffic); `:always` **microsteps** that settle
over N>0 microsteps (quiz); `:after` **timer** auto-fire + cancellation
(brew); **spawn → `:final?` → `:on-done` → auto-destroy** lifecycle
(session); `:*` wildcard-action **throw** (fuse); deep-compound LCA + self-
transitions (hvac); and the `:history` **placement-reject** step plus **live
shallow + deep restore** steps (first-class history per rf2-mle6e). Each step
is backed by a CLJS-unit assertion in
`day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test` that
drives the step through the live substrate and pins BOTH the machine outcome
AND the Xray cascade-render projection it lights up — so re-driving the deck
is a real regression test of this render contract. Cascade ORDER is read off
the structured `:cascade` steps (the `cascade-regions` projection), so the
harness keys its order assertions directly off the Xray-surface projection —
there is no app-level order-oracle to keep in sync.

Timer rows surface only the header + click-to-source chip (no inline
body — cancellations are housekeeping; the chip routes to the
`:after`-bearing state node).

**Empty-state correctness** (acceptance #4 — rf2-u69j7). A vanilla
`reg-event` cascade (or any non-`:reg-machine` flavour) renders
the existing pipeline UNCHANGED — the cascade view is gated on
`:flavour = :reg-machine`. The redesign is machine-specific.

**Why this lands cleanly.** The cascade view composes with existing
Epoch infrastructure:

- `proj/long-step-threshold-ms` (16ms — rf2-nqt3d) drives the per-row
  duration chip's warning chrome.
- `ei/mini` (rf2-8w8er) renders every CLJS value (data write, fx
  args, state vectors) so the cascade syntax-tokens match the rest
  of the panel.
- The shared `coord-chip` affordance (rf2-80u5a / rf2-ehd8v) is the
  source-link grammar; the cascade-row verb-link reuses the same
  `:rf.xray/open-in-editor` dispatch.

**The legacy category-grouped sub-sections are REPLACED, not
augmented** (per Mike, pre-alpha posture). The full state-change
story is now told inline by the cascade: transitions render their
`from → to` headline verb + the `{:state :tags}` logical-state delta
box (rf2-iwy0c); actions render their data-write + fx attribution +
source body; no separate DATA REDUCTION / SNAPSHOT DIFF block lands. The FX section's per-action `:attributed-to`
chip stays in place (the FX step is the post-commit lens; the
cascade row is the action-attribution lens — same data, two
surfaces).

The flavour discriminator runs at projection time as a pure-data
check against the trace stream — no spec read, no registry lookup,
no replay. The substrate enhancements in #2155 (rf2-82a0u) are the
prerequisite that lets every cascade row read directly off the
trace.

#### §9.1.5.1 HANDLER `:db` — single FULL+DIFF rendering (rf2-vv3m6)

The HANDLER step's `:db` sub-section renders the **post-handler,
pre-flow** `:db` tree via the edn-inspector widget WITH the focused
epoch's `:db-before` threaded as the diff pre-image, so inline diff
annotations paint per the R1-R8 grammar below. There is no mode
toggle.

> **Post-handler `:db`, not post-flow (rf2-4wywy / rf2-48oc4)** — the
> rendered value is the **effective post-handler db** (the db AS IT
> STOOD AT END-OF-HANDLER, pre-flow), surfaced by the projection as the
> HANDLER step's `:db-post-handler`. It is NOT the epoch record's
> `:db-after`. `:db-after` is the FINAL post-flow / post-commit state —
> flows write app-db AFTER the handler returns (at the outermost
> `:after` interceptor), so reading `:db-after` here CONFLATED the
> handler's change with any flow recompute that followed it (the bug).
> The HANDLER step shows ONLY what the handler returned; the flow's own
> contribution is rendered on the FLOW step as its own `:db` diff (the
> reshape · §9.1.10.8). The diff pre-image stays `:db-before` throughout.
>
> The effective post-handler db **MUST NOT assume the handler returned a
> `:db`** (`projection/effective-post-handler-db`):
>
> 1. **Handler returned `:db`** → the **t1 snapshot**
>    (`:rf.event/db-pending`, post-handler / pre-flow · rf2-ta0y7).
> 2. **Handler returned NO `:db` but a flow fired** (rf2-48oc4 edge
>    case) → **`db-before`**. The substrate stamps t1 only `(when
>    has-db?)`, so no t1 rides the stream; but t2
>    (`:rf.event/db-pending-post-flow`) DOES fire because a flow
>    synthesised a `:db` from app-db and changed it. The db at
>    end-of-handler equals `db-before` (the handler wrote nothing), so
>    the HANDLER step shows **NO `:db` change** — the flow's change is
>    attributed to the FLOW step, not the handler.
> 3. **Neither t1 nor t2** (no flow + no `:db`, OR a pre-rf2-ta0y7
>    runtime) → the slot is nil and the sub-section **falls back to the
>    record's `:db-after`** so older epochs still render.

> **Retirement (2026-05-29, rf2-vv3m6)** — the prior
> `[diff][full][full+diff]` three-mode toggle (rf2-n2jig + rf2-yqjrd)
> retired. FULL+DIFF became a complete lens once the auto-collapse
> of unchanged subtrees (rf2-fqcdd), the leaf-scalar `← was X`
> annotation (rf2-fyd8u), and the added/removed colouring fix
> (rf2-9d4j8) landed — operator gets the density `:diff` used to
> provide AND the comparison context `:full` lacked in one rendering.
> The mode-toggle widget at
> `day8.re-frame2-xray.views.diff-mode-toggle` and its per-surface
> sub/event/slot trios are gone (no shim; pre-alpha posture). See the
> §9.1.5.2 lineage note for the symmetric retirement on the App-DB
> panel, Machine Inspector snapshot drill-in, and SUBSCRIPTIONS
> value-cells surfaces.

**Diff engine**: Editscript A* (`juji/editscript` 0.6.5). Replaces the
home-grown leaf-walker classifier wholesale (10 fns retired at
`tools/xray/src/.../views/edn_inspector.cljs:533-664`). Pure-data
edit-script output as EDN — `[[[path] :+ value] [[path] :- ]
[[path] :r value]]` — feeds the projection layer at
`day8.re-frame2-xray.diff.engine`, which emits `{:path-ops
:container-ops :flat-rows :wholly-changed-roots :shift-suffix
:vector-removals}`. The renderer chrome reads off this projection;
the engine swap is invisible to the chrome, only the classifier
flips.

**Mode-3 grammar (R1-R8)** — implements the rules from
`diff-mode-3-key-and-triangle-grammar` findings doc §5.1 (revised
per §7 with Mike's pair-debug answers):

- **R1**: value-side `~` gutter glyph for modified scalars + `←
  was <prior>` italic muted suffix. Value-anchored —
  the slot identity didn't change, only the contents did, so
  chrome paints the value cell only (no key-cell wash, no key
  strike). See the **slot-vs-value anchoring rule** below.
- **R2 (refined rf2-zpeyv)**: key-side `+` / `−` glyph at column 1
  of the key row when the KEY itself is `:added` / `:removed`.
  Per the slot-vs-value rule (below), the per-op WASH paints the
  **whole row** (key cell + value cell) and the `:removed`
  strike-through reaches the KEY text — not just the value. The
  visual unit matches the semantic unit: the SLOT changed, so the
  WHOLE ROW reads as changed.
- **R3 (revised)**: container triangle stays default
  `:text-tertiary` always (no colour swap). When COLLAPSED (`▶`)
  AND the subtree carries change, a `[N∆]` count chip appears
  after the closing ellipsis: `▶ :user {…} [3∆]`. No per-op
  breakdown — single count only.
- **R4**: single 2px vertical rail in the gutter through each
  change-bearing subtree, in the dominant-op hue. Drawn at each
  container body's left border; no nested rails at the same
  indent because each container's body is indented further than
  its parent.
- **R5 (revised)**: wholly-new / wholly-removed subtrees
  reclassify to `:added` / `:removed` at the parent. Descendant
  gutter GLYPHS + 2px STRIPES are suppressed; descendant row
  WASHES are RETAINED (low-opacity tint). Operator scrolled into
  the middle of a 20-leaf added shard still reads green. The
  PARENT key's row follows the R2-refined slot-anchoring rule:
  whole-row wash on the parent key + value cells.
- **R6**: vector shift-detection via Editscript's A* + Myers
  underpinnings. Shifted-but-equal rows carry a `(was N)` muted
  suffix in `:text-tertiary` and NO gutter glyph (new op
  classification `:same-shifted`). Removed elements live on a
  separate `:vector-removals` channel keyed by parent path
  (the after-tree has no stable path identity for the deleted
  element). Vector INSERT / REMOVE rows naturally satisfy the
  slot-anchoring rule — the row IS the slot (no key column),
  so the per-op wash on the row's content IS the whole-row
  treatment.
- **R7**: type-change containers reclassify to `:modified`. New
  value renders in the value column; `← was <prior>` suffix via
  the `mini` renderer (falling back to "<type> with N keys"
  when `mini` overflows). Value-anchored per the slot-vs-value
  rule — the slot still resolves, only the value's type flipped.
- **R8**: one-sided `:rf/redacted` renders as `:modified` with
  curated `← was redacted` / `← now redacted` suffix (no
  sentinel text leak). Two-sided redacted classifies as `:same`
  for v1; propagating "underlying-differs" is a follow-on bead.
  Value-anchored — the slot's visibility changed, not its
  identity.

**Slot-vs-value anchoring (rf2-zpeyv)** — the visual chrome
mirrors the semantic unit of the change. R2 + R6 (slot-identity
changes) paint the whole row; R1 / R7 / R8 (value mutations
inside an existing slot) stay value-anchored.

| Change kind | Anchor | Wash reach | Strike (for `:removed`) |
|---|---|---|---|
| **R2 — key added** (slot identity change) | Key cell | **Whole row** (key + value cells) | n/a |
| **R2 — key removed** (slot identity change) | Key cell | **Whole row** (key + value cells) | Reaches the KEY text + value text |
| **R5 — wholesale add/remove subtree** | Parent key | Whole subtree (R5-tinted descendants); parent key's row follows R2 | Parent key text struck when removed |
| **R6 — vector insert / remove** | Whole row (the row IS the slot) | Whole row | Reaches the row's content |
| **R1 — value mutated** (slot identity unchanged) | Value cell | Value cell only | n/a |
| **R7 — type change** (slot identity unchanged) | Value cell | Value cell only | n/a |
| **R8 — redaction transition** (slot identity unchanged) | Value cell | Value cell only | n/a |

Implementation: the renderer paints the per-op wash directly on
the key cell `<div>` and the value cell `<div>` of the grid row
when the child slot's op is `:added` or `:removed`. The inner
gutter-row's own wash is suppressed (via `:slot-anchored?` →
`:suppress-wash?`) so the row reads as a single banded slot, not
a double-painted darker band over the value half. Test surface:
`slot-anchored-added-key-paints-whole-row`,
`slot-anchored-removed-key-paints-whole-row-and-strikes-key`,
and `value-anchored-modified-row-does-not-paint-key-cell-wash`
in `tools/xray/test/.../views/edn_inspector_cljs_test.cljs`.

**Default-expanded-depth**: 3 in mode-3 specifically (per Q4 — between
browse's 1 and diff's 2; deep enough to surface most app-db top-level
shards). Other modes keep their existing defaults.

**Canonical visual reference**: the Story variants under
`tools/xray/testbeds/panel_gallery/` exercise every R-rule + scenario
+ theme/density case. To see what R5 looks like, see story
`diff-mode-3/r5-wholly-new-subtree`.

#### §9.1.5.2 Single FULL+DIFF rendering across diff surfaces (rf2-vv3m6 lineage)

Per Mike pair-debug 2026-05-29 the `[diff][full][full+diff]` mode
toggle (rf2-n2jig + rf2-yqjrd) retired across every Xray surface
that surfaces a `(before, after)` data view. FULL+DIFF is the single
rendering — the full data tree with inline diff annotations against
`:before`, painted per the R1-R8 grammar (§9.1.5.1).

**Affected surfaces** (single rendering, no per-surface mode slot):

| Surface                                | Rendering posture |
|----------------------------------------|-------------------|
| Epoch HANDLER step `:db`               | FULL+DIFF via edn-inspector with `:before` threaded |
| App-DB panel                           | Section list, each section FULL+DIFF |
| Machine Inspector snapshot drill-in    | Single edn-inspector mount, FULL+DIFF |
| Epoch SUBSCRIPTIONS step value cells   | Per-row leaf-scalar (rf2-fyd8u) or container FULL+DIFF; a first-run container routes through `:added?` (§10.0.13) for whole-tree `:added` chrome (rf2-kp7bw). Leaf-scalar rows fork three ways on `(changed?, first-run?)` — see §9.1.5.3 |

**R-rule applicability across surfaces**: all R1-R8 rules from
§9.1.5.1 apply uniformly to every surface. Rules that have no work
to do on a given value shape trivially no-op rather than special-
cased per surface — e.g. R6 (vector shift-detection) no-ops on map
containers; R2 (key glyph) no-ops on primitive cell values like a
SUBSCRIPTIONS row's scalar return; R3 (collapsed-container `[N∆]`
chip) no-ops on leaf scalars. The shared projection engine
(`day8.re-frame2-xray.diff.engine`) holds the grammar; per-surface
mounts contribute the `(before, after)` value pair — passing
`:before` IS the activation of the full R1-R8 grammar, including the
R3 + R4 structural-context chrome (§10.0.12; rf2-e28r3 removed the
former `:full-with-diff?` opt — there is no separate flag).

**Canonical `data-*` shape** (Cluster F — rf2-xvu24): every
surface's enclosing section still carries
`data-rf-xray-diff-mode="full+diff"` so browser-test selectors +
DOM probes can pin "this section renders FULL+DIFF" against a
uniform axis. The attribute is now a constant rather than a
toggle-driven value; kept for selector compatibility with
e2e specs that pre-date the retirement.

**Retired by rf2-vv3m6** (2026-05-29):

- The three-mode toggle widget itself
  (`day8.re-frame2-xray.views.diff-mode-toggle`).
- The four per-surface sub + event + slot trios
  (`:rf.xray.epoch/db-diff-mode`,
  `:rf.xray.epoch/subs-value-diff-mode`,
  `:rf.xray.app-db/diff-mode`,
  `:rf.xray.machine-inspector/diff-mode`, plus the matching
  `set-*-diff-mode` events).
- The `:diff` flat-row lens body in App-DB (`flat-diff-body`) and
  Machine Inspector (`snapshot-flat-diff-body`) — FULL+DIFF carries
  the same conveyance via the edn-inspector widget.
- The HANDLER `:db` section's `db-diff-line` flat-row renderer.
- The discoverability label `View` (rf2-fytu4) — no toggle, no label.
- The shared `reg-mode-sub+event!` helper.
- The `panel-top-bar-style` chrome on the App-DB panel + the
  `mode-toggle-bar-style` / `mode-toggle-button-*-style` hoists in
  the Epoch view layer.
- The `:rf.xray.epoch/subs-value-diff-mode` testbed gallery
  (`gallery_diff_mode_universal.cljs`) + its inventory smoke
  cascade-B test.

**Retired earlier by rf2-yqjrd** (2026-05-27, still applies):

- The Machine Inspector's prior Before/After CSS-Grid side-by-side
  layout (rf2-3d987 issue #3 chrome). FULL+DIFF carries the same
  meanings — full AFTER state + diff context + comparison — in a
  single unified mount via the R1-R8 grammar.
- Per-section auto-routing in App-DB (the `:before` sentinel
  branch). Replaced by FULL+DIFF as the single rendering.

**Single canonical diff engine** (rf2-xuyac, 2026-05-27): every
diff surface (App-DB panel · Machine Inspector snapshot · Epoch
HANDLER `:db` · Epoch SUBSCRIPTIONS) routes through the canonical
Editscript-A* engine at `day8.re-frame2-xray.diff.engine/project`
and consumes the same `:flat-rows` channel. Same `(before, after)`
→ same `:flat-rows` → same chrome → identical R-rule application
across every surface.

#### §9.1.5.3 SUBSCRIPTIONS leaf-scalar value cell — three-way fork (rf2-fyd8u + rf2-o77z4)

A leaf-scalar sub return (number / string / keyword / nil — no
container children for the edn-inspector's R1-R8 grammar to paint on)
has its change signal surfaced at the SUBSCRIPTIONS **row** level,
since the inspector's leaf surface carries no per-leaf annotation
hook. `subs-value-cell` (`panels/epoch/view.cljs`) forks three ways on
`(changed?, first-run?)`, each mirroring the corresponding app-db
edn-inspector leaf chrome so a sub leaf reads identically to an app-db
leaf:

| Row state | Chrome | Glyph | Annotation | Mirrors |
|---|---|---|---|---|
| **Unchanged** (`changed? false`) | **Current value, NO diff chrome** (no wash, no stripe) | — | — | app-db `:same` leaf |
| **First-run** (`changed? true`, `first-run? true`) | `:added` — green stripe (`:diff-added-stripe`) + wash (`:diff-added-wash`) | `+` | none (no prior value) | app-db R1 `:added` leaf |
| **Changed** (`changed? true`, `first-run? false`) | `:modified` — yellow stripe (`:diff-modified-stripe`) + wash (`:diff-modified-wash`) | `~` | `← was <prev>` (prev via `ei/mini`) | app-db R1 `:modified` leaf |

> rf2-o77z4 (Mike pair 2026-06-01) made two corrections. (1) The
> UNCHANGED row now renders the CURRENT value with no diff chrome —
> REVERSING the prior 2026-05-27 "empty cell = unchanged indicator"
> design (rf2-fqcdd follow-up). Row density is governed by the
> `[all][changed][unchanged]` filter (rf2-tzmmf), so showing values on
> unchanged rows is no longer a density cost. (2) The CHANGED leaf,
> previously rendered as a bare inline-flex (no wash / no stripe / no
> glyph), now mirrors app-db's `:modified` leaf chrome — yellow
> stripe + wash via the reserved `:diff-modified-*` token family + a
> leading `~` glyph — bringing it to parity with the already-mirrored
> `:added` (first-run) branch. The glyph paints in `:diff-gutter`,
> the same reserved gutter tone the inspector uses for added /
> removed / modified leaves (`op-gutter-colour`).

The CONTAINER (map / vector / set) value-cell branch is unchanged by
rf2-o77z4: a changed container threads `:before` (full R1-R8 grammar);
a first-run container threads `:added?` (§10.0.13); an unchanged
container mounts plain (current value, no diff opts).

### §9.1.6 Numbered cascade chrome

Per the bead body's §Visual Structure:

- Container: padding `21px`, overflow auto, full height.
- Pipeline: left margin `55px` to accommodate numbered circles.
- Vertical line: absolute-positioned, 1px width, starts at `13px`
  from top, positioned at `-34px` from the content column's left
  edge.
- Row spacing: `13px` vertical gap between entries.

Each step's row carries:

1. Numbered circle: `21px` diameter, positioned at `-44px` left,
   colour-matched to the step's badge.
2. Badge pill: uppercase 10px font, rounded, `5px/3px` padding.
3. Verb/label: monospace, click-to-source hyperlink for any
   source-bearing target (cofx ids, handler flavour, flow ids,
   fx ids, sub vectors, view ids). The HANDLER verb's specific
   shape — verb-as-link + external-link glyph + graceful fallback —
   is the contract in §9.1.6.1.
4. Duration: right-aligned, muted, monospace (e.g. `0.1ms`).
5. Per-step body content: code blocks, tables, diff displays.

Fibonacci spacing system (3 · 5 · 8 · 13 · 21 · 34 · 55 · 89) drives
every gap / pad value. Tabulated in `panels.epoch.badge/fib` for one
source of truth.

#### §9.1.6.1 HANDLER source affordance (rf2-ehd8v · rf2-80u5a · rf2-xjgdk · pair-debug 2026-05-26)

The HANDLER step's verb label (`reg-event`, the one public event-registration
form after EP-0018; `reg-machine` for machine handlers) IS the
click-to-source affordance —
the verb itself is the hyperlink, with an external-link glyph (↗)
trailing inside it. The earlier rf2-ehd8v shape parked a separate
`file:line + [open]` sub-header below the HANDLER header; Mike's pair-
debug commit `ee9def224` (2026-05-26) collapsed that sub-header into
the verb so the affordance is read inline with the cascade rhythm
(one link per step, no second-line chrome).

**Contract** — implemented by `handler-verb-link` in
`tools/xray/src/day8/re_frame2_xray/panels/epoch/view.cljs`:

- **Source-coord read.** Pulls `(rf/handler-meta :event event-id)` for
  ALL flavours — including `:reg-machine` (rf2-ge6uj ISSUE 1). A machine
  is registered as an `:event` handler carrying `:rf/machine? true`, so its
  registration meta (with the top-level `reg-machine` call-site `:file` /
  `:line`) lives under the `:event` kind. There is NO `:machine`
  registrar kind (`registrar/kinds` is the closed ten `:event :sub :fx
  :cofx :view :frame :route :head :error-projector :flow` — and per
  rf2-ftrcv no `:machine-guard` / `:machine-action` either); the prior
  `(rf/handler-meta :machine event-id)`
  resolved nil, so the machine EVENT HANDLER painted the glyph-less plain
  span. Reading under `:event` surfaces the call-site coord so the
  machine EVENT HANDLER carries the same `↗` glyph a plain event does.
  Coord shape: `{:file <string> :line <int>}` (the registrar-meta
  surface; NOT a trace read).
- **Clickable when** `(:file coord)` is non-empty — renders a
  `<button>` carrying the flavour label + lucide `external-link`
  glyph. Underlined-dotted in the accent tone so the hyperlink reads
  as an affordance without crowding the step header.
- **Plain coloured span** otherwise — accent-tone label only, no
  glyph, no button. Graceful degradation for production builds (meta
  stripped under elision) and fn-form registrations where the
  registrar never captured `{:file :line}`.
- **Click dispatch.** `[:rf.xray/open-in-editor {:source-coord
  <coord>}]` on the `:rf/xray` frame envelope. Click handler calls
  `.stopPropagation` so the verb-link doesn't trigger the row's own
  expand-toggle. URI resolution + `Location.assign` happens
  downstream in the `:rf.editor/open` reg-fx via the rf2-vwcsq
  scheme denylist (rf2-ox357n).
- **Test surface.** `data-testid` is
  `rf-xray-epoch-handler-verb-link` (clickable variant) or
  `rf-xray-epoch-handler-verb-plain` (fallback). The `aria-label` +
  `title` carry `open <file>:<line> in editor`.

**Composition with the testbed-boot precondition (rf2-2c5xb).** The
`:rf.xray/open-in-editor` event resolves the file path against
`:rf.xray/project-root`. Testbeds that host the panel-gallery (or any
xray-instrumented surface) MUST seed `:rf.xray/project-root` at boot
via `xray-config/configure!` (with a query-string override slot) —
without it the chip ships bare classpath-relative paths that don't
resolve to the operator's filesystem. The verb-link's event payload
is unchanged by this requirement; it is the downstream URI builder
that consumes `:rf.xray/project-root`.

#### §9.1.6.2 Shared `coord-chip` component (rf2-xjgdk audit L2 · `panels/shared/coord_chip.cljs`)

The HANDLER verb-link is the panel's primary source affordance, but
other Epoch surfaces (and the Event-detail panel) still ride an
**icon-only chip** — a `<button>` carrying just the `external-link`
glyph, no inline label. rf2-xjgdk extracted the previously-duplicated
private chip from `panels/epoch/view.cljs` + `panels/event_detail.cljs`
into a single canonical home at
`tools/xray/src/day8/re_frame2_xray/panels/shared/coord_chip.cljs`.

**Contract.**

- **Public fn.** `coord-chip coord testid` (or `coord-chip coord
  testid opts`). Returns `nil` when `coord` lacks a `:file` — call-
  sites drop the chip cleanly without conditional wrapping.
- **Click dispatch.** Identical to the verb-link —
  `[:rf.xray/open-in-editor {:source-coord coord}]` on `:rf/xray`.
- **Pixel knobs.** Two options surface per call-site (the only knobs
  that varied across the duplicated chips before extraction):
  - `:color` — defaults `"inherit"` (Epoch idiom, chip rides the
    parent text colour). Pass `(:accent tokens)` for the Event-
    detail idiom (chip stands alone in a `:text-primary` row).
  - `:margin-left` — defaults `"4px"` (Epoch). Pass `"6px"` for the
    Event-detail's slightly wider tap target.
- **Style hoist.** `chip-style-base` is an ns-top immutable map
  reused across every render; per-call knobs land via a tiny `assoc`
  overlay (rf2-xjgdk audit F4 hoist).
- **Accessibility.** Native `<button>` (Enter / Space activate),
  `aria-label "open in editor"`, inline SVG `aria-hidden`.
- **SUBSCRIPTIONS call-sites (rf2-aesni).** The active SUBSCRIPTIONS
  table's sub-name cell mounts `coord-chip` (testid
  `rf-xray-epoch-sub-row-coord-<i>`), exact parity with the
  disposed-subs (`…-sub-disposed-row-coord-<i>`) + UNMOUNTED VIEWS
  rows. The coord lookup keys off the row's `sub-id` keyword even
  when a parameterized `sub-vec` (`[:counter/greater-than? 5]`)
  drives the displayed label, so the chip resolves the sub's
  REGISTRATION coord. Pre-fix this cell rendered a bare decorative
  `external-link` glyph with no coord resolution + no click handler.
- **SUBSCRIPTIONS `inputs` column (rf2-87c8a + rf2-e3acps).** The middle
  column of the active SUBSCRIPTIONS table is TWO-LEVEL. For a `:static`
  (`:<-`) sub it shows the STATIC input topology — the sub-ids of its
  registered `:input-signals`, resolved by the SUB-ID off
  `(rf/handler-meta :sub <sub-id>)` via the `sub-input-signals` helper
  (parity with the `sub-coord` lookup, which also keys by sub-id).
  `:input-signals` is registered on the sub-id, so every parameterized
  instance (`[:standard-epochs/greater-than? 5]`) shows its REAL input sub
  (`chain-root`) regardless of whether it re-ran inside a cascade this
  epoch. For a `:parametric` (`input-fn`) sub `sub-input-signals` returns
  nil (the static topology has no enumerable edge set — `:input-kind
  :parametric`, empty `:input-signals`), so the cell DEFERS to the row's
  REALIZED `:inputs` slot, which carries the concrete `(input-fn query-v)`
  edges from the `:rf.sub/inputs` trace tag. This renders the REALIZED
  parametric edges in the live/cascade view without fabricating a static
  set. Each input sub-id / query-vector paints through
  `edn-inspector/mini`. The `app-db` label renders ONLY for a genuine
  Level-1 reader (`:input-kind :db`, empty `:input-signals`). Pre-fix the
  column read the row's `:inputs` slot,
  which the projection sourced purely from the `:rf.sub/cause-sub`
  cascade attribution — OMITTED outside an in-flight cascade, so a
  fresh-run derived/parameterized sub fell through to the `app-db`
  fallback and was mislabeled a Level-1 reader. (A runtime with no
  captured meta but a cascade-attributed `:inputs` slot still paints
  that upstream sub as a graceful fallback for replayed traces.)

  **Row `:inputs` slot shape — uniform vector-of-query-vectors (rf2-nlraqq).**
  The projection's `:inputs` row slot is ALWAYS a vector OF query-vectors,
  regardless of which trace source fed it. `:rf.sub/inputs` is already that
  shape (the literal `:<-` list / realized `(input-fn query-v)` edge set).
  `:rf.sub/cause-sub`, by contrast, is a SINGLE query-vector — the one
  upstream input whose value drove this recompute — so the projection
  WRAPS it as `[cause]`. The view's inputs cell iterates the slot as a list
  of query-vectors, rendering one `edn-inspector/mini` per ENTRY. Without
  the wrap, a PARAMETERIZED cause-sub (e.g. `[:article/by-id :a1]`) would be
  iterated element-wise — `:article/by-id` and `:a1` rendered as TWO
  separate inputs instead of one query-vector.
- **SUBSCRIPTIONS `caused by <event-id>` cell (rf2-1cc03).** Below the
  sub-name + `coord-chip`, the same sub-name cell carries a CONDITIONAL
  `caused by <event-id>` chrome — a `<div>` (testid
  `rf-xray-epoch-sub-row-cause-event-id-<i>`, attr
  `data-rf-xray-subs-cause-event-id`) holding a `caused by` label plus
  the dispatching cascade's trigger event-id, which routes through
  `edn-inspector/mini` (the same `ei/mini` the sub-id above rides) so
  the keyword paints the same magenta syntax-token chrome. It NAMES which event invalidated this
  sub's reactive input — the same attribution the VIEW step surfaces via
  `:rf.view/cause-event-id`. The cell is OMITTED (not an empty
  placeholder) when the row carries no `:cause-event-id` — a sub that
  ran outside any in-flight cascade has no event attribution. The
  projection threads the slot via a `cond->` assoc so the row key stays
  absent in that case, parity with the OMIT-vs-nil semantics of the
  `:rf.sub/cause-event-id` trace tag (rf2-okz1u).
- **FX-step call-sites (rf2-g1mfc).** Each FX-step row's fx-id mounts
  `coord-chip` (testid `rf-xray-epoch-fx-row-coord-<i>`), exact parity
  with the SUBSCRIPTIONS rows + the HANDLER verb. The coord lookup
  keys off the row's `fx-id` and resolves the `reg-fx` REGISTRATION
  coord via `(rf/handler-meta :fx <fx-id>)`. The `:file` is ABSOLUTE:
  `reg-fx` registers through the same `defreg-macro` → `coords-form`
  path that `reg-sub` / `reg-event` use, so it is absolutised at
  macro-expansion time (rf2-wvsxg) — no error-coords fallback needed
  (unlike the VIEW case, rf2-quir9, where `reg-view` skips it).
  Pre-fix the FX row carried NO open-code affordance at all, leaving
  the source-link grammar inconsistent across pipeline steps. The
  chip drops out cleanly for framework-shipped fx with no user source
  (`:dispatch`, `:db`, …) and in production builds without coords.

**`coord-chip` vs `open_in_editor/open-chip`** — two surfaces co-
exist by design. `coord-chip` (the dispatch-based button used inside
xray panels) routes through the trace bus so the click is observable
as a first-class operation on `:rf/xray`. `open_in_editor/open-chip`
is a SEPARATE surface that renders an `<a href="…">` anchor with the
URI pre-resolved against `editor-uri/editor-uri`; it is used by demo
surfaces + the standalone static page where no trace bus is
available. (rf2-evgf5 / rf2-g5q8d decision.)

##### §9.1.6.2.1 Companion `coord-link` (rf2-vw5pi · `panels/shared/coord_link.cljs`)

Where `coord-chip` is the **icon-only** affordance (a glyph appended
after an id), `coord-link` is the **label-as-link** companion — the
verb / label TEXT itself is the hyperlink, with the `external-link`
glyph appended (`reg-event ↗`). Before rf2-vw5pi this shape was
hand-rolled ~11 times across `panels/epoch/view.cljs` (the HANDLER
verb-link, the DISPATCH source label, the COEFFECT / FLOW verb ids,
the machine cascade verb-link + state-path, the schema-violation
action + inline prose link) plus the `↗` table-cell affordances in
`panels/issues_ribbon.cljs` + `panels/trace.cljs`, each re-inlining
the same `[:button {:on-click (rf/dispatch [:rf.xray/open-in-editor
…])} …]` boilerplate + its own `clickable?` / plain-fallback branch.

**Contract.**

- **Public fns.** `coord-link coord label testid` (or `… testid
  opts`). Renders a `<button>` carrying `label` + the glyph when
  `coord` has a `:file`; degrades to a plain `<span>` (no dead button)
  otherwise. `open-in-editor!` is the shared dispatch action — the ONE
  `[:rf.xray/open-in-editor {:source-coord coord}]` dispatch every
  panel-side affordance funnels through (incl. `coord-chip`, the
  `↗` table cells, and the Reactive panel's SVG-node clicks which bind
  it to `:on-click` directly because they are `<g>`/`<rect>` nodes, not
  `<button>` chips).
- **Opts.** `:style` (the button chrome), `:plain-style` (the no-coord
  span chrome), `:glyph-leading?` (glyph BEFORE the label — the
  schema-violation grammar), `:glyph?` (default true; `false` for a
  pure inline text link inside prose, e.g. the `schema check` link).
- **Per-site resolvers stay.** The coord RESOLVERS (`sub-coord`,
  `view-coord`, `machine-state-path-coord`, the cofx / flow
  `handler-meta` lookups, …) are legitimately per-site and remain at
  their call sites — only the button + dispatch + fallback RENDERING
  is shared.

**Consolidation invariant + guard.** Exactly two shared panel-side
click-to-source helpers exist (`coord-chip` icon-only · `coord-link`
label), and no panel file re-inlines the open-in-editor dispatch. A
JVM source-text guard
(`tools/xray/test/.../panels/click_to_source_consolidation_test.clj`)
asserts the `dispatch [:rf.xray/open-in-editor …]` CALL appears ONLY
in `coord_chip.cljs` + `coord_link.cljs` — a future hand-rolled button
trips it. (The guard keys on the dispatch-CALL shape, not the bare
keyword, so docstrings / comments naming the event — and the
`open_in_editor.cljs` reg-event receiver + static-page `<a href>`
anchor, excluded by design — do not false-positive.)

#### §9.1.6.3 DISPATCH source-kind enrichment (rf2-5qp4g · consuming rf2-ejtpd)

The DISPATCH step's source label adapts to the closed-set substrate-
internal `:source` values introduced in rf2-ejtpd (`:after-timer`,
`:always`, `:machine-spawn`, `:fx-dispatch`, `:fx-dispatch-later`).
Each kind paints richer chrome than the bare `from <source>` label
the pre-rf2-5qp4g renderer produced for every value — the operator
reads the specific timer/delay/state-path, the spawned actor's
identity, or the parent-epoch link in the same numbered-cascade
rhythm without leaving the panel.

**Per-source label inventory.**

| `:source`            | Rendered label                                                | Click affordance |
|----------------------|---------------------------------------------------------------|------------------|
| `:after-timer`       | `from :after timer · 250ms on [:active :authenticating]`      | the state-path is click-to-source on the state-node's co-located `:source-coords` (rf2-vqja2); falls through to a plain monospace span when no coord captured |
| `:machine-spawn`     | `from machine spawn · :child-actor-id`                        | none (the actor-id is gensym'd; resolving its spec is a follow-on enrichment) |
| `:fx-dispatch`       | `from fx :dispatch · parent epoch #142`                       | the parent-epoch chip is click-to-navigate via `:rf.xray/focus-epoch <epoch-id>` (registered in `spine.cljs`'s `install!` per rf2-5qp4g) |
| `:fx-dispatch-later` | `from fx :dispatch-later · 500ms · parent epoch #142`         | same parent-epoch navigation; the `500ms` chip surfaces when the trace carries the optional `:rf.event/source-detail :ms` tag |
| `:always`            | `from :always`                                                | defensive — `:always` rides the microstep trace, not `:rf.event/dispatched`; the renderer covers it so the closed set is fully exhaustive |
| `:ui` / `:frame-init` / `:test-harness` / `:unknown` | `from <source>` (unchanged from §9.1.6.1) | the source word itself is click-to-source on the dispatch call-site (rf2-80u5a) |

**Projection.** `dispatch-row` (in `panels/epoch/projection.cljc`)
attaches a `:source-enrichment` map to the row whose shape depends
on `:source` — `{:machine-id :delay-ms :source-state-path}` for
`:after-timer`, `{:spawned-actor-id}` for `:machine-spawn`,
`{:parent-dispatch-id :delay-ms?}` for the fx-dispatch variants.
Vanilla sources carry no enrichment; the renderer falls through to
the pre-rf2-5qp4g call-site link.

**Parent-epoch resolution.** The view layer resolves
`:parent-dispatch-id → :parent-epoch-id` against a precomputed
`{dispatch-id → epoch-id}` index (rf2-x25e0 — built once per render
via `proj/dispatch-id->epoch-id-index` over the Xray `:epoch-history`
slice and threaded through `pipeline-view`'s `ctx` as
`:dispatch-id->epoch-id`; `(proj/find-parent-epoch index
parent-dispatch-id)` is an O(1) lookup). When
the parent epoch is in the buffer the chip renders as a clickable
`<button>` carrying `parent epoch #N`; when not (root cascade, or
the parent was evicted from the ring) the chip renders as a muted
`<span>` reading `parent dispatch #N (not in buffer)` so the
operator still sees the lineage.

**Test surface.** Per-kind `data-testid` slots:

  - `rf-xray-epoch-dispatch-source-label` (the source-label root,
    present for every source kind)
  - `rf-xray-epoch-dispatch-after-timer-delay` /
    `rf-xray-epoch-dispatch-after-timer-state-path` (`:after-timer`)
  - `rf-xray-epoch-dispatch-machine-spawn-actor` (`:machine-spawn`)
  - `rf-xray-epoch-dispatch-fx-later-delay` (`:fx-dispatch-later`
    delay chip)
  - `rf-xray-epoch-dispatch-parent-epoch-link` /
    `rf-xray-epoch-dispatch-parent-epoch-unresolved` (fx-dispatch
    parent-epoch chrome — clickable variant + muted-unresolved
    variant)

**Trace stamp contract (substrate side).** The framework stamp
sites that feed the enrichment live in `implementation/core/src/
re_frame/fx.cljc` (`:dispatch` / `:dispatch-later` reserved-fx
handlers stamp `:source :fx-dispatch` / `:source :fx-dispatch-later`
on the child envelope; when the emitting parent is a machine handler
they stamp `:source :machine-action` instead — the actor-message
path, per rf2-c3990 — with the `:rf.event/source-detail {:ms ms}`
tag riding on the dispatched trace for `:dispatch-later` and the
machine-action `:dispatch-later` variant alike),
`implementation/machines/src/re_frame/machines/timer.cljc` (the
`:after` timer's `dispatch!` stamps `:source :after-timer`), and
`implementation/machines/src/re_frame/machines/lifecycle_fx/spawn.cljc`
(the spawn fx stamps `:source :machine-spawn`). The substrate-side
contract is the closed set documented in §9.1.10.1's table.

#### §9.1.6.4 Machine-event EVENT HANDLER orientation line (rf2-akvfe, supersedes rf2-18oe3)

A re-frame2 machine **is** an event handler addressed by its id
(`reg-machine :door/main spec` ≈ `reg-event :door/main …`);
dispatching `[:door/main [:door/close]]` routes the **inner trigger**
`[:door/close]` through the machine's `:on` map. The raw event vector
reads as opaque nesting — `[:machine-id [inner-trigger]]` — so the
operator needs orienting: *which* machine, on *what* trigger, from
*what* starting state.

**Superseded design (rf2-18oe3 DISPATCH gloss — REMOVED).** The prior
design rendered a muted italic sub-line under the **DISPATCH** step
(*"this means the machine `:door/main` received the trigger
`:door/close`"*). rf2-akvfe removes it. The narration moves to the
**EVENT HANDLER** step — a better location (it is the handler that
processes the trigger) and a more scannable shape that also carries the
**starting STATE** the gloss never showed.

**The orientation line.** Immediately under the EVENT HANDLER heading,
ONE structured line (rf2-2hj0h item 4 — the leading "Processing" word is
DROPPED; the chips + values are self-orienting):

> `[TRIGGER]` *‹trigger-vector›* for `[MACHINE]` *‹machine-id›*
> in `[STATE]` *‹pre-transition-state›*

where `[TRIGGER]` / `[MACHINE]` / `[STATE]` render as small **grey
chip-labels** and the values follow each chip, **code-formatted** (mono):

| Slot        | Value                                                                |
|-------------|----------------------------------------------------------------------|
| `[TRIGGER]` | the full inner trigger event vector incl. args, e.g. `[:door/close]` |
| `[MACHINE]` | the machine id, e.g. `:door/main`                                    |
| `[STATE]`   | the machine **pre-transition** logical state, e.g. `:closed`         |

**Start special case.** A pure `[:rf.machine/start]` creation kick — per
F‴ (rf2-gl588) it runs the initial-entry cascade then STOPS (a pure
init-kick — xstate's `createActor(m).start()` / `xstate.init`), **not** a
real trigger (see
`ai/findings/2026-06-03.machine-creation-bootstrap-review.md` §1) —
produces a cascade with only a `[START]` row (no transition / no-op), so
the orientation line is **suppressed**: the birth story rides the
`[START]` cascade row, not an orientation line.

**Projection.** `projection/machine-event-orientation` (pure-data,
`panels/epoch/projection.cljc`) reads the orientation triple directly off
the already-projected cascade rows — `:trigger` (the `:transition` / `:no-op`
row's `:event` tag, the inner trigger), `:machine-id` (the row's
`:machine-id`, backstopped by the HANDLER step's `:event-id`), `:state`
(the `:transition` row's `:from-state`, else the `:no-op` row's `:state`).
Returns `nil` for a cascade with no transition / no-op row (a pure creation
kick, or a non-machine handler). `fmt/orientation-value` formats each value
(keyword → `ns-keyword`; vector → `pr-str`; `nil` → em-dash).

**Test surface.** `data-testid` `rf-xray-epoch-event-handler-orientation`
(the line; absent for a pure creation kick) + the per-value testids
`…-orientation-trigger` / `…-orientation-machine` / `…-orientation-state`.

### §9.1.7 Composition with the edn-inspector widget

Per the bead body's §Implementation Notes, expandable rows mount the
edn-inspector widget with `:zoomable? true` (rf2-h71e0) and
`:header "<step-name>"` (rf2-okq7p) so nested data drill-down composes
naturally with the §10 widget's contract. The initial landing of
the panel renders default-visible content for every step (the
cascade's punch is its always-visible rhythm); per-row drill-down
state lives on the `:rf.xray/epoch-panel-expanded-rows` set via the
`:rf.xray.epoch/toggle-row-expand` event, ready for the follow-on
rich-expansion pass.

### §9.1.8 Pure-data projection (testable in isolation)

The projection lives at `tools/xray/src/day8/re_frame2_xray/panels/
epoch/projection.cljc` — `.cljc` so JVM unit tests (`clojure -M:test`)
exercise the algebra without spinning a CLJS runtime. The view
layer (`panels/epoch/view.cljs`) consumes the projection's output
verbatim; no DOM concern bleeds into the data layer.

The view-presentation string formatters (`format-duration-ms`,
`event-display`, `ns-keyword`, `cascade-row-label`,
`cascade-outcome-label`, `cascade-row-source-key`,
`handler-flavour-label`, …) live in the sibling
`panels/epoch/format.cljc` (rf2-qkygs) — also `.cljc` and JVM-testable
— so the projection ns stays the pure step-derivation engine and the
presentation boundary reads as one named ns the view imports.

### §9.1.9 Queries

| Sub | Reads | Yields |
|-----|-------|--------|
| `:rf.xray/epoch-pipeline` | `:rf.xray/focus` · `:rf.xray/epoch-history` (via the shared `panels.shared.focus-resolver`) | `{:status :no-focus | :focused | :epoch-evicted, :epoch-id, :record, :steps, :outcome :ok｜:error}` — `:outcome` is the rf2-ahhgn tool-side outcome (`projection/epoch-outcome`; `:error` when any step carries an exception or violation), NOT the framework epoch-record slot (§9.1.10.5) |
| `:rf.xray.epoch/expanded-rows` | `:epoch-panel-expanded-rows` slot | `#{[step-kw row-id] …}` |
| `:rf.xray.epoch/subs-filter-mode` | `:epoch-panel-subs-filter-mode` slot | keyword `:all / :changed / :unchanged` (rf2-tzmmf — SUBSCRIPTIONS step's `[all][changed][unchanged]` button-bar; supersedes rf2-kfh1v's boolean `subs-show-unchanged?`. Default `:changed` preserves the rf2-kfh1v hide-unchanged-by-default rationale) |

### §9.1.10 Events

| Event | Effect |
|-------|--------|
| `:rf.xray.epoch/toggle-row-expand step-kw row-id` | flip the row's pair in `:epoch-panel-expanded-rows` |
| `:rf.xray.epoch/clear-row-expand` | drop the expansion set |
| `:rf.xray.epoch/set-subs-filter-mode mode` | set the SUBSCRIPTIONS filter mode (rf2-tzmmf — closed set `:all / :changed / :unchanged`; supersedes rf2-kfh1v's `toggle-subs-show-unchanged`. The button-bar replaces both the prior boolean toggle AND the badge-adjacent `N recomputed (M changed, K unchanged)` summary text — Mike pair-debug 2026-05-26: no coexistence, pre-alpha posture) |
| `:rf.xray/focus-epoch epoch-id` | pivot the spine's `:rf.xray/focus` to the supplied epoch-id (rf2-5qp4g). Resolves the matching record's settling `:dispatch-id` via `spine/dispatch-id-for-epoch` and defers to `focus-cascade-reducer`. Drives the DISPATCH step's parent-epoch navigation chip on `:fx-dispatch` / `:fx-dispatch-later` dispatches (§9.1.6.3). When the epoch-id has no matching dispatch-id in the buffer (trace elided, record-only fixture), the event still pins `:focus :epoch-id` so panels pivoting on it (App-DB diff, Views, Machine Inspector) follow the navigation; the cascade's `:dispatch-id` resolves on the next live tick. |

### §9.1.10.1 Substrate tag dependencies (the trace-stamp contract)

The projection is a pure-data fn over the focused epoch's `:trace-events`.
Each step row reads ONE OR MORE substrate-emitted tags; the panel only
renders the slot when its driving tag is present. The 6-bead bug-fix
sweep (2026-05-26) aligned every projection read with the substrate's
canonical tag names — earlier reads against legacy names returned nil
and silently rendered empty rows. The binding inventory:

| Step | Trace operation | Canonical tags |
|------|-----------------|----------------|
| `:dispatch` | `:rf.event/dispatched` | `:rf.event/v` (event vector — rf2-93a7s), `:source` (closed set per rf2-hxj0d + rf2-ejtpd + rf2-c3990; substrate-internal values drive the §9.1.6.3 per-kind enrichment), `:rf.trace/call-site`, `:rf.trace/parent-dispatch-id` (rf2-5qp4g — fx-dispatch parent-epoch link), `:rf.event/source-detail` (rf2-5qp4g — optional per-source-kind detail map; `:dispatch-later` rides `{:ms <delay>}` so the renderer surfaces the original scheduled delay) |
| `:coeffect` | `:rf.cofx/run` (preferred) or `:rf.event/run-end` `:rf.event/coeffects` (fallback) | `:rf.cofx/id`, `:rf.cofx/value` (rf2-sepqgg — the supplier's PRODUCED value, redacted by the cofx's marks; egresses into `:coeffects`), `:rf.cofx/arg` (rf2-sepqgg — the per-call REQUIREMENT ARG, present only for a parameterized `[id arg]` requirement; surfaces on the row as `:input`), `:rf.cofx/elapsed-ms` (rf2-w2r4p aligned the per-cofx duration read against the substrate's canonical name + threaded `:duration-ms` through the `cofx-steps` flattening) — SYSTEM defaults `:db / :event / :frame / :source / :trace-id` are filtered (rf2-cq0ch). The row's `:value` reads the run-end egress (the authoritative `:coeffects` slot), falling back to the run-op's `:rf.cofx/value` when no run-end fired; since rf2-sepqgg the two surfaces AGREE. **Projection splits each surviving cofx into its own numbered step** (rf2-s1jw4 · pair-debug 2026-05-26): `cofx-steps` is a `mapv` over `cofx-rows` producing `{:step :coeffect :badge :COEFFECT :id <kw> :value <produced> :duration-ms <ms>}` (with `:input <requirement-arg>` when a parameterized requirement rode) per entry, spliced into the steps vec before HANDLER. |
| `:handler` duration | `:rf.event/run-end` `:rf.event/elapsed-ms` (rf2-slnce aligned the per-handler duration read against the substrate's canonical name — see `re-frame.router/emit-run-end-trace`) |
| `:handler` source | `(rf/handler-meta :event id)` → `:rf.handler/source` (rf2-66wis · NOT a trace read — registrar meta). The `{:file :line}` coord on the same meta drives the HANDLER verb-as-link affordance (§9.1.6.1 · rf2-ehd8v + pair-debug 2026-05-26). **EVENT handlers only:** a MACHINE handler renders NO HANDLER source block (rf2-4yrr6 — `handler-source-block` returns nil for `:reg-machine`). The machine CASCADE below is the content; the defmachine / reg-machine value stays reachable via the HANDLER verb link (rf2-ge6uj) + the per-element machine-def source-links (rf2-iwy0c). Dumping the whole machine spec via `edn/inspect` under the HANDLER step was noise, and the machine case does NOT fall through to the `<source not yet captured>` placeholder (that slot is for event handlers whose source the substrate didn't stamp). |
| `:handler` machine cascade (rf2-u69j7) | `:rf.machine/guard-evaluated` · `:rf.machine/action-ran` · `:rf.machine/transition` · `:rf.machine.timer/cancelled` (closed set: `machine-cascade-trace-ops`) | guard rows read `:guard-id`, `:outcome` (closed set `:pass / :fail / :threw` — rf2-82a0u); action rows read `:action-id`, `:phase` (closed set `:exit / :transition / :entry / :always / :after-action / :initial-entry / :destroy-exit` — rf2-82a0u), `:outcome` (rich map; `:fx` + `:data` hoisted onto the row), `:input`, `:exception`; transition rows read `:actor-id` (the live actor INSTANCE — rf2-ws5thu / rf2-yyvtk5, `:machine-id` fallback for legacy fixtures), `:event`, `:before`, `:after`, `:microsteps` (state vectors hoisted off `:before`/`:after`); timer rows read `:actor-id`, `:state`, `:delay`, `:reason` (closed set `:on-exit / :on-destroy / :on-resolution / :on-supersede / :on-frame-destroy` — rf2-82a0u) — and, for a `:delay-source :sub` timer, the canonical subscription identity `:rf.sub/id` + `:rf.sub/query-v` (rf2-1b6uh5, not the bare `:sub-id`). The guard / action rows likewise read the live actor under `:actor-id` (rf2-yyvtk5). Source-coord lookup reads `(rf/handler-meta :event id) → :rf/machine`, then the co-located entry `:source-coords` for a named `[:actions <id>] | [:guards <id>]` key, or the `:source-coords` on the nearest enclosing `:states`-tree map node for a reference-site `[:states ...]` key (rf2-vqja2). |
| `:flow` | `:rf.flow/computed` (NOT `:rf.flow/recomputed` — rf2-yhgk8 aligned the read against `re-frame.flows`'s canonical emit) | `:flow-id`, `:path`, `:before`, `:result` (the view-side `:after` slot maps to the substrate's `:result`), `:elapsed-ms` |
| `:fx` | `:rf.fx/handled` / `:rf.fx/override-applied` / `:rf.fx/skipped-on-platform` | `:rf.fx/id`, `:rf.fx/args`, `:rf.fx/elapsed-ms` (rf2-ipaza aligned the duration read against the substrate's canonical name) |
| `:subscriptions` | `:rf.sub/run` / `:rf.sub/skip` | `:rf.sub/id`, `:rf.sub/query-v`, `:rf.sub/value-changed?`, `:rf.sub/prev-value`, `:rf.sub/value`, `:rf.sub/cascade?`, `:rf.sub/cause-sub`, `:rf.sub/inputs` (rf2-e3acps — the REALIZED input query-vectors for this concrete cache entry: the literal `:<-` list for a `:static` sub, the `(input-fn query-v)` result for a `:parametric` sub, `[]` for layer-1), `:rf.sub/elapsed-ms` (rf2-kfh1v aligned the reads against these). **`inputs` column, two-level source (rf2-87c8a + rf2-e3acps):** for a `:static` (`:<-`) sub the view resolves the STATIC input topology via `(rf/handler-meta :sub <sub-id>) → :input-signals` keyed by the SUB-ID (first element of the query-v), so every parameterized instance `[:sub-id arg…]` shows its REAL input sub. For a `:parametric` (`input-fn`) sub the static topology has no enumerable edge set, so the view DEFERS to the row's REALIZED `:inputs` slot (sourced from the `:rf.sub/inputs` trace tag — the concrete `(input-fn query-v)` edges for this cache entry) rather than fabricating a static set. `app-db` is the label only for a genuine Level-1 reader (`:input-kind :db`, `:input-signals` empty). The `:rf.sub/cause-sub` cascade attribution feeds the `caused by <event-id>` chrome (rf2-1cc03); the projection's row `:inputs` slot prefers `:rf.sub/cause-sub` (the single changed input) and falls back to the full realized `:rf.sub/inputs` edge set when no single cause is named (fresh run / parametric first materialize). |
| `:subscriptions` disposed (rf2-wpfjo) | `:rf.sub/dispose` (emitted by `re-frame.subs.cache/emit-dispose!` per rf2-mrnur — every cache eviction site funnels through ONE emit shape) | `:rf.sub/id`, `:rf.sub/query-v`, `:rf.sub/reason` (closed set `:no-more-derefers / :hot-reload / :cache-clear`), `:frame`. Surfaced via `projection/disposed-subs-rows`; the SUBSCRIPTIONS step carries an optional `:disposed-rows` slot (omit-by-absence). The step renders a DISPOSED sub-section when populated and reads `N recomputed (...); L disposed` in its header. A dispose-only cascade (no run/skip) still renders the step. |
| `:views` | `:rf.view/rendered` (NOT the simpler `:rf.view/render` marker) | `:rf.view/id`, `:rf.view/render-key`, `:rf.view/deref-subs`, `:rf.view/elapsed-ms`, `:rf.view/mount?`, `:rf.view/triggered-by`, `:rf.view/render-args` (rf2-6djth aligned the read against the rich marker; rf2-u3lii adds the render-key + render-args reads). The projection derives a `:cause` slot per row from `:rf.view/mount?` + `:rf.view/triggered-by` — `:mount` (first render) / `{:kind :sub :sub-id <id>}` (a deref'd sub changed value) / `:props` (a re-render with no own sub change). **rf2-3b9w4 (Mike pair 2026-06-01) — VIEWS-table redesign; rf2-u3lii adds col-2.** The table is **3 columns: view / render-args / subs.** Col-1 is stripped to the **view NAME** (routed through `ei/mini` — the edn-inspector leaf primitive — so each view row reads as an inspectable data entity, parity with the App-db / subs value cells) + a **mount/re-render GLYPH** (`+` = first mount / `~` = re-render, derived from the row's `:cause`; mirrors the SUBSCRIPTIONS leaf-scalar `+`/`~` glyph idiom) + the **go-to-source coord-chip** (`…-view-row-coord-<i>`). The prior rf2-bhi3t render-cause `← :sub-id` / `← props` chip AND the `:duration-ms` span are **REMOVED** (the col-3 sub colour-code now shows which dereffed sub drove a re-render). **Col-2 render-args DIFF (rf2-u3lii, consuming rf2-rpgq8's `:rf.view/render-args`):** the positional args/props passed to THIS render, rendered as an edn-inspector **DIFF vs the SAME view INSTANCE's PREVIOUS render** (testid `…-view-row-render-args-<i>`). The projection (`projection/view-rows`) retains the previous render's args keyed by `:rf.view/render-key` (the per-instance tuple) — threaded left-to-right over the trace-ordered render events so each row's `:prev-render-args` is ITS OWN instance's prior render this cascade, never a neighbouring view's — and stamps `:render-args` + `:prev-render-args` (both omit-by-absence). The cell mounts the SAME `:before` diff-mode the App-db / subs value cells use (rf2-vv3m6 FULL+DIFF — **reused, not reinvented**): a re-render whose args changed threads `{:before <prev>}` and the inspector paints the R1-R8 grammar on the args-vector's changed elements (a vector is a container — per-element deltas surface directly, so no leaf-scalar row-level chrome is needed unlike the subs cell); unchanged args paint no delta (browse posture); the FIRST render of an instance (`:prev-render-args` absent) mounts plain. A no-arg render (`:rf.view/render-args` absent at the emit site — stamped only `(seq render-args)`) reads `(no args)`. **PRIVACY:** the render-args value is PRIVACY-elided at the substrate emit chokepoint (`re-frame.marks/project-trace-event` routes it through `elide-wire-value` — the identical treatment `:rf.event/db` gets, rf2-rpgq8); sensitive / schema-`:large?` slots land as `:rf/redacted` / `:rf.size/large-elided` before delivery. **SIZE (rf2-yi0nr):** that emit-time walk is SCHEMA-DRIVEN — it only marks slots a schema declares `{:large? true}`. Arbitrary fat props (a big map / collection that no schema marks — which ANY real app passes; the machine-epochs runner's 26-map steps vector is the vivid case) ride through un-elided, AND Xray reads RAW epoch records in-process (the egress walk never touches what Xray sees — see `panels.app-db-diff-helpers` head comment). So the cell ROUTES the args (and the `:before` prev-args the diff annotates against) through `format/elide-large-render-args` before mounting: each top-level positional element whose `pr-str` exceeds `format/render-args-byte-budget` (512 bytes) is replaced by the framework's canonical `{:rf.size/large-elided …}` sentinel — the SAME marker the App-db panel surfaces for large state, which the shared `ei/edn-inspector` renders as the yellow `● large · N bytes` chip. `:reason :size` marks the tool-side, threshold-driven origin (vs the framework's schema-declared `:reason :schema`); the cap is purely DISPLAY (Xray is read-only). Per-element so a small id arg beside a fat props map elides ONLY the fat one. The `data-rf-render-args-diff` attribute (`diff` / `plain` / `none`) carries the posture for tests + the operator. **Col-3 sub colour-code (rf2-3b9w4):** each dereffed sub in the subs cell is GREEN (`:new` — first-run this epoch) / ORANGE (`:changed` — recomputed to a new value) / GREY (`:unchanged`). Status is joined per-sub against the epoch's `subscription-rows` `:first-run?` / `:changed?` (`projection/sub-status-index`, keyed by both sub-vec and bare sub-id). "New" = the sub's cache slot was CREATED this epoch (globally first-run), NOT first-time-this-view-read-it — so the VIEWS colour reads identically to the green `:added` chrome the SUBSCRIPTIONS value cell paints for the same sub. No substrate/instrumentation change — fully tool-side inference. |
| `:views` unmounted (rf2-gmw1i; folded by rf2-3b9w4) | `:rf.view/unmounted` (already emitted by `re-frame.views/emit-view-unmounted!` per rf2-9hoos + rf2-te71r) | `:rf.view/id`, `:rf.view/render-key`, `:frame`. **rf2-3b9w4 (SUPERSEDES the rf2-gmw1i separate UNMOUNTED sub-section)** — `projection/unmounted-views-rows` tags each row `:status :unmounted` + `:unmounted? true`, and `views-step` folds them into the SAME `:rows` collection (rendered rows first, unmounted rows following). The VIEWS table renders an unmounted row inline with a **red strikethrough (`line-through` + `:diff-removed-stripe` / `:diff-removed-wash`, diff-removed posture)** and a `−` red glyph, so the operator reads the epoch's full view delta — what re-rendered AND what tore down — in one scan. The go-to-source coord-chip stays on unmounted rows (the view's definition outlives the torn-down instance). The step carries `:unmounted-count` (M) for the header verb `N re-rendered; M unmounted`. |

### §9.1.10.2 Per-step elapsed time (rf2-nqt3d · rf2-dwuq3)

Each step row carries `:duration-ms` (a number when the substrate
stamped it; nil otherwise). The view paints it as a right-aligned
monospace chip on every step's header — pure-data via
`format/format-duration-ms` (rf2-qkygs extracted the view-presentation
string formatters out of the pure-data `projection` ns into the
sibling `panels/epoch/format.cljc`), which formats `0.1ms` / `12ms` /
`1.2s` per scale.

Per-row predicate driving long-step chrome:

| Helper | Returns | Used for |
|--------|---------|----------|
| `projection/long-step? step` | boolean (`:duration-ms > 16ms`) | per-step warning chrome |

`projection/long-step-threshold-ms` is **16ms** — one display frame
at 60Hz, the natural marker for "this single step will visibly
jank the next paint". Crossing the threshold paints the chip in the
warning tone with a `▲` glyph. Below threshold the chip is muted
with no glyph — alarmist `✗` chrome would crowd the cascade on the
common case where one step is naturally heavy.

The top-of-pipeline `cascade total: <N>ms` summary chip was
retired post-rf2-nqt3d (rf2-dwuq3) — the operator reads heavy
steps from the per-row chips, not the sum. The per-row chip + the
per-row `▲` long-step warning are the cascade's complete timing
surface.

### §9.1.10.3 Violation attachment contract (rf2-17vxj · rf2-xgeag)

> **Reshaped pair-debug 2026-05-27 (rf2-xgeag).** The trailing
> aggregate SCHEMA VIOLATIONS step retired in favour of per-step
> inline attachment. Each violation now renders as a pink-wash
> sub-block INSIDE its owning pipeline step — the operator reads
> the failing boundary alongside the work it failed on rather than
> jumping to a trailing footnote.

The substrate still emits the same two trace ops:

- `:rf.error/schema-validation-failure` — runtime per-boundary
  validation failure (app-db commit / cofx / sub-return / fx-args /
  event payload). Tags: `:where`, `:path`, `:value`, `:failing-id`,
  `:rollback?`, `:explain`, `:sensitive?`.
- `:rf.schema/violation` — hot-reload drift: a re-registration
  changed the schema at `(frame-id, path)` and the live app-db
  value fails the new schema. Tags: `:path`, `:frame`,
  `:pre-reload-schema`, `:post-reload-schema`, `:mismatching-value`,
  `:recovery`, `:sensitive?`.

Both ops project into the same row schema (`schema-violation-row` —
unchanged); only the aggregation moved.

#### Attachment mapping (the new contract)

| `:where` slot   | Owning pipeline step                                | Granularity              |
|-----------------|-----------------------------------------------------|--------------------------|
| `:event`        | DISPATCH                                            | step-level               |
| `:cofx`         | COEFFECT step whose `:id` = `:failing-id`           | step-level (per cofx)    |
| `:app-db`       | SIDE EFFECTS step `:db` row (the handler's app-db write) — per rf2-8resu / rf2-kt6js | row-level         |
| `:machine-data` | SIDE EFFECTS step `:rf.db/runtime` row (the handler's runtime-db partition write — EP-0001 rf2-ff9b0d, the runtime-db sibling of the `:app-db` → `:db` attach, via `attach-to-runtime-db-row`). The runtime-db partition carries durable machine snapshots, so its post-commit boundary is `:where :machine-data`. When the violation triggered full-cascade rollback (`:rollback? true`), the rollback fact is also signalled via the §Rollback blast-radius mute pass; the violation itself stays attached to the runtime-db row. Per rf2-jbbp7 (see [spec/005 §Schema validation](../../../spec/005-StateMachines.md#schema-validation), [spec/010 §Per-step recovery row 7](../../../spec/010-Schemas.md#per-step-recovery)). | row-level (fallback step) |
| `:fx-args`      | SIDE EFFECTS step, row whose `:fx-id` = `:failing-id` | row-level (fallback step)|
| `:sub-return`   | SUBSCRIPTIONS step, row whose `:sub-id` = `:failing-id` | row-level (fallback step) |

Hot-reload drift no longer attaches to a cascade step — it surfaces
via the Issues panel exclusively (rf2-7gf7v retired the standalone
`SCHEMA HOT-RELOAD` tail step in favour of the Issues panel's
richer explanatory chrome).

The projection pass `attach-violations` walks the
`schema-violation-rows` once and binds each row onto its owning
step (or the step's matching row for FX / SUBSCRIPTIONS); step
maps gain a `:violations` slot, row maps gain their own
`:violations` slot. The view's per-step renderers inject
`(violation-blocks step-key violations)` under their primary
body.

#### Sub-block visual contract (post-rf2-2ek7t)

> **Reshaped pair-debug 2026-05-27 (rf2-2ek7t).** The earlier
> seven-discrete-field card (title + chip + headline + path +
> value + two `open` action buttons + collapsed raw explain) read
> as noise — too many fields, none of them prose. The block now
> carries **three pieces** only: title bar, prose sentence with
> inline schema-source link, and a humanized explain map. Every
> retired field is subsumed by one of the surviving three.

Each violation renders as a pink-wash card under its host step
carrying, top to bottom:

1. **Title bar** — `⚠` warning glyph + the literal string `Schema
   Violation Error` (mixed case, no `text-transform: uppercase`)
   in warning colour, followed by a right-aligned recovery chip.
   Recovery-chip text resolves per `:where` (see [§violation-prose-template](#violation-prose-template)
   below for the full per-`:where` table):
   - `:app-db` with `:rollback? true` → `Aborted`
   - `:fx-args` → `Skipped`
   - `:sub-return` → `Returned nil`
   - `:event` → `Rejected`
   - `:cofx` → `Skipped`
   - `:hot-reload` (retired pipeline step; chip still defined for
     the Issues panel) → `logged + skipped`
   - Falls back to the trace's `:recovery` keyword (name-d) when
     no canonical mapping applies.
2. **Prose sentence with inline `schema check` link** — one
   short natural-language paragraph per `:where`, rendered in
   `sans-stack` font (the outer block's monospace inheritance is
   overridden — this slot is prose, not data). The substring
   `schema check` is the inline click-to-source link;
   click dispatches `:rf.xray/open-in-editor` against the
   schema's resolved source-coord. Coord resolution varies by
   `:where`: `:app-db` reads `(rf/app-schema-meta-at path)`
   (per rf2-mg6ya); other `:where` values read
   `(rf/handler-meta :schema failing-id)`. Missing coord →
   the link degrades to plain inline text inside the sentence
   (sentence still reads cleanly). Per-`:where` prose templates
   live in [§violation-prose-template](#violation-prose-template).
3. **Humanized explain map (or raw fallback)** — rendered via
   `ei/edn-inspector` with `:default-expanded-depth 16`
   (every nested level visible on first paint; operator must SEE
   the failure detail, not click to discover it). Reads
   `:explain-humanized` from the projected violation row when the
   substrate's `:schemas/humanize-explain!` late-bind hook is
   installed (see [spec/010 §Humanize-hook](../../../spec/010-Schemas.md#humanize-hook--operator-readable-explain-payload));
   falls back to the raw `:explain` map when the hook is absent
   (non-Malli validator, or framework predating rf2-2ek7t). A
   small `expected: / got: (+N more errors)` decomposition row
   (rf2-zn6u5) renders ABOVE the humanized map when the row's
   `:explain` carries the canonical Malli `{:errors […] :value …}`
   shape — pulls the first error's `:schema` (expected) +
   `:value` (got) as a programmer-friendly summary so the
   operator reads the first-error-prominent line without
   scanning the full humanized tree. Drops out cleanly for
   non-Malli explain shapes.

Trailing **sensitive marker** — when the row carries
`:sensitive? true` the block appends a compact muted-italic
caption (`(value redacted — slot declared :sensitive?)`) so the
operator knows the substrate scrubbed the value at the emit site
(not a humanizer artefact). Cross-reference:
[spec/010 §`:sensitive?` — privacy in schema-validation error traces](../../../spec/010-Schemas.md#sensitive--privacy-in-schema-validation-error-traces).

Retired fields (subsumed):

- The all-caps `⚠ SCHEMA VIOLATION` shouted; mixed-case +
  prose-driven explanation reads as a real diagnostic message.
- The `<where-label> · <failing-id>` headline duplicated what the
  prose sentence states in natural language; dropped.
- Discrete `path:` / `value:` rows are subsumed — `:path` is
  implicit in the host step's location (e.g. an FX `:db` row
  IS the failing path's commit row, post-rf2-8resu); `:value`
  is visible via the humanized explain map's root value.
- The two click-to-source action buttons (`↗ open <failing-id>`
  + `↗ open schema <failing-id>`) collapsed to one click target:
  the inline `schema check` link in the prose. The
  failing-handler coord is reachable from the cascade itself
  (every handler badge in the step header already carries a
  source-jump per §9.1.11), so the second action was redundant.

Background = `:bg-violation` palette token (soft-rose on light,
deep-rose-muted on dark — distinct from `:magenta-pink`
SUBSCRIPTIONS chrome and from the retired aggregate step's
`:warning` amber). Border in `:warning` for cross-theme legibility.

#### Violation-prose-template

The prose sentence's per-`:where` canned text + recovery chip,
both populated by the same `violation-prose` / `violation-recovery-label`
helpers at view time. The substring `schema check` is the inline
click-to-source link in every template (rendered as a `<button>`
when coord resolves, plain `<span>` text otherwise — graceful
degrade keeps the sentence readable):

| `:where`      | Recovery chip   | Prose sentence (`schema check` is the inline link)                                                  |
|---------------|-----------------|------------------------------------------------------------------------------------------------------|
| `:app-db`     | `Aborted`*      | "This value failed a `schema check` and can't be committed to app-db."                              |
| `:fx-args`    | `Skipped`       | "fx aborted because args failed the `schema check`."                                                |
| `:sub-return` | `Returned nil`  | "This sub returned nil because its value failed the `schema check`."                                |
| `:event`      | `Rejected`      | "This event was rejected because its payload failed the `schema check`."                            |
| `:cofx`       | `Skipped`       | "This handler was skipped because the coeffect failed the `schema check`."                          |
| `:hot-reload` | `logged + skipped` | "A schema re-registration invalidated existing app-db state. See `schema check` for the new shape." |
| (fallback)    | none            | "Schema violation. `schema check` for details."                                                     |

\* `:app-db` chip text is `Aborted` only when the violation
carries `:rollback? true` (the runtime aborted the cascade and
rolled `:db` back to its pre-handler value — Spec 010 §Per-step
recovery row 3). The rollback-mute pass (§Rollback blast-radius
mute below) handles the downstream-step opacity overlay.

**Inline link → coord resolution.** The `schema check` link
resolves to the schema's source-coord, NOT the handler's:

- `:app-db` → `(rf/app-schema-meta-at path)` (the
  `:schemas/app-schema-meta-at` late-bind hook per rf2-mg6ya)
  reads the registration meta the schemas artefact stamped on
  `reg-app-schema`. Returns `{:file :line}` or nil.
- `:fx-args` / `:sub-return` / `:event` / `:cofx` →
  `(rf/handler-meta :schema failing-id)` — the registration meta
  carrying the `:schema` slot is the click target.
- Coord missing (registration wasn't stamped, or `handler-meta`
  unavailable) → the prose still renders, with `schema check`
  as plain inline text rather than a clickable affordance.

**Expected / got decomposition row.** When the row's `:explain`
matches the canonical Malli shape (`{:errors [{:schema … :value …}
…] :value <root>}`), the projection's `decode-malli-explain` extracts
a tight two-line summary and stamps it on the row's `:decoded` slot;
the view reads that projected field and renders it ABOVE the humanized
map:

```
expected: [:map [:user :string]]
got:      {:user 42}
(+2 more errors)
```

The `(+N more errors)` chip surfaces only when `:errors` carries
more than one entry (`max 0 (dec (count errors))`). Non-Malli
explain shapes return nil from the decoder; the decomposition
row drops out cleanly and the humanized / raw map renders alone.

#### Rollback blast-radius mute

When the cascade carries an `:app-db` violation with
`:rollback? true`, the projection's
`mark-rolled-back-downstream` pass flags every step AFTER the
SIDE EFFECTS step with `:rolled-back? true`. The view's pipeline
wrapper applies a `:opacity 0.55` overlay to those steps. The SIDE
EFFECTS step itself stays visible — the violation rides on its `:db`
row (per the attachment table above, post-rf2-8resu / rf2-kt6js) so
the operator reads the failing commit inline. The operator sees the
blast radius at a glance instead of reading downstream rows that
claim success for fx that never actually fired.

#### What does NOT change

- Substrate trace op shapes (`:rf.error/schema-validation-failure`
  + `:rf.schema/violation`) — same.
- Per-row data projected from those ops (`schema-violation-row`
  + `schema-violation-rows`) — same.
- The Issues panel's cross-session list — same.
- Spec 010 (Schemas) boundary contract — unchanged; the
  cascade-side presentation changes, the underlying contract is
  identical. See spec/010 §Tooling surface — Xray attachment for
  the cross-reference.

Sections / step are conditional — `:violations` slot is absent
when no violation attached to that step. Hot-reload drift no
longer rides any pipeline step (rf2-7gf7v); it surfaces in the
Issues panel only.

### §9.1.10.4 Inline EXCEPTION attachment + per-step status (rf2-ahhgn)

> **Motivation.** A handler / interceptor / coeffect / fx / flow
> EXCEPTION (distinct from a schema VIOLATION) leaves a
> `:rf.error/*` cascade trace but, pre-rf2-ahhgn, surfaced
> NOWHERE in the Epoch panel — the cascade rendered as if it ran
> clean. Clicking button-15 (`:standard-epochs/throw-handler`) showed
> nothing explaining the failure, and the framework epoch
> `:outcome` read `:ok`. This section adds inline per-step error
> cards + a tool-side outcome so a failed event is visible where the
> operator is already looking.

**Per-step status (no per-stage glyph — rf2-9wq0v).** Every pipeline
step carries a `:status` of `:ok` / `:error` / `:skipped`
(`projection/step-status`). `step-status` reads the step's stamped
`:status` slot first, then falls back to scanning the step-level +
row-level `:errors` / `:violations` vecs (so a step that gained a
schema violation via `attach-violations` — which does not stamp
`:status` — still reads `:error`).

rf2-ahhgn originally painted a per-stage ✓/✗/⊘ glyph immediately after
each step's badge pill (off a `badge/step-status-glyph` /
`badge/step-status-colour` resolver). **rf2-9wq0v RETIRED that
per-stage glyph** (and the `badge/step-status-set` / `step-status?` /
`step-status-glyph` / `step-status-token-key` / `step-status-colour`
resolver primitive it rendered through): a clean run painted a quiet ✓
on every stage — no information — and a failure is already shown by the
inline exception card UNDER the failing stage (below; rf2-yz57h /
rf2-wnvid), so the per-stage ✗ was redundant. The pipeline reads
quieter without it.

`projection/step-status` SURVIVES — it still drives (a) the SKIPPED-body
branch (`:skipped` → the "did not run" placeholder, below) and (b) the
overall cascade-outcome banner (`cascade-outcome` / `epoch-outcome`,
which scan the step vector for any `:error`). The per-EFFECT SIDE-EFFECTS
ledger glyphs (`badge/fx-row-status-glyph`, §9.1.10.x SIDE EFFECTS) and
the machine cascade-outcome glyph (`badge/cascade-outcome-glyph`) are
distinct per-row / per-cascade signals and are UNAFFECTED.

**Exception harvesting + attachment.** The projection harvests the
`cascade-exception-ops` subset into per-step error records
(`projection/exception-row` → `exception-rows`) and attaches each
to its owning step via `attach-exceptions` (a sibling of
`attach-violations`, run immediately after it). Each touched step
gains the attached record under `:errors` (step-level) or its
matching row's `:errors` (FX / interceptor row-level) AND
`:status :error`.

**Per-step placement (rf2-yz57h + framework attribution rf2-mszrz).**
rf2-mszrz split the pre-mszrz blanket `:rf.error/handler-exception`
(which the router used to emit for EVERY interceptor-chain throw —
handler, user interceptor, coeffect injector alike) into THREE
component-attributed ops via `classify-pipeline-exception`. rf2-yz57h
places each exception **under the step where it actually occurred**
rather than collapsing them all onto HANDLER (the pre-rf2-yz57h
mis-render):

| Trace op | Owning step | Granularity |
|----------|-------------|-------------|
| `:rf.error/coeffect-exception` | COEFFECT, the step whose `:id` = `:failing-id` (cofx id) | step-level; falls back to the first COEFFECT step. When the throwing cofx produced no `:rf.cofx/run` (it threw on injection), `project` synthesises a placeholder COEFFECT step (`:no-value? true`) so the card has a home |
| `:rf.error/interceptor-exception` | INTERCEPTOR (NEW), the step whose `:phase` = the exception's `:phase` (rf2-vew2n) | step-level; routes to the `:before` step (before HANDLER) or the `:after` step (after HANDLER); the row matching `:failing-id` to `:interceptor-id` carries the card |
| `:rf.error/handler-exception` | HANDLER | step-level (only the event handler itself now) |
| `:rf.error/fx-handler-exception` | SIDE EFFECTS, row whose `:fx-id` = `:failing-id` | row-level (fallback step-level) |
| `:rf.error/no-such-fx` | SIDE EFFECTS | step-level (fallback) |
| `:rf.error/flow-eval-exception` | FLOW | step-level (fallback HANDLER when no FLOW step) |
| `:rf.error/machine-action-exception` (rf2-e7yhv) | HANDLER | step-level. A machine action threw during a transition; the machine handler IS an event handler so its cascade renders under HANDLER. The card adds a single collapsed machine-attribution line — `action <id> threw an exception` (rf2-4yrr6, replacing the earlier `in machine <id> (action <id>) threw on unhandled event <ev> … fired by the :* wildcard …` run-on that repeated `:*`/`wildcard`/`unhandled`/`action` and re-stated the event right above the verbatim message). The machine is obvious from cascade context; the triggering event + `:where` ride the ex-data; the user's message renders verbatim below; `:data-via-wildcard` still rides the line so consumers can distinguish a `:*` WILDCARD throw (`:rf/via-wildcard?` on the trace's `:transition` slot, stamped by `transition/match-on-clause`) from a named-transition throw. The event row goes pink (the trace is op-type `:error`), the inverse of the benign `:no-op` row above |

The error MESSAGE rides `[:tags :exception-message]` ONLY (handler / fx
throws — `re-frame.router/emit-handler-exception!` stamps the
exception's `.getMessage`). rf2-oqi0c **DROPPED** the `[:tags :reason]`
fallback: `:reason` is the terse CATEGORY boilerplate ("Event handler
threw." / "…interceptor threw.") already conveyed by the card's position
+ "Exception Thrown" heading, so surfacing it as the card message was
redundant chrome; the message line now renders ONLY when the throw
carried a real `.getMessage`. The failing handler's SOURCE-COORD rides
the hoisted top-level `:rf.trace/trigger-handler :source-coord` slot
(with `:rf.trace/call-site` as fallback) — the SAME slot the Issues
panel's `source-coord` reads.

**INTERCEPTOR step (NEW, rf2-yz57h · phase-placement rf2-vew2n).** The
pipeline had no distinct interceptor step before rf2-yz57h — interceptors
WRAP the handler chain rather than appearing as their own cascade entry,
so a user-interceptor `:before` / `:after` throw had no home. The
INTERCEPTOR step (`projection/interceptor-step` →
`view/render-interceptor-step`) is **PHASE-SPLIT** and placed on the
correct side of the EVENT HANDLER, reflecting execution ORDER + REACH:
DISPATCH → COEFFECTS → **[:before interceptors]** → EVENT HANDLER →
**[:after interceptors]** → EFFECT HANDLERS → FLOWS. A `:before`
interceptor throws on the way IN (the chain aborts before the handler),
so its step renders **BEFORE** HANDLER; an `:after` interceptor throws on
the way OUT (the handler ran first), so its step renders **AFTER**
HANDLER. (rf2-vew2n fix: the pre-existing single fixed-early step put an
`:after` throw at position 2, before the handler — wrong; the `:phase`
captured by rf2-mszrz now drives placement.) Each step carries its own
`:phase`, and `attach-exceptions` routes each interceptor exception to
the step matching its phase (`interceptor-exception-target`). Both are
**CONDITIONAL** — the substrate emits no per-interceptor "ran" trace (the
chain runs as one unit; only a throw surfaces a trace), so a phase's step
renders ONLY when an interceptor threw in that phase this cascade.
**rf2-rvxem** — each row is ONE inline line, in this order:
`[INTERCEPTOR badge] [grey BEFORE/AFTER phase badge] <interceptor :id>
<single ↗ go-to-source glyph>`. The `:INTERCEPTOR` badge LEADS the row
(it is no longer a step-header above the rows — that painted a second,
content-free badge); the **phase badge** sits right after it, BEFORE the
name, rendered UPPERCASE (`BEFORE` / `AFTER`) as a grey chip
(`:bg-3` / `:text-tertiary`) so it reads as a badge. **rf2-siheh** — the
jump-to-source coord rides the projection row's `:coord` slot, resolved
from the `:rf.error/interceptor-exception` trace's `:source-coord` tag
(threaded by the router from the throwing interceptor's map). The
**`reg-interceptor` macro** (the public interceptor-authoring form under
EP-0022) captures that coord at the registration site; an interceptor
registered via the plain `reg-interceptor*` fn carries no captured coord,
so its row has nothing to render (`handler-meta :interceptor` resolves the
registration meta — `:interceptor` is a first-class registrar kind under
EP-0022). The id renders via
the shared **`coord-link`**, which ALREADY emits `name ↗` — **a SINGLE
go-to-source glyph** (rf2-rvxem FIX 1: the row formerly ALSO appended a
standalone `coord-chip`, producing TWO `↗`; the HANDLER / COEFFECTS rows
use `coord-link` alone, and only the plain-label SUBS / VIEWS /
SIDE-EFFECTS rows pair a label with a `coord-chip` — the interceptor row
had conflated the two). The id hyperlinks when a coord is present and
degrades to plain text + no glyph when the interceptor was registered via the
`reg-interceptor*` fn, is a framework interceptor, or the bundle elided the
coord in production. rf2-oqi0c **DROPPED** the badge's "N interceptor(s)
threw" summary verb — redundant with the per-row id + the inline card
below; the `:INTERCEPTOR` badge stands alone (it pulls the `:accent`
token — the chain WRAPS the handler; they read as one identity family).
A `:before` throw skips the handler; an `:after` throw runs the handler
first, then throws on the way out.

**SKIPPED steps (rf2-yz57h).** When an UPSTREAM `:before`-chain throw
aborts the cascade — a coeffect injector (`:rf.error/coeffect-exception`)
or a user interceptor `:before` (`:rf.error/interceptor-exception` +
`:phase :before`) — the event HANDLER never runs. Pre-rf2-yz57h the
HANDLER step still rendered its body and the `:db` sub-section read
"— no :db (handler returned no :db)" — WRONG: the handler's body returns
a `:db` (e.g. via `bump`), it simply never executed (verified bug,
buttons 17 / coeffect throw). `projection/mark-skipped-handler` now
stamps the HANDLER + SIDE EFFECTS steps `:status :skipped` (the
`handler-skipped-by-upstream?` discriminator). The view renders a SKIPPED
placeholder body ("The handler did not run — an upstream step threw
before this step could execute.") instead of the normal body. An
interceptor `:after` throw is NOT a skip — the handler ran first; the
throw fired on the way out. `step-status` gains a third value `:skipped`
(distinct from `:ok` / `:error`) which drives the SKIPPED placeholder
body; the SKIPPED body itself ("did not run") carries the signal (the
per-stage `⊘` glyph retired in rf2-9wq0v). A skip is NEUTRAL, not a
failure, so it does NOT inflate the epoch outcome (the failing COEFFECT /
INTERCEPTOR step is the load-bearing `:error` signal).

**Inline error card (rf2-ahhgn · refined rf2-wnvid · sophistication pass
rf2-ynvv7 · flattened rf2-iizhe).** `view/error-block` renders the
exception card (sibling to the amber schema-violation card) for ALL
exception kinds. **rf2-ynvv7** lifts the card out of the flat violation
skeleton it formerly borrowed and sits it in the design system the way
the surrounding pipeline-step cards do — every colour / spacing value
resolves through the theme token ns (`theme/tokens`), no hardcoded hex.
**rf2-iizhe** then makes it **FLAT** (no elevation): every other Xray
surface is flat, so the prior drop shadow read as off; the failure tone
is carried by the fill + edge + glyph, not a lift:

- **Surface (rf2-ksl5m)** — a **very-light-red** fill: `:error` mixed ~7%
  over the raised `:bg-2` panel surface (an opaque 2-token `color-mix`, so
  it paints cleanly on light + dark). Still **quiet** — a subtle tint, NOT
  the saturated `:bg-violation` rose wash — but the error now reads on the
  fill at a glance, joining the same `:error` tone the edge, rail +
  glyph already carry.
- **Border + rail** — a refined hairline keyed to the error token via
  `tokens/with-alpha` (a tinted edge, not a solid-red box) plus a solid
  `:error` **left rail** (the same accented-left-edge language as the L4
  panel header stripe + the diff stripes), so severity reads at the
  column-1 anchor.
- **Flat — no elevation (rf2-iizhe)** — the card carries **no
  `:box-shadow`**. The earlier layered shadow (a neutral drop shadow + a
  faint `:error`-tinted `0 0 0 1px` ring) read as off against the flat
  Xray UI, and the ring was redundant with the `:border` hairline; the
  card sits flat like every other surface while staying clearly
  error-keyed.
- **Spacing + radius** — padding from the 4px `tokens/spacing` scale
  (`:gap-2` / `:gap-3`); radius matches the surrounding cards.
- **Typographic hierarchy** — the `✗ Exception Thrown` headline (sans,
  `:error` accent, `:body-tight`) + the quiet `:rf.error/id` category
  badge (rf2-vvixub; mono, `:bg-3` chip, `:text-tertiary`) → the verbatim
  mono message (`:text-primary`, `:mono-body`) → the quiet
  collapsed-detail affordance (`:text-tertiary`, `:caption`).
- **Glyph** — the `✗` is a sized + baseline-aligned badge in the `:error`
  accent (a fixed 14px box), not a bare floating character.

The card carries, top to bottom:

1. a `✗ Exception Thrown` title bar carrying (rf2-vvixub) the
   `:rf.error/id` **category badge** — a quiet mono chip rendering the
   row's `:operation` (the canonical machine discriminator). Under the
   Spec 009 thrown-error human-message contract the verbatim message
   (item 2) now **leads with a human-actionable sentence** rather than
   the bare keyword, so the machine category surfaces here as
   at-a-glance metadata (the `:rf.error/<id>` pivot), distinct from the
   prose message below and no longer buried in collapsed `ex-data`. The
   title bar also carries a `Rolled back` recovery chip
   that paints **ONLY** when the cascade **ACTUALLY rolled back**
   (`db-rolled-back?` — a `:where :app-db` schema-validation failure
   reverted the commit, stamped onto each exception row by `project`).
   The substrate stamps `:recovery :no-recovery` on EVERY `:rf.error/*`,
   so keying the chip off recovery alone painted a **spurious** "Rolled
   back". The earlier rf2-wnvid gate (`db-committed?`) fixed the
   pre-commit handler throw (button-16 — no commit) but still mis-fired
   on a **POST-COMMIT fx throw** (button-20 `:standard-epochs/boom`): fx
   are best-effort post-commit (the FX atomicity asymmetry), so a
   throwing fx leaves the `:db` committed yet reverts nothing. Gating on
   actual rollback (rf2-s6oqd) paints the chip on a `:db` schema-fail
   rollback (correct) and omits it on a post-commit fx throw AND a
   pre-commit handler throw;
2. the verbatim `ex-info` message (monospace) — the punchline. Under the
   Spec 009 thrown-error contract (rf2-vvixub) this message **leads with
   a human-actionable sentence** (public concept + expected fix + key
   context) and trails the `[:rf.error/<id>]` greppability token — no
   longer the bare stringified keyword. rf2-oqi0c **DROPPED** the
   one-line category-reason boilerplate headline ("The event handler
   threw." / "…interceptor threw." — formerly `error-block-label`): it
   was redundant with the card's position (under the failing step) + the
   "Exception Thrown" heading, which already attribute the failure. The
   card leads with the real `.getMessage`;
3. a **collapsible** `<details>` disclosure ("Details", collapsed by
   default) carrying the exception's `ex-data` (via `ei/edn-inspector`)
   + its stack trace (monospace `<pre>`), read off the raw `:exception`
   object the projection lifts onto the exception row (rf2-wnvid).

The pre-rf2-wnvid always-expanded **jump-to-source link is DROPPED**:
it duplicated the HANDLER step's verb link (the canonical
jump-to-source) and, on a handler throw where the trace carried no
coord, degraded to a useless "source unavailable" (rf2-wnvid). COEFFECT /
INTERCEPTOR / HANDLER / FLOW inject `(error-blocks step-key errors)` under
their body (rf2-yz57h routes coeffect / interceptor throws to their own
steps); the SIDE EFFECTS step injects per-row cards via
`fx-row-with-violations` plus a step-level
`(error-blocks :side-effects errors)` for unmatched fx ids.

**HANDLER `:db` — no phantom `:db` (rf2-wnvid); omitted on a throw
(rf2-oqi0c).** When the handler **THREW**, `view/handler-body` OMITS the
`:db` sub-section entirely (rf2-oqi0c): the redundant
`— no :db (handler threw)` line was noise — the inline "Exception Thrown"
card is the signal. For a CLEAN handler that simply wrote no `:db`, the
sub-section (`view/handler-db-diff-block`) still renders the
`— no :db (handler returned no :db)` placeholder, keyed off the
projection's `:db-write?` slot (`projection/handler-wrote-db?`: t1
`:rf.event/db-pending` OR a `:rf.event/db-changed` commit fired). The
pre-rf2-wnvid code fell back to the record's full post-cascade
`:db-after` whenever the post-handler db value was nil — painting the
ENTIRE app-db tree under the HANDLER step as if the handler had returned
it (the phantom `:db`, most visible where the handler mutated nothing).

### §9.1.10.5 Epoch outcome — tool-side, NOT the framework slot (rf2-ahhgn)

The `:rf.xray/epoch-pipeline` composite sub carries an `:outcome`
(`:ok` / `:error`) from `projection/epoch-outcome` — `:error` when
ANY projected step reads `step-status :error`. The Panel stamps
`data-rf-xray-outcome` on the panel root (tools / e2e read the
tool-side outcome there). The failure surfaces **inline**: the failing
step paints the red ✗ glyph (the per-step `step-status` primitive) and
the inline "Exception Thrown" card sits right under it.

The pre-rf2-wnvid top-of-pipeline **outcome banner** ("This event
failed — see the ✗ step below.") is **RETIRED** (rf2-wnvid): it merely
restated the inline signal — the ✗ glyph + the error card already name
and locate the failure — and pushed the actual cascade content down.

**This is the TOOL-SIDE outcome** — the same trace-derived
`:error`/`:ok` signal `event-status-colour/cascade-outcome` already
computes for the L2 list / Event header / Trace bar (a cascade
carrying an `:rf.error/*` trace reads `:error`). It is
**DELIBERATELY NOT** the framework `:rf/epoch-record` `:outcome`
slot, which stays `:ok` for a recovered handler exception **by
spec**: the reference runtime recovers handler exceptions through
the interceptor error-capture seam and settles `:ok` with the error
trace under `:trace-events` (per
[spec/Spec-Schemas §`:rf/epoch-record` §Outcomes](../../../spec/Spec-Schemas.md#rfepoch-record)
+ [spec/009 §`:rf.epoch/*`](../../../spec/009-Instrumentation.md#op-type-vocabulary)
— `:halted-handler-exception` is RESERVED for a future
drain-aborting runtime). Surfacing the framework slot's `:ok` as the
panel's outcome was the rf2-ahhgn bug; deriving from the trace
stream fixes it **without a framework-contract change** — which
would have rippled into `restore-epoch`'s non-`:ok` refusal + Story
outcome chips + MCP wire consumers + the pinned
`outcome-enum-projection-pins-mapping` test. No spec/009 /
Spec-Schemas edit was needed (rf2-ahhgn settle-first finding).

### §9.1.10.6 EFFECT HANDLERS step — flat per-effect ledger (rf2-j630b, supersedes the rf2-kt6js 3-tier · rf2-uffov · rf2-m8ac9)

The pre-rf2-kt6js single `:fx` step became the **EFFECT HANDLERS** step
(badge `:SIDE-EFFECTS`, the same `:orange` hue). rf2-j630b supersedes the
rf2-kt6js 3-tier `:db` / `:fx` / other sub-step presentation with a
**FLAT per-effect ledger**: ONE row per effect, down the page, in
EXECUTION order, with **NO `:db` / `:fx` / other group headers**. The
leading per-row status glyph + effect-id + args edn-inspector + the row
order carry the structure.

**Single badge status (no labels).** After the "EFFECT HANDLERS" badge the
header paints **ONE overall glyph** — `✓` when every present row
succeeded, `✗` when one or more FAILED (`projection/side-effects-badge-
status` = the AND of the present rows; the view reads it via the generic
`step-status`, reusing the shared rf2-ahhgn `badge/step-status-*`
primitive). **All post-commit / best-effort labels and the threw-count
chip are dropped** — the single badge + the per-row glyphs are the whole
signal. SKIPPED rows are **NEUTRAL** — they do not trip the badge to
cross.

**Row order (execution order).** The flat `:rows` slot is
`[synthesised :db row, if present] + [synthesised :rf.db/runtime row, if
present] + [:fx rows, in order] + [other rows]`:

- **`:db` row** (FIRST, when present) — the handler's app-db write (the
  `:db` effect). `✓` on a successful commit; `✗` when the post-commit
  app-db schema check rejected the write and the cascade rolled back —
  the `:where :app-db` violation reason box attaches to the `:db` row via
  `attach-to-fx-db-row`. Its **args slot is the `→ app-db` DESTINATION
  marker** — NOT the db diff (the actual change lives in the App-db panel;
  no duplication). The marker is clickable: it jumps to the App-db panel
  for the focused epoch (a `[:rf.xray/select-tab :app-db]` dispatch; the
  App-db panel reads the same shared focus). The `:db` row appears
  whenever a `:db` commit happened, **including a plain reg-event that
  returns only `:db`** (no `:fx`); it is **ABSENT** when the handler
  returned only `:fx` / only `:rf.db/runtime` / only other / nothing, or
  THREW (no phantom `:db`, rf2-wnvid). Reconciles with rf2-4wywy: this is
  the HANDLER db write (post-handler / pre-flow); the FLOW step's own
  `:db` diff (the flow's t1→t2 reshape) stays a SEPARATE step.
- **`:rf.db/runtime` row** (after `:db`, before `:fx` — EP-0001
  rf2-ff9b0d) — the handler's **runtime-db partition write** (the reserved
  `:rf.db/runtime` STATE effect). The two partition writes (`:db` →
  app-db, `:rf.db/runtime` → runtime-db) commit **atomically together**,
  so the runtime-db row sits immediately after the `:db` row and before
  the `:fx` rows. `✓` on a successful commit; `✗` when the post-commit
  runtime-db boundary (the `:where :machine-data` validator — runtime-db
  carries durable machine snapshots) rejected the write and the cascade
  rolled back, with the violation reason box attaching to the row via
  `attach-to-runtime-db-row`. The row appears whenever the runtime-db
  partition was committed, **including a runtime-ONLY commit** (no `:db`,
  no `:fx`). The `:db`-commit signal (`:rf.event/db-changed`) is
  **APP-DB-ONLY** (EP-0001 Mike ruling #6) — a runtime-only commit emits
  **no** `db-changed` — so the runtime-db row keys off the partition-
  tagged **`:rf.event/frame-state-changed`** trace whose
  `:rf.event/partitions` set includes `:runtime-db`. This is what makes a
  runtime-only cascade render a SIDE EFFECTS row at all. A mixed
  `{:rf.db/runtime .. :fx ..}` return therefore shows the runtime write as
  an applied state effect (`✓`), **never** as a dropped/`other` row.
- **`:fx` rows** — one row per entry in the handler's `:fx` vector, in
  order, each carrying the rf2-g1mfc open-code chip + a per-effect glyph:
  `✓` ran / `✗` threw / `↺` overridden / `–` skipped-on-platform (the
  muted en-dash "n/a", with the hover "skipped on this platform — gated,
  didn't run here"; the en-dash avoids the `·` middle-dot, which is the
  `:cancelled` cascade-row glyph, and the circled-slash, which reads
  error-ish). For ASYNC / deferred fx (`dispatch-later`, `http`, a slow
  fx) the `✓` means **ACTIONED** (the fx handler was invoked ok), not
  awaited — matching the trace's `:rf.fx/handled` semantics.
- **`other` rows** (LAST) — one row per TOP-LEVEL effect key on the
  handler's returned map **beyond the closed-effect set
  `{:db :fx :rf.db/runtime}`** (the historical
  `{:db .. :fx .. :other-key ..}` form). Under EP-0001 (spec/002 §The
  two-partition frame contract) the effect map is the closed
  `{:db :fx :rf.db/runtime}` shape: the runtime commits the two STATE
  effects (`:db` → app-db, `:rf.db/runtime` → runtime-db) atomically and
  runs `:fx`. Any **other** top-level key is silently DROPPED — never
  executed, never traced. So each `other` row is a `–` (skipped) not-run
  **DIAGNOSTIC** flagging a declared effect the runtime ignored (almost
  always a bug — the effect belongs inside `:fx`); it is NEUTRAL.
  `:rf.db/runtime` is **not** an `other` key — it is a committed state
  effect with its own row (see above). In the canonical closed shape
  there are NO `other` rows.

**Atomicity governs which rows appear.** A `:db` schema-fail (pre-commit
transactional) rolls the cascade back BEFORE any `:fx` ran (spec/002
atomicity; spec/010 — `:fx` doesn't walk on a rollback) — so the ledger
carries just the `:db` CROSS row and the badge reads cross, with NO fx
rows.

**Exceptions.** A row that threw is a `✗` row whose expand is wnvid's
shared "Exception Thrown" card (collapsible details — stack + ex-data —
no redundant source link; the owning row provides context).
`attach-to-fx-error-row` matches the throwing fx by `:fx-id` against the
flat `:rows`; an unmatched fx exception (e.g. no-such-fx) attaches to the
step level. This per-row rendering is compatible with rf2-yz57h's
exception-under-step rendering.

**ALWAYS APPEARS.** Unlike the pre-rf2-kt6js `:fx` step (which showed
only when an `:fx` fired), the SIDE EFFECTS step appears whenever ANY
side effect occurred (a `:db` commit — including a bare reg-event —
and/or a runtime-db (`:rf.db/runtime`) commit — including a runtime-ONLY
commit — and/or `:fx` and/or other). The `:db`-commit signal is the
framework's `:rf.event/db-changed` trace (a second one with
`:rf.trace/phase :rollback` flags a schema-fail rollback). The
runtime-db-commit signal is the partition-tagged
`:rf.event/frame-state-changed` trace whose `:rf.event/partitions`
includes `:runtime-db` (EP-0001 rf2-ff9b0d — `:rf.event/db-changed` is
APP-DB-ONLY per Mike ruling #6, so a runtime-only commit emits no
`db-changed`; `frame-state-changed` is the only signal it surfaces).
Neither is a fx-id-less
`:rf.fx/handled` (which the substrate never emits; `re-frame.fx/emit-
handled!` always stamps `:rf.fx/id`, and the `:db` install path
`re-frame.router/commit-db-effect!` routes through `:rf.event/db-changed`,
not the fx pipeline). The pre-rf2-kt6js heuristic looked for that
non-existent emit, so a clean reg-event surfaced no side-effects step
at all — the bug rf2-kt6js fixes tool-side.

**Settle-first (rf2-kt6js — confirmed against the live substrate; NO
framework-instrumentation change).** Per-`:fx` success is ALREADY
RECORDED: each `:fx`-vector entry emits exactly one of `:rf.fx/handled`
/ `:rf.fx/override-applied` / `:rf.fx/skipped-on-platform` /
`:rf.error/fx-handler-exception` / `:rf.error/no-such-fx`
(`re-frame.fx/handle-one-fx`). The `:db` commit + schema-fail are
recorded by `:rf.event/db-changed` + `:rf.error/schema-validation-
failure :where :app-db`. "Other" effects don't exist on the trace
stream because the runtime never touches them. So the whole step is a
PRESENTATION over already-recorded data — implemented tool-side in
`projection/side-effects-step`, no core / spec-009 edit. rf2-j630b only
reshapes that presentation (3-tier → flat ledger); the data source is
unchanged.

**Header chrome** — badge `:SIDE-EFFECTS` only. No verb, no
`(post-commit)` caption, no threw-count chip — the per-row glyphs
(`badge/fx-row-status-glyph`) carry per-effect outcome and are the whole
signal (the rf2-m8ac9 "count summary is noise" rationale carries through;
rf2-j630b extended it to drop the post-commit labels too). rf2-j630b's
single overall `✓ / ✗` badge glyph was **RETIRED in rf2-9wq0v** along
with the other per-stage glyphs — it duplicated what the per-row ledger
already shows. The `:rows`-level AND-of-rows outcome stays queryable via
`projection/side-effects-badge-status` (tests + the cascade-outcome
banner).

**Per-action attribution** — when the cascade was driven by a machine
handler, each `:fx` ledger row that maps to a fx-id emitted by an
action's outcome `:fx` slot carries `:attributed-to {:action-id …,
:phase …}` (rf2-9c27r + rf2-uffov). The view renders an italic
`← <action-id> (<phase>)` chip. First-attribution wins (cascade order).

**Args rendering (rf2-ef2hy)** — each `:fx` / `other` row's args/value
mount the shared edn-inspector widget with `:default-expanded-depth 1`
(scan-then-drill); `:zoomable?` opens the popup overlay for a complex
map (the `:db` row's slot is the `→ app-db` destination marker, not an
edn-inspector). Sibling: the HANDLER step's `:fx` section (§9.1.10.5
lineage, rf2-p2zy0) uses depth 16 (full-expand) — HANDLER reads INTENT,
SIDE EFFECTS reads EXECUTION.

### §9.1.10.7 COEFFECT step chrome (rf2-s1jw4 · pair-debug 2026-05-26)

Mike's commit `ee9def224` reshaped the COEFFECT step from "one
step with N rows" to "N steps, one per injected cofx" (see §9.1.3
+ §9.1.10.1). The accompanying view-layer chrome:

- **Header** — `:COEFFECT` badge + cofx-id button to the right of
  the badge. The button is clickable when
  `(rf/handler-meta :cofx <id>)` returns a coordinate (click-to-source
  jumps through the shared `:rf.xray/open-in-editor` scheme denylist —
  see §9.1.11); otherwise the id renders as a plain coloured span.
  An external-link glyph trails the id when source-jump is wired.
- **Body** — `+ [:cofx-id] <value>` diff-style line, left-aligned
  with the badge (no indent), mirroring HANDLER's `:db` diff-line
  idiom. Value rendered via `edn/inspect-inline`.
- **Verb dropped** — the prior `N coeffect(s) injected` summary
  verb is gone; the per-step expansion of cofx makes the count
  visible in the cascade numbering itself.
- **SYSTEM-default filter** — `:db / :event / :frame / :source /
  :trace-id` are filtered before splitting (rf2-cq0ch). A cascade
  with no surviving user-cofx renders zero COEFFECT steps.

### §9.1.10.8 FLOW step chrome (rf2-xnb1x · pair-debug 2026-05-27)

The FLOW step was restructured from "one step with N rows" to
"N steps, one per flow that recomputed", mirroring the COEFFECT
per-cofx split (§9.1.10.7). The projection splats `flow-rows`
into N first-class step maps in `project`'s cascade `concat`;
each carries `:flow-id`, `:path`, `:before`, `:after`, and
`:duration-ms` (when stamped). The aggregate `flow-step` defn
retired with rf2-xnb1x.

Per rf2-4wywy / rf2-48oc4 each FLOW step additionally carries the
pre-flow + post-flow db snapshots — `:db-pre-flow` and `:db-post-flow`
— threaded by `project` off the trace stream (rf2-ta0y7). One flows
pass produces one pre→post transition shared across all FLOW steps of
the epoch.

- `:db-pre-flow` = the **effective post-handler db**
  (`projection/effective-post-handler-db`): the **t1 snapshot**
  (`:rf.event/db-pending`) when the handler returned `:db`, else
  **`db-before`** when the handler returned NO `:db` yet a flow fired
  (the rf2-48oc4 edge case — the flow's diff baseline is the ACTUAL
  post-handler db, which equals db-before since the handler wrote
  nothing; the implementation MUST NOT assume the handler supplied a
  `:db`). nil only on a pre-rf2-ta0y7 / no-flow stream.
- `:db-post-flow` = the **t2 snapshot**
  (`:rf.event/db-pending-post-flow`) — what the flow returned.

The accompanying view-layer chrome:

- **Header** — `:FLOW` badge + flow-id button to the right of
  the badge. The button is clickable when
  `(rf/handler-meta :flow <id>)` returns a coordinate (click-to-source
  jumps through the shared `:rf.xray/open-in-editor` scheme denylist —
  see §9.1.11); otherwise the id renders as a plain coloured span.
  An external-link glyph trails the id when source-jump is wired.
- **Body — the flow's OWN `:db` diff (rf2-4wywy / rf2-48oc4)** — a
  flow's contribution IS an app-db mutation: it writes `:output` into
  `:path` AFTER the handler returned. The body renders that
  contribution as a `:db` DIFF via the shared edn-inspector diff
  renderer (FULL+DIFF, parity with the HANDLER `:db` sub-section
  §9.1.5.1 + the App-DB Diff panel), under a `↳ :db <path>` sub-header.
  The diff is **scoped to the flow's `:path`**: `:before` =
  `:db-pre-flow` with the flow's pre-write value at `:path`, value =
  `:db-post-flow` with the flow's post-write value at `:path`, so each
  FLOW step shows ONLY its own slot's reshape even when several flows
  rode the same pre→post transition. Because `:db-pre-flow` is the
  EFFECTIVE post-handler db, this renders correctly EVEN WHEN the
  handler returned no `:db` (the diff baseline is `db-before`, NOT a
  scalar fallback — rf2-48oc4). This keeps the flow's change (e.g.
  `:derived` recomputed) SEPARATE from the HANDLER step's `:db` (which
  shows only the post-handler state). Testid
  `rf-xray-epoch-flow-db-diff-<name>`; the step root carries
  `data-rf-xray-flow-db-diff="true"`.
- **Fallback (pre-rf2-ta0y7 / no snapshots)** — when the step carries
  no pre/post snapshots (a pre-rf2-ta0y7 runtime, or a flow on a stream
  with neither t1 nor t2), the body falls back to the legacy
  `<glyph> [path] before → after` diff-style scalar line, left-aligned
  with the badge (no indent), reusing the COEFFECT body styles
  (`coeffect-body-*`). Glyph is `~` for an update (both before and
  after present) or `+` for a first-write (no before). Values render
  through the edn-inspector `mini` widget. Testid
  `rf-xray-epoch-flow-value-<name>`; the step root carries
  `data-rf-xray-flow-db-diff="false"`.
- **Verb dropped** — the prior `N flows recomputed` aggregate
  verb is gone; the per-step expansion of flows makes the count
  visible in the cascade numbering itself (operator scans left
  rail; numbered circle count = flow count).
- **Conditional emit unchanged** — a cascade with zero
  `:rf.flow/computed` events renders zero FLOW steps.

### §9.1.10.5 App-db diff section — RETIRED 2026-05-26 (rf2-rrykz · rf2-zkiu5)

> **Retired pair-debug 2026-05-26** in Mike's commit `ee9def224`. The
> APP-DB DIFF step was a state-mutation lens that rode immediately after
> HANDLER. It was redundant with HANDLER's `:db` sub-section (§9.1.5.1,
> FULL+DIFF single rendering post-rf2-vv3m6), which surfaces the same
> data in-context. The projection no longer emits this step and the
> `badge-set` no longer carries `:APP-DB-DIFF`. Section retained as a
> stub for searchability; historical design intent is reachable via
> the bead history (rf2-rrykz original + rf2-zkiu5 retirement).

### §9.1.10.4 Cascading-dispatches section — RETIRED 2026-05-26 (rf2-yx1ae · rf2-zkiu5)

> **Retired pair-debug 2026-05-26** in Mike's commit `eccb6db1b`. The
> CHILD-DISPATCHES step was a parent→child cascade-link lens that rode
> between FX and SUBSCRIPTIONS. It was redundant with the FX step, which
> already surfaces every `:dispatch` / `:dispatch-n` / `:dispatch-later`
> fx entry per row — the cascade-link affordance now lives on the FX rows
> themselves. The projection no longer emits this step and the
> `badge-set` no longer carries `:CHILD-DISPATCHES`. Section retained as
> a stub for searchability; historical design intent is reachable via the
> bead history (rf2-yx1ae original + rf2-zkiu5 retirement).

### §9.1.11 Cross-panel navigation

Every click-to-source affordance in the cascade flows through the
shared `:rf.xray/open-in-editor` event (rf2-vwcsq scheme denylist) —
the same surface every other L4 panel uses for source jumps. The Epoch
panel emits no panel-internal navigation; spine focus drives every
data axis.

---

## §10 Shared edn-inspector renderer

The renderer is **ONE canonical component used everywhere data appears**
— App-db's huge nested map, the Epoch panel's COEFFECT step rows + FX
step rows, the View panel's sub values, Trace ops' expanded payloads,
Issues `ex-data`. Operator learns one interaction pattern; applies it
everywhere.

### §10.0 First-class edn-inspector widget (rf2-oqa60 phase 1)

Per Mike-direction 2026-05-25 (rf2-sndui ratification `b a a a a a a a`)
the renderer is the **first-class edn-inspector widget** at
`day8.re-frame2-xray.views.edn-inspector`. It is a roll-your-own
CLJS-value-to-hiccup tree walker (~900 LoC after phase 5) that
owns the WHOLE contract — **browse + diff + mini** — as a single
source of truth: classifies every CLJS type natively, owns its own
sticky-expansion app-db slot, ships first-class chrome for the
spec/015 sentinels, and renders inline diff annotations when a
`:before` opt is supplied. The cljs-devtools dep is dropped
(rf2-oqa60); the legacy `edn-inspector.render` engine +
`theme.data-inspector` chrome ns are deleted in phase 5 (rf2-q3dzw).
The EDN-widget facade at `views.edn-widget` is retained as a
thin delegate so existing call sites compile; new call sites should
reach for `[edn-inspector value opts]` directly.

#### §10.0.1 Public API

```clj
[edn-inspector value]                         ;; browse mode
[edn-inspector value opts]                    ;; browse / diff per opts
[edn-inspector-diff before after]             ;; diff convenience
[edn-inspector-diff before after opts]
```

`opts` keys (all optional):

- `:panel-id` — distinguishes per-panel expansion state. Defaults
  `:rf.xray.edn-inspector/anon`.
- `:default-expanded-depth` — first-render expansion depth before
  operator clicks. Defaults `2`.
- `:max-inline-width` — character budget before forced-vertical
  layout. Defaults `60`.
- `:max-depth` — hard cap on recursion depth; deeper levels render
  `{…}` collapsed and click expands one level. Defaults `16`.
- `:before` (rf2-q3dzw · rf2-e28r3) — the prior value to annotate
  against. The widget has ONE rendering path keyed on **value
  (always) + before (optional)** — there is no separate diff "mode".
  With a `:before` present the `value` arg is the AFTER side and the
  tree paints the full diff chrome: gutter glyphs (`+` added · `-`
  removed · `~` modified · `◴` children-changed) per node, modified
  leaves get an inline `← was <prior>` annotation, the R3 collapsed-
  container `[N∆]` count chip + R4 2px vertical gutter rail render on
  change-bearing subtrees (the §9.1.5.1 R1-R8 grammar), and the
  ancestor chain force-expands over any changed descendant. With no
  `:before` (and no `:added?`) the SAME renderer shows the value
  plainly — no annotations, no chip, no rail. The presence of a pre-
  image is the only diff signal. See §10.0.8 + §10.0.12.
- `:popup-affordance?` — when `true`, renders a top-right ↗ icon
  button that pops the value into a modal overlay (§10.0.7.2).
  Defaults `false`.
- `:card?` (rf2-63ie5) — when `true`, the widget's outer container
  carries the inspector-card chrome (background `:bg-1`, 1px
  `:border-default` border, `8px` radius, `8px 10px` padding,
  `8px` margin-bottom) so multiple top-level mounts in the same
  panel read as DISTINCT cards rather than blending into one
  continuous block. Theme-aware via tokens. Opt-in per call-site:
  inline mounts (table cells, popup contents that already carry
  modal chrome, diff sub-renderers) leave it off; panels with
  multiple top-level inspector mounts (App-DB's TOP + per-`:rf/*`
  sections) opt in. Defaults `false`.
- `:header` (rf2-okq7p) — when supplied (string or hiccup), the
  widget renders the three-shade card chrome from §10.0.10
  (outer `<section>` + `<header>` ribbon + body sleeve) modelled on
  the Machine panel's `focused-event-section`. `nil` (default)
  renders inline with no section wrapper. Consumers supply a label
  string for simple cases or hiccup for composite headers (label +
  code chip + per-inspector affordances). See §10.0.10 for the
  three-shade ramp + composition rules.
- `:site-id` (rf2-pvsxs) — when supplied, becomes the second
  component of the per-node expansion key INSTEAD of the auto-
  generated mount-id. Lets the same logical call site survive a
  panel-leave-and-return round-trip (auto-mount-id changes on
  remount; a stable site-id does not). Omit to keep the per-call-
  site isolation default.
- `:added?` (rf2-kp7bw) — boolean FIRST-RUN signal. When `true` AND
  no explicit `:before` is supplied, the widget enters diff mode with
  the prior side synthesised as `engine/missing-sentinel`, so the
  projection classifies the WHOLE value tree as `:added` (root op
  `:added` → green wash + `+` chrome over every descendant). Use for a
  value that just came into existence — a sub's first cache entry, an
  app-db key that just appeared — where a plain mount would read as
  un-annotated. An explicit `:before` always takes precedence (a real
  prior value is a genuine diff, not a first run), so `:added?` is a
  no-op when `:before` is present. Empty containers still read
  `:added`. See §10.0.13. Defaults `false`.

The widget is a **Reagent form-2 component** — the outer fn captures
a stable `mount-id` (auto-generated UUID, per D4=a — no public
`render-id` arg) in closure; the inner fn subscribes to the
expansion slot and renders. Two `[edn-inspector v opts]` mounts in
the same panel each receive a distinct `mount-id`, so their
expansion state is independent.

`mini` is the one-line inline overload (D2=a per rf2-sndui — folds
sentinel routing in; the legacy `inspect-inline` is dropped):

```clj
[mini value]              ;; default max-len 80
[mini value max-len]      ;; with width cap
```

#### §10.0.2 Acceptance properties (rf2-oqa60)

Five properties the unit gate pins so a future renderer change can't
re-break what phase 1 lands. All five are tested in
`tools/xray/test/day8/re_frame2_xray/views/edn_inspector_cljs_test.cljs`.

1. **Per-type colours via CSS variables.** Each leaf type maps to a
   distinct token (keyword `:accent`, string `:syntax-string`,
   number `:syntax-number`, boolean `:syntax-keyword`, nil
   `:text-tertiary`, symbol `:magenta`, uuid/regex `:info`, fn
   `:text-tertiary italic`). Theme-aware via CSS variables — no
   per-theme code path, no re-render needed for theme switch.

2. **Distinct bracket styling per collection kind.** Map `{…}` /
   vector `[…]` / list `(…)` / set `#{…}` / map-entry `[…]` /
   record `#tag{…}` — characters AND colour tokens differ per kind.
   Map-entry brackets share the chars of a 2-vector but read in the
   `:accent` colour, so the operator can visually distinguish
   `(MapEntry. :k :v)` from `[:k :v]`.

3. **Inline preview for collapsed collections.** Collapsed
   collections show `▸ {:a 1, :b 2, :c 3}` (first 3 fit), or
   `▸ {:a 1, …}` (partial fit), or `▸ {…3 keys}` (fallback). Never
   recurses into a child container's contents — one-level only — so
   nothing sensitive leaks from inside a collapsed parent.

4. **Click-to-toggle actually toggles.** Each node carries a stable
   `data-testid` derived from `[panel-id mount-id path]`; the `▸`
   span's `:on-click` dispatches
   `[:rf.xray.edn-inspector/toggle-node panel-id mount-id path rendered-expanded?]`
   via the **reg-view-injected frame-bound `dispatch`** — the widget
   is `reg-view`-registered (per rf2-y59tb) so the macro expands its
   body's `dispatch` over a `frame-handle` capturing the surrounding
   `frame-provider`'s frame from React context. Mounted under `:rf/xray`
   (App-DB panel) the click lands
   in `:rf/xray`'s app-db; under any other frame it lands in that
   frame's app-db. No explicit `{:frame :rf/xray}` envelope — the
   frame is captured by `reg-view` at mount.

   The fifth payload slot — `rendered-expanded?` — is the visible
   state at the click site (the value `resolve-expanded?` returned
   for the path on the current render). The reducer inverts from it
   when no override exists, so the FIRST click always flips what the
   user sees. Without this the reducer's "no override → first click
   opens" branch is a silent no-op on default-expanded paths
   (top-level triangles render expanded BEFORE any click, so storing
   `:expanded? true` repeats the rendered state — rf2-y59tb Bug B).

   The reducer writes `{:expanded? bool}` into the per-node entry in
   `:rf.xray.edn-inspector/expansion`. Next render reads the slot via
   `:rf.xray.edn-inspector/expansion` subscription, the path's
   entry exists, the renderer picks `:expanded?` over the default
   heuristic, the triangle swaps `▸` → `▾` (or vice versa), and the
   body becomes visible (or hidden). This is the property rf2-dw8n7 /
   rf2-oswhk failed to deliver under the cljs-devtools-layered
   approach; rf2-y59tb closed the remaining frame-leak + first-click
   gap on top of the rf2-oqa60 widget rebuild.

5. **Per-call-site isolation.** Two `[edn-inspector]` mounts in the
   same panel receive distinct `mount-id`s (auto-generated UUID per
   mount; captured in form-2 closure). Toggling a path under
   mount-A leaves the identical path under mount-B untouched.

#### §10.0.3 Sentinels as first-class types (D3=a per rf2-sndui)

The spec/015 sentinels are first-class type-classifications inside
the renderer, not separate chrome wrappers:

- `:rf/redacted` (bare keyword) — magenta chip with `●` indicator;
  never expandable.
- `{:rf.size/large-elided {:path [...] :bytes N :type <kw> :reason :schema :hint s :handle [:rf.elision/at <path>]}}` — yellow chip showing bytes;
  click-to-reveal is deferred (was in the now-deleted
  `theme.data-inspector` ns; the popup phase D6=a returns it).
- `{:rf/redacted {:bytes N}}` — combined sensitive+size; magenta
  chip with size annotation.

The legacy `theme.data-inspector` ns is **deleted** in phase 5
(rf2-q3dzw, D5=a) — sentinel chrome lives entirely inside the
edn-inspector widget now.

#### §10.0.4 Phased rollout

Phase 1 (rf2-oqa60, this commit): core renderer + App-DB panel
integration. The EDN-widget facade delegates `browse` / `inspect` /
`mini` / `inspect-inline` to the new widget; the cljs-devtools
adapter + dep are dropped.

Phases 2-5 file as separate beads chained off rf2-oqa60:

- Phase 2 — Trace per-event detail integration
- Phase 3 (rf2-e46qs) — **Sub value inspector integration. RETIRED
  in rf2-uz3wm.** Previously the Views panel (`reactive-panel-view`)
  rendered a `SUB VALUES` section beneath the flow graph (one row per
  RUN sub, each row mounting `[ei/edn-inspector value opts]` directly).
  Retired once the Epoch panel's SUBSCRIPTIONS table grew per-cascade
  sub values + per-sub diff chrome (rf2-e46qs successor coverage,
  rf2-fyd8u `← was X` annotation) — that table carries the same
  information cradled in the cascade context, so the Reactive tab's
  value-listing was duplicate inventory. The Reactive panel now stops
  at the flow graph + the UNMOUNTED VIEWS / DESTROYED SUBSCRIPTIONS
  teardown sections.
- Phase 4 — Machine snapshot drill-in integration
- Phase 5 (rf2-q3dzw) — **Diff renderer subsumption (D5=a per
  rf2-sndui).** Diff is now an opt-in MODE on the same widget —
  pass `:before` in opts. The legacy
  `edn-inspector.render` engine (`render-tree`) and the
  `theme.data-inspector` chrome ns are DELETED with this phase.
  The widget owns the whole `browse + diff + mini` contract as a
  single source of truth — see §10.0.8 for the full diff contract.
- Phase 6 (rf2-s0x6x) — Popup overlay infra (D6=a · D8=a) —
  see §10.0.7
- Phase 7 (rf2-0qrcr) — `IXrayEdnInspector` custom-formatters
  protocol (D7=a) — see §10.0.6
- Phase 7 follow-on (rf2-x16b1) — curated default formatters
  for `uuid` + `inst` (uri deferred) — see §10.0.9

#### §10.0.5 Code-block (separate surface)

`views.edn-widget/code-block {:source src :lang :clojure}`
is the in-bundle Clojure source-text highlighter for the Event
panel's HANDLER slot — distinct from the value renderer (cljs-
devtools never owned this, and the in-bundle tokenizer + zprint
pre-format pipeline survives the rf2-oqa60 cut-over unchanged).
Token colours follow the Figma `.syntax-*` block: keywords red,
strings blue/green, numbers blue, comments muted-italic.

#### §10.0.6 `IXrayEdnInspector` custom-formatters protocol (phase 7 · D7=a · rf2-0qrcr)

Phase 7 of rf2-oqa60 opens a single extension seam on the closed
phase-1 renderer for consuming applications that carry domain types
the built-in classifier has no better fallback for than `pr-str`.

**Surface** — `day8.re-frame2-xray.views.edn-inspector-protocol`:

```clj
(defprotocol IXrayEdnInspector
  (-xray-render-header [v opts])
  (-xray-render-body   [v opts]))
```

**Contract**:

- `-xray-render-header` returns hiccup for the node's header row
  (the row that sits next to the toggle glyph, or the inline-fit
  content). Returning `nil` falls through to the built-in renderer
  for the whole node — consumers can selectively opt out per value.
- `-xray-render-body` returns hiccup for the expanded body, or
  `nil` for a header-only render (no expanded body, no toggle).

`opts` carries the same per-node context the built-in renderer
sees — `:panel-id`, `:mount-id`, `:path`, `:depth`,
`:expansion-map`, plus the per-widget `:default-expanded-depth` /
`:max-inline-width` / `:max-depth`. The original argument map is
also threaded under `:node-opts` for consumers that want to recurse
the built-in renderer on sub-values via `edn-inspector/render-node`.

**Dispatch order** (in `render-node`):

1. `(satisfies? IXrayEdnInspector v)` → consult the protocol.
2. `-xray-render-header v opts` returns nil → fall through to the
   built-in container / scalar dispatch.
3. Otherwise wrap the header (+ optional body) in the standard
   widget chrome — same `data-testid` shape `[panel-id mount-id
   path]` as built-in nodes so panel-level toggle / reset
   affordances address protocol nodes uniformly.

**Safety** — the wrapper catches exceptions thrown by consumer
impls. A broken third-party formatter falls through to the
built-in renderer rather than blanking the inspector.

**Boundary** — the protocol is the ONLY public extension seam
in the widget. Consumers do NOT extend interactions through this
protocol (the locked B.9 / rf2-sndui model ships exactly one
path-click interaction); the seam is purely for value rendering.

**Worked example** — a domain `Money` type that wants to render
as a single chip with the amount + currency, but expand to show
the underlying ledger entries:

```clj
(ns my-app.domain.money
  (:require [day8.re-frame2-xray.views.edn-inspector-protocol
             :refer [IXrayEdnInspector]]))

(deftype Money [amount currency ledger]
  IXrayEdnInspector
  (-xray-render-header [_ _opts]
    [:span {:style {:color    "var(--rf-xray-syntax-number)"
                    :padding  "0 6px"
                    :background "color-mix(in srgb, var(--rf-xray-syntax-number) 12%, transparent)"
                    :border-radius "3px"}}
     (str amount " " currency)])
  (-xray-render-body [_ opts]
    [day8.re-frame2-xray.views.edn-inspector/render-node
     (assoc opts :value ledger)]))
```

Mounted via `[edn-inspector some-money-instance]` — the chip shows
collapsed; click expands to the ledger as a normal map view.

**Tests** — `tools/xray/test/day8/re_frame2_xray/views/edn_inspector_protocol_cljs_test.cljs`
pins: (a) built-in types still route through the built-in dispatch
(no protocol path leakage), (b) protocol-implementing types take
the protocol path and the consumer's hiccup appears verbatim,
(c) header-nil falls through, (d) body-nil renders header-only,
(e) broken consumer impl falls through safely, (f) expansion-map
overrides still apply to protocol nodes.
#### §10.0.7 Popup overlay infra (rf2-oqa60 phase 6 · D6=a · D8=a)

Phase 6 ships the **popup overlay** that floats over an Xray panel
and inspects a CLJS value at depth via `[edn-inspector value opts]`.
Locked decisions:

- **D6=a — Xray-internal anchor scope.** The backdrop spans the
  Xray shell only (or the Story cell when
  `:rf.xray/modal-positioning` resolves to `:absolute`). The popup
  never anchors to the debugged application's DOM — the right-click
  surface lives over Xray-internal edn-inspector nodes only.
- **D8=a — auto-generated UUID per popup mount.** Each
  `[edn-inspector-popup value opts]` mount allocates a fresh
  `mount-id` (UUID, captured in form-2 closure) on first render.
  Two side-by-side mounts get independent expansion state — the
  popup's `:panel-id` is namespaced by `mount-id`, so the embedded
  widget's per-path expansion entries cannot collide with sibling
  popups or with the panel underneath.

Public API at
`tools/xray/src/day8/re_frame2_xray/views/edn_inspector_popup.cljs`:

```clj
[edn-inspector-popup value]
[edn-inspector-popup value opts]

;; programmatic — opens via the stack slot:
(rf/dispatch [:rf.xray.edn-inspector-popup/open
              mount-id {:value v :opts opts}]
             {:frame :rf/xray})
```

`opts` keys (all optional):

- `:title` — header label. Defaults `"Inspect"`.
- `:panel-id` / `:default-expanded-depth` / `:max-inline-width` /
  `:max-depth` — forwarded to the wrapped edn-inspector widget. The
  `:panel-id` is namespaced by the popup's `mount-id` before
  reaching the widget so per-mount isolation holds without caller
  effort.
- `:on-close` — optional 0-arg fn; overrides the default close
  dispatch (for parent components that manage their own
  open/closed flag).

State model (slots under `:rf/xray` frame's db):

- `:rf.xray.edn-inspector-popup/stack` — vector of `mount-id`s, top
  of stack = last entry. Esc closes only the top entry, so layered
  popups beneath survive.
- `:rf.xray.edn-inspector-popup/entries` — `{mount-id {:value …
  :opts …}}` payload map; survives shadow-cljs `:after-load`
  reloads. Re-opening a popup with the same `mount-id` raises it
  to the top of the stack and replaces its payload (window-manager
  raise semantics).

Close affordances (per the bead's scope):

1. **Esc** — handled by the popup's `:on-key-down`; dispatches
   `:rf.xray.edn-inspector-popup/close-top` so layered popups
   close one at a time.
2. **Click backdrop** — closes that specific popup (matches the
   segment-inspector's click-outside-closes contract).
3. **✕ button** — explicit close in the dialog header.

Z-index layering: the popup paints **above the active Xray
panel** but below app-modals (palette / settings). Base layer
`2147483640` (one tier below the segment-inspector at
`2147483645`); stacking popups within this surface get
sequential z-indexes derived from their stack position so the
topmost popup wins click + focus.

The `edn-inspector-popup-stack` view is the entry point for
programmatic opens (a context-menu handler dispatches `:open`
and the stack view picks the entry up); the
`[edn-inspector-popup value opts]` component is the entry point
for inline opens (a panel that wants to control the popup
imperatively from its own view tree). Both compose through
`popup-chrome` so the chrome shape is identical.

##### §10.0.7.1 Shell mount (rf2-l4625)

The `edn-inspector-popup-stack` view mounts **once** at the Xray
shell root, alongside the other modal stacks (palette, settings,
segment-inspector, …). The stack view reads
`:rf.xray.edn-inspector-popup/stack` + `:rf.xray.edn-inspector-popup/entries`
and renders the popup chrome for every active mount-id in z-index
order; it short-circuits to `nil` when the stack is empty (closed-
state cost: one subscribe + a `when`-gate).

Registration is a single line in `registry.cljs`:

```clj
(edn-inspector-popup/install!)
```

…idempotent per the orchestrator's `compare-and-set!` sentinel.
The shell-side mount sits alongside `[app-db-segment-inspector/Popup]`
in `shell.cljs`'s overlay block, INSIDE the `rf/frame-provider-existing
{:frame :rf/xray}` wrapper so subscribes resolve to Xray's frame.

##### §10.0.7.2 Per-panel "open in popup" affordance (rf2-l4625 · rf2-7sdja)

Each `[ei/edn-inspector value opts]` call site **opts in** to a
top-right ↗ icon button by passing `:popup-affordance? true` in
`opts`. Default is **off** — scalar / tiny-value mounts don't
benefit from a larger inspection surface, and the silent default
keeps simple call sites quiet.

Glyph is `↗` (north-east arrow) — rf2-7sdja replaced the original
`⊕`. The arrow reads as "open in new pane / navigate outward"
which matches the popup's window-manager semantics better than
the circled plus (which read as "expand" / "add").

Mechanics:

- The affordance renders a `:button` positioned absolutely at
  the top-right of the edn-inspector widget's outer container
  (the container gets `position: relative` when the affordance
  is enabled).
- Click dispatches
  `[:rf.xray.edn-inspector-popup/open popup-mount-id {:value v :opts o}]`
  against `:rf/xray` **explicitly** via the established
  `(rf/dispatch event {:frame :rf/xray})` pattern (rf2-7sdja).
  This pins the dispatch frame regardless of where the widget
  mounts — popup state is Xray-global (the popup-stack-view
  subscribes only against `:rf/xray`), unlike expansion state
  which is per-frame and uses the reg-view-injected frame-bound
  `dispatch`.
- `popup-mount-id` is derived from the edn-inspector's own
  mount-id (`"ddp-" + mount-id`) — stable per call-site mount,
  so re-clicking the affordance **raises** the existing popup
  rather than spawning a duplicate (matches
  `edn-inspector-popup/push-entry` window-manager semantics).
- The opts forwarded to the popup carry
  `:popup-affordance? false` so the popup's embedded
  edn-inspector does not recurse the affordance inside itself.

Stable testid: `"rf-xray-edn-inspector-popup-affordance-" +
popup-mount-id`. The button carries
`:data-rf-affordance "popup"` for hover-style hooks +
`:aria-label "Open in popup"` for assistive tech.

Enabled call sites (panels where the inline widget is genuinely
cramped):

| Panel                                  | Site                                              | Rationale                                                                            |
|----------------------------------------|---------------------------------------------------|--------------------------------------------------------------------------------------|
| `panels/machine_canvas.cljs`           | snapshot drill-in                                 | Machine snapshots carry deeply-nested `:data` maps                                   |
| `panels/machine_inspector.cljs`        | per-phase snapshot block                          | Same as canvas — per-machine `:data` maps                                            |
| `panels/reactive_panel_view.cljs`      | per-sub value row                                 | Sub values can be the full domain projection (cart, users, route tree, …)            |
| `panels/trace.cljs`                    | per-row payload expand                            | Trace rows expand within the row's narrow column; tags + payload maps are cramped    |

App-DB does NOT use the affordance (rf2-7sdja — Mike's live-testing
call 2026-05-26). The side panel has plenty of horizontal room; the
whole-tree inspector reads comfortably in-place. Earlier framing of
App-DB as "the canonical cramped in the side panel case" was wrong.

Diff renderers' internal `inspect-value` leaves (the diff-mode path of
`views/edn-inspector`, per §10.0.8 below — the prior `diff/hiccup_render.cljs`
mini-renderer was retired with the dropped Hydration panel cluster,
rf2-ici2id) intentionally **do not** carry the affordance — they're inner
mini-renderers inside a larger diff tree chrome, not user-facing leaf
inspect mounts; an inline ↗ per inner leaf would clutter the diff
display.

#### §10.0.8 Diff mode (phase 5 · D5=a · rf2-q3dzw)

Phase 5 closes the "diff renderer as separate engine" pattern. The
pre-rf2-q3dzw shape composed two separate engines:
`edn-inspector.render` walked before/after pairs to paint gutter rows,
delegating leaf-value rendering to `theme.data-inspector/inspect`.
That seam left two ns'es to keep in sync — every time the inspector
gained a new type classification (sentinel chrome, type colours,
bracket styling) the diff engine had to mirror it, or risk diverging
visual contracts between current-state browse and diff renders.

Post-rf2-q3dzw the diff path is an **opt-in mode on the same widget**.
A single hiccup walker classifies types, paints sentinels, decides
expand/collapse, AND applies diff annotations — one source of truth.

**Surface**:

```clj
[edn-inspector value {:before before-value …}]
[edn-inspector-diff before after opts]
```

**Diff ops**:

| op          | trigger                                   | glyph | colour token   |
|-------------|-------------------------------------------|-------|----------------|
| `:added`    | `:before` is `::missing`                  | `+`   | `:green`       |
| `:removed`  | `:value`  is `::missing`                  | `-`   | `:red`         |
| `:modified` | both exist; differ (leaf-level)           | `~`   | `:yellow`      |
| `:children` | container with changed descendant         | `◴`   | `:accent`      |
| `:same`     | values equal                              | ` `   | `:text-tertiary` |

The op classification is pure data via `ei/diff-op` (public for
tests). The `::missing` marker (`ei/missing-sentinel`) distinguishes
"slot absent on this side of the diff" from a real `nil` value.

**Gutter row**: each diff'd node renders inside a `gutter-row`
wrapper — a 3px left border in the op's colour + a glyph span +
the rendered hiccup. `:same` rows render with a transparent border
+ blank glyph so non-diff renders share the same shape (no layout
jitter between modes).

**Change annotation**: modified leaves carry an inline
`← was <prior>` chip rendered in `:text-secondary` /
`sans-stack` / italic. Pure hiccup; sits to the right of the
rendered value.

**Ancestor force-open**: in diff mode, a container with a changed
descendant ignores the default-expand depth heuristic — it always
opens so the operator never has to drill to find the change.
Implementation: `default-expanded?` takes a `:has-changed-descendant?`
flag (derived from `changed-descendant?`) which wins over the
depth/size table.

**Map / sequential alignment**: in diff mode the children loop walks
the UNION of keys (for maps) or the index range (for sequentials)
so removed slots surface as struck-through rows. Sets render as
plain browse — set-element diff is structurally ambiguous without a
key contract.

**Sentinels in diff mode**: the spec/015 sentinels keep their chip
chrome regardless of mode. A modified `:rf/redacted` slot still
renders as the magenta chip + `← was <prior>` annotation;
no chip-reveal leakage.

**Test pins** — `tools/xray/test/day8/re_frame2_xray/views/edn_inspector_cljs_test.cljs`:

1. `diff-op` classifies the canonical 4 ops + `:same`.
2. `changed-descendant?` walks maps + sequentials + returns
   primitive boolean.
3. Gutter glyph + tone-key mappings are stable
   (`op->gutter-glyph`, `op->gutter-tone-key`).
4. Modified leaves carry the `← was <prior>` annotation
   chip.
5. Deep modified leaves force the ancestor chain open
   (`diff-forces-ancestor-chain-open-over-changed-descendant`).
6. The public widget's outer container carries `data-rf-mode
   "diff"` when `:before` is supplied; `"browse"` otherwise.

#### §10.0.9 Default `IXrayEdnInspector` formatters (rf2-x16b1)

The phase-7 protocol seam (§10.0.6) ships zero default impls — every
consuming app would have to register the same boilerplate for the
common types where the raw repr is genuinely cramped. rf2-x16b1
ships a curated set of opinionated default formatters, loaded
automatically when `views.edn-inspector` is required.

**Coverage** — the curated set is deliberately small. Each type
included carries a clear win over `pr-str`; the rest stay on the
built-in dispatch.

| Type           | Header (collapsed)            | Body (expanded)              | Title (hover)        |
|----------------|-------------------------------|------------------------------|----------------------|
| `cljs.core/UUID` | `#uuid "…<last-8>"`         | `#uuid "<full-36-char>"`     | full canonical form  |
| `js/Date` (`inst?`) | `#inst "<relative>"`     | `#inst "<ISO-8601>"`         | full ISO             |

**Relative-time formatter** — pure-data buckets, no library dep:

- `< 5s`                   → `just now`
- `< 60s`                  → `Ns ago` / `in Ns`
- `< 60m`                  → `Nm ago` / `in Nm`
- `< 24h`                  → `Nh ago` / `in Nh`
- `< 30d`                  → `Nd ago` / `in Nd`
- else                     → `YYYY-MM-DD`

The 30-day upper bound on relative-time avoids `247d ago` reading
as precise when the eye actually wants the ISO date. Both
directions are symmetric — past (`Nm ago`) and future (`in Nm`) —
so scheduled-event timestamps read naturally.

**URI heuristic — deliberately deferred.** CLJS has no built-in
`uri?` predicate, and extending `js/String` so non-URL strings can
return `nil` from the protocol path would route every string in
every render through the seam — invasive on a fundamental type
for a marginal win. Domain URI wrappers (`goog.Uri`, project-
specific types) can opt in via `extend-type` per the standard
contract. The seam-via-extension stays the project-owned escape
hatch.

**Consumer precedence** — consumers who `extend-type` the same
types win. CLJS protocol dispatch is not class-hierarchy-based;
the most-recently-loaded impl is the one that fires. The bundled
defaults are inert when a consumer takes the seam for a type they
own. Regression-anchored by
`consumer-extension-wins-over-default-on-its-own-type` in
`tools/xray/test/day8/re_frame2_xray/views/edn_inspector_default_formatters_cljs_test.cljs`.

**Surface** — `day8.re-frame2-xray.views.edn-inspector-default-formatters`.
Public for tests + reference:

- `format-relative` — pure-data inst bucketing against a pinned `now`
- `render-uuid-header` / `render-uuid-body`
- `render-inst-header` / `render-inst-body`

The ns is required for side-effect (the `extend-type` forms) from
`views.edn-inspector` itself — no call-site registration needed.

#### §10.0.10 `:header` opt — three-shade card chrome (rf2-okq7p)

Consumer panels routinely mount multiple top-level `edn-inspector`s
side by side: App-DB has three (counter-app db, machine-app db, route
params); Handler/Event has up to five per epoch (event vector, db-
before, db-after, fx, coeffects). Without per-mount labelling the eye
has to *read the value* to figure out which inspector is which. The
Machine panel already solves the same problem via a card aesthetic —
white-bordered `<section>` + darker-grey `<header>` ribbon labelling
the section + lighter-grey body containing the content — and the
operator likes that pattern. `:header` lifts the aesthetic into the
widget so any consumer panel can opt in per mount.

**Surface** — one new opt on the existing `[edn-inspector value opts]`:

| `:header` value     | Behaviour                                                          |
|---------------------|--------------------------------------------------------------------|
| `nil` (default)     | No section wrapper — single-div render (back-compat).              |
| string              | `<section>` + `<header>` ribbon containing the string.             |
| hiccup vector       | `<section>` + `<header>` ribbon containing the hiccup verbatim.    |

The widget treats the hiccup as opaque — no parsing, no required shape.
Consumer panels supply whatever they want (label + code chip + per-
inspector affordances):

```clj
[ei/edn-inspector counter-db
 {:panel-id :rf.xray/app-db
  :site-id  :app-db/counter
  :header   [:span
             [:strong "Counter app"]
             " · "
             [:code ":rf/default"]
             [:button {:on-click reset-counter!} "reset"]]}]
```

**Three-shade structure** — measured live against the Machine panel's
`focused-event-section` (pair-debug 2026-05-26) and codified through
the existing token table — no new tokens introduced:

```
<section> :bg-2 (light: #ffffff)
          1px solid :border-default
          4px border-radius
          margin-bottom 8px
  <header> :bg-3 (light: #e8e8e8)
           padding 10px 12px
           border-bottom 1px solid :border-subtle
    <!-- the :header value renders here -->
  <div>   :bg-1 (light: #f5f5f5)
          padding 12px
    <!-- the actual edn-inspector tree renders here -->
```

The three-shade ramp (`:bg-2` outer / `:bg-3` header / `:bg-1` body)
reads as a single card with a distinct label band rather than one
continuous block. Theme-aware via the existing `tokens` map — both
light and dark resolve at paint time without a re-render.

**Composition with other opts** —

- **`:popup-affordance?`** — the affordance icon button stays at the
  section's top-right corner. The section establishes the positioning
  context (`position: relative`).
- **`:card?`** (rf2-63ie5) — `:header` provides its own surface chrome,
  so `:card?` is usually redundant when `:header` is present.
  Independent for back-compat; consumers normally pick one.
- **Width measurement / `:ref` / mount-id testid** — when `:header`
  fires, the measurement ref + container testid + `data-rf-mount-id`
  + `data-rf-mode` all migrate to the section so existing DOM-level
  consumers (tests, panel-gallery selectors) keep their selectors
  working against `data-testid container-id` regardless of which
  shape rendered.

**Where to opt in** — per the bead's audit, panels with multiple top-
level inspector mounts that benefit from labelling:

- **App-DB** — counter-app db / machine-app db / routes (three labels).
- **Handler/Event** — event vector / db-before / db-after / fx /
  coeffects (per-slot labels).
- **Issues** — ex-data inspector (when present alongside other
  inspectors in the same panel).
- **Routes** — per top-level slot.

**Where NOT to opt in** — the inspector is already nested in a
labelled container or the mount is inline / table-cell / popup-
internal:

- **Machines panel** — sub-panels already carry the header+body
  pattern; nesting would be card-in-card.
- **Views panel** — already wrapped in its own card chrome.
- **Trace panel** — expanded payloads sit inside a trace row that
  already carries chrome.
- **Popup contents** — the popup overlay supplies modal chrome; the
  inner `edn-inspector` recurses with `:popup-affordance? false`
  (§10.0.7.2) and would equally suppress its own card.

Consumer adoption is per-panel and case-by-case; this section
documents the contract, not a blanket migration.

#### §10.0.11 `:zoomable?` opt — zoom-into-node + breadcrumb (rf2-h71e0; gesture reworked rf2-zl4rs)

Dense app-db trees force the operator to scroll past chrome AND every
intermediate level just to see one deep subtree. Sticky expansion
(rf2-pvsxs / §10.0.6) only solves part of this — even when the path is
fully expanded the surrounding tree consumes screen real-estate and
visual attention.

Zoom-into-node turns the inspector into a focused window onto an
arbitrary subtree. The operator **double-clicks** any container (or
presses **Enter** while it is keyboard-focused); that node becomes the
root of the displayed tree. A breadcrumb trail at the top shows the
path from the original root; clicking any segment zooms back to that
level. Anchors to known mental models — file-explorer double-click-to-
descend, Chrome devtools' object inspector + nav, React devtools'
selected-component focus, IDE nav-to-symbol + back, file browsers'
breadcrumb drill-in.

> **rf2-zl4rs gesture rework.** The earlier rf2-h71e0 design rendered a
> separate `⊙` glyph button next to every container's expand triangle.
> That glyph is **removed**: it crowded the header row, no-op'd in diff
> mode, and added a second per-container interactive control. Zoom-in is
> now a gesture on the container **itself** (double-click / Enter), with
> the focusability + ARIA label the glyph button used to carry moved
> onto the container. The same rework makes zoom apply in the single
> full+diff renderer (see the `:before` composition note below).

**Surface** — one opt on the existing `[edn-inspector value opts]`:

| `:zoomable?` value | Behaviour                                                                                                  |
|--------------------|------------------------------------------------------------------------------------------------------------|
| `false` (default)  | No zoom target, no breadcrumb — widget renders as today (back-compat).                                     |
| `true`             | Every non-empty non-root container is a double-click / Enter zoom target; breadcrumb renders if zoomed.    |

**Gesture** — no glyph. Each non-empty non-root container's outer div
carries:

- `:on-double-click` — re-roots onto that node. `preventDefault`
  suppresses the browser's native dblclick text-selection;
  `stopPropagation` ensures a double-click deep in the tree zooms the
  INNERMOST container rather than an ancestor.
- `:on-key-down` — bare **Enter** (no Ctrl/Cmd/Alt/Shift) re-roots, same
  as the double-click. Every other key passes through untouched, so the
  Esc-zoom-out handler and the global spine bindings (Space/L/j/k/G) are
  never swallowed.
- `:tab-index 0` + `:aria-label "Zoom into <path>"` — keyboard-focusable
  and screen-reader-announced, preserving the a11y the removed glyph
  button provided. `role="button"` is deliberately NOT set: the
  container already nests its own `role="button"` expand triangle, and a
  button-inside-button role is an ARIA nesting violation — a focusable
  labelled region is the correct shape for a composite node.
- `:data-rf-zoom-target "1"` — DOM hook for tooling / tests.

**Triangle owns its own double-click (rf2-6nw3g).** The nested
`role="button"` expand triangle toggles on `:on-click` (which already
`stopPropagation`s each click). It additionally carries an
`:on-double-click` that `preventDefault`s + `stopPropagation`s and
dispatches **nothing** — so a double-click _on the triangle_ never
bubbles to the container's `:on-double-click` zoom. Zoom-in only fires
on a double-click in the container body **outside** the triangle; the
triangle is purely an expand/collapse control.

**Breadcrumb structure** — when a zoom is active, a row above the body
renders `<home> › <seg1> › <seg2> › …`. Each segment is a clickable
button; click dispatches `:zoom-to` with a TRUNCATED prefix of the zoom
path:

- **Home segment** — content is the consumer's `:header` hiccup if
  supplied (the §10.0.10 path — reuses the existing "header is the
  natural identity label" choice). Falls back to a generic "root"
  string. Click dispatches `:zoom-to ... []` (clears the zoom).
- **Segment N** — content is the path-segment label, rendered through
  the same syntax-palette as the renderer's key column (keyword
  magenta, integer orange, string green, …). Click dispatches
  `:zoom-to ... (subvec path 0 (inc N))` — truncates to depth N+1.

**State shape** — per-mount, mirrors the §10.0.6 expansion-slot
pattern:

```
:rf.xray.edn-inspector/zoom
  {[<panel-id> <site-or-mount-id>] <zoom-path-vec>
   ...}
```

A `nil` / empty / missing entry renders the un-zoomed full tree.
Per-mount keying makes two side-by-side mounts zoom independently; a
stable `:site-id` (§10.0.6) preserves the zoom across a panel-leave-
and-return round-trip (App-DB tab switching, focused-event re-mount).

**Events**

| Event id                                  | Payload                              | Action                                                                              |
|-------------------------------------------|--------------------------------------|-------------------------------------------------------------------------------------|
| `:rf.xray.edn-inspector/zoom-to`          | `panel-id mount-id absolute-path`    | Set zoom path; empty path clears the entry.                                         |
| `:rf.xray.edn-inspector/zoom-up`          | `panel-id mount-id`                  | Pop one segment off the zoom path; popping past root clears.                        |
| `:rf.xray.edn-inspector/zoom-reset`       | `[panel-id mount-id]` (both optional) | With args: clear that mount's entry only. Without args: clear the whole slot.       |

**Sub** — `[:rf.xray.edn-inspector/zoom]` reads the slot as a map.
Pure helpers `resolve-zoom-path` + `resolve-zoom-into` project the
map for a given mount into either the stored path vector or the
resolved sub-value (used by the widget's render-time `get-in` walk).

**Keyboard navigation** — two node-local + widget-level bindings:

- **Enter** (zoom IN) — pressing Enter while a non-root container is
  keyboard-focused re-roots onto that node (the gesture's keyboard half;
  the a11y replacement for clicking the removed glyph). The handler
  lives on each zoomable container; it matches bare Enter only and
  `stopPropagation`s on a match so the keypress doesn't double-fire.
- **Esc** (zoom OUT) — when the widget has focus AND a zoom is active,
  Esc dispatches `:zoom-up`. The handler is installed on the outer
  widget container only while `zoom-active?` is true, so unzoomed mounts
  let Esc bubble unchanged. Coordinates with the popup widget's
  Esc-closes-top (rf2-7sdja): the popup's own keydown handler lives on
  its backdrop + dialog and `stopPropagation`s, so an open popup
  intercepts Esc first; subsequent Esc presses (no popup) reach the
  inspector's handler and zoom up.

Esc-zoom-out is now active in diff mode too (zoom applies in the single
full+diff renderer — see the `:before` note below).

**Composition with other opts** —

- **`:popup-affordance?`** — independent. The popup affordance + the
  zoom gesture coexist (popup = open the value in a roomier modal; zoom
  = focus here without opening a new pane). Inside a popup the inner
  inspector recurses with `:popup-affordance? false` (§10.0.7.2) but
  `:zoomable?` survives the recursion so the popup body is itself
  zoom-navigable.
- **`:before` (diff mode)** — zoom applies in the single full+diff
  renderer (rf2-zl4rs supersedes the earlier rf2-h71e0 "diff suppresses
  zoom"). When a zoom is active the widget re-roots `value` along the
  stored path ALWAYS, and re-roots `before` the same way when a
  pre-image is present; the projection is recomputed over the re-rooted
  `(before, value)` pair, so the diff rail / `[N∆]` chip / inline
  `← was X` annotations paint relative to the zoomed subtree exactly as
  they do at the root. Edge cases: a zoom into a wholly-`:added` subtree
  re-roots both halves so the whole subtree reads `:added` (the
  re-rooted before stays the missing-sentinel and the projection
  classifies the subtree green); a stale path (mutated out from under
  the zoom) falls back to the full value via `resolve-zoom-into`. So
  `zoom-active? = zoomable? AND zoom-path non-empty` — the `NOT diff?`
  clause is gone.
- **`:header`** — the hiccup feeds the breadcrumb home segment when
  zoomed (`home-label`).
- **`:default-expanded-depth`** / **`:max-depth`** — applied to the
  zoomed subtree as if it were the root (depth counter restarts at
  `0` from the zoom point). Operator's mental model: "the zoom IS the
  new root."

**Where to opt in** — per the bead's audit:

- **App-DB** — the canonical consumer. Dense top-level trees benefit
  hugely from focusing on a single subtree; the breadcrumb keeps the
  operator's bearings. Per-section mount (user-domain TOP + every
  `:rf/*` area) opts in.
- **Handler/Event** — db-before / db-after / fx / coeffects mounts each
  benefit. Operator clicks deep into one slot to focus the comparison.
- **Machine snapshot drill-in** — operator can zoom into a particular
  snapshot path; the breadcrumb anchors the navigation across
  successive snapshots.

**Where NOT to opt in** —

- **`mini` inline renders** — too small to be useful; the inline span
  has no room for the affordance.
- **Trace expanded payloads** — the trace row already carries its own
  per-row layout; zoom inside a row would conflict.
- **Inspector-card titles / chip mounts** — single-level renders never
  need zoom.

**Skipped target** — the root of the displayed subtree (relative
path `[]`) and any empty container are never zoom targets; zooming into
the current zoom root (or into a container with no subtree to focus) is
a no-op. The renderer composes the absolute path as
`(into zoom-path-prefix path)` so the dispatched `:zoom-to` carries the
full path from the ORIGINAL root, not the currently-displayed root.

#### §10.0.12 Single renderer — value (always) + before (optional) (rf2-n2jig · rf2-6cm03 · rf2-vv3m6 · rf2-e28r3)

The edn-inspector has ONE rendering path. Its inputs are **value
(always supplied)** and **before (optional)**. There is no diff
"mode" axis and no flag that selects a chrome intensity — the
presence of a pre-image is the only signal:

- **`before` present** → the full tree renders WITH inline diff
  annotations. The widget paints the entire §9.1.5.1 R1-R8 grammar:
  the per-leaf annotation layer (R1, R2, R7, R8 — gutter glyphs +
  `← was <prior>` annotations) AND the structural-context layer (R3
  collapsed-container `[N∆]` count chip + R4 single-2px vertical
  gutter rail through each change-bearing subtree). The ancestor
  chain force-expands over any changed descendant, and the depth/
  width auto-expand heuristic is suppressed for unchanged subtrees so
  the operator sees only changed slices plus the root.
- **`before` absent** → the SAME renderer shows the value plainly —
  no annotations, no R3 chip, no R4 rail. "Full" is not a mode; it is
  this single renderer with no pre-image.

> **Lineage (rf2-e28r3).** This collapses the former three-mode
> machinery. rf2-vv3m6 retired the operator-facing `[diff][full]
> [full+diff]` toggle; rf2-e28r3 then removed the `:full-with-diff?`
> opt that internally distinguished the plain `:diff` lens (mode-2 —
> per-leaf-focused, suppressed the rail/chip) from full+diff (mode-3).
> With one path the rail/chip chrome is the only chrome and paints
> whenever a change is present, so the flag — which only ever existed
> to gate that distinction — is gone. The plain `:diff` lens is gone
> with it. An efficiency audit's redundant-per-render-walk finding
> (the projection cache, rf2-4p1vl) is preserved.

**Contract**:

```clj
;; value + before → annotated diff render (R1-R8 grammar)
[ei/edn-inspector after-value {:before before-value}]

;; value only → plain render (no annotations, no rail, no chip)
[ei/edn-inspector value]
```

**Per-surface usage** — the canonical consumer surfaces thread
`:before` ONLY when a real pre-image is present; its absence is the
signal to render plainly. None set any mode flag:

| Surface                              | Call site                                                                                  | Test surface                                                                |
|--------------------------------------|--------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| Epoch HANDLER step `:db`             | `panels/epoch/view.cljs` — `handler-db-diff-block` (effective post-handler db `:db-post-handler` = t1, else `db-before` when no-`:db`-with-flow, else fallback `:db-after`; `:before db-before` · rf2-4wywy / rf2-48oc4) | `data-testid="rf-xray-epoch-handler-db-full-with-diff"`                     |
| Epoch FLOW step `:db`                | `panels/epoch/view.cljs` — `render-flow-step` (path-scoped pre→post diff `:db-pre-flow` (effective post-handler db) → `:db-post-flow` (t2) · rf2-4wywy / rf2-48oc4) | `data-testid="rf-xray-epoch-flow-db-diff-<name>"`                           |
| App-DB panel (per `:rf/*` section)   | `panels/app_db_diff_state.cljs` — `value-body` (one mount; `:before` via `cond->` for a real pre-image; `:added? true` via `cond->` for a slice absent in the focused epoch's pre-image, i.e. the `h/added` sentinel — rf2-227cz §4.3) | App-DB panel-gallery story fixtures + segment-inspector tests               |
| ~~Machine Inspector snapshot drill-in~~ (REMOVED — rf2-g2axio) | ~~`panels/machine_inspector.cljs` — `snapshot-block`~~ — the Machine tab's snapshot drill-in was removed when the tab was reduced to Prev/Next + the SHARED EVENT HANDLER mini-pipeline + the chart (see [`003-Machine-Inspector.md` §Post-collapse Dynamic panel shape](003-Machine-Inspector.md#post-collapse-dynamic-panel-shape-rf2-y9xmf-rf2-8og3k)). `:data` mutations now read off the mini-pipeline cascade rows' inline data-writes. | — |
| Epoch SUBSCRIPTIONS step value cells | `panels/epoch/view.cljs` — `subs-value-cell` container branch (`:before` / `:added?`)      | `data-testid="rf-xray-epoch-sub-row-*"` mount                               |

**Why the input is the value, not a flag** — the widget is a pure-
data Reagent component that takes `(value, opts)`. Keying the chrome
on the natural input (is there a pre-image?) rather than a redundant
boolean keeps the interface minimal and removes the
rf2-kkhss class of bug entirely: there is no flag to forget, so the
rail/chip can never silently fail to paint on a real diff. Audit
trail: rf2-ya3nj 24hr audit, finding M3 (spec drift) → rf2-6cm03 →
rf2-e28r3 (single-renderer collapse, this section).

#### §10.0.13 `:added?` opt — first-run / whole-value-added chrome (rf2-kp7bw)

A FIRST-RUN value is one with no prior state to diff against — a
sub's first cache entry, an app-db key that just appeared. The
scalar SUBSCRIPTIONS branch (rf2-fyd8u) already paints row-level
`:added` chrome (green stripe + leading `+`) for such a value, but
the container branch had no equivalent: a first-run map / vector /
set fell through to a plain edn-inspector mount with `before` nil,
so the widget never entered diff mode and the subtree read as
un-annotated — visually indistinguishable from an unchanged value
on the cascade that introduced it. Canonical repro: the `[:rf/route]`
map sub on a `/counter` view-mount epoch (every sibling scalar sub
painted `:added`; the route map painted plain).

**Contract.**

- **Signal.** `:added? true` (boolean opt). Without an explicit
  `:before`, the widget synthesises the prior side as
  `engine/missing-sentinel`. The projection (`diff.engine/project
  missing-sentinel value`) reports the root op as `:added`, so the
  whole tree paints the green wash + `+` added chrome.
- **Precedence.** An explicit `:before` always wins — a real prior
  value is a genuine diff, not a first run — so `:added?` is a no-op
  when `:before` is supplied.
- **Empty containers.** A first-run EMPTY map / vector / set still
  reads `:added`: the engine reports root `:added` for
  `(missing-sentinel, {})` / `(missing-sentinel, [])`.
- **Sentinel discipline.** The synthesised prior is the **engine's**
  `missing-sentinel` (`:day8.re-frame2-xray.diff.engine/missing`),
  NOT the edn-inspector's `::missing` walker sentinel — only the
  engine value drives the projection to a root `:added` classification
  (the edn-inspector keyword would project as a `:modified` type-flip).

**Consumer call-site** — `panels/epoch/view.cljs` `subs-value-cell`
container branch passes `:added? true` when the row is `first-run?`
and carries no `before`. Test surface:
`first-run-container-sub-renders-added-chrome-test`
(`view_cljs_test.cljs`).

### §10.1 Capabilities (LOCKED per B.9 super-prompt)

1. **Lazy collapsible tree** — hierarchical EDN with expand/collapse.
   Large lists / maps show `[N items]` / `{N keys}` until expanded. Deep
   nesting renders depth-first; only visible nodes hit the DOM. Escape
   hatch: per-node "show as `pr-str`" toggle for very large data.

2. **Inline diff highlighting** — for the focused epoch view, changed
   values are highlighted IN PLACE (left-margin marker + accent color +
   annotation `← was <prior-value>`). Unchanged values dim.
   **No side-by-side before|after** — diff is annotation on a single
   rendered state.

3. **Minimal type coloring** — keywords get a single accent color
   (the only colored type). Strings / numbers / nil / booleans render
   mono. Aids EDN-shape recognition without color-noise.

4. **Clickable paths** — every key/path-segment is a click target.
   **The only interaction on a clickable path is cross-panel propagation
   (App-db ↔ Reactive).** Clicking a path in App-db highlights the
   downstream subs + views in Reactive; clicking a `caused-by ← sub ← path`
   chip in Reactive jumps to that path in App-db. **No** blame popover, **no**
   "show epoch that last changed this path," **no** copy-path, **no**
   copy-value. These were considered and explicitly stripped — they
   create more noise than value (per the polished super-prompt B.9).

### §10.2 Visual language (mockup)

Dense case — deep nested app-db, focused-epoch diff:

```
▾ :cart                                            ← changed
  ▾ :items   [2 items]                             ← changed (was 1 item)
    ▸ 0  {:id 7  :qty 1}
    ▸ 1  {:id 22 :qty 1}                           ← added
  · :state    :submitting                          ← was :idle
  · :total    71.00                                ← was 48.00
  · :discount nil                                  (unchanged · dim)
  · :coupon   nil                                  (unchanged · dim)
▸ :user      {3 keys}                              (unchanged · dim)
▸ :session   {5 keys}                              (unchanged · dim)
▾ :http
  ▾ :in-flight  {1 entry}                          ← changed
    ▸ "h-142"  {:method :post :url "/orders" …}    ← added
  ▸ :history    [42 entries]                       (unchanged · dim)

Glyph legend (left-gutter):  + added · - removed · ~ modified · ◴ children · (space) unchanged
```

Sparse case — 2-key event payload:

```
{:cart-id "c123"
 :qty     2}
```

Sparse case — bare scalar (string fx result):

```
"POST /orders → 201"
```

### §10.3 Keyword accent color (B.9 spec · orange identity — rf2-ad7zx)

**Decision: the single `accent`** (GitHub blue — the locked identity per
[022-Design-Tokens](022-Design-Tokens.md) + [007 §Colour system](007-UX-IA.md#colour-system)).
EDN keyword **data values** are the single coloured type in the renderer; they read in the mode
accent, keeping the keyword token visually consistent across L1 filter pills, L2 spine rows, L3
tab labels, and L4 data values. (The prior `:accent-violet #7C5CFF` keyword tone is retired with
the violet → orange identity change.)

Other types render in `text-primary` (`#E8EAF0`), monospaced. Dimmed
unchanged values render in `dim` / `text-tertiary`.

The diff annotation (`← was <prior>`) renders in
`:text-secondary` at 80% size (12px @ cosy density).

The left-gutter diff glyph follows the cascade-gutter token mapping (§007 / §022): `+` green
(`success`) · `-` red (`error`) · `~` amber (`warning`) · `◴` mode accent · space tertiary.

> **Note:** the **code-block** syntax highlighter (§10.0 `code-block`, the Event-panel HANDLER
> source slot) is a separate surface — code keywords/strings/numbers render per the syntax
> theme (the Figma export's `syntax-*` classes), not the data-value keyword accent here.

### §10.4 Lazy-expansion heuristic

| Depth | Size | Default state |
|---|---|---|
| ≤ 2 | any | Expanded |
| 3 | ≤ 10 children | Expanded |
| 3 | > 10 children | Collapsed (`{N keys}` placeholder) |
| ≥ 4 | any | Collapsed |

Changed children always force the ancestor chain open — operator never
has to expand to find the change. (Implementation: `:diff?` flag on each
node; if true, parent chain `:default-expanded?` true.)

Per-panel override: panels MAY set `:default-depth` to override (App-db
defaults to depth-3-collapsed; Event payload defaults to depth-2-expanded
because event payloads are typically shallow + small).

Per-node operator override (sticky): clicking expand/collapse persists
to `:rf.xray.edn-inspector/expansion {<path>}` so the operator's
disclosure choices survive epoch navigation. **No right-click reset**
(per §10.5 — right-click context menus are explicitly out). Reset by
navigating to a new focused epoch (the sticky override is per-epoch +
path, so a new epoch's tree starts from the default heuristic) or via
the panel-local "Reset expansion" affordance in the Settings menu
(deferred follow-on).

### §10.5 Interaction model

Deliberately tiny — the renderer ships exactly **one** path-related
interaction. Per the polished super-prompt B.9, blame popovers, copy-path,
copy-value, "show epoch that last changed this," and right-click context
menus are explicitly OUT. Operator learns one gesture; applies it everywhere.

| Gesture | Effect |
|---|---|
| **Click node header** (▸ / ▾) | Toggle expand/collapse (lazy disclosure only — not navigation) |
| **Click path segment** | Cross-panel propagation: in App-db → switch to Reactive and highlight subs + views downstream of that path. In Reactive (`caused-by ← sub ← path` chip) → switch to App-db and scroll to that path. **This is the only path-click semantic.** |
| **Hover changed-row** | Subtle background shift only (no popover). The annotation `← was <prior>` is already rendered inline; hover does not reveal additional metadata. |
| **Keyboard `Space`** on focused row | Toggle expand/collapse (same as click on node header) |
| **Keyboard `Enter`** on focused row whose value is a path | Cross-panel propagation (same as click on path segment) |

**Explicitly NOT supported (per locked decision):**

- Right-click context menus on values
- "Copy value" / "Copy path" keyboard shortcuts
- "Show epoch that last changed this path" blame popover
- "Filter-IN on path" affordance from the renderer
- Hover popovers disclosing change history

These were on earlier drafts and have been removed. Future beads that
re-propose them must re-open the B.9 lock with the mayor first.

### §10.6 Cross-panel edn-inspector consistency

All panels use the same renderer. Implementations MUST go through
`tools/xray/src/day8/re_frame2_xray/views/edn_inspector.cljs` (the
first-class widget — rf2-oqa60 phase 1). Per-panel renderers are
`[edn-inspector value opts]` invocations that configure
`:default-expanded-depth` / `:panel-id` / `:max-inline-width`. The
operator's expansion state lives in one app-db slot
(`:rf.xray.edn-inspector/expansion`) keyed by
`[panel-id mount-id path]` — the mount-id auto-generated per
component instance so two mounts in the same panel are isolated.

Phase 1 wires the App-DB panel through the new widget directly; the
remaining surfaces (Trace, Sub, Machine, Issues) reach through the
legacy `views.edn-widget` facade for now, which delegates to
the new widget. Phases 2-4 migrate each surface to call
`[edn-inspector …]` directly. Phase 5 (rf2-q3dzw) **completes** the
subsumption: diff is now an opt-in mode on the same widget
(`:before` opt) and the legacy `edn-inspector.render` engine +
`theme.data-inspector` chrome ns are DELETED. The widget owns the
whole `browse + diff + mini` contract as one source of truth.

### §10.7 Evicted-epoch placeholder

When the operator scrubs onto an epoch evicted from the buffer, every
edn-inspector in every panel renders the same placeholder:

```
┌─ epoch #12 ──────────────────────────────────────────────────────────┐
│  Epoch evicted from buffer.                                          │
│  Increase :epoch-history to retain more.                             │
│  Settings → Buffer → Epoch history.                                  │
└──────────────────────────────────────────────────────────────────────┘
```

The film-strip ◀ / ▶ keeps working — the operator can scrub past evicted
epochs without losing the rest of the spine.

---

## §11 Locked decisions summary

### §11.1 B.6 Sub-layer placement

**(b) inside Reactive + (d) hover-over in App-db.** No peer L4 Subs
panel. (Detailed §3.3.)

### §11.2 B.7 Handler source display

**MVP: (a) + (c).** Handler metadata in the Epoch panel's HANDLER step
+ click-through to editor via existing `:rf.xray/open-in-editor`.

**Stretch: (d).** Compile-time capture as macro metadata — extend
`reg-event-{db,fx,ctx}` macros to stamp the form-source as a string into
the handler's registry metadata. `goog.DEBUG`-gated so production elides.
The Epoch panel's HANDLER step surfaces the source inline when available.
(See §9.1 for the current cascade shape; §2.2 is the retired Event-panel
mockup kept as historical reference.)

This is **substrate work** (modify the `re-frame.core` `reg-event` macro),
not Xray panel work. Filed as substrate bead in §13.

**(b) clojure.repl/source-fn rejected.** JVM-only.

### §11.3 B.8 Performance + buffer requirements

What the panel design needs from the substrate (per §1.4 captured-not-replayed):

| Requirement | Scope |
|---|---|
| Cascade attribution capture | **Focused-event-only** (cheaper). All epochs in buffer carry the bones (which subs ran, which views re-rendered); only the focused epoch needs the full chain attribution payload. Substrate hot-path: emit lightweight rows on every epoch; emit fattened cause-chain rows only when `:rf.xray/focused-dispatch-id` matches. |
| Bounded per-epoch capture | Cap at **50 subs + 100 views per epoch**. The substrate enforces at capture time; the panel shows `+N more` overflow indicator (existing component, `panels/overflow_indicator.cljc`). |
| Buffer retention | Substrate-owned. Xray documents the operator surface as **Settings → Buffer → Epoch history** (current ~100; configurable). |
| Evicted-epoch UX | Per §10.7 — placeholder string in every panel. |
| Sub `:skipped` op | New trace op needed (`:rf.sub/skipped`) — current trace has `:rf.sub/run` only. Without `:skipped`, the "unchanged subs" disclosure in §3.4 cannot render coverage. |

### §11.4 B.10 Open sub-decisions

| Sub-decision | Pick | Notes |
|---|---|---|
| Unchanged subs in cascade | **Dim, collapsed by default with "Show N unchanged"** | §3.4. Toggle in Settings → View. |
| Meta-epoch section ordering | **Fixed order: Event > App-db > Reactive > Trace > Machines > Routing > Issues** | Matches the L3 tab order. Predictable beats dynamic. (rf2-4v67l — Chrome A11y removed in favour of Story's shipped panel.) |
| Epoch panel section default-expansion | **All cascade steps expanded by default; collapsible per-step via header click; collapse-all keyboard `[`** | The Epoch panel IS the handling-pipeline view — collapsing by default would hide the punch. |
| Dispatch-origin display on L2 rows | **Short text label prefix** (`user · :checkout/submit`) | No icon-only or coloured chip — keeps L2 row scannable. Matches the existing L1 ribbon density. |
| Pattern view (4th lens) | **Defer to follow-up bead** | Per super-prompt. The 3-lens model (handling / reactive / state) is sufficient for MVP. |

### §11.5 Views → Reactive → Views label history

**Canonical label: "Views"** (Mike-direction 2026-05-21, ratified
rf2-5i8nn 2026-06-02). The display label briefly considered "Reactive"
(rf2-wyvf2 — pairs with "Event", captures subs+views, reflects the
perspective split) and "View" (rf2-e33ad — the rendered view as the
panel's primary subject), but settled on the plural **"Views"**: the
all-plural-domain-noun convention aligns the L4 tab vocabulary (Views /
Flows / Schemas / Routes / Machines) and matches the Figma export
(rf2-ad7zx). Implementation note: the L3 tab key stays `:views` for
backward registry / share-URL compat — only the **display label**
moves. (`:views` is an internal id, not a user contract; share URLs are
local-only dev surface. Keep the key for the smaller diff unless a
follow-up cleans up display+key together.)

---

## §12 New trace-bus contracts (substrate work · candidates for separate beads)

These contracts must exist in the substrate before the matching panel
content can ship. Each becomes its own bead against the runtime substrate
(`re-frame.core` + `mcp-base`); the Xray panel beads in §13 list them as
prerequisites.

| Contract | Op key | Payload sketch | Used by |
|---|---|---|---|
| **View re-render attribution** | `:rf.view/rendered` | `{:rf.view/id :ns/Component :file ".../X.cljs" :line N :rf.view/cause-event-id <id> :caused-by-paths [...] :rf.trace/dispatch-id <id>}` | Reactive panel · Trace panel · §3.5 |
| **Sub skip attribution** | `:rf.sub/skipped` | `{:rf.sub/id :s/foo :reason :input-unchanged :rf.trace/dispatch-id <id>}` | Reactive panel "unchanged subs" disclosure · §3.4 |
| **Sub value-change + cascade attribution** (rf2-l1jz8) | `:rf.sub/run` | `{:rf.sub/id :s/foo :query-v [...] :value-changed? <bool> :prev-value <v> :value <v> :cascade? <bool> :cause-sub [query-id args]-or-nil}` — value slots redacted at the marks chokepoint; threaded onto the epoch record's `:sub-runs` projection. **Landed** in the framework substrate (Spec 009 §`:rf.sub/run`, Spec-Schemas §`:rf/epoch-record` `:sub-runs`). | Reactive panel "SUBS WHOSE VALUE CHANGED" (§3.1.1.2) + "SUBS THAT CASCADED" (§3.1.1.3) |
| **Cascade aggregate** | `:rf.cascade/captured` | `{:rf.trace/dispatch-id <id> :subs-ran N :subs-skipped N :views-rendered N :flows-recomputed N}` | Optional — emitted at end-of-epoch for fast L2 badge / Reactive summary line |
| **Dispatch-origin tag** | (on existing `:rf.event/dispatched`) | `:tags :rf.event/origin <origin-kw>` per §1.5 taxonomy (landed) | Epoch panel DISPATCH step · L2 row prefix · filter pills |
| **Handler-source string** | (on existing handler registry) | Stamp `:source-string` metadata via macro (DEBUG-gated) | Epoch panel HANDLER step inline source · §9.1 |
| **Flow recompute** | `:rf.flow/computed` | `{:flow-id :inputs-changed [...] :rf.trace/dispatch-id <id>}` | Epoch panel FLOW step |
| **Flow skip** | `:rf.flow/skipped` | `{:flow-id :reason :input-unchanged :rf.trace/dispatch-id <id>}` | Epoch panel FLOW step "dim" rows |
| **Route phase taxonomy** | (on existing `:rf.route/*`) | Confirm `:tags :phase #{:can-leave :can-enter :on-match :settle}` is consistent | Routing panel §7 |

**Per-substrate adapter work for `:rf.view/rendered`:**

- **Reagent**: ratom watch on the component's reactive context fires
  `:rf.view/rendered` with the watch's input-deps.
- **UIx**: hook-firing instrumentation — hook into `useSyncExternalStore`
  callback to emit on render commit.
- **Helix**: hook-instrumented render counter; emit on render commit.

Each adapter's emit is gated on `goog.DEBUG` (cost is non-trivial — only
ship in dev / Xray-bundle builds).

**Focused-event-only attribution (per §11.3).** The substrate enforces:
on every epoch, emit lightweight `:rf.cascade/captured` aggregate
(counts only). Emit fattened per-sub / per-view rows only when the
current epoch's `:rf.trace/dispatch-id` matches Xray's reported focused id (a
read-only flag the runtime extension reads from a per-frame atom Xray
publishes via `register-frame-meta!` or similar). When unfocused, the
runtime drops fattened payloads at emit time, not at consumer time — the
cost is borne only for the epoch the operator is staring at.

---

## §13 Follow-on implementation beads (worker proposals — mayor files)

Each bullet below is a single-bead implementation slice. **Format: title
+ 2-line description + dependencies.** Mayor reviews and files these as
real beads after approving this doc.

### Substrate beads (these gate panel work)

- **rf2-?????** — *Substrate: add `:rf.event/dispatched` `:rf.event/origin` tag.*
  Extend the dispatch macro to stamp `:tags :rf.event/origin <origin-kw>` per the
  §1.5 taxonomy. All call sites in `re-frame.core` + adapter mounts.
  Gates: Epoch panel DISPATCH step, L2 row prefix, B.10 dispatch-origin display.

- **rf2-?????** — *Substrate: add `:rf.sub/skipped` trace op.* Emit at
  sub-evaluation skip site (input-unchanged short-circuit). Carries
  `:rf.sub/id` + `:reason` + `:rf.trace/dispatch-id`. Gates: Reactive panel
  "unchanged subs" disclosure (§3.4).

- **rf2-?????** — *Substrate: add `:rf.view/rendered` trace op per
  substrate adapter.* One per Reagent / UIx / Helix; instrumented at the
  adapter's render-commit boundary; DEBUG-gated. Gates: Reactive panel
  step 8 (§3.5).

- **rf2-?????** — *Substrate: add `:rf.cascade/captured` aggregate.* End-
  of-epoch summary op with subs/views/flows counts. Cheap; emitted every
  epoch. Gates: L2 badge "cascade size", Reactive header summary line.

- **rf2-?????** — *Substrate: add `:rf.flow/skipped` trace op.* Mirror
  `:rf.sub/skipped` for flows. Gates: Epoch panel FLOW step dim-row
  rendering.

- **rf2-?????** — *Substrate: focused-event-only attribution gate.*
  Runtime extension reads a per-frame `:rf.xray/focused-dispatch-id`
  atom; gates fattened cascade-attribution payloads at emit time.
  Gates: B.8 perf budget.

- **rf2-?????** — *Substrate: DEBUG-gated handler source capture
  (B.7 (d) stretch).* Extend `reg-event-{db,fx,ctx}` macros to stamp
  `:source-string` into registry metadata, elided in `goog.DEBUG=false`
  builds. Gates: Epoch panel HANDLER step inline source (§9.1).

### Xray panel beads

- **rf2-?????** — *Xray: shared edn-inspector renderer.* New ns
  `edn_inspector/render.cljs` per §10. Lazy tree, inline diff,
  keyword-accent, clickable-paths, expansion-state app-db slot. All
  panels rebind to this renderer. Includes evicted-epoch placeholder.

- **rf2-?????** — *Xray: Event panel — pipeline rendering.* **(Retired
  proposal — the Event panel was deleted by rf2-5gl5r and superseded by
  the §9.1 Epoch panel.)** Replace `event_detail.cljs` content with the
  numbered pipeline (§2). Reads new `:rf.flow/computed` + handler `:origin`
  tag. Mode-accent stripe (`accent`). Depends on substrate `:origin` +
  `:rf.flow/computed`.

- **rf2-?????** — *Xray: Reactive panel rebuild + rename.* Rename L3
  tab display label `Views` → `Reactive` (key stays `:views`). Replace
  panel content with sub cascade + view re-render (§3). Depends on
  substrate `:rf.sub/skipped` + `:rf.view/rendered`.

- **rf2-?????** — *Xray: App-db panel — downstream-subs overlay.* Add
  the hover popover at §4.4 that lists subs/views downstream of each
  changed path; click `⤴` → Reactive panel. Depends on Reactive panel
  cross-panel API.

- **rf2-?????** — *Xray: Trace panel — focused-epoch scoping + film-
  strip.* Re-scope Trace panel to focused `:dispatch-id` (drop any
  aggregate-across-epochs view). Add `[◀ Prev] [Next ▶]` header. Reuse
  edn-inspector renderer for expanded payloads.

- **rf2-?????** — *Xray: Machines panel — topology-always-visible
  empty-state.* When focused epoch has no machine transition, still
  render the machine topology with "current ●" annotation. Tightens
  §003's case B treatment to keep topology always-visible.

- **rf2-?????** — *Xray: Routing panel — focused-epoch overlay shape.*
  Restructure routing panel content per §7 (always-visible route tree +
  per-epoch overlay). Promote from L3 tab if not already done.

- **rf2-?????** — *Xray: Issues panel — focused-epoch scoping +
  evicted-epoch placeholder.* Re-scope per §8; ensure issues panel
  film-strip respects the "next epoch with ⚠" stretch filter.

- **rf2-?????** — *Xray: shared film-strip header component.* Single
  reusable `[◀ Prev] [Next ▶]` header consumed by every L4 panel. MVP
  chronological; per-panel filter slot for stretch.

- **rf2-?????** — *Xray: L2 epoch timeline — dispatch-origin prefix +
  activity badges.* Render the §1 badge set on each L2 row (⚠ ◆ 🌐 ⚡ 💧
  🌊 ⏲) + the origin tag prefix. Reads new `:origin` tag + cascade-
  captured aggregate.

- **rf2-?????** — *Xray: settings — `:epoch-history` knob + "Show
  unchanged subs" toggle.* Buffer → Epoch history slider (relocated
  from General per rf2-pu9sb; slot stays `:general :epoch-history`);
  View → Show unchanged subs in cascade toggle (default OFF per §3.4).

### Doc-only beads

- **rf2-?????** — *Spec: update §007 to reference §021 for L4 panel
  content.* The Tabs table in §007 currently embeds per-panel hints;
  point the reader at §021 for the canonical content design.

- **rf2-?????** — *Spec: update §003 Machine-Inspector + §004 App-DB-Diff
  + §012 Views to align with §021.* Existing per-tab specs absorb the
  §021 design choices or cross-link forward.

---

## §14 Constraints honoured

| Constraint | Met by |
|---|---|
| **Pre-alpha posture** — clean refinements, no back-compat shims | §11.5 keeps `:views` registry key only because it's internal; no transitional dimming in any panel; no "deprecated section" markers |
| **Xray hot-zone** — design doc work only | This file lives under `tools/xray/spec/`; no `tools/xray/src/` edits |
| **Reagent hiccup + JetBrains Mono** for mockups | All ASCII mockups assume JetBrains Mono rendering; code examples in §2.2 are Reagent-shaped hiccup-equivalent EDN |
| **Inspection-by-default · rewind-by-affordance** | §1.3 restated as binding; every L4 mockup uses film-strip nav (inspection) — Rewind affordance is explicit in the focused-epoch header (existing §002), never bound to scroll/scrub |
| **Captured-not-replayed** | Every per-panel "queries" subsection cites the trace-bus / registry source; §12 lists every substrate gap, none of which is "derive on inspection" |

---

## §14.1 Heading scrub (rf2-6xezz)

Per Mike-direction 2026-05-21 (rf2-6xezz) every L4 Dynamic + Static
panel scrubs its top-of-panel large heading. The L4 tab strip is the
panel-name source-of-truth; the heading was redundant and wasted
vertical space. Content starts immediately under the tab bar (or
under panel-specific filter / toolbar rows where applicable).

Per-panel header icons (`◐` App-db · `⚠` Issues · `◆` Machines · `⬢`
Trace · etc., spec §17.1.5) lived inside the deleted `<h1>` elements
and are also removed. The accent-stripe-style helper (§17.1.3) is
still applied to the outer panel container's left border for the
domain colour stripe; the heading-based stripe is gone.

Typography pass: in-panel sub-headings (e.g. the Epoch panel's section
labels like COEFFECTS / HANDLER / EFFECTS) use body type scale (11px
sans-stack, weight 600, letter-spacing 0.6px, uppercase) — never the
h1/h2 face.

---

## §15 What's deliberately NOT in this design

- **No 4th L4 panel.** The 7-panel set is the contract; sub-layer
  surfaces inline in Reactive + App-db (§3.3).
- **No cross-epoch L4 views.** Per §1.2. Aggregate signals live on L2
  badges only.
- **No pattern-view (4th lens).** Deferred per §11.4.
- **No master-detail Event-vs-Reactive coupling.** They're peers (§1.1).
- **No simultaneous multi-frame display.** Single-frame focus (§1.6).
- **No back-compat for share-URL `:mode` slot.** Already dropped per
  §003.

---

## §16 Cross-references

- [`000-Vision.md`](000-Vision.md) — the canonical "what Xray is"
- [`002-Time-Travel.md`](002-Time-Travel.md) — Rewind affordance (§1.3 referenced)
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) — Machines panel current behaviour (§6 extends; §6.0 + §17.4 add xyflow integration)
- [`004-App-DB-Diff.md`](004-App-DB-Diff.md) — App-db diff (§4 extends with overlay)
- [`007-UX-IA.md`](007-UX-IA.md) — palette tokens, spacing, density (§10 + §17.1 reuse and extend)
- [`012-Views.md`](012-Views.md) — Views panel current behaviour (§3 rebuilds as Reactive)
- [`013-Trace-Consumer.md`](013-Trace-Consumer.md) — trace-op contract (§12 extends)
- [`014-Registry-Catalogue.md`](014-Registry-Catalogue.md) — `:rf.xray/*` ids; new ids implied by §13 + §17.5 land here
- [`018-Event-Spine.md`](018-Event-Spine.md) — `:rf.xray/focus` (every §-scoped panel binds to this)
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) — 5×4 matrix; §6 / §7 are matrix entries
- `ai/prompts/xray-interface-adjustments.md` — canonical super-prompt (local-only); the source of truth for the §0 information-density binding, the §6.0 xyflow path-B lock, the §10.5 data-renderer interaction strip-out, and the §17 UI-design pass
- `ai/findings/2026-05-20-xray-runtime-information-architecture.md` — earlier exploratory analysis (local-only)

---

## §17 Visual + interaction refinements (UI-design pass)

This section is the **critic-worker pass** layered on top of §1-§16.
The earlier sections nailed the **structural design** (what each panel
shows, how panels link, the IA). §17 layers in the **visual + interaction
quality** — palette token mapping, interaction-state matrices, animation
timings, iconography, the Machines panel xyflow mockup with the Xray
palette integration spec, and the follow-on bead candidates that drop
out of the visual pass.

§17 is binding alongside §1-§16: implementation beads MUST cite both
the structural section (which content lives where) AND the §17
subsection that governs its visual presentation.

### §17.1 Visual language spec

#### §17.1.1 Spacing scale

Density is binding (§0); spacing reinforces it. Xray uses a 4-px base
grid — every gap / pad value is a multiple of 4. This grid is already
implicit across the existing panels; §17.1.1 catalogues it so per-panel
implementations stop guessing.

| Token (proposed) | Pixels | Use |
|---|---|---|
| `:gap-0` | 0 | Adjacent inline glyphs (e.g. diff-glyph + value) |
| `:gap-1` | 4px | Tight inline gap (icon → label inside a chip) |
| `:gap-2` | 8px | Between sibling rows in dense tables; between fields in a header row |
| `:gap-3` | 12px | Between major sections inside a panel (e.g. pipeline-step blocks in §2.2) |
| `:gap-4` | 16px | Panel inner padding (top/right/bottom/left) |
| `:gap-5` | 20px | Between distinct cards / canvases (e.g. between per-machine canvases in §6) |
| `:gap-6` | 24px | Between zones inside a panel (DIFF zone ↔ STATE zone in §4.2) |

Padding inside cards (e.g. the canvas frame around each per-machine
xyflow render) is `:gap-3` (12px) — workstation density, not consumer
breathing room.

Catalogued as a follow-on bead candidate (§17.5) — currently the
spacing values are scattered as inline `:padding "10px"` and
`:margin "8px 0"` literals across the panels.

#### §17.1.2 Typography hierarchy

Per Xray convention (§007 + `theme/tokens/type-scale`): **JetBrains Mono throughout** for chrome, labels, prose, AND data — Xray is a single
voice. Inter is reserved for a few high-chrome surfaces (Settings, About);
the L4 panels are mono-uniform.

The type-scale already exists (`theme/tokens/type-scale`). The §17 binding
is **which size goes where**:

| Surface | Size token | Px @ 13px default | Weight |
|---|---|---|---|
| Panel `<h1>` (e.g. `EVENT · :checkout/submit · epoch #42`) | `:display` | ~14px | 600 (semibold) |
| Section headers (e.g. `DISPATCH`, `SUBS RECOMPUTED`) | `:body` | 13px | 600 |
| Step sub-header keys (e.g. `Event`, `Origin`, `Call-site`) | `:body-tight` | 12px | 500 (medium) |
| Step values (the actual data) | `:mono-body` | 12px | 400 |
| Inline annotations (`← was :idle`, `(input unchanged · skipped)`) | `:caption` | ~11px | 400 |
| Edge labels in xyflow canvases (§6) | `:micro` | ~10px | 400 |
| Metadata (`14:32:01.231`, file:line, `+0.2ms` trace timing) | `:caption` | ~11px | 400 italic |
| L2 row text (origin prefix · event-id · badges) | `:body-tight` | 12px | 400/600 mix |

The display face (Fraunces — `:display-stack` in tokens) is **NOT**
used inside Dynamic-mode panels; Fraunces is reserved for Static-mode
landing-page header surfaces (the audit-trail divergence Xray
deliberately drew per rf2-5kfxe.9). The L4 surfaces are mono.

#### §17.1.3 Palette token mapping (orange identity — rf2-ad7zx)

All hex resolves through `theme/tokens` (dark) / theme-CSS-variables (light + HCM). Reconciled
to the Figma export + the locked tokens in [022-Design-Tokens](022-Design-Tokens.md): the brand
/ active / changed signal is the **mode `accent`** (the single GitHub-blue accent, both modes); the prior
`:accent-violet` is retired.

| Role | Token | Note |
|---|---|---|
| **Keyword accent** (data values · the only colored type) | `accent` | per §10.3 + 022 — the single GitHub-blue accent |
| **Changed-value highlight** (left-margin marker + accent color) | `changed` (= `accent`) + cascade-gutter glyph (`+` green / `-` red / `~` amber / `◴` accent) | gutter glyph is the structural signal; the accent is the row tint |
| **Dim-for-unchanged values** | `unchanged` / `dim` (`:text-tertiary`) | per 022 — `unchanged` is an alias of `dim` |
| **Settled-success** (fx settled, no error) | `success` (`#3fb950` / `#1a7f37`) | per 022 |
| **Settled-error** (fx settled with error · issues panel ERROR) | `error` (`#f85149`) for ink; `:red-deep` (`#a83a3a`) for button fills | per 022 |
| **In-flight** (fx still running, e.g. `⏳ #h-142`) | `warning` (`#d29922`) — matches the perf-scale "medium / in-progress" tone | |
| **Stale** (epoch evicted from buffer; placeholder text) | `:text-tertiary` on `:bg-2` | |
| **Border subtle** (between adjacent rows in a list) | `border-subtle` (`#2a2a2a`) | |
| **Border default** (around cards / canvases) | `border-default` (`#373737`) | |
| **Border strong** (focused-row outline before focus-ring overlay) | `border-default` (`#373737`, per §007) | |
| **Background — panel canvas** | `:bg-2` (`#242424`) | |
| **Background — hover row** | `hover` / `:bg-active` (`#2a2a2a`) | |
| **Background — popover** | `:bg-3` (`#2a2a2a`) | |
| **L4 panel header stripe** | the **mode `accent`** (the single GitHub-blue accent, both modes) | rf2-ad7zx — the per-panel domain-colour stripe (§007 Per-L4 panel accent stripe) is superseded by the single mode-accent identity, matching the Figma export's one-accent design (App active-tab `--devtools-active`). |
| **Cross-panel arrow / `⤴` link** | `accent` 600-weight | |
| **Film-strip back/forward chevron** | `:text-secondary` default · `:text-primary` on hover | |

Under Windows High-Contrast Mode (`@media (forced-colors: active)`),
the existing global_styles forced-colors block (§007 / rf2-wxepo)
remaps these as: `Highlight` for focus rings + active rows + the mode
stripe + in-flight markers; `CanvasText` for primary text + neutral
borders + settled-success; `LinkText` for warning / route highlight;
`ButtonText` for chevrons / dismiss / icon ink. New panel content
inherits this remap **for free** as long as it uses the same token
keys; the panel implementer does not write `@media (forced-colors:
active)` rules.

#### §17.1.4 Border / divider treatment

A clean visual rule: **borders mark architectural boundaries; dividers
DO NOT mark within-section continuation.** This keeps the panel from
becoming a grid of boxes.

| Where borders appear | Where they DON'T |
|---|---|
| Around the L4 panel itself (`:bg-2` on `:bg-1`, 1px `:border-default`) | Between sibling rows in a dense list (use `:gap-1` vertical rhythm only) |
| Around each xyflow / SVG canvas (1px `:border-default`) | Between pipeline steps in §2.2 (the numbered rail IS the divider) |
| Between reserved-area sections in App-db (1px `:border-subtle`, full width) | Inside the edn-inspector tree (indentation IS the structure) |
| Around hover popovers (1px `:border-default` + 4px shadow) | Between cells of an inline KV row (whitespace alone) |
| Around xyflow group/parent nodes (1px solid; 1px dashed for parallel-region containers) | Between L4 tabs (the L3 tab strip handles this) |

The "`──────`" full-width separators in the ASCII mockups (e.g. the section hairlines in §4.2 /
§7.2 / §8.2) render as **1px `:border-subtle`** in HTML, not as text characters. The box-drawing
characters in the ASCII are narrative shorthand for the operator to visualise.

#### §17.1.5 Iconography

The mockups in §1-§9 already pick these. §17.1.5 binds them.

**L2 row badges (per §1.1.1 + B.1.1):**

| Glyph | Meaning | Token (text color) |
|---|---|---|
| ⚠ | Issue (error or warning) emitted this epoch | `:red` |
| ◆ | State machine transition this epoch | `:green` |
| 🌐 | HTTP request lifecycle touched (managed-HTTP settle / response) | `:orange` |
| ⚡ | fx-emit child — dispatched from a parent's `do-fx` | `:magenta` |
| 💧 | SSR hydration phase | `:cyan` |
| 🌊 | A flow recomputed | `accent` (mode accent) |
| ⏲ | Timer-triggered dispatch | `:text-tertiary` |

Emoji glyphs are deliberate (consistent with existing Xray
convention). Under HCM, the `@media (forced-colors: active)` block
strips the color; the glyph alone carries the signal — colour is never
alone (§007).

**Per-panel header icons** (rendered to the LEFT of the panel `<h1>`,
8px to the left of the accent stripe):

| Panel | Icon (Unicode glyph) | Token |
|---|---|---|
| Event | ⚡ | `accent` (mode accent) |
| Reactive | ◉ | `:cyan` |
| App-db | ◐ | `:cyan` |
| Trace | ⬢ | `:orange` |
| Machines | ◆ | `:green` |
| Routing | 🌐 | `:yellow` |
| Issues | ⚠ | `:red` |

(rf2-4v67l — the Chrome A11y `✦` glyph row was removed alongside
the panel itself.)

**Film-strip back/forward buttons** (rendered in every L4 panel header):

- `◀ Prev` — left-pointing triangle glyph, 12px JetBrains Mono, hover
  state shifts color from `:text-secondary` to `:text-primary`
- `Next ▶` — mirror of the above
- Both buttons render as 28×20px hit targets (minimum 24×24 for AA
  target-size; 28×20 with 4px vertical padding for the operator's
  fingertip target)

**Cross-panel arrows / link affordances:**

- `⤴` (return arrow) — used in hover popovers to indicate
  "jump to this panel." `accent` (mode accent), 12px.
- `↳` (turn-down arrow) — used in pipeline-step source links + cause
  attribution chips. `:text-tertiary`, 11px.
- `→` (right arrow) — used as a transition glyph in machine-state
  rows (`:populated → :submitting`). `:text-primary`, mono inline.

**Tree-disclosure glyphs** (edn-inspector renderer · §10.2):

- `▾` expanded · `▸` collapsed — both `:text-secondary`, 11px
- `·` leaf-row indent — `:text-tertiary`, 11px

### §17.2 Interaction-state matrix per panel

Every interactive element (row, button, chip, tree-node, edge, etc.)
has a defined state per the matrix below. The matrix is **panel-
uniform** — a hover-state on an Event row looks the same as a
hover-state on a Trace row, with only the panel-domain accent
swapped.

| State | Visual change | Notes |
|---|---|---|
| **Default** | No mod; sits at panel base color (`:bg-2`) | The 90% case |
| **Hover** | Background shifts to `:bg-active` (`#2A2F3D`); transition `120ms ease-out` | NO tooltip pop on hover (per the "co-visible over expand-to-see" principle) — exceptions: the App-db hover popover (§4.4) and the long-keyword 200ms-delayed tooltip (§007) |
| **Focus** | Background as hover + 2px focus-ring outline color `#FBBF24` (the global focus-visible amber from rf2-fxde5); outline-offset 2px; under HCM remaps to `Highlight` | The focus-ring is the existing global Xray convention — panels inherit it for free. NEVER suppress `:focus-visible` per-panel. |
| **Pressed** | Background as hover, transformed `translateY(1px)` for the duration of the click (~60ms); visual feedback only — no layout shift | Applied to film-strip buttons + clickable rows |
| **Disabled** | Foreground at `:text-tertiary`; cursor `not-allowed`; tabindex removed | E.g. "Next ▶" at end of L2 spine; "Open in editor" when source unavailable |
| **Loading** | Skeleton row at `:bg-active` opacity 0.6 with a 1.2s `pulse` animation (interpolated through `--rf-xray-motion-scale` so reduced-motion collapses it) | Used during trace-bus subscription warmup; should be brief (<200ms) |
| **Empty** | Panel-specific empty-state string in `:text-tertiary` at panel-center | Already specified per-panel in §1-§9 mockups (e.g. "No issues in this epoch.") |
| **Error** | Red banner at top of panel (`:red-deep` background, `:white` text, `:gap-2` padding); panel content greys out below at 0.5 opacity | E.g. "Trace bus disconnected — reload to reconnect." Distinct from `Issues` panel content (which IS the panel's purpose, not an error) |

**Focus-ring spec (binding):**
- Color: `#FBBF24` (the global focus-visible amber from
  `theme/global_styles` lines 467-469)
- Width: 2px
- Offset: 2px (per the documented high-contrast hit threshold)
- HCM remap: `Highlight` (per the `@media (forced-colors: active)`
  block at lines 519-528 of `theme/global_styles.cljs`)
- NEVER suppress `:focus-visible` — palette / search inputs that need
  to suppress the default UA outline MUST re-enable the Xray
  focus-visible outline (per the existing convention at lines 454-460)

**Animation timings (binding):**

| Animation | Duration | Easing |
|---|---|---|
| Interaction feedback (hover, focus, press) | **≤ 200ms** (typical 120-180ms) | `ease-out` |
| Panel switch / tab transition | ≤ 400ms — currently 180ms cross-fade (`theme/motion :fade-duration-ms`) | `ease-in-out` |
| Diff flash on changed value | 400ms (`theme/motion :flash-duration-ms`) | `ease-out` |
| Machine-state current-state pulse (§6) | 1.2s | `ease-in-out infinite` |
| xyflow edge "fired this epoch" animation | xyflow built-in (≈ 1s loop) | xyflow default |

All durations multiply through `var(--rf-xray-motion-scale, 1)` so
`prefers-reduced-motion: reduce` collapses them via the existing
`theme/global_styles motion-css` mechanic. Per-panel implementations
MUST use `theme/tokens/duration-css` to build their `animation-duration`
strings — never hard-code ms.

### §17.3 Density choices per panel

Restating + tightening the per-panel density notes (§2-§9) into one
table the implementer reads at panel-build time:

| Panel | Default lines-per-screen | Default expansion |
|---|---|---|
| Event (§2) | ~28-40 visible | Steps 1-6 ALL expanded (the pipeline IS the punch); collapse-all keyboard `[` toggles all |
| Reactive (§3) | ~24-32 visible | Cascade tree fully expanded; unchanged subs collapsed under footer `[Show N unchanged subs ▾]` |
| App-db (§4) | ~30-50 visible | DIFF zone: changed paths fully expanded. STATE zone: depth-3-collapsed per §10.4 |
| Trace (§5) | ~30-60 visible | Each op row collapsed (single line); per-row expand reveals payload via §10 renderer at depth-2-expanded |
| Machines (§6) | ~16-36 (xyflow auto-fit) | Each per-machine canvas auto-fit-on-mount; guards/actions/cancellation lists all expanded |
| Routing (§7) | ~16-30 | Route tree fully expanded (max depth typically ≤ 4); "This epoch" block always expanded |
| Issues (§8) | ~6-24 (variable) | Each issue block fully expanded (severity + op-key + message + path + ex-data); ex-data tree depth-2-expanded |

(rf2-4v67l — the Chrome A11y row was removed alongside the panel
itself.)

Per-panel implementations MUST NOT add a "Compact / Cosy / Comfy"
density toggle inside the panel — the global `--rf-xray-font-size`
knob (Settings → General → Density) is the single density surface
across all panels. Per-panel "default expanded" choices are deliberate
to the lens, not operator-overrideable.

### §17.4 Machines panel — detailed xyflow integration

This subsection sits alongside §6.0; §6.0 documents WHAT we're
building, §17.4 documents WHAT IT LOOKS LIKE — the polished mockup
operators will see.

#### §17.4.1 Focused-epoch overlay applied — polished mockup

```
┌─ MACHINES · epoch #42 ──────────────────────────[◀ Prev] [Next ▶] ─┐
│  ◆  (panel header icon, :green)                                     │
│  ──────────────────────────────────────────────────────             │
│   :rf.machine.cart/lifecycle    :populated → :submitting            │
│  ┌──[xyflow canvas]────────────────────────────────────────────┐ │
│  │                                                                │ │
│  │   ╭───────╮  registered   ╭───────────╮  fired  ╭──────────╮  │ │
│  │   │:empty │ ╴ ╴ ╴ ╴ ╴ ╴▷ │:populated │ ═════▶ │:submitting│  │ │
│  │   ╰───────╯                ╰───────────╯  :submit ╰─────◉───╯  │ │
│  │                              (last seen)            ↑ current   │ │
│  │                                                     (pulses)    │ │
│  │                                                                  │ │
│  │   ╭──────────╮                                                  │ │
│  │   │ :settled │ (registered; no path from current)               │ │
│  │   ╰═════════╯  (final · double-ring)                            │ │
│  │                                                                  │ │
│  │   Edge stroke palette:                                          │ │
│  │     ─── registered, not fired this epoch   :text-tertiary, 1px  │ │
│  │     ═══ fired this epoch (animated)        accent, 2px          │ │
│  │     ╶ ╶ registered, no path traversed       :border-default     │ │
│  │                                                                  │ │
│  │   Node fill:                                                    │ │
│  │     ╭─╮ standard state                     :bg-2 + :border-def  │ │
│  │     ╭═╮ final state                        :bg-2 + 2px :green   │ │
│  │     ╭◉╮ current state                      :bg-2 + 2px :green   │ │
│  │            + 1.2s pulse animation                                │ │
│  │   ┌──────┐                                                      │ │
│  │   │ + − ⛶ │ ← xyflow <Controls> (bottom-left); zoom-±/fit only,  │ │
│  │   └──────┘    no NN% chip, no Reset                              │ │
│  └────────────────────────────────────────────────────────────────┘ │
│   Guards    ✓ :cart-non-empty?                                      │
│   Actions   ✓ :clear-form  ✓ :set-submitting-state                  │
│   Cancellation cascade  (none)                                      │
│                                                                     │
│   :rf.machine.checkout/flow   (no activity this epoch)              │
│  ┌──[xyflow canvas]────────────────────────────────────────────────┐│
│  │   ╭─────╮  ╭─────────╮  ╭──────────╮                            ││
│  │   │:idle│  │:authing │  │:settled  │                            ││
│  │   ╰──◉──╯  ╰─────────╯  ╰══════════╯                            ││
│  │   (current)             (final)                                  ││
│  └──────────────────────────────────────────────────────────────────┘│
└─────────────────────────────────────────────────────────────────────┘
```

#### §17.4.2 Node-shape conventions

| Shape | Meaning | xyflow `data.kind` |
|---|---|---|
| Rounded rect (`border-radius: 6px`) | Standard state | `:standard` |
| Rounded rect + 2px solid `:green` outer + 1px `:bg-2` inner gap (double-ring) | Final state | `:final` |
| **Double-circle** (`border-radius: 50%`) in the mode `:accent` — 3px accent border + concentric inner-gap + inner-ring (stacked `inset` box-shadows) + 1.2s breathing pulse | Current / TO state (most recent visit) | `:current` |
| Dashed/dim circle (`border-radius: 50%`, `2px dashed :text-tertiary`, transparent fill) | FROM state — source of the focused fired transition | `:from` |
| Rounded rect with dashed border (`2px dashed :border-default`) wrapping the focused FROM/TO pair | `active (compound)` focused-transition container | `:compound` |
| Rounded rect with dashed border (`1px dashed :border-default`) | Parallel-region container | `:region` |

Per §6.2 Case C (Figma reconcile · rf2-ad7zx.10): the FROM/TO pair are
**circle** nodes inside a dashed `:compound` container; the FROM is
dashed/dim, the TO/current is the mode-accent double-circle. `:current`
takes precedence over `:from` (a self-transition reads as active, not
dimmed). `:compound` is distinct from `:region` — the former is the
single focused-transition lens, the latter is the parallel-region sibling
container. The accent double-circle replaces the former green single
ring so the active node reads as the Stately/xstate convention the Figma
fixes; it animates via `rf-xray-machine-pulse-active` (the accent
counterpart of the green `rf-xray-machine-pulse`).

**Parallel-machine node-ids are region-qualified (rf2-uo0rc.4).** The
projector walks EVERY region of a `:parallel` machine, projecting each
region's states under a path prefixed with the region-id — region `:a`'s
`:idle` is path `[:a :idle]`, region `:b`'s `:idle` is `[:b :idle]`.
Because `chart.layout/node-id` is injective on the path, same-named
cross-region states mint **distinct** node-ids rather than colliding onto
one node (which previously merged/mis-targeted the xyflow graph). The
region segment is also what makes the grouping addressable when the
`:region` container node-kind is surfaced.

#### §17.4.3 Edge styles

| Style | Stroke | Width | Animated | Meaning |
|---|---|---|---|---|
| `--` (dashed `:text-tertiary`) | `:text-tertiary` | 1px | no | Registered transition, not fired this epoch |
| `──` (solid `:text-tertiary`) | `:text-tertiary` | 1px | no | Same as above, but represents the most-recent traversal in the buffer |
| `══` (thick + animated) | `accent` (the single GitHub-blue accent) | 2px | yes | Transition fired this epoch (the overlay) |

Edge label: `:micro` (`~10px`) JetBrains Mono in `:text-secondary`,
rendered inline on the edge (xyflow's `label` prop), not in a side
legend.

**Transition kinds projected onto edges (rf2-ezqpm).** The topology
projection at `tools/xray/src/.../panels/machines/topology.cljs`
inspects all three first-class transition-source slots a state node
may carry (Spec 005). Each emits its own edge; the edge style table
above applies uniformly to all three kinds (their distinction is
encoded in the label glyph, not the stroke). The label conventions
match `machines-viz/chart.layout/event-segment` (rf2-a2b55, the single
source of truth for the Stately graph-view glyphs):

| Slot | Edge label segment | Edge data flags | Spec |
|---|---|---|---|
| `(:on    state-node)` — event-triggered | `event-id` (ns preserved; `:*` → `* (any)`) | (none) | Spec 005 §`:on` map |
| `(:after state-node)` — delay-fired | `⌚ <delay>ms` | `:after <delay>` | Spec 005 §Delayed `:after` |
| `(:always state-node)` — eventless / transient | `∞` | `:always? true` | Spec 005 §Eventless `:always` |

`:after`'s `<delay>` is the ms-key of the `{ms transition-spec}` map
entry — each entry schedules an independent timer and therefore mints
its own edge. `:always`'s guarded-fork grammar (`[{:target … :guard …}
…]`) forks into one edge per candidate; the per-candidate ordinal in
the edge id keeps every branch addressable in xyflow.

#### §17.4.4 Layout direction + default zoom

- **Direction**: top-to-bottom (elk `elk.direction DOWN`, the
  `MachineChart` default Xray does not override). The original sketch
  called for a left-to-right dagre `rankdir: 'LR'`; the elkjs backend
  that shipped maps `:lr` → elk `RIGHT` / `:tb` → elk `DOWN` and defaults
  to `DOWN`.
- **Default framing**: `fitView` on mount via `:fitViewOptions {:padding
  0.1}` (a fractional viewport padding, not a fixed pixel inset). The
  fit-view `⛶` button in the `<Controls>` cluster re-runs `fitView`;
  Xray additionally re-frames on panel-entry by bumping the `:fit-signal`
  nonce (rf2-6tw7t). There is no `NN%` zoom-readout chip and no `Reset`
  button — the `<Controls>` cluster is zoom-±/fit only (`{:showZoom true
  :showFitView true :showInteractive false}`); see spec/007 §"Controls".
- **Min/max zoom**: 0.2× to 4.0× (`MachineChart`'s `minZoom` / `maxZoom`).
  Wheel-zoom enabled.
- **Pan**: drag-to-pan on the canvas background (xyflow `panOnDrag`);
  node-drag disabled (`nodesDraggable false` — read-only render).

#### §17.4.5 Xray-palette token integration into xyflow style props

Sketched in §6.0. **NOTE (drift reconcile, rf2-r1u79d):** the live
Machines panel renders through the shared machines-viz `MachineChart`,
which carries its OWN Stately-style node/edge styling — the catalogue
below is NOT the live styling source. It survives as the self-contained,
JVM-portable **fallback** palette at
`tools/xray/src/day8/re_frame2_xray/panels/machines/xyflow_style.cljs`
(unit-tested, off the hot path), paired with the
`panels/machines/topology.cljs` `project` projector. The originally-
designed standalone `xyflow_adapter.cljs` ns (§6.0 / §17.5) was never
created.

```clojure
;; tools/xray/src/day8/re_frame2_xray/panels/machines/xyflow_style.cljs
;; The single source of truth for the FALLBACK projector's xyflow
;; visual props (the live panel styles through machines-viz MachineChart).

(ns day8.re-frame2-xray.panels.machines.xyflow-style
  (:require [day8.re-frame2-xray.theme.tokens
             :refer [tokens mono-stack type-scale duration-css with-alpha]]))

(def node-style
  {:standard {:background    (:bg-2 tokens)
              :border        (str "1px solid " (:border-default tokens))
              :border-radius "6px"
              :color         (:text-primary tokens)
              :font-family   mono-stack
              :font-size     (:body-tight type-scale)
              :padding       "6px 10px"}
   :final    {:background    (:bg-2 tokens)
              :border        (str "2px solid " (:green tokens))
              :box-shadow    (str "inset 0 0 0 1px " (:bg-2 tokens))
              :border-radius "6px"
              :color         (:text-primary tokens)
              :font-family   mono-stack
              :font-size     (:body-tight type-scale)
              :padding       "6px 10px"}
   ;; Figma reconcile (rf2-ad7zx.10 · §6.2 Case C): the TO/current state
   ;; is a mode-accent DOUBLE-CIRCLE, not a green single ring.
   :current  {:width         "64px"  :height "64px"
              :border-radius "50%"   ; circle, not rounded-rect
              :background    (with-alpha :accent 10)
              :border        (str "3px solid " (:accent tokens))
              ;; concentric double-ring: inner gap + inner ring
              :box-shadow    (str "inset 0 0 0 3px " (:bg-1 tokens) ", "
                                  "inset 0 0 0 5px " (:accent tokens))
              :color         (:accent tokens)
              :font-family   mono-stack
              :font-weight   600
              :animation     (str "rf-xray-machine-pulse-active "
                                  (duration-css 1200)
                                  " ease-in-out infinite")}
   ;; FROM state — dashed/dim circle (source of the focused transition).
   :from     {:width         "64px"  :height "64px"
              :border-radius "50%"
              :background    "transparent"
              :border        (str "2px dashed " (:text-tertiary tokens))
              :color         (:text-tertiary tokens)
              :font-family   mono-stack}
   ;; `active (compound)` container bounding the focused FROM/TO pair.
   :compound {:background    "transparent"
              :border        (str "2px dashed " (:border-default tokens))
              :border-radius "8px"
              :color         (:text-tertiary tokens)
              :font-family   mono-stack
              :font-size     (:micro type-scale)}
   :region   {:background    "transparent"
              :border        (str "1px dashed " (:border-default tokens))
              :border-radius "8px"}})

(def edge-style
  {:registered          {:stroke       (:text-tertiary tokens)
                         :stroke-width 1
                         :stroke-dasharray "4 4"}
   :registered-traversed {:stroke      (:text-tertiary tokens)
                          :stroke-width 1}
   :fired-this-epoch    {:stroke       (:accent tokens)  ; the single GitHub-blue accent
                         :stroke-width 2}})  ; + xyflow :animated true

(def edge-label-style
  {:fill        (:text-secondary tokens)
   :font-family mono-stack
   :font-size   (:micro type-scale)})
```

The `rf-xray-machine-pulse` keyframe (the green legacy pulse) and the
`rf-xray-machine-pulse-active` keyframe (the mode-accent double-circle
pulse the `:current` node now uses per the Figma reconcile · rf2-ad7zx.10)
both live in the existing `theme/global_styles motion-css` block — added
alongside the existing diff-flash + fade keyframes per the same
`prefers-reduced-motion` collapsing mechanic. The active keyframe
re-states the concentric inner-gap + inner-ring on every stop (box-shadow
sets the whole property each frame) and rides `--rf-xray-accent` so the
halo tracks the mode (orange Dynamic / cyan Static).

### §17.5 Follow-on bead candidates (visual layer)

Each bullet below is a single-bead implementation slice the §17 visual
pass implies. Format: title + 2-line description + dependencies.
Mayor reviews + files these alongside the structural per-panel beads
already drafted in §13.

- **DONE (superseded · rf2-gpzb4)** — *Xray: xyflow integration adapter
  — re-frame2 machine spec → xyflow JSON.* Shipped NOT as the originally-
  sketched standalone `machines/xyflow_adapter.cljs` ns, but via the
  shared machines-viz `MachineChart` (`day8.re-frame2-machines-viz.chart`
  — xyflow + **elkjs** layout) wrapped by Xray's `panels/machine_canvas.
  cljs`. The pure projector + palette catalogue (`panels/machines/
  topology.cljs` `project` + `panels/machines/xyflow_style.cljs`) survive
  off the live path as a unit-tested JVM-portable fallback. xyflow +
  elkjs are machines-viz `devDependency`s (bundle-isolated from
  production).

- **rf2-?????** — *Xray: edn-inspector renderer component — lazy tree
  + inline diff + keyword accent + clickable paths.* New ns
  `tools/xray/src/day8/re_frame2_xray/edn_inspector/render.cljs` per
  §10 + §17.1.3 palette mapping + §17.2 interaction-state matrix. The
  only path-interaction is cross-panel propagation (§10.5). Gates: all
  Dynamic panels.

- **rf2-?????** — *Xray: apply forced-colors palette token coverage to
  all L4 panel borders + accents + film-strip chevrons.* Audit the new
  panel content against the §17.1.3 token table + the existing
  `@media (forced-colors: active)` block; add any missing remaps so
  Windows HCM renders the new chrome correctly. Gates: panel-by-panel
  visual polish.

- **rf2-?????** — *Xray: spacing-scale tokens.* Catalogue `:gap-0`
  through `:gap-6` per §17.1.1 in `theme/tokens.cljc` and migrate the
  ~50 inline `:padding "10px"` / `:margin "8px 0"` literals across the
  panels to the tokenised values. Mechanical sweep; isolated surface.

- **rf2-?????** — *Xray: film-strip header component.* Single reusable
  `[◀ Prev] [Next ▶]` header consumed by every L4 panel. Per §17.1.5 hit-
  target sizing (28×20px) + §17.2 state matrix (hover · focus-ring ·
  pressed · disabled at spine ends). Keyboard `← / →` global binding.
  Gates: panel-by-panel film-strip rollout.

- **rf2-?????** — *Xray: per-L4 panel header icons.* Add the §17.1.5
  Unicode header glyphs (⚡ ◉ ◐ ⬢ ◆ 🌐 ⚠ ✦) to the panel `<h1>` chrome
  via a new `theme/tokens/panel-icon` map. Renders 8px to the left of
  the accent stripe. Mechanical, single-file PR.

- **rf2-?????** — *Xray: L2 row activity badges + dispatch-origin
  prefix.* Already drafted in §13; the §17 visual pass binds the exact
  glyph palette + per-glyph color token + HCM remap. Worker implements
  against §17.1.5 + §17.1.3 mapping.

- **rf2-?????** — *Xray: machine-state pulse keyframe.* Add
  `rf-xray-machine-pulse` keyframe to `theme/global_styles motion-css`
  alongside the existing flash + fade keyframes. 1.2s ease-in-out
  infinite; interpolated through `--rf-xray-motion-scale` for
  reduced-motion collapse. Gates: xyflow current-state node rendering.
