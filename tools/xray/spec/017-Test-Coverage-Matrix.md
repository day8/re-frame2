# 017-Test-Coverage-Matrix

This spec defines the browser-feature coverage matrix Xray must grow
around its existing unit/helper/view tests. It is intentionally a
coverage contract, not an implementation plan: rows name the
user-visible behaviour, the deterministic testbed affordance required
to drive it, the direct happy path, the empty/failure/error path, the
occasional 20-event/load re-check, the diagnostics a failing gate must
print, the owning command/gate, and the current status.

Current status values are as of 2026-05-20 (post the Static / Cmd-K
palette / machines-viz / event-status-colour landings — rf2-o5f5f
epic + rf2-ybjkx + rf2-o9arp + rf2-b76v4), with the rows and their
owning-gate cites re-read against the tree on 2026-09-18
(rf2-y8doi.44):

| Status | Meaning |
|---|---|
| `covered` | The row has unit/helper/view coverage plus a browser-level feature path, including failure/empty coverage where applicable. |
| `partial` | Some unit/helper/view or smoke coverage exists, but the deterministic feature path, failure path, or 20-event/load re-check is missing. |
| `deferred` | Bookkeeping placeholder: the row's promotion to `covered` is gated on a specific follow-on bead (referenced inline in the Status cell). |
| `missing` | No meaningful Xray-specific automated coverage exists yet. |

**A row outlives its panel only through a successor.** Where the
surface a row was written for has been retired, the row stays only
while its user-visible contract lives on in a surviving surface, and
it then describes that successor: `Event Detail` is the Epoch panel,
`Views (incl. nested subs)` the reactive-flow Views tab, `Routes` the
Dynamic Routing lens. `covered` on such a row speaks for the successor
and the gate the row names — never for the retired panel. A surface
retired with no successor loses its row instead of keeping a `covered`
it cannot earn: the Time Travel, Schema Timeline and Hydration rows went
that way (rf2-y8doi.44), and what survives of them is carried by the
Spine binding, Static Schemas and Issues Ribbon rows.

**An Owning-gate cite names a suite that exists.** Every test file this
page cites must resolve under `tools/xray/test/`, and
`coverage_matrix_metadata_test.clj` goes red when one does not. Where a
row's behaviour has no suite, the cell says "none yet" rather than
naming the suite it should have.

The 20-event/load gate is **not default CI**. It is an occasional
pre-commit or explicit pre-PR gate for Xray-heavy work, because it
drives slow browser scenarios and intentionally stresses dispatch
storms, buffer caps, and panel rendering budgets. Default CI should
continue to run the lightweight unit/helper/view gates plus the normal
smokes.

**When a `covered` row is proved, and by which tier.** The owning
command in the matrix below — the Xray browser feature gate — runs in
two tiers, and the column does not distinguish them. `npm run
test:xray-feature-gate:smoke` runs on every PR that touches a Story or
Xray surface; it executes only the scenarios tagged `smoke: true` in
`tools/xray/testbeds/feature_matrix/scenarios.cjs` and compiles only the
surfaces those scenarios load. `npm run test:xray-feature-gate` — every
scenario over every staged surface — runs nightly in
`.github/workflows/expensive-tests.yml`, and that nightly sweep is the
system of record. The gate prints the live pair on every run, so read
that rather than this snapshot: re-counted 2026-09-18 (rf2-y8doi.44) the
split is 5 scenarios over 3 surfaces on the PR path against 16 over 11
nightly. The fifth PR scenario, `source coordinates and launch-mode
availability`, was tagged 2026-09-05 (rf2-61i5) after the previous
count of 4 over 3 was written; rf2-l86mm had taken it there from 5 over
4 against 17 over 12 by retiring the `freehand-views` scenario and its
staged surface. So **most
`covered` rows in this matrix are proved once a day rather than on the
change that could break them**, and a newly added non-smoke scenario
merges without ever having run (rf2-rliq7 records the policy call and
its costings). That is the deliberate cost decision in `TESTING.md`, not
an accident — but read `covered` in that light: it means the row has a
browser-level feature path, not that the path guards the PR that breaks
it. Per `TESTING.md`, tag a scenario `smoke: true` only if it earns a
slot on every PR **and** loads an already-staged smoke surface;
everything else is nightly by default, and the author is expected to run
the full gate locally (`cd implementation && npm run test:xray-feature-gate`)
before merging a scenario the PR tier will not execute.

## Required shared testbed

The feature gate should prefer one deterministic Xray feature testbed
over many narrow ad-hoc pages. The testbed must expose stable
controls, DOM test ids, and page-evaluable diagnostics for:

| Affordance | Requirement |
|---|---|
| Deterministic exceptions | Buttons that throw from event handlers, fx handlers, sub functions, machine guards/actions, flow evaluators, and view render paths. Each emits stable `:operation`, `:op-type`, `:dispatch-id`, source coord, and short message. |
| Schema violations | Known-good and known-bad event payload, cofx, app-db slice, and sub-return shapes. Each named recovery mode used by the schema timeline must be triggerable without random data. |
| HTTP failures | Managed HTTP success, 4xx/5xx, decode failure, accept failure, retry exhaustion, abort, and stale response. The Effects, Trace, Issues, Performance, and Event Detail panels must all see the same cascade. |
| Drain-depth/load | A deterministic dispatch cascade that approaches the drain-depth limit without flaking, plus a distinct path that intentionally exceeds it. The 20-event check drives 20 representative dispatches after opening Xray. |
| Long-flow failure | A flow DAG with at least one long path, one skip/no-op path, one recompute, and one deterministic evaluator failure. |
| Deep machine | A hierarchical/parallel machine with nested states, child actors, invoked work, timers, guard failure, action failure, and transition history deep enough to require scrolling. |
| Multi-frame | At least three frames with one cross-frame cascade, one dormant frame, and one destroyed-frame trace. Frame picker assertions must prove panels isolate and fan out correctly. |
| Non-trivial app-db | A nested app-db with maps, vectors, sets, metadata-like keys, at least 50 leaves, and repeatable before/after snapshots. Diff views must include touched slices, pinned slices, unchanged siblings, and deep vector indices. |
| Sensitive and large dispatchers | Separate `:sensitive? true` and `:large?` paths, plus a combined path. Redacted values are unrecoverable; large-elided values expose a fetch handle and digest. |
| SSR/hydration mismatch | Server/client render hash mismatch, divergent render-tree row, missing payload, corrupt payload, and multi-frame hydration mismatch. |
| Source coordinates | Event, view, sub, route, machine, flow, hydration row, and trace row source coords, including a missing-file case that hides the chip. |
| Agent origin | A reproducible `:origin :re-frame2-pair-mcp` action stream with read-only calls, confirmed writes, one failed tool call, and one empty lifecycle event with no dispatch id (Xray-MCP is dropped; re-frame2-pair-mcp is the AI access path). |
| Shell modes | Default true-inline host auto-mount, CSS-only close/open, pop-out, optional overlay debug chrome if present, settings corruption/reset, and production elision probes. |

## Diagnostics required on failure

Every browser feature gate failure must print enough runtime state to
debug without re-running under a debugger:

| Diagnostic | Required contents |
|---|---|
| Scenario header | Testbed name, URL, panel/surface id, selected frame, selected panel, seed/version if any, and gate name. |
| Browser state | Console errors, unhandled rejections, failed network requests, screenshot path, and active `data-testid` locator if the assertion was DOM-based. |
| Xray state | Active panel, target frame, selected dispatch id, epoch index/count, trace count, suppressed-sensitive count, active filters, and mounted/visible flags. |
| Dynamic slice | Last 20 trace events as compact rows: `id`, `time`, `frame`, `operation`, `op-type`, `dispatch-id`, `origin`, `source`, `severity`, and short message. |
| Panel-specific payload | The row-specific diagnostics named below. Payload values must respect redaction/large-value rules; never print a raw sensitive value. |
| Load stats | For 20-event/load failures, include event count before/after, trace buffer depth, visible-row count, render duration summary, slowest cascade id, and any buffer eviction count. |

## Coverage matrix

| Surface | User-visible contract | Required testbed affordance | Direct path | Failure/empty/error path | 20-event/load re-check | Diagnostics on failure | Owning command/gate | Status |
|---|---|---|---|---|---|---|---|---|
| Event Detail | Now the Epoch panel, which superseded the retired Event/Handler panel (rf2-5gl5r). Opening Xray lands on the latest dispatch, and the panel shows that epoch as a numbered cascade — DISPATCH · COEFFECTS · HANDLER · FLOW · FX · SUBSCRIPTIONS · VIEWS, each step rendered only when it fired (`021` §9.1) — with source chips, and exceptions attached inline under their owning step. | Non-trivial app-db, deterministic exception cascade, HTTP failure cascade, source coords, sensitive/large payloads. | Open with `Ctrl+Shift+C`, drive one representative dispatch, select its row, assert the steps of its numbered cascade and the source chip. | Empty trace shows no-events copy; a handler/fx/sub/view exception attaches inline under the step that threw. | After 20 mixed dispatches, latest cascade remains selected, rows stay capped/virtualised, no duplicate steps. | Selected dispatch id, rendered step list, event vector, source coord URI, db changed paths, exception summary. | Xray browser feature gate plus `tools/xray` unit gate. | `covered` |
| L2 Event List (the spine timeline) | L2 event list shows the last N dispatches as single-line rows (latest-on-bottom); rows carry a fixed-width 10px leading caret gutter (`>` on the focused row, empty on every other row — rf2-hga49) + event id + a text `source` tag + timestamp + handler duration, with a light-pink wash on a row whose epoch contains an issue (`018` §Row anatomy; the badges `⚠ 🌐 🤖` and the redaction marker were retired under rf2-pjjwh and their helpers deleted under rf2-65qlf); LIVE-tracking + sticky-on-older + auto-scroll-to-bottom; row click → spine focus + L4 detail panel rebinds. The newer-events marker indicates the spine is not following; no head-row pulse cue was ever built (rf2-pjjwh, rf2-2sez0) and the focused row's caret is a selection signal rather than a RETRO cue (the Mode pill widget was dropped). | Drain-depth/load path, multi-frame cascade, agent-origin dispatch (`:origin :re-frame2-pair-mcp`), same-event sibling dispatches. | Dispatch three events, assert row order (latest at bottom), assert the caret gutter + source tag + duration per row, click a row, assert L4 detail panel rebinds + spine `:mode` flips to RETRO with the clicked row carrying the `>` caret and every other row's gutter rendering empty. | Empty list shows cold-start copy ("Click around your app…"); stale/destroyed-frame events do not crash; rows survive without dispatch id (rare). | Drive 20 dispatches and assert latest row remains selected when at head, sticky-on-older holds, virtualisation budget correct. | Row contents (gutter / event-id / source tag / timestamp / duration / issue wash), focused dispatch-id, spine `:mode` value, virtualised row index range. | Xray browser feature gate. | `covered` |
| App-DB Diff | Changed slices are first-class; before/after diff, clickable path segments (rf2-e9tb0), path-origin chips (rf2-s8r6c), reserved slices, redaction, and large elision are visually distinct. | Non-trivial app-db, deep vector index change, sensitive and large values, handler + flow writers on overlapping paths. | Dispatch deterministic app-db update, open App-DB, assert touched slice, before/after values, unchanged sibling collapse, click a path segment and assert the inspector popup opens at the path-prefix, assert per-slice origin chip ([fx :db] / [flow :id] / [mixed]). | Empty/no diff copy; missing before/after snapshot degrades cleanly; redacted and large-elided markers preserve structure. | After 20 updates across different slices, changed-path summary remains accurate and rendering stays capped. | Changed paths, before/after hashes, visible rows, redacted/elided marker counts, path-origin chip counts. | Xray browser feature gate plus app-db diff unit/view gate. | `covered` |
| Trace | Raw trace feed filters by all canonical axes and remains the common substrate for panel drill-ins. | Mixed operations, severities, frames, origins, source coords, redacted events, clear-buffer control. | Open Trace, assert rows, axis chips, AND-composed filters, clear filters, row click to Event Detail, source chip. | Empty buffer and no-match filters are distinct; sensitive drops increment redaction count; unknown filter axes are ignored. | After 20 mixed dispatches, counts, visible rows, filters, and buffer cap remain consistent. | Active filters, row count/total, last 20 trace compact rows, axis histograms, suppressed count. | Xray browser feature gate plus trace-collector/filter unit gates. | `covered` |
| Views (incl. nested subs) | Views tab answers "what rendered as a result?" for the focused epoch: a left→right reactive-flow graph from app-db through the subs that ran to the views they re-rendered, changed and unchanged nodes told apart, with the memo-hit subs behind a "Show N unchanged subs" disclosure and the epoch's unmounted views and destroyed subscriptions listed beneath (`021` §3.2 and §3.4). The three temporal groups, ≥ 50 clustering and props-diff drilldown this row once described were `012`'s design, superseded (see the note above the matrix). | Render cascades with sub recomputes, re-renders, a memo-hit sub, an unmount, a throwing sub, a large/redacted sub return. | Trigger a render cascade, focus it, open Views, assert the graph carries the cascade's sub and view nodes with changed and unchanged nodes distinguished, and that the unchanged disclosure reveals the memo-hit sub. | No renders this cascade (empty graph copy); throwing sub; redacted/large sub output renders per the data-classification contract. | After 20 mixed dispatches, the graph follows the focused epoch and the isolation filter holds. | Focused epoch id, sub-node and view-node counts, unchanged-sub count, isolation filter (no Xray-namespaced components in host frame's Views). | Xray browser feature gate plus views unit/view gate. | `covered` |
| Machines | Machine inspector renders state chart, active states, transitions, actors, timers, errors, and source chips. The chart primitive lives in `tools/machines-viz/` post-rf2-o9arp; Xray re-exports via thin shims (the `machines-viz` shim integrity gate below asserts the re-export is wired and not stripped under `:advanced`). | Deep machine with hierarchy/parallel states, child actor, invoke, timer, guard/action failure, transition history, plus a machines-viz shim integrity affordance (chart SVG renders ≥ 1 layout node; `:advanced` build keeps the re-export). | Start machine, drive transitions, open Machines, assert active state highlight, transition log, actor/timer rows, source chip. | No machines registered; guard/action/invoke failure; destroyed machine; missing source coord. | After 20 machine events, transition history remains ordered and chart render stays stable. | Machine id, active state path, child actor ids, transition ids, timer ids, failure keyword, chart node count. | Xray browser feature gate plus machine helper/view gate. | `covered` (machines-viz shim integrity covered by `runDeepMachine` per rf2-bz72m). |
| Routes | The Dynamic Routing lens (`016` §Dynamic Routing): the current route with its params, query and fragment; the navigation the focused epoch caused, as `◆ FROM` / `◆ TO` chips read off that epoch's own routing emits, or `◆ HERE` when it caused none; and the route topology with the nested-route hierarchy. It keeps no navigation history — each epoch carries its own navigation, so there is nothing to cap. | Route registry with a nested route, navigation success, blocked navigation, not-found, loading/error transition, multi-frame route. | Navigate to a route with params, focus that epoch, open Routes, assert the current route, the TO marker and outcome, and the nested row under its parent. | No routes registered; blocked navigation; not-found; a focused epoch that navigated nowhere shows only `◆ HERE`. | After 20 navigations, the current route stays correct and each focused epoch shows its own FROM / TO. | Route id, params/query/fragment, transition state, nav token, FROM / TO ids of the focused epoch. | Xray browser feature gate plus routes unit/view gate. | `covered` |
| Issues Ribbon | The dedicated Issues TAB was removed per rf2-gbz39 (Mike's Option (c) ruling); the `:rf.xray/issues-ribbon` projection survives as the always-on ribbon signal's data source. Issues surface inline — in the Epoch panel (per-step pass/fail + the "Exception Thrown" block), via the L2 event-row pink-wash, and via the always-on issues ribbon signal — across errors, warnings, schema violations, hydration mismatches, and advisories. | Deterministic handler/fx/sub/view exceptions, schema violations, hydration mismatch, warning/advisory emits. | Trigger one issue per category, assert the issues ribbon signal fires (auto-open-on-error watcher), assert the focused epoch's Epoch panel surfaces the issue inline + the issuing L2 row carries the pink-wash, assert the source chip resolves. | No issues shows all-clear (ribbon silent, no pink-wash); malformed issue keeps the projection alive. | After 20 mixed success/failure dispatches, issue projection counts + pink-wash attribution remain accurate. | Severity/category counts, ribbon-signal state, issue trace ids, parent dispatch ids, source coords, per-row pink-wash flag. | Xray browser feature gate plus issues-projection unit/view gate. | `covered` |
| 4-layer chrome | L1 **two ribbons** (rf2-4vp5j) — chrome ribbon (`Events` label · nav `[◀ ▶ ⏭]` · `+ filter` add on the left; frame view-scope dropdown · Dynamic/Static mode dropdown · `🔇 N`/`● N` indicators · `⚙` `✕` on the right) + events ribbon (`↳ filters:` label · add `+` · filter pills · `N events filtered out` warning; hidden until the first filter exists — the focus-chip and `Clear Filters` button were retired per rf2-pjjwh) — + L2 event list (8 rows default, resizable to min 2) + L3 tab bar (10 Dynamic tabs) + L4 detail panel mount correctly; no L0 bottom rail; resize handle works between L2/L3; narrow (<800px) + wide (≥1200px) layouts preserve layer order. | Multi-frame app, ≥20 cascades for L2 scrolling, narrow + wide viewport configurations. | Open Xray, assert chrome-ribbon clusters (`Events` label · nav · frame · mode dropdown · icons) + events-ribbon clusters (`↳ filters:` label · pills · `N events filtered out` warning), assert L2 8 rows visible, drag L2/L3 handle to resize, switch tabs by clicking the L3 tab buttons and via the command-palette tab-jump verb (`Cmd`/`Ctrl+K` → type the tab label → `Enter`); there are no digit or bare-letter tab-jump keys to press (007 §Trimmed pending demand). The `runShellFeatureSweep` walks the full ten-tab `PANEL_HANDOFFS` (per rf2-tgp6i — Routing tab added 2026-05-19; Resources / Graph / Frames added per EP-0016 / EP-0014 / EP-0023; Fresco added per rf2-hic-023; the Machines Canvas tab was removed 2026-05-21 per rf2-ga16q — its browse-all canvas relocated to the Static Machines sub-tab; the Issues tab was removed 2026-05-31 per rf2-gbz39 — issues surface inline in the Epoch panel + L2 event-row pink-wash + the issues ribbon). | Below 800px tab labels truncate to 3 chars; below 560px tab strip scrolls horizontally. (Nothing refuses to mount at any viewport width.) | After 20 dispatches, layer geometry stable, virtualised rows render correctly, no L0 ever appears in DOM. | Ribbon cluster widths, L2 row count, L2 row height, L3 tab labels, L4 mount tree, viewport breakpoint state. | Xray browser feature gate plus the shell unit suite (`shell_cljs_test.cljs` — the four layers, both ribbons' clusters, the ten-tab bar, the L2 rows) and the L2/L3 seam suite (`events_list_seam_cljs_test.cljs` — drag, clamp, reset). The narrow/wide breakpoints have no unit gate yet. | `partial` (spec 018 §2; breakpoints ungated). |
| Spine binding (`:rf.xray/focus`) | Clicking any L2 row dispatches `:rf.xray/focus-event`, which atomically rebinds the L2 newer-events marker + L3 count badges + L4 detail panel content. No panel reads `(peek history)`; no panel carries its own `:selected-*-id` slot. LIVE-tracking + sticky-on-older + LIVE-paused state transitions correct. (The Mode pill widget was dropped — `:mode` surfaces in the L2 spine.) | Deterministic cascade chain ≥10 events, LIVE / RETRO / paused mode transitions. | Click row 5 of 8, assert the focused row's caret gutter paints `>` while every other row's gutter stays empty, assert L4 Epoch panel content updates to row 5's cascade, assert L3 `Views N` count updates, assert spine `:mode` flips to RETRO. Press `l`, assert spine `:mode` flips back to LIVE, assert L2 auto-scrolls to bottom + the newer-events marker clears. | Empty buffer; selection at boundary (oldest / newest); `Space` pauses LIVE; new event arrives while paused (sticky on selection); `G` snaps to head. | After 20 dispatches with mid-stream selection, sticky-on-older holds; selection only auto-advances when at head. | Spine sub value (`:dispatch-id`, `:epoch-id`, `:frame`, `:mode`, `:head?`), focused-row gutter glyph, L3 count badges, L4 detail panel content hash. | Xray browser feature gate plus the spine unit suite (`spine_cljs_test.cljs`). | `partial` (spec 018 §6) |
| Filter IN/OUT pills | Events-ribbon pills add/edit/delete via popup; AND-across-modes / OR-within-mode filter semantics; **reset-on-load** (rf2-swclw — a reload starts unfiltered; `mount.cljs/::reset-transient-filters` hydrates no pill slot, and since rf2-y8doi.27 the pills have no localStorage layer to clear); the `N events filtered out` warning surfaces when a pill suppresses rows (rf2-jvghz; the `Clear Filters` button was retired per rf2-pjjwh — each pill's trailing `✕` removes that pill); right-click event-row creates correct pill type. Pills filter at data layer (`:rf.xray/filtered-event-bundles`), not render; the frame view-scope is applied first and is NOT a pill (rf2-4vp5j). | Mixed cascades (errors, HTTP, machine events), pre-populated pill set, Recommended-filters definition. | Add IN pill via trailing `+` (popup), add OUT pill via right-click row, assert pill rendering + filtered cascade count + `N events filtered out` indicator, edit pill via click, delete pill via popup, **reload page assert pills RESET to empty** (rf2-swclw). | Empty pills (no filter); pattern non-matching (zero filtered cascades); overflow `…N more ▾` collapse; error-override-bypass (filtered errored event surfaces with `⚠ ▽` gutter); active pill that hides nothing → no `N events filtered out` warning renders (the warning requires N > 0; there is no `Clear Filters` control — rf2-pjjwh). | After 20 dispatches with active pills, filtered cascade count + virtualised row count stay accurate; the chrome-ribbon nav cluster (rf2-3f2di A5) walks the RAW spine, not the filtered set (rf2-cqpj4). | Active pill set (`:rf.xray/active-filters`), filtered cascade count, raw cascade count, `:rf.xray/hidden-by-filters` summary, virtualised row index range. | Xray browser feature gate plus the filter pills unit suite (`filters/pills_cljs_test.cljs`) + hidden-indicator unit gate (`filters/hidden_cljs_test.cljc`, dual-runtime since rf2-odlm3). | `partial` (spec 018 §7) |
| Sim mode (UC1) per-feature | The Static Machines surface's Sim sub-mode (the `s` pill, `rf-xray-static-machines-pill-sim`) runs a hermetic clone seeded from the runtime's own initial snapshot — initial `:entry` actions are not run, and the banner says so. The shipped surface is the amber `SIMULATING` banner (`…-sim-banner` + `…-sim-entry-notice`); the rail — `sim-event-input`, the optional EDN event-payload `sim-data-input`, `…-sim-{step,reset,exit}-button`, the "Available from current state" list (a guarded transition is tagged `[guard]` with NO verdict predicted; one `⌚` row per `:after` timer on the active path), the inline step diagnostic and the audit trail; and the chart (active state amber, clicking a plain `:on` edge sends that event). The sub-mode choice persists per machine in `xray.static.machines.sub-mode-by-id`. **Future design, not built** (spec 003 §UC1 historical): per-transition guard verdicts and greyed failed-guard rows, the `Shift+Enter` fire-despite-guard chord, the skip-guards toggle, the schema-derived mock `:data` form, the `E`/`R`/`Esc` keys, a per-(frame, machine) toggle and the "Save as scenario" clipboard emit. | Deterministic machine with guarded and unguarded `:on` transitions and at least one `:after` timer, schema-registered + schema-less variants, Static mode with the machine selected. | Switch to Static → Machines, select the machine, click the Sim pill; assert the `SIMULATING` banner + entry notice and the amber active-state highlight on the chart; assert the available list names each outgoing `:on` transition with guarded rows tagged `[guard]`; click a row → the event input fills; click Step → current state + audit trail advance; click Reset → initial state; click Exit → the Topology sub-mode. (Greyed failed-guard rows and `Shift+Enter` fire-despite-guard are future design — not asserted until built.) | No machines registered (`…-sim-no-machine`); machine without a definition (`…-sim-no-definition`); a state with no outgoing transitions (`…-sim-available-empty`); a step that moves nothing — no match, a declined guard or a no-op — surfaces ONE amber `No change` diagnostic (`…-sim-error`, `data-kind="no-change"`) rather than a phantom trail row. | After 20 sim steps, audit trail accurate (no live timestamps / source coords leak); the per-machine Sim sub-mode choice survives reload. | Sub-mode, banner visible, active-state hue (amber vs cyan), available-list rows and their `[guard]` tags, audit-trail entries, step-diagnostic kind + text. | Xray browser feature gate plus the Static Machines Sim unit suite (`static/machines/sim_cljs_test.cljs`). | `partial` (spec 003 §UC1) |
| Mode A/B/C dynamic instances (UC2) | Machines tab auto-selects Mode based on instance count: A (0) → empty hint or sim; B (1–3) → all instances on same diagram with stable per-instance hues + side-tag arrows; C (4+) → cluster-by-state count badges + virtualised table + shift-click divergence (cap 4 lanes); per-instance mini-scrubber in arc strip works without affecting global spine. | Deterministic machine registration, programmatic instance spawn (0 / 1 / 3 / 47 instances), shift-click multi-select. | Spawn instances 0 → 1 → 3 → 47, assert mode transitions A→B→C, assert per-instance hues stable across re-renders, assert Mode C count badges + virtualised table + sort/filter; shift-click 2 instances, assert divergence highlight. | Shift-click cap > 4 returns to single focus; per-instance scrubber doesn't touch `:rf.xray/focus`; recent-deaths buffer 10s fade + 30s preservation. | After 20 mixed spawn/destroy ops, mode auto-detect remains correct, instance hues + table sort stay consistent. | Live instance count, mode keyword, per-instance hue assignments, divergence-lane count, mini-scrubber state, recent-deaths buffer contents. | None yet — and none can land before the feature: Mode A/B/C is not built (`:mode-a` / `:mode-b` / `:mode-c` read 0 in `tools/xray/src` and in `tools/xray/test`). | `missing` (spec 003 §UC2; unbuilt) |
| Data classification rendering | Xray renders `:rf/redacted` opaque (the magenta `● redacted` chip, `rf-xray-edn-inspector-redacted`; **no reveal button ever**); `:rf.size/large-elided` renders today as an INERT yellow `● large · N bytes` marker (`rf-xray-edn-inspector-large`) whose `title` carries the value's `:type` and `:hint` — the designed `[● ELIDED · N bytes]` fetch popover, `get-path` round-trip and > 100KB confirm are designed-not-built, and `:handle` is carried for that future drill-in; combination semantics (`:rf/redacted` + bytes dominates: magenta `● redacted · N bytes`, `rf-xray-edn-inspector-redacted-size`, no drill). Rendering is at L4 (per `inspect`, plain and `:before` diff modes); the session count is the ribbon's `REDACTED N` indicator (`rf-xray-redacted-indicator`), the only aggregate. (No L2 trailing marker renders and there is no Settings → Diagnostics section — spec 018 §12; the Mode pill widget that earlier drafts carried per-session totals on was dropped.) | Sensitive dispatcher, large dispatcher (small + > 100KB), combined sensitive-large path, clear-buffer path, egress-profile reveal/redact. | Dispatch sensitive + large events; assert the ribbon `REDACTED N` indicator counts the dropped sensitive events; assert the L4 Epoch panel + App-db tab + Views tab render the redacted, large-elided and redacted-with-size sentinels correctly; assert the large marker is inert (no popover on click, `title` carries `:type` + `:hint`). (The < 100KB popover / > 100KB confirm-modal clicks and the L2 row markers are future design — not asserted until built.) | Widening the egress profile to `:rf.egress/local-raw` affects future events only; `:rf/redacted` cannot be fetched (no button); malformed `:rf.size/large-elided` marker renders safe fallback. | After 20 sensitive/large dispatches, the ribbon count and the rendered sentinel counts are accurate, and no raw sensitive string appears in DOM/diagnostics. | Sentinel paths/counts, marker hue, ribbon `REDACTED N` value, DOM text scrub result. | Xray browser feature gate plus the renderer suites that pin the sentinels: `views/edn_inspector_cljs_test.cljs` (redacted, large-elided and redacted-with-size chrome) and `panels/app_db_diff_cljs_test.cljs` (egress redacts a sensitive slot, size-elides a large one). The fetch popover and the > 100KB confirm are unbuilt and have no gate. | `partial` (spec 018 §12 + spec/015; fetch path unbuilt) |
| Frame-isolation invariants (I1–I4) | I1: the frame picker excludes the tool frames (`:rf/xray`, `:rf/re-frame2-pair`) unconditionally — there is no toggle (the "Show tool frames in picker" setting was removed 2026-05-27; rf2-y8doi.27 removed its orphaned slot). I2: no Xray view reads host data through `:rf/xray`. I3: Views render-attribution is scoped to the selected frame (Xray-internal renders MUST NOT bleed into the host frame's Views). I4: Xray-self-observation is disallowed — Xray's own events and renders are dropped from the inspected stream. | Multi-frame app (≥2 host frames + Xray-internal `:rf/xray`), deterministic Xray-internal hover-render. | (1) Open the frame picker, assert `:rf/xray` absent; (2) select `:rf/default`, trigger an Xray-internal hover, open Views, assert NO Xray-namespaced component appears. | Empty render set (no host renders this cascade); an Xray-internal sub feeding a host-data path (I2 — no automated check yet). | After 20 host renders + 20 Xray-internal hover-renders, isolation holds. | Frame picker contents, Views render rows, the inspected stream's Xray-namespaced event count. | I1: `frame_switcher_cljs_test.cljs` (`distinct-frames-excludes-internal-frames-unconditionally`). I3 / I4: `panels_e2e/multi_frame_isolation_e2e_cljs_test.cljs` (focused-frame tracking, cross-frame isolation) backed by `self_noise_cljs_test.cljc` (the drop predicates); per-instance state isolation: `two_instance_isolation_cljs_test.cljs`. All on the node lane (`npm run test:cljs`). None yet for I2's dev-time sub-graph lint, and no `:owning-frame` render tag exists to filter on (`018` §8). | `partial` (spec 018 §8; I2 lint and browser-level I3 ungated) |
| Settings modal popup | Modal opens via `,` / `s` / click `⚙`; closes via `Esc` / click outside / `✕`; four tabs — General, Keybindings, Buffer, Diff — switched by clicking or by their `g` / `k` / `b` / `d` mnemonics; each field writes through as it changes (no Apply/Cancel); Buffer's "Clear buffer now" asks for confirmation before it drops the trace buffer. | Default-config Xray, modified-config Xray, corrupted persisted settings. | Open via `,`, assert the General tab is active; switch to Buffer with `b`, click "Clear buffer now", confirm, assert the trace buffer is empty; re-open via `s`, assert the popup re-opens on General. | Corrupted persisted settings degrade to defaults; click outside closes; `Esc` closes; the Clear confirmation's Cancel keeps the buffer. | After 20 setting toggles, the persisted settings match the Settings UI; no toggle drift. | Modal mount state, active tab, per-tab field values, persisted settings, trace-buffer depth before/after Clear. | Xray browser feature gate plus the settings popup unit suite (`settings/popup_cljs_test.cljs`). | `partial` (spec 018 §9) |
| Open in Editor / Source Coordinates | Every source chip builds the configured editor URI, hides on missing file, and never inlines custom URI assembly per panel. | Source coords across event, fx (rf2-g1mfc — Epoch FX-step row), trace, app-db, sub, route, machine, flow, hydration, and missing-file case. | Configure each editor keyword/custom template, click chips from multiple panels, assert URI shape or hidden chip. | Missing `:file`; unknown editor keyword fallback; no OS handler no-op; malformed custom template fallback. | After 20 dispatches, source chips remain attached to correct rows after virtualization/reordering. | Source coord map, configured editor, built URI, panel id, row id, hidden-chip reason. | Xray browser feature gate plus config/open-in-editor unit gate. | `covered` |
| Redaction, Sensitive, and Large Values | Sensitive data is dropped and counted; large data is elided with recoverable handle/digest; panels render clear markers without leaking raw values. | Sensitive dispatcher, large dispatcher, combined sensitive-large path, clear-buffer path, egress-profile reveal/redact. | Dispatch sensitive and large events, assert the L1 chrome ribbon's `[● REDACTED N]` indicator, inline markers, large fetch handle/digest, clear reset. | Widening the egress profile to `:rf.egress/local-raw` affects future events only; redacted values cannot be fetched; malformed elision marker renders safe fallback. | After 20 sensitive/large dispatches, counts are correct, buffer cap is respected, and no raw sensitive string appears in DOM/diagnostics. | Suppressed counters, marker paths/counts, digest/bytes/handle, DOM text scrub result, buffer depth. | Xray browser feature gate plus sensitive trace and large dispatcher gates. | `covered` |
| Pop-out and Default True-Inline Embedding | True inline host is default; pop-out reads opener runtime; `open-overlay!` remains an optional debug surface. (Per `rf2-sbfb7` the `dock!` / `undock!` body-padding surface and the imperative `mount-inline-panel!` debug surface were removed; declarative panel embedding lives at `008-Embedding-Contract.md`.) | Same-origin pop-out, opener-close simulation, default true-inline mount under `[data-rf-xray-host]`. | Load with layout host, pop out, assert second window renders selected panel and shares trace/epoch state. | Missing layout host emits actionable diagnostic; opener gone warns/degrades. | After 20 host dispatches, inline host and pop-out agree on latest cascade without duplicate listeners. | Window mode, opener status, listener count, mount id, selected panel/frame, shared trace count, host selector. | Xray browser feature gate; current pop-out warning remains covered by core unit gate. | `covered` |
| Shell, Keybinding, Config, Preload, Settings, and Production Elision | Xray auto-mounts into `[data-rf-xray-host]` when runtime/substrate is ready, toggles via configured keys, isolates `:rf/xray`, persists/reset settings, and obeys preload order. Production posture is **build placement**, not construction: the preload rides `:devtools/preloads` (dev build config) and folds its boot block under `goog.DEBUG=false`; `init!` and the mount verbs carry no `goog.DEBUG` gate, so a host installing Xray from app code owns its own exclusion. | Default inline host page, missing-host page, settings corruption/reset, alternate keybinding, config knobs. No Xray release probe exists — see Status. | Load page, assert inline DOM in host after runtime readiness, open/close CSS-only, configure editor/sensitive/depth, reload/hot-reload idempotency. | Missing adapter retries then diagnoses; missing host reports selector/snippet/status without `alert()`; corrupt settings reset. A `goog.DEBUG=false` build that still loads the preload mounts nothing (the boot block folds); a build that calls `init!` from app code still installs — ungated, and asserted by no gate. | After 20 toggles/dispatches, no duplicate listeners, no remount state loss, no extra trace collectors. | Mounted/visible flags, listener registry, config atoms, settings payload, frame id. For posture: a release-bundle grep for `rf-xray-root` / `rf.xray` — a leak detector, not proof of zero retained bytes. | `tools/xray` unit gate and Xray browser feature gate. The implementation elision gates root `re-frame.*` namespaces only and root no Xray namespace. | `partial` — the shell / keybinding / config / preload / settings half is covered; the **production-posture** half has no gate at all. `npm run test:elision` roots `re-frame.*` sentinels only and `check-bundle-isolation.cjs` greps the counter example's no-feature bundle, so neither says anything about a bundle that installed Xray. An Xray-rooted release probe was considered and DECLINED under rf2-qqn9, which scoped the guarantee to build placement; this row stays `partial` by design rather than pending a gate. |
| Static mode mount + chord | Static mode is unconditionally available (per rf2-8l3uk): the surface composer renders the 3-layer Static chrome silhouette + mode **dropdown** at chrome-ribbon-right (rf2-4vp5j `<select>`, testid `rf-xray-mode-pill`); `Cmd-Shift-M` / `Ctrl-Shift-M` toggles between Dynamic and Static; selected mode persists to localStorage key `xray.mode` and hydrates on reload. | Xray-enabled testbed; localStorage round-trip; chord listener wired. | Assert mode dropdown visible + Dynamic selected; press `Ctrl+Shift+M`; assert Static surface mounts (3-layer silhouette, no L2 event list); select Dynamic in the dropdown; assert Dynamic restored; reload; assert persisted mode round-trips. | localStorage cleared: defaults to `dynamic`. Corrupt localStorage value: clean fallback to `dynamic` (the mode is one of `dynamic` / `static`). | After 20 chord toggles, mode-set fx persists each flip, no orphaned listeners, surface composer remains stable. | Mode pill state (`data-active-mode`), Static surface presence + `data-rf-xray-mode` attr, localStorage `xray.mode` value, chord-listener installed flag. | Xray browser feature gate (`runStaticModeChromeAndChord` in `scenarios.cjs` per rf2-n39g2 / rf2-o5f5f.1 / rf2-8l3uk). | `covered` |
| Static Machines panel | Topology chart renders the same `mv-chart/MachineChart` (xyflow + elkjs, rf2-gpzb4) primitive the Dynamic panel uses; Browse-list renders one row per registered machine with a `→ Dynamic` JUMP chip; 4-mode sub-strip `[Topology][Sim][Instances][Cascade]` renders with Topology default-active; Cascade pill is greyed + disabled with tooltip; JUMP-to-Dynamic flips mode + opens Dynamic Machines tab focused on the chosen machine-id. | Deep-machine testbed (`/testbeds/deep-machine/`) — multiple registered machines; Static mode unconditionally available (per rf2-8l3uk). | Enter Static via `Ctrl+Shift+M`, switch to Machines sub-tab, assert `rf-xray-static-machines-browse-list` renders ≥ 1 row, assert `rf-xray-static-machines-topology-chart` SVG has ≥ 1 layout-node child, assert `rf-xray-static-machines-sub-strip` carries Topology pill active + Cascade pill `disabled`+`opacity:0.5`, click a row's `→ Dynamic` chip, assert mode flips to Dynamic + Machines tab opens with the selected machine id. | No machines registered: `rf-xray-static-machines-empty` empty state. Sub-mode persistence corrupt: clean fallback to `:topology`. Cascade pill click is a no-op (disabled). | After 20 JUMP toggles, mode round-trips don't drop the selected machine-id; per-machine sub-mode map persists. | Selected machine-id, sub-mode keyword, topology SVG node count, JUMP target tab id + machine-id, Cascade pill `aria-disabled` attr. | Multi-frame e2e CLJS Node test at `tools/xray/test/day8/re_frame2_xray/panels_e2e/static_machines_panel_e2e_cljs_test.cljs` per rf2-7icrs (browse-list ≥ 1 row + Topology SVG ≥ 1 `<g>` layout node + sub-strip Topology-active + Cascade `aria-disabled='true'` + → Dynamic JUMP flips mode + opens Machines tab + lands machine-id). | `covered` (rf2-1laqx) |
| Static Routes panel | Flat-list browse-all surface with substring search, Simulate-URL hermetic preview (zero host nav mutation), per-row inline expand for full registrar meta, per-row hermetic Simulate-navigation preview, and per-row `→ Dynamic` jump chip that flips mode + opens Dynamic Routing tab (the two-verbs-two-homes pattern per `016` §Routes — two verbs, two homes). | Routes-aware testbed (`/examples/routing/` — 4 registered routes); Static mode unconditionally available (per rf2-8l3uk). | Enter Static via `Ctrl+Shift+M`, switch to Routes sub-tab, assert `rf-xray-static-routes-list` renders ≥ 1 row per registered route, type a known URL into `rf-xray-static-routes-sim-input`, assert `rf-xray-static-routes-sim-result` renders with a WINNER row + host `:rf/route` slot UNCHANGED (probe via `page.evaluate`), click a row's `→ Dynamic` jump chip, assert mode flips to Dynamic + Routing tab is selected. | No routes registered: `rf-xray-static-routes-empty` empty state. Simulate-URL with non-matching pattern: empty candidates list. Hermetic preview MUST NOT call `:rf.route/navigate` / `:rf.route/url-requested` / `history.pushState`. | After 20 simulate-URL inputs, projection runs deterministically; per-row expand state persists across re-renders. | Total registered-route row count, Simulate-URL candidate count, host `:rf/route` slot before + after sim, JUMP target tab id, expanded-id set. | Xray unit view gate (`static/routes/panel_cljs_test.cljs` — registry wiring, silent state, flat-list render, search filter, Simulate-URL row, expand toggle, hermetic preview, cross-link mode/tab flip) + Xray multi-frame e2e gate (`static_routes_panel_e2e_cljs_test.cljs` — synthetic 3-route override → tab-data browse list + WINNER candidate + host `:rf/route` hermetic + `:rf.xray.static.routes/jump-to-dynamic` mode/tab flip per rf2-wj46n). | `covered` (rf2-wj46n) |
| Static Flows panel | Flat-list browse-all surface (per Lock #15 — browse-all lives in Static) over every flow registered via `re-frame.flows/reg-flow`. Each row carries `:inputs` paths, `:output-path`, owning frame (flows are frame-scoped per Spec 013), and the doc-string. Substring search across flow-id + frame + inputs + output-path + doc. No jump-to-source chip (flow registration metadata does not surface source-coords in the current registry shape); no Simulate-input verb (flows have no input-event taxonomy to inject — they recompute on app-db changes). | Flows-aware testbed (deterministic `reg-flow` registrations across ≥ 1 frame); Static mode unconditionally available (per rf2-8l3uk). Test-only override seam: `:rf.xray.static.flows/registered-flows-override` (settable via `:rf.xray.static.flows/set-registered-flows-override-for-test`) injects a deterministic `{frame-id {flow-id flow-map}}` snapshot without touching the live flows-registry atom. | Enter Static via `Ctrl+Shift+M`, switch to Flows sub-tab, assert `rf-xray-static-flows-list` renders ≥ 1 row per registered flow, assert each row surfaces flow-id + frame + `inputs:` + `output →` segments, type a known substring into `rf-xray-static-flows-search-input`, assert `rf-xray-static-flows-search-count` flips to `match` + filtered row count drops. | No flows registered: `rf-xray-static-flows-empty` empty state. Search query with no match: `rf-xray-static-flows-empty-filtered` empty-filtered state. Live-registry deref failure: clean `{}` fallback (no crash). | After 20 mixed `reg-flow!` calls, projection ordering stays stable (sort-by flow-id ascending); search query persistence holds across re-renders. | Total registered-flow row count, filtered row count, search query value, per-row testid (`rf-xray-static-flows-row-<id>`), override slot present? | Xray unit view gate (`static/flows/panel_cljs_test.cljs` — registry wiring, pure projection over `project-rows` / `filter-rows` / `project-data`, silent state, flat-list render, search filter). | `covered` (rf2-uhsqb) |
| Static Schemas panel | Flat-list browse-all surface (per Lock #15 — browse-all lives in Static) over three input registries: app-db slot schemas (`re-frame.schemas/reg-app-schema`'s per-frame `{frame-id {path schema-meta}}`), event schema metadata (`registrar/registrations :event`, `:schema` slot), and sub schema metadata (`registrar/registrations :sub`, `:schema` slot). Each row surfaces kind (`app-db` / `event` / `sub`) + schema-id/path + the Malli EDN. Substring search across kind + id + schema. Jump-to-source chip per row when registration metadata surfaces `:file` / `:line` — click dispatches `:rf.xray/open-in-editor` (same wiring as Trace + Issues per rf2-evgf5 / rf2-g5q8d). | Schema-aware testbed (deterministic `reg-app-schema` + event/sub `:schema` registrations across ≥ 1 frame); Static mode unconditionally available (per rf2-8l3uk). Test-only override seam: `:rf.xray.static.schemas/set-registry-override-for-test` injects a deterministic `{:app-db {…} :event {…} :sub {…}}` snapshot without touching the live registries. | Enter Static via `Ctrl+Shift+M`, switch to Schemas sub-tab, assert `rf-xray-static-schemas-list` renders ≥ 1 row per registered schema across all three kinds, type a known substring into `rf-xray-static-schemas-search-input`, assert filtered row count drops, click a row's source-coord chip, assert `:rf.xray/open-in-editor` dispatches with the registered `:file` / `:line`. | No schemas registered across any kind: `rf-xray-static-schemas-empty` empty state. Search query with no match: empty-filtered state. Row with no source-coord: chip hidden. Malformed schema EDN in a registration: row still renders with safe fallback. | After 20 mixed schema registrations, three-kind grouping + filter holds; jump-to-source chips remain attached to correct rows after virtualisation/reordering. | Per-kind row counts (app-db / event / sub), filtered row count, search query value, source-coord chip presence per row, dispatched `:rf.xray/open-in-editor` payload. | Xray unit view gate (`static/schemas/panel_cljs_test.cljs` — registry wiring, pure projection across the three input registries, silent state, flat-list render, search filter, source-coord chip dispatch). | `covered` (rf2-o5f5f.4) |
| Static Interceptors panel | Flat-list browse-all surface (per Lock #15 — browse-all lives in Static) over every interceptor surfaced through registered events. A chain entry is an INLINE interceptor value OR a by-reference entry (bare keyword / `[id arg]`) into the `:interceptor` registrar (EP-0022, rf2-0adhqs.7); a reference is surfaced by its authored form (a `ref` badge + the factory `arg`) and enriched from the registered descriptor (`(rf/handler-meta {:source :store :kind :interceptor :id id})`). Each row surfaces interceptor id + a before/after/factory-hook indicator + a framework-emitted auto-wrapper badge (rf2-twt7m) + the `ref` badge when applicable. Substring search across the row text incl. the authored ref form. | Interceptor-aware testbed (deterministic interceptor registrations + ref-bearing chains); Static mode unconditionally available (per rf2-8l3uk). | Enter Static via `Ctrl+Shift+M`, switch to Interceptors sub-tab, assert `rf-xray-static-interceptors-list` renders ≥ 1 row per registered interceptor, type a known substring into `rf-xray-static-interceptors-search-input`, assert `rf-xray-static-interceptors-search-count` flips + filtered row count drops. | No interceptors registered: `rf-xray-static-interceptors` empty state. Search query with no match: empty-filtered state. Unregistered/hot-reloaded ref: `default-resolve-ref` is fail-soft (row renders, no hooks). | After 20 mixed registrations, projection ordering stays stable. | Total registered-interceptor row count, filtered row count, search query value, before/after/factory-hook + auto-wrapper + ref flags per row. | Xray unit view gate (`static/interceptors/panel_cljs_test.cljs` — incl. EP-0022 keyword-ref / `[id arg]`-factory-ref / inline-value-non-ref / fail-soft default-resolver coverage). | `covered` |
| Cmd-K palette | `Cmd-K` / `Ctrl-K` chord opens the palette dialog; mode-aware command index (commands filter by `:rf.xray/mode`); the rf2-ybjkx verbs, as shipped today — `:toggle-theme`, `:cycle-reduced-motion`, `:snapshot-app-db`, `:toggle-mode`, `:jump-to-settings` (a sixth, `:clear-epoch-history`, was removed under rf2-y8doi.27); recents slot persists top-3 invocations to localStorage; Esc closes without dispatching. | Counter testbed; localStorage seed for `re-frame2.xray.palette.recents.v1`; theme slot readable via `cfg.get_setting`. | Press `Ctrl+K`, assert `rf-xray-palette-dialog` mounts + input focused, type "toggle theme", assert fuzzy filter narrows to `:toggle-theme` row, press Enter, assert dialog closes + theme slot flips, re-open palette, assert recents-boost places `:toggle-theme` at row 0. | Empty recents: fuzzy ordering only. Theme slot unreadable: fail with diagnostics. Esc on palette: closes without firing the focused verb. | After 20 invokes, recents capped at 3, slot persistence stable. | Dialog mount state, input focus, first-row source + label, theme slot before/after, persisted recents payload. | Xray browser feature gate (`runPaletteOpenExecute` in `scenarios.cjs` per rf2-z5zip / rf2-ybjkx). | `covered` |
| Density — `--rf-xray-font-size` calc-anchor (rf2-n8i2c + rf2-i40us) | Every type-scale entry resolves through `calc(var(--rf-xray-font-size, 13px) * <multiplier>)`, so a single CSS variable rescales the whole shell on the next style flush. The `:general :density` slot (`#{:cosy :compact}`, default `:cosy`; `:compact 12px` / `:cosy 13px`) has two in-shell writers, since the Settings radio was removed 2026-05-27: the palette's `Cycle display density` item (`:palette/cycle-density`, flipping `:cosy ↔ :compact` through `:rf.xray/settings-update :general :density`) and `init! {:density …}`. Both reach `effects/apply-density-font-size!`, which writes the resolved px value into `--rf-xray-font-size` on BOTH the Xray shell root AND `<html>` (so popout/fullscreen mounts inherit). A host `:root` rule (`:root { --rf-xray-font-size: 14px }`) rescales every typographic surface without a code change. The px helper also maps `:comfy` to 14px, but `:comfy` is not a shipped choice; an unknown density keyword falls back to `:cosy` 13px. Distinct from `--rf-xray-text-size` (the separate text-size var). | Default-config Xray, the command palette's `Cycle display density` item, popout window with no inline-shell ancestor, host stylesheet override fixture, persisted-density localStorage fixture. | Open the palette (Cmd/Ctrl+K), invoke `Cycle display density` (Cosy → Compact), assert `--rf-xray-font-size` reads `12px` on both shell root + `<html>`, assert every `:body`/`:caption`/`:micro` token rescales via computed style; invoke it again, assert `13px`. Open popout, repeat assertion (no inline-shell ancestor). Apply host `:root { --rf-xray-font-size: 14px }` override, reload, assert tokens rescale ~1.08×. | A persisted `:comfy` payload: the `:rf.xray/density` sub reads it as `:cosy` and the palette cycle flips it to `:compact`. Unknown density keyword → `:cosy` fallback. JVM test runner (no DOM) — writer is a clean no-op. | After 20 palette density cycles, idempotent writer leaves no orphaned `--rf-xray-font-size` declarations on either root; persisted value round-trips through reload. | Active density keyword, computed `--rf-xray-font-size` on shell root, computed `--rf-xray-font-size` on `<html>`, popout-window computed style, host-override stylesheet presence, persisted localStorage `:density` value. | Xray unit gate (`settings/effects_cljs_test.cljs` — the `density->font-size-px` mapping and its `:cosy` fallback; `settings/effects_dom_cljs_test.cljs` — the writer's `--rf-xray-font-size` stamp and its restore; `palette/events_cljs_test.cljs` — the palette cycle drives the setting and the sub) + Xray browser feature gate (host-override + popout inheritance covered by the shell-feature sweep, sub-row of `runShellFeatureSweep`). | `partial` (spec 007 §Sizes + spec 015 `:density`; popout-inheritance sub-row missing — file follow-on if a regression surfaces). |
| Event-lifecycle status colour | The 5-status taxonomy (`:in-flight` / `:settled-success` / `:settled-error` / `:paused-by-tool` / `:stale`) maps via the central pure fn (`event_status_colour.cljc`). Two consumer sites — L2 event-row (`shell.cljs`), L4 Trace timeline bar (`panels/trace.cljs`) — MUST read the SAME status token for the SAME cascade. Cross-site consistency is the invariant. (Earlier drafts also surfaced the status on a header dot in the retired Event/Handler panel; rf2-ad7zx.17 removed that dot, and rf2-5gl5r subsequently retired the panel itself in favour of the Epoch panel — which carries no status dot either.) | Deliberate-throw testbed (`/testbeds/deliberate-throw/`) — handler-throw + flow-throw fixtures give the `:settled-error` cascade; counter testbed gives `:settled-success`. | Drive one cascade per status (`:settled-success` via counter inc; `:settled-error` via throw-handler); for each, focus the L2 row, open L4 Trace tab; read `data-rf-xray-status` attribute on L2 row + L4 Trace status bar (`rf-xray-trace-event-bundle-status-bar-*`); assert both carry the same status keyword for the same cascade. | Empty trace: no rows to assert. Cascade with no terminal outcome: `:in-flight` fallback. Mode flip to RETRO: `:stale` overrides outcome unless `:settled-error`. | After 20 mixed cascades, every cascade's consumer sites stay in lockstep; no regression where one site forgets to read the central fn. | Per-cascade dispatch-id, L2 `data-rf-xray-status`, L4 Trace bar testid suffix. | Xray unit gate (`event_status_colour_cljs_test.cljc` — pure-fn JVM coverage) + Xray unit view gate (`event_status_colour_view_cljs_test.cljs` — synthetic-trace cross-site walk) + Xray multi-frame e2e gate (`event_status_colour_cross_site_e2e_cljs_test.cljs` — REAL host frame dispatch → trace bus → spine focus → 2-site `data-rf-xray-status` walk per rf2-b8pui). | `covered` (rf2-b8pui) |
| machines-viz chart-import integrity | `tools/machines-viz/` is the canonical home of the chart primitive (per rf2-o9arp / PR #1570; xyflow + elkjs since the rf2-gpzb4 migration); Xray imports `day8.re-frame2-machines-viz.chart/MachineChart` **directly** (the older `chart.svg` / `chart.layout` / `chart.interaction` re-export shims were removed). The `:advanced` build MUST NOT strip the chart; the chart must render with > 0 layout nodes when a non-trivial machine is selected. | Deep-machine testbed (deterministic non-trivial machine definitions); `:advanced` production-elision build. | Open Machines tab against deep-machine, assert the xyflow chart renders ≥ 1 layout-node child; build the artefact under `:advanced` and assert the chart still renders. | Chart ns stripped under `:advanced`: chart fails to render, fail with diagnostic. elkjs unavailable: layout-fallback still renders. | After 20 machine snapshots / re-renders, the chart node count remains stable. | Layout engine string, node count, `:advanced` build presence test, chart ns reachability probe. | Xray browser feature gate (covered transitively by `runDeepMachine`'s `rf2-bz72m` chart-render assertion) + bundle-isolation gate (`tools/machines-viz` jar). | `covered` |
| Routing L3 tab handoff | `PANEL_HANDOFFS` walks all 10 Dynamic L3 tabs including `:routing`; the Routing tab mounts the `rf-xray-routing` canvas; switching to the tab via the tab-button succeeds. (`r` is a tab *label* mnemonic, not a key — the tab is reached by click or command palette; no `r` handler exists in `keybinding.cljs`, per 007 §Trimmed pending demand.) | Multi-frame app (carries route slots); shell sweep affordance over the ten-tab L3 bar. | Click `rf-xray-tab-routing`; assert `rf-xray-routing` canvas mounts. | Empty route registry: silent empty state. Frame-picker switch: lens re-binds. | After 20 tab switches the handoff is idempotent. | Tab `aria-selected` state, canvas root testid, frame picker selection. | Xray browser feature gate (`runShellFeatureSweep` walks `PANEL_HANDOFFS` per rf2-tgp6i). | `covered` |
| Static surface frame isolation | The Static composer's `reg-view` registrations resolve to `:rf/xray` (the same Spec 002 §Reading the frame from React context frame-context the Dynamic panel uses). A sub-row of the Frame-isolation invariants (I1–I4) row above, called out separately because the Static composer is its own ingress and a frame leak there would not show in the Dynamic surfaces those suites drive. | Static mode (unconditionally available, rf2-8l3uk) plus a multi-frame host. | Open Static, walk the 5 sub-tabs, open the host's Views panel, assert no Static-namespaced render rows appear in the host frame's Views. | Frame-leak regression: a Static-panel `reg-view` resolves to `:rf/default` instead of `:rf/xray`. | After 20 Static sub-tab walks, isolation holds. | Subscribed-frame attribution per render row. | None yet: no suite walks the Static sub-tabs against a host frame's Views, and the `:owning-frame` render tag the Direct path would filter on does not exist (`018` §8). | `partial` (the I1–I4 suites cover the Dynamic surfaces; the Static ingress sub-row is ungated — file a follow-on if a regression surfaces). |
| Two-verbs-two-homes Routes | Static Routes + Dynamic Routing serve distinct lenses (browse-all vs. focused-event) and BOTH MUST exist; the Static Routes `→ Dynamic` jump chip MUST flip mode + open the Dynamic Routing tab. Normative lock per DESIGN-RATIONALE Lock #15 / `016` §Routes — two verbs, two homes (rf2-o5f5f.3). | Routes-aware testbed (registered routes); Static mode unconditionally available (per rf2-8l3uk). | Assert both Static Routes (`rf-xray-static-routes`) and Dynamic Routing (`rf-xray-routing`) panels mount independently; click the Static `→ Dynamic` jump chip, assert mode flip + tab open. | Only one home present: convention violation, fail with explicit cite to the lock. | After 20 jumps, both lenses survive remount cleanly. | Mode keyword after jump, active tab keyword, both panel canvas testids. | Xray multi-frame e2e gate (`static_routes_panel_e2e_cljs_test.cljs` — jump-chip flips `:rf.xray/mode` `:static → :dynamic` and opens `:rf.xray/selected-tab :routing`, per rf2-wj46n) + Dynamic Routing panel e2e gate (`routing_e2e_cljs_test.cljs`). | `covered` (rf2-wj46n) |
| Mode-signal mechanism (3 stacked signals) | The 3 stacked mode signals — chrome silhouette (3-layer Static vs 4-layer Dynamic), mode **dropdown** at chrome-ribbon-right (rf2-4vp5j), motion dampening (Static reduces transition durations) — MUST all be present + consistent with the active mode. The 2-px left-edge stripe this row once counted as a fourth signal was REMOVED (rf2-4yemd, Static mirror by rf2-y8doi.30) and is pinned absent by `chrome-ribbon-has-no-left-edge-stripe`, so it is not a coverage obligation. Per `007` §Static-mode + `018` §Static surface architectural section. | Static mode unconditionally available (per rf2-8l3uk). | Assert each of the 3 signals against the active mode: chrome silhouette via DOM structure (presence/absence of L2 event list), mode pill via `data-active-mode`, motion dampening via the user-override reduced-motion axis. | Reduced-motion user override: dampening already on regardless of mode. | After 20 mode toggles, all 3 signals remain in lockstep with `:rf.xray/mode`. | Per-signal sentinel: chrome layout DOM hash, pill `data-active-mode`, motion-axis flag. | Xray browser feature gate (chrome silhouette + pill covered by `runStaticModeChromeAndChord`; motion dampening covered by visual-language sub-gate, partial). | `partial` (chrome silhouette + pill covered; motion-dampening sub-gate missing — file follow-on if a regression surfaces). |

## Gate ownership

| Gate | Scope |
|---|---|
| `tools/xray` unit gate | CLJ/CLJS helper, registry, config, shell, trace collector, and panel view tests. Intended default local/CI coverage for Xray internals. |
| Frame-singleton guard (rf2-1w07r — EPIC closed via rf2-nesy9) | Source-text JVM guard (`frame_singleton_guard_test.clj`, in the `clojure -M:test` gate) flagging the two singleton-class anti-patterns — a bare `{:frame :rf/xray}` / `(rf/subscribe :rf/xray …)` literal, and a global `rf/dispatch` wired to an `:on-*` handler — in any file under `tools/xray/src`. The rf2-nesy9 sweep migrated EVERY panel / modal / static surface to the captured-instance-frame pattern (reg-view-injected `dispatch`, a threaded `dispatch-fn`, a render-time `(rf/current-frame-id)` capture, or a `(rf/capture-frame)` frame api for async/held ops), so the `pending-migration` allowlist is now **EMPTY** — the whole `tools/xray/src` tree is locked clean (an allowlist-honesty test keeps it empty). The few legitimate production-singleton seams (trace-collector `note-suppressed!`, share-URL on-load restore, per-feature `hydrate!` init) target the shell via the named `defaults/default-frame-id` Var, never a bare map literal. The two-instance state-isolation acceptance (distinct tab/mode/focused-epoch per shell) is pinned by the CLJS `two_instance_isolation_cljs_test.cljs`. |
| Xray testbed decks | The decks under `tools/xray/testbeds/` are driving surfaces, not a browser gate of their own. Each deck that declares a build compiles under the `examples_compile` sweep (§What gates the testbed SOURCES below), and `standard_epochs`, `edn_inspector` and `managed_http` are compile-only. `two_frame_isolation` — one `standard_epochs` app mounted in two isolated frames, `:above` + `:below` (rf2-wa8my) — also boots under a nightly feature-gate scenario that claims no matrix row, because it reads each frame's app-db directly and opens no Xray tab (rf2-y8doi.28). The PR-smoke browser tier is the feature gate's `smoke: true` subset described at the top of this page, not these decks. |
| Xray browser feature gate | New deterministic feature matrix gate described by this spec. It owns direct and failure paths for each matrix row. It can be sharded by panel but should report one matrix. Includes the `machine-epochs multi-machine frame-isolated stepper` scenario (rf2-q3lfm) over the `:examples/machine-epochs` testbed — see §The machine-epochs frame-isolated stepper below. |
| Xray 20-event/load gate | Explicit or pre-commit/pre-PR stress gate only. It is not default CI. It reuses the feature testbed and runs the row-specific 20-event/load checks. |
| Production posture (no Xray gate) | The implementation production-elision probes (`npm run test:elision`, `npm run test:browser-prod-elision`) root `re-frame.*` namespaces only; `check-bundle-isolation.cjs` greps the counter example's no-feature production bundle for tooling-sibling and dev-dependency sentinels. **No gate in this repo roots an Xray namespace**, so nothing proves Xray's absence from a release bundle that installed it. An Xray-rooted release probe was considered and DECLINED under rf2-qqn9: Xray is kept out by build placement, and the host owns that placement. |

## What gates the testbed SOURCES (rf2-p0b0)

The rows above say what gates Xray's *behaviour*. This section says what
gates the testbed files themselves, because "nothing compiles this file"
was filed as a defect (rf2-p0b0) and the answer turned out to be no.

**Every testbed under `tools/xray/testbeds/` that declares a build is
compiled by the `examples_compile` sweep — on any PR touching that
surface's enumerated paths, and unconditionally nightly.**
`npm run test:examples-compile`
(`implementation/scripts/check-examples-compile.cjs`) DERIVES its build
list from `implementation/shadow-cljs.edn` by scanning for `:examples/<name>`
and `:testbeds/<name>` keys, so a newly-declared testbed build is swept
with no roster to update. It runs as the `cljs-examples-compile` job in
`.github/workflows/test.yml`, and unconditionally nightly in
`.github/workflows/expensive-tests.yml`. `shadow-cljs compile` exits 0 even
when a build emits warnings, so the gate parses the per-build warning count
and fails on `> 0`.

The classifier arms that job from **both** relevant directions
(`.github/scripts/report-changed-surfaces.sh`): `tools/xray/testbeds/*`
arms it, and so does `implementation/http/*` — i.e. both a change to a
testbed and a change to a substrate contract a testbed consumes.

**But that surface is an ENUMERATION, not "anything that can affect the
build", and the gap is deliberate.** `implementation/core/*` is NOT on it:
its classifier arm sets `implementation_jvm`, `cljs_node_test`,
`adapter_diagnostic`, `cljs_browser`, `cljs_prod`, `bundle_isolation`,
`tools_jvm`, `template_expensive` and `mcp_conformance` — but never
`examples_compile`. Every testbed build here
`:require`s `re-frame.core`, so a core-only PR *can* affect these compiles
with this lane skipped. `.github/workflows/test.yml` states the trade at
the `cljs-examples-compile` job: core-only changes "keep their focused PR
gates and rely on the unconditional nightly example compile; they no
longer queue this perennial long-tail job on every PR" — the sweep is
~10 minutes and core is the most-touched surface in the repo. So the
per-PR guarantee is bounded by the enumerated surfaces, and the nightly
covers the remainder.

Seven of the nine directories here declare a build (`edn_inspector`,
`machine_epochs`, `managed_http`, `panel_gallery`, `routes_epochs`,
`standard_epochs`, `two_frame_isolation`). The two that do not are not
apps: `feature_matrix` is the scenario driver (`scenarios.cjs` + README)
and `runner` is the shared queued-step library the decks require, so it is
compiled transitively by every deck that requires it.

**But a compile is a weak gate, and this is the point.** Measured under
rf2-p0b0 on the `:examples/managed-http` build:

| Tree state | Result |
|---|---|
| tip | exit 0, 555 files, **0 warnings** |
| the exact pre-fix rf2-s4dp defect (parent of the fix commit) | exit 0, **0 warnings** |
| `record-in-flight!` renamed to a non-existent var (control) | **2 warnings** → gate fails |

So the compile gate is real and non-vacuous — it catches a renamed or
re-aritied registry function — but it did **not** catch rf2-s4dp, and could
not have. That defect was a missing `:frame` key in a map literal plus two
legal alternate arities; none of those is a compile error or warning in
ClojureScript. **Adding a compile gate was therefore never the remedy for
the defect that prompted the question, because the compile gate already
existed and was green.**

**Runtime coverage is deliberately partial and centrally owned.** It is not
delivered by a per-testbed `spec.cjs`: the framework testbeds' `spec.cjs`
files were deliberately DELETED across four migration waves, their
assertions moved to cheaper per-PR CLJS/JVM unit tests (85% of 124
assertions — see `tools/story/spec/Migration-Audit.md`). What remains is
one central driver, `tools/xray/testbeds/feature_matrix/scenarios.cjs`,
whose `STAGED_SURFACES` list stages and drives a subset of builds. Of the
seven testbed builds here, four are staged (`panel_gallery`,
`routes_epochs`, `machine_epochs`, `two_frame_isolation`) and three are
compile-only (`standard_epochs`, `edn_inspector`, `managed_http`) —
though `standard_epochs`' ladder view is exercised transitively, because
`two_frame_isolation` mounts it twice.

**That residual gap is accepted, not overlooked.** Closing it means a
browser build plus Playwright assertions per testbed, on a gate whose full
tier already runs nightly rather than per-PR; the managed-HTTP *Xray
rendering* surface is separately covered by the `managed http and effects
rows` scenario over `testbeds/http-toggle`. A testbed is a driving surface
for a human or a scenario, not a shipped artefact, and compile coverage is
the floor its cost justifies. Add a `feature_matrix` scenario when a
specific Xray behaviour needs pinning — not to give a testbed coverage for
its own sake.

## Tier-2 deepening (rf2-5aw5v.9..14)

Tier-1 (rf2-160di + rf2-gdqm1) promoted the per-panel rows above to
`covered`; Tier-2 (rf2-5aw5v.9 / .10 / .11 / .12 / .14) deepens the
cross-cutting framework contracts that sit BETWEEN panels and the
host. After rf2-qd5r6 deleted the sidebar-era rigorous testbed (it
was already skipped under rf2-xy4yb because §12's panel sweep
targeted the pre-spec/018 16-panel chrome) the Tier-2 scenarios that
pin contracts not covered elsewhere were rehomed onto the surviving
canonical surfaces:

| Tier-2 bead | Surface | Spec | Home + status |
|---|---|---|---|
| `rf2-5aw5v.12` (L-12) | Embedding-contract Panel surface across every tab namespace; frame isolation; registry-key namespacing | [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) | **Covered by Tier-1.** The current 4-layer chrome's L3 tab bar (one tab per registered `:dynamic` panel) + L4 detail-panel handoff is covered by `tools/xray/test/day8/re_frame2_xray/panels_e2e/parallel_frames_e2e_cljs_test.cljs` (mount + tabs + isolation) and `tools/xray/testbeds/feature_matrix/scenarios.cjs §runShellFeatureSweep` (per-panel handoff sweep). (This row named `tools/xray/testbeds/parallel_frames/spec.cjs` until rf2-p0b0; that file was DELETED under rf2-lcg1z — Wave 2 of the Story migration audit — and the row went on claiming cover from it. See `tools/story/spec/Migration-Audit.md` §B7.) |
| `rf2-5aw5v.9` (L-9) | Pop-out / inline-host launch-mode duality; opener-close diagnostic | [`011-Launch-Modes.md`](./011-Launch-Modes.md) | **Covered.** `tools/xray/testbeds/feature_matrix/scenarios.cjs §runLaunchModesTwentyEventLoad` pins overlay + popout shared-runtime across 20 host dispatches. The opener-gone watchdog overlay is not specifically exercised post-rf2-qd5r6; file a follow-on if a regression surfaces. |
| `rf2-5aw5v.14` (L-14) | Multi-frame isolation through the panel layer (rf2-tijr Option-C lock) | [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §State isolation | **Rehomed.** `tools/xray/test/day8/re_frame2_xray/panels_e2e/parallel_frames_e2e_cljs_test.cljs` (deftests `rf2-ulpp8-*` / `rf2-1p1j4-*`) exercises `:rf.xray/set-target-frame` + `:rf.xray/target-frame-db` against the canonical `:above` / `:below` frames; asserts target-frame round-trips, per-frame `:counter` projection, and Xray-side isolation (no `:counter` slot leak into `:rf/xray`'s app-db). |
| `rf2-5aw5v.10` (L-10) | Shell auto-mount, missing-host diagnostic, settings reset, keybindings, config knobs, production elision probe | [`011-Launch-Modes.md`](./011-Launch-Modes.md) + [`015-Configuration.md`](./015-Configuration.md) | **Rehomed.** `tools/xray/test/day8/re_frame2_xray/panels_e2e/configure_multi_key_e2e_cljs_test.cljs` pins `configure!` multi-key + partial-update semantics plus `set-auto-open!(null)` / `set-layout-host-selector!(null)` reset round-trips. (This row named the Playwright original, `scenarios.cjs §runConfigurePartialUpdate`, for some time after the rf2-rviu8 CLJS port superseded it; the dead JS scenario was deleted and this row corrected under rf2-2rtt6.78, when the new repo-wide ESLint gate's `no-unused-vars` reported the function as unreferenced.) Inline auto-mount + `Ctrl+Shift+C` toggle + missing-host diagnostic are covered by `runShellFeatureSweep` + the production-elision gate. |
| `rf2-5aw5v.11` (L-11) | 20-event/load stress invariant — caps + virtualisation + no duplicate dominoes | This file §20-event/load gate | **Covered.** The canonical heavyweight equivalent lives under `npm run test:xray-feature-gate`'s `runLargeDispatcher` / `runTraceBudgetSaturation` / `runLaunchModesTwentyEventLoad` scenarios (the matrix's explicit pre-PR gate). |

`rf2-5aw5v.13` (L-13 Clojars publish probe) is excluded from the
Tier-2 cluster — it depends on a release decision and is tracked as
its own bead.

## Vision — bug-class coverage column

**Discipline:** every bug-class named in
[`019-Cross-Cutting-Insight.md`](019-Cross-Cutting-Insight.md) §2 (and
in the per-tab spec's bug catalogue, e.g.
[`003-Machine-Inspector.md`](003-Machine-Inspector.md) §The bug
catalogue) MUST have at least one matrix row that exercises the
**user-visible insight** the catalogue promises.

Add a **`bug-class`** column to the matrix mapping each test row to
the bug-class ids it covers (e.g. `M.1`, `M.2`, `S.1`, `F.4`, `R.3`).
For a row covering more than one bug-class, list all ids
comma-separated.

The audit query: "every `M.*` / `R.*` / `S.*` / `F.*` id appears in
at least one matrix row." A failing audit blocks PR merge — the
spec promised the user this affordance; the matrix must verify it
ships.

This closes a structural gap: today's coverage matrix tests
**surfaces** (does the panel render? does the click work?); the
bug-class column tests **insight delivery** (does the surface answer
the question the user came in with?).

## The machine-epochs frame-isolated stepper (rf2-q3lfm)

The `:examples/machine-epochs` testbed (port 8033,
`tools/xray/testbeds/machine_epochs/`) is the MULTI-MACHINE,
FRAME-ISOLATED state-machine stepper. It is a testbed surface that
CONSUMES the existing Xray frame-switcher contract
(`:rf.xray/select-frame`, `018-Event-Spine.md` §Frame dropdown +
`frame_switcher.cljs`) — it adds **no new Xray contract**; this section
documents the testbed's shape so the browser feature gate's
`machine-epochs multi-machine frame-isolated stepper` scenario has a
normative reference.

**Shape.** Ten machine domains (door · traffic · quiz · brew ·
session · fuse · hvac · media · modal · gate) each run in their OWN
frame (`:machine/<track>`) and own their OWN Xray epoch ring. The
final two — `modal` (a MULTI-EVENT transition: one edge `:open ──►
:closed` reached on THREE distinct events, the events-as-nodes
divergence) and `gate` (a MULTI-BRANCH GUARDED fork: `:gate/check`
forks from `:idle` by a guarded candidate vector — `:high` / `:low` /
unguarded-fallback `:rejected`, the guard-fork divergence) — were added
under rf2-vilpfa to cover the two xstate-render-divergence cases the
original eight miss. A left-rail PICKER selects a track; selecting a
track (the picker-row React `:on-click` calls a top-level `select-track!`
boundary):

1. LAZILY creates the track's `:machine/<track>` frame on first entry
   (`rf/make-frame` with an `:initial-events` boot event — BOOT-ON-SELECT, so
   the first observed epoch is the machine's START cascade). Per EP-0027 a
   frame is constructed by the VIEW or at TOP LEVEL, **never inside an event
   handler cascade** (`:rf.error/frame-construction-in-handler`) — and an
   `:fx` still runs inside `*handler-scope*` — so the `make-frame` runs at the
   React `:on-click` (and at boot in `run`) top level, BEFORE the select
   event is dispatched, not in the `:machine-epochs/select` handler;
2. dispatches `:machine-epochs/select`, which sets the SHELL frame's
   (`:rf/default`) runner bookkeeping (`:rf.runner/selected` + per-track
   `:rf.runner/cursors`), and
3. re-points Xray at that frame via the host-facing focus channel
   (`day8.re-frame2-xray.focus/focus!` with `{:frame :machine/<track>}`,
   which fires `:rf.xray/select-frame`), so the Epoch panel cascade,
   the time-travel scrubber, the App-db panel, and the Machine Inspector
   all show ONLY that machine's isolated arc.

**Stepping.** A step writes the per-track cursor in the SHELL (not
observed) and dispatches the step's machine event INTO the machine
frame (`{:frame :machine/<track>}`) — two epochs: a shell cursor write
+ the machine cascade in the machine frame (observed). The cursor and
selection live in app-db (events + subs), not Reagent atoms (rf2-5sjbg).

**Restart** resets the selected track's machine frame
(`destroy-frame!` + re-`make-frame` with the same
`:initial-events` — there is no dedicated reset verb, rf2-lxwpob), so the
ring clears and the machine re-arcs from boot; the track cursor clears. Like
select, the reset runs at the TOP LEVEL (a `restart-track!` boundary called
from the restart button's `:on-click`), NOT in the `:machine-epochs/restart`
handler (EP-0027 frame construction rule); the handler only clears the
cursor and re-points Xray.
The fuse track's boot-on-select THROWS (its initial `:entry` action throws
on boot) — that is the sole machine-action-exception trigger.

**Isolation invariant (the lens).** Each machine's progression is a
clean scrubbable arc in its own ring — switching switches WHICH
isolated arc Xray shows, never interleaving, including across
switch-and-return (pick A → step → pick B → step → return to A: A's
ring is intact and resumes). The browser feature gate asserts this with
a cross-frame flip + a per-track frame-snapshot read off the
`:machine/<track>` frame's **runtime-db** partition (machine snapshots
are durable framework runtime-db state at `[:rf.runtime/machines
:snapshots <machine-id>]` per EP-0001 / `re-frame.machines.paths`, read
via `(:rf.db/runtime (re-frame.core/frame-state-value id))` — NOT app-db, and not
`:rf/default`).

**Localized runner.** The multi-track / frame-per-machine machinery is
machine-epochs-LOCAL (the deck's own ns); the shared `runner.core`
(consumed by the five single-track decks) is UNCHANGED — the deck
reuses its host-frame + cross-frame-dispatch idiom as a building block
only. The CLJS render-fidelity harness
(`panels.epoch.machine-epochs-harness-cljs-test`) drives the substrate
directly and is decoupled from this view.

**Parallel `:always`-round BROWSER proof (rf2-gy9ln).** The render-fidelity
harness above is Node-only, while browser selection runs `-dom-cljs-test`
namespaces; the nightly `runMachineEpochs` asserts per-frame snapshots and
explicitly delegates deep microstep render fidelity to the CLJS unit. So
removing the projection/view clause for parent-owned parallel `:always` rounds
(rf2-bvwv4q) could leave every browser check green. The focused browser proof
`panels.epoch.machine-epochs-always-round-dom-cljs-test` closes that gap: it
drives the REAL co-selected parallel-round machine (`:go` moves both regions
`:idle → :staged`, then a parent round co-selects both `:staged → :done`),
captures the emitted `:rf.machine/transition` + `:rf.machine.microstep/
transition` traces, and mounts the REAL render layers into a real Chromium DOM
(Reagent adapter → `reagent.dom.client` → `flushSync`). It asserts (1) the
SHARED machine-cascade mini-pipeline renders TWO first-class `[ALWAYS]` round
rows (regions `:a` then `:b`, shared `data-cascade-round-index` 0) with NO
`[ACTION]` row — the round is ACTIONLESS yet the rows are visible — and (2) the
focused-event section's chart wrapper renders `data-fired-edge-ids` carrying the
FOUR real regional edges (`:a`/`:b` direct `:go` events + `:a`/`:b` `:always`
rounds), computed from the real trace via `extract-fired-edge-ids`. Filtering
`:rf.machine.microstep/transition` out of the projection drops the round rows;
filtering it out of the fired-edge derivation drops the two `:always` edges —
either reddens the proof.

## The freehand-views deck — RETIRED with the Views panel's Freehand sections (rf2-l86mm)

The `:testbeds/freehand-views` testbed (port 8036,
`tools/xray/testbeds/freehand_views/`) was the only shipped Xray deck whose
views were FREEHAND views. It existed for one purpose: putting real connected
occurrences in front of the Views panel's Mounted Views and Declared View
Sites sections (rf2-6pohj), because every other staged surface is
Reagent-hosted and connects no Freehand occurrence, so the browser lane could
otherwise prove those sections RENDER and nothing about what they rendered.

Both sections retired with the Freehand substrate — the disposition, and the
eight-row mapping showing why they could not move to `re-frame.fresco.tool`,
is [`021-Dynamic-Panel-Designs.md`](021-Dynamic-Panel-Designs.md) §3.4.1. The
deck inherited it (rf2-u5b4, against census rows X6/X7/X8): five of the nine
facts its scenario asserted are identity or declaration facts with no answer
on the target, and those five were its entire subject.

**The retirement is EIGHT artefacts and it is split across two passes.** Four
are the deck itself; four more name it by build id or by scenario name from
outside it. TWO of the eight red a gate, and they catch different omissions —
neither catches the other's. Those two travelled with the PANEL, under
rf2-l86mm, because that is the change that makes them red:

| # | Artefact | Pass | State |
|---|---|---|---|
| 4 | the `freehand-views populated Views roster` scenario and its `STAGED_SURFACES` entry in `tools/xray/testbeds/feature_matrix/scenarios.cjs` | rf2-l86mm | **DONE.** The PR-smoke run is derived from these entries rather than from a fixed list, so leaving them standing while the panel goes reds the smoke gate against a section that no longer renders |
| 7 | the canonical covered-row pin in `coverage_matrix_metadata_test.clj` | rf2-l86mm | **DONE — 13 → 12.** This scenario was the sole claimant of the retired `Mounted view reads (Freehand tool door, rf2-7gth0)` matrix row, so removing that row without moving the pin fails `coverage_matrix_metadata_test` |

The remaining six went quietly stale rather than red, and they travelled with
the FREEHAND TREE DELETION rather than with the panel — because rows 2 and 3
are in top-level `implementation/shadow-cljs.edn`, rows 5 and 6 in
`implementation/`, and row 8 in `.github/workflows/`, none of which the panel
pass held. Row 1 waited with them rather than going early: deleting the deck's
source while its build id survived would have left a build compiling a tree
that is not there, which is a worse state than either end of the move. **All
six have now landed**, across three commits rather than one — rows 1, 2 and 3
with the tree deletion itself (rf2-0yp7w.6), rows 5 and 6 with the
config-residue sweep that followed it (rf2-puwyb), and row 8 with the
smoke-tier re-costing (rf2-ano54):

| # | Artefact | Pass | State |
|---|---|---|---|
| 1 | `tools/xray/testbeds/freehand_views/` — `core.cljs` and `index.html` | rf2-0yp7w.6 | **DONE — tree deleted.** Dead source under a build id that was going with it; its `re-frame.freehand` require died with the tree either way |
| 2 | the `:testbeds/freehand-views` build in `implementation/shadow-cljs.edn` | rf2-0yp7w.6 | **DONE — build map deleted.** Left standing it would have been a build compiling a deleted tree |
| 3 | its port-8036 `:dev-http` entry in `implementation/shadow-cljs.edn` | rf2-0yp7w.6 | **DONE — entry deleted, and the 803x slot is free again.** Left standing it would have served a root that no longer exists |
| 5 | the `DEV_HTTP` entry and the port-band comment in `implementation/scripts/dev-testbed.cjs` | rf2-puwyb | **DONE — entry deleted; the port-band comment re-tensed rather than deleted, and it now records 8036 as free again.** Left standing, the launcher would have advertised a URL for a build id shadow-cljs no longer knows — silently, because the drift guard in `dev-testbed.test.cjs` runs shadow-cljs → `DEV_HTTP` and never the reverse |
| 6 | the build→URL row in `implementation/README.md` | rf2-puwyb | **DONE — row deleted.** Left standing it would have documented a testbed nobody can start |
| 8 | the PR-smoke enumeration in `.github/workflows/test.yml` | rf2-ano54 | **DONE — 5 scenarios → 4, 4 staged surfaces → 3, 4 bundles → 3, and 12 → 11 in the nightly sweep.** Left standing it would have named a scenario that no longer exists |

Beyond the eight, three re-reads rather than removals. The dated aggregate
costings in `implementation/scripts/serve-and-run-xray-feature-gate.cjs` and in
this document's own opening move with row 8's numbers, but neither names the
deck and both already carry the date they were measured, so re-date them rather
than treat them as breakage.
[`027-Fresco-Evidence.md`](027-Fresco-Evidence.md) tracks this deck's build
id, port and scenario slot as freeing up together — rows 1, 2 and 3 have now
freed all three. And the reactively-driven repaint the scenario asserted
(rf2-2t126) was the only browser-level proof that
`:adapter/activate-derived-value!` holds end to end in a real DOM, while
`re-frame.fresco.impl.collector` calls that hook as well as the Freehand
observation port did — so it is the one asserted fact whose MECHANISM outlives
the deck, and it needs a witness on the surviving caller rather than retiring
with the Freehand cells.

## Cross-references

- [`000-Vision.md`](./000-Vision.md) - panel inventory and the five canonical questions.
- [`019-Cross-Cutting-Insight.md`](./019-Cross-Cutting-Insight.md) - the bug-class catalogue this matrix must cover.
- [`007-UX-IA.md`](./007-UX-IA.md) - chrome, keyboard, source-coordinate, redaction, launch, and production posture.
- [`011-Launch-Modes.md`](./011-Launch-Modes.md) - true-inline host default, optional overlay/debug chrome, pop-out, MCP coexistence, preload, and mount lifecycle.
- [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) - trace buffer, filter vocabulary, privacy gate, lifecycle, and production elision.
- [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) - owning `:rf.xray/*` ids for panel subscriptions/events/effects.
- [`015-Configuration.md`](./015-Configuration.md) - host-visible configuration keys and defaults.
- [`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) - per-tab content contracts (the Epoch panel as the numbered cascade — it supersedes the retired Event/Handler panel per rf2-5gl5r and absorbs the "fx handlers that ran" block, and flows surface as its FLOW step; Routes as two verbs in two homes, Static Routes and Dynamic Routing; the Settings popup; the retirement records of the Issues and Performance panels).
- [`018-Event-Spine.md`](./018-Event-Spine.md) - 4-layer chrome, spine binding, filter pills, Settings popup, isolation invariants, data-classification rendering contract.
- [`012-Views.md`](./012-Views.md) - the Views tab's earlier three-group, clustering design, superseded by `021` §3 (the shipped reactive-flow panel).
- [`../../../testbeds/README.md`](../../../testbeds/README.md) - existing reusable testbeds for schema violation, non-trivial app-db, large/sensitive dispatchers, deep machines, long flows, HTTP, SSR, and multi-frame scenarios.
