# 023-Trace-Panel

The normative redesign spec for the Xray **Trace panel** (the `t` tab of
the 9-tab Dynamic L3 inventory; the Issues tab was removed per rf2-gbz39 Option (c)). This is the **Figma-handoff target**: it carries
the complete content + interaction contract for the Trace arc, with the
visual encoding (colour, styling) deliberately delegated to Figma (§8).

This doc supersedes the implemented Trace layout sketched in
[`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §5 as
the direction-setting design; 021 §5 documents what v1 ships, this doc
documents the destination. Authority for the surrounding look-and-feel is
the devtools reference `tools/xray/design-reference/xray_devtools_reference.cljs`.

Cross-refs:
- [`000-Vision.md`](./000-Vision.md) — the five canonical questions; the 9-tab Dynamic inventory
- [`007-UX-IA.md`](./007-UX-IA.md) — typography, density, keyboard maps (still load-bearing)
- [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) — the trace-bus + collector contract this panel reads
- [`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) — the sibling per-tab content contracts (Routing, Flows; the Issues tab was removed per rf2-gbz39 Option (c))
- [`018-Event-Spine.md`](./018-Event-Spine.md) — the `:rf.xray/focus` spine contract + 4-layer chrome
- [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) — §3 Views · §5 implemented Trace · §10 shared edn-inspector renderer
- [`003-Machine-Inspector.md`](./003-Machine-Inspector.md) — the Machine panel this panel's MACHINE rows jump to

Owner: tools/xray.

---

## §1 Purpose & scope

The Trace panel renders the **complete trace of a single epoch** — every trace operation the substrate emits during the epoch, in fire order, as a **single flat list of rows** (rf2-aqusw). It is the comprehensive lens on what happened, complementing:
- the **Epoch panel** (curated handling-pipeline narrative — [`021`](./021-Dynamic-Panel-Designs.md) §9.1), and
- the **Views panel** (the reactive subgraph as a graph — [`021`](./021-Dynamic-Panel-Designs.md) §3).

The Trace panel's contract is **completeness**: it must surface *every* op-family in the Spec-009 trace vocabulary, including lifecycle ops other panels omit (view unmounted, sub disposed, coeffect run, view mounted).

## §2 Layout (flat list — rf2-aqusw)

- **No top chrome.** No title bar, no filter bar, no summary/profile strip. The panel opens directly on the list.
- **A single flat list — no hierarchy.** Every op the focused epoch emitted is one row, in strict fire order (oldest-first). There is **no envelope** and **no phase-band nesting** — the 4-band hierarchy was replaced (rf2-aqusw) because it was hard to scan. The epoch-lifecycle ops (`:rf.epoch/*` — snapshotted / outcome / restore / replay / db-replaced) render as **ordinary rows** in the flat list (no longer bracketed in an envelope); `:rf.epoch/outcome` carries the consumer-facing summary `:ok` / `:blocked` / `:error` per [Spec 009 §`:rf.epoch/*`](../../../spec/009-Instrumentation.md#op-type-vocabulary) (rf2-18g1w / rf2-jppad).
- **Stage recovers the phase shape flatly (§3a).** The phase information the bands conveyed is recovered per-row by a **stage column** + a **colour-coded left edge** — each row names the Epoch-panel pipeline step it belongs to.
- **Chronological** — every row carries a Δt from the epoch's first op; ordering is strict fire order.
- **Show every op** — no default filtering or collapse (completeness-first).

## §3 Row anatomy

Columns: **Δt · stage · area badge · what-happened · target/detail · duration**.

- **Δt** — ms offset from the epoch's first op.
- **stage** — the Epoch-panel pipeline step this op belongs to (§3a): `DISPATCH` · `COEFFECT` · `EVENT HANDLER` · `FLOW` · `EFFECT HANDLERS` · `SUBSCRIPTIONS` · `VIEWS`. The label rides the step's own colour.
- **area badge** — a neutral text badge (no per-family colour): `EVENT` · `COEFFECT` · `DB` · `FX` · `FLOW` · `SUB` · `VIEW` · `MACHINE` · `ROUTING` · `RESOURCE` · `EPOCH` · `ERROR` · `WARNING`. (`RESOURCE` is the `:rf.resource/*` trace family — Spec 016 + [`024`](./024-Resources-Panel.md); the rows are emitted at op-type `:rf.event` so they are discriminated by NAMESPACE before the generic `EVENT` fallthrough.)
- **what-happened** — the per-area verb (§5).
- **target/detail** — the op's subject: event vector, `fx-id → arg`, `sub-id  old→new`, `view-id ← cause-sub`, route id, path, etc.
- **duration** — a number in **ms**, or `—` when the substrate supplies no timing (§6).
- **Colour-coded left edge** — a 3px left border in the row's **stage** colour (§3a). Error / warning rows override the stage colour with their severity colour so a failure stands out (§7).
- **Row click → open the edn-inspector on the raw trace MAP** inline (`:op-type` · `:operation` · `:tags` · timing · `:rf.trace/dispatch-id` …) via the first-class edn-inspector widget — the canonical CLJS-value renderer (the shared widget contract — [`021`](./021-Dynamic-Panel-Designs.md) §10, in particular §10.0 + §10.0.2 acceptance properties). The trace panel calls `[ei/edn-inspector value {:panel-id :rf.xray.trace/row-<id> …}]` directly (no facade hop); each row independently collapsible (the per-row `panel-id` qualifier scopes expansion state to that row, on top of the widget's own per-mount UUID).

## §3a Stage column + colour-coded left edge (rf2-aqusw)

The flat list recovers the phase information the removed bands conveyed by mapping each op to one of the **7 Epoch-panel pipeline steps** and surfacing it as both an explicit **stage column** (the label) and a **colour-coded left edge** (the at-a-glance colour). The label and colour are reused from the Epoch panel's own badge taxonomy (`panels.epoch.badge`) — **not** a parallel palette — so the Trace stage column + edge match the Epoch numbered cascade exactly. One step model, DRY.

The op → stage mapping (the coarse projection of the §3 area badge onto the 7 Epoch steps):

| Op (area / operation) | Epoch stage |
|---|---|
| `:rf.event/dispatched` | DISPATCH |
| `:rf.event/run-start` · `run-end` (handler body) | EVENT HANDLER |
| `:rf.cofx/*` (COEFFECT) | COEFFECT |
| `:rf.flow/*` (FLOW) | FLOW |
| `:rf.machine/*` (machine-as-handler) | EVENT HANDLER |
| `:rf.event/db-changed` (DB) | EFFECT HANDLERS |
| `:rf.event/db-noop` (DB) | EFFECT HANDLERS |
| `:rf.fx/*` (FX) | EFFECT HANDLERS |
| `:rf.route/*` (ROUTING) | EFFECT HANDLERS |
| `:rf.resource/*` (RESOURCE) | EFFECT HANDLERS |
| `:rf.sub/*` (SUB) | SUBSCRIPTIONS |
| `:rf.view/*` (VIEW) | VIEWS |
| `:rf.epoch/*` (EPOCH lifecycle) | DISPATCH (muted grey) |

Errors / warnings are cross-cutting (§7): the stage column still labels the step where the op chronologically occurred, but the left edge rides the severity colour.

## §5 What-happened verb taxonomy

| Area | Verbs |
|---|---|
| EVENT | dispatched · handler-ran |
| COEFFECT | run · skipped-on-platform |
| DB | changed |
| FX | one row per fx-id (`:dispatch`, `:http-xhrio`, …) · queued / landed · skipped-on-platform |
| FLOW | computed · cleared · failed · skipped |
| SUB | created · recalculated · ran-unchanged · cache-hit · disposed |
| VIEW | mounted · re-rendered · skipped · unmounted |
| MACHINE | created · transition · action-ran · guard-evaluated · after · spawned · spawn-cancelled · timer-scheduled · timer-fired · timer-stale · timer-cancelled · timer-skipped-on-server · done · finished · event-received · system-id-bound · system-id-released · destroyed (a `:spawn` wall-clock guard fires as `timer-fired` on the `:spawn`-bearing state's `:after` — the retired `spawn-timed-out` op, per Spec-009 / rf2-3y3y) |
| ROUTING | activated · deactivated · cleared · fragment-changed · navigation-blocked (no-match surfaces as the `WARNING :rf.warning/no-not-found-route` row, not a positive ROUTING op; a URL change is the dispatched event `:rf.event/dispatched [:rf.route/handle-url-change …]`, an EVENT row) |
| RESOURCE | registered · owner-attached · cache-hit · deduped · fetch-started · work-started · abort-requested · work-completed · succeeded · failed · refresh-failed · invalidated · refetch-decision · revalidate-scan · route-plan · owner-released · stale-scheduled · stale-fired · gc-scheduled · gc-fired · gc-skipped · poll-scheduled · poll-fired · removed · stale-suppressed · hydrated · hydrate-refetch · restored (the `:rf.resource/*` trace family, Spec 016 §Xray and AI tooling + [`024`](./024-Resources-Panel.md) §The `:rf.resource/*` trace family; the per-op semantic class + label live in `panels/resources_helpers.cljc`. The `:warning`-level `hydrate-clock-skew` / `restore-clock-skew` rows surface as `WARNING`, not a positive RESOURCE op — severity is cross-cutting §7. `:rf.resource/ensure` / `refetch` / `window-focused` are dispatched EVENT ids, surfacing as `:rf.event/dispatched` EVENT rows, not RESOURCE ops) |
| EPOCH | snapshotted · restored · db-replaced · replay-conflict · reset |

Registration ops (`:rf.flow/registered`, `:rf.route/registered`, `:rf.fx/reg-flow`, `:rf.machine.registrar/*`) are boot-time / out-of-epoch and are normally absent from a per-epoch arc.

## §6 Duration / timing

- Duration renders as a plain number in ms; `—` when no timing is available.
- Per-op timing is sourced from the **canonical per-area `:rf.<area>/elapsed-ms` tag** the substrate stamps on each op family's run-end / handled / rendered emit (dev builds):
  - **Views** — `:rf.view/elapsed-ms` ([Spec 009](../../../spec/009-Instrumentation.md) §281, `re-frame.views`).
  - **Fx** — `:rf.fx/elapsed-ms` (§241, `re-frame.fx`).
  - **Coeffects** — `:rf.cofx/elapsed-ms` (§243, `re-frame.cofx`).
  - **Subs** — `:rf.sub/elapsed-ms` (§251).
  - **Handler** — `:rf.event/elapsed-ms` (`re-frame.router` emit-run-end).
  - **Flows** — a bare `:elapsed-ms` tag (`re-frame.flows`).
- `—` remains the correct render only in the genuine no-timing cases: point-in-time emits that carry no elapsed (e.g. `:rf.epoch/snapshotted`, `:rf.event/dispatched`), and **production builds** where the timing capture is DCE-stripped.

## §7 Errors & warnings

Cross-cutting, **not a stage**. An `:rf.error/*` / `:rf.warning/*` op renders **inline at its chronological point** in the flat list, visually emphasised so failures stand out while scanning (and error vs warning distinguishable) — the row's left edge rides the severity colour over the stage colour; exact treatment delegated to Figma (§8). It carries the failing op's context (the `:rf.error/*` operation id + ex-data). The same diagnostics also populate the Issues panel ([`021`](./021-Dynamic-Panel-Designs.md) §8).

## §8 Visual encoding (delegated to Figma)

Colour and visual styling are intentionally **not specified here** — to be designed in Figma. The design must, however, make these dimensions visually distinguishable:

- **Stage** — each row names its Epoch pipeline step (§3a) in the **stage column** and a **colour-coded left edge** reusing the Epoch step palette (`panels.epoch.badge`). The stage column + edge must read together at a glance.
- **Op-family** — the area badge identifies the family (EVENT · COEFFECT · DB · FX · FLOW · SUB · VIEW · MACHINE · ROUTING · RESOURCE · EPOCH).
- **Outcome** — the what-happened state is legible at a glance, with at least these tiers distinguished: created/changed/recalculated/mounted · cache-hit/ran-unchanged/skipped · disposed/unmounted · queued/pending.
- **Errors / warnings** — clearly emphasised, distinct from normal ops and from each other (the edge rides the severity colour).

## §9 Canonical layout (flat list — rf2-aqusw)

```
+0.0  DISPATCH         EVENT     dispatched      [:counter-inc] from view↗       —
+0.0  EPOCH            EPOCH     snapshotted     #42 · frame :rf/default          —
+0.0  COEFFECT         COEFFECT  run             :now → #inst…                   0.1 ms
+0.1  EVENT HANDLER    EVENT     handler ran     reg-event                       0.2 ms
+0.3  FLOW             FLOW      computed        :totals → [:totals]             1.5 ms
+1.8  EFFECT HANDLERS  DB        changed         [:counter] 1 → 2 · [:totals] 42  —
+1.9  EFFECT HANDLERS  FX        :http-xhrio     GET /api/data (queued)           —
!2.5  EFFECT HANDLERS  ERROR     fx-handler-exc  :bad-fx "No such fx"             —
+2.6  SUBSCRIPTIONS    SUB       recalculated    :counter/value 1→2              0.3 ms
+2.9  SUBSCRIPTIONS    SUB       ran · unchanged :counter/parity                 0.1 ms
+3.0  SUBSCRIPTIONS    SUB       disposed        :cart/preview (no readers)       —
+3.1  VIEWS            VIEW      re-rendered     counter-display ← :counter/value 1.8 ms
+4.9  VIEWS            VIEW      unmounted       old-tooltip                      —
+4.9  VIEWS            VIEW      mounted         new-tooltip                     0.4 ms
+5.0  DISPATCH         EPOCH     outcome :ok     #42                              —
```

The stage column (DISPATCH / COEFFECT / EVENT HANDLER / FLOW / EFFECT HANDLERS / SUBSCRIPTIONS / VIEWS) + the colour-coded left edge recover, flatly, the phase shape the removed bands carried.

## §10 Data sources (grounding)

Per-op fields already on the epoch record / trace bus: views (`:rf.view/elapsed-ms`, `:triggered-by`/`:cause-subs`, `:mount?`/`:unmounted`/`:rendered`/`:skip`); subs (`:rf.sub/create`/`:run`/`:computed`/`:skip`/`:dispose`, `:value-changed?`); plus the full Spec-009 op vocabulary (event/cofx/db/fx/flow/machine/route/epoch/error/warning). The panel reads the epoch record's `:trace-events` (the focused-epoch scope resolved via `:rf.xray/focus` — [`018`](./018-Event-Spine.md), [`021`](./021-Dynamic-Panel-Designs.md) §5.2).

**Per-step RESULTS (the data the arc must show, not just that a step ran):**
- **Handler result** — its returned effects map: `:rf.event/fx` (incl. the handler's own `:db`) + `:rf.event/coeffects`. The EVENT "handler ran" row surfaces what the handler produced (its `:db` contribution *before* flows + the `:fx` it requested).
- **Flow result** — `:rf.flow/computed` carries `:result` (computed value), `:before` (prior value), `:input-values`. The FLOW row renders the value + the `[:path] before → after` delta directly (no db-snapshot walk needed).
- **Net db** — `:rf.event/db-changed` is the net installed diff (handler + flows combined). So the three contributions are distinguishable: handler-return (`:rf.event/fx` `:db`) → per-flow delta (`:rf.flow/computed` `:before`→`:result`) → net install (`:rf.event/db-changed`). No new trace fields required (rf2-u0zz5 must keep them distinct at the new emit points).

### §10.1 APP-DB CHANGES — per-path diff is PANEL-SIDE DERIVED

The `:rf.event/db-changed` trace event carries only `:event` + `:frame` — **no per-path diff payload on the event itself**. The per-path before→after rows the DB row presents (`+ [:path] new` / `~ [:path] old → new` / `- [:path]`) are **derived at render time** from the focused epoch record's `:db-before` / `:db-after` slots (already on every `:rf/epoch-record` per [`Spec 009`](../../../spec/009-Instrumentation.md) / [`spec/Spec-Schemas.md §:rf/epoch-record`](../../../spec/Spec-Schemas.md)). The derivation routes through the same structural-sharing engine the App-DB Diff tab and the Event-panel APP-DB CHANGES section consume (`app-db-diff-helpers/diff-paths`, [`004-App-DB-Diff.md §Changed-paths derivation`](./004-App-DB-Diff.md) — O(changed paths), not O(db size)). One engine, one shape; differences in rendering live in the view. When `db-before == db-after` the diff is `[]` and the DB row renders no per-path sub-list — the empty-diff case. Decision recorded on rf2-8q8i4 (panel-side derive, 2026-05-25); implementation tracked under rf2-b3zw2.

## §11 Implementation dependencies

1. **Timing instrumentation** (Spec 009) — run-start/run-end (or elapsed-ms) on sub / cofx / fx / flow / handler trace events, so §6 durations populate beyond views.
2. **Visual design** — colour palette + styling for the §8 dimensions, to be produced in Figma.

## §12 Child & nested epochs

The panel renders **one** epoch's trace; work that produces *other* epochs is shown by relationship, never absorbed:
- **Same-epoch work renders inline** — machine microsteps (`:always` / `:raise` cascades) and any synchronous sub-steps that share this epoch's `dispatch-id` are ordinary rows at their fire-order position (their stage column labels the step).
- **Separate epochs render as a `↗` link** on the originating op row — `:dispatch` / `:dispatch-later` fx, async responses (`:http-xhrio` → on-success/on-failure), routing `:rf.route/handle-url-change`, and machine `:after` / spawn / timer-fired. The row shows the spawning op + the child epoch id; clicking jumps the panel to that epoch. The child's own trace is **not** inlined (it has its own focused-epoch scope).
- **Parent breadcrumb** — the `:rf.epoch/snapshotted` row shows `◂ from #N :parent-event` when this epoch was spawned by another, so causality is traceable both directions.

## §13 Outcomes & short-circuit

- **No empty-band scaffolding (rf2-aqusw).** With the bands removed, there is no dimmed `(none)` placeholder — an op simply has a row or it does not. A no-op event renders only the ops it produced (its absence of EFFECT HANDLERS / SUBSCRIPTIONS / VIEWS rows is itself information). The stage column makes "which steps ran" legible without empty scaffolding.
- **Outcome** — the `:rf.epoch/outcome` op renders as an ordinary row carrying the `:outcome` tag (the consumer-facing summary the runtime emits paired with `:rf.epoch/snapshotted` per [Spec 009 §`:rf.epoch/*`](../../../spec/009-Instrumentation.md#op-type-vocabulary) — rf2-18g1w / rf2-jppad): `:ok` · `:blocked` (e.g. routing `:can-leave` rejected, drain depth-limit tripped, frame destroyed mid-drain) · `:error` (handler/fx threw — schema-reserved cause, not currently emitted) · plus a **platform tag** for server epochs. The runtime does the cause→summary projection; the panel reads the summary directly. This is a trace fact (not an aggregate), so it stays despite §2's "no summary."
- **Short-circuit** — on a throw (`:rf.error/*`) the list stops at the failing op (inline error row); later steps simply have no rows; outcome `:error`. No fabricated rows.

## §14 States & responsive

- **No epoch selected** — "Select an event to see its trace arc."
- **Expanded row** — inline EDN block (the edn-inspector on the raw trace map) between the row and the next.
- **Long list** — panel scrolls (show-all can be 100+ rows); the stage column on every row keeps the pipeline step labelled without sticky headers.
- **Truncation** — long target/detail values truncate with ellipsis in the row; full value via row-expand (EDN) or hover.
- **Responsive / docked width** — must stay usable at Xray's narrow docked width (≈420px). Δt + stage + area-badge + verb columns are fixed/compact; the **target/detail column is the flexible one and truncates first**; duration right-aligns and may hide under a width threshold. No horizontal scroll of the row grid, no wrapping that breaks the row rhythm.

## §15 Cross-panel navigation (v1 map)

Linkable rows carry a `↗` jump affordance to the relevant panel (restoring/extending the prior Trace behaviour). **v1 target map:**

| Trace row | Jumps to |
|---|---|
| SUB · VIEW | **Views** panel, that node selected in the signal graph ([`021`](./021-Dynamic-Panel-Designs.md) §3) |
| DB (path) | **App-db** panel, scrolled to that path ([`021`](./021-Dynamic-Panel-Designs.md) §4) |
| MACHINE | **Machine** panel, that machine's chart at the row's state ([`003`](./003-Machine-Inspector.md)) |
| ROUTING | **Routes** panel ([`021`](./021-Dynamic-Panel-Designs.md) §7) |
| RESOURCE | **Resources** panel, that resource's instance/timeline row ([`024`](./024-Resources-Panel.md)) |
| FX `:dispatch` / child | the child epoch's Trace arc (`↗`) |
| source coords (any op) | open-in-editor at `file:line` (`↗`) |

This is **interaction wiring, not visual** — Figma need only render a generic `↗` affordance on linkable rows; the destination map above is the behavioral spec and each target panel must accept a "focus on X" anchor (App-db scroll-to-path, Machine focus-at-state, …). The exact wiring is an implementation concern that can refine post-Figma without affecting the visual design.

## §16 Worked epoch shapes (so the design isn't over-fit to the counter)

> The band groupings (`▾ ② EVENT HANDLING …`) below are **illustrative
> only** — they show *which ops a given epoch produces*, not the layout.
> Post-rf2-aqusw the panel renders these ops as one flat list; the stage
> column on each row carries the step (EVENT HANDLING ops split across the
> EVENT HANDLER / COEFFECT / FLOW stages, EFFECTS ops onto EFFECT HANDLERS, etc.
> per §3a).

**Routing** `[:rf.route/navigate :app/settings]`:
```
○ EPOCH OPEN  :rf.route/navigate :app/settings
▾ ② EVENT HANDLING   COEFFECT current-route · handler ran · DB [:rf.runtime/routing :current] (or [:rf.runtime/routing :pending-navigation])
              ROUTING deactivated :app/home · ROUTING activated :app/settings
              — or — ROUTING navigation-blocked  (:can-leave)        → outcome :blocked
▾ ③ EFFECTS   FX :rf.nav/push-url /settings   ↗ child epoch #N (the URL change re-enters as EVENT :rf.route/handle-url-change)
▾ ④ REACTIVE  SUB :route/current recalculated · VIEW route-outlet re-rendered
● EPOCH CLOSE outcome :ok | :blocked
```
**Machine event** `[:ws/connection [:ws/connect]]`:
```
▾ ② EVENT HANDLING   MACHINE event-received :ws/connect · guard-evaluated :handshake-ok? ✓
              MACHINE transition :disconnected → [:active :connecting] · action-ran :bump-connections
              DB [:rf.runtime/machines :snapshots :ws/connection]            (microsteps → multiple transition rows)
▾ ③ EFFECTS   MACHINE timer-scheduled :after 250ms        ↗ future epoch when it fires
▾ ④ REACTIVE  SUB :ws/state recalculated · VIEW connection-status re-rendered
```
**SSR** (server):
```
○ EPOCH OPEN  :app/boot   (platform :server)
▾ ② EVENT HANDLING   COEFFECT skipped-on-platform · handler ran · DB changed
▾ ③ EFFECTS   FX skipped-on-platform :scroll-to · MACHINE timer-skipped-on-server
▾ ④ REACTIVE  VIEW rendered-to-string (one-shot — no re-render cascade)
● EPOCH CLOSE outcome :ok   (platform :server)
```
**Async** — `:http-xhrio` shows `queued`; the response lands as a separate epoch (`↗`). **Throw** — `ERROR handler-exception` inline at its fire-order point, list short-circuits, outcome `:error`. **No-op** — only DISPATCH + EVENT HANDLER rows; no EFFECT HANDLERS / SUBSCRIPTIONS / VIEWS rows (their absence is the signal — no empty-band scaffolding post-rf2-aqusw).

## §17 Design-system conformance

Type scale, spacing, density, iconography, base surfaces/borders MUST conform to the existing Xray devtools design system as captured in the authority reference `tools/xray/design-reference/xray_devtools_reference.cljs` (the `devtools-*` 13/12/11/10px scale; mono for EDN/code; the surfaces the other L4 panels use). Colour per §8 (Figma).

## Appendix A — Op-handling matrix (the "covers ALL trace" checklist)

Every Spec-009 trace operation → its row. The **Stage** column is the Epoch pipeline step each op maps to (§3a — the flat panel's stage column + colour-coded edge). `dur?` = a number sourced from the op family's per-area `:rf.<area>/elapsed-ms` tag in dev builds (§6), `—` for genuine point-in-time emits and production (DCE-stripped) builds. Errors/warnings collapse to one generic rule each; registration/boot ops are out of scope.

| Operation | Stage (§3a) | Area | Row label | Target / detail | Dur |
|---|---|---|---|---|---|
| `:rf.epoch/snapshotted` | DISPATCH | EPOCH | snapshotted | frame · snapshot | — |
| `:rf.epoch/outcome` | DISPATCH | EPOCH | outcome `:ok/:blocked/:error` | outcome (+platform) | — |
| `:rf.epoch/restored` · `db-replaced` · `replay-conflict` · `reset-*` | DISPATCH | EPOCH | restored / db-replaced / replay-conflict / reset | reason | — |
| `:rf.event/dispatched` | DISPATCH | EVENT | dispatched | event-vector + origin | — |
| `:rf.cofx` (inject) | COEFFECT | COEFFECT | run | cofx-id → value | dur? |
| `:rf.cofx/skipped-on-platform` | COEFFECT | COEFFECT | skipped-on-platform | cofx-id | — |
| `:rf.event/run-start`+`run-end` | EVENT HANDLER | EVENT | handler ran | handler flavour (`:rf.event/sync?` flag if dispatch-sync) | dur (run-end−run-start) |
| `:rf.event/db-changed` | EFFECT HANDLERS | DB | changed | path old → new | — |
| `:rf.event/db-noop` | EFFECT HANDLERS | DB | unchanged | returned unchanged db — nothing committed | — |
| `:rf.machine/event-received` | EVENT HANDLER | MACHINE | event-received | event | — |
| `:rf.machine/guard-evaluated` | EVENT HANDLER | MACHINE | guard-evaluated | guard-id ✓/✗ | — |
| `:rf.machine.microstep/transition` · `:rf.machine/transition` | EVENT HANDLER | MACHINE | transition (×microsteps) | from → to | — |
| `:rf.machine/action-ran` | EVENT HANDLER | MACHINE | action-ran | action-id | dur? |
| `:rf.machine.lifecycle/created` | EVENT HANDLER | MACHINE | created | machine-id | — |
| `:rf.machine.lifecycle/spawned` | EFFECT HANDLERS | MACHINE | spawned (registrar-substrate — actor snapshot installed; partner of the fx-substrate `:rf.machine.spawn/spawned` below) | machine-id · spawned-id | — (↗ child) |
| `:rf.machine/system-id-bound` · `system-id-released` | EVENT HANDLER | MACHINE | system-id-bound/released | system-id | — |
| `:rf.machine/snapshot-updated` | EVENT HANDLER | MACHINE | snapshot-updated | (or fold into DB) | — |
| `:rf.fx/handled` (per fx-id) | EFFECT HANDLERS | FX | `<fx-id>` | fx-id → arg · queued/landed | dur? |
| `:rf.fx/do-fx` | EFFECT HANDLERS | FX | (fx batch; usually elided to per-fx rows) | — | — |
| `:rf.fx/override-applied` | EFFECT HANDLERS | FX | override-applied | fx-id | — |
| `:rf.fx/skipped-on-platform` | EFFECT HANDLERS | FX | skipped-on-platform | fx-id | — |
| `:rf.flow/computed` | FLOW | FLOW | computed | flow-id → path | dur? |
| `:rf.flow/cleared` · `failed` · `skip` | FLOW | FLOW | cleared / failed / skipped | flow-id | — |
| `:rf.route/activated` · `deactivated` · `cleared` · `fragment-changed` | EFFECT HANDLERS | ROUTING | activated / deactivated / cleared / fragment-changed | route-id / fragment | — |
| `:rf.route/navigation-blocked` | EFFECT HANDLERS | ROUTING | navigation-blocked | route-id (guard) | — (→ outcome :blocked) |
| `:rf.warning/no-not-found-route` | inline (its stage) | WARNING | no-not-found-route | url (unmatched, no `:rf.route/not-found` route registered) | — |
| `:rf.event/dispatched [:rf.route/handle-url-change …]` | DISPATCH | EVENT | dispatched | url (the URL-change EVENT — **not** a standalone trace op; it rides the `:rf.event/dispatched` row above) | — (↗ child) |
| `:rf.machine.timer/scheduled` | EFFECT HANDLERS | MACHINE | timer-scheduled | delay · state | — (↗ future epoch) |
| `:rf.machine.timer/fired` | EFFECT HANDLERS | MACHINE | timer-fired | delay · state | — |
| `:rf.machine.timer/stale-after` · `cancelled` (rf2-82a0u — unified, `:reason` discriminates) | EFFECT HANDLERS | MACHINE | timer-stale / timer-cancelled | state | — |
| `:rf.machine.timer/skipped-on-server` | EFFECT HANDLERS | MACHINE | timer-skipped-on-server | state | — |
| `:rf.machine.spawn/spawned` · `:rf.machine.spawn/cancelled-on-join-resolution` · `:rf.machine.spawn-all/*` | EFFECT HANDLERS | MACHINE | spawned / spawn-cancelled / spawn-all-started/completed/failed | invoke-id | — (↗ child) |
| `:rf.machine/after` · `done` · `finished` | EFFECT HANDLERS | MACHINE | after / done / finished | delay / output | — |
| `:rf.sub/create` | SUBSCRIPTIONS | SUB | created | sub-id | — |
| `:rf.sub/run`+`computed` (value-changed? ✓) | SUBSCRIPTIONS | SUB | recalculated | sub-id  old → new | dur? |
| `:rf.sub/run`+`computed` (value-changed? ✗) | SUBSCRIPTIONS | SUB | ran-unchanged | sub-id | dur? |
| `:rf.sub/skip` · `skipped` | SUBSCRIPTIONS | SUB | cache-hit | sub-id | — |
| `:rf.sub/dispose` | SUBSCRIPTIONS | SUB | disposed | sub-id · query-v · `:reason` (closed set `:no-more-derefers / :hot-reload / :cache-clear`) · frame | — |
| `:rf.view/render`+`rendered` (mount? ✗) | VIEWS | VIEW | re-rendered | view-id ← cause-sub | `elapsed-ms` |
| `:rf.view/render` (mount? ✓) | VIEWS | VIEW | mounted | view-id | `elapsed-ms` |
| `:rf.view/skip` | VIEWS | VIEW | skipped | view-id | — |
| `:rf.view/unmounted` | VIEWS | VIEW | unmounted | view-id | — |
| `:rf.view/dropped-after` · `rendered-cap-reached` | VIEWS | VIEW | dropped / cap-reached | view-id | — |
| **`:rf.error/*`** (any) | inline (its stage) | ERROR | the `operation` id | ex-data | — |
| **`:rf.warning/*`** (any) | inline (its stage) | WARNING | the `operation` id | context | — |
| **Registration/boot** — `:rf.fx/reg-flow` · `:rf.fx/registered-platforms` · `:rf.cofx/registered-platforms` · `:rf.flow/registered` · `:rf.route/registered` · `:rf.machine.registrar/*` | — | — | OUT OF SCOPE (boot-time, not per-epoch) | — | — |

> Generic rules: any `:rf.error/*` → ERROR row (emphasised, inline, → Issues panel); any `:rf.warning/*` → WARNING row (inline, → Issues). The implementer cross-checks this matrix against the live trace-emit sites; new ops get a row before shipping (the completeness contract, §1).
