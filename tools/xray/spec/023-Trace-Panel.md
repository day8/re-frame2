# 023-Trace-Panel

The normative redesign spec for the Xray **Trace panel** (the `t` tab of
the 7-tab L3 inventory). This is the **Figma-handoff target**: it carries
the complete content + interaction contract for the Trace arc, with the
visual encoding (colour, styling) deliberately delegated to Figma (§8).

This doc supersedes the implemented Trace layout sketched in
[`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) §5 as
the direction-setting design; 021 §5 documents what v1 ships, this doc
documents the destination. Authority for the surrounding look-and-feel is
the devtools reference `tools/xray/design-reference/xray_devtools_reference.cljs`.

Cross-refs:
- [`000-Vision.md`](./000-Vision.md) — the five canonical questions; the 7-tab inventory
- [`007-UX-IA.md`](./007-UX-IA.md) — typography, density, keyboard maps (still load-bearing)
- [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) — the trace-bus + collector contract this panel reads
- [`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) — the sibling per-tab content contracts (Issues, Routing, Flows)
- [`018-Event-Spine.md`](./018-Event-Spine.md) — the `:rf.xray/focus` spine contract + 4-layer chrome
- [`021-Dynamic-Panel-Designs.md`](./021-Dynamic-Panel-Designs.md) — §3 Views · §5 implemented Trace · §10 shared data-display renderer
- [`003-Machine-Inspector.md`](./003-Machine-Inspector.md) — the Machine panel this panel's MACHINE rows jump to

Owner: tools/xray.

---

## §1 Purpose & scope

The Trace panel renders the **complete trace arc of a single epoch** — every trace operation the substrate emits during the epoch, in fire order, organised by the epoch's phase shape. It is the comprehensive lens on what happened, complementing:
- the **Event panel** (curated handling-pipeline narrative — [`021`](./021-Dynamic-Panel-Designs.md) §2), and
- the **Views panel** (the reactive subgraph as a graph — [`021`](./021-Dynamic-Panel-Designs.md) §3).

The Trace panel's contract is **completeness**: it must surface *every* op-family in the Spec-009 trace vocabulary, including lifecycle ops other panels omit (view unmounted, sub disposed, coeffect run, view mounted).

## §2 Layout

- **No top chrome.** No title bar, no filter bar, no summary/profile strip. The panel opens directly on the arc.
- **Arc envelope.** An `EPOCH OPEN` row and an `EPOCH CLOSE` row bracket the arc and carry the epoch-lifecycle ops (`:rf.epoch/*`): open shows epoch id · event · frame · `snapshotted` (from `:rf.epoch/snapshotted`); close shows `:rf.epoch/outcome` (the consumer-facing summary `:ok` / `:blocked` / `:error` per [Spec 009 §`:rf.epoch/*`](../../../spec/009-Instrumentation.md#op-type-vocabulary) — rf2-18g1w / rf2-jppad). Restore/replay/db-replaced lifecycle ops, when they occur, render in the envelope.
- **Four phase bands** (collapsible), in arc order:
  - **① DISPATCH** — the event dispatched (trigger + origin).
  - **② EVENT HANDLING** — coeffects (injected inputs) → handler ran → **flows** (transform the pending `:db`, right after the handler per rf2-u0zz5) → db changed (net, at install). (Interceptors are not separately traced; before-interceptor injection surfaces as COEFFECT rows, after-interceptor effects as the FX/DB rows they produce.)
  - **③ EFFECTS / FX** — fx handlers (db install + fx execution; flows are NOT here — they run in ② after the handler).
  - **④ REACTIVE RENDERING** — the reactive flush: subscriptions → views.
- **Chronological within and across bands** — every row carries a Δt from epoch open; ordering is strict fire order.
- **Show every op** — no default filtering or collapse (completeness-first).

## §3 Row anatomy

Columns: **Δt · area badge · what-happened · target/detail · duration**.

- **Δt** — ms offset from `EPOCH OPEN`.
- **area badge** — a neutral text badge (no per-family colour): `EVENT` · `COEFFECT` · `DB` · `FX` · `FLOW` · `SUB` · `VIEW` · `MACHINE` · `ROUTING` · `EPOCH` · `ERROR` · `WARNING`.
- **what-happened** — the per-area verb (§5).
- **target/detail** — the op's subject: event vector, `fx-id → arg`, `sub-id  old→new`, `view-id ← cause-sub`, route id, path, etc.
- **duration** — a number in **ms**, or `—` when the substrate supplies no timing (§6).
- **Row click → expand** the full raw trace-event EDN inline (`:op-type` · `:operation` · `:tags` · timing · `:rf.trace/dispatch-id` …) via the first-class data-display widget — the canonical CLJS-value renderer (the shared widget contract — [`021`](./021-Dynamic-Panel-Designs.md) §10, in particular §10.0 + §10.0.2 acceptance properties). The trace panel calls `[dd/data-display value {:panel-id :rf.xray.trace/row-<id> …}]` directly (no facade hop); each row independently collapsible (the per-row `panel-id` qualifier scopes expansion state to that row, on top of the widget's own per-mount UUID).

## §4 Phase → op-family placement

| Phase | Op-families |
|---|---|
| envelope | `:rf.epoch/*` (open/close/restore/replay/db-replaced) |
| ① DISPATCH | `:rf.event/dispatched` |
| ② EVENT HANDLING | `:rf.cofx/*` · `:rf.event/run-start`·`run-end` · **`:rf.flow/*`** (right after handler) · `:rf.event/db-changed` · machine-as-handler transitions (`:rf.machine/*`, `:rf.machine.microstep/*`). Interceptors not separately traced. |
| ③ EFFECTS / FX | `:rf.fx/*` (one row per fx-id) · machine `:after`/spawn/timer effects (`:rf.machine.timer/*`, `:rf.machine.spawn*/*`, lifecycle) · routing nav (`:rf.route/*`) |
| ④ REACTIVE RENDERING | `:rf.sub/*` · `:rf.view/*` |

Errors & warnings are cross-cutting — see §7. Child epochs spawned by a `:dispatch` fx link/nest from their originating fx row (§12).

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
| EPOCH | snapshotted · restored · db-replaced · replay-conflict · reset |

Registration ops (`:rf.flow/registered`, `:rf.route/registered`, `:rf.fx/reg-flow`, `:rf.machine.registrar/*`) are boot-time / out-of-epoch and are normally absent from a per-epoch arc.

## §6 Duration / timing

- Duration renders as a plain number in ms; `—` when no timing is available.
- **Views** — sourced from `:rf.view/elapsed-ms` (available today).
- **Subs · coeffects · fx · flows · handler** — require run-start/run-end timing on the trace events. This is a Spec-009 instrumentation dependency; until present, these ops render `—`. (Implementation should add the timing capture so the arc is fully profiled.)

## §7 Errors & warnings

Cross-cutting, **not a phase**. An `:rf.error/*` / `:rf.warning/*` op renders **inline at its chronological point** within whatever band it occurred in, visually emphasised so failures stand out while scanning (and error vs warning distinguishable) — exact treatment delegated to Figma (§8). It carries the failing op's context (the `:rf.error/*` operation id + ex-data). The same diagnostics also populate the Issues panel ([`021`](./021-Dynamic-Panel-Designs.md) §8).

## §8 Visual encoding (delegated to Figma)

Colour and visual styling are intentionally **not specified here** — to be designed in Figma. The design must, however, make these dimensions visually distinguishable:

- **Phase band** — each of the 4 bands (plus the epoch envelope) reads as a distinct segment of the arc (e.g. a band header + left rail).
- **Op-family** — the area badge identifies the family (EVENT · COEFFECT · DB · FX · FLOW · SUB · VIEW · MACHINE · ROUTING · EPOCH).
- **Outcome** — the what-happened state is legible at a glance, with at least these tiers distinguished: created/changed/recalculated/mounted · cache-hit/ran-unchanged/skipped · disposed/unmounted · queued/pending.
- **Errors / warnings** — clearly emphasised, distinct from normal ops and from each other.

## §9 Canonical layout

```
○ EPOCH OPEN   #42 :counter-inc · frame :rf/default · snapshotted
▾ ① DISPATCH
    +0.0  EVENT     dispatched      [:counter-inc] from view↗           —
▾ ② EVENT HANDLING
    +0.0  COEFFECT  run             :now → #inst…                      0.1 ms
    +0.1  EVENT     handler ran     reg-event-db                       0.2 ms
    +0.3  FLOW      computed        :totals → [:totals]                1.5 ms
    +1.8  DB        changed         [:counter] 1 → 2 · [:totals] 42      —
▾ ③ EFFECTS / FX
    +1.9  FX        :http-xhrio     GET /api/data (queued)              —
    !2.5  ERROR     fx-handler-exc  :bad-fx "No such fx"               —
▾ ④ REACTIVE RENDERING
    +2.6  SUB       recalculated    :counter/value 1→2                 0.3 ms
    +2.9  SUB       ran · unchanged :counter/parity                    0.1 ms
    +3.0  SUB       disposed        :cart/preview (no readers)          —
    +3.1  VIEW      re-rendered     counter-display ← :counter/value   1.8 ms
    +4.9  VIEW      unmounted       old-tooltip                         —
    +4.9  VIEW      mounted         new-tooltip                        0.4 ms
● EPOCH CLOSE  outcome :ok
```

## §10 Data sources (grounding)

Per-op fields already on the epoch record / trace bus: views (`:rf.view/elapsed-ms`, `:triggered-by`/`:cause-subs`, `:mount?`/`:unmounted`/`:rendered`/`:skip`); subs (`:rf.sub/create`/`:run`/`:computed`/`:skip`/`:disposed`, `:value-changed?`); plus the full Spec-009 op vocabulary (event/cofx/db/fx/flow/machine/route/epoch/error/warning). The panel reads the epoch record's `:trace-events` (the focused-epoch scope resolved via `:rf.xray/focus` — [`018`](./018-Event-Spine.md), [`021`](./021-Dynamic-Panel-Designs.md) §5.2).

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

The panel renders **one** epoch's arc; work that produces *other* epochs is shown by relationship, never absorbed:
- **Same-epoch work renders inline** — machine microsteps (`:always` / `:raise` cascades) and any synchronous sub-steps that share this epoch's `dispatch-id` are ordinary rows in their phase.
- **Separate epochs render as a `↗` link** on the originating op row — `:dispatch` / `:dispatch-later` fx, async responses (`:http-xhrio` → on-success/on-failure), routing `:rf.route/handle-url-change`, and machine `:after` / spawn / timer-fired. The row shows the spawning op + the child epoch id; clicking jumps the panel to that epoch. The child's own arc is **not** inlined (it has its own envelope).
- **Parent breadcrumb** — EPOCH OPEN shows `◂ from #N :parent-event` when this epoch was spawned by another, so causality is traceable both directions.

## §13 Empty bands, outcomes & short-circuit

- **Empty phase bands** render their header dimmed with `(none)` — never hidden — so the 4-phase shape is always legible and the absence is itself information (a no-op event has empty ③④; a blocked navigation has empty ④).
- **Outcome** — EPOCH CLOSE reads the `:outcome` tag off the `:rf.epoch/outcome` trace op (the consumer-facing summary the runtime emits paired with `:rf.epoch/snapshotted` per [Spec 009 §`:rf.epoch/*`](../../../spec/009-Instrumentation.md#op-type-vocabulary) — rf2-18g1w / rf2-jppad): `:ok` · `:blocked` (e.g. routing `:can-leave` rejected, drain depth-limit tripped, frame destroyed mid-drain) · `:error` (handler/fx threw — schema-reserved cause, not currently emitted) · plus a **platform tag** for server epochs. The runtime does the cause→summary projection; the panel reads the summary directly. This is a trace fact (not an aggregate), so it stays despite §2's "no summary."
- **Short-circuit** — on a throw (`:rf.error/*`) the arc stops at the failing op (inline error row); later phases simply have no ops; outcome `:error`. No fabricated rows.

## §14 States & responsive

- **No epoch selected** — "Select an event to see its trace arc."
- **Collapsed band** — header + per-band op count.
- **Expanded row** — inline EDN block (data-inspector) between the row and the next.
- **Long arc** — panel scrolls; **band headers are sticky** so the current phase stays labelled (show-all can be 100+ rows).
- **Truncation** — long target/detail values truncate with ellipsis in the row; full value via row-expand (EDN) or hover.
- **Responsive / docked width** — must stay usable at Xray's narrow docked width (≈420px). Δt + area-badge + verb columns are fixed/compact; the **target/detail column is the flexible one and truncates first**; duration right-aligns and may hide under a width threshold. No horizontal scroll of the row grid, no wrapping that breaks the band rhythm.

## §15 Cross-panel navigation (v1 map)

Linkable rows carry a `↗` jump affordance to the relevant panel (restoring/extending the prior Trace behaviour). **v1 target map:**

| Trace row | Jumps to |
|---|---|
| SUB · VIEW | **Views** panel, that node selected in the signal graph ([`021`](./021-Dynamic-Panel-Designs.md) §3) |
| DB (path) | **App-db** panel, scrolled to that path ([`021`](./021-Dynamic-Panel-Designs.md) §4) |
| MACHINE | **Machine** panel, that machine's chart at the row's state ([`003`](./003-Machine-Inspector.md)) |
| ROUTING | **Routes** panel ([`021`](./021-Dynamic-Panel-Designs.md) §7) |
| FX `:dispatch` / child | the child epoch's Trace arc (`↗`) |
| source coords (any op) | open-in-editor at `file:line` (`↗`) |

This is **interaction wiring, not visual** — Figma need only render a generic `↗` affordance on linkable rows; the destination map above is the behavioral spec and each target panel must accept a "focus on X" anchor (App-db scroll-to-path, Machine focus-at-state, …). The exact wiring is an implementation concern that can refine post-Figma without affecting the visual design.

## §16 Worked epoch shapes (so the design isn't over-fit to the counter)

**Routing** `[:rf.route/navigate :app/settings]`:
```
○ EPOCH OPEN  :rf.route/navigate :app/settings
▾ ② EVENT HANDLING   COEFFECT current-route · handler ran · DB [:rf/route] (or [:rf/pending-navigation])
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
              DB [:rf/machines :ws/connection]            (microsteps → multiple transition rows)
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
**Async** — `:http-xhrio` shows `queued`; the response lands as a separate epoch (`↗`). **Throw** — `ERROR handler-exception` inline in ②, arc short-circuits, outcome `:error`. **No-op** — only ② populated; ③④ dimmed `(none)`.

## §17 Design-system conformance

Type scale, spacing, density, iconography, base surfaces/borders MUST conform to the existing Xray devtools design system as captured in the authority reference `tools/xray/design-reference/xray_devtools_reference.cljs` (the `devtools-*` 13/12/11/10px scale; mono for EDN/code; the surfaces the other L4 panels use). Colour per §8 (Figma).

## Appendix A — Op-handling matrix (the "covers ALL trace" checklist)

Every Spec-009 trace operation → its row. `dur?` = number once timing instrumentation lands (§6), else `—`. Errors/warnings collapse to one generic rule each; registration/boot ops are out of scope.

| Operation | Phase | Area | Row label | Target / detail | Dur |
|---|---|---|---|---|---|
| `:rf.epoch/snapshotted` | envelope | EPOCH | snapshotted | frame · snapshot | — |
| `:rf.epoch/outcome` | envelope | EPOCH | outcome `:ok/:blocked/:error` | outcome (+platform) | — |
| `:rf.epoch/restored` · `db-replaced` · `replay-conflict` · `reset-*` | envelope | EPOCH | restored / db-replaced / replay-conflict / reset | reason | — |
| `:rf.event/dispatched` | ① | EVENT | dispatched | event-vector + origin | — |
| `:rf.cofx` (inject) | ② | COEFFECT | run | cofx-id → value | dur? |
| `:rf.cofx/skipped-on-platform` | ② | COEFFECT | skipped-on-platform | cofx-id | — |
| `:rf.event/run-start`+`run-end` | ② | EVENT | handler ran | handler flavour (`:rf.event/sync?` flag if dispatch-sync) | dur (run-end−run-start) |
| `:rf.event/db-changed` | ② | DB | changed | path old → new | — |
| `:rf.machine/event-received` | ② | MACHINE | event-received | event | — |
| `:rf.machine/guard-evaluated` | ② | MACHINE | guard-evaluated | guard-id ✓/✗ | — |
| `:rf.machine.microstep/transition` · `:rf.machine/transition` | ② | MACHINE | transition (×microsteps) | from → to | — |
| `:rf.machine/action-ran` | ② | MACHINE | action-ran | action-id | dur? |
| `:rf.machine.lifecycle/created` | ② | MACHINE | created | machine-id | — |
| `:rf.machine/system-id-bound` · `system-id-released` | ② | MACHINE | system-id-bound/released | system-id | — |
| `:rf.machine/snapshot-updated` | ② | MACHINE | snapshot-updated | (or fold into DB) | — |
| `:rf.fx/handled` (per fx-id) | ③ | FX | `<fx-id>` | fx-id → arg · queued/landed | dur? |
| `:rf.fx/do-fx` | ③ | FX | (fx batch; usually elided to per-fx rows) | — | — |
| `:rf.fx/override-applied` | ③ | FX | override-applied | fx-id | — |
| `:rf.fx/skipped-on-platform` | ③ | FX | skipped-on-platform | fx-id | — |
| `:rf.flow/computed` | ② | FLOW | computed | flow-id → path | dur? |
| `:rf.flow/cleared` · `failed` · `skip` | ② | FLOW | cleared / failed / skipped | flow-id | — |
| `:rf.route/activated` · `deactivated` · `cleared` · `fragment-changed` | ③ | ROUTING | activated / deactivated / cleared / fragment-changed | route-id / fragment | — |
| `:rf.route/navigation-blocked` | ② / ③ | ROUTING | navigation-blocked | route-id (guard) | — (→ outcome :blocked) |
| `:rf.warning/no-not-found-route` | inline (its phase) | WARNING | no-not-found-route | url (unmatched, no `:rf.route/not-found` route registered) | — |
| `:rf.event/dispatched [:rf.route/handle-url-change …]` | ① | EVENT | dispatched | url (the URL-change EVENT — **not** a standalone trace op; it rides the `:rf.event/dispatched` row above) | — (↗ child) |
| `:rf.machine.timer/scheduled` | ③ | MACHINE | timer-scheduled | delay · state | — (↗ future epoch) |
| `:rf.machine.timer/fired` | own epoch | MACHINE | timer-fired | delay · state | — |
| `:rf.machine.timer/stale-after` · `cancelled-on-resolution` | ③ | MACHINE | timer-stale / timer-cancelled | state | — |
| `:rf.machine.timer/skipped-on-server` | ③ | MACHINE | timer-skipped-on-server | state | — |
| `:rf.machine/spawned` · `:rf.machine.spawn/cancelled-on-join-resolution` · `:rf.machine.spawn-all/*` | ③ | MACHINE | spawned / spawn-cancelled / spawn-all-started/completed/failed | invoke-id | — (↗ child) |
| `:rf.machine/after` · `done` · `finished` | ② / ③ | MACHINE | after / done / finished | delay / output | — |
| `:rf.sub/create` | ④ | SUB | created | sub-id | — |
| `:rf.sub/run`+`computed` (value-changed? ✓) | ④ | SUB | recalculated | sub-id  old → new | dur? |
| `:rf.sub/run`+`computed` (value-changed? ✗) | ④ | SUB | ran-unchanged | sub-id | dur? |
| `:rf.sub/skip` · `skipped` | ④ | SUB | cache-hit | sub-id | — |
| `:rf.sub/disposed` | ④ | SUB | disposed | sub-id (no readers) | — |
| `:rf.view/render`+`rendered` (mount? ✗) | ④ | VIEW | re-rendered | view-id ← cause-sub | `elapsed-ms` |
| `:rf.view/render` (mount? ✓) | ④ | VIEW | mounted | view-id | `elapsed-ms` |
| `:rf.view/skip` | ④ | VIEW | skipped | view-id | — |
| `:rf.view/unmounted` | ④ | VIEW | unmounted | view-id | — |
| `:rf.view/dropped-after` · `rendered-cap-reached` | ④ | VIEW | dropped / cap-reached | view-id | — |
| **`:rf.error/*`** (any) | inline (its phase) | ERROR | the `operation` id | ex-data | — |
| **`:rf.warning/*`** (any) | inline (its phase) | WARNING | the `operation` id | context | — |
| **Registration/boot** — `:rf.fx/reg-flow` · `:rf.fx/registered-platforms` · `:rf.cofx/registered-platforms` · `:rf.flow/registered` · `:rf.route/registered` · `:rf.machine.registrar/*` | — | — | OUT OF SCOPE (boot-time, not per-epoch) | — | — |

> Generic rules: any `:rf.error/*` → ERROR row (emphasised, inline, → Issues panel); any `:rf.warning/*` → WARNING row (inline, → Issues). The implementer cross-checks this matrix against the live trace-emit sites; new ops get a row before shipping (the completeness contract, §1).
