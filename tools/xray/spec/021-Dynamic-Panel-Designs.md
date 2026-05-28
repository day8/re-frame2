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

Optional sections (COEFFECTS, FLOWS, AFTER INTERCEPTORS) are **shown only when present** —
absence is conveyed by omission, not an empty-state line. A throwing handler therefore simply
has no APP-DB CHANGES / later sections; there is **no outcome badge** and **no "db committed"
footer**.

Section order (numbered; optional sections shown only when present):

  1. **DISPATCH** — the event vector + `FROM: <source>` (the dispatch-origin, a
     click-to-source link).
  2. **COEFFECTS** *(optional)* — user-injected coeffects: each id (click-to-source) + the
     value it added to context (`+ [:now] #inst…`).
  3. **EVENT HANDLER** — the flavour (`reg-event-db` / `reg-event-fx`) as a click-to-source
     link + the **syntax-highlighted handler source** in a code block + a **returned
     effects sub-block** (the t1 pre-commit observable: the pending `:db` VALUE + each
     entry of the returned `:fx` vector). Per rf2-ta0y7 (Mike 2026-05-25) the substrate
     stamps the full pending `:db` value onto the `:rf.event/db-pending` (t1) trace event
     under `:tags :rf.event/db`; the panel renders it as an inspectable EDN tree (same
     posture as `:rf.event/fx` — full value, no diff, PDS structural-sharing keeps the
     cost negligible). When the runtime is older than rf2-ta0y7 (no t1 on the stream)
     the block falls back gracefully to a presence-only placeholder pointing at APP-DB
     CHANGES for the committed diff.
  4. **FLOWS** *(optional)* — flows that recomputed + the db path they wrote (the t1→t2
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
  5. **AFTER INTERCEPTORS** *(optional)* — non-standard after-interceptors: each id
     (click-to-source) + the effect it contributed (`+ [:fx :local-storage] {…}`).
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
optional sections (COEFFECTS / AFTER INTERCEPTORS / FLOWS) shown only when present. The `↗`
glyphs mark click-to-source links (DISPATCH origin, each COEFFECT id, EVENT HANDLER, each AFTER
INTERCEPTOR id, each FLOW id). There is **no outcome badge** and **no "db committed" footer** —
absence of a step is conveyed by omission. Diff glyphs follow the cascade gutter (`~` modified ·
`+` added · `-` removed).

Dense case (default — focused epoch is a normal event with coeffects, after-interceptors,
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
│  ③  EVENT HANDLER ↗                                                       │
│   │    (rf/reg-event-db :counter-inc          ← syntax-highlighted        │
│   │      [:rf/db]                                                         │
│   │      (fn [db _]                                                       │
│   │        (update db :counter inc)))                                     │
│   │    ↳ returned effects (pre-commit)                                    │
│   │       :db   pending — see APP-DB CHANGES below for committed diff     │
│   │       :fx   1 entry — see FX below for what ran                       │
│   │             [:dispatch [:title/flow [:rf/init]]]                      │
│   │                                                                       │
│  ④  FLOWS                                  (optional · shown when present) │
│   │    :totals-flow ↗  →  [:totals] recomputed                           │
│   │      + [:totals :sum]  42                                            │
│   │                                                                       │
│  ⑤  AFTER INTERCEPTORS                     (optional · shown when present) │
│   │    :persist-db ↗                                                      │
│   │      + [:fx :local-storage]  {:key "app-state" :value {...}}          │
│   │    :analytics ↗                                                       │
│   │      + [:fx :track]          {:event "counter-inc"}                   │
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

Sparse case (focused epoch is a noisy timer — no coeffects, no after-interceptors, no flows;
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
│   │    (rf/reg-event-db :poll/tick …)        ← syntax-highlighted         │
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
| Registries | Handler metadata (`reg-event-*` form file:line, optional source string when DEBUG-gated) |
| App-db panel (bridge) | Inline diff renderer for the committed `:db` — the APP-DB CHANGES section (reuses the shared renderer §10) |

### §2.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| DISPATCH `FROM: <source>` link | Open-in-editor (Xray's existing `:rf.xray/open-in-editor`) at the dispatch-origin call-site |
| COEFFECTS / AFTER-INTERCEPTORS id ↗ | Open-in-editor at the coeffect / interceptor registration file:line |
| EVENT HANDLER ↗ | Open-in-editor at handler file:line |
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
again to `View` per rf2-e33ad (Mike-direction 2026-05-21) — the
panel's primary subject is the rendered **view** (hover a view-row,
the rendered DOM highlights), with the sub cascade as supporting
context. The internal panel-registry key stays `:views` (never a
user contract). The L4 tab label renders as `View`.

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
- **Views** (right-most — **the focus**) — each tagged re-rendered + *why*.

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

Below the graph, three list sections complete the panel:

- **SUB VALUES** (rf2-e46qs phase 3 of rf2-oqa60) — one row per RUN sub this cascade carrying
  its current value through the first-class edn-inspector widget (`[ei/edn-inspector value opts]`,
  §10). Each row uses a stable per-sub `:panel-id` qualifier under `:rf.xray.reactive-sub-value`
  so two sub-row expansions are independent. Subs whose value carries no `:value` slot (privacy
  redaction / pre-attribution) render a muted no-value placeholder rather than mounting the
  widget with `nil`. Memoised skips (`:recomputed?` false) are omitted — only RUN subs surface.
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
   imperatively; nothing records which paths a Level-1 sub reads (`sub-topology` `:inputs`
   is empty for Level-1; the trace records re-runs + value-changes, not paths). So `app-db`
   is one source node with an arrow to each Level-1 sub — no per-path edges. Only **flows**
   declare `:inputs` paths (precise app-db-path → flow), and flows are not in this graph.
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
│   ▸ {:id :home, :params {}}                                           │
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

### §4.5 No epoch focused (LIVE mode at head)

When the L2 spine is at head (no historical epoch focused), the sections
show the most-recent epoch's state with its diff annotations (head-cascade)
— current db, sectioned. Same render shape — no second mode.

### §4.6 Queries

| From | Reads |
|---|---|
| Trace bus | `:rf/epoch-record` `:db-before` + `:db-after` (existing) for diff; structural-sharing diff per §004 |
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
rows — no extra modal or popout. The List view-mode fallback (§6.2)
trades the canvas for an even denser flat textual list.

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
| Adapter layer | ~100 LoC CLJS — one-way walker from re-frame2 machine spec → xyflow's node/edge JSON. Lives at `tools/xray/src/day8/re_frame2_xray/machines/xyflow_adapter.cljs` (new ns implied by this design). |

**Visual conventions to recreate (Stately reference, Xray palette):**

| Convention | xyflow implementation |
|---|---|
| Nested state containment | xyflow's group/parent-node mechanic. Parent state renders as a containing rect; child states are nested xyflow nodes whose `parentNode` references the parent. |
| Transition edge animation | xyflow's `animated: true` edge prop. Color via Xray palette (`:accent` — the single GitHub-blue accent — for "fired this epoch"; `:text-tertiary` for "registered but not fired this epoch"). |
| Current-state highlight pulse | Custom node CSS class that applies the `pulse` keyframe (~1.2s ease-in-out; CSS-variable interpolated through `--rf-xray-motion-scale` so `prefers-reduced-motion` collapses it). Pulse outline color = `:green` (the panel-domain accent). |
| Auto-layout | xyflow's built-in `getLayoutedElements` helper (dagre algorithm). One-shot layout on first render; cached per machine-id; recomputed only when topology changes. |
| Zoom + pan + fit | xyflow's built-in `Controls` component (re-styled to match Xray's button chrome). Default zoom: fit-on-mount with 20px padding. `[− 100% +] [Fit][Reset]` chrome already shown in the existing mockups maps 1:1 to xyflow's `Controls`. |
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
│ ┌─[Canvas]─────────────────────────────────────[− 100% +] [Fit][Reset]│
│ │  ( idle ) ──→ (( loaded )) ──→ ( error )                            │
│ │                  ↑ current                                          │
│ └────────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ machine :other/flow            (no activity this epoch · current ●)   │
│ ┌─[Canvas]──────────────────────────────────────────────────────────┐ │
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
│ ┌─[Canvas]────────────────────────────────────────[− 100% +] [Fit][Reset]──┐│
│ │ ┌─ active (compound) ──────────────────────────────────┐                  ││
│ │ │  ( idle ) ════════▶ (( loading )) ───→ ( loaded )     │      ( error )   ││
│ │ │   FROM    [:rf/init]    TO/current                    │       ↑          ││
│ │ │           guard: token? [pass]                        │   loading→error  ││
│ │ │           do: fetch!                                  │                  ││
│ │ └───────────────────────────────────────────────────────┘                 ││
│ │  FROM = dashed/dim · TO = double-circle, mode-accent, pulse                 ││
│ │  fired edge = mode-accent 2px animated · registered = dim 1px · error = red ││
│ └────────────────────────────────────────────────────────────────────────────┘│
│ guards: token? [pass]    actions: [fetch!]    after: ◴ 5s → :timeout         │
│ Cancellation cascade (none)                                                  │
└──────────────────────────────────────────────────────────────────────────────┘
```

Per §003, the interactive chart adapter (zoom / pan / fit / Canvas|List
view-mode) wraps each per-machine canvas. **The Canvas mode is the
xyflow surface described in §6.0**; the List view-mode is a flat
xyflow-free fallback for accessibility / low-power devices (preserved
from §003 — unchanged here). The mode-accent fired-edge / double-circle
active-node / dashed compound container / inline guard+action labels +
the `after: ◴ Ns → :event` countdown ring are the visual elements the
Figma design fixes; xyflow renders them with the §6.0 palette mapping.

Layout direction: **left-to-right by default** (matches typical state-
machine convention; xyflow's dagre layout option `rankdir: 'LR'`).
Operator can flip to top-to-bottom via Settings → View → Machines layout
direction (deferred to follow-on bead). Default zoom: fit-on-mount with
20px padding around the bounding box of all nodes.

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
   Click a route → its definition / source coord.

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
| **COEFFECT** | `:rf.cofx/run` (or `:rf.event/run-end` `:rf.event/coeffects` fallback) | **one COEFFECT step per user-injected coeffect** (rf2-s1jw4 · Mike pair-debug 2026-05-26, commit `ee9def224`). SYSTEM defaults `:db / :event / :frame / :source / :trace-id` are filtered at projection time (rf2-cq0ch). A cascade with 3 user cofx injections renders 3 numbered COEFFECT entries, not 1 entry containing 3 rows. |
| **HANDLER** | every epoch settles through a handler | always |
| **FLOW** | `:rf.flow/recomputed` | only when flows fired |
| **FX** | `:rf.fx/handled` / `:rf.fx/override-applied` / `:rf.fx/skipped-on-platform` | only when fx-handlers fired |
| **SUBSCRIPTIONS** | `:rf.sub/run` / `:rf.sub/skip` | only when subs recomputed |
| **VIEWS** | `:rf.view/render` | only when views re-rendered |

Steps are numbered DYNAMICALLY 1..N — an absent OPTIONAL step
consumes no number; absence is conveyed by OMISSION, not an
empty-state line. This matches the §2.2 dynamic-numbering contract
from the Event lens — both panels share the same "absence is
silence" rhythm.

### §9.1.4 Badge taxonomy (the 8-badge inventory)

Each step renders a uppercase badge pill at its numbered circle:

| Badge | Token | Hue family |
|-------|-------|------------|
| `:DISPATCH`           | `:text-tertiary` | muted grey |
| `:COEFFECT`           | `:magenta`       | purple |
| `:HANDLER`            | `:accent`        | blue (single Xray accent) |
| `:FLOW`               | `:accent`        | blue (paired with HANDLER) |
| `:FX`                 | `:orange`        | functional amber (post-commit irreversible) |
| `:SUBSCRIPTIONS`      | `:magenta-pink`  | pink (rf2-cgm4f split from COEFFECT violet) |
| `:VIEWS`              | `:success`       | green |
| `:SCHEMA-HOT-RELOAD`  | `:warning`       | warning amber (rf2-17vxj · renamed rf2-xgeag for the narrowed hot-reload-only scope) |

> **Retired 2026-05-26 (pair-debug, rf2-zkiu5):** the prior `:CHILD-DISPATCHES`
> + `:APP-DB-DIFF` rows were dropped. CHILD DISPATCHES (originally rf2-yx1ae,
> Mike commit `eccb6db1b`) is redundant with the FX step, which already
> surfaces every `:dispatch` / `:dispatch-n` / `:dispatch-later` fx entry
> per row. APP-DB DIFF (originally rf2-rrykz, dropped earlier same session
> in commit `ee9def224`) is redundant with the HANDLER step's `:db`
> sub-section + its `[diff][full][full+diff]` toggle (§9.1.5.1), which surfaces the same data
> in-context. The projection's `badge-set` enforces the 8-entry inventory.

The inventory is LOCKED — the projection's `badge-set` enforces
that every emitted step's `:badge` is a member, and the view's
colour resolver bails to `:text-tertiary` on an unknown badge so a
future taxonomy extension paints something but tests catch the
omission.

### §9.1.5 Handler-type adaptation (rf2-82a0u prerequisites · rf2-u69j7 cascade redesign)

The HANDLER step's body adapts to the handler's flavour, discovered
from the trace stream:

- **`:reg-event-db`** — `:db` diff only (the simplest case).
- **`:reg-event-fx`** — `:db` diff + per-fx-entry block.
- **`:reg-machine`** — **TIME-ORDERED MACHINE CASCADE** per rf2-u69j7.

#### Machine cascade (rf2-u69j7)

**Stance.** The pre-rf2-u69j7 layout grouped machine activity into 7
category sub-sections (TRANSITION / GUARDS / LIFECYCLE / AFTER-TIMERS
/ DATA REDUCTION / SNAPSHOT DIFF / FX). That was a roll-up — the
operator had to scroll up/down across categories to reconstruct what
actually fired in what order. The redesign replaces the category-
grouped layout with a single **time-ordered cascade view**: one row
per substrate emit, ordered by the trace buffer's insertion order.

**Order is the substrate's.** The substrate already emits in cascade
order (guards → exit actions → transition → entry actions → always →
after-action → timer-cancels — Spec 005 §Trace events + rf2-82a0u).
The panel never re-sorts; it walks `:trace-events` and surfaces every
member of `machine-cascade-trace-ops` as a row in the same order:

  | Trace op                          | Row `:kind`     |
  |-----------------------------------|------------------|
  | `:rf.machine/guard-evaluated`     | `:guard`         |
  | `:rf.machine/action-ran`          | `:action`        |
  | `:rf.machine/transition`          | `:transition`    |
  | `:rf.machine.timer/cancelled`     | `:timer`         |

**Per-row chrome.** Each row carries:

- **Step ordinal** (1..N) — left-rail compact monospace chip.
- **Kind pill** — colour-coded (`:guard / :action / :transition /
  :timer`) — see `badge.cljc` for the hue assignments.
- **Phase chip** (`:action` rows only) — one of the rf2-82a0u closed
  set `:exit / :transition / :entry / :always / :after-action /
  :initial-entry / :destroy-exit`.
- **Verb** — the action-id / guard-id / transition headline / timer
  state, rendered as a click-to-source button when the machine spec's
  `:rf.machine/source-coords` index (rf2-8bp3) carries a `{:file :line}`
  for the row's spec-path. Falls back to a plain coloured span when
  no coord was captured.
- **Duration chip** — right-aligned monospace; paints long-step
  warning chrome (`▲` + warning tone) when `:duration-ms` exceeds
  `projection/long-step-threshold-ms` (16ms — one 60Hz frame).
- **Outcome chip** — kind-specific:
  - `:guard` → `✓ pass / ▲ fail / ✗ threw`
  - `:action` → `✓ ok / ✗ threw`
  - `:transition` → `N microstep(s)` (the headline)
  - `:timer` → `· cancelled (<reason>)`

**Per-row body — interleaved source code (always visible).** Every
cascade row carries source visibility (rf2-wwc3j extends rf2-u69j7's
named-only coverage to inline-fn / transition / timer rows). The body
renders the source form pulled from the registered machine spec via
`edn/code-block` (clojure-syntax highlight; same widget the HANDLER
source block uses). Source-key dispatch (`projection/cascade-row-
source-key`) returns the spec-path tuple under which the macro
stamped the per-element source-coord (rf2-8bp3):

| Row kind | id flavour              | spec-path key                                         |
|----------|-------------------------|-------------------------------------------------------|
| `:action`| keyword `action-id`     | `[:actions <id>]` (definition-site stamp)             |
| `:action`| inline-fn, `:entry`     | `[:states <target-state>... :entry]`                  |
| `:action`| inline-fn, `:exit`      | `[:states <source-state>... :exit]`                   |
| `:action`| inline-fn, `:transition`| `[:states <source-state>... :on <event> :action]`     |
| `:guard` | keyword `guard-id`      | `[:guards <id>]` (definition-site stamp)              |
| `:guard` | inline-fn               | `[:states <source-state>... :on <event> :guard]`      |
| `:transition` | —                  | `[:states <source-state>... :on <event>]`             |
| `:timer` | —                       | `[:states <state>...]` (parent state-node, D1 shape)  |

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

For `:transition` rows, the body renders the transition map literal
(EDN). For `:timer` rows, the body is elided (the parent state-node
is too verbose to render verbatim); the click-to-source chip on the
verb is the primary affordance, opening the operator on the state
that owns the `:after` slot.

Always visible by default per the bead body's "interleaved source
code" requirement — the operator reads what ran AND its code at the
same vertical position without expand/collapse gestures. Source-
missing fallback renders a muted `<source not yet captured>`
placeholder so the slot is consistently present.

**Per-row outcome detail.** Action rows surface inline:

- **`↳ data Δ`** — when the action returned a `:data` map, the delta
  the action contributed (via `ei/mini`).
- **`↳ fx`** — per-action fx-id chips for each effect the action
  emitted (same data the FX step's `:attributed-to` chip surfaces,
  now visible IN the action's row).
- **`✗ threw`** — when the action threw, an error chip + exception
  message.

Transition rows surface the `state {:from} → {:to}` chrome with the
event vector that drove the cascade plus the transition-map source
body (rf2-wwc3j). Timer rows surface only the header + click-to-
source chip (no inline body — cancellations are housekeeping; the
chip routes to the `:after`-bearing state node).

**Empty-state correctness** (acceptance #4 — rf2-u69j7). A vanilla
`reg-event-db` cascade (or any non-`:reg-machine` flavour) renders
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
`from → to` snapshots; actions render their data-write + fx
attribution + source body; no separate DATA REDUCTION / SNAPSHOT
DIFF block lands. The FX section's per-action `:attributed-to`
chip stays in place (the FX step is the post-commit lens; the
cascade row is the action-attribution lens — same data, two
surfaces).

The flavour discriminator runs at projection time as a pure-data
check against the trace stream — no spec read, no registry lookup,
no replay. The substrate enhancements in #2155 (rf2-82a0u) are the
prerequisite that lets every cascade row read directly off the
trace.

#### §9.1.5.1 HANDLER `:db` diff-mode toggle — three-mode `[diff][full][full+diff]` (rf2-n2jig)

The HANDLER step's `:db` sub-section carries a three-button toggle:

| Mode | Label | Renders |
|------|-------|---------|
| `:diff`      | `diff`      | Flat path-prefixed change list (one row per change). |
| `:full`      | `full`      | Full post-cascade `:db-after` via the edn-inspector. No diff chrome. |
| `:full+diff` | `full+diff` | **Mode-3** — full data tree WITH inline diff annotations per the R1-R8 grammar below. |

**Default**: `:full+diff` (Mike pair-debug 2026-05-27 — the operator's
most-useful default; shape + delta in one read). Mode persists via
`:rf.xray.epoch/db-diff-mode` so the operator's preference survives
focus shifts.

> **Migration**: the prior two-button `[diff][all]` toggle was retired
> 2026-05-27 (pre-alpha, no shim). No `:all → :full` translation lives
> in the sub — Xray app-db is in-memory only, so a pre-rf2-n2jig
> `:all` reading can't survive a reload.

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

#### §9.1.5.2 Universal three-mode toggle adoption (rf2-yqjrd)

Per Mike's pair-debug 2026-05-27 the toggle established in §9.1.5.1
applies to **every Xray surface that surfaces a `(before, after)`
data view**, not just the HANDLER step's `:db` sub-section. The
adoption uses ONE shared widget at
`day8.re-frame2-xray.views.diff-mode-toggle/diff-mode-toggle` so the
operator sees the same `[diff][full][full+diff]` button-bar shape
regardless of panel.

**Affected surfaces** (post-rf2-yqjrd):

| Surface                           | Sub                                       | Set event                                       | Default |
|-----------------------------------|-------------------------------------------|-------------------------------------------------|---------|
| Epoch HANDLER step `:db`          | `:rf.xray.epoch/db-diff-mode`             | `:rf.xray.epoch/set-db-diff-mode`               | `:full+diff` |
| App-DB panel (panel-wide)         | `:rf.xray.app-db/diff-mode`               | `:rf.xray.app-db/set-diff-mode`                 | `:full+diff` |
| Machine Inspector snapshot drill-in | `:rf.xray.machine-inspector/diff-mode`  | `:rf.xray.machine-inspector/set-diff-mode`      | `:full+diff` |
| Epoch SUBSCRIPTIONS step value cells | `:rf.xray.epoch/subs-value-diff-mode`  | `:rf.xray.epoch/set-subs-value-diff-mode`       | `:full+diff` |

All four surfaces install their sub + event pair via the shared
`day8.re-frame2-xray.views.diff-mode-toggle/reg-mode-sub+event!`
helper (rf2-0cyjm / rf2-44xya) — one call per surface, identical
naming convention enforced at the source.

Per-surface mode behaviour follows the same vocabulary as §9.1.5.1's
HANDLER `:db`:

- `:diff` — pure-diff lens (flat path-prefixed change list).
- `:full` — pure-data lens (live current-state, no `:before`).
- `:full+diff` — mode-3 combined lens (full tree + R1-R8 grammar
  annotations against `:before`).

**R-rule applicability across surfaces**: all R1-R8 rules from
§9.1.5.1 apply uniformly to every surface in `:full+diff` mode.
Rules that have no work to do on a given value shape trivially
no-op rather than special-cased per surface — e.g. R6 (vector
shift-detection) no-ops on map containers; R2 (key glyph) no-ops
on primitive cell values like a SUBSCRIPTIONS row's scalar return;
R3 (collapsed-container `[N∆]` chip) no-ops on leaf scalars. The
shared widget + shared projection engine (`day8.re-frame2-xray.diff.engine`)
hold the grammar; per-surface mounts contribute the
`(before, after)` value pair PLUS the `:full-with-diff?` opt
(§10.0.12) that activates the R3 + R4 structural-context chrome.
Mode-3 (`:full+diff`) call sites MUST pass `:full-with-diff? true`
alongside `:before`; mode-2 (`:diff`) call sites MUST NOT — see
§10.0.12 for the per-surface obligations + the asymmetric silent-
failure cost of getting it wrong.

**Per-surface storage**: every surface registers its own sub +
event pair so the modes are independent (operator's App-DB choice
doesn't override the Machine Inspector choice).

**Canonical `data-testid` + `data-*` shapes** (Cluster F — rf2-7vv8f
+ rf2-xvu24, with rf2-shuxd alignment): post-normalisation every
surface follows ONE shape so browser-test selectors + DOM probes
target a uniform axis. The rule is **testid prefix matches the
sub-id namespace**, giving a single naming root across DevTools
(testid) + Trace (sub-id):

| Surface | Toggle `:testid` prefix | Section-level data-attr |
|---|---|---|
| App-DB panel                          | `rf-xray-app-db-diff-mode`             | `data-rf-xray-diff-mode` |
| Machine Inspector snapshot drill-in   | `rf-xray-machine-inspector-diff-mode`  | `data-rf-xray-diff-mode` |
| Epoch HANDLER step `:db`              | `rf-xray-epoch-handler-db-diff-mode`   | `data-rf-xray-diff-mode` |
| Epoch SUBSCRIPTIONS step value cells  | `rf-xray-epoch-subs-value-diff-mode`   | `data-rf-xray-diff-mode` |

Per-button testids combine the prefix with the canonical mode suffix
(`-diff`, `-full`, `-full-with-diff`) so `[data-testid$="-diff-mode-
full-with-diff"]` matches the active-button of any surface in mode-3.
The active button itself reports `aria-pressed="true"` — that is the
single source of truth for "which mode is selected" (rf2-xlmhh — the
toggle bar's redundant `data-mode` retired per `tools/xray/spec/
Conventions.md` §264; section-level `data-rf-xray-diff-mode` survives
as an enclosing-section decoration only).

**Discoverability label** (rf2-fytu4): every consumer passes
`:label "View"` to the shared widget. The widget renders the label
inline with the bar (`View [diff][full][full+diff]`) so a first-time
operator has a contextual anchor on every surface — no per-surface
hand-rolled labels.

**Retired by rf2-yqjrd**:

- The Machine Inspector's prior Before/After CSS-Grid side-by-side
  layout (rf2-3d987 issue #3 chrome). Mode-3 (`:full+diff`)
  carries the same meanings — full AFTER state + diff context +
  comparison — in a single unified mount via the R1-R8 grammar.
- Per-section auto-routing in App-DB (the `:before` sentinel
  branch). Replaced by an explicit panel-wide toggle so the
  operator chooses the lens.

**Note on Story snapshot identity**: the bead (rf2-yqjrd) listed
Story snapshot as a fourth surface. Investigation 2026-05-27:
Story's `snapshot-identity` (per rf2-zfy1e) is a content-hash
string consumed by visual-regression services — there's no value-
diff UI surface in Story to retire. The universal toggle adoption
is therefore limited to the three Xray surfaces above.

**Single canonical diff engine** (rf2-xuyac, 2026-05-27): every
universalised `:diff` lens (App-DB panel · Machine Inspector
snapshot · Epoch HANDLER `:db`) routes through the canonical
Editscript-A* engine at `day8.re-frame2-xray.diff.engine/project`
and consumes the same `:flat-rows` channel — projected into the
universal 4-tuple shape `[path before after op]` at the call
site. Before rf2-xuyac the App-DB + HANDLER `:db` `:diff` lenses
still routed through the home-grown
`app-db-diff-helpers/diff-paths` walker (a structural-sharing
key-walker, not Editscript); engines disagreed on R6 vector-shift,
R7 type-change, and R8 redaction, and an operator switching from
`:full+diff` mode-3 to the `:diff` lens of the same data saw
different chrome. Post-migration the comparison is engine-stable:
same `(before, after)` → same `:flat-rows` → same chrome → identical
R-rule application. The home-grown walker is retained ONLY for
the trace panel's `db-changed-diff-triples` (out of scope for
rf2-xuyac; see §9.1.5.3 for the residual surface).

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

The HANDLER step's flavour label (e.g. `reg-event-db` / `reg-event-fx`
/ `reg-event-ctx` / `reg-machine`) IS the click-to-source affordance —
the verb itself is the hyperlink, with an external-link glyph (↗)
trailing inside it. The earlier rf2-ehd8v shape parked a separate
`file:line + [open]` sub-header below the HANDLER header; Mike's pair-
debug commit `ee9def224` (2026-05-26) collapsed that sub-header into
the verb so the affordance is read inline with the cascade rhythm
(one link per step, no second-line chrome).

**Contract** — implemented by `handler-verb-link` in
`tools/xray/src/day8/re_frame2_xray/panels/epoch/view.cljs`:

- **Source-coord read.** Pulls `(rf/handler-meta :event event-id)` for
  vanilla flavours and `(rf/handler-meta :machine event-id)` for
  `:reg-machine`. Coord shape: `{:file <string> :line <int>}` (the
  registrar-meta surface; NOT a trace read).
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
  downstream in the `:rf.editor/open` reg-fx via the rf2-cm93v
  launcher allowlist.
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

**`coord-chip` vs `open_in_editor/open-chip`** — two surfaces co-
exist by design. `coord-chip` (the dispatch-based button used inside
xray panels) routes through the trace bus so the click is observable
as a first-class operation on `:rf/xray`. `open_in_editor/open-chip`
is a SEPARATE surface that renders an `<a href="…">` anchor with the
URI pre-resolved against `editor-uri/editor-uri`; it is used by demo
surfaces + the standalone static page where no trace bus is
available. (rf2-evgf5 / rf2-g5q8d decision.)

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
| `:after-timer`       | `from :after timer · 250ms on [:active :authenticating]`      | the state-path is click-to-source on the machine spec's `:rf.machine/source-coords` index (rf2-8bp3); falls through to a plain monospace span when no coord captured |
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
`:parent-dispatch-id → :parent-epoch-id` against the Xray
`:epoch-history` slice threaded through `pipeline-view`'s `ctx`
(`(proj/find-parent-epoch epoch-history parent-dispatch-id)`). When
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

### §9.1.9 Queries

| Sub | Reads | Yields |
|-----|-------|--------|
| `:rf.xray/epoch-pipeline` | `:rf.xray/focus` · `:rf.xray/epoch-history` (via the shared `panels.shared.focus-resolver`) | `{:status :no-focus | :focused | :epoch-evicted, :epoch-id, :record, :steps}` |
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
| `:coeffect` | `:rf.cofx/run` (preferred) or `:rf.event/run-end` `:rf.event/coeffects` (fallback) | `:rf.cofx/id`, `:rf.cofx/value`, `:rf.cofx/elapsed-ms` (rf2-w2r4p aligned the per-cofx duration read against the substrate's canonical name + threaded `:duration-ms` through the `cofx-steps` flattening) — SYSTEM defaults `:db / :event / :frame / :source / :trace-id` are filtered (rf2-cq0ch). **Projection splits each surviving cofx into its own numbered step** (rf2-s1jw4 · pair-debug 2026-05-26): `cofx-steps` is a `mapv` over `cofx-rows` producing `{:step :coeffect :badge :COEFFECT :id <kw> :value <v> :duration-ms <ms>}` per entry, spliced into the steps vec before HANDLER. |
| `:handler` duration | `:rf.event/run-end` `:rf.event/elapsed-ms` (rf2-slnce aligned the per-handler duration read against the substrate's canonical name — see `re-frame.router/emit-run-end-trace`) |
| `:handler` source | `(rf/handler-meta :event id)` → `:rf.handler/source` (rf2-66wis · NOT a trace read — registrar meta). The `{:file :line}` coord on the same meta drives the HANDLER verb-as-link affordance (§9.1.6.1 · rf2-ehd8v + pair-debug 2026-05-26). |
| `:handler` machine cascade (rf2-u69j7) | `:rf.machine/guard-evaluated` · `:rf.machine/action-ran` · `:rf.machine/transition` · `:rf.machine.timer/cancelled` (closed set: `machine-cascade-trace-ops`) | guard rows read `:guard-id`, `:outcome` (closed set `:pass / :fail / :threw` — rf2-82a0u); action rows read `:action-id`, `:phase` (closed set `:exit / :transition / :entry / :always / :after-action / :initial-entry / :destroy-exit` — rf2-82a0u), `:outcome` (rich map; `:fx` + `:data` hoisted onto the row), `:input`, `:exception`; transition rows read `:machine-id`, `:event`, `:before`, `:after`, `:microsteps` (state vectors hoisted off `:before`/`:after`); timer rows read `:state`, `:delay`, `:reason` (closed set `:on-exit / :on-destroy / :on-resolution / :on-supersede / :on-frame-destroy` — rf2-82a0u). Source-coord lookup reads `(rf/handler-meta :machine id) → :rf/machine → :rf.machine/source-coords` (rf2-8bp3), keyed by `[:actions <id>] | [:guards <id>]`. |
| `:flow` | `:rf.flow/computed` (NOT `:rf.flow/recomputed` — rf2-yhgk8 aligned the read against `re-frame.flows`'s canonical emit) | `:flow-id`, `:path`, `:before`, `:result` (the view-side `:after` slot maps to the substrate's `:result`), `:elapsed-ms` |
| `:fx` | `:rf.fx/handled` / `:rf.fx/override-applied` / `:rf.fx/skipped-on-platform` | `:rf.fx/id`, `:rf.fx/args`, `:rf.fx/elapsed-ms` (rf2-ipaza aligned the duration read against the substrate's canonical name) |
| `:subscriptions` | `:rf.sub/run` / `:rf.sub/skip` | `:rf.sub/id`, `:rf.sub/query-v`, `:rf.sub/value-changed?`, `:rf.sub/prev-value`, `:rf.sub/value`, `:rf.sub/cascade?`, `:rf.sub/cause-sub`, `:rf.sub/elapsed-ms` (rf2-kfh1v aligned the reads against these) |
| `:subscriptions` disposed (rf2-wpfjo) | `:rf.sub/dispose` (emitted by `re-frame.subs.cache/emit-dispose!` per rf2-mrnur — every cache eviction site funnels through ONE emit shape) | `:rf.sub/id`, `:rf.sub/query-v`, `:rf.sub/reason` (closed set `:no-more-derefers / :hot-reload / :cache-clear`), `:frame`. Surfaced via `projection/disposed-subs-rows`; the SUBSCRIPTIONS step carries an optional `:disposed-rows` slot (omit-by-absence). The step renders a DISPOSED sub-section when populated and reads `N recomputed (...); L disposed` in its header. A dispose-only cascade (no run/skip) still renders the step. |
| `:views` | `:rf.view/rendered` (NOT the simpler `:rf.view/render` marker) | `:rf.view/id`, `:rf.view/deref-subs`, `:rf.view/elapsed-ms`, `:rf.view/mount?`, `:rf.view/triggered-by` (rf2-6djth aligned the read against the rich marker) |
| `:views` unmounted (rf2-gmw1i) | `:rf.view/unmounted` (already emitted by `re-frame.views/emit-view-unmounted!` per rf2-9hoos + rf2-te71r) | `:rf.view/id`, `:rf.view/render-key`, `:frame`. Surfaced via `projection/unmounted-views-rows`; the VIEWS step carries an optional `:unmounted-rows` slot (omit-by-absence when none fired). The step renders an UNMOUNTED sub-section when populated and reads `N re-rendered; M unmounted` in its header. |

### §9.1.10.2 Per-step elapsed time (rf2-nqt3d · rf2-dwuq3)

Each step row carries `:duration-ms` (a number when the substrate
stamped it; nil otherwise). The view paints it as a right-aligned
monospace chip on every step's header — pure-data via
`projection/format-duration-ms`, which formats `0.1ms` / `12ms` /
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
| `:app-db`       | FX step `:db` row (the implicit commit fx) — per rf2-8resu | row-level         |
| `:fx-args`      | FX step, row whose `:fx-id` = `:failing-id`         | row-level (fallback step)|
| `:sub-return`   | SUBSCRIPTIONS step, row whose `:sub-id` = `:failing-id` | row-level (fallback step) |
| `:machine-data` | HANDLER, machine-cascade row whose machine id = `:failing-id` (the failing machine). When the violation triggered full-cascade rollback (`:rollback? true`), the rollback fact is signalled via the §Rollback blast-radius mute pass (no special tail step); the violation itself stays attached to the machine-cascade row. Per rf2-jbbp7 (see [spec/005 §Schema validation](../../../spec/005-StateMachines.md#schema-validation), [spec/010 §Per-step recovery row 7](../../../spec/010-Schemas.md#per-step-recovery)). | row-level (fallback step) |

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
…] :value <root>}`), `decode-malli-explain` extracts a tight
two-line summary that renders ABOVE the humanized map:

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
FX step with `:rolled-back? true`. The view's pipeline wrapper
applies a `:opacity 0.55` overlay to those steps. The FX step
itself stays visible — the violation rides on its `:db` row
(per the attachment table above, post-rf2-8resu) so the operator
reads the failing commit inline with the implicit commit fx. The
operator sees the blast radius at a glance instead of reading
downstream rows that claim success for fx that never actually
fired.

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

### §9.1.10.6 FX section header + per-action attribution (rf2-uffov · rf2-m8ac9)

The FX step has rendered per-fx rows since rf2-sc3r1. rf2-uffov
extends it with header + attribution; Mike's pair-debug commit
`adaabb8aa` (2026-05-26, rf2-m8ac9) reshaped the header. Current
shape:

- **Header chrome** — badge `:FX` (uppercase pill, single-token
  hue per §9.1.4), a muted-italic `(side effects)` caption beside
  the badge, and a threw-count chip in `:error` tone that surfaces
  **only when non-zero**. The prior `N fired (M succeeded, K threw,
  L skipped)` count summary was dropped per Mike's pair-debug
  rationale: the per-row glyphs (`✓ / ✗`) already convey per-fx
  outcome, so the count summary was noise in the step header. The
  threw chip is the at-a-glance error signal that survives.
- **Per-action attribution** — when the cascade was driven by a
  machine handler, each FX row that maps to a fx-id emitted by an
  action's outcome `:fx` slot carries `:attributed-to {:action-id
  …, :phase …}` (rf2-9c27r + rf2-uffov). The view renders an
  italic `← <action-id> (<phase>)` chip alongside the row so the
  operator reads `fx X emitted by action Y in phase Z` in one
  line. Best-effort: first-attribution wins when the same fx-id
  is emitted by multiple actions in the same cascade (cascade
  order).
- **Conditional emit unchanged** — section omits when no fx-handler
  events fired.
- **Args rendering (rf2-ef2hy)** — each fx row's args mount the
  shared edn-inspector widget with `:default-expanded-depth 1`.
  Top-level keys (`:strategy`, `:from`, `:to`) are visible inline;
  nested maps collapse to clickable `▸` chevrons (`{…N keys}`). The
  FX step is a dense table the operator scans, then drills into one
  row's args — depth 1 hits the right scan-then-drill posture.
  `:zoomable?` is on so the popup overlay opens for a complex fx
  args map.

  Sibling: the HANDLER step's `:fx` section (§9.1.10.5 lineage,
  rf2-p2zy0) uses the same widget with `:default-expanded-depth 16`
  (full-expand). Both share the widget; per-call-site depth reflects
  each section's role — HANDLER reads INTENT (full), FX reads
  EXECUTION (compact + drill).

### §9.1.10.7 COEFFECT step chrome (rf2-s1jw4 · pair-debug 2026-05-26)

Mike's commit `ee9def224` reshaped the COEFFECT step from "one
step with N rows" to "N steps, one per injected cofx" (see §9.1.3
+ §9.1.10.1). The accompanying view-layer chrome:

- **Header** — `:COEFFECT` badge + cofx-id button to the right of
  the badge. The button is clickable when
  `(rf/handler-meta :cofx <id>)` returns a coordinate (click-to-source
  jumps through the shared `:rf.xray/open-in-editor` allowlist —
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

The accompanying view-layer chrome:

- **Header** — `:FLOW` badge + flow-id button to the right of
  the badge. The button is clickable when
  `(rf/handler-meta :flow <id>)` returns a coordinate (click-to-source
  jumps through the shared `:rf.xray/open-in-editor` allowlist —
  see §9.1.11); otherwise the id renders as a plain coloured span.
  An external-link glyph trails the id when source-jump is wired.
- **Body** — `<glyph> [path] before → after` diff-style line,
  left-aligned with the badge (no indent), reusing the COEFFECT
  body styles (`coeffect-body-*`). Glyph is `~` for an update
  (both before and after present) or `+` for a first-write (no
  before). Values render through the edn-inspector `mini` widget.
- **Verb dropped** — the prior `N flows recomputed` aggregate
  verb is gone; the per-step expansion of flows makes the count
  visible in the cascade numbering itself (operator scans left
  rail; numbered circle count = flow count).
- **Conditional emit unchanged** — a cascade with zero
  `:rf.flow/computed` events renders zero FLOW steps.

### §9.1.10.5 App-db diff section — RETIRED 2026-05-26 (rf2-rrykz · rf2-zkiu5)

> **Retired pair-debug 2026-05-26** in Mike's commit `ee9def224`. The
> APP-DB DIFF step was a state-mutation lens that rode immediately after
> HANDLER. It was redundant with HANDLER's `:db` sub-section + its
> `[diff][full][full+diff]` toggle (§9.1.5.1), which surfaces the same data in-context. The
> projection no longer emits this step and the `badge-set` no longer
> carries `:APP-DB-DIFF`. Section retained as a stub for searchability;
> historical design intent is reachable via the bead history (rf2-rrykz
> original + rf2-zkiu5 retirement).

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
shared `:rf.xray/open-in-editor` event (rf2-cm93v allowlist) — the
same surface every other L4 panel uses for source jumps. The Epoch
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
The EDN-widget facade at `views.edn-widget.widget` is retained as a
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
- `:before` — when supplied, the widget renders in **DIFF mode**.
  The `value` arg is treated as the AFTER side and the supplied
  `:before` is the prior value. Gutter glyphs (`+` added · `-`
  removed · `~` modified · `◴` children-changed) paint per node;
  modified leaves get an inline `← was <prior>`
  annotation; ancestor chain force-expands over any changed
  descendant. Omit `:before` for plain BROWSE mode. See §10.0.8
  for the full diff contract.
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
- `:full-with-diff?` (rf2-n2jig · rf2-6cm03) — boolean. When `true`
  (combined with `:before`) the widget paints the **mode-3** chrome
  on top of the diff annotations: R3 collapsed-container `[N∆]`
  count chip + R4 single-2px vertical gutter rail through each
  change-bearing subtree. When `false` / omitted (the legacy diff
  surface) the widget renders the **mode-2** chrome — per-leaf
  gutter glyphs + `← was <prior>` annotations, no R3 chip,
  no R4 rail. `:full-with-diff?` is a no-op without `:before`. See
  §10.0.12 for the contract + per-surface call-site obligation. The
  silent-default chosen here is intentional: it preserves the diff-
  only call sites that should NOT paint mode-3 chrome.

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
   via the **lexically-injected frame-aware dispatcher** — the widget
   is `reg-view`-registered (per rf2-y59tb) so its body's `dispatch`
   closure inherits the surrounding `frame-provider` from React
   context. Mounted under `:rf/xray` (App-DB panel) the click lands
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
- Phase 3 (rf2-e46qs) — **Sub value inspector integration.** The
  Views panel (`reactive-panel-view`) renders a `SUB VALUES` section
  beneath the flow graph. Each RUN sub gets one row carrying its
  current cascade value through `[ei/edn-inspector value opts]`
  DIRECTLY (no `edn/inspect` / `edn/browse` facade hop). Each row's
  `:panel-id` is a STABLE per-sub keyword namespaced under
  `:rf.xray.reactive-sub-value` (folded from the sub-id), so two
  sub-row expansions are independent. Sub-runs that carry no `:value`
  slot (privacy redaction / pre-attribution) render a muted no-value
  placeholder instead of mounting the widget with `nil` — distinct
  from a sub whose value actually IS `nil`. Memoised skips
  (`:recomputed?` false) are omitted from the inspector; only RUN
  subs surface.
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

`views.edn-widget.widget/code-block {:source src :lang :clojure}`
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
in `shell.cljs`'s overlay block, INSIDE the `rf/frame-provider
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
  which is per-frame and uses the lexically-captured frame-
  aware dispatcher.
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

Diff renderers' internal `inspect-value` leaves (`diff/hiccup_render.cljs`)
intentionally **do not** carry the affordance — they're inner
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

#### §10.0.11 `:zoomable?` opt — zoom-into-node + breadcrumb (rf2-h71e0)

Dense app-db trees force the operator to scroll past chrome AND every
intermediate level just to see one deep subtree. Sticky expansion
(rf2-pvsxs / §10.0.6) only solves part of this — even when the path is
fully expanded the surrounding tree consumes screen real-estate and
visual attention.

Zoom-into-node turns the inspector into a focused window onto an
arbitrary subtree. The operator clicks the `⊙` zoom affordance on any
container; that node becomes the root of the displayed tree. A
breadcrumb trail at the top shows the path from the original root;
clicking any segment zooms back to that level. Anchors to known mental
models — Chrome devtools' object inspector + nav, React devtools'
selected-component focus, IDE nav-to-symbol + back, file browsers'
breadcrumb drill-in.

**Surface** — one new opt on the existing `[edn-inspector value opts]`:

| `:zoomable?` value | Behaviour                                                                               |
|--------------------|-----------------------------------------------------------------------------------------|
| `false` (default)  | No affordance, no breadcrumb — widget renders as today (back-compat).                   |
| `true`             | Every non-empty non-root container gets a `⊙` affordance; breadcrumb renders if zoomed. |

**Affordance glyph** — `⊙` (circled-dot, U+2299). Reads as "focus / aim
cursor at this node" — visually distinct from the popup affordance (`↗`,
"open in new pane"), the expand triangles (`▸`/`▾`), and the breadcrumb
separator (`›`). Single codepoint, theme-token coloured (`:text-tertiary`
at 0.55 resting opacity, hover bumps to `:text-secondary` via the
`data-rf-affordance="zoom"` selector in the global stylesheet).

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

**Keyboard navigation** — Esc (when the widget has focus AND a zoom is
active) dispatches `:zoom-up`. The handler is installed on the outer
container only while `zoom-active?` is true, so unzoomed mounts let
Esc bubble unchanged. Coordinates with the popup widget's Esc-closes-
top (rf2-7sdja): the popup's own keydown handler lives on its
backdrop + dialog and `stopPropagation`s, so an open popup intercepts
Esc first; subsequent Esc presses (no popup) reach the inspector's
handler and zoom up.

**Composition with other opts** —

- **`:popup-affordance?`** — independent. Both affordances render
  side-by-side when both opts are present — they serve different
  intents (zoom = focus here without opening a new pane; popup = open
  the value in a roomier modal). Inside a popup the inner inspector
  recurses with `:popup-affordance? false` (§10.0.7.2) but
  `:zoomable?` survives the recursion so the popup body is itself
  zoom-navigable.
- **`:before` (diff mode)** — zoom is SUPPRESSED in diff mode. The
  widget self-detects (`zoom-active? = zoomable? AND NOT diff? AND
  zoom-path non-empty`) and renders the full value with the gutter
  glyphs as today. Rationale: diff's force-expand-over-changed-
  descendants logic and zoom's hide-everything-outside-the-subtree are
  conflicting intents; operators view diffs over the full value.
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

**Skipped affordance** — the root of the displayed subtree (relative
path `[]`) never renders the affordance; zooming into the current
zoom root is a no-op. The renderer composes the absolute path as
`(into zoom-path-prefix path)` so the dispatched `:zoom-to` carries the
full path from the ORIGINAL root, not the currently-displayed root.

#### §10.0.12 `:full-with-diff?` opt — mode-3 chrome activation (rf2-n2jig · rf2-6cm03)

The §9.1.5.1 R-rule grammar partitions diff chrome into two visual
intensities — the per-leaf annotation layer (R1, R2, R7, R8) and the
structural-context layer (R3 collapsed `[N∆]` chip + R4 vertical
rail). The widget paints the annotation layer whenever `:before` is
present; it paints the structural-context layer ONLY when the caller
also passes `:full-with-diff? true`.

The split is a contract — not a runtime guess — because the same
widget serves TWO operator intents the universal toggle (§9.1.5.2)
distinguishes:

- **Mode-2 (`:diff`)** — pure-diff lens; per-leaf focused chrome,
  no structural rail. Callers pass `:before` only.
- **Mode-3 (`:full+diff`)** — combined lens; the operator wants the
  full tree AND the rail + chip cues that visually anchor where the
  change-bearing subtrees live. Callers pass `:before` AND
  `:full-with-diff? true`.

**Contract**:

```clj
;; Mode-2 (diff lens) — annotation chrome only
[ei/edn-inspector after-value
 {:before before-value}]

;; Mode-3 (full+diff lens) — annotation + R3/R4 structural chrome
[ei/edn-inspector after-value
 {:before before-value
  :full-with-diff? true}]
```

**Consequence of NOT passing it from a mode-3 call site**: silent
absence of R4 rails + R3 chips. The widget still paints R1/R2/R7/R8
because those are driven off `:before` alone, so the surface looks
"diff-y" at a glance but the operator loses the structural-context
cues that the universal toggle's "full+diff" promise implies. This
is the root-cause class of bug rf2-kkhss (App-DB silently dropped R4
when its mode-3 call site omitted the flag).

**Mode-2 call sites MUST NOT pass `:full-with-diff? true`** —
painting the rail through every change-bearing subtree on the
per-leaf diff lens defeats the lens's per-leaf focus. The
distinction is asymmetric: omitting the flag silently degrades
mode-3; passing it incorrectly silently corrupts mode-2.

**Per-surface call-site obligation** — the four canonical consumer
surfaces from §9.1.5.2 each carry a mode-3 branch; that branch MUST
set `:full-with-diff? true`:

| Surface                              | Mode-3 call site                                                                                       | Test surface (verifies the opt + chrome are wired)                          |
|--------------------------------------|--------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------|
| Epoch HANDLER step `:db`             | `panels/epoch/view.cljs` — `handler-db-section` `:full+diff` branch (rf2-n2jig)                        | `data-testid="rf-xray-epoch-handler-db-full-with-diff"`                     |
| App-DB panel (per `:rf/*` section)   | `panels/app_db_diff_state.cljs` — `display-card`'s `:before`-present branch (rf2-kkhss)                | App-DB panel-gallery story fixtures + segment-inspector tests               |
| Machine Inspector snapshot drill-in  | `panels/machine_inspector.cljs` — `snapshot-block` mode-3 `assoc` (`:before` + `:full-with-diff?`)     | `data-testid="rf-xray-machine-snapshot-block-<id>"` with `data-rf-xray-diff-mode="full+diff"` |
| Epoch SUBSCRIPTIONS step value cells | `panels/epoch/view.cljs` — `subs-value-cell` `:full+diff` branch                                       | `data-testid="rf-xray-epoch-subs-value-cell"` mode-3 path                   |

Mode-2 branches (the per-surface `:diff` lens) omit `:full-with-diff?`
entirely — the flag's silent-false default IS the mode-2 contract.

**Canonical visual reference** — the Story fixtures under
`tools/xray/testbeds/panel_gallery/fixtures_diff_mode_3.cljs` pass
`:full-with-diff? true` on every variant; that is the operator-
facing pin for the mode-3 chrome.

**Why the opt isn't auto-inferred from mode** — the widget has no
read of the surrounding §9.1.5.2 mode sub. The mode lives in the
consumer panel's frame; the widget is a pure-data Reagent component
that takes `(value, opts)`. Threading the mode through opts would
duplicate the surrounding panel's mode choice into the widget's
contract; the boolean `:full-with-diff?` is the minimal handshake
that lets the consumer panel keep ownership of the mode while the
widget keeps a pure data interface. Audit trail: rf2-ya3nj 24hr
audit, finding M3 (spec drift) → rf2-6cm03 (this section).

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
legacy `views.edn-widget.widget` facade for now, which delegates to
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

This is **substrate work** (modify `re-frame.core` reg-event-* macros),
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

### §11.5 Views → Reactive rename

**Pick: (a) "Reactive".** Pairs with "Event"; accurately captures
subs+views; reflects perspective split. Implementation note: the L3 tab
key stays `:views` for backward registry / share-URL compat — only the
**display label** rebases to "Reactive." (Pre-alpha posture says no
back-compat shims, but `:views` is an internal id, not a user contract;
share URLs are local-only dev surface. Keep the key for the smaller diff
unless a follow-up cleans up display+key together.)

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
│  ┌──[xyflow canvas]──────────────────────[− 100% +][Fit][Reset]──┐ │
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

- **Direction**: left-to-right (xyflow `dagre` `rankdir: 'LR'`) — matches
  Stately's convention; matches reading order.
- **Default zoom**: `fitView` on mount with 20px padding around the
  bounding box. The `Fit` button in the Controls re-runs `fitView`.
- **Min/max zoom**: 0.25× to 2×. Wheel-zoom enabled.
- **Pan**: drag-to-pan on the canvas background; node-drag disabled
  (it's a read-only render).

#### §17.4.5 Xray-palette token integration into xyflow style props

Sketched in §6.0; restated here as the canonical reference the
xyflow-adapter bead (§17.5) implements against:

```clojure
;; tools/xray/src/day8/re_frame2_xray/machines/xyflow_style.cljs
;; The single source of truth for xyflow visual props.

(ns day8.re-frame2-xray.machines.xyflow-style
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

- **rf2-?????** — *Xray: xyflow integration adapter — re-frame2 machine
  spec → xyflow JSON.* New ns
  `tools/xray/src/day8/re_frame2_xray/machines/xyflow_adapter.cljs`
  walks `reg-machine` topology + per-epoch transition trace and emits
  xyflow nodes/edges JSON. Plus `xyflow_style.cljs` per §17.4.5. Plus
  Reagent ↔ React mount wiring per §6.0. Depends: xyflow added to
  `package.json` (~50-80KB gzipped, MIT). Gates: Machines panel
  redesign (§6).

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
