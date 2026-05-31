# panels — Dynamic lenses on one event + Static registry browse

Sources of truth: the live tab inventory is the set of
`panel-registry/reg-l4-tab!` calls under
`tools/xray/src/day8/re_frame2_xray/panels/` (Dynamic) and
`.../static/` (Static); the normative tab list is
[`018-Event-Spine.md` §5](../../../tools/xray/spec/018-Event-Spine.md)
+ [`021-Dynamic-Panel-Designs.md` §9.1](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
(Dynamic) + [`007-UX-IA.md` §Static mode](../../../tools/xray/spec/007-UX-IA.md)
(Static). Per-panel content design lives in
[`021-Dynamic-Panel-Designs.md`](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
(per-panel layout, locked decisions, palette / iconography / animation);
[`007-UX-IA.md`](../../../tools/xray/spec/007-UX-IA.md) for chrome,
palette tokens, density. Tab order is set declaratively via `reg-l4-tab!`
`:order` and rendered by `shell.cljs` (Dynamic) / `static/shell.cljs`
(Static). The live Dynamic `:order` values are Epoch `-1` · app-db `1` ·
Views `2` · Trace `3` · Machine `4` · Routes `6`.

## Two modes

The two-mode model (Dynamic event-spine 4-layer chrome · Static registry
3-layer chrome, flipped by the L1 mode pill or `Cmd/Ctrl+Shift+M`) is
covered in [`SKILL.md` §Two modes](../SKILL.md#two-modes). This leaf is
the per-panel tour: the 6 Dynamic tabs in §Panel-by-panel below, the 5
Static tabs in §Static mode — registry browse. One binding constraint to
restate: **no cross-epoch L4 panels** — every Dynamic L4 tab is a lens on
the one focused epoch; aggregate signal lives on L2 badges only (§021
§1.2 — binding). There is **no Issues tab** — issues surface inline (the
Epoch cascade's per-step ✓/✗ + the shared Exception card), via the L2
pink-wash, and via the always-on `:rf.xray/issues-ribbon` signal
(rf2-gbz39; see §What's deliberately NOT here).

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
- **Issue pink-wash** per row (rf2-b8guz) — a cascade carrying an issue
 washes its whole L2 row with the `:bg-issue-row` light-pink token. The
 wash is driven by `cascade-has-issue?`, which reuses the same
 `issue-event?` predicate as the issues-ribbon signal so the wash and the
 ribbon stay in lockstep by construction. This is now (with the Epoch
 cascade's per-step ✓/✗) the primary "which epochs are broken?" signal —
 the dedicated Issues tab is gone (rf2-gbz39).

Implementation lives at
[`panels/l2_timeline.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/l2_timeline.cljc)
+ [`panels/issues_ribbon_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/issues_ribbon_helpers.cljc).

### Scope rule — every L4 panel is focused-epoch-scoped

Every L4 panel answers "what happened in **this** epoch?" — through its
own lens. Cross-epoch signals belong on L2 badges, never inside L4.

### Inspection vs Rewind

Clicking an L2 row is **INSPECTION** — L4 panels rebind to that epoch's
captured snapshots; app-db is NOT rolled back. Rewind is a separate
affordance in the focused-epoch header (`002-Time-Travel.md`).

## Panel-by-panel (Dynamic mode)

Six Dynamic tabs, in their fixed L3-tab order (§018 §5 + §021 §9.1;
mnemonics `e a v t m r`): **Epoch · app-db · Views · Trace · Machine ·
Routes.** (Internal tab ids stay `:epoch :app-db :views :trace :machines
:routing` — the display labels rebased over a rename history but the ids
are stable.) The pre-rebuild **Event** panel was retired (rf2-5gl5r,
2026-05-27 — `panels/event_detail.cljs` is deleted) and the **Issues**
tab was retired (rf2-gbz39, 2026-05-31 — `panels/issues_ribbon.cljs` is
deleted); see §What's deliberately NOT here.

Most Dynamic tabs share the same chrome: panel icon (left of stripe) ·
panel title · focused-event id · `[◀ Prev] [Next ▶]` film-strip walking
the L2 spine chronologically (per §021 §17.1.5; shared component at
[`panels/shared/film_strip/header.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/film_strip/header.cljc)).
**Trace opts out of the film-strip header** (rf2-o6yqq) — the L2 events
list already owns spine focus navigation.

### Epoch — `⚡` · stripe `:accent-violet` · mnem `e` · `:order -1`

Question: **What happened in this epoch?** — the full computational
timeline. Default landing view; supersedes the retired Event panel
(rf2-5gl5r). Registered at `:order -1` so it claims the leftmost /
default-landing slot.

A **numbered vertical cascade** rendered top-to-bottom — a faithful
projection of the epoch's trace stream. Each step is **conditional**:
it renders iff its driving trace events surfaced this epoch, and steps
are numbered dynamically 1..N so an absent optional step consumes no
number (absence is conveyed by OMISSION, not an empty-state row). The
step order, per `panels/epoch/projection.cljc`'s `project` (§021 §9.1.3):

1. **DISPATCH** — always present (every epoch starts here). Event vector,
 origin tag, call-site (open-in-editor).
2. **COEFFECT** — **one numbered step per user-injected coeffect**
 (system defaults `:db / :event / :frame / :source / :trace-id` are
 filtered at projection time). A cofx that threw on injection gets a
 synthesised placeholder step so its exception card has a home.
3. **INTERCEPTOR** — **conditional, exception-only**: rendered ONLY when a
 user interceptor threw this cascade (the substrate emits no
 per-interceptor "ran" trace, so a clean chain shows nothing). One row
 per throwing interceptor — id + `:before` / `:after` phase chip + the
 shared Exception card. Sits between COEFFECTS and HANDLER (the chain's
 cascade position). NEW per rf2-yz57h.
4. **HANDLER** — always present; body adapts to the handler flavour
 (`:reg-event-db` → `:db` diff · `:reg-event-fx` → `:db` + per-fx ·
 `:reg-machine` → the time-ordered machine cascade, rf2-u69j7). Rendered
 as **SKIPPED** (⊘) when an upstream `:before`-chain throw — a coeffect
 injector or a `:before` interceptor — aborted the cascade before the
 handler ran (rf2-yz57h; NOT "ran, returned no :db").
5. **FLOW** — one numbered step per flow that fired (the t1→t2 reshape as
 the flow's own `:db` diff). Only when flows fired.
6. **SIDE EFFECTS** — a flat per-effect ledger (see §SIDE EFFECTS below).
 Only when a side effect occurred; equally SKIPPED when an upstream
 throw aborted the cascade.
7. **SUBSCRIPTIONS** — only when subs recomputed.
8. **VIEWS** — only when views re-rendered.

**Per-step status + inline exceptions.** Every step header carries a
status glyph off the shared `step-status` primitive — `✓` (`:ok`) / `✗`
(`:error`) / `⊘` (`:skipped`) (`panels/epoch/badge.cljc`). A
handler / interceptor / coeffect / fx / flow **exception** renders UNDER
the step where it occurred via the shared **"Exception Thrown"** card
(rf2-wnvid) — each exception attached to its owning step per
`exception-op->step`; the epoch's outcome reads `:error` whenever any step
settled `:error` (trace-derived, NOT the framework epoch-record `:outcome`
slot). Schema violations attach inline the same way.

**Badge taxonomy** (the inventory `badge-set` enforces; `badge.cljc`):
DISPATCH (`:text-tertiary`) · COEFFECT (`:magenta`) · INTERCEPTOR
(`:accent`) · HANDLER (`:accent`) · FLOW (`:accent`) · SIDE-EFFECTS
(`:orange`) · SUBSCRIPTIONS (`:magenta-pink`) · VIEWS (`:success`). (The
view's colour resolver bails to `:text-tertiary` on an unknown badge.)

**Open when:** "what did this event do?", "where did the cascade fail?",
"what fx fired?", "did the flow recompute?"

Spec: [`021-Dynamic-Panel-Designs.md` §9.1](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md);
implementation at
[`panels/epoch_panel.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/epoch_panel.cljs)
+ [`panels/epoch/`](../../../tools/xray/src/day8/re_frame2_xray/panels/epoch)
(`view.cljs` · `projection.cljc` · `badge.cljc`).

### SIDE EFFECTS step — flat per-effect ledger (rf2-j630b)

The Epoch cascade's SIDE EFFECTS step renders as **one flat ledger** —
one row per effect, down the page, in execution order, with **no group
headers** (supersedes the old 3-tier / "EFFECTS RETURNED + EFFECTS
APPLIED" split). Per `panels/epoch/projection.cljc`'s `side-effects-step`:

- **One row per effect**, leading with a per-effect status glyph: `✓` ran
 ok · `✗` threw / no-such-fx / `:db` schema-fail rollback · `↺` fx
 override applied · `–` (muted en-dash) skipped-on-platform or a dropped
 `other` effect (NEUTRAL — never trips the badge).
- **Single AND-of-rows badge** after the "SIDE EFFECTS" label: TICK when
 every present row succeeded, CROSS when one or more FAILED; SKIPPED rows
 are neutral (`side-effects-badge-status`).
- **The `:db` row** leads the ledger when a `:db` commit was attempted
 (incl. a plain reg-event-db returning only `:db`); its args slot is the
 clickable **"→ app-db"** destination marker (the actual diff lives in
 the app-db panel — no duplication). Absent when the handler returned
 only `:fx` / nothing / threw (no phantom `:db`, rf2-wnvid).
- **fx exceptions** attach to the owning `:fx-id` row via the shared
 Exception card; `:db` schema-fail rollback paints the `:db` row ✗ with
 the reason box, and a rollback means `:fx` never walked (Spec 002
 atomicity) — so the ledger carries only the red `:db` row.

### app-db — `◐` · stripe `:cyan` · mnem `a`

Question: **What does state LOOK LIKE — and what just changed?**

Sectioned-by-reserved-area layout (§021 §4.2 — the prior DIFF / STATE
two-zone split is **superseded**, per §021 line 693). The complete
app-db renders as **vertical sections**, each headed by an uppercase
caption label and rendering its value as a collapsible cljs-devtools-
style inspector widget (shared lazy-tree renderer, depth-3-collapsed
default per §021 §10.4). Adjacent sections are separated by a 1px
hairline. Diff annotations are carried **inline** as `← was X`
on changed nodes within each section's tree — there is no separate
DIFF zone; ancestor chains are force-expanded so the operator never
expands to find a change. Section order, top → bottom:

- **APP STATE** (always shown) — the app-db **minus** every reserved
 `:rf/*` key (the application's own user-domain state).
- **MACHINE `<id>`** — `:rf/machines` **fans out** to one section per
 machine, headed by the machine id (e.g. `MACHINE :title/flow`).
- **SPAWNED `<id>`** — `:rf/spawned` fans out the same way, one
 section per spawned instance.
- **ROUTE** — `:rf/route` is a **singleton** section (the current-
 route slice; NOT fanned out).
- **SYSTEM-IDS · PENDING-NAVIGATION · ELISION** — singleton sections
 for the remaining reserved areas (`:rf/system-ids`,
 `:rf/pending-navigation`, `:rf/elision`).

Populated reserved areas render; empty / absent reserved areas are
omitted from the model (rf2-jcdvo) so the operator isn't shown a
clutter of "no X here" placeholder cards. The APP STATE top section
always renders even when the user-domain db is empty — it's the
panel's anchor.

**Underlying paths.** Per rf2-eguy4 phase-A the runtime owns a single
`:rf/runtime` top-level slot; the operator-facing labels (`:rf/machines`,
`:rf/route`, …) map to nested sub-paths via the `runtime-areas` lookup
in
[`panels/app_db_diff_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/app_db_diff_helpers.cljc)
— e.g. `:rf/machines → [:rf/runtime :machines :snapshots]`,
`:rf/route → [:rf/runtime :routing :current]`. Spec source:
[`004-App-DB-Diff.md` §Reserved-keys group](../../../tools/xray/spec/004-App-DB-Diff.md).

**Downstream-subs hover popover** (§021 §4.4) — hover any changed path
within any section to surface the subs depending on it + the views
rendered + an inline `⤴` to jump to the Views panel scrolled to those
subs. Popover is Xray-owned (not a browser title), keyboard-
dismissable. Walks subs from the registry's `:input-paths` — see
[`panels/shared/sub_input_paths.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/sub_input_paths.cljc).

When the L2 spine is at head (no historical epoch focused), sections
show the most-recent epoch's state with its inline diff annotations
(head-cascade) — current db, sectioned. Same render shape, no second
mode.

**Open when:** "what just changed in app-db?", "what's downstream of
`[:cart :items]`?", "show me the full db at this epoch."

Spec: [`021-Dynamic-Panel-Designs.md` §4](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`004-App-DB-Diff.md`](../../../tools/xray/spec/004-App-DB-Diff.md);
implementation at
[`panels/app_db_diff.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/app_db_diff.cljs)
+ siblings (`app_db_diff_{events,format,subs,state,helpers}.{cljs,cljc}`);
the downstream-subs walk lives in
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

The reactive cascade (the SUBSCRIPTIONS + VIEWS trailing edge) rendered as
a depth-first DAG with explicit indentation showing sub-of-sub layering:

- **SUBS RECOMPUTED** — each sub with input-path → output-value
 change inline (`:idle → :submitting`, `+1 entry`), with skipped subs
 collapsed under a footer `[Show N unchanged subs ▾]` (§021 §3.4).
- **VIEWS RE-RENDERED** — each view with file:line (open-in-editor) +
 a **render-cause chip** on every re-render leaf (rf2-bhi3t,
 `panels/reactive_panel_view.cljs` `view-node` + the pure
 `panels/epoch/projection.cljc` `render-cause`): `← :sub-id` when a
 subscription the view derefs changed value (`:triggered-by`), or
 `← props` when none of the view's own subs changed (so the cause is the
 orthogonal `:rf/props` channel — a prop changed / parent re-rendered;
 the parent is never named, rf2-8ve8z). A first **mount** carries no
 cause (the `(mounted)` label conveys it).

Flows are NOT in the reactive cascade — they're handling-side (the Epoch
FLOW step) per §021 §3.2. The cascade nodes are exactly: db-paths (seed)
→ subs (intermediate) → views (leaf).

"Show unchanged subs" toggle defaults OFF (§021 §3.4 + §11.4).
Per-cascade clicks propagate cross-panel: a sub row → app-db at that
input path. Hovering a view node toggles a pink DOM highlight on the live
element (rf2-8l03l).

**Open when:** "why didn't my view update?", "what re-rendered?",
"trace the recompute chain for sub X", "which subs short-circuited?"

Spec: [`021-Dynamic-Panel-Designs.md` §3](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`012-Views.md`](../../../tools/xray/spec/012-Views.md);
implementation at
[`panels/reactive_panel.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/reactive_panel.cljs)
+ siblings under `panels/reactive_panel_*.cljs`.

### Trace — `⬢` · stripe `:orange` · mnem `t`

Question: **What raw trace events fired during this epoch?**

The underlying stream the Epoch + Views tabs summarise. **Focused-epoch
scoped** (per §021 §1.2 — no aggregate-across-epochs view), rendered as a
**single flat oldest-first row list** — the 4-band phase hierarchy /
envelope is GONE (rf2-aqusw #2545, because it was hard to scan). Each row
is six columns: **Δt · stage · area badge · what-happened · target/detail
· duration**.

The phase shape the bands conveyed is recovered flatly per-row by:

- a **stage column** naming the Epoch-panel pipeline step the op belongs
 to — `DISPATCH · COEFFECT · HANDLER · FLOW · SIDE EFFECTS ·
 SUBSCRIPTIONS · VIEWS`; and
- a **colour-coded left edge** in that stage's colour.

Both the stage label and the edge colour resolve through the Epoch
panel's OWN badge taxonomy (`panels.epoch.badge`) — **not** a parallel
palette — so the Trace stage column + edge match the Epoch numbered
cascade exactly (one step model, DRY). Errors / warnings are
cross-cutting (§023 §7): the row renders inline at its chronological
point, its left edge riding the severity colour over the stage colour.

**No filtering UI** (rf2-gkczt): the focused epoch IS the scope, so the
panel-local chip filters (and clear-filters) are gone — the only
drill-down is per-row click, which opens the **edn-inspector** on the
row's raw trace-event map inline. **No film-strip header** (rf2-o6yqq) —
the L2 events list owns spine focus navigation.

(Spec 009's `trace-buffer` filter vocabulary — `:op-type`,
`:dispatch-id`, the tag axes — is real for the *programmatic* API but is
**not** surfaced as Trace-panel UI.)

**Open when:** "show me every raw op in this epoch", "is `:rf.fx/*`
firing as expected?", "what order did these emit in?"

Spec: [`023-Trace-Panel.md` §3 + §3a](../../../tools/xray/spec/023-Trace-Panel.md)
+ [`021-Dynamic-Panel-Designs.md` §5](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
+ [`013-Trace-Consumer.md`](../../../tools/xray/spec/013-Trace-Consumer.md)
+ [`spec/009-Instrumentation.md`](../../../spec/009-Instrumentation.md);
implementation at
[`panels/trace.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/trace.cljs)
+ [`panels/trace_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/trace_helpers.cljc).

### Machine — `◆` · stripe `:green` · mnem `m`

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

### Issues — no longer a Dynamic tab

There is **no dedicated Issues tab**. Mike ruled it out (rf2-gbz39
#2540, Option (c), 2026-05-31): the standalone tab + its aggregate panel
(`panels/issues_ribbon.cljs`) were deleted and the session-wide triage
list was consciously dropped. "What's wrong in this epoch?" is answered
inline, through **three** always-on channels (per the surviving `.cljc`
algebra
[`panels/issues_ribbon_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/issues_ribbon_helpers.cljc)):

1. **Inline in the Epoch cascade** — per-step ✓ / ✗ status glyphs, the
 shared **"Exception Thrown"** card under the throwing step
 (handler / interceptor / coeffect / fx / flow exceptions), and the
 `:db` schema-fail rollback ✗ on the SIDE EFFECTS `:db` row. The
 errors + warnings + schema violations + hydration mismatches that the
 old tab unified now surface against the step where they occurred.
2. **L2 event-row pink-wash** (rf2-b8guz) — a cascade carrying an issue
 washes its L2 timeline row pink; the `cascade-has-issue?` predicate
 reuses `issue-event?` so the wash stays in lockstep with the ribbon.
3. **The always-on `:rf.xray/issues-ribbon` signal** — the composite
 (registered in `registry.cljs`) drives the auto-open-on-error watcher
 (`settings/effects.cljs/install-auto-open-watcher!`) — the cross-epoch
 "something is wrong" signal Mike kept.

So route "anything broken in this epoch?" to the **Epoch tab**; "which
epochs are broken?" to the **L2 pink-wash**.

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

The three components every L4 panel reuses (`edn_inspector/render`,
`film_strip/header`, `focus_resolver`) and the full tab-icon / L2-badge /
cross-panel-arrow glyph reference live in
[`shared-components.md`](shared-components.md).

## What's deliberately NOT here

Per §021 §15 (Dynamic mode) + §007 §Static mode:

- **No Issues tab.** Removed (rf2-gbz39 #2540, Mike Option (c),
 2026-05-31 — `panels/issues_ribbon.cljs` deleted). Issues are NOT a
 Dynamic tab; they surface inline in the **Epoch** cascade (per-step
 ✓/✗ + the shared "Exception Thrown" card), via the **L2 pink-wash**
 (rf2-b8guz), and via the always-on **`:rf.xray/issues-ribbon`** signal
 (auto-open-on-error). The session-wide aggregate / triage list was
 consciously dropped.
- **No Event tab.** Retired (rf2-5gl5r, 2026-05-27 —
 `panels/event_detail.cljs` deleted). The **Epoch** panel (numbered
 cascade, `:order -1`) is the canonical "what happened" surface.
- **No extra Dynamic L4 lens.** The 6-tab Dynamic set is the contract;
 sub-layer surfaces inline in Views + the app-db hover popover (no peer
 Subs panel).
- **No Chrome A11y tab.** Removed; a11y dogfooding is Story's domain.
- **No standalone Dynamic "Machines Canvas" tab.** Removed (rf2-ga16q);
 the spine-INDEPENDENT browse-all machine canvas lives under Static
 mode's Machines tab (Topology sub-mode). The Dynamic Machine tab is
 purely the event-driven lens.
- **No cross-epoch Dynamic L4 views.** Aggregate signals live on L2
 badges only.
- **No pattern-view.** Deferred.
- **No master-detail coupling.** Tabs are peers, bridged by app-db.
- **No simultaneous multi-frame display.** Single-frame focus (§021
 §1.6); switch focus via the L1 frame picker.
- **No legacy panels.** Subscriptions, Effects, Flows, Performance,
 Schemas, Hydration are NOT separate Dynamic tabs. Their content is
 surfaced through the Dynamic 6 above — and the registry catalogues live
 in Static mode:
 - Subscriptions → Views (cascade tree) + app-db (hover popover)
 - Effects → Epoch SIDE EFFECTS step (flat ledger) + Trace (raw `:rf.fx/*` ops)
 - Flows → Epoch FLOW step (one per flow) · Static → Flows (registry)
 - Performance → L2 row stripe colours + per-step duration in Epoch + per-row `:time` in Trace
 - Schemas → Epoch (violations attach inline to the owning step) + L2 pink-wash · Static → Schemas (registry)
 - Hydration → Epoch inline + the issues-ribbon signal

Stale file refs once cited here — `event_detail.cljs` and
`issues_ribbon.cljs` — are **deleted**; only
`issues_ribbon_helpers.cljc` survives (powering the ribbon signal +
L2 wash).

For the user-question → tab routing tables, see
[`SKILL.md` §The tabs — what each surfaces](../SKILL.md#the-tabs--what-each-surfaces).
