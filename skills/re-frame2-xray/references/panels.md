# panels — Dynamic lenses on one event + Static registry browse

Sources of truth: the live tab inventory is the set of
`panel-registry/reg-l4-tab!` calls under
`tools/xray/src/day8/re_frame2_xray/panels/` (Dynamic) and `.../static/`
(Static); the normative tab list is
[`018-Event-Spine.md` §5](../../../tools/xray/spec/018-Event-Spine.md)
+ [`021-Dynamic-Panel-Designs.md` §9.1](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md)
(Dynamic) + [`007-UX-IA.md` §Static mode](../../../tools/xray/spec/007-UX-IA.md)
(Static). Per-panel content design (layout, locked decisions, palette /
iconography / animation) lives in
[`021-Dynamic-Panel-Designs.md`](../../../tools/xray/spec/021-Dynamic-Panel-Designs.md);
chrome / palette tokens / density in
[`007-UX-IA.md`](../../../tools/xray/spec/007-UX-IA.md). Tab order is set
declaratively via `reg-l4-tab!` `:order`, rendered by `shell.cljs`
(Dynamic) / `static/shell.cljs` (Static). Live Dynamic `:order` values:
Epoch `-1` · app-db `1` · Views `2` · Trace `3` · Machine `4` · Routes `6`
· Resources `7` · Graph `8` · Modules `9` — nine in all (`:order 5`
unallocated). The authoritative inventory (`focus.cljc`'s `valid-panels`
def) + the build-time cross-check that guards it live in
[`../evals/README.md` §Keeping the tab inventory in sync](../evals/README.md).

## Two modes

The two-mode model (Dynamic event-spine 4-layer chrome · Static registry
3-layer chrome, flipped by the L1 mode pill or `Cmd/Ctrl+Shift+M`) is
covered in [`SKILL.md` §Two modes](../SKILL.md#two-modes). This leaf is
the per-panel tour: the 9 Dynamic tabs in §Panel-by-panel below, the 5
Static tabs in §Static mode — registry browse. One binding constraint to
restate: **no cross-epoch L4 panels** — every Dynamic L4 tab is a lens on
the one focused epoch; aggregate signal lives on L2 badges only (§021
§1.2 — binding). There is **no Issues tab** — issues surface inline (see
§Issues — not a Dynamic tab below).

### L2 timeline grammar

The L2 timeline above the panels carries:

- **Dispatch-origin prefix glyph** per row — the live classifier
 (`l2_timeline.cljc` `source->bucket`) renders a glyph for `:router` (R) ·
 `:http` (🌐) · `:ssr` (💧) · `:fx-emit` (⚡) · `:timer` (⏲) · `:test` (T) ·
 `:tool` (🔧) · `:machine-spawn` (i) · `:websocket`, and renders **no
 prefix** for app-code origins (`:ui`/`:unknown`/`:other`/`:repl`/
 `:frame-init` → silent, the common case). *(Impl note — pre-alpha drift:
 the ten-value `:user … :internal` list in §021 §1.5 is spec vocabulary the
 shipped classifier does not yet match; the live buckets above are
 authoritative.)*
- **Activity badge cluster** per row (live impl `l2_timeline.cljc`
 `activity-badge-glyphs`, render order issue → machine → HTTP → fx-emit →
 timer): `⚠` issue (error) · `◆` machine transition · `🌐` HTTP activity ·
 `⚡` fx-emit child dispatch · `⏲` timer-triggered. HCM remap is automatic
 (colour is never alone). *(Impl note — pre-alpha drift: §021 §17.1.5's
 row-badge table documents a richer set (adds `💧` SSR-hydration, `🌊`
 flow-recomputed); the live cluster above is authoritative.)*
- **Issue pink-wash** per row — a cascade carrying an issue
 washes its whole L2 row with the `:bg-issue-row` light-pink token. The
 wash is driven by `cascade-has-issue?`, which reuses the same
 `issue-event?` predicate as the issues-ribbon signal so the wash and the
 ribbon stay in lockstep by construction. Together with the Epoch
 cascade's per-step ✓/✗, this is the primary "which epochs are broken?"
 signal — there is no dedicated Issues tab.

Implementation lives at
[`panels/l2_timeline.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/l2_timeline.cljc)
+ [`panels/issues_ribbon_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/issues_ribbon_helpers.cljc).

### Scope rule — every L4 panel is focused-epoch-scoped

Every L4 panel answers "what happened in **this** epoch?" — through its
own lens. Cross-epoch signals belong on L2 badges, never inside L4.

### Inspection vs Rewind

Clicking an L2 row is **INSPECTION** (L4 panels rebind to that epoch's
captured snapshots; the live frame is NOT rolled back). Live rewind is the
separate, explicit **`Reset` button** on the L3 tab-bar ribbon. The full
mechanism — the whole frame-state (both app-db AND runtime-db) reinstalled
via `restore-epoch!` / `replace-frame-state!`, not the `:db-after`
projection alone — is the owning home in chrome.md §Time-travel
(`002-Time-Travel.md`).

## Panel-by-panel (Dynamic mode)

Nine Dynamic tabs, left-to-right by `:order` (mnemonics
`e a v t m r s g u`): **Epoch · app-db · Views · Trace · Machine · Routes ·
Resources · Graph · Modules.** First six are core spine lenses (§018 §5 +
§021 §9.1); **Resources** (`:order 7`), **Graph** (`:order 8`), **Modules**
(`:order 9`) are the cross-feature lenses, each self-registered through
`reg-l4-tab!` (`panels/resources.cljs`, `panels/derivation_graph.cljs`,
`panels/module_view.cljs`). Internal tab ids (`:epoch :app-db :views
:trace :machines :routing :resources :derivation-graph :module-view`) are
stable. There is no **Event** tab and no **Issues** tab — see §What's
deliberately NOT here.

Most Dynamic tabs share the same chrome: panel icon (left of stripe) ·
panel title · focused-event id · `[◀ Prev] [Next ▶]` film-strip walking
the L2 spine chronologically (per §021 §17.1.5; shared component at
[`panels/shared/film_strip/header.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/shared/film_strip/header.cljc)).
**Trace opts out of the film-strip header** — the L2 events
list already owns spine focus navigation.

### Epoch — `⚡` · stripe `:accent-violet` · mnem `e` · `:order -1`

Question: **What happened in this epoch?** — the full computational
timeline. Default landing view.
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
2. **RECORDABLE COEFFECTS** — **conditional**: the dispatch envelope's flat
 `:rf.cofx` map (`:rf/time-ms` + owner-qualified recordable leaves,
 privacy-summarized), filtered to the handler's **declared** recordable
 cofx ids via the panel's registry resolver (EP-0010 · EP-0017 §9).
 Omitted when the envelope carried no `:rf.cofx` map.
3. **COEFFECT** — **one numbered step per handler-declared coeffect** (the
 framework defaults `:db / :event / :frame / :source / :trace-id` are
 filtered at projection time, so the lens shows only declared leaves). A
 cofx supplier that threw while delivering its value gets a synthesised
 placeholder step so its exception card has a home.
4. **INTERCEPTORS** — **conditional**: the **authored** interceptor chain
 read from the registry (`handler-meta :event`, threaded in by the
 composite sub as `:resolve-event-interceptors`; EP-0022 §11), rendered
 whenever the event carries authored (non-`:rf/default?`) interceptor
 refs — clean or throwing. Rows carry resolved metadata
 (`:before?`/`:after?`/factory/doc/coord), per-dispatch override
 substitutions (the `:rf.interceptor/override-summary` tag off
 `:rf.event/run-start`), and missing-ref rows. The substrate emits no
 per-interceptor "ran" trace — the chain comes from the **registry**, not
 the trace stream.
5. **INTERCEPTOR** — **conditional, exception-only**: one row per
 **throwing** interceptor (id + phase chip + the shared Exception card),
 **phase-split** around the handler — a `:before` throw renders BEFORE the
 handler, an `:after` throw renders AFTER it. A clean chain shows nothing
 here (it renders under INTERCEPTORS above).
6. **EVENT HANDLER** — always present; body adapts to **what the handler
 returned**, not to a registrar flavour (there is one public
 `reg-event` and the registry kind is simply `:event` — no `:db`-vs-`:fx`
 sub-discriminator): a returned map carrying only `:db` → `:db` diff · a
 map carrying `:db` + `:fx` → `:db` diff + per-fx · a `reg-machine` event
 → the time-ordered machine cascade. Rendered
 as **SKIPPED** (⊘) when an upstream `:before`-chain throw — a coeffect
 supplier that threw while delivering, or a `:before` interceptor —
 aborted the cascade before the handler ran (NOT "ran, returned no :db").
7. **FLOW** — one numbered step per flow that fired (the t1→t2 reshape as
 the flow's own `:db` diff). Only when flows fired.
8. **EFFECT HANDLERS** — a flat per-effect ledger (see §EFFECT HANDLERS below).
 Only when a side effect occurred; equally SKIPPED when an upstream
 throw aborted the cascade.
9. **SUBSCRIPTIONS** — only when subs recomputed.
10. **VIEWS** — only when views re-rendered.

**Per-step status + inline exceptions.** Every step header carries a
status glyph off the shared `step-status` primitive — `✓` (`:ok`) / `✗`
(`:error`) / `⊘` (`:skipped`) (`panels/epoch/badge.cljc`). A
handler / interceptor / coeffect / fx / flow **exception** renders UNDER
the step where it occurred via the shared **"Exception Thrown"** card
— each exception attached to its owning step per
`exception-op->step`; the epoch's outcome reads `:error` whenever any step
settled `:error` (trace-derived, NOT the framework epoch-record `:outcome`
slot). Schema violations attach inline the same way.

**Badge taxonomy** (the binding inventory is `badge->token-key` /
`badge->label` in `badge.cljc` — the view never paints a badge outside this
map): DISPATCH (`:text-tertiary`) · RECORDABLE COEFFECTS
(`:text-secondary`) · COEFFECT (`:magenta`) · INTERCEPTORS (`:accent`) ·
INTERCEPTOR (`:accent`) · EVENT HANDLER (`:accent`) · FLOW (`:accent`) ·
EFFECT HANDLERS (`:orange`) · SUBSCRIPTIONS (`:magenta-pink`) · VIEWS
(`:success`) · SCHEMA HOT-RELOAD (`:warning`). (The view's colour resolver
bails to `:text-tertiary` on an unknown badge.)

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
headers**. Per `panels/epoch/projection.cljc`'s `side-effects-step`:

- **One row per effect**, leading with a per-effect status glyph: `✓` ran
 ok · `✗` threw / no-such-fx / `:db` schema-fail rollback · `↺` fx
 override applied · `–` (muted en-dash) skipped-on-platform or a dropped
 `other` effect (NEUTRAL — never trips the badge).
- **Single AND-of-rows badge** after the "EFFECT HANDLERS" label: TICK when
 every present row succeeded, CROSS when one or more FAILED; SKIPPED rows
 are neutral (`side-effects-badge-status`).
- **The `:db` row** leads the ledger when a `:db` commit was attempted
 (incl. a handler that returned only `:db`); its args slot is the
 clickable **"→ app-db"** destination marker (the actual diff lives in
 the app-db panel — no duplication). Absent when the handler returned
 only `:fx` / nothing / threw (no phantom `:db`).
- **fx exceptions** attach to the owning `:fx-id` row via the shared
 Exception card; `:db` schema-fail rollback paints the `:db` row ✗ with
 the reason box, and a rollback means `:fx` never walked (Spec 002
 atomicity) — so the ledger carries only the red `:db` row.

### app-db — `◐` · stripe `:cyan` · mnem `a`

Question: **What does state LOOK LIKE — and what just changed?**

Sectioned-by-reserved-area layout (§021 §4.2). The complete
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

The display label is **Views** (set at `reactive_panel.cljs:75`). **The L3
tab key is `:views`** — an internal id, not a user contract.

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
**single flat oldest-first row list**, click-to-expand per row. The row
anatomy (the six columns, the per-row stage column + colour-coded left edge
that recover the phase shape, the inline error/warning treatment) is the
normative subject of **§023 §3 + §3a** — the one detail Xray honours by
construction is that the stage label + edge colour resolve through the
Epoch panel's OWN badge taxonomy, so the Trace stage column matches the
Epoch numbered cascade (one step model). Cite §023 for the column /
stage / severity detail rather than re-encoding it here.

Two USAGE facts the operator needs to route correctly:

- **No filtering UI** — the focused epoch IS the scope, so the only
 drill-down is per-row click, which opens the **edn-inspector** on the
 row's raw trace-event map inline. (Spec 009's `trace-buffer` filter
 vocabulary — `:op-type`, `:dispatch-id`, the tag axes — is real for the
 *programmatic* API but is **not** surfaced as Trace-panel UI.)
- **No film-strip header** — the L2 events list owns spine focus
 navigation (this tab opts out, see §Panel-by-panel above).

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
machine.

Topology-plus-overlay (§021 §6 + §17.4). Each machine renders as an
xyflow canvas through the shared machines-viz **MachineChart**
(`day8.re-frame2-machines-viz.chart`), mounted via the Xray-side
[`panels/machine_canvas.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/machine_canvas.cljs)
wrapper (per §021 §6.0) — not Stately Inspect, not native Reagent. Nodes,
edges, current-state pulse, parallel-region containers, and final-state
double-rings all come from that chart, which carries its own styling.
[`panels/machines/xyflow_style.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/machines/xyflow_style.cljs)
(+ `machines/topology.cljs`) is the self-contained, JVM-portable,
unit-tested fallback projector / style catalogue — **not** on the live
render path.

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
slots excluded), carrying `:tags` + the structured before→after object
(§021 §6).

Per-canvas footer lists guards / actions / cancellation cascade chips
inline (no modal, no popout). When the focused event had **no machine
activity** the panel is **truly blank** — a single calm placeholder line,
**no per-machine topology** (no topology renders on a non-machine epoch).
*(Impl note — pre-alpha drift: the live panel gates topology on machine
activity; §021 §6.2 Case B's "topology always visible on a no-activity
epoch" is not the live behaviour. Browse-all topology lives in Static mode
— see the callout below.)*

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
> it lives under Static mode's Machines tab (Topology sub-mode). There is no
> standalone Dynamic "Machines Canvas" tab. Impl
> [`static/machines/topology.cljs`](../../../tools/xray/src/day8/re_frame2_xray/static/machines/topology.cljs).

### Routes — `🌐` · stripe `:yellow` · mnem `r`

Question: **What did this event do to my routes?** (Display label
**Routes**, plural-noun convention; internal tab id `:routing`.)

Same topology-plus-overlay pattern as Machines, rendered as a textual
tree with `├─ └─` box-drawing (per §021 §7.1).

Two blocks:

- **Active route tree** (always visible) — each node with one of three
 markers per current state and per-epoch activity:
 - `◉` active this epoch, on the resolved match (the `:to` destination)
 - `◇` registered, traversed (can-leave / can-enter guard phases) this epoch (the `:from` origin)
 - `◀ current` on the current matched route with no activity this epoch
   (folded into the mode-accent row highlight, not a painted dot glyph)
- **This epoch** — short dense block: `Phase`, `From`, `To`, `Match`,
 `Events`. Empty state ("No route activity in this epoch.") keeps
 the tree visible above.

Reads the focused cascade's routing trace ops (correlated by
`:rf.trace/dispatch-id`) — the live phase derivation keys off
`:rf.route.nav-token/allocated` (a navigation landed → `:on-match`),
`:rf.route/navigation-blocked`, and `:rf.route/fragment-changed`, and the
active-tree traversal markers read `:rf.route/deactivated` +
the navigate / `:rf.route/transitioned` ops (`panels/routing_helpers.cljc`).
*(Impl note — pre-alpha drift: §021 §7's `:rf.route/can-leave` /
`:can-enter` / `:on-match` op names are spec naming; the live panel
correlates the ops listed above.)*

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
(Spec 016 §Xray and AI tooling). The sections, top → bottom, name the
read; the per-section anatomy + the EP-0016 trace-op vocabulary they
project (`:rf.resource/scope-resolved`, `:rf.mutation/replied`, the
`:invalidation` facet, `:refetch-populated?`, `:resolved-nil?`, …) is the
normative subject of **§024** + spec/016 — cite those rather than
re-encoding the field-by-field detail here:

- **STATIC RESOURCE REGISTRY** — every registered resource + scope,
 stale-after, GC-after, and the routes that activate it.
- **LIVE INSTANCES** (per frame) — each scoped cache entry with state,
 generation, owner count, and freshness.
- **WORK LEDGER** — live fetch attempts (running · cancellable ·
 deadline); host handles are inaccessible.
- **ROUTE / RESOURCE GRAPH** — blocking activations (the SSR wait
 points), the lifecycle timeline, and cache growth.
- **SCOPE RESOLUTION TIMELINE** (EP-0016 D3) — which named
 `reg-resource-scope` resolver ran, its inputs, and the resolved scope —
 including the fail-closed nil evidence (a scope-requiring site that got
 nil and produced NO global fallback).
- **MUTATION CONTINUATIONS + SCOPED INVALIDATION** (EP-0016 D1/D2) — the
 surface that makes the doctrine "`:reply-to` is for workflow;
 populate/patch/invalidate are for cache" visible: `:reply-to`
 continuation evidence (did the accepted reply continue into app
 workflow?) and descriptor-level invalidation evidence (which scopes a
 write resolved, refetched, or left stale — fail-closed, never an
 implicit global blast).
- **SCOPE AUDIT** — every `:rf.scope/global` use + lints.

**The absence-is-evidence rule.** A continuation / refetch that the
runtime *suppresses* (a stale or superseded reply, an `ensure` that skips
a fresh read) surfaces as its own suppression op (`:rf.mutation/stale-suppressed`,
`:rf.resource/cache-hit` / `:rf.resource/stale-suppressed`), not as a
missing row — so "my `:reply-to` didn't fire" / "my read didn't refetch"
is answered by the *presence of the suppression op*. (The op names + the
full settlement facet live in §024.)

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
resource stale?", "what owns this cache entry?", "did my mutation's
`:reply-to` continuation fire?", "which scopes did this write invalidate?",
"why didn't this read refetch?"

Spec: [`spec/016-Resources.md` §Xray and AI tooling](../../../spec/016-Resources.md)
+ [`024-Resources-Panel.md`](../../../tools/xray/spec/024-Resources-Panel.md);
implementation at
[`panels/resources.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/resources.cljs)
+ [`panels/resources_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/resources_helpers.cljc).

### Graph — cross-feature · mnem `g` · `:order 8`

<a id="graph"></a>

Question: **Where does this value come from — when is it evaluated, where
does it live, who owns it?** — across families, in one place. (Display
label **Graph**; internal tab id `:derivation-graph`.) Xray's UI over the
**EP-0014 derivation/process algebra graph** and the **named first
consumer** of EP-0014's structured graph accessor. Every declared fact
and process in the host app — across **all five contributor families**
(subscriptions, flows, resources, route facts, machine processes +
selectors) — is a node in **one** node-and-edge graph over the frame
fold. (A family with no registrations in the host app contributes no
nodes — the *per-app* no-machines / no-resources story, not a per-tool
boundary.)

The classification model (the two closed superkinds `:derivation` /
`:process` read off `:kind` alone, the refined kinds as the colour axis),
the per-panel **Declared ↔ Realized** *projection toggle* (a Graph-local
control, NOT the L1 Dynamic/Static **mode** pill — Graph is always a Dynamic
tab; the Declared-side **don't-execute rule**), and the off-box egress
projection are the normative subject of **spec/Derivations.md + EP-0014 +
§025** — cite those for the rule detail rather than re-encoding it here. (The
internal data contract / shipped UI keeps `:mode :static | :live`; that stays
implementation language — this skill calls the user-facing control a
*Declared/Realized projection toggle* so it never collides with the L1
`Static`/`Dynamic` mode words.)

Two caveats this skill owns (this is their full owning home; SKILL.md
carries the short forms):

> **Authority is an axis, not a storage class.** Remote-backed nodes
> (resources) carry an **authority** chip. A resource's *storage* class is
> still **local** (the frame's runtime-db, like any runtime-managed value);
> *remote* describes its **authority** — where the value is sourced/owned
> upstream — a distinct axis from where it is stored. Read the chip as
> "locally stored, locally read, upstream source of truth", never as
> app-db/runtime-db placement.

> **The graph accessor is internal, not a public API.** The Graph tab
> *consumes* EP-0014's internal `re-frame.derivation.graph` composer — a
> **structured** internal accessor, **not** a `re-frame.core` facade
> export and **not** a public app authoring/accessor primitive, with no
> public accessor name. Route users to **open the Graph tab**;
> do **not** tell them to call a public graph API from app code.

**Read-only** — observing the graph pins nothing, dispatches nothing,
mutates no host state; on-box raw, off-box redacted.

**Open when:** "where does this value come from?", "show me the whole
derivation graph", "what's the dependency graph for this page?", "is this
ephemeral or materialized, and who owns it?"

Spec: [`spec/Derivations.md` §Graph inspection — internal but structured](../../../spec/Derivations.md)
+ [`docs/EP/EP-0014-derivation-and-process-algebra.md`](../../../docs/EP/EP-0014-derivation-and-process-algebra.md);
implementation at
[`panels/derivation_graph.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/derivation_graph.cljs)
+ [`panels/derivation_graph_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/derivation_graph_helpers.cljc).

### Modules — cross-feature · mnem `u` · `:order 9`

Question: **Which images load which frames, and how do those frames
resolve their registrations?** (Rendered tab-bar label **Frames** — that
is the literal `:label` `reg-l4-tab!` registers, per `module_view.cljs` +
spec/026 §5 Tab registration; this skill and the spec call it the
**Modules** / module-view tab conceptually; internal tab id `:module-view`.) Xray's UI
over the EP-0023 **`image -> frame -> event
stream`** public model — the structural counterpart to the Graph tab (Graph
is the per-fact derivation/process view; Modules is the per-frame
installation + image-provenance view). Registered at `:order 9`, after the
Graph tab (`:order 8`), keeping the cross-feature runtime-structure tabs
adjacent.

The panel is the **FRAMES** view (§026 §8) — each live frame as an
**execution context** carrying its **resolved image** (the generation's
`[kind id]` descriptor set), its capabilities, and how it resolves
`(kind id)` lookups through that image. This is the model a consumer app
developer reasons in: image assembly plus frame isolation are the whole
composition story, with no realm / app / module layer to browse.

The projection runs through the pure
`image-view-helpers/project-image-view` over the live-frame reads
(`re-frame.live-frame/image-view-frames`, via the `image_view_reads` seam —
each frame carrying its resolved image generation).

> **`:module-view` is an L4-only registry tab — no standalone mount
> facade.** Like the Graph tab, Modules registers through `reg-l4-tab!`
> but exposes **no** `mount-*!` facade (it is **not** in `panel-enum`,
> which carries only the standalone-mountable surface) — it is a
> shell-internal tab, focusable via `focus!` / the command palette but not
> independently mountable the way the other seven Dynamic panels are.
> Route users to **open the Modules tab**; do not tell them to call a
> `mount-module-view!` — there isn't one.

**Does not compose off an `:rf.xray/*` app-db slot.** Images and frames
are process-global facts (they live in the framework's registries, not
Xray's app-db); the sub reads them directly at recompute time, and a tab
activation re-renders the panel which re-derefs (a browse-on-open shape
matching the other Static-style surfaces).

**Read-only** — enumerating frames and images pins nothing and dispatches
nothing.

**Open when:** "what frames exist and which image loaded each?", "how does
this frame resolve its registrations?" — the public partitioning axis is
`image -> frame`.

Spec: [`026-Module-View-Panel.md` §8](../../../tools/xray/spec/026-Module-View-Panel.md);
implementation at
[`panels/module_view.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/module_view.cljs)
+ [`panels/image_view_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/image_view_helpers.cljc)
+ [`panels/image_view_reads.cljs`](../../../tools/xray/src/day8/re_frame2_xray/panels/image_view_reads.cljs).

### Issues — not a Dynamic tab

There is **no dedicated Issues tab** and no session-wide triage list.
"What's wrong in this epoch?" is answered
inline, through **three** always-on channels (the `.cljc` algebra lives in
[`panels/issues_ribbon_helpers.cljc`](../../../tools/xray/src/day8/re_frame2_xray/panels/issues_ribbon_helpers.cljc)):

1. **Inline in the Epoch cascade** — per-step ✓ / ✗ status glyphs, the
 shared **"Exception Thrown"** card under the throwing step
 (handler / interceptor / coeffect / fx / flow exceptions), and the
 `:db` schema-fail rollback ✗ on the EFFECT HANDLERS `:db` row.
 Errors, warnings, schema violations, and hydration mismatches each
 surface against the step where they occurred.
2. **L2 event-row pink-wash** — a cascade carrying an issue
 washes its L2 timeline row pink; the `cascade-has-issue?` predicate
 reuses `issue-event?` so the wash stays in lockstep with the ribbon.
3. **The always-on `:rf.xray/issues-ribbon` signal** — the composite
 (registered in `registry.cljs`) drives the auto-open-on-error watcher
 (`settings/effects.cljs/install-auto-open-watcher!`) — the cross-epoch
 "something is wrong" signal.

So route "anything broken in this epoch?" to the **Epoch tab**; "which
epochs are broken?" to the **L2 pink-wash**.

> **Accessibility note.** A11y dogfooding is **not** a Xray tab. A11y
> scanning lives in Story
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
pick the frame whether you're in Dynamic or Static (the picker's full
contract — single-frame collapse, tool-frames-hidden-by-default, the
transient-not-persisted pin — lives in
[`chrome.md` §L1 frame picker](chrome.md#l1-frame-picker)). The mode choice
lives at `[:rf.xray/mode]` (`:dynamic | :static`, persisted to
localStorage); the Static-scoped tab choice lives at
`[:rf.xray.static/selected-tab]` (default `:machines`), independent of
Dynamic's `[:rf.xray/selected-tab]` so flipping modes preserves both.
(Note the asymmetry: mode and tab choices persist across reload, but the
**frame pin does not** — it resets to the head-frame default each session.)

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

- **No Issues tab** and no session-wide aggregate / triage list. The three
 inline channels that carry issues are in §Issues — not a Dynamic tab
 above.
- **No Event tab.** The **Epoch** panel (numbered
 cascade, `:order -1`) is the canonical "what happened" surface.
- **No peer Subscriptions L4 tab.** The reactive cascade surfaces inline
 in Views + the app-db hover popover, not as its own Dynamic tab. (The
 Dynamic set is open through the `reg-l4-tab!` seam — Resources, Graph,
 and Modules each register their own cross-feature tab — but there is
 no separate Subs lens.)
- **No Chrome A11y tab.** A11y dogfooding is Story's domain.
- **No standalone Dynamic "Machines Canvas" tab** — the spine-INDEPENDENT
 browse-all canvas lives under Static mode's Machines tab (see the
 §Machine Browse-all callout).
- **No cross-epoch Dynamic L4 views.** Aggregate signals live on L2
 badges only.
- **No pattern-view.**
- **No master-detail coupling.** Tabs are peers, bridged by app-db.
- **No simultaneous multi-frame display.** Single-frame focus (§021
 §1.6); switch focus via the L1 frame picker.
- **No legacy panels.** Subscriptions, Effects, Flows, Performance,
 Schemas, Hydration are NOT separate Dynamic tabs. Their content is
 surfaced through the Dynamic tabs above — and the registry catalogues
 live in Static mode:
 - Subscriptions → Views (cascade tree) + app-db (hover popover)
 - Effects → Epoch EFFECT HANDLERS step (flat ledger) + Trace (raw `:rf.fx/*` ops)
 - Flows → Epoch FLOW step (one per flow) · Static → Flows (registry)
 - Performance → L2 row stripe colours + per-step duration in Epoch + per-row `:time` in Trace
 - Schemas → Epoch (violations attach inline to the owning step) + L2 pink-wash · Static → Schemas (registry)
 - Hydration → Epoch inline + the issues-ribbon signal

The only issues-related source file is `issues_ribbon_helpers.cljc`
(powering the ribbon signal + L2 wash); there is no `event_detail.cljs` or
`issues_ribbon.cljs`.

For the user-question → tab routing tables, see
[`SKILL.md` §The tabs — what each surfaces](../SKILL.md#the-tabs--what-each-surfaces).
