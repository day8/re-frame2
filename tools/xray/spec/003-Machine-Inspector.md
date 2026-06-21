# 003-Machine-Inspector

> **See also**: [`021-Dynamic-Panel-Designs.md` §6](021-Dynamic-Panel-Designs.md#6-the-machines-panel-topology--overlay) for the canonical content design layered onto the topology view.

> **The EVENT HANDLER machine cascade lives in the Epoch panel, not
> here.** Spec 005 §The structured transition cascade names "Xray's epoch
> panel" as the consumer of the `:cascade` tag on the
> `:rf.machine/transition` trace (rf2-n9f4z). Per rf2-akvfe the Epoch
> panel's EVENT HANDLER section narrates a machine event as: a structured
> **orientation line** (`Processing [TRIGGER] ‹vec› for [MACHINE] ‹id› in
> [STATE] ‹pre-transition-state›`) over the **numbered cascade pipeline**
> (one row per emit — guard / exit-action / TRANSITION / entry-action / …,
> each carrying its source + per-action `↳ data Δ`). The prior rf2-52u5n
> up/down `↑ exit / ↓ entry` structured-cascade BLOCK is REMOVED (it
> duplicated the pipeline); the structured `:cascade` survives as
> projection data + the cascade-order oracle the `machine_epochs` harness
> asserts against. Spec'd in
> [`021-Dynamic-Panel-Designs.md` §Transition row](021-Dynamic-Panel-Designs.md#machine-cascade-rf2-u69j7)
> + [§9.1.6.4 orientation line](021-Dynamic-Panel-Designs.md#9164-machine-event-event-handler-orientation-line-rf2-akvfe-supersedes-rf2-18oe3).
> This file (003) covers the Machines TAB's topology chart + focused-
> transition lens; the Epoch panel's EVENT HANDLER section is the
> cascade narration.

> **2026-05-19 collapse note (rf2-y9xmf):** the Dynamic Machines panel
> is now **event-driven only**. The panel is BLANK when the currently
> focused event triggered no machine transitions; it renders one
> per-machine section (topology + transition highlight + guards +
> actions + cancellation cascade + `:after` rings) when the focused
> event did trigger transitions. Per-machine prev/next navigation
> walks the spine's epoch history to the prior/next event that ALSO
> touched the focused machine.
>
> **What this collapse removed from the Dynamic surface:** the Mode A/B/C
> picker chrome, the sub-strip (Topology/Sim/Instances/Cluster), the
> picker-driven Sim ribbon UI, the multi-instance aggregate (Mode C
> cluster view), the per-instance arc + mini-scrubber, and the
> Browse-all entry point. The **Sim engine** has since been re-hosted
> under the Static Machines surface's Sim sub-mode (sibling bead
> rf2-r4nao landed — sub/event family at
> `:rf.xray.static.machines/sim-*`, view at
> `tools/xray/src/day8/re_frame2_xray/static/machines/sim.cljs`); the
> **browse-all index** ships as the Static Machines surface's
> master-detail left pane. Sections below describing those removed UI
> ribbons are kept as historical design-reference; they no longer
> describe what the Dynamic Machines panel renders.

The Machines tab (tab 5 of 7 in the 4-layer chrome — see
[`018-Event-Spine.md`](018-Event-Spine.md) §5) renders a Stately-quality
state-chart per registered machine. Post-rf2-y9xmf the panel surfaces
the focused event's machine activity only; the interactive simulation
(UC1) + dynamic multi-instance views (UC2 Mode A/B/C) descriptions in
later sections are normative for the Static re-host, NOT for the
Dynamic tab. The state-chart primitive is **owned by
`tools/machines-viz/`** as its own tool jar (canonical implementation
at `tools/machines-viz/src/day8/re_frame2_machines_viz/chart.cljs`
(the `MachineChart` component) + `chart/{layout,projection,nodes,edges}`,
per rf2-o9arp / PR #1570 and the rf2-gpzb4 xyflow migration); since the
xyflow migration the chart renders via **`@xyflow/react` + `elkjs`**, and
Xray imports `MachineChart` **directly**
(`[day8.re-frame2-machines-viz.chart :as mv-chart]` →
`mv-chart/MachineChart`) — the former Xray-side re-export shims were
removed.

The Machines tab is the **single most distinctive Xray surface** because
re-frame2's machine substrate (Spec 005) carries the richest runtime
behaviour in the framework — cancellation cascades, `:after` timers,
`:spawn-all` joins, microstep loops, hierarchical state transitions,
parallel regions, supervision trees. Xray is the only place these
contracts become legible. Bug-class motivation for each major feature in
[§The bug catalogue](#the-bug-catalogue) below; see also
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.1.

## Post-collapse Dynamic panel shape (rf2-y9xmf, rf2-8og3k)

The Dynamic Machines panel renders one of three states. The selection
is **single-instance, event-driven** — the panel binds to exactly the
machine instance the focused event targets, or to nothing. The rule is
specified in full at
[§Dynamic mode — single-instance, event-driven (rf2-8og3k)](#dynamic-mode--single-instance-event-driven-rf2-8og3k)
below; the three render states are:

1. **No machines registered** → empty-state message: "No machines
   registered." (Verbatim text in [§Empty state](#empty-state).)
2. **Focused event does not target a state machine** → the Machine tab
   shows NOTHING ELSE — only the verbatim placeholder:

   > **This event does not target a state machine**

   That string is the entire empty-state content. No chart, no lens, no
   transition history, no machine name — just that line, rendered as a
   quiet centered empty-state per the design system. (See
   [§Dynamic mode — single-instance, event-driven (rf2-8og3k)](#dynamic-mode--single-instance-event-driven-rf2-8og3k)
   for the full rule.)
3. **Focused event targets a machine instance** → the Machine tab
   renders **EXACTLY THREE elements, in order** (rf2-g2axio), for the one
   instance chosen per the rf2-8og3k selection rule below. A machine
   **birth** (`:rf.machine/started`) and a guard-blocked **no-op**
   (`:rf.machine.event/unhandled-no-op`) are first-class members of this
   set (per [§Machine birth (rf2-eldze)](#machine-birth--the-start--initial-entry-case-rf2-eldze)
   and [§Guard-blocked / no-op (rf2-skmc7)](#guard-blocked--unhandled-no-op--the-no-transition-case-rf2-skmc7)).

    1. **Prev/Next epoch nav** — the per-machine epoch walker (in the
       panel header; `rf-xray-machine-inspector-prev` /
       `rf-xray-machine-inspector-next`). Walks the spine's epoch history
       to the prior/next epoch whose cascade ALSO touched the focused
       machine (see the header-nav note below). Prev/Next moves the
       focused epoch, which re-feeds **both** the mini-pipeline and the
       chart highlights together.
    2. **The SHARED EVENT HANDLER mini-pipeline** — the **same** renderer
       the Epoch panel's EVENT HANDLER step uses
       (`epoch.view/machine-cascade-mini-pipeline`, over the **same**
       `epoch.projection/machine-cascade-rows` projection). It renders
       the numbered machine-cascade for the focused epoch: the structured
       EVENT HANDLER orientation line plus the microstep / exit / action /
       entry / guard / `[START]` / `[NO OP]` rows, each with its KIND+PHASE
       badge, verb link (click-to-source), interleaved source body, and
       per-row outcome / data-write. Both surfaces consume the **one**
       renderer (extract-and-reuse, rf2-g2axio) so they cannot diverge.
       The mini-pipeline mounts under
       `rf-xray-machine-event-handler-mini-pipeline` and carries the
       Epoch panel's own cascade testids verbatim
       (`rf-xray-epoch-handler-machine`,
       `rf-xray-epoch-machine-cascade-row-N`, `-ordinal-N`, `-kind-*`,
       `-phase-*`, `-verb-link-N`, `-source-body-N`, `-outcome-N`,
       `-data-write-N`, and `rf-xray-epoch-event-handler-orientation`).
    3. **The topology chart** (**xyflow + elkjs** primitive — rf2-gpzb4
       xyflow migration) with the focused epoch's highlights: the FROM
       state drawn dashed/accent-violet, the TO state bold/cyan,
       connecting edges emphasised, the focused epoch's traversed edges
       painting the FIRED treatment (rf2-qeemm), and `:after` countdown
       rings overlaying armed timer states. The chart carries its **own**
       toolbar (xyflow zoom/pan/fit controls) supplied by
       `machine-canvas/Chart`; the chart is a chart-owned concern, not
       tab chrome. (rf2-48fwsi retired the vestigial Canvas/List
       view-mode toggle that previously sat in this toolbar — it was
       dead after the rf2-g2axio events-as-nodes redesign, with no view
       branching on the persisted mode.)

   **Removed (rf2-g2axio):** everything else the pre-redesign section
   carried is gone — the bespoke **focused-transition lens** (the
   `Target Machine Instance:` · `TRANSITION` · `GUARDS RUN` ·
   `ACTIONS RUN` forensic block, formerly rf2-99rhe / rf2-2n34o), the
   per-machine **header ribbon** (`from → to` / `[START]` / `[NO-OP]`
   header badges), the **list/canvas view-mode wrapper**, the
   **chart-collapse** toggle + summary, the **snapshot drill-in**
   (rf2-lxvn6), and the **inline cancellation cascade**. The lens's
   forensic content is subsumed by the richer mini-pipeline cascade
   (which carries the SAME guard pass/fail, action source, and microstep
   detail in the SAME row stream the Epoch panel shows); timer
   cancellations surface as the mini-pipeline's `:timer` cascade rows.
   The redesign's purpose: the Machine tab had drifted into a thinner,
   bespoke summary that diverged from the Epoch panel's richer microstep
   view — sharing the one renderer fixes that permanently.

   **Cascade-row VERB rendering (rf2-982212).** Each `:guard` / `:action`
   cascade row paints a VERB beside its KIND+PHASE badge. A NAMED
   action/guard — a keyword into the machine's `:actions` / `:guards` map —
   renders its keyword verbatim (`:may-close?` / `:my-ns/count-open`). An
   INLINE action/guard — an anonymous `(fn …)` declared directly in an
   `:on` / `:always` / `:entry` / `:exit` / `:after` slot — carries a bare
   FUNCTION OBJECT (not a keyword) as its `:action-id` / `:guard-id` (the
   runtime carries the fn; impl: `re-frame.machines.transition` resolve-guard
   / resolve-action). Such rows render the synthetic placeholder `⟨inline⟩`
   for the verb — NOT the raw fn-object toString (`#object[Function …]` / a
   minified blob), which is what leaked before rf2-982212
   (`format/verb-label` replaced the `ns-keyword` `str` fallthrough at the
   cascade-row label site). The row's KIND+PHASE badge, per-row outcome chip,
   and (in dev builds) the interleaved SOURCE BODY + click-to-source via the
   derived spec-path still carry WHAT the inline declaration is; the
   `⟨inline⟩` verb reads as "an anonymous declaration" rather than garbage.

The header carries:
- A **prev/next nav** (`◀ Prev` / `Next ▶`) that walks the spine's
  epoch history to the prior/next epoch whose cascade ALSO touched
  the focused machine instance — skipping epochs whose cascade touched
  only other machines. The "focused machine instance" is the one
  selected per rf2-8og3k for the current event. Hidden in the
  state-2 (no-machine-targeted) and state-1 (no-machines-registered)
  branches.

  > **rf2-nugvv (2026-06-04)** — the nav mutates focus through the
  > spine's `focus-cascade-reducer` (stamping `:mode :retro` + resolving
  > the target epoch's settling `:dispatch-id`), starting from the
  > COMPOSED focus rather than the raw `:focus` slot. A bare
  > `[:focus :epoch-id]` write is silently overridden by
  > `compose-focus`'s LIVE+unpaused head-tracking, which is why the
  > buttons previously appeared dead on the live panel.

  > **Topology stability across Prev/Next (rf2-un3gfo).** Prev/Next
  > navigation within the SAME machine MUST preserve the topology
  > chart's node positions: only the per-transition highlights
  > (`from`/`to`/`current`/fired-edge) re-paint as the operator steps
  > through epochs — the chart is NOT re-laid-out. This is a structural
  > guarantee, not an emergent one: the per-machine focused-event
  > section's React `:key` is keyed on the STRUCTURAL identity
  > `[target-frame machine-id]` only — never on the epoch/record id,
  > the from/to-state, the fired-edge ids, or any highlight value — so
  > React preserves the section (and the nested `MachineChart`) instance
  > across navigation, keeping the chart's per-instance parse + ELK
  > layout caches warm. A genuinely different machine (different
  > topology) or a frame switch still produces a distinct key → a clean
  > instance + its own layout. Highlights remain a pure visual overlay
  > orthogonal to `MachineChart`'s ELK layout-key
  > (`[definition direction layout-options density]`, see
  > `tools/machines-viz/spec/API.md`); re-fitting the viewport on
  > navigation rides the orthogonal `:fit-signal` nonce, never a remount.

The header previously also carried a right-aligned **Share button**;
**rf2-nugvv (2026-06-04) removed it** along with the whole Xray share
surface (see [§Share affordance](#share-affordance) below) — the Machine
panel was its sole UI entry point.

The above-the-chart framing region is described in
[§Focused-transition lens — above the chart (rf2-99rhe)](#focused-transition-lens--above-the-chart-rf2-99rhe)
below; that lens is the surface a developer reads when they ask
"what just fired, and why?"

<!-- ============================================================ -->
<!--  FOCUSED-TRANSITION LENS  (rf2-99rhe)                          -->
<!-- ============================================================ -->

## Focused-transition lens — above the chart (rf2-99rhe)

> **SUPERSEDED — the bespoke lens was REMOVED by rf2-g2axio.** The
> Machine tab no longer renders this bespoke `Target Machine Instance:`
> · `TRANSITION` · `GUARDS RUN` · `ACTIONS RUN` forensic block. The
> Machine tab now shows EXACTLY THREE elements — Prev/Next, the
> **SHARED EVENT HANDLER mini-pipeline** (the same renderer + projection
> the Epoch panel uses), and the chart — see
> [§Post-collapse Dynamic panel shape](#post-collapse-dynamic-panel-shape-rf2-y9xmf-rf2-8og3k)
> above. The forensic question this lens answered ("this transition
> fired — show me everything about it: which guards decided how, which
> actions ran and what `:fx` they returned") is now answered MORE richly
> by the mini-pipeline's per-row cascade (guard pass/fail rows, action
> source bodies + outcomes + `:fx` data-writes, and every microstep) —
> the SAME rows the Epoch panel's EVENT HANDLER step renders, so the two
> surfaces cannot diverge. The §Data sources table below remains
> accurate as the trace-contract reference the mini-pipeline's projection
> reads; the §Selection sources and §Rendered shape subsections describe
> the retired bespoke surface and are kept only for design history.

The **focused-transition lens** was a forensic per-transition detail
panel that lived ABOVE the chart in the Machines panel. It answered
the question "this transition fired — show me everything about it":
which instance, which states it moved between, which guards
evaluated and how they decided, which actions ran and what `:fx`
they returned, and which downstream `:dispatch`es cascaded from
those actions.

The chart shows the **topology** of the transition (FROM dashed,
TO bold, edge emphasised); the (now-retired) lens showed the
**forensics** — content the shared mini-pipeline now carries.

### Selection sources

The lens is bound to a single fired transition at a time. The
selection can arrive from three surfaces, all of which write to
the same `:rf.xray/focused-transition` slot:

1. **Chart fired-edge highlight.** Clicking the emphasised edge
   in the topology view (the FROM→TO edge for the currently-rendered
   transition) selects it. The renderer-side fired-edge wiring
   (parity gap G3) is owned by `tools/machines-viz/`; the host-side
   wiring this panel implements is documented at
   [`tools/machines-viz/spec/001-Topology-Parity.md`](../../machines-viz/spec/001-Topology-Parity.md).
2. **Transition history ribbon.** See
   [§Transition history ribbon](#transition-history-ribbon) — clicking
   an entry rewinds the chart AND selects that entry as the focused
   transition (the same click drives both view-only chart rewind and
   lens binding, no double-affordance).
3. **L2 event row.** Selecting an event in the spine's L2 event list
   that triggered a transition surfaces the head transition from the
   resulting cascade in the lens. Selecting an event with no machine
   activity collapses the lens to its empty state.

The lens binding is **view-only** (same passive-scrubbing rule as the
global spine in [`018-Event-Spine.md`](018-Event-Spine.md) §6) — Xray
does not call `restore-epoch` from the lens.

### Rendered shape

The lens renders the following block of text-rich detail. The shape
is normative — any v1 implementation must render exactly these
labels in exactly this order:

```
Target Machine Instance: :title/flow-instance-42
TRANSITION
  idle → loading
GUARDS RUN
  :token?
    (fn [data] (get-in data [:session :token]))
    → return true
ACTIONS RUN
  :fetch!
    (fn [data] {:fx [[:dispatch [:loading/complete]]]})
    → :fx :dispatch → [:loading/complete]
```

Reading top-to-bottom: which instance fired · the from→to states ·
each guard that evaluated (id · fn source · return value) · each
action that ran (id · fn source · `:fx` output · downstream
`:dispatch` cascade child).

The text is rendered in monospace; ids use accent-violet (per the
[§Source-coord integration](#source-coord-integration) palette);
return values render in cyan; the trailing dispatch vector in the
`ACTIONS RUN` block is itself a clickable cross-reference to the
child epoch in the spine.

### Data sources

| Field | Source | Status | Notes |
|---|---|---|---|
| Target instance id (`:title/flow-instance-42`) | `:rf.machine/transition` `:tags {:machine-id …}` | available | the head trace's `:machine-id` tag |
| `from → to` states | `:rf.machine/transition` `:tags` | available | from/to are first-class fields on the transition trace per [Spec 005 §Trace events](../../../spec/005-StateMachines.md#trace-events) |
| Guard id (`:token?`) | `:rf.machine/guard-evaluated` `:tags {:guard-id …}` per [Spec 005 §Trace events — guard evaluations and action runs](../../../spec/005-StateMachines.md#trace-events--guard-evaluations-and-action-runs) | available | one trace per user-declared guard call site |
| Guard **fn source** | `(:rf.handler/source (rf/handler-meta :machine-guard [<machine-id> <guard-id>]))` per [Spec 005 §`:machine-guard` / `:machine-action` handler-meta surfaces (rf2-ftrcv)](../../../spec/005-StateMachines.md#machine-guard--machine-action-handler-meta-surfaces) | available (rf2-ftrcv) | the `reg-machine` macro walks the literal spec at expansion time and co-locates `pr-str` of each guard fn-form onto the spec's `:guards` entry; `handler-meta` **derives** it from the machine's `:event` registration (NOT a registrar kind); DEBUG-elided in production |
| Guard return (`true`) | `:rf.machine/guard-evaluated` `:tags {:outcome :pass \| :fail}` | available | binary outcome on the trace |
| Action id (`:fetch!`) | `:rf.machine/action-ran` `:tags {:action-id …}` | available | one trace per user-declared action invocation |
| Action **fn source** | `(:rf.handler/source (rf/handler-meta :machine-action [<machine-id> <action-id>]))` per [Spec 005 §`:machine-guard` / `:machine-action` handler-meta surfaces (rf2-ftrcv)](../../../spec/005-StateMachines.md#machine-guard--machine-action-handler-meta-surfaces) | available (rf2-ftrcv) | same shape as guard; the macro co-locates each action fn-form's `pr-str` onto the spec's `:actions` entry, derived back via `handler-meta` |
| Action `:fx` output | `:rf.machine/action-ran` `:tags {:outcome <return-value>}` | available | the action's return value rides the trace (`:ok` for nil; otherwise the literal `{:fx …}` map) |
| Downstream `:dispatch [...]` | cascade child-epoch link via the spine | available | the `:dispatch` is a child epoch off the focused cascade; the lens reads the link from the spine's epoch graph |

Every field above flows from existing Spec 005 / Spec 009 trace
contracts. The guard / action fn-source rows are served by the
`:machine-guard` / `:machine-action` handler-meta surfaces (rf2-ftrcv,
supersedes rf2-ypu5i; parallel to `:rf/cofx-id` per #2097) — see
[§Core-side enabler — guard / action fn source capture](#core-side-enabler--guard--action-fn-source-capture)
below for the data contract the lens reads.

#### Core-side enabler — guard / action fn source capture

The lens reads guard and action fn source via the `:machine-guard` /
`:machine-action` handler-meta surfaces (rf2-ftrcv, supersedes rf2-ypu5i;
parallel to the `:rf/cofx-id` marker work per #2097). These are **NOT**
registrar kinds — `registrar/kinds` is the closed ten. The `reg-machine`
macro walks the literal spec at expansion time, captures `pr-str` of every
guard / action fn-form, and co-locates it onto the spec's `:guards` /
`:actions` entries; that spec is stored under `:rf/machine` in the
machine's `:event` registration. `(rf/handler-meta :machine-guard [mid
gid])` **derives** the meta on demand from that `:event` spec (the
addressing is unchanged — the lens call sites do not change):

```clojure
(rf/handler-meta :machine-guard  [<machine-id> <guard-id>])
;; => {:rf/guard-id   <guard-id>
;;     :rf/machine-id <machine-id>
;;     :rf.handler/source  "(fn [data] ...)"
;;     :handler-fn    <fn>
;;     :ns :line :file [:column]}

(rf/handler-meta :machine-action [<machine-id> <action-id>])
;; => {:rf/action-id  <action-id>
;;     :rf/machine-id <machine-id>
;;     :rf.handler/source  "(fn [data] {:fx ...})"
;;     :handler-fn    <fn>
;;     :ns :line :file [:column]}
```

The lens renders `:rf.handler/source` inline beneath the guard /
action id. Production-elided per Spec 009 §Production builds — under
`:advanced` + `goog.DEBUG=false` the macro emission drops the
co-located `:source-coords` / `:source-code` slots (the dev arm DCEs)
and the `handler-meta` derivation is itself gated on
`interop/debug-enabled?` (returning nil), keeping fn-body bytes out of
prod bundles (verified by the elision-probe co-located `:source-code`
sentinels — rf2-ftrcv). The inline `reg-machine` macro is not the only
capture site: a machine defined with `defmachine` (the `def`-replacement
that walks the inline literal at the DEFINITION site, rf2-gwj8l) carries
the same co-located per-element source on the def'd VALUE, so a
value-registered machine — `(defmachine m …)` then `(reg-machine :id m)`
— surfaces source in the inspector exactly as an inline `reg-machine`
does. The `reg-machine*` plain-fn surface and a plain `(def m {…})`
value bypass both walkers, so those registrations carry no fn-source —
tools fall back to the call-site coords on the top-level
`(rf/handler-meta :event <machine-id>)`, per the standard
`reg-machine` / `reg-machine*` contract (Spec 005
§reg-machine vs reg-machine* and §Value-registered machines —
defmachine).

### Operator decision — Dynamic resolved, other modes pending (rf2-99rhe + rf2-8og3k)

> **Dynamic mode — resolved by rf2-8og3k (Reading A–equivalent).**
> The rf2-8og3k single-instance rule answers the original "whole vs
> one-of-several" question for Dynamic: the panel binds to **exactly
> one** machine instance per focused event (or to none, in which case
> the panel renders only the verbatim
> `**This event does not target a state machine**` placeholder per
> [§Dynamic mode — single-instance, event-driven (rf2-8og3k)](#dynamic-mode--single-instance-event-driven-rf2-8og3k)).
> There is no machine-instance picker in Dynamic mode — selection is
> implicit from event focus — so the lens IS the whole above-chart
> framing region for Dynamic mode (effectively Reading A from the
> original framing below). The `Target Machine Instance:` header line
> is a static label of the implicitly-selected instance, not a
> dropdown.
>
> **Static / Mode-C / Sim modes — still pending.** These modes have
> picker semantics (UC2 historical Mode A/B/C / Sim sub-strip; see
> [§UC2 — Dynamic Mode A / B / C](#uc2--dynamic-mode-a--b--c) preserved
> as Static-re-host reference, and [§Sim re-host reference](#sim-re-host-reference-rf2-r4nao--landed)).
> Whether the lens is the whole above-chart framing or one element
> among several in those modes remains under-determined; Mike to rule
> when those modes are next touched. Below is the original framing,
> preserved for that future decision:
>
> **Reading A — "Whole".** The lens IS the above-chart framing.
> The `Target Machine Instance: :title/flow-instance-42` header
> line becomes the instance picker (a dropdown over
> `(rf/machine-instances frame-id machine-id)`). Mode strip / sim
> controls live elsewhere or are deferred. The transition history
> ribbon stays below the chart per its current spec.
>
> **Reading B — "One element among several".** The lens is what
> appears when a transition is selected; other above-chart UI
> (machine-instance picker, the transition-history ribbon migrated
> above the chart, optional mode strip / sim controls) lives in
> parallel rows in the same above-chart region. When no transition
> is selected, the lens collapses to a thin placeholder row and the
> other rows still render. When a transition IS selected, the lens
> expands inline within the above-chart region.

### Cross-references

- [§Transition history ribbon](#transition-history-ribbon) — the
  ribbon is the primary selection-source for the lens. Click
  semantics, microstep indentation, and tooltips live there. The
  ribbon's location (above vs below the chart) is settled by the
  Reading-A / Reading-B decision flagged above.
- [§Selection and switching](#selection-and-switching) — the
  panel-header machine picker (Sim re-host reference territory) is
  a peer concept to the lens's `Target Machine Instance:` line; the
  Reading-A / Reading-B decision determines whether they merge.
- [§Source-coord integration](#source-coord-integration) — every
  id rendered in the lens (machine-id · guard-id · action-id · the
  child-`:dispatch` event-id) is a clickable source-coord chip per
  the existing editor-protocol matrix. The lens does not introduce
  a new source-coord pattern; it reuses the panel-wide one.

<!-- ============================================================ -->

## What is NOT in the Dynamic panel post-rf2-y9xmf

- No machine picker (the panel is event-driven; no exploratory
  selection).
- No sub-strip (Topology / Sim / Instances / Cluster).
- No Mode A/B/C dynamic instance views; no `:rf.xray/forced-machine-
  mode` slot.
- No Sim toggle / Sim side-rail UI in the Dynamic panel header. The
  `SimRail` view (post-rf2-r4nao rename of the historical
  `SimSideRail`) ships under
  `tools/xray/src/day8/re_frame2_xray/static/machines/sim.cljs` and
  is mounted by the Static Machines surface; the
  `:rf.xray.static.machines/sim-*` engine events / subs are
  registered against the `:rf/xray` frame from that ns.
- No per-instance arc overlay; no mini-scrubber. The
  `:rf.xray/machine-scrubber-position` SLOT survives (read/write
  events still registered) because the `:after`-rings overlay reads it
  to gate ring rendering to the `:present` position; the scrubber UI
  itself is gone. (rf2-nugvv removed the share-URL surface that
  previously also round-tripped it.)
- No Browse-all UI entry. The browse-all index algebra in the
  helpers ns remains for the Static re-host.

The historical Mode A/B/C / Sim sub-strip / arc / scrubber / cluster
descriptions in subsequent sections are preserved as design-reference
for the rf2-r4nao Static re-host; they DO NOT describe the Dynamic
panel.

<!-- ============================================================ -->
<!--  DYNAMIC MODE — SINGLE-INSTANCE, EVENT-DRIVEN  (rf2-8og3k)    -->
<!-- ============================================================ -->

## Dynamic mode — single-instance, event-driven (rf2-8og3k)

In Dynamic mode, the Machine tab shows **EXACTLY ONE machine
instance, OR NONE**. There is no machine picker, no instance list,
no exploratory selection — the panel binds to whatever instance the
**focused event** transitioned, and to nothing else. This rule
amends rf2-99rhe (the focused-transition lens) by settling the
instance-selection question for Dynamic mode: the lens has exactly
one subject, sourced implicitly from event focus.

### The rule

For the currently-focused event in the L2 event list:

1. **Enumerate the event's `:rf.machine/transition` traces, its
   machine-birth `:rf.machine/started` traces, _and its guard-blocked /
   unhandled `:rf.machine.event/unhandled-no-op` traces_.** The
   transitions are the from→to moves the event caused (Spec 005 trace
   contract; one per outer transition, plus
   `:rf.machine.microstep/transition` for `:always`-driven microsteps).
   The `:rf.machine/started` traces are machine **births** (rf2-eldze) — a
   pure start emits no `:rf.machine/transition` (it is an entry into the
   initial state, not a from→to), so a birth would otherwise be invisible
   to this lens. The `:rf.machine.event/unhandled-no-op` traces are
   guard-blocked / unhandled **NO-OPs** (rf2-skmc7) — an event that DID
   target a registered machine but matched no transition (xstate-v5 parity)
   so the machine stayed in its current state; a no-op emits no
   `:rf.machine/transition`, so it too would otherwise be invisible. Each
   trace (transition or no-op) carries `:tags {:actor-id …}` naming the live
   instance (rf2-ws5thu / rf2-yyvtk5); the birth signal (`:rf.machine/started`)
   keeps `:tags {:machine-id …}` (the type / singleton id). The Machine
   Inspector's `machine-id-of` reader prefers `:actor-id` and falls back to
   `:machine-id` so both shapes resolve. **Per-machine de-dup (Spec 005 — "a no-op is
   single-signalled"):** a machine that BOTH transitioned (or was born) AND
   no-op'd in the same cascade surfaces its transition / birth record only;
   the redundant no-op for that machine is dropped, so no ghost section.
2. **If the set is empty** → the panel renders only the verbatim
   placeholder (see [§Empty state — focused event does not target
   a state machine](#empty-state--focused-event-does-not-target-a-state-machine)
   below). No chart, no lens, no history ribbon, nothing else. (A birth OR a
   guard-blocked / unhandled no-op makes the set non-empty — a focused start
   epoch is NOT this empty state, see [§Machine birth
   (rf2-eldze)](#machine-birth--the-start--initial-entry-case-rf2-eldze); a
   focused guard-blocked / no-op epoch is NOT this empty state either, see
   [§Guard-blocked / no-op (rf2-skmc7)](#guard-blocked--unhandled-no-op--the-no-transition-case-rf2-skmc7).)
3. **If the set is non-empty** → select **one** instance per the
   tiebreaker below; render the focused-transition lens above the
   chart with that instance as `Target Machine Instance:`, the
   chart bound to that instance, and the transition history ribbon
   filtered to that instance's transition stream.

### Instance-selection tiebreaker

When the focused event's cascade transitioned **multiple machine
instances** in one event, the panel picks **the first
`:rf.machine/transition` trace in trace order** (the earliest
`:rf.trace/at` timestamp; ties broken by trace-emission sequence
within the same epoch).

Rationale: trace order matches the order the developer's effect /
action handlers fired the cascade, so "the first machine to
transition" is the **proximate** transition — the one the event's
primary effect aimed at. Subsequent machine activity in the same
cascade is downstream `:dispatch` fallout, which is what the spine's
prev/next cascade nav is for (per
[§Post-collapse Dynamic panel shape](#post-collapse-dynamic-panel-shape-rf2-y9xmf-rf2-8og3k)
header nav).

This is the v1 rule. Future iterations may surface a per-event hint
in the event payload (e.g. a `:rf.machine/primary-target` tag
written by the event handler) to override the first-by-trace-order
default; until then the framework offers no such hook and trace
order is authoritative.

### Empty state — focused event does not target a state machine

When the focused event's `:rf.machine/transition` set is empty, the
Machine tab renders only the following placeholder, as a quiet
centered empty-state per the design system — nothing else:

> **This event does not target a state machine**

That string is the entire empty-state content. No chart, no lens,
no transition history, no machine name, no instance picker, no
"select an event with machine activity" hint — just that line.
Visual treatment: centered in the panel viewport, body weight,
muted-foreground colour token per
[`007-UX-IA.md`](007-UX-IA.md) (matching the existing quiet
empty-state pattern used by other Xray panels). Worker proposes
this treatment from the Figma authority; if Figma has no settled
empty-state pattern yet, this section is the normative reference.

**Unhandled-event no-op is NOT this empty state (rf2-ugdas, impl rf2-skmc7).**
An event that DOES target a machine but matches no transition (xstate-v5
parity — a benign no-op, op-type `:rf.machine`, emitting
`:rf.machine.event/unhandled-no-op`) still targets a machine, so the Machines
tab renders the machine's topology with the CURRENT state highlighted (the "no
activity this epoch" Case B shape, [§021
Dynamic-Panel-Designs](021-Dynamic-Panel-Designs.md)), not this "does not
target a state machine" placeholder — see [§Guard-blocked / no-op
(rf2-skmc7)](#guard-blocked--unhandled-no-op--the-no-transition-case-rf2-skmc7)
for the full render shape. The same no-op is ALSO surfaced in the **Epoch
panel's** EVENT HANDLER machine cascade as a muted `NO OP` row (`[NO OP]
staying in {state}`, rf2-iu3no) — see [021
§machine-cascade-rows](021-Dynamic-Panel-Designs.md); that surface is the
per-event cascade narration, this section is the Machines TAB's topology read.
This placeholder is reserved for events that target NO machine at all.

This is the **state 2** branch in
[§Post-collapse Dynamic panel shape](#post-collapse-dynamic-panel-shape-rf2-y9xmf-rf2-8og3k);
the rf2-y9xmf prior text "No machine activity in the focused event."
is superseded by the verbatim string above.

### Machine birth — the start / initial-entry case (rf2-eldze)

A machine **birth** is the moment a machine first comes up into its
initial state — the eager `[:machine-id [:rf.machine/start]]` kick, the
lazy first-real-event fold, or a `:spawn`. The runtime emits exactly one
`:rf.machine/started` trace per successful initial-entry cascade (Spec
009 §`:op-type` vocabulary; the substrate's `maybe-boot` per Spec 005),
carrying `:tags {:machine-id :state :data :cause}` where `:state` /
`:data` are the **initial** snapshot slots and `:cause` is
`:explicit` / `:lazy` / `:spawned`.

A pure start **does not** emit a `:rf.machine/transition` — the substrate
deliberately suppresses it because a birth is an entry INTO the initial
state, not a from→to transition (Spec 005; "a pure start emits no
`:rf.machine/transition`; `:rf.machine/started` is the sole birth
signal"). So the Machine tab MUST treat `:rf.machine/started` as a
render-worthy focused-event member, not only `:rf.machine/transition`.
Before rf2-eldze the focused-event lens filtered to transitions alone, so
a focused start epoch produced zero records and the tab rendered the
state-2 "does not target a state machine" empty state — even though the
machine had just come up into its initial state. That was the rf2-eldze
bug.

**Render shape for a birth.** The birth renders the SAME per-machine
section a transition does, with these differences:

- **No from-state.** The header renders a `[START]` marker in place of
  the from-state path (`[START] → <initial-state>`), and the
  focused-transition lens renders **INITIAL ENTRY** (not TRANSITION) with
  the entry-arrow grammar `↳ <initial-state> (<cause>)` instead of
  `<from> → <to>`.
- **Topology highlights the initial state.** The chart is fed the
  resulting initial state as both the `to-highlight` (the landing-state
  emphasis grammar) and the `:current-state` (active-state highlight) so
  the just-born machine's initial state lights up regardless of which
  highlight the renderer keys off. There is no from-highlight (no prior
  state).
- **Snapshot drill-in.** `:before` is nil (the machine did not exist
  before its birth); `:after` is the synthesized initial snapshot
  (`{:state <initial> :data <initial-data>}`) so the drill-in renders the
  just-born machine's `:data`.
- **No guards / actions list.** The initial-entry cascade's actions are
  not traced as `:rf.machine/action-ran` (rf2-n9f4z), so there are no
  guard / action rows to attach.

Normal transitions are unaffected — they continue to render
`<from> → <to>` with from-dashed / to-bold highlighting exactly as
before.

The Epoch panel's EVENT HANDLER section renders the SAME birth signal as
a `[START]` cascade-row (rf2-it4vt) — that surface is the per-event
cascade narration; this section is the Machines TAB's topology read.
Both key off the one `:rf.machine/started` trace.

### Guard-blocked / unhandled no-op — the no-transition case (rf2-skmc7)

A **no-op** is a machine event that matched no transition and so left the
machine in its current state. Two causes produce it, indistinguishably at the
resolution layer:

- an **unhandled** user event — the machine declares no `:on` clause for the
  event-id at any active state-node (nor at the root `:on`); and
- a **guard-blocked** transition — a clause for the event-id DOES exist, but
  its `:guard` returned false. The substrate's `match-on-clause` returns the
  first candidate _whose guard passes_, so a guard-blocked clause is treated
  exactly like no clause: resolution yields nil.

In both cases the runtime emits exactly one `:rf.machine.event/unhandled-no-op`
trace (Spec 009 §`:op-type` vocabulary; the SOLE signal — a no-op macrostep
emits NO `:rf.machine/transition`), op-type `:rf.machine` (machine-activity
family, NOT an error / warning — xstate-v5 parity). The trace carries `:tags
{:machine-id :event :state}` where `:state` is the machine's **current**
(unchanged) state.

The motivating case (Mike, live machine-epochs, 2026-06-04): the door
guard-blocked close — `[:door/main [:door/close]]` where the `:may-close?`
guard FAILS, so the machine stays in `:open` as a no-op. Before rf2-skmc7 the
focused-event lens filtered to `:rf.machine/transition` / `:rf.machine/started`
alone, so a focused guard-blocked-close epoch produced zero records and the
Machine tab rendered the "does not target a state machine" empty state — even
though the event DID dispatch to `:door/main` and run its guard. This is the
SAME underlying gap rf2-eldze fixed for the START case, for a different
no-transition cause; the Machine tab MUST treat
`:rf.machine.event/unhandled-no-op` as a render-worthy focused-event member.

**Render shape for a no-op.** The no-op renders the SAME per-machine section a
transition does, with these differences:

- **No from→to edge.** The machine stayed put, so the header renders a
  `[NO-OP]` marker followed by the unchanged current state (`[NO-OP]
  <current-state>`) instead of a `<from> → <to>` path. The
  focused-transition lens renders **NO TRANSITION** (not TRANSITION) with the
  unchanged current state and a muted `(stayed — no transition matched)`
  annotation instead of `<from> → <to>`.
- **Topology highlights the current state.** The chart is fed the current
  state via `:current-state` (the active-state highlight) with NO
  from-highlight / to-highlight — so the chart paints a single active-state
  highlight on the current node rather than a misleading `state → state`
  self-transition edge.
- **The guard-blocked edge paints PINK (rf2-fzrzlw).** When the no-op is a
  GUARD-BLOCK (a clause for the event-id exists but its `:guard` returned
  false / threw), the chart marks that exact edge — and its event-node —
  GUARD-BLOCKED so the operator sees _which_ edge the event hit and that a
  guard rejected it. See
  [§Guard-blocked edge highlight on the topology chart (rf2-fzrzlw)](#guard-blocked-edge-highlight-on-the-topology-chart-rf2-fzrzlw)
  for the full mechanism. (Before rf2-fzrzlw the chart painted ALL of the
  active state's exits affordance-blue and gave ZERO rejection signal.)
- **No snapshot drill-in.** The no-op trace carries no `:before` / `:after`
  snapshot pair (`:data` did not change), so the drill-in suppresses cleanly.
- **The blocking guard IS surfaced in the lens cascade LIST (rf2-35mwxv).**
  The lens's GUARDS-RUN / ACTIONS-RUN forensic list is the SHARED machine
  cascade — the Machine Inspector lens (via `:rf.xray/machine-focused-epoch-
  cascade`) and the Epoch panel's EVENT HANDLER mini-pipeline both render the
  one `machine-cascade-rows` projection over the focused epoch's RAW
  `:trace-events` (rf2-g2axio). A guard-blocked no-op emits BOTH the
  `:rf.machine/guard-evaluated` fail/threw trace (during the candidate walk)
  AND the `:rf.machine.event/unhandled-no-op` trace; BOTH ops are members of
  `machine-cascade-trace-ops`, so the cascade carries a `[GUARD]` row NAMING
  the blocking guard with its `fail` / `threw` outcome chip, AHEAD of the
  `[NO OP]` row (canonical rank: guard → no-op). The operator's most common
  guard-block question — "my event did nothing, which guard blocked it?" — is
  answerable from the LIST, not only the chart. (The CHART independently
  surfaces the same failing guard by painting the rejected edge PINK — it
  consumes the `:rf.machine/guard-evaluated` fail/threw trace directly; see
  the rf2-fzrzlw section.)

A machine that BOTH transitioned (or was born) AND no-op'd in the same cascade
surfaces only its transition / birth record — a no-op is single-signalled
(Spec 005), so no redundant ghost no-op section renders for that machine.

Normal transitions and machine births are unaffected — they continue to render
exactly as their respective sections specify.

The Epoch panel's EVENT HANDLER section renders the SAME no-op signal as a
muted `[NO OP] staying in {state}` cascade-row (rf2-iu3no) — that surface is
the per-event cascade narration; this section is the Machines TAB's topology
read. Both key off the one `:rf.machine.event/unhandled-no-op` trace.

### Parallel multi-region fired-edge highlight (rf2-8ncxrf)

A `:type :parallel` machine's snapshot `:state` is a **region-map** —
one active leaf per orthogonal region (Spec 005 §Parallel regions). A
single external event can fire transitions in **N regions at once**, yet
the runtime emits exactly **ONE** `:rf.machine/transition` whose
`:before` / `:after` carry the WHOLE composite region-map (machines ·
`lifecycle_fx.registration/commit-or-finalize`). The focused Machine view
must light **every** region's fired edge for that one event.

The motivating case (Mike, live `examples/machine-epochs` HVAC,
2026-06-08): `:hvac/controller` in `{:climate :idle, :fan :off}`;
dispatch `[:hvac/power-cycle]` fires `:climate :idle→:running` AND
`:fan :off→:on` in the same macrostep. The STATIC topology renders both
`power-cycle` event-nodes, but the EVENT-FOCUSED dynamic view showed **NO
transition** — `extract-fired-edge-ids` ran the trace's `:before` /
`:after` `:state` through the single-active path coercion
(`normalise-path`), which returns nil for a **map**, so it lit ZERO
edges and the chart went blank.

**Derivation (data plane).** `panels/machines/trace-state/extract-fired-edge-ids`
detects the region-map shape on a transition's RAW (un-normalised)
`:before` / `:after` `:state`. For a region-map it derives the fired set
from the **machine-state change across ALL regions** — NOT from a single
`(from, to)` pair (and NOT from the cascade db-diff, which can report
empty changed-paths even though the machine snapshot under
`[:rf.runtime/machines :snapshots]` (runtime-db) changed). For each region whose
`(from ≠ to)`, it matches the per-region `(from-path, to-path, event)`
against the projected region edges, disambiguated by the edge's
**region-scoped `:source`** (`chart.layout/region-scoped-id` of the
region + in-region from-path — the SAME injective scheme
`chart.layout/highlight-ids` resolves a region-map against, rf2-wnzha).
This attributes each fired edge to exactly the region that moved, so two
regions sharing a state NAME never cross-light. A region that did NOT
move this event contributes no edge. The returned ids are the EXACT
canonical machines-viz edge-ids the live chart mints (agreement by
construction). Single-active (flat / compound) transitions are
unaffected — they take the existing `(from, to, event)` match + the
machine-level fallback.

**Per-region CHANGED fallback ordering (rf2-85a9do).** A changed region's
edge is resolved through an ordered, mutually-exclusive fallback:
(1) the **region-local** edge (region-scoped in-region `:source`, above);
else (2) the region's **OWN top-level `:on`** fallback — a region def is a
compound state and may carry a region-level `:on`, which machines-viz
projects (dropping the synthetic machine-root) as a `:machine-level?`
edge sourced from the **region container** (`chart.layout/region-node-id`)
with an in-region `:to-path` and **no** `:parallel-root-on?` (rf2-85a9do);
else (3) the parallel **ROOT `:on` (or root `:after`)** ancestor fallback —
the MACHINE-ROOT-sourced `:parallel-root-on?` chip whose region-qualified
`:to-path` names the region (rf2-3v3gv1). The root **`:after`** ancestor
fallback (rf2-m3otj2 — the timer-driven analog, applying via the same
region-qualified grammar) is projected carrying `:parallel-root-on? true`
TOO (plus `:parallel-root-after? true` + `:after <delay>`), so this same
arm 3 resolves a fired root-`:after` move without a separate arm — a fired
root `:after` lights its region-qualified edge exactly as a root `:on`
does. Separately, a region **HANDLED but UNCHANGED**
(`before == after` with a non-empty `:cascade` for the region) lights its
self / internal edge through an ordered, mutually-exclusive pair:
(a) a **leaf** self / internal edge (region-scoped in-region `:source`,
`:from-path == :to-path`, rf2-l8ls6w); else (b) a region-**ROOT
targetless / action-only `:on`** fallback — a region-level `:on`
omitting `:target` runs only its `:action` and moves no state, which
machines-viz projects (dropping the synthetic machine-root) as a
`:machine-level? true` **`:internal? true`** edge anchored to the
**region container** (`chart.layout/region-node-id`) on BOTH ends
(rf2-pdvtxt). A RESTING region (= but absent from the `:cascade`) lights
nothing. Without arm (2) a parallel region moved by its own (targeted)
region-level `:on` rendered the change with no highlight; without arm
(b) a region **handled-unchanged** by its own region-root targetless
`:on` was equally dark — `region-self-internal-fired-ids` keys on the
region-scoped in-region source and cannot reach the container-anchored
fallback. In both gaps the topology drew the edge but the focused
Dynamic chart left it dark.

**Render (render plane).** The host threads the multi-id set verbatim as
`:fired-edge-ids` into `machine-canvas/Chart` → `MachineChart`; the
projector marks **each** matching edge `:fired`, so all N region edges
paint the fired treatment at once. No render-plane change was needed —
the fired-edge set was always a SET; the bug was purely the data-plane
derivation returning empty for the region-map shape.

DOM pin: the chart root surfaces the sorted multi-id set on
`data-fired-edge-ids` (space-joined), so a parallel multi-region event
pins **all** fired region-edge ids, not one.

### Guard-blocked edge highlight on the topology chart (rf2-fzrzlw)

A guard-blocked no-op (above) emits NO `:rf.machine/transition`, so the chart's
fired-edge set ([§fired-this-epoch](#focused-transition-lens--above-the-chart-rf2-99rhe))
is empty for it. Pre-rf2-fzrzlw the chart therefore painted ALL of the active
state's outgoing edges affordance-blue and gave the operator ZERO signal that a
specific edge was _attempted and rejected_. The motivating case (Mike, live
`examples/machine-epochs` door, 2026-06-08): door in `:open` with `held-open?
true`; dispatch `[:door/close]`; the `:may-close? = (not held-open?)` guard
fails → guard-blocked no-op.

**The data already exists.** On the declining candidate the runtime emits
`:rf.machine/guard-evaluated {:actor-id … :guard-id <named-guard> :outcome
:fail|:threw :input {:event <event>}}` (machines · `transition.cljc`
`evaluate-guard`; rf2-yyvtk5 — the live actor INSTANCE rides under `:actor-id`). The named guard is the PRECISION the bare transition trace
lacks: `(event, guard)` uniquely picks the declining candidate — even one arm
of a guarded fork.

**Derivation (data plane).** `panels/machines/trace-state/extract-guard-blocked-edge-ids`
filters the focused epoch's `:rf.machine/guard-evaluated` traces (for this
`:machine-id`) to the `:fail` / `:threw` outcomes, then matches each by
`(event, guard-ref)` against the edges of `chart.layout/project-definition`,
returning the CANONICAL machines-viz edge-ids (the EXACT ids the live chart
mints — agreement by construction, mirroring `extract-fired-edge-ids`). The
host threads the set as `:guard-blocked-edge-ids` into `machine-canvas/Chart` →
`MachineChart` (both the Dynamic Machine tab and the Static Topology view).

**Render (render plane).** Under the events-as-nodes paradigm (rf2-qo5xy) each
transition splits into two segments: a `__in` (source-state → event-node) half
and a `__out` (event-node → target-state) half. `chart.projection/xyflow-graph`
marks the matching event-node + its `__in` half `:guardBlocked`;
`chart.nodes.event-node` + `chart.edges` then paint the guard-blocked treatment
off the new `theme/tokens` `:edge-guard-blocked` token (a PINK hue —
`:magenta-pink`, distinct from the blue fired/active hues AND the red
`:final-error` ring):

1. The `__in` edge stroke + arrowhead paint PINK and emphasised-width
   (`tokens/edge-color` resolves `:blocked?` with HIGHEST precedence).
2. The event-node border + box-shadow go PINK and its `IF <guard>` chip is
   EMPHASISED (bold pink) so the node reads as the guard that rejected it.

**The highlight STOPS at the guard event-node (rf2-4nxgqq, Mike-ruled
2026-06-08).** A guard-blocked transition is a NO-OP: the guard declined, the
machine STAYED in the source state, the target was NEVER reached (the renderer
distinguishes blocked from fired precisely because the machine state did not
advance — `db-before` state == `db-after` state). So the live blocked HIGHLIGHT
covers `source → event-node` ONLY, NEVER `event-node → target`. The `__out`
half therefore carries `:guardBlocked false` and paints RESTING (non-pink) —
lighting the onward arrow would FALSELY imply the transition progressed to the
target. The `__out` STATIC topology edge still renders (the transition exists
in the definition); only the live blocked-overlay is withheld from it. This is
GENERAL to ALL guard-blocked transitions: each branch of a guarded fork
independently drops the overlay from its own onward half. Implementation:
`chart.projection`'s `events-as-nodes-edge` gates the blocked flag per half
(`half-blocked? = blocked? AND in?`), driving both `:guardBlocked` and the
arrowhead colour.

**Design calls (Mike-ruled 2026-06-08):**

- _(1) BOTH_ — pink the edge stroke AND emphasise the guard label.
- _(2) The attempted edge WINS_ — on a guard-blocked no-op the PINK overrides
  the all-exits affordance-blue on that specific edge (it does NOT merely sit
  under the blue). `tokens/edge-color` ranks `blocked? > fired? > focused?/active? > quiet`.
- _(3) Scope = guard-blocked ONLY_ — a truly-unhandled event (no declared edge)
  is a separate state-node "ignored event" marker, filed separately.
- _(4) Highlight stops at the event-node (rf2-4nxgqq)_ — the onward
  `event-node → target` half is NOT highlighted, because the no-op never
  reached the target.

**Beyond XState.** Stately labels the guarded edge but, on a block, takes no
transition and highlights NOTHING — it does not paint a guard-failed edge
colour. This is a SUPERSET enhancement, only possible because re-frame2 emits
`:rf.machine/guard-evaluated` (observable-everything ethos).

**DOM pins (rf2-bdwolc — corrected).** The CANONICAL guard-blocked DOM pins are
the **guard-blocked event-node** and the **chart root**:

- the guard-blocked event-node (`[data-testid^="rf-mv-chart-event-"]`) carries
  `data-guard-blocked="true"` UNCONDITIONALLY (its `IF <guard>` chip also
  carries `data-guard-blocked`);
- the chart root surfaces the sorted set on `data-guard-blocked-edge-ids`
  (mirroring `data-fired-edge-ids`).

The per-half `:guardBlocked` STATE (`__in` true / `__out` false per rf2-4nxgqq —
the highlight stops at the event-node) is **projection/rendering data, NOT an
edge-half DOM pin**. It drives the half's stroke + arrowhead colour, but no
`data-guard-blocked` attribute exists on the `__in` / `__out` edge DOM:
`chart.edges/transition-edge` emits the edge-label element (and its
`data-guard-blocked` / `data-fired` attrs) ONLY when the edge has a non-empty
`:eventLabel` (`(when (seq label) …)`), and under events-as-nodes (rf2-qo5xy)
both halves carry an EMPTY label (the text rides the event-node). A test that
needs the per-half blocked treatment asserts the `:guardBlocked` projection
value (a `chart.projection` unit test), not an edge-half DOM attribute. (Earlier
revisions of this doc claimed the `__in` / `__out` halves carried
`data-guard-blocked` DOM attrs; they do not — the event-node + chart-root are
the real pins.)

### Relationship to the focused-transition lens (rf2-99rhe)

The rf2-99rhe focused-transition lens defines **what** renders
above the chart when there is one transition to inspect: the
forensic block of `Target Machine Instance:` · `TRANSITION` ·
`GUARDS RUN` · `ACTIONS RUN` (per
[§Focused-transition lens — above the chart (rf2-99rhe)](#focused-transition-lens--above-the-chart-rf2-99rhe)
§Rendered shape). This bead (rf2-8og3k) defines **when** there is
one to inspect and **which one** it inspects:

- **Dynamic mode:** always at most one. The lens IS the whole
  above-chart framing — there is no instance picker, no mode
  strip, no parallel rows. (This resolves the rf2-99rhe
  Operator-decision callout in favour of Reading A for Dynamic;
  see the updated callout under that lens section.)
- **Static / Mode-C / Sim modes:** retain picker semantics per
  the historical [§UC2 — Dynamic Mode A / B / C](#uc2--dynamic-mode-a--b--c)
  section (preserved as Static re-host reference). The
  rf2-99rhe Reading-A vs Reading-B question is still open for
  those modes.

### Cross-references

- [§Post-collapse Dynamic panel shape (rf2-y9xmf, rf2-8og3k)](#post-collapse-dynamic-panel-shape-rf2-y9xmf-rf2-8og3k)
  — the three render states; this section refines states 2 and 3.
- [§Focused-transition lens — above the chart (rf2-99rhe)](#focused-transition-lens--above-the-chart-rf2-99rhe)
  — the lens this rule sources its single instance for; the
  Operator-decision callout there cross-references back to this
  section for the Dynamic-mode resolution.
- [§Transition history ribbon](#transition-history-ribbon) — the
  ribbon is filtered to the single selected instance's transition
  stream in Dynamic mode.
- [§Selection and switching](#selection-and-switching) — the
  panel-header machine picker described there is **Static** /
  reference territory; Dynamic mode has no such picker.
- [Spec 005 §Trace events](../../../spec/005-StateMachines.md#trace-events)
  — the `:rf.machine/transition` trace contract this rule reads
  from.

## `:data-schema` — declared Context shape + frame-declared redacted `:data` (rf2-kq8nac · EP-0005 · EP-0025)

EP-0005 adds an optional `:data-schema` to `reg-machine` — a Malli
validator for the machine's `:data` context (the re-frame2-native analog
of XState v5 typed context). It carries two consequences the Machine
Inspector surfaces, both consumer-side of the framework work
([the declared Context shape](../../machines-viz/spec/001-Topology-Parity.md)
is rf2-3q4k5b; the `:data` redaction is now **frame-owned** per
[EP-0025](../../../docs/EP/EP-0025-data-classification.md),
rf2-398kql — which reversed the EP-0005 `:data-schema`→marks redaction
bridge). The `:data-schema` continues to VALIDATE `:data` and drive the
declared Context shape; durable `:data` egress classification moved to the
frame.

### Declared Context shape in the focused-event chart

The focused-event chart's root **Context band** shows the machine's
`:data` keys + their type captions (`{:retries "number", :token
"string?"}`). When the machine declares a `:data-schema`, the shape is
read **AUTHORITATIVELY** off the schema's `[:map [k schema] …]` entries
and the chart drops the `inferred from :data` badge (rf2-5tz9p),
rendering `declared` instead. Absent a schema, the shape falls back to
the one-sample inference from the definition's initial `:data` and the
`inferred from :data` badge stays — exactly the Static Topology view's
behaviour (rf2-3q4k5b). Both surfaces consume the SAME
`panels.machines.topology-view/static-context-shape` /
`static-context-inferred?` (which delegate to the machines-viz
`context-shape` helper); there is no duplicate derivation. The Context
band shows the SHAPE only — never live `:data` VALUES — so a declared
shape carries no value-redaction concern.

> **machines-viz chart defends the live-value path independently
> (rf2-27e38h · EP-0015).** Xray feeds the value-free SHAPE, but the
> machines-viz `MachineChart` contract also accepts **live `:data`
> values** (`:context-band-inferred? false`), and that band is serialised
> into the chart's SVG/PNG/clipboard export. The chart therefore applies
> a **local-redacted projection to the Context band by default**, keyed
> on host-declared `:context-band-sensitive` / `:context-band-large`
> sets (derived from the machine's `:data-schema` slot props) with an
> explicit `:context-band-raw?` trusted-local opt-in — see
> [machines-viz API §Context-band egress contract](../../machines-viz/spec/API.md#context-band-egress-contract--local-redacted-by-default-rf2-27e38h--ep-0015).
> Xray's value-free type-caption shape is a redaction no-op, so this
> does not change the Machine Inspector surface.

### Redacted `:data` — the panel reads the EGRESSED snapshot (frame-owned, EP-0025)

> **EP-0025 (rf2-398kql) — frame-owned, not schema-attached.** Durable
> machine `:data` egress classification is now declared **on the frame**:
> `reg-frame` `:sensitive` / `:large {:app-db [[:rf.runtime/machines
> :snapshots <id> :data …]]}` (the sole app-db / runtime-db mechanism). The
> EP-0005 `:data-schema`→marks redaction bridge is **reversed** — a
> `:sensitive?` / `:large?` `:data-schema` slot prop no longer classifies
> durable `:data` for egress (it still drives validation-failure-trace
> redaction, a separate axis). The `:data` egress treatment below is
> unchanged in shape; only the classification SOURCE moved to the frame.

A FRAME-declared sensitive / large machine-snapshot `:data` path is honoured
in **snapshot egress**: the `:before` / `:after` / `:snapshot` `:data` slots
on every `:rf.machine/transition` / `:rf.machine/snapshot-updated` trace are
redacted to `:rf/redacted` / the `:rf.size/large-elided` marker by
`re-frame.marks/project-trace-event` at emit (which re-roots the frame's
snapshot-path declaration snapshot-relative via
`re-frame.marks/frame-snapshot-marks`), BEFORE epoch-capture sees the event.
The Machine Inspector's `:data` view reads the focused epoch's
`:trace-events` — those EGRESSED events — so a frame-declared sensitive
`:data` slot renders redacted, never raw, in the SHARED mini-pipeline cascade
(and the Epoch panel's EVENT HANDLER step, which shares the projection).

The **LIVE** machine-snapshots sub (`:rf.xray/machine-snapshots`) is the
one path that reads the RAW frame-db slot `[:rf.runtime/machines
:snapshots]` (EP-0001 rf2-vzld77 — machine snapshots are durable
runtime-db state, sourced via `:rf.xray/target-frame-runtime-db`)
directly rather than a trace, so it has NOT passed through
the egress redactor. The panel therefore routes each live snapshot
through the SAME `project-trace-event` chokepoint (as a synthetic
`:rf.machine/snapshot-updated` event **stamped with the target frame**) on
read, so a frame-declared sensitive `:data` slot in the live snapshot reads
back `:rf/redacted` for every downstream consumer (the chart's live-snapshot
`:current-state-override`, after-rings, sim) — the same per-slot treatment
the trace path gets. A frame that declares no matching `:data` path leaves
its snapshot reference-unchanged (no extra work for the no-marks common case).

## Architectural posture

**`tools/machines-viz/` owns the chart.** Per rf2-o9arp / PR #1570 the
MachineChart primitive lives in its own tool jar; per the rf2-gpzb4
xyflow migration (2026-05-21) it now renders via **xyflow + elkjs**
(hierarchical-layered layout, custom Stately/xstate-style nodes + edges
+ arrowheads) — the prior host-side ELK+SVG layout/render is gone.
Xray's own pure-data topology projector (`panels/machines/topology.cljs`
— initial markers, compound nesting, self-loops, `event [guard] / action`
labels, parallel regions; JVM-portable + unit-tested) survives as a
self-contained fallback projection but is no longer the hot render path.
Xray is a **consumer** of that primitive: the panel surface
(`panels/machine_canvas.cljs` + `panels/machines/topology_view.cljs`)
imports the machines-viz chart API **directly**
(`day8.re-frame2-machines-viz.chart/MachineChart`) — post the xyflow
migration the older Xray-side `chart.svg` / `chart.layout` /
`chart.interaction` re-export shims were removed (the only remaining
Xray-local `chart.*` ns is `chart/timing_waterfall.cljc`, unrelated).
One implementation, one elkjs layout pass — re-used by Xray, Story
(per-variant observability ribbons), the read-only viewer page, and any
host-app drop-in that wants the chart without Xray's panel chrome.

The Mermaid text emitter lives at
`tools/machines-viz/src/day8/re_frame2_machines_viz/mermaid.cljc`
(namespace `day8.re-frame2-machines-viz.mermaid`) per rf2-sqhqu — it
is a tool-side code-gen concern, so the runtime `machines` artefact
stays pure-engine. Hosts that want Markdown-paste Mermaid require the
machines-viz tool jar; the framework does not re-export the emitter
(no `rf/machine->mermaid` at framework level — see machines-viz
`DESIGN-RATIONALE.md` §Lock).

```
tools/xray/  ─requires→  tools/machines-viz/  ─requires→  implementation/machines/
   (panel surface,           (MachineChart primitive:        (machine registry +
    direct import of          chart.cljs (xyflow+elkjs) +      runtime substrate)
    MachineChart)             chart/{layout,projection,
                              nodes,edges})
```

### Direct-import contract (post xyflow migration)

The re-export-shim layer was retired with the xyflow migration: Xray's
panel surface now `:require`s the machines-viz chart API directly
(`[day8.re-frame2-machines-viz.chart :as mv-chart]` →
`mv-chart/MachineChart`; `day8.re-frame2-machines-viz.chart.layout` for
`highlight-id`). The machines-viz API is the single source of truth; the
contract is still one-implementation, no-Xray-side-behaviour, but
without the indirection. Adding a chart feature means landing it in
machines-viz; Xray picks it up by import. Embedders who want the chart
alone depend on `tools/machines-viz/` directly; embedders who want Xray's
panel chrome get the chart transitively via Xray.

### See also

- [`000-Vision.md`](000-Vision.md) §Where Xray fits — the
  Xray → machines-viz → implementation/ dependency arrow at the
  whole-tool level.
- [`008-Embedding-Contract.md`](008-Embedding-Contract.md) — the
  full-shell embed contract. Hosts that want only the chart skip
  Xray entirely and depend on `tools/machines-viz/` directly.
- [`tools/machines-viz/spec/001-Topology-Parity.md`](../../machines-viz/spec/001-Topology-Parity.md)
  — the machine-topology parity plan against xstate / Stately Studio
  (parity bar · gap analysis · Figma-ready visual design · roadmap).
  Owns the renderer-side parity bar; the fired-edge live-highlight
  wiring (G3) is the host half this panel implements.

## Tab placement

- **Tab id:** the Machines tab (`m` mnemonic), `:order 4` in the nine-tab Dynamic inventory (Epoch · app-db · Views · Trace · **Machine** · Routes · Resources · Graph · Modules). Routing is its own L3 tab per rf2-nrbs9; Resources / Graph / Modules were added per EP-0016 / EP-0014 / EP-0013. (The Issues tab was removed per rf2-gbz39 Option (c); issues surface inline in the Epoch panel + the L2 event-row pink-wash + the always-on issues ribbon signal.)
- **Spine binding:** reads `:rf.xray/focus`. The Machines tab inherits
  the ribbon's selected frame; if a user has a machine spawned in
  `:app/dialog`, they need to select `:app/dialog` in the picker to see
  it.
- **Isolation invariant:** the tab shows ONLY the selected frame's
  machines per [`018-Event-Spine.md`](018-Event-Spine.md) §8 I3.

<!-- ============================================================ -->
<!--  STATIC MACHINES SURFACE (normative; shipped per rf2-o5f5f.2)  -->
<!-- ============================================================ -->

## Static Machines surface

The Static-mode Machines surface is **a peer** to the post-collapse Dynamic Machines panel described above, **not a successor**. Static is the event-INDEPENDENT registry browse — what's REGISTERED — and runs alongside the Dynamic panel within Xray's two-mode chrome. The architectural contract for the Two-modes split lives in [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) Lock #14 + [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 Static surface; this section owns the concrete Static Machines surface description.

### Tab placement

The Static Machines tab is **tab 1 of 5** in the Static L3 strip (per [`007-UX-IA.md`](007-UX-IA.md) §Static mode sub-tab inventory). Mnemonic `m` (mode-scoped — see [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 Mnemonic mode-scoping rule: `m` in Dynamic opens the instance inspector, `m` in Static opens the registry browse). Static Machines is the **default Static tab** because the Machines registry is the densest Static surface; opening Static on a fresh slate lands on the highest-value tab.

### Master-detail layout

```
┌──────────────────────────┬───────────────────────────────────────────────┐
│ L4-left  (280px fixed)   │ L4-right  (fills)                             │
│                          │                                               │
│ ─ search box             │ <machine-id> · src/cart/.../checkout.cljs:42 ↗│
│ ─ sort cycle (Name /     │   · 6 states · 2 live (→ Dynamic)             │
│   States / Live)         │                                               │
│ ─ scrollable rows:       │ ─ 4-mode sub-strip [T][S][I][C]               │
│   ◉ :checkout    src:42  │ ─ per-mode body (Topology · Sim body ·        │
│   ○ :auth/main   src:18  │   Instances JUMP · Cascade dimmed)            │
│   ○ :wizard      src:90  │                                               │
│   …                      │                                               │
│   each row carries:      │                                               │
│   - selection glyph      │                                               │
│   - mono machine-id      │                                               │
│   - source-coord chip    │                                               │
│   - state-count chip     │                                               │
│   - live-instance pips   │                                               │
│   - → Dynamic JUMP chip  │                                               │
└──────────────────────────┴───────────────────────────────────────────────┘
```

**Left pane — browse-all list.** Scrollable list of every registered machine; search box + sort-cycle button (`Name → States → Live → Name`) at the top. Each row carries: a selection glyph (`◉` active / `○` inactive — same vocabulary as the Static tab-bar), the machine-id rendered in monospace accent-violet, a source-coord chip (jump-to-source via the existing open-in-editor affordance), a state-count chip, a live-instance pip cluster (capped at 12; beyond that → textual count), and a per-row `→ Dynamic` JUMP chip. Empty-state: "No machines registered. `rf/reg-machine` to add the first."

**Right pane — definition detail.** Header carries `<machine-id> · <source-coord ↗> · <N> states · <M> live`. Below the header, the **4-mode sub-strip** drives the per-mode body.

### 4-mode sub-strip

```
┌────────────┬────────────┬───────────────┬─────────────────────┐
│ [Topology] │ [Sim]      │ [Instances]   │ [Cascade]           │
│  (t)       │  (s)       │  (i — JUMP)   │  (c — DIMMED)       │
└────────────┴────────────┴───────────────┴─────────────────────┘
```

The 4 sub-modes (mnemonic letters `t/s/i/c` surfaced in each pill's `title`) live inside the same DOM shape the Dynamic Machines sub-strip used pre-collapse — same pill DOM, same letter mnemonics — so muscle-memory carries between the two modes. The Static-mode behaviours differ:

| Pill | Behaviour in Static | Body renderer |
|---|---|---|
| **Topology** (`t`, default) | Static-read of the machine's state graph — the SAME `chart/MachineChart` (xyflow + elkjs) primitive the Dynamic panel uses (single implementation), but with **NO `:highlight-id`** because Static is event-INDEPENDENT (there is no active state to spotlight). Click on a state node fires `:rf.xray.static.machines/state-clicked` for a per-state metadata rail (follow-on bead). Carries an "Open chart in pop-out" affordance. | xyflow MachineChart |
| **Sim** (`s`) | Hermetic 'what-if' simulator (rf2-r4nao — landed). Clones the registered machine definition into Xray's app-db at `[:rf.xray.static.machines/sim-by-machine <machine-id>]`; production registry is untouched. Event-INDEPENDENT — Sim does NOT read the live snapshot; the seed is the definition's declared `:initial` + `:data`. Engine events/subs live under the `:rf.xray.static.machines/sim-*` namespace (`sim-start`, `sim-step`, `sim-reset`, `sim-stop`, `sim-set-pending-event`, `sim-set-pending-data`). View at `tools/xray/src/day8/re_frame2_xray/static/machines/sim.cljs` exports `pill` (the strip cell), `body` (the per-machine Sim panel) and `SimRail` (the geometry-coupled side rail). Failed-guard handling + sim-trail described in §UC1 — Sim sub-mode below remain the design reference for v1 mechanics. | Sim body panel (banner + topology highlight + mock-`:data` form + sim-trail) |
| **Instances** (`i`) | **JUMP to Dynamic.** Clicking the pill (or the per-row `→ Dynamic` chip in the browse-list) dispatches three events against `:rf/xray`: `:rf.xray/set-mode :dynamic` · `:rf.xray/select-tab :machines` · `:rf.xray/select-machine-id <mid>`. The user lands on the Dynamic Machines tab with this machine pre-selected. Mode B/C auto-detection (Mode B for 2-8 live, Mode C for ≥8) is the Dynamic panel's responsibility — the Static-side JUMP just lands the selection. | no body — the click is the surface |
| **Cascade** (`c`) | **Dimmed + disabled** with a tooltip: *"Cancellation cascade is a Dynamic-only surface. Switch to Dynamic mode to view."* The pill renders for muscle-memory consistency with the Dynamic sub-strip (same DOM, same letter mnemonic) but is non-interactive — `disabled` + `aria-disabled="true"` + dashed border + 0.5 opacity. The cancellation cascade composes against the trace ring buffer which is event-coupled — there is no spine in Static mode, so the surface has no source data. | no body — the pill IS the surface |

The sub-strip mnemonics are mode-scoped under the same rule the L3 tabs follow (see [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 Mnemonic mode-scoping rule).

### Per-row → Dynamic chip

Every browse-list row carries a trailing `→ Dynamic` chip that fires the same three-dispatch JUMP the Instances pill fires (centralised in one handler so the two surfaces never drift). Click semantics: stop propagation on the row's own select-handler; flip mode to Dynamic; surface the Dynamic Machines tab; select this machine. The user gets a per-row shortcut from Static into the Dynamic instance inspector without first having to select the row in Static.

### localStorage persistence

The user's Static-Machines state survives reloads via **two** localStorage slots under the `xray.static.machines.*` prefix (the broader Static mode slot is `xray.mode` per [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 localStorage persistence):

| Slot | Type | Notes |
|---|---|---|
| `xray.static.machines.selected-id` | bare string (machine-id keyword name; namespaced ids store as `ns/name`) | currently selected machine-id; mirrors the `xray.mode` pattern — bare string keeps it cheap to inspect from devtools |
| `xray.static.machines.sub-mode-by-id` | EDN map `{machine-id sub-mode}` | per-machine sub-mode choice. EDN because the map will grow new keys as new sub-modes land; modes are an enum but the per-machine keying needs structured serialisation |

`hydrate!` is called on Static-Machines `install!` so the first render after a reload restores the prior selection + per-machine sub-mode choices. Test fixtures call `clear!` in their `:each` setup.

### Frame isolation

Same discipline as the Dynamic Machines panel (per §Tab placement above + [`018-Event-Spine.md`](018-Event-Spine.md) §8 I3). The Static Machines surface is wrapped in the Static shell's `[rf/frame-provider-existing {:frame :rf/xray}]`; every subscribe + dispatch inside the surface resolves to `:rf/xray`. The browse-list, definition-detail, sub-strip pills, and Topology renderer are all `reg-view`-registered.

### See also

- [`DESIGN-RATIONALE.md`](DESIGN-RATIONALE.md) Lock #14 — the direction-setting decision behind Two modes (Dynamic + Static).
- [`018-Event-Spine.md`](018-Event-Spine.md) §2.5 Static surface — the architectural spine for the Static mode (3-layer chrome · 4 mode signals · mode-state lifecycle · localStorage `xray.mode` · feature flag · mnemonic mode-scoping).
- [`007-UX-IA.md`](007-UX-IA.md) §Static mode — the visual-language details (mode pill chrome, edge stripe tokens, motion dampening, sub-tab inventory).
- §Sim re-host reference (rf2-r4nao — landed) below — the historical UC1 Sim + UC2 Mode A/B/C prose preserved as design-reference. The Sim sub-mode now ships per rf2-r4nao at `tools/xray/src/day8/re_frame2_xray/static/machines/sim.cljs` with engine events/subs under `:rf.xray.static.machines/sim-*`.

<!-- ============================================================ -->
<!--  SIM RE-HOST REFERENCE (rf2-r4nao — landed)                    -->
<!-- ============================================================ -->

## Sim re-host reference (rf2-r4nao — landed)

> The sections below describe the UC1 Sim engine and UC2 Mode A/B/C
> dynamic-instance UI as they existed pre-collapse (rf2-y9xmf). They
> remain preserved as design-reference for the **Sim re-host effort
> (rf2-r4nao — landed)** — the sibling bead that landed the Sim
> machinery under the Static Machines surface's Sim sub-mode. The
> shipped engine + view live at
> `tools/xray/src/day8/re_frame2_xray/static/machines/sim.cljs`
> with events/subs under `:rf.xray.static.machines/sim-*`; the
> §4-mode sub-strip row above is the normative description of the
> shipped Sim sub-mode shape.
>
> **They DO NOT describe what the Dynamic Machines panel renders
> today** (see the collapse note at the top of this doc and the
> "Post-collapse Dynamic panel shape" section above) — that surface
> is event-driven only. They also do NOT describe the shipped Static
> Machines surface above (which is master-detail with a 4-mode
> sub-strip, NOT the Mode A/B/C dynamic-instance UI sketched in the
> following sections — Mode B/C live-instance views remain a
> Dynamic-side responsibility, reached from Static via the JUMP).
>
> Read everything below this divider as historical design-reference
> for the Sim re-host effort, not as a normative description of any
> currently-shipped surface.

## Definition view — Mode A resting state

The Machines tab opens in **registry-index** mode when no specific
machine is selected (showing live count per definition + dormant
defs). When a machine is selected, the inspection view renders:

```
╭─ Machine Inspector · :checkout ─── frame: :app/main ── Sim ○ ──╮
│ 6 states · 11 transitions · 2 spawned children · src/cart/...:42 │
├──────────────────────────────────────────────┬─────────────────┤
│  [diagram canvas: xyflow+elkjs primitive]    │ Metadata          │
│                                              │ ─────────────     │
│  (states, edges, source coords inline)       │ id   :checkout    │
│                                              │ tags #{:user-flow}│
│                                              │ data {...}        │
│                                              │ Transitions (11)  │
│                                              │ ▶ :checkout/begin │
│                                              │ ▶ :pay/approved   │
│                                              │ ▶ :pay/declined   │
│                                              │ ...               │
│                                              │ Source            │
│                                              │ src/cart/...:42 ↗ │
├──────────────────────────────────────────────┴─────────────────┤
│ Live instances (0)                                              │
│  ⬚ idle — toggle Sim ↑ to walk topology                         │
╰─────────────────────────────────────────────────────────────────╯
```

When 0 instances: empty hint nudges Sim mode (UC1). When ≥1 instances:
roster (Mode B/C per UC2 §Dynamic Mode A/B/C below).

## UC1 — Sim sub-mode

The interactive simulation mode. **A sub-mode of Mode A** (entry only
when no live instances OR when explicitly toggled on against live
instances — in the latter case, sim runs on a parallel mocked
instance, never touching the live runtime).

### Toggle and persistence

- **Per-(frame-id, machine-id) toggle** persisted to localStorage.
- **`Sim ●` indicator** in the panel header — visually distinct from
  live (cyan) by being amber.
- **`E` key** opens the event picker for "what event do you want to
  fire?"
- **`R` key** resets to initial state.
- **`Esc`** from chart exits Sim mode.

### Visual cues (three reinforcing)

1. **24px tinted banner** across top of panel canvas:
   `▓▓▓▓▓ SIMULATING — no live data; clicks walk the topology ▓▓▓▓▓ ?`
2. **Amber tint** on active-state highlight (live uses cyan; sim uses
   amber — the existing palette reserves cyan for live, red for
   errors, amber for "informational, not a real event").
3. **`Sim ●` header indicator** alongside the toggle.

### Mock `:data` form

Schema-derived (Malli, when registered) row-per-key form in the right
rail; type-inferred when no schema; EDN fallback for unknown shapes.
Actions execute against mocked `:data` so it evolves through the walk.

### Failed-guard handling (strongest opinion in UC1)

Transitions whose guards fail against current `:data` are **LISTED but
greyed**, not silently hidden. **`Shift+Enter` fires-despite-guard**
with the message *"firing despite guard — this would not happen in a
live instance."*

This diverges deliberately from Stately (who silently hides failed
transitions); listing them with the verdict makes the guard's role
part of the lesson:

```
Available transitions from :authing (current mocked :data: {…}):

  ▸ :auth/login-ok             → :ready         (guard ✓ passed)
  ▸ :auth/login-fail           → :error         (guard ✓ passed)
  ▸ :auth/two-factor-required  → :awaiting-2fa  (guard ✓ passed)
  ▸ :auth/admin-override        → :admin        (guard ✗ failed — :data.user.role ≠ :admin)
       Shift+Enter to fire despite guard
```

### Skip-guards toggle

Opt-in (`G` chord or "Ignore guards" checkbox). For the legitimate use
case "walk topology before writing guards." Default off.

### Sim trail

Below diagram: horizontal `state→state` sequence; hover rewinds without
truncating; click truncates ("forked here"). Distinct from live
transition-history ribbon (no timestamps, no source-coord, no
causation — just `state→state`).

### Save as scenario

Clipboard-only for v1; emits `(machines.test/simulate-scenario {...})`
form for pasting into a test file. No in-app saved scenarios library
(violates Lock #4 / Xray-as-product creep).

## UC2 — Dynamic Mode A / B / C

Three view modes adapt to instance count. The mode is selected
automatically based on `(count (rf/machine-instances <frame-id>
<machine-id>))`; user can pin a mode via the panel header toggle.

| Mode | Trigger | Diagram | Above diagram | Below diagram |
|---|---|---|---|---|
| **A — Zero instances** (or Sim on) | 0 live | Neutral OR sim'd walk | Sim toggle | Empty hint OR sim trail |
| **B — Few (1–3)** | 1–3 live | All on same diagram (different hues) | Inline instance tabs | Instance roster + active-instance arc |
| **C — Many (4+)** | ≥4 live | Diagram + cluster-by-state count badges | Search/filter/sort bar + selected breadcrumb | Virtualised instance table + focused-instance arc |

### Per-instance highlighting on SAME diagram

Two reasons (Lock #11 topology/runtime separation):

1. **Layout cost.** Per-instance diagrams = N layout passes. Same
   diagram = 1 layout pass + O(N) attr mutations.
2. **Topology IS topology.** Two instances of `:checkout` walk the
   same graph. Separate charts imply they could diverge; they can't.

In Mode B, each instance gets a stable hue (assigned at spawn,
persisted per id) — `#c-001` always cyan, `#c-047` always magenta. The
focused instance's active state has the strong amber ring; others
render as thinner rings with `◀── #c-047 ●` side-tag arrows.

In Mode C (4+ instances), per-instance overlays would saturate the
diagram. Switch to **cluster-by-state count badges** on each state
node (`●12` = 12 live instances in `:authing`); state background tint
scales with count.

### Mode C — Erlang-Observer pattern

The Mode C table is the closest peer for "thousands of instances, see
them grouped, click into one." Erlang's Observer Process tab is the
structural template:

- **Virtualised table** above the diagram — 8 rows visible at default
  density; scrollable; row icons per status (`●` focused, `◯` live,
  `╳` errored, `⊘` final, `⊕` recently spawned, `⊖` recently
  destroyed); sortable by any column.
- **Cluster-by-state count badges** on the topology — the aggregate
  read.
- **Aggregate row** (top of table or sticky footer):
  `Total: 47 live · 12 :authing · 28 :sending · 3 :error · 4 :ok`.
- **Shift-click multi-select** for divergence highlight: shift-click an
  instance → secondary focus (violet); diagram renders two highlights
  + arc strip splits into two stacked lanes; the **first event where
  the arcs diverge** auto-marks with `⚠` in both lanes. The diff that
  answers "I clicked Submit twice and got different results."

Shift-click a third instance → tertiary focus (cyan); cap at 4 lanes.

### Per-instance state-arc — v1 ships (rf2-nqw0v, Phase 5)

The mini-scrubber's companion overlay. A thin SVG strip mounted ABOVE
the chart that traces the focused instance's chronological
state-trajectory:

- **Origin** is the machine's initial state (`@idx 0`); each subsequent
  point is one outer or microstep transition for the focused instance.
- **Segments** fade between marker centres in the accent-violet palette
  (matches the Xray brand violet; cyan / amber are reserved for
  live-highlight / sim-mode respectively).
- **Per-marker tooltip** is the browser-native SVG `<title>` element —
  no Xray hover-tooltip machinery. Each title carries
  `#<idx> <from> → <to> (<event>) @<ms>` so the developer reads the
  trajectory by hovering markers in sequence.
- **Trim semantics tied to scrubber position.** When the mini-scrubber
  (below) is at `:present`, the arc renders the full trajectory; when
  scrubbed to `idx N`, the arc trims to `[0..N]` so the strip mirrors
  the chart's rewound state.

Pure-data algebra (`machine_inspector_arc_helpers.cljc`) is
JVM-runnable; the view module (`machine_inspector_arc.cljs`) is a
thin CLJS renderer mounted via the chart primitive's overlay slot.

### Per-instance mini-scrubber

When a machine instance is focused, an in-arc-strip mini-scrubber lets
the user rewind THAT instance without affecting the rest of Xray:

- **Global timeline** = L2 event list + ribbon `[◀ ▶ ⏭]` (every Xray
  surface rebinds).
- **Per-instance scrubber** = inline `◀ scrub ▶` widget in arc strip +
  the focused-instance highlight gets a `⏪` glyph appended (only the
  diagram's focused-instance highlight changes; other instances
  continue live).

This mini-scrubber is intra-tab content (inside the Machines tab); it
is NOT related to the (now-dead) bottom rail / global scrubber. The
global scrubber surface is the ribbon `[◀ ▶ ⏭]` cluster + the L2 event
list per [`018-Event-Spine.md`](018-Event-Spine.md) §6.

#### v1 ships — concrete widget shape (rf2-nqw0v, Phase 5)

The shipped mini-scrubber is a horizontal `<input type="range">`
beneath the chart (NOT the prose `◀ scrub ▶` widget — the spec text
above is the user-facing mental model; the v1 mechanics use a native
range input for keyboard-accessibility and OS-native drag semantics).

- **Slider write surface.** Dragging dispatches
  `:rf.xray/set-scrubber-position` into the per-slot reducer; the
  chart's active-state highlight overrides to the scrubbed-to state;
  the per-instance arc trims to `[0..idx]`.
- **Domain.** `[0, max-idx]` where `max-idx` is the last transition
  recorded for the focused instance.
- **"⏭ present" button** sits next to the slider — snaps the position
  back to head and re-engages live-tracking semantics. Equivalent to
  setting position = `max-idx` AND re-arming the head-follow flag.
- **Auto-flip to `:present` on max-idx drag.** Dragging the slider to
  the right-edge max value auto-flips position state to `:present` so
  head-tracking survives a future-tense scrub (vs. sticking at
  numeric `max-idx` and silently lagging the next live transition).

## Spec 005 actor lifecycle — full XState parity

Xray renders the supervision tree. The framework contract behind it:

- **Invoke auto-cleanup** — `:spawn`d actors are state-scoped; leaving
  the invoking state destroys the child. Stately/XState semantics.
- **Spawn explicit destroy** — `[:rf.machine/spawn]` returns an
  instance id; explicit `[:rf.machine/destroy <id>]` to clean up.
- **Parent-stop cascades** — destroying a parent cascades destroy to
  all children (supervision tree, Erlang OTP style).

Xray surfaces this in:

- **Parent/child relationship section** in metadata rail.
- **Spawn ancestry** in instance tab labels (`#c-001 ... by [:app/start]`).
- **`⊕`/`⊖` markers** in the arc strip at spawn/destroy moments.
- **Recent-deaths buffer** — 10s after destroy, the row red-fades and
  disappears; arc is preserved in `recently-destroyed` sub-list (last
  10 entries) for 30s; still reachable from time-travel.
- **`:spawn-all` inline children** — render as decorated rows beneath
  the invoking state, per Spec 005.

## Selection and switching

The panel header carries a machine picker — a dropdown over
`(rf/machines frame-id)`. The dropdown shows machine-id, current
state, and a tiny activity indicator (filled green if transitioned in
the last 5s; grey otherwise).

Switching the active frame via the ribbon (Layer 1) re-binds the
picker to that frame's machines. Machines spawned via
`:rf.machine/spawn` (dynamic actors) appear in the picker with their
gensym'd id; named addressing via `:system-id` is surfaced as a
parenthetical (e.g. `:gensym-42 (:auth/main)`).

## Transition history ribbon

A horizontal scrubbable list under the chart. Each entry is one
`:rf.machine/transition`:

```
[14:32:01 :login    → :authing ] [14:32:02 :authing  → :error  ]
[14:32:04 :error    → :idle    ] [14:32:11 :idle     → :login  ]
```

- **Click an entry** → chart rewinds to show the state pre-transition,
  with the inbound edge highlighted. The rewind is **view-only** (same
  passive-scrubbing rule as the global spine in
  [`018-Event-Spine.md`](018-Event-Spine.md) §6) — Xray does not call
  `restore-epoch` from this affordance.
- **Hover** → tooltip with the triggering event vector and guard
  result.
- **Microstep entries** (from `:rf.machine.microstep/transition`) are
  rendered slightly indented under their outer transition.

## Data sources

Per Spec 005 and Spec 009:

| Surface | Used for |
|---|---|
| `(rf/machines frame-id)` | Enumerate registered machines (drop-down in panel header). |
| `[:rf.runtime/machines :snapshots <id>]` slot in the **runtime-db partition** (EP-0001 rf2-vzld77) | Read current snapshot; deref drives the live-highlight. The host passes the snapshot's `:state` straight through as the chart's `:current-state`; for a **parallel** machine that `:state` is a region-map and the chart highlights **every** active region leaf simultaneously (parity gap G1; resolution via `chart.layout/highlight-ids` — see [machines-viz API §Parallel multi-active highlight](../../machines-viz/spec/API.md#parallel-multi-active-highlight-rf2-yoe6e-rf2-g2svr)). |
| `:rf.machine/transition` traces | Build the transition-history ribbon. |
| `:rf.machine.microstep/transition` traces | Microstep replay within an `:always`-driven cascade. |
| `:rf.machine.timer/scheduled` / `-fired` / `-stale-after` | Drive `:after` countdown rings. |
| `:rf.machine.spawn-all/*` traces | Render `:spawn-all` join state (started, all-completed, some-completed, any-failed). |
| `:rf.machine.spawn/spawned` (fx-substrate) · `:rf.machine.lifecycle/spawned` (registrar-substrate) / `:rf.machine/destroyed` · `:rf.machine.lifecycle/destroyed` | Render spawn/destroy lifecycle in the parent's chart. The inspector keys on the `:rf.machine.lifecycle/*` axis for "actor appeared/disappeared" and on the `:rf.machine.spawn/*` / `:rf.machine/*` axis when correlating the causing fx (per [009 §Two-axis machine observation](../../../spec/009-Instrumentation.md#two-axis-machine-observation--registrar-substrate-vs-fx-substrate)). |
| `:rf.machine/done` | Mark `:final?`-state entry, before the auto-destroy. |
| `:rf.machine/system-id-bound` / `-released` | Surface `:system-id` reverse-index activity in a sidebar. |
| `:rf.machine.history/restored` / `:rf.machine.history/recorded` | Render the history restore / record banner + the per-`:entry`-step `:source` chip — see [§History restore rendering](#history-restore-rendering-rf2-mle6e5) below. |
| Source-coord stamping | Every clickable element jumps to source. |

<!-- ============================================================ -->
<!--  HISTORY RESTORE RENDERING  (rf2-mle6e.5)                     -->
<!-- ============================================================ -->

## History restore rendering (rf2-mle6e.5)

History pseudo-states ([Spec 005 §History states](../../../spec/005-StateMachines.md#history-states-type-history--shallow--deep--default-target)) record a compound's last-active configuration on exit and restore it on re-entry. The two activity traces ([Spec 009 §History trace events](../../../spec/009-Instrumentation.md#history-trace-events-rfmachinehistory)) make the record/restore observable; Xray renders them so a viewer reads **why** a re-entry landed where it did rather than only `{from}→{to}`.

A history restore is **not** a separate cascade mechanism — it **is** the entry cascade whose target leaf came from `:rf/history` (Spec 009 §Composition with the entry cascade). So Xray composes the rendering, never duplicates it:

### Where it renders — the EVENT HANDLER machine cascade (Epoch panel)

The history surface rides the **EVENT HANDLER machine cascade** in the Epoch panel (the rf2-52u5n structured-cascade render — see [`021-Dynamic-Panel-Designs.md` §machine-cascade](021-Dynamic-Panel-Designs.md#machine-cascade-rf2-u69j7)), keyed to the macrostep's `:rf.machine/transition` row. Two additive renderings:

1. **The history banner** (above the structured cascade, under the `{from}→{to}` verb + logical-state delta). The Xray-brand-violet (informational, never the error/pink wash — a restore/record is benign observability, op-type `:rf.machine`) banner carries the headline a viewer reads BEFORE walking the per-level entry steps:
   - **restored** (`⟲`): `restored <compound-path> from <DEEP|SHALLOW> history · <restored-config> → <resolved-leaf>` on the `:recorded` path; `restored <compound-path> from DEFAULT (no recording) via :<fallback> → <resolved-leaf>` on the `:default` path (the compound was never exited, or the recorded path was dangling after a hot reload — Spec 005 §Dangling recorded paths).
   - **recorded** (`✎`): `history advanced <compound-path> from <prev-config> to <recorded-config>` on an overwrite; `history recorded <compound-path> = <recorded-config>` on the first-ever write (no `:prev-config`).

   A parallel macrostep that restores / records per region renders one banner line per record (the traces are region-qualified by `:compound-path` head segment).

2. **The per-`:entry`-step `:source` chip.** Each structured-cascade `:entry` step produced by a history restore additively carries `:source :recorded | :default` (Spec 009 line 291 — the only addition history makes to the rf2-n9f4z step shape; absent on every non-history step). Xray renders a small chip — **`from history`** (`:recorded`) or **`default`** (`:default`) — on those `:entry` step rows, so the viewer sees WHICH entry steps came from a history restore vs an ordinary `:initial` descent. The chip's `:source` value matches the banner's `:source`; the consumer reads the headline off the banner, then the per-level origin off the `:source`-tagged entry steps without re-deriving either.

### Inspectable `:rf/history` slot

The recorded configuration lives in the snapshot's `:rf/history` slot (`[:rf.runtime/machines :snapshots <id> :rf/history]` in runtime-db), keyed by the compound's region-qualified declaration path. It is an ordinary snapshot slot, so it renders in the **runtime-db view** through the standard edn-inspector — a viewer expands the machine's snapshot and reads the recorded config (keyword for shallow, leaf path for deep) the next restore will resolve. No bespoke panel chrome; `:rf/history` is EDN-clean (keywords + vectors-of-keywords) so it round-trips through the inspector like any other slot.

### Data contract

The pure projection (`panels/epoch/projection.cljc`) harvests the two ops with `history-restored-rows` / `history-recorded-rows` and stamps `:history-restored` / `:history-recorded` (keyed by `:machine-id`) onto the `:transition` cascade row via `attach-history-to-transition-rows`. The headline strings come from `panels/epoch/format.cljc` (`history-restored-headline` / `history-recorded-headline`). The focused-event Machine Inspector lens (`panels/machine_inspector_helpers.cljc` `project-focused-event-transitions`) attaches the same records per transition record so the lens surfaces the restore alongside the topology highlight. All are pure-data + JVM-tested.

## Source-coord integration

Every clickable element on the chart jumps to source:

| Element | Source surface |
|---|---|
| State node | The state-node's co-located `:source-coords` — `(get-in spec [:states <prefix-path> :source-coords])` — opens the state's registration in the editor. |
| Edge | The transition map's co-located `:source-coords` — `(get-in spec [:states <from> :on <event> :source-coords])` — opens the transition entry's source line. |
| Guard | The guard entry's co-located `:source-coords` — `(get-in spec [:guards <name> :source-coords])` — opens its definition. |
| Action | The action entry's co-located `:source-coords` — `(get-in spec [:actions <name> :source-coords])`. |

(Per rf2-npvsx + rf2-vqja2 the per-element guard/action coords are co-located on each `:guards` / `:actions` entry; the reference-site state-node / transition coords are co-located directly on each `:states`-tree map node. An inline-fn / keyword slot resolves to the nearest enclosing map node's `:source-coords`.)

Source coords are surfaced as copyable `file:line` chips; clicking
opens the file via the editor URL handler the user configured in
Settings → Source. See [`007-UX-IA.md`](007-UX-IA.md) §Editor protocol
matrix.

When the dispatch coord is missing (e.g., a synthetic dispatch from a
machine action), Xray falls back to the registered handler's source
coord with an inline `(?)` annotation that hovers a tooltip ("This
coord is the handler's; the dispatch was synthesised by `:auth/main`
at state `:authing`.")

## `:spawn-all` viz

When a state declares `:spawn-all`, the chart shows the N parallel
children as a horizontal row of mini-machines, each with their own
state. The join condition (`:all` / `:any` / `{:n N}` / `{:fn ...}`)
renders as a label below the row.

As children complete or fail, the row updates:

- A child reaching `:final?` colours green and marks `done?`.
- A child failing colours red and marks `failed?`.
- When the join resolves, the parent state advances; the children
  collapse to a summary (`3/5 completed, 2 cancelled`).

## Auto-pan

Large machines (many states, deep nesting) are wider than the panel.
Xray auto-pans on every transition so the active state stays in view.
The user can disable auto-pan via the panel header toggle (kept state
per-machine in localStorage).

## Performance

- **Chart re-layout** runs only on registry change (machine
  re-registered) or compound-state expand/collapse. Live transitions
  do not re-layout — they just update node highlights.
- **`:after` countdown rings** update at 60Hz only when the panel is
  visible; backgrounded panels pause the countdown render (the timer
  itself runs at framework-time).
- **Transition history** virtualises past 200 entries; older entries
  scroll into view but are not retained in DOM.

## Render + layout engine — xyflow over elkjs

The chart ships in **`tools/machines-viz/`** (per §Architectural posture
above); the namespace paths below are relative to
`tools/machines-viz/src/day8/re_frame2_machines_viz/`. Xray consumes the
`MachineChart` component **directly** — there is no Xray-side shim layer
(the former re-export shims were removed with the rf2-gpzb4 xyflow
migration).

Post-migration the chart has a single render stack: **`@xyflow/react`**
draws the canvas (custom Stately/xstate-style nodes + edges from
`chart/nodes.cljs` + `chart/edges.cljs`); **`elkjs`** runs as xyflow's
layout backend. There is no second "fallback" engine — the pre-migration
ELK+SVG renderer and its hand-rolled `layered-fallback` positioner are
gone (`chart/layout.cljc` survives only as the substrate-agnostic
definition→graph PARSER, not a positioner).

| Concern | Source ns | What it does |
|---|---|---|
| **Component + interop glue** | `chart.cljs` (`MachineChart`) | Reagent component; mounts `[:> ReactFlow ...]`, runs the elkjs pass, holds the positions atom. |
| **Graph parse** | `chart/layout.cljc` (`project-definition`, `highlight-id`, `highlight-ids`) | Pure CLJS data→data; JVM-runnable; definition → flat nodes + edges + per-node/per-edge metadata. `highlight-ids` resolves a whole snapshot `:state` (incl. a **parallel region-map**) to the **set** of active-leaf node-ids so the live highlight lights up **every** active region at once (parity gap G1; see [machines-viz API §Parallel multi-active highlight](../../machines-viz/spec/API.md#parallel-multi-active-highlight-rf2-yoe6e-rf2-g2svr)). Tests pin this without DOM / xyflow / elkjs. |
| **xyflow projection** | `chart/projection.cljc` (`xyflow-graph`, `->elk-children`) | Pure projector: parsed graph + elk positions → xyflow `:nodes` / `:edges` shape with per-node/per-edge `:data` payloads (highlight flags, labels, density). |
| **elkjs layout** | `chart.cljs` (`compute-layout!`) | Async elk.js pass over the parsed graph; merges positions back into xyflow nodes. |

### elkjs layout pass

`elkjs` is a direct `:require` in `chart.cljs`
(`["elkjs/lib/elk.bundled.js" :as elkjs]`) — a `devDependency` of
`implementation/package.json`, bundle-isolated from production (the
chart is dev-only: Xray preload + the static viewer page; the
`check-bundle-isolation.cjs` sentinel pins this). There is no lazy
`js/import` loader-atom; the import resolves at module load.

The pass is async + cached:

1. When the `[definition direction layout-options]` tuple changes,
   `MachineChart` calls `compute-layout!` with the parsed graph.
2. `compute-layout!` builds an elk.js input graph (`->elk-input`),
   calls `elk.layout` (returns a Promise), and on resolution maps the
   elk result → `{node-id {:x :y :width :height}}` into a positions
   `r/atom`. On rejection it calls back with nil and the previous
   positions are retained (no empty-chart flash; xyflow re-fits when
   the new positions land).
3. `xyflow-graph` (in `chart/projection.cljc`) merges those positions
   onto the xyflow node objects; xyflow's own `fitView` frames the
   result. xyflow owns zoom / pan / fit internally — hosts no longer
   manage a `{:scale :tx :ty}` viewport.

Canonical elk options (`default-elk-options`): `"elk.algorithm" "layered"`,
`"elk.direction" "DOWN"` (or `"RIGHT"` for `:lr`), spline edge routing,
and `"elk.hierarchyHandling" "INCLUDE_CHILDREN"` whenever the graph nests
(parallel regions per rf2-lkwev, or compound substates per rf2-54s5a).
Hosts may override via the `:layout-options` prop (merged over the
defaults).

## Share affordance

### Removed — rf2-nugvv (2026-06-04)

The Machine panel's `⤴ Share` button and the entire Xray share-URL
surface it fronted are **removed** (Mike, 2026-06-04). The Machine
panel was the modal's sole UI entry point, so the button, the modal
(`share_modal.cljs`), the share-URL + cascade-export infra
(`share.cljs`), the structured-export projection
(`export/cascade.cljc`), and the shell-root modal mount all went with
it. The per-cascade structured export (rf2-0us27) rode the same modal
and is removed as collateral — see the follow-up bead for re-homing it
if a host-agnostic export entry point is wanted.

The historical share-URL design (rf2-nqw0v, Phase 5) encoded the
visible inspection posture (`xray-share=1&machine=…&pos=…&tab=…`) into
a flat, human-legible query-string and restored it on load via
`maybe-restore-from-location!`. That restore hook was never wired into
mount, so the round-trip's read half was already inert before this
removal.

### Not built — Copy machine as PNG / SVG / Mermaid

Right-click on the machine chart (or use the panel header's `⋯`
overflow menu) surfaces a **Copy machine as…** sub-menu:

| Format | Output |
|---|---|
| **PNG** | Rasterised chart at 2x DPR, transparent background, current-state highlight included. Copied to clipboard as an image. |
| **SVG** | Vector chart with embedded fonts (so it renders identically when pasted into a doc or a Figma frame), current-state highlight included. Copied to clipboard as `image/svg+xml`. |
| **Mermaid text** | Markdown-friendly Mermaid block. Emitted by `day8.re-frame2-machines-viz.mermaid/emit` (lives in `tools/machines-viz/` per rf2-sqhqu). Copied as plain text. |

Inspired by Stately Visualizer's registry-style share. Use cases:
dropping a chart into a pull-request description, into a design doc,
into a Slack thread, or onto a whiteboard during a design discussion.

### No Stately compatibility

Xray is the canonical rendering surface for re-frame2 machines. There
is no `machine->xstate-json` export; no Stately Studio bridge. Stately
compat would constrain re-frame2 machine semantics to XState's subset;
we want to evolve freely.

### NOT a session export

This is **not** a session export. Lock #4 holds: Xray never
serialises the running trace stream, the epoch buffer, the app-db
history, or the conversation — those are session-local by design.

PNG / SVG / Mermaid serialise a **static machine definition** only —
topology + current-state hint (purely visual). The serialised form is
reproducible from the registry alone.

### Performance

- PNG / SVG rendering: client-side, derived from the same
  `MachineChart` (xyflow + elkjs) the inspector renders, at the chart's
  natural size. Sub-50ms for charts up to ~80 nodes.
- Mermaid emit: pure data → text projection; sub-1ms for typical
  machines.

### Accessibility

The PNG / SVG outputs include `<title>` and `<desc>` element (SVG) or
alt-text companion (PNG, as a sidecar `text/plain` payload on the
clipboard) summarising the machine: its id, its current state, and
the number of states / transitions.

## Empty state

When no machines are registered:

```
   No machines registered.
   Once your app registers a machine via reg-machine (Spec 005),
   it will appear here with:
   • Live state-chart highlighting
   • Transition history
   • :after countdown rings
   • UC1 simulation (Sim toggle)
   → Read about machine integration
```

For the **focused-event-targets-no-machine** empty state (Dynamic
mode, machines registered but the current event did not transition
any instance), see
[§Empty state — focused event does not target a state machine](#empty-state--focused-event-does-not-target-a-state-machine)
under the rf2-8og3k single-instance rule — that is a distinct
condition with a distinct verbatim placeholder.

## Accessibility

The state-chart is a graph, which is hard for screen readers. v1.0
ships **without** a chart alt-view; the alt-view is a v1.1 commitment.
Until then, the transition-history ribbon and the machines picker are
the accessible surfaces — both are text-heavy and reach the same data.

## The bug catalogue

Every Machines-tab feature is grounded in a concrete bug-class. Format per
[`000-Vision.md`](000-Vision.md) §Bug-driven, not feature-driven: bug class
→ example → insight → affordance.

### M.1 — Guard rejection (silent)

**Bug class:** An event fires; the chosen `:on` entry's guard returns
`false`; the snapshot doesn't move. The
`:rf.machine.transition/suppressed` trace is the only signal, buried in
the Trace firehose.

**Example bug:** You dispatched `:auth/cancel` on machine `:checkout` in
state `:authing`, expected a transition to `:idle`, nothing happened. The
event landed; the guard `:no-pending-payment?` returned `false` because
`:data.pending-payment` was `4232`.

**Insight Xray provides:** The transition's edge in the chart **flashes
red 400ms** with a tooltip naming the rejected event and the guard's
return value. In the metadata rail (right of chart), a "Recent guard
rejections (N)" section lists the rejected transitions; click-to-expand
shows the `:data` snapshot at evaluation time and the guard's source-coord.

**Affordance:** Guard-verdict overlay (M-C1). Three clicks from "huh,
nothing happened" to "ah, my guard is wrong."

**v1 ships:** the existing chart with no overlay. **Future:** the
red-flash overlay + the metadata rail's rejections section.

### M.2 — Stale `:after` timer / cancelled (per rf2-82a0u — unified `:rf.machine.timer/cancelled` event with `:reason :on-resolution` for the sub-resolve case)

**Bug class:** Wall-clock time-bound bug. The timer arms; the wall clock
advances; the timer expires; the snapshot doesn't move. Five
possibilities: (1) epoch stale because we exited and re-entered the state;
(2) guard returned false; (3) the synthetic event hit a `nil` `:on` entry;
(4) the timer fired in SSR mode and was suppressed; (5) subscription-driven
re-resolution cancelled the old timer in favour of a new one that hasn't
fired yet. The user can't tell from snapshots alone.

**Example bug:** You entered `:loading` with `:after 5000ms → :timeout`.
30 seconds passed. The snapshot is still `:loading`. The Trace shows both
`:rf.machine.timer/scheduled` (epoch 12) and `:rf.machine.timer/stale-after`
(epoch 13) — the state re-entered between schedule and fire.

**Insight Xray provides:** On each state with a live `:after` timer, the
node has a **thin countdown ring** with an animated arc representing
time-elapsed/total. Starts at 12 o'clock, rotates clockwise to fill.

- **Live mode:** the ring animates in real time.
- **Retro mode (scrubber-driven):** the ring is static at the
  elapsed-fraction the timer had reached at the focused-cascade's
  timestamp.
- **Stale timer** (epoch mismatch): the ring is rendered dashed/grey +
  tooltip "this timer was scheduled in a prior visit and is stale."
- **Cancelled-on-resolution** (sub-driven re-resolve): the old ring
  fades out (200ms); new ring fades in.

Click any ring → opens a timer detail popover: `:scheduled-at`,
`:delay`, `:epoch`, `:source` (`:literal` / `:sub` / `:timeout-config`
/ `:fn`).

**Affordance:** `:after` countdown rings + scrubber-aware retro-replay
(M-C2). Time IS the bug surface; the ring makes wall clock visible on a
tool that previously rendered only the snapshot. **Stately doesn't ship
this. Nobody does.**

**v1 ships:** no rings (transition-history ribbon only). **Future:** the
full countdown-ring system + retro-replay (Phase 2 per
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §6).

### M.3 — Cancellation cascade ambiguity

**Bug class:** Parent state exits; child's `:spawn` destroyed; child had
N in-flight HTTP requests; each aborts. The author sees a flurry of
`:rf.http/aborted-on-actor-destroy` traces in the Trace firehose and one
`:rf.machine.lifecycle/destroyed`. They cannot reconstruct which abort
belongs to which destroy.

**Example bug:** You clicked Cancel on a checkout flow. The Trace tab
shows 4 abort traces + 1 destroyed trace, scattered through 200 unrelated
rows. You can't tell which abort came from the cancel vs which were
independent.

**Insight Xray provides:** A **"Cancellation cascade" detail panel**
that appears inline in the Machines tab when the focused cascade triggered
a destroy. Header: "Parent `:checkout/main` exited `:processing` at
16:42:14. Destroyed 1 child actor (`:http/post#347`) and aborted 3
in-flight HTTP requests." Body: vertical waterfall showing the parent's
exit → destroy → per-HTTP-abort → final destroyed trace, indented under
its parent decision, each row with source-coord.

What was "a flurry of confusing trace lines" becomes "one decision and
its consequences, laid out vertically."

**Affordance:** Cancellation cascade visualiser (M-C3) — the Machines
tab's hero growth. Also a template for SSR cancellation cascade (when a
streaming SSR boundary times out, the same waterfall idiom shows what
cleanup ran).

**v1 ships:** scattered Trace rows. **Future:** the cascade-grouping
projection + the detail panel (Phase 3).

### M.4 — `:spawn-all` never joins

**Bug class:** N children spawned; join condition is `:all` (or `:any` /
`{:n M}` / `{:fn ...}`); some complete, some don't, the parent stays
stuck. Author wants per-child status, what each is doing right now, the
join-state map (`:done #{:cfg :flag} :failed #{} :resolved? false`), and
whether `:on-any-failed` is wired.

**Example bug:** You entered `:hydrating` which declares `:spawn-all
{:children {:cfg ... :flag ... :user ... :dash ...} :join :all}`. Two
children completed in <200ms; two are still "running" 2 seconds in. The
machine hasn't advanced.

**Insight Xray provides:** When the focused machine is in an
`:spawn-all`-bearing state, the metadata rail shows a **dedicated join
card**:

```
┌─ :spawn-all  ·  invoke-id [:hydrating :spawn-all] ─────────────┐
│ Join condition: :all                                              │
│ Resolved: ✗   (waiting for 2 of 4)                                │
│  ✓ :cfg     :load-config#1         done @ +124ms                  │
│  ✓ :flag    :load-feature-flags#2  done @ +89ms                   │
│  ⧖ :user    :load-user-profile#3   running 2.3s                   │
│  ⧖ :dash    :load-dashboards#4     running 2.4s                   │
│  :on-all-complete  → [:assets-loaded]                             │
│  :on-any-failed    → [:asset-load-failed]                         │
│  :cancel-on-decision?  true                                        │
└────────────────────────────────────────────────────────────────────┘
```

Each running child row: click → pivots to that child's machine instance.
Each done/failed: click → opens the per-child completion event.

**Affordance:** `:spawn-all` join inspector card (M-C4).

**v1 ships:** the `:spawn-all` viz row (children rendered inline; basic
`done?` / `failed?` colouring). **Future:** the full join inspector card
with click-to-pivot.

### M.5 — Per-instance "why am I stuck"

**Bug class:** Mode C debugging at scale. The user picks one instance
out of 47 (e.g. `:checkout#c-047` stuck in `:authing` for 30s); they
need the last few trace events filtered to THAT instance only. No
cross-instance chatter.

**Example bug:** Of 47 `:request/protocol` instances, `#c-047` is stuck
in `:authing` for 30s. The Mode C table tells you the state and the
duration but not WHY.

**Insight Xray provides:** Click an instance row → opens a per-instance
trace strip below the table showing the last 5 traces for THIS instance
only. The "32s in state" auto-callout flags suspiciously-long state
occupancy.

```
┌─ #c-047  ·  current state :authing  ·  in state for 32s ────────────────┐
│ Last 5 traces for this instance:                                          │
│ 16:42:14.103  :rf.machine.transition  :idle → :authing                   │
│ 16:42:14.108  :rf.machine.timer/scheduled  :after 30000ms epoch 4         │
│ 16:42:14.110  :rf.http/managed-issued  POST /api/auth/login              │
│ 16:42:14.140  :rf.http/handled  POST /api/auth/login → 200 (30ms)         │
│ 16:42:14.142  :rf.machine.transition/suppressed  :auth/ok                │
│                  guard :2fa-not-required? = FALSE                         │
│                  (data: {:requires-2fa? true})                            │
│                                                                            │
│ ⓘ Instance has not transitioned for 32s. 1 guard rejection pending.       │
└────────────────────────────────────────────────────────────────────────────┘
```

**Affordance:** Per-instance trace strip (M-C5). Phase 1 quick win.

### M.6 — Hierarchical state cascade (entry/exit along the LCA)

**Bug class:** A transition crosses multiple hierarchy levels. The
exit-cascade and entry-cascade interleave per Spec 005 §Entry/exit
cascading along the LCA. Today the chart highlights the new active state
but doesn't show the cascade.

**Insight Xray provides:** When a transition fires, the chart plays the
cascade in sequence:

1. The old leaf's `:exit` fires (node ring pulses red, then dims).
2. Walking up to LCA: each intermediate `:exit` (rings pulse in
   sequence; 80ms each).
3. The LCA's level (no action).
4. Walking down from LCA to new leaf: each intermediate `:entry` (rings
   pulse green; 80ms each).
5. The new leaf's `:entry` (ring settles to active-state amber/cyan).

Total: ~500ms for a 3-level cascade. Skippable via Settings → View →
"Reduced motion."

**Affordance:** Hierarchical state cascade highlighter (M-C6). Phase 5.
LCA semantics is the most subtle part of XState parity; the cascade
visualisation makes it obvious in ways no doc ever could.

### M.7 — Microstep loop visualiser

**Bug class:** `:always` fires; lands in a state with `:always`; fires
again; eventually hits the bounded-depth ceiling and emits
`:rf.machine.microstep/depth-exceeded`. The microstep chain wants to be
the diagnostic.

**Insight Xray provides:** When a focused cascade contains ≥3 microsteps,
render them as a **strip of micro-arrows** in the Machine tab header:

```
Microsteps (4 of max 12):
:idle ──always→ :checking ──always→ :checking-deep ──always→ :ready ──always→ :idle
                                                                                  ↑
                                                              (loop detected — see ⚠)
```

If the chain returns to a previously-seen state, mark it `⚠ loop
detected; will hit microstep depth limit in N more iterations`.

**Affordance:** Microstep loop visualiser (M-C7). Phase 5.

### M.8 — Path-walked transition explainer

**Bug class:** In a hierarchical machine with parent fallthrough, the
resolution rule is "deepest wins; parent fallthrough on miss." When a
child consumes an event the parent expected to handle, the author is
surprised.

**Insight Xray provides:** In the Epoch panel's "EFFECTS HANDLERS RAN"
section, add a sub-row for each `:rf.machine/transition` showing the
**path walked**:

```
:rf.machine/transition  :checkout
  Path walked:
    [:processing :paying]  :on {:pay/cancel ...}     ← MATCHED ✓
    [:processing]          :on {:pay/cancel ...}     ← not reached (deepest wins)
    [<top>]                :on {:pay/cancel ...}     ← not reached
```

**Affordance:** Path-walked sub-row (M-C8). Phase 1 quick win.

### M.9 — Spawn ancestry

**Bug class:** "Why is this instance still alive? Who's referencing it?"
A spawned actor should have been destroyed but wasn't. Could be:
`:system-id` reference held it alive; parent didn't fully exit
(hierarchical sticking); manual `:rf.machine/destroy` was never
dispatched; OR the destroy WAS dispatched but the snapshot's old state
references it.

**Insight Xray provides:** When an instance is focused, the metadata
rail shows the **spawn tree leading to it**:

```
Spawn ancestry:
  :app/start
   └── spawned :auth/main#m-001
        └── spawned :http/post#h-018  ← currently focused
```

Each ancestor click → focuses that instance. Bottom of card: "Will be
auto-destroyed when `:auth/main#m-001` exits `:authing`."

**Affordance:** Spawn-ancestry tree in metadata rail (M-C9). Phase 1.

### M.10 — Snapshot diff across transitions

**Bug class:** Each transition mutates the machine's snapshot
(`:state` + `:data`). Today the chart highlights state changes; `:data`
mutations are invisible unless the user opens the app-db diff.

**Insight Xray provides:** A **diff card below the chart** when a
transition fires: side-by-side `:data` before/after, action-attribution
per slot (`:data.retry-count incremented from 2 → 3 by action
:increment-retry`).

**Affordance:** Snapshot diff visualisation (M-C10). Phase 5. Needs
per-action attribution.

**Phase 4 — snapshot drill-in (rf2-lxvn6 · landed).** The
visibility-only half of M.10 lands first: the BEFORE / AFTER snapshot
maps for the focused transition render as collapsible trees via the
first-class edn-inspector widget (see
[`021-Dynamic-Panel-Designs.md` §10](021-Dynamic-Panel-Designs.md#10-shared-edn-inspector-renderer)).
The operator can drill into `:data` either side of the transition
without leaving the Machines tab. Phase 5 (D5=a, rf2-oqa60 phase 5)
adds the diff overlay on top of the same widget for the
action-attribution half.

## Cross-references

- [`018-Event-Spine.md`](018-Event-Spine.md) — Machines tab placement
  in the 4-layer chrome; spine-binding contract; isolation invariants.
- [`007-UX-IA.md`](007-UX-IA.md) — typography, colour tokens, editor
  protocol matrix.
- [`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2.1 —
  the bug-class catalogue this spec normalises.
- [Spec 005 — StateMachines](../../../spec/005-StateMachines.md) — the
  framework contract Xray renders.
- [Spec 009 — Instrumentation](../../../spec/009-Instrumentation.md) —
  the `:rf.machine/*` trace contract Xray consumes.
- [`014-Registry-Catalogue.md`](014-Registry-Catalogue.md) — the
  `:rf.xray/*` registry ids for the Machines tab.
- [`017-Test-Coverage-Matrix.md`](017-Test-Coverage-Matrix.md) — Sim
  mode + Mode A/B/C dynamic instance test rows.
- **Render regression harness (rf2-g27vv; runner-shaped rf2-kipb5;
  step-driver rewrite rf2-5sjbg).** The `machine_epochs` testbed (:8033) is
  the assertion-backed harness that pins this spec's cascade-render contract
  against reality — driven through the shared step-driver runner
  (`runner.core` — cursor in app-db `:step`, a `[:run-step n]` event, no
  Reagent atom), its step matrix walks the full feature × render-surface
  matrix (plain / entry-exit / guards / internal / fx /
  unhandled-no-op / root-`:on` resolution; parallel regions + history +
  member-level tag-set delta; `:always` microsteps; `:after` timer + cancel;
  spawn / `:final?` / `:on-done` / destroy lifecycle; `:*` wildcard throw;
  deep-compound LCA + self-transitions; `:history` placement-reject + live
  shallow/deep restore; the two xstate-render-divergence cases — MULTI-EVENT
  transition (`modal`: one edge `:open ──► :closed` reached on three events,
  events-as-nodes) + MULTI-BRANCH GUARDED fork (`gate`: `:gate/check` forks by
  a guarded candidate vector to `:high` / `:low` / unguarded `:rejected`),
  added under rf2-vilpfa). Each step is
  backed by a CLJS-unit assertion in
  `day8.re-frame2-xray.panels.epoch.machine-epochs-harness-cljs-test` (BOTH
  the machine outcome AND the cascade-render projection it lights up — cascade
  ORDER is read off the structured `:cascade` steps, not an app-level oracle),
  complemented by `…hard-machine-fidelity-cljs-test` for the HVAC HARD machine.
