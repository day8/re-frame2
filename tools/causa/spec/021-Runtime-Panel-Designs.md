# 021-Runtime-Panel-Designs

Worker design doc for the Causa **Runtime L4 panel redesign** (rf2-dur6w).
Co-drafted by Mike + mayor; this doc is the implementer's reference
for the **per-panel content layout**, **shared data-display renderer**,
**locked decisions**, and **substrate gaps** that the redesign implies.

Canonical foundation: [`ai/prompts/causa-interface-adjustments.md`](../../../ai/prompts/causa-interface-adjustments.md)
(local-only working doc). The framing in §1 below is a synthesis — the
super-prompt remains the authoritative statement of intent.

Cross-refs:
- [`000-Vision.md`](000-Vision.md) — the five canonical questions
- [`007-UX-IA.md`](007-UX-IA.md) — chrome, palette, density (still load-bearing)
- [`013-Trace-Bus.md`](013-Trace-Bus.md) — substrate the panels read
- [`018-Event-Spine.md`](018-Event-Spine.md) — `:rf.causa/focus` contract
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) — 5 idioms × 4 areas

Owner: tools/causa.

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
| Right-click any value | Data-display contextual menu (§8) |

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

### §3.2 Layout — DAG visualised as indented cascade

The reactive cascade is a DAG (§A.3 super-prompt). The Reactive panel
renders it depth-first with explicit indentation showing sub-of-sub layering.

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
| `caused-by ← sub ← path` chip | Each chip is clickable; "path" jumps to App-db panel at that path |
| Right-click view row | Filter-IN on view-render origin (stretch, B.5) |

### §3.7 Film-strip

Same `[◀ Prev] [Next ▶]` shape as Event. MVP chronological; stretch
filter "next epoch with view re-render" (skip the silent epochs).

---

## §4 The App-db panel (state bridge)

### §4.1 Question

> **"What does state LOOK LIKE — and what just changed?"**

App-db is the bridge between Event (writes) and Reactive (reads). It
anchors the cascade's seed paths.

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
| Changed-path row | Highlights the same path in the STATE zone below |
| Path segment | Open segment-inspector at path-prefix (existing affordance §004) |
| Hover overlay `⤴` | Switch to **Reactive**, scrolled to the listed views |
| Right-click path | "Show epoch that last changed this path" (uses film-strip nav semantics — stretch) |

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

### §6.2 Layout

Three cases (per existing §003 + the refined topology-plus-overlay rule):

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
view-mode) wraps each per-machine canvas — preserved unchanged.

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
│   Events      [:rf/url-changed] [:cart/route-entered]                │
│                                                                      │
│ Empty (no route activity this epoch):                                │
│   Shows tree with current active node highlighted; "This epoch"      │
│   section reads "No route activity in this epoch."                   │
└──────────────────────────────────────────────────────────────────────┘
```

### §7.3 Queries

| From | Reads |
|---|---|
| Trace bus | `:rf.route/can-leave`, `:rf.route/can-enter`, `:rf.route/on-match`, `:rf.route/url-changed` — filtered by `:dispatch-id` |
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

## §9 The Chrome A11y panel (unchanged)

Causa's own chrome accessibility dogfood, per §007 / rf2-5r2yj. Spine-
independent — same shape pre- and post-redesign. No further work in this
spec.

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
   Clicking propagates cross-panel: select a path in App-db → Reactive
   highlights downstream subs + views. Right-click for "blame /
   show epoch that last changed this path."

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
disclosure choices survive epoch navigation. Reset via right-click →
"Collapse all" / "Expand all to default."

### §10.5 Interaction model

| Gesture | Effect |
|---|---|
| **Click node header** (▸ / ▾) | Toggle expand/collapse |
| **Click key** | Open segment-inspector popover at path-prefix (existing §004 affordance) |
| **Click path segment** | Highlight same path in App-db panel; if cross-panel, switch and scroll |
| **Right-click value** | Context menu: Copy value · Copy path · Show epoch that last changed this · Filter-IN on path |
| **Hover changed-row** | Tooltip: "Changed in epoch #42 by `[:checkout/submit …]`" |
| **Keyboard `Space`** on focused row | Toggle expand/collapse |
| **Keyboard `c`** on focused row | Copy value to clipboard |
| **Keyboard `p`** on focused row | Copy path to clipboard |

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
| Meta-epoch section ordering | **Fixed order: Event > App-db > Reactive > Trace > Machines > Routing > Issues > Chrome A11y** | Matches the L3 tab order. Predictable beats dynamic. |
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
- [`003-Machine-Inspector.md`](003-Machine-Inspector.md) — Machines panel current behaviour (§6 extends)
- [`004-App-DB-Diff.md`](004-App-DB-Diff.md) — App-db diff (§4 extends with overlay)
- [`007-UX-IA.md`](007-UX-IA.md) — palette tokens, spacing, density (§10 reuses)
- [`012-Views.md`](012-Views.md) — Views panel current behaviour (§3 rebuilds as Reactive)
- [`013-Trace-Bus.md`](013-Trace-Bus.md) — trace-op contract (§12 extends)
- [`014-Registry-Catalogue.md`](014-Registry-Catalogue.md) — `:rf.causa/*` ids; new ids implied by §13 land here
- [`018-Event-Spine.md`](018-Event-Spine.md) — `:rf.causa/focus` (every §-scoped panel binds to this)
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) — 5×4 matrix; §6 / §7 are matrix entries
- `ai/prompts/causa-interface-adjustments.md` — canonical super-prompt (local-only)
- `ai/findings/2026-05-20-causa-runtime-information-architecture.md` — earlier exploratory analysis (local-only)
