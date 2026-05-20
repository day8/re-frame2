# 021-Dynamic-Panel-Designs

Worker design doc for the Causa **Dynamic L4 panel redesign** (rf2-dur6w).
(File previously titled "Runtime Panel Designs" — renamed to track the
locked Static/Dynamic linguistic pairing per the polished super-prompt.
"Dynamic" = what's happening across epochs; "Static" = what's registered
before any event fires.)
Co-drafted by Mike + mayor; this doc is the implementer's reference
for the **per-panel content layout**, **shared data-display renderer**,
**locked decisions**, and **substrate gaps** that the redesign implies.

Canonical foundation: `ai/prompts/causa-interface-adjustments.md`
(local-only working doc; not in repo). The framing in §1 below is a
synthesis — the super-prompt remains the authoritative statement of intent.

Cross-refs:
- [`000-Vision.md`](000-Vision.md) — the five canonical questions
- [`007-UX-IA.md`](007-UX-IA.md) — chrome, palette, density (still load-bearing)
- [`013-Trace-Bus.md`](013-Trace-Bus.md) — substrate the panels read
- [`018-Event-Spine.md`](018-Event-Spine.md) — `:rf.causa/focus` contract
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) — 5 idioms × 4 areas

Owner: tools/causa.

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
| Inline annotations (`← changed from :idle` · `(input unchanged · skipped)`) | Defer to expand-to-see when the data could be shown inline |
| Use color, weight, and inline annotations to LAYER info without spreading | Generous whitespace idiomatic in consumer apps |
| Co-visible related details on one surface (pipeline stages + values + paths together) | Cross-panel scatter for things the operator wants to see together |

**Density baseline (resolved via tokens):** body 13px / mono 12px /
line-height 1.35, per `theme/tokens/type-scale` — already runs ~1px
below the spec's cosy baseline because Causa is an info-dense dev
surface. JetBrains Mono throughout per Causa convention (§007). Every
per-panel section below carries an explicit **density note** restating
this in-context — they are reminders, not exceptions.

This principle is binding on every panel; subsequent design decisions
(default-expanded steps in §2, inline diff annotation over side-by-side
in §10, footer-collapsed unchanged subs in §3.4) all derive from it.

---

## §1 Framing — the load-bearing model

Causa's chrome is two zones, one purpose each:

```
┌──────────────────────────────────────────────────────────────────────┐
│  L1 ribbon · L2 epoch timeline                ← MOVING BETWEEN epochs│
├──────────────────────────────────────────────────────────────────────┤
│  L4 panels (8 lenses on the focused epoch)    ← DEPTH INTO one epoch │
└──────────────────────────────────────────────────────────────────────┘
```

**Top** carries the only cross-epoch signal — the L2 epoch timeline + its
per-row badges (`⚠ ◆ 🌐 ⚡ 💧 🌊 ⏲`) and the dispatch-origin tag prefix
(`user / fx / route / hyd / ws / timer / tool / internal`). **Bottom** is
eight L4 panels each answering "what happened in this epoch?" through its
own lens. **No third axis. No cross-epoch L4 panels.**

### §1.1 The epoch — eight steps, two perspectives

Re-frame2 runs as a sequence of epochs. One epoch = one event's full chain.
The split that organises the L4 panels is **handling vs reactive** —
state-mutating vs state-observing.

| Phase | Steps | What |
|---|---|---|
| **Handling** (state-mutating) | 1 Dispatch → 2 Coeffects → 3 Handler → 4 Effects returned → 5 Effects applied → 6 Flows recompute | The `Event` L4 panel renders these six steps as a linear pipeline. Ends with "db committed." |
| **Pivot** (the keystone) | — | Step 6 → step 7 transition. The architectural inflection. App-db panel sits on this boundary. |
| **Reactive** (state-observing) | 7 Subs recompute → 8 Views re-render | The `Reactive` L4 panel renders the cascade as a DAG. |

The pivot from step 6 to step 7 is the architectural keystone (per A.3
super-prompt). All state mutation is left of the line; all state
observation is right of it. **Event** and **Reactive** are PEERS — not
master + detail — bridged by **App-db**.

### §1.2 Scope rule — every L4 panel is focused-epoch-scoped

Every L4 panel answers "what happened in this epoch?" — each through its
own lens. **No exceptions.** The only cross-epoch signal lives on the L2
timeline as per-row badges (B.1.1 super-prompt; restated in §1 here).

This is binding: workers implementing per-panel beads MUST NOT introduce
"aggregate across epochs" subviews inside L4 — those go on L2 as badges,
or out-of-scope.

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
substrate concern (`re-frame.core` + per-tool `mcp-base`), not a Causa
panel-design concern; Causa reads what the substrate retains.

Per-epoch buffer eviction surfaces as **"Epoch evicted from buffer —
increase `:epoch-history` to retain more"** placeholder text in any panel
the operator scrubs onto for an evicted row (see §10.3 below).

### §1.5 Dispatch origin — the universal classifier

Every epoch carries a dispatch origin tag (per A.5 super-prompt):
`:user` `:router` `:websocket` `:http` `:ssr` `:fx-emit` `:timer`
`:test-harness` `:tool` `:internal`. The Event panel surfaces this prominently
in step 1 (Dispatch); the L2 timeline surfaces it as a short prefix on
each row. There is no such thing as a context-less epoch.

### §1.6 Single-frame focus

Causa observes one target frame at a time, picked via the L1 frame
picker. The L2 timeline lists that frame's epoch stream; the L4 panels
read state from that frame. No per-frame split layouts, no
colour-coding-by-frame on the timeline. Multi-frame apps are inspected
by switching focus.

---

## §2 The Event panel (handling perspective · steps 1-6)

### §2.1 Question

> **"What did this event DO?"**

End-to-end mutation pipeline for the focused epoch.

**Density note** (per §0). Workstation feel: all six steps render
default-expanded; the pipeline IS the punch and hiding it behind
"Show details" would undercut the lens. Target ~28-40 lines visible at
default density on a 1080p screen (the cosy case in §2.2 lands at ~36
lines including arrows). Per-step collapse stays available via header
click for the operator who wants to focus, but is opt-out, not default.

### §2.2 Layout — one-way pipeline with explicit arrows

The 8 steps form a one-way pipeline. The Event panel MUST present it as
such — **linear flow with arrows, not a flat list of independent sections.**

Dense case (default — focused epoch is a normal event with effects):

```
┌─ EVENT · :checkout/submit · epoch #42 ─────────────── [◀ Prev] [Next ▶] ─┐
│ Stripe: violet (:accent-violet)                                          │
│                                                                          │
│ [1] DISPATCH                                                              │
│   Event       [:checkout/submit {:cart-id "c123"}]                        │
│   Origin      :user                                                       │
│   Call-site   views/checkout.cljs:142  ⎘ ⤴ open-in-editor                │
│   At          14:32:01.231                                                │
│       │                                                                   │
│       ▼                                                                   │
│ [2] COEFFECTS ASSEMBLED                                                   │
│   :db         {…current slice…}            [▸ expand]                     │
│   :now        2026-05-20T14:32:01.231Z                                    │
│   :http-cache {3 entries}                  [▸ expand]                     │
│       │                                                                   │
│       ▼                                                                   │
│ [3] HANDLER INVOKED                                                       │
│   :checkout/submit · reg-event-fx                                         │
│   ↳ impl/events.cljs:88   ⤴ open-in-editor                               │
│   ↳ source (DEBUG-gated, when available):                                │
│       (reg-event-fx :checkout/submit                                      │
│         (fn [{:keys [db]} [_ {:keys [cart-id]}]]                          │
│           {:db (assoc-in db [:cart :state] :submitting)                   │
│            :fx [[:http/managed {…}]]}))                                   │
│       │                                                                   │
│       ▼                                                                   │
│ [4] EFFECTS RETURNED  (handler intent)                                    │
│   :db   {…new slice…}                      [▸ diff inline]                │
│   :fx   [[:http/managed {:method :post :url "/orders" …}]]                │
│       │                                                                   │
│       ▼                                                                   │
│ [5] EFFECTS APPLIED  (what actually happened)                             │
│   :db          written  ✓                                                 │
│   :http/managed  POST /orders   in-flight  ⏳   #h-142                   │
│       │                                                                   │
│       ▼                                                                   │
│ [6] FLOWS RECOMPUTED                                                      │
│   :cart/total           re-fired  (input [:cart :items] changed)          │
│   :cart/eligibility     unchanged input — skipped (dim)                   │
│                                                                          │
│ ━━━━━━━━━━━━━━━━ db now committed for epoch #42 ━━━━━━━━━━━━━━━━━        │
└──────────────────────────────────────────────────────────────────────────┘
```

Sparse case (focused epoch is a noisy timer with no effects):

```
┌─ EVENT · :ping/tick · epoch #87 ─────────────────── [◀ Prev] [Next ▶] ─┐
│                                                                        │
│ [1] DISPATCH    [:ping/tick]    origin :timer                          │
│       │                                                                │
│       ▼                                                                │
│ [2] COEFFECTS   :db (sliced)                                           │
│       │                                                                │
│       ▼                                                                │
│ [3] HANDLER     :ping/tick · reg-event-db                              │
│       │                                                                │
│       ▼                                                                │
│ [4] EFFECTS     :db only — no :fx returned                             │
│       │                                                                │
│       ▼                                                                │
│ [5] APPLIED     :db written ✓     (no fx)                              │
│       │                                                                │
│       ▼                                                                │
│ [6] FLOWS       (no flow inputs changed)                               │
│                                                                        │
│ ━━━ db committed ━━━                                                   │
└────────────────────────────────────────────────────────────────────────┘
```

### §2.3 Queries (what the panel reads)

| From | Reads |
|---|---|
| Trace bus | `:rf/event-dispatched` (step 1), `:rf/coeffects-assembled` (step 2), `:rf/handler-invoked` (step 3), `:rf/effects-returned` (step 4), `:rf/effects-applied` per fx-id (step 5), `:rf.flow/computed` (step 6) — all filtered to the focused epoch's `:dispatch-id` |
| Registries | Handler metadata (`reg-event-*` form file:line, optional source string when DEBUG-gated) |
| App-db panel (bridge) | Inline diff renderer for step 4's `:db` value (reuses §8) |

### §2.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Step 1 `Origin :user` chip | (no-op MVP; stretch: filter-IN on origin) |
| Step 1 call-site | Open-in-editor (Causa's existing `:rf.causa/open-in-editor`) |
| Step 3 handler source ↳ | Open-in-editor at handler file:line |
| Step 4 `:fx` row | Switch to **Trace** panel, scrolled to the `:rf.fx/dispatched` op for that fx |
| Step 5 fx settlement | Switch to **Trace** panel, scrolled to settlement op; if `:http/managed`, badge offers the wire-trace popover |
| Step 6 flow row | Switch to **App-db** panel, scrolled to the path that flow wrote |
| "db committed" marker | Switch to **App-db** panel (focused-epoch diff view) |
| Click any path segment in step 2/4 value | Cross-panel propagation per §10.5 (App-db ↔ Reactive); no other value interactions |

### §2.5 Film-strip back/forward

Header `[◀ Prev] [Next ▶]` walks the L2 spine chronologically. MVP
semantics: **next chronological epoch** regardless of dispatch-origin
(per B.5 super-prompt). Stretch: per-panel filter (`Next epoch with same
dispatch-origin`).

Global keyboard: `←` / `→` always bound (matches L1 ribbon nav). Within
Event panel, `j` / `k` work too (consistent with L2 spine nav).

---

## §3 The Reactive panel (reactive perspective · steps 7-8)

### §3.1 Question

> **"What RENDERED as a result?"**

Reactive sweep — sub cascade + view re-renders, scoped to the focused epoch.

**Rename decision: `Views` → `Reactive`.** Option (a) per B.3. Pairs
symmetrically with `Event`, accurately captures the contents (subs +
views), and re-aligns the panel name with the perspective split. (See
§9.1.)

**Density note** (per §0). The cascade tree renders inline with full
attribution (`caused-by ← sub ← path`) on each leaf — no expand-to-see.
Unchanged subs are the **only** thing hidden by default (footer
disclosure per §3.4) because they're coverage signal, not signal-of-
the-moment. Target ~24-32 lines visible at default density; cascades
deeper than 4 levels rare enough that vertical scroll is acceptable.

### §3.2 Layout — DAG visualised as indented cascade

The reactive cascade is a DAG (§A.3 super-prompt). The Reactive panel
renders it depth-first with explicit indentation showing sub-of-sub layering.

**Cascade scope — flows are NOT in the reactive cascade.** The cascade
is strictly **db-paths → subs → views**. Flows mutate state — they
belong to the handling pipeline (step 6) — and they may **feed** the
cascade by writing db-paths the subs are watching, but they don't
participate in the read-only subs/views flow. Quoted from the
super-prompt (A.3):

> The Reactive panel renders the cascade (subs + views); the Event panel
> renders flows (alongside other handling steps).

This is binding for the DAG diagram below: the only nodes are db-paths
(seed), subs (intermediate), and views (leaf). No flow node ever
appears as a branch of the cascade. The L2 row's `🌊 flow-recomputed`
badge surfaces flows as a cross-epoch signal; per-epoch flow detail
lives in Event panel step 6.

Dense case (focused epoch ripples through several subs into multiple
views):

```
┌─ REACTIVE · epoch #42 ───────────────────────────── [◀ Prev] [Next ▶] ─┐
│ Stripe: cyan (:cyan)                                                    │
│                                                                         │
│ Triggered by   [:checkout/submit …]                                     │
│ Seed paths     [:cart :state]  [:cart :items]                           │
│       │                                                                 │
│       ▼                                                                 │
│ [7] SUBS RECOMPUTED  (8 ran · 4 changed · 4 dim short-circuits)         │
│                                                                         │
│   ◆ :cart/state                  :idle → :submitting                    │
│       └─ ◆ :cart/can-submit?     true → false                           │
│             └─ ▢ CheckoutButton  view re-rendered                       │
│       └─ ▢ StateBanner           view re-rendered                       │
│   ◆ :cart/items                  +1 entry                               │
│       └─ ◆ :cart/total           48.00 → 71.00                          │
│             └─ ▢ TotalsRow       view re-rendered                       │
│   ○ :user/name                   (input unchanged · skipped)            │
│   ○ :cart/eligibility            (input unchanged · skipped)            │
│       │                                                                 │
│       ▼                                                                 │
│ [8] VIEWS RE-RENDERED  (3)                                              │
│                                                                         │
│   ▢ CheckoutButton   views/checkout.cljs:88                             │
│       caused-by ← :cart/can-submit? ← [:cart :state]                    │
│   ▢ StateBanner      views/cart/banner.cljs:14                          │
│       caused-by ← :cart/state ← [:cart :state]                          │
│   ▢ TotalsRow        views/cart/totals.cljs:22                          │
│       caused-by ← :cart/total ← [:cart :total]                          │
│                                                                         │
│ ─────────────────────────────────────────────────────────────────       │
│  [Show 4 unchanged subs ▾]  ← collapsed by default (B.10 pick: dim)     │
└─────────────────────────────────────────────────────────────────────────┘
```

Sparse case (the epoch's db change touched no subscribed paths — common
for tool-frame internal events):

```
┌─ REACTIVE · epoch #87 ──────────────────────────── [◀ Prev] [Next ▶] ─┐
│                                                                       │
│ Triggered by   [:ping/tick]                                           │
│ Seed paths     [:ping :count]                                         │
│                                                                       │
│ [7] SUBS        No subs subscribed to changed paths.                  │
│ [8] VIEWS       No views re-rendered.                                 │
│                                                                       │
│ This epoch produced no reactive cascade. State changed at the seed    │
│ path but nothing downstream was observing it.                         │
└───────────────────────────────────────────────────────────────────────┘
```

### §3.3 Sub-layer placement (B.6 decision)

**Decision: (b) inside Reactive + (d) hover-over in App-db.**

- **In Reactive (b):** subs appear inline in the cascade tree (above)
  indented under their seed paths, with view-render leaves under the
  causing sub-chain. Each re-rendered view in step 8 lists its full
  causation chain in `caused-by ← sub ← path` form.
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
| Trace bus | `:rf.sub/computed`, `:rf.sub/skipped` (new — §11), `:rf.view/rendered` (new — §11) — filtered to focused `:dispatch-id` |
| Registries | Sub metadata (input-paths, signal-fn), view metadata (file:line) |
| App-db | Seed-path resolution from the epoch's diff (§4) |

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

**Density note** (per §0). The DIFF zone shows changed paths only —
narrow, dense, scannable. The STATE zone uses the shared lazy-tree
renderer with App-db's own depth heuristic (depth-3-collapsed by
default — see §10.4) so a 5-level-deep production db doesn't blow the
viewport. The hover popover (§4.4) is the canonical example of a
**hover affordance** in Causa: never replacing inline content, always
augmenting. Lines-per-screen target ~30-50 depending on db shape.

### §4.2 Layout

Two zones inside the panel:

```
┌─ APP-DB · epoch #42 ────────────────────────────── [◀ Prev] [Next ▶] ─┐
│ Stripe: cyan (:cyan)                                                  │
│                                                                       │
│  ─ DIFF (this epoch) ──────────────────────────────────────────────   │
│   ◆ [:cart :state]              :idle → :submitting                   │
│       Subs depending: [:cart/state] [:cart/can-submit?]               │
│   ◆ [:cart :items]              +1 entry                              │
│       Subs depending: [:cart/items] [:cart/total] [:cart/eligibility] │
│                                                                       │
│  ─ STATE (browseable, full db at end of epoch #42) ─────────────────  │
│   ▾ :cart                                                             │
│     ▾ :items   [2 items]                                              │
│       ▸ 0  {:id 7  :qty 1}                                            │
│       ▸ 1  {:id 22 :qty 1}    ← changed                              │
│     · :state  :submitting     ← changed from :idle                    │
│     · :total  71.00           ← changed from 48.00                    │
│   ▸ :user      {3 keys}                                               │
│   ▸ :session   {5 keys}                                               │
│   ▸ :http      {1 in-flight}                                          │
│                                                                       │
│  Empty diff state (no app-db change this epoch):                      │
│  "Epoch produced no app-db changes — handler returned no :db effect." │
└───────────────────────────────────────────────────────────────────────┘
```

### §4.3 The diff renderer

Reuses the shared lazy-tree + inline-diff + keyword-accent + clickable-paths
renderer (§8). The DIFF zone is the lazy tree narrowed to changed paths;
the STATE zone is the lazy tree rooted at `[]` with diff annotations
inline ("← changed from X").

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

Popover is a Causa-owned component (not a browser title), keyboard-
dismissable, click-through to Reactive panel via the `⤴` footer link.

### §4.5 No epoch focused (LIVE mode at head)

When the L2 spine is at head (no historical epoch focused), the diff zone
shows the most-recent epoch's diff (head-cascade); the state zone shows
current db. Same render shape — no second mode.

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

### §5.1 Question

> **"What raw trace events fired during this epoch?"**

Per-epoch raw trace ops ordered by emission time. The underlying stream
that Event + Reactive summarise. NOT aggregate across epochs (per §1.2).

**Density note** (per §0). Each op renders as a single mono row —
`#id  +Xms   op-kw   inline-summary` — so a 30-op epoch reads as a
30-line scroll. Per-row payload expansion via click reuses the shared
data-display renderer (§10) with depth-2-expanded default. Filter chips
(`[op-type ▾] [tag ▾]`) are panel-local + always visible — no
"Show filters" toggle. Lines-per-screen target ~30-60.

### §5.2 Layout

```
┌─ TRACE · epoch #42 ─────────────────────────────── [◀ Prev] [Next ▶] ─┐
│ Stripe: orange (:orange)                                              │
│                                                                       │
│ 14 ops · ordered by emission                                          │
│                                                                       │
│ #1837  +0.0ms   :rf/event-dispatched        [:checkout/submit …]      │
│ #1838  +0.2ms   :rf/coeffects-assembled     {:db, :now, :http-cache}  │
│ #1839  +0.3ms   :rf/handler-invoked         :checkout/submit          │
│ #1840  +0.8ms   :rf/effects-returned        {:db …, :fx [1 entry]}    │
│ #1841  +0.9ms   :rf/effects-applied         :db                       │
│ #1842  +1.1ms   :rf.fx/dispatched           :http/managed             │
│ #1843  +1.2ms   :rf.flow/computed           :cart/total               │
│ #1844  +1.3ms   :rf.sub/computed            :cart/state               │
│ #1845  +1.4ms   :rf.sub/computed            :cart/can-submit?         │
│ #1846  +1.5ms   :rf.sub/skipped             :user/name                │
│ #1847  +1.6ms   :rf.view/rendered           CheckoutButton            │
│ #1848  +1.7ms   :rf.view/rendered           StateBanner               │
│ #1849  +12ms    :rf.http/response           POST /orders → 201        │
│ #1850  +12ms    :rf.fx/settled              :http/managed #h-142      │
│                                                                       │
│ Filters [op-type ▾] [tag ▾] · Click any row → expand payload         │
└───────────────────────────────────────────────────────────────────────┘
```

Expanded payload uses the data-display renderer (§8). The per-epoch
filter chips are panel-local (do not affect L1 ribbon's IN/OUT pills).

### §5.3 Queries

| From | Reads |
|---|---|
| Trace bus | All ops with `:tags :dispatch-id` matching focused epoch's id |

### §5.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Row → expand payload | Inline in panel (no nav) |
| Op-type chip | Filter the panel to that op-type only |
| `:rf.view/rendered` row | Switch to **Reactive**, scrolled to that view |
| `:rf.fx/*` row | Inline — managed-fx hover shows wire-trace popover |

### §5.5 Film-strip

Chronological. The film-strip on Trace gives the operator "play this
epoch's trace stream then advance to the next" which is the closest
Causa comes to a time-step debugger replay UX.

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
engine is **locked to path (B): xyflow + custom Causa-palette styling.**
Path (A) (embed Stately's `@statelyai/inspect`) is rejected for bundle
weight + loss of palette control; path (C) (native Reagent) is rejected
for the work cost of rebuilding auto-layout / zoom-pan-fit from scratch.

| Decision | Value |
|---|---|
| Library | **xyflow** (the new name for react-flow). https://reactflow.dev/ |
| Visual reference | **Stately's visualizer playground** — https://stately.ai/viz (we recreate this look in Causa's palette; we do NOT import Stately UI) |
| State-machine model reference | xstate — https://github.com/statelyai/xstate (model only; re-frame2's machine vocab stays its own per rf2-5r4q2) |
| License | MIT |
| Bundle cost | ~50-80KB gzipped depending on which xyflow submodules are imported |
| Mount mechanic | xyflow is a React component; Causa is Reagent. Use Reagent's React-component interop (`reagent/adapt-react-class` or `[:>` syntax) to mount xyflow inside the Causa Machines-panel Reagent component. |
| Adapter layer | ~100 LoC CLJS — one-way walker from re-frame2 machine spec → xyflow's node/edge JSON. Lives at `tools/causa/src/day8/re_frame2_causa/machines/xyflow_adapter.cljs` (new ns implied by this design). |

**Visual conventions to recreate (Stately reference, Causa palette):**

| Convention | xyflow implementation |
|---|---|
| Nested state containment | xyflow's group/parent-node mechanic. Parent state renders as a containing rect; child states are nested xyflow nodes whose `parentNode` references the parent. |
| Transition edge animation | xyflow's `animated: true` edge prop. Color via Causa palette (`:accent-violet` for "fired this epoch"; `:text-tertiary` for "registered but not fired this epoch"). |
| Current-state highlight pulse | Custom node CSS class that applies the `pulse` keyframe (~1.2s ease-in-out; CSS-variable interpolated through `--rf-causa-motion-scale` so `prefers-reduced-motion` collapses it). Pulse outline color = `:green` (the panel-domain accent). |
| Auto-layout | xyflow's built-in `getLayoutedElements` helper (dagre algorithm). One-shot layout on first render; cached per machine-id; recomputed only when topology changes. |
| Zoom + pan + fit | xyflow's built-in `Controls` component (re-styled to match Causa's button chrome). Default zoom: fit-on-mount with 20px padding. `[− 100% +] [Fit][Reset]` chrome already shown in the existing mockups maps 1:1 to xyflow's `Controls`. |
| Label-on-edge transitions | xyflow's `label` prop on edges; rendered inline on the edge, not in a side legend. Font: JetBrains Mono 10px (`:micro` size). |
| Parallel-state side-by-side | Parallel-region containers render as sibling group-nodes with a dashed border (`:border-default` at `dash-array: 4 4`). Inner states laid out independently per region. |
| Final states | Thick border ring (2px solid `:green` outer + 1px solid `:bg-2` inner gap, recreating Stately's double-ring convention). |

**Causa palette token mapping into xyflow style props** (per
rf2-z7ms8 — the operator must immediately recognise this as a Causa
panel, not a generic xyflow diagram):

```clojure
;; Sketch — applied via xyflow nodes' :style and edges' :style props.
{:state-node {:background (:bg-2 tokens)            ; "#1B1E24"
              :border     (str "1px solid " (:border-default tokens))
              :color      (:text-primary tokens)    ; "#E8EAF0"
              :font-family mono-stack
              :font-size  (:body-tight type-scale)}
 :state-node-current {:border (str "2px solid " (:green tokens))
                      :animation "rf-causa-machine-pulse 1.2s ease-in-out infinite"}
 :state-node-final   {:border (str "2px solid " (:green tokens))
                      :box-shadow (str "inset 0 0 0 1px " (:bg-2 tokens))}
 :region-container   {:background "transparent"
                      :border (str "1px dashed " (:border-default tokens))}
 :edge-registered    {:stroke (:text-tertiary tokens) :stroke-width 1}
 :edge-fired-this-epoch {:stroke (:accent-violet tokens) :stroke-width 2
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
the nodes and edges with Causa palette tokens.

**Case A — no machines registered:**
```
┌─ MACHINES ─ no machines registered ───────────────────────────────────┐
│ This frame has no state machines.                                     │
└───────────────────────────────────────────────────────────────────────┘
```

**Case B — machines registered, focused epoch had no transition:**

```
┌─ MACHINES · epoch #87 ──────────────────────────── [◀ Prev] [Next ▶] ─┐
│ Stripe: green (:green)                                                │
│                                                                       │
│ :rf.machine.checkout/flow      (no activity this epoch · current ●)   │
│ ┌─[Canvas]─────────────────────────────────────[− 100% +] [Fit][Reset]│
│ │  ▢ :idle ──→ ◉ :authing ──→ ▢ :settled                              │
│ │              ↑ current                                              │
│ └────────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ :rf.machine.cart/lifecycle     (no activity this epoch · current ●)   │
│ ┌─[Canvas]──────────────────────────────────────────────────────────┐ │
│ │  ▢ :empty  ◉ :populated  ▢ :submitting  ▢ :settled                │ │
│ └────────────────────────────────────────────────────────────────────┘│
└───────────────────────────────────────────────────────────────────────┘
```

Topology stays visible — only the overlay (highlight on the transition
edge, `:after`-rings, action chips) is absent.

**Case C — focused epoch triggered ≥1 transitions:**

```
┌─ MACHINES · epoch #42 ─────────────────────────── [◀ Prev] [Next ▶] ─┐
│                                                                      │
│ :rf.machine.cart/lifecycle   :populated → :submitting   [click → L4] │
│ ┌─[Canvas]─────────────────────────────────────────────────────────┐ │
│ │  ▢ :empty  ▢ :populated ══▶ ◉ :submitting  ▢ :settled            │ │
│ │                  ↑ FROM      ↑ TO  (this epoch)                  │ │
│ │  ◔ :after ring · :submit-timeout · 30s countdown                 │ │
│ └──────────────────────────────────────────────────────────────────┘ │
│ Guards    ✓ :cart-non-empty?                                         │
│ Actions   ✓ :clear-form  ✓ :set-submitting-state                     │
│ Cancellation cascade (none)                                          │
└──────────────────────────────────────────────────────────────────────┘
```

Per §003, the interactive chart adapter (zoom / pan / fit / Canvas|List
view-mode) wraps each per-machine canvas. **The Canvas mode is the
xyflow surface described in §6.0**; the List view-mode is a flat
xyflow-free fallback for accessibility / low-power devices (preserved
from §003 — unchanged here).

**Focused-epoch overlay applied to xyflow rendering:**

Mock of Case C with the overlay applied (the operator's actual visual):

```
┌─ MACHINES · epoch #42 ─────────────────────────────[◀ Prev] [Next ▶]─┐
│ Stripe: green (:green)                                               │
│                                                                      │
│ :rf.machine.cart/lifecycle   :populated → :submitting                │
│ ┌─[xyflow canvas]────────────────────────────[− 100% +] [Fit][Reset]│
│ │                                                                   │
│ │   ┌────────┐ registered  ┌────────────┐ fired this epoch  ┌──────│
│ │   │ :empty │ ─ ─ ─ ─ ─ ▷ │ :populated │ ══════ animate ══▶│:subm │
│ │   └────────┘             └────────────┘   :submit          │itti │
│ │                                                            │ ng  │
│ │                                                            └─◉───│
│ │                                                              ↑   │
│ │                                                          current │
│ │                                                          (pulse) │
│ │                                                                   │
│ │     fired-edge: stroke :accent-violet · 2px · animated            │
│ │     registered edge: stroke :text-tertiary · 1px · dashed         │
│ │     current node:  2px :green border · 1.2s pulse                 │
│ │                                                                   │
│ └────────────────────────────────────────────────────────────────────┘
│ Guards    ✓ :cart-non-empty?                                         │
│ Actions   ✓ :clear-form  ✓ :set-submitting-state                     │
│ Cancellation cascade (none)                                          │
└──────────────────────────────────────────────────────────────────────┘
```

Layout direction: **left-to-right by default** (matches typical state-
machine convention; xyflow's dagre layout option `rankdir: 'LR'`).
Operator can flip to top-to-bottom via Settings → View → Machines layout
direction (deferred to follow-on bead). Default zoom: fit-on-mount with
20px padding around the bounding box of all nodes.

### §6.3 Queries

| From | Reads |
|---|---|
| Trace bus | `:rf.machine/transition`, `:rf.machine.after/scheduled`, `:rf.machine.after/fired`, `:rf.machine/cancellation` — filtered by `:dispatch-id` |
| Registries | Machine topology (`reg-machine`), guard / action metadata |
| Per-frame state | Current machine state (for the "current ●" annotation in case B) |

### §6.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Transition edge | (no-op MVP; stretch: scroll to the dispatching event in Event panel) |
| Guard row | Inline source-glance (DEBUG-gated source string) |
| Action chip | Switch to **Event** panel, scroll to step 5 `:fx` row for that action |
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

**Density note** (per §0). Most route trees are shallow (≤ 4 levels) so
the tree renders inline as text with `├─ └─ │` box-drawing — no canvas
needed. The "This epoch" block below the tree renders four short rows
(`Phase / From / To / Match / Events`) — dense, scannable, no expand-to-
see. Routing CAN escalate to xyflow if a future bead surfaces a route
tree large enough to demand auto-layout; until then, the textual tree
is denser AND simpler. Lines-per-screen target ~16-30.

### §7.2 Layout

```
┌─ ROUTING · epoch #38 ──────────────────────────── [◀ Prev] [Next ▶] ─┐
│ Stripe: yellow (:yellow)                                             │
│                                                                      │
│ Active route tree                                                    │
│  /                                                                   │
│  ├─ /cart      ◉  (active this epoch — :on-match)                    │
│  │  └─ /cart/:id                                                     │
│  ├─ /orders                                                          │
│  │  └─ /orders/:order-id                                             │
│  └─ /settings                                                        │
│                                                                      │
│ This epoch                                                           │
│   Phase       :on-match                                              │
│   From        /                                                      │
│   To          /cart                                                  │
│   Match       {:route :cart}                                         │
│   Events      [:rf.route/transitioned] [:cart/route-entered]                │
│                                                                      │
│ Empty (no route activity this epoch):                                │
│   Shows tree with current active node highlighted; "This epoch"      │
│   section reads "No route activity in this epoch."                   │
└──────────────────────────────────────────────────────────────────────┘
```

### §7.3 Queries

| From | Reads |
|---|---|
| Trace bus | `:rf.route/can-leave`, `:rf.route/can-enter`, `:rf.route/on-match`, `:rf.route/fragment-changed` — filtered by `:dispatch-id` |
| Registries | Route tree (`reg-route`) |
| Per-frame state | Current active route + phase (for empty-state) |

### §7.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Route node | Set as "selected route" for filter-IN candidate |
| Phase chip | Filter trace panel to route ops in that phase |
| Event in "Events" list | Switch to **Event** panel for that event |

### §7.5 Film-strip

MVP chronological; stretch "next route activity" (skip silent epochs).

---

## §8 The Issues panel (per-epoch issues)

### §8.1 Question

> **"What's wrong in this epoch?"**

Per-epoch errors, warnings, schema violations, a11y violations.

**Density note** (per §0). Issues are rare per epoch but high-signal —
each renders as a 4-6 row block (severity · op-key · message · path ·
ex-data) with the ex-data laid out via the shared data-display renderer
(§10) at depth-2-expanded. No expand-to-see-message — the whole issue
block reads inline so the operator sees the punch at a glance. Empty
state is a single line. Lines-per-screen target ~6-24 (it's variable;
the panel is fine being shorter than its peers).

### §8.2 Layout

Dense case:

```
┌─ ISSUES · epoch #42 ────────────────────────────── [◀ Prev] [Next ▶] ─┐
│ Stripe: red (:red)                                                    │
│                                                                       │
│ 2 issues                                                              │
│                                                                       │
│ ⚠ ERROR    :rf.error/handler-threw                                    │
│   Handler  :checkout/submit                                           │
│   Message  AssertionError: cart-id must be string, got nil            │
│   At       impl/events.cljs:88                                        │
│   ex-data  {:cart-id nil :event [:checkout/submit nil]}               │
│                                                                       │
│ ⚠ WARN    :rf.schema/violation                                        │
│   Schema   :cart/item                                                 │
│   Path     [:cart :items 1]                                           │
│   Value    {:id 22}                                                   │
│   Expected :cart/item — missing :qty                                  │
│                                                                       │
│ Empty state (no issues):                                              │
│   "No issues in this epoch."                                          │
└───────────────────────────────────────────────────────────────────────┘
```

### §8.3 Queries

| From | Reads |
|---|---|
| Trace bus | `:rf.error/*`, `:rf.warning/*`, `:rf.schema/violation`, `:rf.a11y/violation` — filtered by `:dispatch-id` |

### §8.4 Cross-panel navigation

| Click | Navigates to |
|---|---|
| Issue handler | Open-in-editor at handler file:line |
| Issue path | Switch to **App-db** panel at that path |
| ex-data value | Data-display renderer expand inline |

### §8.5 Film-strip

MVP chronological. **High-value stretch: "next epoch with ⚠ badge"** —
operator stepping through bug repro lands on issue-bearing epochs only.

---

## §9 The Chrome A11y panel — removed (rf2-4v67l)

The Causa Chrome A11y dogfood panel was removed. A11y dogfooding is
properly Story's domain, where it already ships:

- `re-frame.story.ui.chrome-a11y` (rf2-18t6p · `tools/story/src/re_frame/
  story/ui/chrome_a11y.cljs`) — axe-core scoped to the Story chrome.
- `re-frame.story.ui.a11y` (rf2-qgms1 · `tools/story/src/re_frame/story/
  ui/a11y.cljs`) — axe-core scoped to VARIANT trees.

A duplicate Causa-side panel was noise that flagged the Causa
events-list as a problem; the canonical "one source of truth" rule
keeps the dogfood in Story.

---

## §10 Shared data-display renderer

The renderer is **ONE canonical component used everywhere data appears**
— App-db's huge nested map, the Event panel's coeffects slice + returned
effects, the Reactive panel's sub values, Trace ops' expanded payloads,
Issues `ex-data`. Operator learns one interaction pattern; applies it
everywhere.

### §10.1 Capabilities (LOCKED per B.9 super-prompt)

1. **Lazy collapsible tree** — hierarchical EDN with expand/collapse.
   Large lists / maps show `[N items]` / `{N keys}` until expanded. Deep
   nesting renders depth-first; only visible nodes hit the DOM. Escape
   hatch: per-node "show as `pr-str`" toggle for very large data.

2. **Inline diff highlighting** — for the focused epoch view, changed
   values are highlighted IN PLACE (left-margin marker + accent color +
   annotation `← changed from <prior-value>`). Unchanged values dim.
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
  · :state    :submitting                          ← changed from :idle
  · :total    71.00                                ← changed from 48.00
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

### §10.3 Keyword accent color (B.9 spec)

**Decision: `:accent-violet` (`#7C5CFF`)** — already Causa's brand
keyword tone (per §007 colour system, panel-domain `:event` stripe, and
existing long-keyword-treatment §007). Reusing keeps the keyword token
visually consistent across L1 filter pills, L2 spine rows, L3 tab labels,
and L4 data values.

Other types render in `text-primary` (`#E8EAF0`), monospaced. Dimmed
unchanged values render in `text-tertiary` (`#6B7080`).

The diff annotation (`← changed from <prior>`) renders in
`:text-secondary` at 80% size (12px @ cosy density).

The left-gutter diff glyph follows §007's cascade-gutter token mapping:
`+` green · `-` red · `~` yellow · `◴` violet · space tertiary.

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
to `:rf.causa.data-display/expansion {<path>}` so the operator's
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
| **Hover changed-row** | Subtle background shift only (no popover). The annotation `← changed from <prior>` is already rendered inline; hover does not reveal additional metadata. |
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

### §10.6 Cross-panel data-display consistency

All panels use the same renderer. Implementations MUST go through
`tools/causa/src/day8/re_frame2_causa/data_display/render.cljs` (new
shared ns implied by this design); per-panel renderers are wrappers that
configure depth / scope / diff-mode. Operator's expansion / pinning
state lives in one app-db slot keyed by panel-id + path.

### §10.7 Evicted-epoch placeholder

When the operator scrubs onto an epoch evicted from the buffer, every
data-display in every panel renders the same placeholder:

```
┌─ epoch #12 ──────────────────────────────────────────────────────────┐
│  Epoch evicted from buffer.                                          │
│  Increase :epoch-history to retain more.                             │
│  Settings → General → Epoch history.                                 │
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

**MVP: (a) + (c).** Handler metadata in Event panel step 3 + click-through
to editor via existing `:rf.causa/open-in-editor`.

**Stretch: (d).** Compile-time capture as macro metadata — extend
`reg-event-{db,fx,ctx}` macros to stamp the form-source as a string into
the handler's registry metadata. `goog.DEBUG`-gated so production elides.
The Event panel step 3 surfaces the source inline when available. (See
§2.2 dense mockup.)

This is **substrate work** (modify `re-frame.core` reg-event-* macros),
not Causa panel work. Filed as substrate bead in §13.

**(b) clojure.repl/source-fn rejected.** JVM-only.

### §11.3 B.8 Performance + buffer requirements

What the panel design needs from the substrate (per §1.4 captured-not-replayed):

| Requirement | Scope |
|---|---|
| Cascade attribution capture | **Focused-event-only** (cheaper). All epochs in buffer carry the bones (which subs ran, which views re-rendered); only the focused epoch needs the full chain attribution payload. Substrate hot-path: emit lightweight rows on every epoch; emit fattened cause-chain rows only when `:rf.causa/focused-dispatch-id` matches. |
| Bounded per-epoch capture | Cap at **50 subs + 100 views per epoch**. The substrate enforces at capture time; the panel shows `+N more` overflow indicator (existing component, `panels/overflow_indicator.cljc`). |
| Buffer retention | Substrate-owned. Causa documents the operator surface as **Settings → General → Epoch history** (current ~100; configurable). |
| Evicted-epoch UX | Per §10.7 — placeholder string in every panel. |
| Sub `:skipped` op | New trace op needed (`:rf.sub/skipped`) — current trace has `:rf.sub/computed` only. Without `:skipped`, the "unchanged subs" disclosure in §3.4 cannot render coverage. |

### §11.4 B.10 Open sub-decisions

| Sub-decision | Pick | Notes |
|---|---|---|
| Unchanged subs in cascade | **Dim, collapsed by default with "Show N unchanged"** | §3.4. Toggle in Settings → View. |
| Meta-epoch section ordering | **Fixed order: Event > App-db > Reactive > Trace > Machines > Routing > Issues** | Matches the L3 tab order. Predictable beats dynamic. (rf2-4v67l — Chrome A11y removed in favour of Story's shipped panel.) |
| Event panel section default-expansion | **Steps 1-6 all expanded by default; collapsible per-step via header click; collapse-all keyboard `[`** | The Event panel IS the handling-pipeline view — collapsing by default would hide the punch. |
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
(`re-frame.core` + `mcp-base`); the Causa panel beads in §13 list them as
prerequisites.

| Contract | Op key | Payload sketch | Used by |
|---|---|---|---|
| **View re-render attribution** | `:rf.view/rendered` | `{:view-id :ns/Component :file ".../X.cljs" :line N :caused-by-sub :sub-id :caused-by-paths [...] :dispatch-id <id>}` | Reactive panel · Trace panel · §3.5 |
| **Sub skip attribution** | `:rf.sub/skipped` | `{:sub-id :s/foo :reason :input-unchanged :dispatch-id <id>}` | Reactive panel "unchanged subs" disclosure · §3.4 |
| **Cascade aggregate** | `:rf.cascade/captured` | `{:dispatch-id <id> :subs-ran N :subs-skipped N :views-rendered N :flows-recomputed N}` | Optional — emitted at end-of-epoch for fast L2 badge / Reactive summary line |
| **Dispatch-origin tag** | (on existing `:rf/event-dispatched`) | Add `:tags :origin <origin-kw>` per §1.5 taxonomy | Event panel step 1 · L2 row prefix · filter pills |
| **Handler-source string** | (on existing handler registry) | Stamp `:source-string` metadata via macro (DEBUG-gated) | Event panel step 3 inline source · §2.2 |
| **Flow recompute** | `:rf.flow/computed` | `{:flow-id :inputs-changed [...] :dispatch-id <id>}` | Event panel step 6 |
| **Flow skip** | `:rf.flow/skipped` | `{:flow-id :reason :input-unchanged :dispatch-id <id>}` | Event panel step 6 "dim" rows |
| **Route phase taxonomy** | (on existing `:rf.route/*`) | Confirm `:tags :phase #{:can-leave :can-enter :on-match :settle}` is consistent | Routing panel §7 |

**Per-substrate adapter work for `:rf.view/rendered`:**

- **Reagent**: ratom watch on the component's reactive context fires
  `:rf.view/rendered` with the watch's input-deps.
- **UIx**: hook-firing instrumentation — hook into `useSyncExternalStore`
  callback to emit on render commit.
- **Helix**: hook-instrumented render counter; emit on render commit.

Each adapter's emit is gated on `goog.DEBUG` (cost is non-trivial — only
ship in dev / Causa-bundle builds).

**Focused-event-only attribution (per §11.3).** The substrate enforces:
on every epoch, emit lightweight `:rf.cascade/captured` aggregate
(counts only). Emit fattened per-sub / per-view rows only when the
current epoch's `:dispatch-id` matches Causa's reported focused id (a
read-only flag the runtime extension reads from a per-frame atom Causa
publishes via `register-frame-meta!` or similar). When unfocused, the
runtime drops fattened payloads at emit time, not at consumer time — the
cost is borne only for the epoch the operator is staring at.

---

## §13 Follow-on implementation beads (worker proposals — mayor files)

Each bullet below is a single-bead implementation slice. **Format: title
+ 2-line description + dependencies.** Mayor reviews and files these as
real beads after approving this doc.

### Substrate beads (these gate panel work)

- **rf2-?????** — *Substrate: add `:rf/event-dispatched` `:origin` tag.*
  Extend the dispatch macro to stamp `:tags :origin <origin-kw>` per the
  §1.5 taxonomy. All call sites in `re-frame.core` + adapter mounts.
  Gates: Event panel step 1, L2 row prefix, B.10 dispatch-origin display.

- **rf2-?????** — *Substrate: add `:rf.sub/skipped` trace op.* Emit at
  sub-evaluation skip site (input-unchanged short-circuit). Carries
  `:sub-id` + `:reason` + `:dispatch-id`. Gates: Reactive panel
  "unchanged subs" disclosure (§3.4).

- **rf2-?????** — *Substrate: add `:rf.view/rendered` trace op per
  substrate adapter.* One per Reagent / UIx / Helix; instrumented at the
  adapter's render-commit boundary; DEBUG-gated. Gates: Reactive panel
  step 8 (§3.5).

- **rf2-?????** — *Substrate: add `:rf.cascade/captured` aggregate.* End-
  of-epoch summary op with subs/views/flows counts. Cheap; emitted every
  epoch. Gates: L2 badge "cascade size", Reactive header summary line.

- **rf2-?????** — *Substrate: add `:rf.flow/skipped` trace op.* Mirror
  `:rf.sub/skipped` for flows. Gates: Event panel step 6 dim-row
  rendering.

- **rf2-?????** — *Substrate: focused-event-only attribution gate.*
  Runtime extension reads a per-frame `:rf.causa/focused-dispatch-id`
  atom; gates fattened cascade-attribution payloads at emit time.
  Gates: B.8 perf budget.

- **rf2-?????** — *Substrate: DEBUG-gated handler source capture
  (B.7 (d) stretch).* Extend `reg-event-{db,fx,ctx}` macros to stamp
  `:source-string` into registry metadata, elided in `goog.DEBUG=false`
  builds. Gates: Event panel step 3 inline source (§2.2).

### Causa panel beads

- **rf2-?????** — *Causa: shared data-display renderer.* New ns
  `data_display/render.cljs` per §10. Lazy tree, inline diff,
  keyword-accent, clickable-paths, expansion-state app-db slot. All
  panels rebind to this renderer. Includes evicted-epoch placeholder.

- **rf2-?????** — *Causa: Event panel — pipeline rendering.* Replace
  `event_detail.cljs` content with the 6-step pipeline (§2). Reads new
  `:rf.flow/computed` + handler `:origin` tag. Stripe `:accent-violet`.
  Depends on substrate `:origin` + `:rf.flow/computed`.

- **rf2-?????** — *Causa: Reactive panel rebuild + rename.* Rename L3
  tab display label `Views` → `Reactive` (key stays `:views`). Replace
  panel content with sub cascade + view re-render (§3). Depends on
  substrate `:rf.sub/skipped` + `:rf.view/rendered`.

- **rf2-?????** — *Causa: App-db panel — downstream-subs overlay.* Add
  the hover popover at §4.4 that lists subs/views downstream of each
  changed path; click `⤴` → Reactive panel. Depends on Reactive panel
  cross-panel API.

- **rf2-?????** — *Causa: Trace panel — focused-epoch scoping + film-
  strip.* Re-scope Trace panel to focused `:dispatch-id` (drop any
  aggregate-across-epochs view). Add `[◀ Prev] [Next ▶]` header. Reuse
  data-display renderer for expanded payloads.

- **rf2-?????** — *Causa: Machines panel — topology-always-visible
  empty-state.* When focused epoch has no machine transition, still
  render the machine topology with "current ●" annotation. Tightens
  §003's case B treatment to keep topology always-visible.

- **rf2-?????** — *Causa: Routing panel — focused-epoch overlay shape.*
  Restructure routing panel content per §7 (always-visible route tree +
  per-epoch overlay). Promote from L3 tab if not already done.

- **rf2-?????** — *Causa: Issues panel — focused-epoch scoping +
  evicted-epoch placeholder.* Re-scope per §8; ensure issues panel
  film-strip respects the "next epoch with ⚠" stretch filter.

- **rf2-?????** — *Causa: shared film-strip header component.* Single
  reusable `[◀ Prev] [Next ▶]` header consumed by every L4 panel. MVP
  chronological; per-panel filter slot for stretch.

- **rf2-?????** — *Causa: L2 epoch timeline — dispatch-origin prefix +
  activity badges.* Render the §1 badge set on each L2 row (⚠ ◆ 🌐 ⚡ 💧
  🌊 ⏲) + the origin tag prefix. Reads new `:origin` tag + cascade-
  captured aggregate.

- **rf2-?????** — *Causa: settings — `:epoch-history` knob + "Show
  unchanged subs" toggle.* General → Epoch history slider; View → Show
  unchanged subs in cascade toggle (default OFF per §3.4).

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
| **Causa hot-zone** — design doc work only | This file lives under `tools/causa/spec/`; no `tools/causa/src/` edits |
| **Reagent hiccup + JetBrains Mono** for mockups | All ASCII mockups assume JetBrains Mono rendering; code examples in §2.2 are Reagent-shaped hiccup-equivalent EDN |
| **Inspection-by-default · rewind-by-affordance** | §1.3 restated as binding; every L4 mockup uses film-strip nav (inspection) — Rewind affordance is explicit in the focused-epoch header (existing §002), never bound to scroll/scrub |
| **Captured-not-replayed** | Every per-panel "queries" subsection cites the trace-bus / registry source; §12 lists every substrate gap, none of which is "derive on inspection" |

---

## §15 What's deliberately NOT in this design

- **No 4th L4 panel.** The 8-panel set is the contract; sub-layer
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

- [`000-Vision.md`](000-Vision.md) — the canonical "what Causa is"
- [`002-Time-Travel.md`](002-Time-Travel.md) — Rewind affordance (§1.3 referenced)
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) — Machines panel current behaviour (§6 extends; §6.0 + §17.4 add xyflow integration)
- [`004-App-DB-Diff.md`](004-App-DB-Diff.md) — App-db diff (§4 extends with overlay)
- [`007-UX-IA.md`](007-UX-IA.md) — palette tokens, spacing, density (§10 + §17.1 reuse and extend)
- [`012-Views.md`](012-Views.md) — Views panel current behaviour (§3 rebuilds as Reactive)
- [`013-Trace-Bus.md`](013-Trace-Bus.md) — trace-op contract (§12 extends)
- [`014-Registry-Catalogue.md`](014-Registry-Catalogue.md) — `:rf.causa/*` ids; new ids implied by §13 + §17.5 land here
- [`018-Event-Spine.md`](018-Event-Spine.md) — `:rf.causa/focus` (every §-scoped panel binds to this)
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) — 5×4 matrix; §6 / §7 are matrix entries
- `ai/prompts/causa-interface-adjustments.md` — canonical super-prompt (local-only); the source of truth for the §0 information-density binding, the §6.0 xyflow path-B lock, the §10.5 data-renderer interaction strip-out, and the §17 UI-design pass
- `ai/findings/2026-05-20-causa-runtime-information-architecture.md` — earlier exploratory analysis (local-only)

---

## §17 Visual + interaction refinements (UI-design pass)

This section is the **critic-worker pass** layered on top of §1-§16.
The earlier sections nailed the **structural design** (what each panel
shows, how panels link, the IA). §17 layers in the **visual + interaction
quality** — palette token mapping, interaction-state matrices, animation
timings, iconography, the Machines panel xyflow mockup with the Causa
palette integration spec, and the follow-on bead candidates that drop
out of the visual pass.

§17 is binding alongside §1-§16: implementation beads MUST cite both
the structural section (which content lives where) AND the §17
subsection that governs its visual presentation.

### §17.1 Visual language spec

#### §17.1.1 Spacing scale

Density is binding (§0); spacing reinforces it. Causa uses a 4-px base
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

Per Causa convention (§007 + `theme/tokens/type-scale`): **JetBrains Mono throughout** for chrome, labels, prose, AND data — Causa is a single
voice. Inter is reserved for a few high-chrome surfaces (Settings, About);
the L4 panels are mono-uniform.

The type-scale already exists (`theme/tokens/type-scale`). The §17 binding
is **which size goes where**:

| Surface | Size token | Px @ 13px default | Weight |
|---|---|---|---|
| Panel `<h1>` (e.g. `EVENT · :checkout/submit · epoch #42`) | `:display` | ~14px | 600 (semibold) |
| Section headers (e.g. `[1] DISPATCH`, `[7] SUBS RECOMPUTED`) | `:body` | 13px | 600 |
| Step sub-header keys (e.g. `Event`, `Origin`, `Call-site`) | `:body-tight` | 12px | 500 (medium) |
| Step values (the actual data) | `:mono-body` | 12px | 400 |
| Inline annotations (`← changed from :idle`, `(input unchanged · skipped)`) | `:caption` | ~11px | 400 |
| Edge labels in xyflow canvases (§6) | `:micro` | ~10px | 400 |
| Metadata (`14:32:01.231`, file:line, `+0.2ms` trace timing) | `:caption` | ~11px | 400 italic |
| L2 row text (origin prefix · event-id · badges) | `:body-tight` | 12px | 400/600 mix |

The display face (Fraunces — `:display-stack` in tokens) is **NOT**
used inside Dynamic-mode panels; Fraunces is reserved for Static-mode
landing-page header surfaces (the audit-trail divergence Causa
deliberately drew per rf2-5kfxe.9). The L4 surfaces are mono.

#### §17.1.3 Palette token mapping (per rf2-z7ms8)

Confirming + amending the structural worker's picks. All hex resolves
through `theme/tokens` (dark) / theme-CSS-variables (light + HCM).

| Role | Token (dark hex) | Confirmed / amended |
|---|---|---|
| **Keyword accent** (data values · the only colored type) | `:accent-violet` (`#7C5CFF`) | ✅ confirmed (per §10.3 + §007 panel-domain mapping) |
| **Changed-value highlight** (left-margin marker + accent color) | `:accent-violet` + glyph from cascade-gutter (§007: `+` green / `-` red / `~` yellow / `◴` violet) | ✅ confirmed — gutter glyph is the structural signal; accent-violet is the row tint |
| **Dim-for-unchanged values** | `:text-tertiary` (`#8990A0`) | ✅ confirmed (already lifted to AA-passing 4.7:1 per rf2-0fr6v) |
| **Settled-success** (fx settled, no error) | `:green` (`#4ADE80`) | ✅ |
| **Settled-error** (fx settled with error · issues panel ERROR) | `:red` (`#F87171`) for ink; `:red-deep` (`#a83a3a`) for button fills | ✅ |
| **In-flight** (fx still running, e.g. `⏳ #h-142`) | `:yellow` (`#FBBF24`) — matches §007 perf-scale "medium / in-progress" tone | ✅ amend (structural draft was ambiguous; lock to `:yellow`) |
| **Stale** (epoch evicted from buffer; placeholder text) | `:text-tertiary` on `:bg-2` | ✅ |
| **Border subtle** (between adjacent rows in a list) | `:border-subtle` (`#232730`) | ✅ |
| **Border default** (around cards / canvases) | `:border-default` (`#2F3441`) | ✅ |
| **Border strong** (focused-row outline before focus-ring overlay) | `#444B5B` (per §007) | ✅ |
| **Background — panel canvas** | `:bg-2` (`#1B1E24`) | ✅ |
| **Background — hover row** | `:bg-active` (`#2A2F3D`) | ✅ |
| **Background — popover** | `:bg-3` (`#232730`) | ✅ |
| **L4 panel header stripe** | `theme/tokens/panel-accent` table (§007): Event violet · App-db cyan · Reactive cyan · Trace orange · Machines green · Routing yellow · Issues red (rf2-4v67l — Chrome A11y removed) | ✅ |
| **Cross-panel arrow / `⤴` link** | `:accent-violet` 600-weight | ✅ |
| **Film-strip back/forward chevron** | `:text-secondary` default · `:text-primary` on hover | ✅ |

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
| Around each xyflow canvas (1px `:border-default`) | Between pipeline steps in §2.2 (the arrow `▼` IS the divider) |
| Between DIFF zone and STATE zone in App-db (1px `:border-subtle`, full width) | Inside the data-display tree (indentation IS the structure) |
| Around hover popovers (1px `:border-default` + 4px shadow) | Between cells of an inline KV row (whitespace alone) |
| Around xyflow group/parent nodes (1px solid; 1px dashed for parallel-region containers) | Between L4 tabs (the L3 tab strip handles this) |

The "`──────`" full-width separators in the ASCII mockups (e.g.
`━━━ db committed ━━━` in §2.2) render as **1px `:border-subtle`** in
HTML, not as text characters. The `━` characters in the ASCII are
narrative shorthand for the operator to visualise.

#### §17.1.5 Iconography

The mockups in §1-§9 already pick these. §17.1.5 binds them.

**L2 row badges (per §1.1.1 + B.1.1):**

| Glyph | Meaning | Token (text color) |
|---|---|---|
| ⚠ | Issue (error or warning) emitted this epoch | `:red` |
| ◆ | State machine transition this epoch | `:green` |
| 🌐 | Route navigation this epoch | `:yellow` |
| ⚡ | HTTP request lifecycle touched | `:orange` |
| 💧 | SSR hydration phase | `:cyan` |
| 🌊 | A flow recomputed | `:accent-violet` |
| ⏲ | Timer-triggered dispatch | `:text-tertiary` |

Emoji glyphs are deliberate (consistent with existing Causa
convention). Under HCM, the `@media (forced-colors: active)` block
strips the color; the glyph alone carries the signal — colour is never
alone (§007).

**Per-panel header icons** (rendered to the LEFT of the panel `<h1>`,
8px to the left of the accent stripe):

| Panel | Icon (Unicode glyph) | Token |
|---|---|---|
| Event | ⚡ | `:accent-violet` |
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
  "jump to this panel." `:accent-violet`, 12px.
- `↳` (turn-down arrow) — used in pipeline-step source links + cause
  attribution chips. `:text-tertiary`, 11px.
- `→` (right arrow) — used as a transition glyph in machine-state
  rows (`:populated → :submitting`). `:text-primary`, mono inline.

**Tree-disclosure glyphs** (data-display renderer · §10.2):

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
| **Focus** | Background as hover + 2px focus-ring outline color `#FBBF24` (the global focus-visible amber from rf2-fxde5); outline-offset 2px; under HCM remaps to `Highlight` | The focus-ring is the existing global Causa convention — panels inherit it for free. NEVER suppress `:focus-visible` per-panel. |
| **Pressed** | Background as hover, transformed `translateY(1px)` for the duration of the click (~60ms); visual feedback only — no layout shift | Applied to film-strip buttons + clickable rows |
| **Disabled** | Foreground at `:text-tertiary`; cursor `not-allowed`; tabindex removed | E.g. "Next ▶" at end of L2 spine; "Open in editor" when source unavailable |
| **Loading** | Skeleton row at `:bg-active` opacity 0.6 with a 1.2s `pulse` animation (interpolated through `--rf-causa-motion-scale` so reduced-motion collapses it) | Used during trace-bus subscription warmup; should be brief (<200ms) |
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
  to suppress the default UA outline MUST re-enable the Causa
  focus-visible outline (per the existing convention at lines 454-460)

**Animation timings (binding):**

| Animation | Duration | Easing |
|---|---|---|
| Interaction feedback (hover, focus, press) | **≤ 200ms** (typical 120-180ms) | `ease-out` |
| Panel switch / tab transition | ≤ 400ms — currently 180ms cross-fade (`theme/motion :fade-duration-ms`) | `ease-in-out` |
| Diff flash on changed value | 400ms (`theme/motion :flash-duration-ms`) | `ease-out` |
| Machine-state current-state pulse (§6) | 1.2s | `ease-in-out infinite` |
| xyflow edge "fired this epoch" animation | xyflow built-in (≈ 1s loop) | xyflow default |

All durations multiply through `var(--rf-causa-motion-scale, 1)` so
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
density toggle inside the panel — the global `--rf-causa-font-size`
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
│  │     ═══ fired this epoch (animated)        :accent-violet, 2px  │ │
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
| Rounded rect + 2px solid `:green` + 1.2s pulse | Current state (most recent visit) | `:current` |
| Rounded rect with dashed border (`1px dashed :border-default`) | Parallel-region container | `:region` |

#### §17.4.3 Edge styles

| Style | Stroke | Width | Animated | Meaning |
|---|---|---|---|---|
| `--` (dashed `:text-tertiary`) | `:text-tertiary` | 1px | no | Registered transition, not fired this epoch |
| `──` (solid `:text-tertiary`) | `:text-tertiary` | 1px | no | Same as above, but represents the most-recent traversal in the buffer |
| `══` (thick + animated) | `:accent-violet` | 2px | yes | Transition fired this epoch (the overlay) |

Edge label: `:micro` (`~10px`) JetBrains Mono in `:text-secondary`,
rendered inline on the edge (xyflow's `label` prop), not in a side
legend.

#### §17.4.4 Layout direction + default zoom

- **Direction**: left-to-right (xyflow `dagre` `rankdir: 'LR'`) — matches
  Stately's convention; matches reading order.
- **Default zoom**: `fitView` on mount with 20px padding around the
  bounding box. The `Fit` button in the Controls re-runs `fitView`.
- **Min/max zoom**: 0.25× to 2×. Wheel-zoom enabled.
- **Pan**: drag-to-pan on the canvas background; node-drag disabled
  (it's a read-only render).

#### §17.4.5 Causa-palette token integration into xyflow style props

Sketched in §6.0; restated here as the canonical reference the
xyflow-adapter bead (§17.5) implements against:

```clojure
;; tools/causa/src/day8/re_frame2_causa/machines/xyflow_style.cljs
;; The single source of truth for xyflow visual props.

(ns day8.re-frame2-causa.machines.xyflow-style
  (:require [day8.re-frame2-causa.theme.tokens
             :refer [tokens mono-stack type-scale duration-css]]))

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
   :current  {:background    (:bg-2 tokens)
              :border        (str "2px solid " (:green tokens))
              :border-radius "6px"
              :color         (:text-primary tokens)
              :font-family   mono-stack
              :font-size     (:body-tight type-scale)
              :padding       "6px 10px"
              :animation     (str "rf-causa-machine-pulse "
                                  (duration-css 1200)
                                  " ease-in-out infinite")}
   :region   {:background    "transparent"
              :border        (str "1px dashed " (:border-default tokens))
              :border-radius "8px"}})

(def edge-style
  {:registered          {:stroke       (:text-tertiary tokens)
                         :stroke-width 1
                         :stroke-dasharray "4 4"}
   :registered-traversed {:stroke      (:text-tertiary tokens)
                          :stroke-width 1}
   :fired-this-epoch    {:stroke       (:accent-violet tokens)
                         :stroke-width 2}})  ; + xyflow :animated true

(def edge-label-style
  {:fill        (:text-secondary tokens)
   :font-family mono-stack
   :font-size   (:micro type-scale)})
```

The `rf-causa-machine-pulse` keyframe lives in the existing
`theme/global_styles motion-css` block — added alongside the existing
diff-flash + fade keyframes per the same `prefers-reduced-motion`
collapsing mechanic.

### §17.5 Follow-on bead candidates (visual layer)

Each bullet below is a single-bead implementation slice the §17 visual
pass implies. Format: title + 2-line description + dependencies.
Mayor reviews + files these alongside the structural per-panel beads
already drafted in §13.

- **rf2-?????** — *Causa: xyflow integration adapter — re-frame2 machine
  spec → xyflow JSON.* New ns
  `tools/causa/src/day8/re_frame2_causa/machines/xyflow_adapter.cljs`
  walks `reg-machine` topology + per-epoch transition trace and emits
  xyflow nodes/edges JSON. Plus `xyflow_style.cljs` per §17.4.5. Plus
  Reagent ↔ React mount wiring per §6.0. Depends: xyflow added to
  `package.json` (~50-80KB gzipped, MIT). Gates: Machines panel
  redesign (§6).

- **rf2-?????** — *Causa: data-display renderer component — lazy tree
  + inline diff + keyword accent + clickable paths.* New ns
  `tools/causa/src/day8/re_frame2_causa/data_display/render.cljs` per
  §10 + §17.1.3 palette mapping + §17.2 interaction-state matrix. The
  only path-interaction is cross-panel propagation (§10.5). Gates: all
  Dynamic panels.

- **rf2-?????** — *Causa: apply forced-colors palette token coverage to
  all L4 panel borders + accents + film-strip chevrons.* Audit the new
  panel content against the §17.1.3 token table + the existing
  `@media (forced-colors: active)` block; add any missing remaps so
  Windows HCM renders the new chrome correctly. Gates: panel-by-panel
  visual polish.

- **rf2-?????** — *Causa: spacing-scale tokens.* Catalogue `:gap-0`
  through `:gap-6` per §17.1.1 in `theme/tokens.cljc` and migrate the
  ~50 inline `:padding "10px"` / `:margin "8px 0"` literals across the
  panels to the tokenised values. Mechanical sweep; isolated surface.

- **rf2-?????** — *Causa: film-strip header component.* Single reusable
  `[◀ Prev] [Next ▶]` header consumed by every L4 panel. Per §17.1.5 hit-
  target sizing (28×20px) + §17.2 state matrix (hover · focus-ring ·
  pressed · disabled at spine ends). Keyboard `← / →` global binding.
  Gates: panel-by-panel film-strip rollout.

- **rf2-?????** — *Causa: per-L4 panel header icons.* Add the §17.1.5
  Unicode header glyphs (⚡ ◉ ◐ ⬢ ◆ 🌐 ⚠ ✦) to the panel `<h1>` chrome
  via a new `theme/tokens/panel-icon` map. Renders 8px to the left of
  the accent stripe. Mechanical, single-file PR.

- **rf2-?????** — *Causa: L2 row activity badges + dispatch-origin
  prefix.* Already drafted in §13; the §17 visual pass binds the exact
  glyph palette + per-glyph color token + HCM remap. Worker implements
  against §17.1.5 + §17.1.3 mapping.

- **rf2-?????** — *Causa: machine-state pulse keyframe.* Add
  `rf-causa-machine-pulse` keyframe to `theme/global_styles motion-css`
  alongside the existing flash + fade keyframes. 1.2s ease-in-out
  infinite; interpolated through `--rf-causa-motion-scale` for
  reduced-motion collapse. Gates: xyflow current-state node rendering.
