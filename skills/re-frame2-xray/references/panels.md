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
Views `2` · Trace `3` · Machine `4` · Routes `6` · Resources `7` ·
Graph `8` — eight Dynamic tabs in all (the core six plus the two
cross-feature lenses, **Resources** and **Graph**, each registered by its
own panel through the `reg-l4-tab!` seam).

## Two modes

The two-mode model (Dynamic event-spine 4-layer chrome · Static registry
3-layer chrome, flipped by the L1 mode pill or `Cmd/Ctrl+Shift+M`) is
covered in [`SKILL.md` §Two modes](../SKILL.md#two-modes). This leaf is
the per-panel tour: the 8 Dynamic tabs in §Panel-by-panel below, the 5
Static tabs in §Static mode — registry browse. One binding constraint to
restate: **no cross-epoch L4 panels** — every Dynamic L4 tab is a lens on
the one focused epoch; aggregate signal lives on L2 badges only (§021
§1.2 — binding). There is **no Issues tab** — issues surface inline (the
Epoch cascade's per-step ✓/✗ + the shared Exception card), via the L2
pink-wash, and via the always-on `:rf.xray/issues-ribbon` signal
(see §What's deliberately NOT here).

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
- **Issue pink-wash** per row — a cascade carrying an issue
 washes its whole L2 row with the `:bg-issue-row` light-pink token. The
 wash is driven by `cascade-has-issue?`, which reuses the same
 `issue-event?` predicate as the issues-ribbon signal so the wash and the
 ribbon stay in lockstep by construction. This is now (with the Epoch
 cascade's per-step ✓/✗) the primary "which epochs are broken?" signal —
 the dedicated Issues tab is gone.

Implementation lives at
[`panels/l2_timeline.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/l2_timeline.cljc)
+ [`panels/issues_ribbon_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/issues_ribbon_helpers.cljc).

### Scope rule — every L4 panel is focused-epoch-scoped

Every L4 panel answers "what happened in **this** epoch?" — through its
own lens. Cross-epoch signals belong on L2 badges, never inside L4.

### Inspection vs Rewind

Clicking an L2 row is **INSPECTION** — L4 panels rebind to that epoch's
captured snapshots; app-db is NOT rolled back. Rewind is a separate,
explicit affordance — the **`Reset` button** on the far-right of the L3
tab-bar ribbon, which rewinds the observed frame's live
`app-db` to the focused epoch's `:db-after` (`002-Time-Travel.md`).

## Panel-by-panel (Dynamic mode)

Eight Dynamic tabs, left-to-right by `:order` (mnemonics
`e a v t m r s g`): **Epoch · app-db · Views · Trace · Machine · Routes ·
Resources · Graph.** The first six are the core spine lenses (§018 §5 +
§021 §9.1); **Resources** (`:order 7`) and **Graph** (`:order 8`) are the
two cross-feature lenses that landed last, each self-registered through
the `reg-l4-tab!` seam (`panels/resources.cljs`,
`panels/derivation_graph.cljs`). (Internal tab ids stay `:epoch :app-db
:views :trace :machines :routing :resources :derivation-graph` — the
display labels rebased over a rename history but the ids are stable.) The
pre-rebuild **Event** panel was retired (2026-05-27 —
`panels/event_detail.cljs` is deleted) and the **Issues** tab was retired
(2026-05-31 — `panels/issues_ribbon.cljs` is deleted); see §What's
deliberately NOT here.

Most Dynamic tabs share the same chrome: panel icon (left of stripe) ·
panel title · focused-event id · `[◀ Prev] [Next ▶]` film-strip walking
the L2 spine chronologically (per §021 §17.1.5; shared component at
[`panels/shared/film_strip/header.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/film_strip/header.cljc)).
**Trace opts out of the film-strip header** — the L2 events
list already owns spine focus navigation.

### Epoch — `⚡` · stripe `:accent-violet` · mnem `e` · `:order -1`

Question: **What happened in this epoch?** — the full computational
timeline. Default landing view; supersedes the retired Event panel.
Registered at `:order -1` so it claims the leftmost /
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
 cascade position).
4. **EVENT HANDLER** — always present; body adapts to the handler flavour
 (`:reg-event-db` → `:db` diff · `:reg-event-fx` → `:db` + per-fx ·
 `:reg-machine` → the time-ordered machine cascade). Rendered
 as **SKIPPED** (⊘) when an upstream `:before`-chain throw — a coeffect
 injector or a `:before` interceptor — aborted the cascade before the
 handler ran (NOT "ran, returned no :db").
5. **FLOW** — one numbered step per flow that fired (the t1→t2 reshape as
 the flow's own `:db` diff). Only when flows fired.
6. **EFFECT HANDLERS** — a flat per-effect ledger (see §EFFECT HANDLERS below).
 Only when a side effect occurred; equally SKIPPED when an upstream
 throw aborted the cascade.
7. **SUBSCRIPTIONS** — only when subs recomputed.
8. **VIEWS** — only when views re-rendered.

**Per-step status + inline exceptions.** Every step header carries a
status glyph off the shared `step-status` primitive — `✓` (`:ok`) / `✗`
(`:error`) / `⊘` (`:skipped`) (`panels/epoch/badge.cljc`). A
handler / interceptor / coeffect / fx / flow **exception** renders UNDER
the step where it occurred via the shared **"Exception Thrown"** card
— each exception attached to its owning step per
`exception-op->step`; the epoch's outcome reads `:error` whenever any step
settled `:error` (trace-derived, NOT the framework epoch-record `:outcome`
slot). Schema violations attach inline the same way.

**Badge taxonomy** (the inventory `badge-set` enforces; `badge.cljc`):
DISPATCH (`:text-tertiary`) · COEFFECT (`:magenta`) · INTERCEPTOR
(`:accent`) · EVENT HANDLER (`:accent`) · FLOW (`:accent`) · EFFECT HANDLERS
(`:orange`) · SUBSCRIPTIONS (`:magenta-pink`) · VIEWS (`:success`). (The
view's colour resolver bails to `:text-tertiary` on an unknown badge.)

**Open when:** "what did this event do?", "where did the cascade fail?",
"what fx fired?", "did the flow recompute?"

Spec: [`021-Dynamic-Panel-Designs.md` §9.1](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md);
implementation at
[`panels/epoch_panel.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/epoch_panel.cljs)
+ [`panels/epoch/`](../../../tools/xray/src/day8/re_frame2_xray/panels/epoch)
(`view.cljs` · `projection.cljc` · `badge.cljc`).

### EFFECT HANDLERS step — flat per-effect ledger

The Epoch cascade's EFFECT HANDLERS step renders as **one flat ledger** —
one row per effect, down the page, in execution order, with **no group
headers** (supersedes the old 3-tier / "EFFECTS RETURNED + EFFECTS
APPLIED" split). Per `panels/epoch/projection.cljc`'s `side-effects-step`:

- **One row per effect**, leading with a per-effect status glyph: `✓` ran
 ok · `✗` threw / no-such-fx / `:db` schema-fail rollback · `↺` fx
 override applied · `–` (muted en-dash) skipped-on-platform or a dropped
 `other` effect (NEUTRAL — never trips the badge).
- **Single AND-of-rows badge** after the "EFFECT HANDLERS" label: TICK when
 every present row succeeded, CROSS when one or more FAILED; SKIPPED rows
 are neutral (`side-effects-badge-status`).
- **The `:db` row** leads the ledger when a `:db` commit was attempted
 (incl. a plain reg-event-db returning only `:db`); its args slot is the
 clickable **"→ app-db"** destination marker (the actual diff lives in
 the app-db panel — no duplication). Absent when the handler returned
 only `:fx` / nothing / threw (no phantom `:db`).
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
omitted from the model so the operator isn't shown a
clutter of "no X here" placeholder cards. The APP STATE top section
always renders even when the user-domain db is empty — it's the
panel's anchor.

**Underlying paths.** Framework durable state lives in the **runtime-db**
partition (`:rf.db/runtime`, children under `:rf.runtime/*`) — a separate
partition from the user app-db; the operator-facing labels (`:rf/machines`,
`:rf/route`, …) map to nested runtime-db sub-paths via the `runtime-areas`
lookup in
[`panels/app_db_diff_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/app_db_diff_helpers.cljc)
— e.g. `:rf/machines → [:rf.runtime/machines :snapshots]`,
`:rf/route → [:rf.runtime/routing :current]`. Spec source:
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
 a **render-cause chip** on every re-render leaf
 (`panels/reactive_panel_view.cljs` `view-node` + the pure
 `panels/epoch/projection.cljc` `render-cause`): `← :sub-id` when a
 subscription the view derefs changed value (`:triggered-by`), or
 `← props` when none of the view's own subs changed (so the cause is the
 orthogonal `:rf/props` channel — a prop changed / parent re-rendered;
 the parent is never named). A first **mount** carries no
 cause (the `(mounted)` label conveys it).

Flows are NOT in the reactive cascade — they're handling-side (the Epoch
FLOW step) per §021 §3.2. The cascade nodes are exactly: db-paths (seed)
→ subs (intermediate) → views (leaf).

"Show unchanged subs" toggle defaults OFF (§021 §3.4 + §11.4).
Per-cascade clicks propagate cross-panel: a sub row → app-db at that
input path. Hovering a view node toggles a pink DOM highlight on the live
element.

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
envelope is GONE (#2545, because it was hard to scan). Each row
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

**No filtering UI**: the focused epoch IS the scope, so the
panel-local chip filters (and clear-filters) are gone — the only
drill-down is per-row click, which opens the **edn-inspector** on the
row's raw trace-event map inline. **No film-strip header** —
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

The focused-epoch **transition row** is a single prominent row: a header
verb `<before-state → after-state>` (larger / bolder / magenta, doubling
as click-to-source) over a **logical-state DELTA box** — an
`edn-inspector` before→after DIFF of the machine's `{:state :tags}` only
(`:data` excluded — the per-action `↳ data Δ` carries it; `:rf/*` snapshot
slots excluded). This reverses the older transition-map "delight shape":
the delta box earns its place by carrying `:tags` + the structured
before→after object (§021 §6).

Per-canvas footer lists guards / actions / cancellation cascade chips
inline (no modal, no popout). When the focused event had **no machine
activity** the panel is **truly blank** — a single calm placeholder line,
**no per-machine topology** (agreeing with this leaf's earlier BLANK
statement and the `machine_inspector` blank-state tests, which assert no
topology renders on a non-machine epoch). To browse a machine's topology
cold — without picking a machine-active event — flip to **Static mode**'s
Machines tab (the spine-INDEPENDENT canvas browser). (The "topology
always visible on a no-activity epoch" treatment in §021 §6.2 Case B is a
documented future tightening, **not** the live behaviour — the live panel
gates topology on machine activity.)

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
> it lives under Static mode's Machines tab (Topology sub-mode); the
> standalone Dynamic "Machines Canvas" tab was removed. Impl
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

### Resources — cross-feature · mnem `s` · `:order 7`

Question: **Where is my server state — what owns it, and is it stale?**
(Display label **Resources**, plural-noun convention; internal tab id
`:resources`.) The Xray surface for re-frame2's declarative server-state
(Spec 016 §Xray and AI tooling). Sections, top → bottom:

- **STATIC RESOURCE REGISTRY** — every registered resource + scope,
 stale-after, GC-after, and the routes that activate it.
- **LIVE INSTANCES** (per frame) — each scoped cache entry with state,
 generation, owner count, and freshness; scope/params summarized.
- **WORK LEDGER** — live fetch attempts (running · cancellable ·
 deadline); host handles are inaccessible by design.
- **ROUTE / RESOURCE GRAPH** — blocking activations are the SSR wait
 points; plus lifecycle timeline, invalidation graph, cache growth.
- **SCOPE AUDIT** — every `:rf.scope/global` use + lints.

**Read-only** — opening this panel pins nothing: it dispatches no
`:rf.resource/ensure`, attaches no owner, refetches nothing, extends no
GC (Spec 016 §Active owners and causes). **Privacy**: params, scopes, AND
data all get the same summarize-and-redact treatment — every value is a
bounded, redaction-aware preview, never the raw value. **Decoupled**:
Xray does **not** `:require` the optional `re-frame.resources.*` artefact;
the panel reads the static registry via `(rf/registrations :resource)`
and the live cache/ledger from the runtime-db slice the spine already
publishes — so it renders cleanly even when the host wired no resources
(identical posture to the Routes tab's route slice + the Machine tab's
snapshots).

**Open when:** "where's my server state?", "what's in flight?", "is this
resource stale?", "what owns this cache entry?"

Spec: [`spec/016-Resources.md` §Xray and AI tooling](../../../spec/016-Resources.md);
implementation at
[`panels/resources.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/resources.cljs)
+ [`panels/resources_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/resources_helpers.cljc).

### Graph — cross-feature · mnem `g` · `:order 8`

Question: **Where does this value come from — when is it evaluated, where
does it live, who owns it?** — across families, in one place. (Display
label **Graph**; internal tab id `:derivation-graph`.) Xray's UI over the
**EP-0014 derivation/process algebra graph** and the **named first
consumer** of EP-0014's structured graph accessor. Every declared fact
and process in the host app — subscriptions, flows, resources, route
facts, machine processes + selectors — is a node in **one**
node-and-edge graph over the frame fold.

- **Classification by the two closed superkinds.** Each node is grouped +
 classified by its `:kind` — exactly `:derivation` or `:process` — read
 off `:kind` alone (a tool MUST classify knowing only the two
 superkinds). The refined kinds (`:resource-process`, `:route-fact`,
 `:machine-process`, `:machine-selector`) are the **colour** axis (family
 accent), never the contract; an unknown future refinement still renders
 and classifies off its superkind.
- **Per-panel static ↔ live toggle** (its OWN toggle, in the panel
 header — distinct from the L1 Dynamic/Static mode pill). **Static**: the
 registration-derived graph for the picked frame's **realm** (the
 registrar is realm-owned, EP-0013 — in a single-realm app that is the
 default realm, so it reads as "what's registered"; a sibling realm has
 its own); a parametric sub shows the `:parametric` marker and contributes
 **no** edge — the **don't-execute rule** (static inspection never invokes
 param/scope functions).
 **Live**: the graph realized in the observed frame — concrete
 subscription query vectors with realized edges, active resource keys,
 live machine instances, the materialized route slice with its nav-token
 owner.
- **Contributor coverage — all five families.** The panel composes the
 five EP-0014 contributor families — **subscriptions, flows, routes,
 resources, and machines** (machine processes *and* selectors, with
 precise machine→selector edges). Xray statically `:require`s the flows,
 routing, resources, and machines tooling siblings (subs live in core), so
 every family feeds the one graph. A family with **no registrations in the
 host app** contributes no nodes — but that is the *per-app* no-machines /
 no-resources story (the host registered none), not a *per-tool*
 dependency boundary.
- **Authority is an axis, not a storage class.** Each node carries its
 storage / evaluation / lifecycle (owner) classifications; remote-backed
 nodes (resources) additionally carry an **authority** chip. A resource's
 storage class is still **local** (the frame's runtime-db, like any
 runtime-managed value); *remote* describes its **authority** — where the
 value is sourced/owned upstream — a distinct axis from where it is
 stored. Read the chip as "locally stored, locally read, upstream source
 of truth", never as app-db/runtime-db placement. (The EP-0014 ruled
 split: a remote fact has a local storage class; "remote" is its
 authority.)
- **On-box raw, off-box redacted.** On-box rendering shows raw value
 summaries (the developer is entitled to their own app's values; previews
 are bounded for ergonomics, not privacy). The off-box egress boundary —
 shipping the graph to a remote agent or a serialized capture — projects
 each node's value-bearing fields through the frame's wire-elision policy
 (per-frame, fail-closed), preserving node + edge structure.

**Read-only** — observing the graph pins nothing, dispatches nothing,
mutates no host state.

> **The graph accessor is internal, not a public API.** The Graph tab
> *consumes* EP-0014's internal `re-frame.derivation.graph` composer — a
> **structured** internal accessor, **not** a `re-frame.core` facade
> export and **not** a public app authoring/accessor primitive. EP-0014
> defers the public name until a third consumer (beyond Xray + the
> conformance fixtures) needs it. Route users to **open the Graph tab**;
> do **not** tell them to call a public graph API from app code.

**Open when:** "where does this value come from?", "show me the whole
derivation graph", "what's the dependency graph for this page?", "is this
ephemeral or materialized, and who owns it?"

Spec: [`spec/Derivations.md` §Graph inspection — internal but structured](../../../spec/Derivations.md)
+ [`docs/EP/EP-0014-derivation-and-process-algebra.md`](../../../docs/EP/EP-0014-derivation-and-process-algebra.md);
implementation at
[`panels/derivation_graph.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/derivation_graph.cljs)
+ [`panels/derivation_graph_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/derivation_graph_helpers.cljc).

### Issues — no longer a Dynamic tab

There is **no dedicated Issues tab**. Mike ruled it out (#2540,
Option (c), 2026-05-31): the standalone tab + its aggregate panel
(`panels/issues_ribbon.cljs`) were deleted and the session-wide triage
list was consciously dropped. "What's wrong in this epoch?" is answered
inline, through **three** always-on channels (per the surviving `.cljc`
algebra
[`panels/issues_ribbon_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/issues_ribbon_helpers.cljc)):

1. **Inline in the Epoch cascade** — per-step ✓ / ✗ status glyphs, the
 shared **"Exception Thrown"** card under the throwing step
 (handler / interceptor / coeffect / fx / flow exceptions), and the
 `:db` schema-fail rollback ✗ on the EFFECT HANDLERS `:db` row. The
 errors + warnings + schema violations + hydration mismatches that the
 old tab unified now surface against the step where they occurred.
2. **L2 event-row pink-wash** — a cascade carrying an issue
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

The three components every L4 panel reuses (`edn-inspector/render-node`,
`film_strip/header`, `focus_resolver`) and the full tab-icon / L2-badge /
cross-panel-arrow glyph reference live in
[`shared-components.md`](shared-components.md).

## What's deliberately NOT here

Per §021 §15 (Dynamic mode) + §007 §Static mode:

- **No Issues tab.** Removed (#2540, Mike Option (c),
 2026-05-31 — `panels/issues_ribbon.cljs` deleted); the session-wide
 aggregate / triage list was consciously dropped. The three inline
 channels issues surface through instead are detailed in §Issues — no
 longer a Dynamic tab above.
- **No Event tab.** Retired (2026-05-27 —
 `panels/event_detail.cljs` deleted). The **Epoch** panel (numbered
 cascade, `:order -1`) is the canonical "what happened" surface.
- **No extra Dynamic L4 lens.** The 6-tab Dynamic set is the contract;
 sub-layer surfaces inline in Views + the app-db hover popover (no peer
 Subs panel).
- **No Chrome A11y tab.** Removed; a11y dogfooding is Story's domain.
- **No standalone Dynamic "Machines Canvas" tab.** Removed;
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
 - Effects → Epoch EFFECT HANDLERS step (flat ledger) + Trace (raw `:rf.fx/*` ops)
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
