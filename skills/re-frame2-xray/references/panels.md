# panels — Dynamic lenses on one event + Static registry browse

Sources of truth: the live tab inventory is the set of
`panel-registry/reg-l4-tab!` calls under
`tools/xray/src/day8/re_frame2_xray/panels/` (Dynamic) and
`.../static/` (Static); the normative tab list is
[`018-Event-Spine.md` §5](../../../tools/xray/spec/018-Event-Spine.md)
(Dynamic) + [`007-UX-IA.md` §Static mode](../../../tools/xray/spec/007-UX-IA.md)
(Static). Per-panel content design lives in
[`021-Dynamic-Panel-Designs.md`](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
(per-panel layout, locked decisions, palette / iconography / animation);
[`007-UX-IA.md`](../../../tools/xray/spec/007-UX-IA.md) for chrome,
palette tokens, density. Tab order is set declaratively via `reg-l4-tab!`
`:order` and rendered by `shell.cljs` (Dynamic) / `static/shell.cljs`
(Static).

## Two modes

The two-mode model (Dynamic event-spine 4-layer chrome · Static registry
3-layer chrome, flipped by the L1 mode pill or `Cmd/Ctrl+Shift+M`) is
covered in [`SKILL.md` §Two modes](../SKILL.md#two-modes). This leaf is
the per-panel tour: the 7 Dynamic tabs in §Panel-by-panel below, the 5
Static tabs in §Static mode — registry browse. One binding constraint to
restate: **no cross-epoch L4 panels** — every Dynamic L4 tab is a lens on
the one focused epoch; aggregate signal lives on L2 badges only (§021
§1.2 — binding).

### L2 timeline grammar

The L2 timeline above the panels carries:

- **Dispatch-origin prefix glyph** per row — one of
 `:user :router :websocket :http :ssr :fx-emit :timer :test-harness :tool :internal`
 (per Spec 002; §021 §1.5 is the universal classifier).
- **Activity badge cluster** per row (live impl `l2_timeline.cljc`
 `activity-badge-glyphs`, render order issue → machine → HTTP → fx-emit →
 timer): `⚠` issue (error) · `◆` machine transition · `🌐` HTTP activity ·
 `⚡` fx-emit child dispatch · `⏲` timer-triggered. HCM remap is automatic
 (colour is never alone). *(Spec §021 §17.1.5's row-badge table also
 documents `💧` SSR-hydration + `🌊` flow-recomputed as normative-but-not-
 yet-in-the-cluster, and maps `🌐`/`⚡` to route/HTTP — a pre-alpha spec-vs-
 impl drift; the live tool above is authoritative.)*

Implementation lives at
[`panels/l2_timeline.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/l2_timeline.cljc).

### Scope rule — every L4 panel is focused-epoch-scoped

Every L4 panel answers "what happened in **this** epoch?" — through its
own lens. Cross-epoch signals belong on L2 badges, never inside L4.

### Inspection vs Rewind

Clicking an L2 row is **INSPECTION** — L4 panels rebind to that epoch's
captured snapshots; app-db is NOT rolled back. Rewind is a separate
affordance in the focused-epoch header (`002-Time-Travel.md`).

## Panel-by-panel (Dynamic mode)

Seven Dynamic tabs, in their fixed L3-tab order (§018 §5; mnemonics
`e a v t m r i`): **Event · app-db · Views · Trace · Machines ·
Routes · Issues.** (Display labels are the all-plural-domain-noun set,
Mike-direction 2026-05-21; internal tab ids stay `:event :app-db :views
:trace :machines :routing :issues`.)

Most Dynamic tabs share the same chrome: panel icon (left of stripe) ·
panel title · focused-event id · `[◀ Prev] [Next ▶]` film-strip walking
the L2 spine chronologically (per §021 §17.1.5; shared component at
[`panels/shared/film_strip/header.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/film_strip/header.cljc)).
**Trace opts out of the film-strip header** (rf2-o6yqq) — the L2 events
list already owns spine focus navigation.

### Event — `⚡` · stripe `:accent-violet` · mnem `e`

Question: **What did this event DO?** — the handling pipeline.

Six-step linear pipeline rendered top-to-bottom with explicit arrows
(per §021 §2.2):

1. **DISPATCH** — event vector, origin tag, call-site (open-in-editor), timestamp
2. **COEFFECTS ASSEMBLED** — `:db` slice, `:now`, registered coeffects
3. **HANDLER INVOKED** — handler-id, file:line (open-in-editor), DEBUG-gated source string
4. **EFFECTS RETURNED** — handler intent (`:db` + `:fx`), inline diff for `:db`
5. **EFFECTS APPLIED** — what actually happened (db written, fx settlement/in-flight markers)
6. **FLOWS RECOMPUTED** — per-flow recompute or `(input unchanged · skipped)` dim row

All six steps default-expanded (the pipeline IS the punch, §021 §2 +
§17.3). Ends with the `db committed for epoch #N` marker — the pivot
to the Views panel. Header film-strip walks the L2 spine
chronologically.

**Open when:** "what did this event do?", "what fx fired?", "what did
the handler return?", "did the flow recompute?"

Spec: [`021-Dynamic-Panel-Designs.md` §2](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md);
implementation at
[`panels/event_detail.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/event_detail.cljs)
+ `panels/event/`.

### app-db — `◐` · stripe `:cyan` · mnem `a`

Question: **What does state LOOK LIKE — and what just changed?**

Two-zone layout (§021 §4.2):

- **DIFF zone** — changed paths for the focused epoch (`← changed`,
 `← changed from <prior>`, `← added`). Narrow, dense, scannable.
- **STATE zone** — the full db at end of epoch, rendered via the
 shared lazy-tree data-display (depth-3-collapsed default per §021
 §10.4) with diff annotations inline.

**Downstream-subs hover popover** (§021 §4.4) — hover any changed path
to surface the subs depending on it + the views rendered + an inline
`⤴` to jump to the Views panel scrolled to those subs. Popover is
Xray-owned (not a browser title), keyboard-dismissable. Walks subs
from the registry's `:input-paths` — see
[`panels/shared/sub_input_paths.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/sub_input_paths.cljc).

When the L2 spine is at head (no historical epoch focused), the DIFF
zone shows the most-recent epoch's diff; STATE shows current db. Same
render shape — no second mode.

**Open when:** "what just changed in app-db?", "what's downstream of
`[:cart :items]`?", "show me the full db at this epoch."

Spec: [`021-Dynamic-Panel-Designs.md` §4](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`004-App-DB-Diff.md`](../../../tools/xray/spec/004-App-DB-Diff.md);
implementation at
[`panels/app_db_diff.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/app_db_diff.cljs)
+ siblings (`app_db_diff_{events,format,subs,state}.cljs`); the
downstream-subs walk lives in
[`panels/app_db_diff_subs.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/app_db_diff_subs.cljs)
+ the shared
[`panels/shared/sub_input_paths.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/sub_input_paths.cljc).

### Views — `◉` · stripe `:cyan` · mnem `v`

Question: **What RENDERED as a result?**

The display label is **Views** — the rename chain ran `Views`
(pre-rebuild) → `Reactive` (§021 §11.5) → `View` → back to `Views`, the
final all-plural-domain-noun convention (Mike-direction 2026-05-21), set
at `reactive_panel.cljs:75`. **The L3 tab key stays `:views`** — it's an
internal id, not a user contract, so only the display label rebases.

The reactive cascade (Spec 009 ops 7-8) rendered as a depth-first DAG
with explicit indentation showing sub-of-sub layering:

- **Step 7 — SUBS RECOMPUTED** — each sub with input-path → output-value
 change inline (`:idle → :submitting`, `+1 entry`), with skipped subs
 collapsed under a footer `[Show N unchanged subs ▾]` (§021 §3.4).
- **Step 8 — VIEWS RE-RENDERED** — each view with file:line
 (open-in-editor) + `caused-by ← sub ← path` causation chain on every
 leaf (no expand-to-see, §021 §3.2).

Flows are NOT in the reactive cascade — they're handling-side (Event
step 6) per §021 §3.2. The cascade nodes are exactly: db-paths (seed)
→ subs (intermediate) → views (leaf).

"Show unchanged subs" toggle defaults OFF (§021 §3.4 + §11.4).
Per-cascade clicks propagate cross-panel: sub row → app-db at that
input path; `caused-by ← sub ← path` chip → app-db at that path.

**Open when:** "why didn't my view update?", "what re-rendered?",
"trace the recompute chain for sub X", "which subs short-circuited?"

Spec: [`021-Dynamic-Panel-Designs.md` §3](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`012-Views.md`](../../../tools/xray/spec/012-Views.md);
implementation at
[`panels/reactive_panel.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/reactive_panel.cljs)
+ siblings under `panels/reactive_panel_*.cljs`.

### Trace — `⬢` · stripe `:orange` · mnem `t`

Question: **What raw trace events fired during this epoch?**

The underlying stream that Event + Views summarise. **Focused-epoch
scoped** (per §021 §1.2 — no aggregate-across-epochs view) — each row
is a single mono line `#id +Xms op-kw inline-summary`. **No filtering
UI** (rf2-gkczt): the focused epoch IS the scope, so the panel-local
chip filters (and the clear-filters control) are gone — the only
drill-down is per-row payload expand.

Per-row click expands the payload inline via the EDN widget's current-
state `browse` (cljs-devtools look) — type-coloured, nested, expanded.
**No film-strip header** (rf2-o6yqq) — the L2 events list owns spine
focus navigation.

(Spec 009's `trace-buffer` filter vocabulary — `:op-type`,
`:dispatch-id`, the tag axes — is real for the *programmatic* API but is
**not** surfaced as Trace-panel UI.)

**Open when:** "show me every raw op in this epoch", "is `:rf.fx/*`
firing as expected?", "what order did these emit in?"

Spec: [`021-Dynamic-Panel-Designs.md` §5](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`013-Trace-Consumer.md`](../../../tools/xray/spec/013-Trace-Consumer.md)
+ [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md);
implementation at
[`panels/trace.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/trace.cljs).

### Machines — `◆` · stripe `:green` · mnem `m`

Question: **What did this event do to my machines?**

**Event-driven** (no picker, no Mode A/B/C): the panel is
BLANK when the focused event had no machine activity, and renders one
per-machine section (topology + transition highlight + guards + actions +
cancellation cascade + `:after` rings) when it does. Per-machine
prev/next nav walks the spine to the next event that touched that
machine. (To browse a machine's *full* topology regardless of the focused
event, flip to **Static mode** and open its Machines tab — its Topology
sub-mode is the spine-INDEPENDENT canvas browser.)

Topology-plus-overlay (§021 §6 + §17.4). Each machine renders as an
xyflow canvas (path B locked per §021 §6.0 — xyflow with Xray-palette
styling; not Stately Inspect, not native Reagent). Nodes, edges,
current-state pulse, parallel-region containers, final-state double-rings
all render through
[`panels/machines/xyflow_style.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/machines/xyflow_style.cljs)
(per §021 §17.4.5).

**Current-state precedence** — a 4-source walk-back resolves the
machine's current state for the focused epoch:

1. **Explicit** — operator override (sticky selection)
2. **Focused-epoch transition** — if this epoch fired a transition for the machine
3. **Epoch-history walk-back** — scan the buffer back to the most-recent transition
4. **Snapshot** — fall back to the substrate's per-frame machine state

The resolved current state node carries the `rf-xray-machine-pulse`
keyframe (1.2s ease-in-out, interpolated through
`--rf-xray-motion-scale` so reduced-motion collapses it; §021
§17.4.5).

Per-canvas footer lists guards / actions / cancellation cascade chips
inline (no modal, no popout). Empty state (machines registered, no
activity this epoch) renders the topology with `current ●` annotation
intact — topology is always visible (§021 §6.2 Case B).

**Open when:** "what state is my checkout machine in?", "what
transition fired this epoch?", "what guards passed / failed?"

Spec: [`021-Dynamic-Panel-Designs.md` §6 + §17.4](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`003-Machine-Inspector.md`](../../../tools/xray/spec/003-Machine-Inspector.md);
implementation at
[`panels/machine_inspector.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/machine_inspector.cljs)
+ `panels/machines/`.

> **Browse-all machine canvas → Static mode.** The spine-INDEPENDENT
> "what does this machine LOOK like overall?" canvas (picker + interactive
> zoom / pan / fit, regardless of focused event) is **not a Dynamic tab** —
> it lives under Static mode's Machines tab (Topology sub-mode); rf2-ga16q
> removed the standalone Dynamic "Machines Canvas" tab. Impl
> [`static/machines/topology.cljs`](../../../tools/xray/src/day8/re_frame2_xray/static/machines/topology.cljs).

### Routes — `🌐` · stripe `:yellow` · mnem `r`

Question: **What did this event do to my routes?** (Display label
**Routes**, plural-noun convention; internal tab id `:routing`.)

Same topology-plus-overlay pattern as Machines, rendered as a textual
tree (route trees are typically ≤ 4 levels deep, so a tree with `├─ └─`
box-drawing is denser AND simpler than xyflow — per §021 §7.1).

Two blocks:

- **Active route tree** (always visible) — each node with one of three
 markers per current state and per-epoch activity:
 - `◉` active this epoch, on the resolved match
 - `◇` registered, traversed (`:can-leave` / `:can-enter`) this epoch
 - `●` current active node (no activity this epoch)
- **This epoch** — short dense block: `Phase`, `From`, `To`, `Match`,
 `Events`. Empty state ("No route activity in this epoch.") keeps
 the tree visible above.

Reads `:rf.route/can-leave`, `:rf.route/can-enter`, `:rf.route/on-match`,
`:rf.route/fragment-changed` filtered by `:dispatch-id`.

**Open when:** "what route am I on?", "what params did the nav-token
resolve?", "did the route change this epoch?"

Spec: [`021-Dynamic-Panel-Designs.md` §7](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`spec/012-Routing.md`](../../../spec/012-Routing.md);
implementation at
[`panels/routing.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/routing.cljs).

### Issues — `⚠` · stripe `:red` · mnem `i`

Question: **What's wrong in this epoch?**

Per-epoch errors + warnings + schema violations + hydration mismatches +
perf-budget overruns + app console errors/warns, unified.
**Focused-epoch scoped** (§021 §8.1). Each issue renders as a 4-6 row
block (severity · op-key · handler / schema · message · path / ex-data)
with the ex-data laid out via the shared data-display renderer at
depth-2-expanded.

**Head-fallback contract** — when the L2 spine is at head (no
historical epoch focused), the panel scopes to the most-recent epoch's
issues (matches the same head-fallback the other focused-epoch panels
use; resolved via the shared
[`panels/shared/focus_resolver.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/focus_resolver.cljc)).
Empty state is a single line.

Stretch film-strip: "next epoch with ⚠ badge" — operator stepping
through a bug repro lands on issue-bearing epochs only.

**Open when:** "anything broken in this epoch?", "show me all schema
failures here", "what warnings fired?"

Spec: [`021-Dynamic-Panel-Designs.md` §8](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`spec/009-Instrumentation.md` §Error event catalogue](../../../spec/009-Instrumentation.md);
implementation at
[`panels/issues_ribbon.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/issues_ribbon.cljs)
+ [`panels/issues_ribbon_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/issues_ribbon_helpers.cljc).

> **Accessibility note.** A11y dogfooding is **not** a Xray tab — the
> pre-rebuild "Chrome A11y" tab was removed. A11y scanning lives in Story
> (`re-frame.story.ui.chrome-a11y` + the variant scanner
> `re-frame.story.ui.a11y`). Route a11y questions there, not to Xray.

## Static mode — registry browse

Static mode answers a different question from Dynamic:
**what is registered**, not **what just happened**. It drops the L2 event
spine (3-layer chrome) and renders 5 catalogue tabs over the picked
frame's registries. Flip into it with the L1 mode pill or
`Cmd/Ctrl+Shift+M`. Source of truth:
[`007-UX-IA.md` §Static mode](../../../tools/xray/spec/007-UX-IA.md);
sources under
[`tools/xray/src/day8/re_frame2_xray/static/`](../../../tools/xray/src/day8/re_frame2_xray/static).

Mnemonics are **mode-scoped** — the same letter dispatches the active
mode's tab (`m` in Dynamic opens the Machines instance-inspector; `m` in
Static opens the Machines registry browse).

| Tab | Mnem | Question it answers | Implementation |
|---|---|---|---|
| **Machines** *(default)* | `m` | "What machines are registered, and what do they look like?" Registry browse + topology + a 4-mode sub-strip (incl. the Sim engine). | [`static/machines/panel.cljs`](../../../tools/xray/src/day8/re_frame2_xray/static/machines/panel.cljs) |
| **Routes** | `r` | "What routes are registered, and which would `/x/y` match?" Registered routes + Simulate-URL (promoted from the Dynamic Routes lens). | [`static/routes/panel.cljs`](../../../tools/xray/src/day8/re_frame2_xray/static/routes/panel.cljs) |
| **Schemas** | `c` | "What schemas are registered, and what shape do they expect?" Registered schemas + sample data + jump-to-source. | [`static/schemas/panel.cljs`](../../../tools/xray/src/day8/re_frame2_xray/static/schemas/panel.cljs) |
| **Flows** | `f` | "What flows are registered?" The flows catalogue. | [`static/flows/panel.cljs`](../../../tools/xray/src/day8/re_frame2_xray/static/flows/panel.cljs) |
| **Interceptors** | `i` | "What interceptors run, and in what order?" Pure-browse lens over the interceptor chains. | [`static/interceptors/panel.cljs`](../../../tools/xray/src/day8/re_frame2_xray/static/interceptors/panel.cljs) |

The L1 frame picker is mode-independent — registries are frame-scoped, so
pick the frame whether you're in Dynamic or Static. The mode choice lives
at `[:rf.xray/mode]` (`:dynamic | :static`, persisted to localStorage);
the Static-scoped tab choice lives at
`[:rf.xray.static/selected-tab]` (default `:machines`), independent of
Dynamic's `[:rf.xray/selected-tab]` so flipping modes preserves both.

**Open when:** "where do I see all my registered machines / routes /
schemas / flows / interceptors?", "browse the whole registry", "what's
registered in this frame?" — anything that is about the *registry* rather
than a single dispatch.

## Shared components + iconography

The three components every L4 panel reuses (`data_display/render`,
`film_strip/header`, `focus_resolver`) and the full tab-icon / L2-badge /
cross-panel-arrow glyph reference live in
[`shared-components.md`](shared-components.md).

## What's deliberately NOT here

Per §021 §15 (Dynamic mode) + §007 §Static mode:

- **No extra Dynamic L4 lens.** The 7-tab Dynamic set is the contract;
 sub-layer surfaces inline in Views + the app-db hover popover (no peer
 Subs panel).
- **No Chrome A11y tab.** Removed; a11y dogfooding is Story's domain.
- **No standalone Dynamic "Machines Canvas" tab.** Removed (rf2-ga16q);
 the spine-INDEPENDENT browse-all machine canvas lives under Static
 mode's Machines tab (Topology sub-mode). The Dynamic Machines tab is
 purely the event-driven lens.
- **No cross-epoch Dynamic L4 views.** Aggregate signals live on L2
 badges only.
- **No pattern-view.** Deferred.
- **No master-detail Event-vs-Views coupling.** Peers, bridged by app-db.
- **No simultaneous multi-frame display.** Single-frame focus (§021
 §1.6); switch focus via the L1 frame picker.
- **No legacy panels.** Subscriptions, Effects, Flows, Performance,
 Schemas, Hydration are NOT separate Dynamic tabs. Their content is
 surfaced through the Dynamic 7 above — and the registry catalogues live
 in Static mode:
 - Subscriptions → Views (cascade tree) + app-db (hover popover)
 - Effects → Event step 4 (returned) + step 5 (applied) + Trace (raw `:rf.fx/*` ops)
 - Flows → Event step 6 (per event) · Static → Flows (registry)
 - Performance → L2 row stripe colours + per-step `:time` in Trace
 - Schemas → Issues (per event) · Static → Schemas (registry)
 - Hydration → Issues (unified feed)

For the user-question → tab routing tables, see
[`SKILL.md` §The tabs — what each surfaces](../SKILL.md#the-tabs--what-each-surfaces).
