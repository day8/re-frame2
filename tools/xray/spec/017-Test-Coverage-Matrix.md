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
epic + rf2-ybjkx + rf2-o9arp + rf2-b76v4):

| Status | Meaning |
|---|---|
| `covered` | The row has unit/helper/view coverage plus a browser-level feature path, including failure/empty coverage where applicable. |
| `partial` | Some unit/helper/view or smoke coverage exists, but the deterministic feature path, failure path, or 20-event/load re-check is missing. |
| `deferred` | Bookkeeping placeholder: the row's promotion to `covered` is gated on a specific follow-on bead (referenced inline in the Status cell). |
| `missing` | No meaningful Xray-specific automated coverage exists yet. |

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
system of record. At the time of writing the split is 5 scenarios over
4 surfaces on the PR path against 17 over 12 nightly, so **most
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
| Event Detail | Opening Xray lands on the latest dispatch cascade and shows event vector, source, db changes, fx, subs, renders, duration, and other traces in one readable panel. | Non-trivial app-db, deterministic exception cascade, HTTP failure cascade, source coords, sensitive/large payloads. | Open with `Ctrl+Shift+C`, drive one representative dispatch, select its row, assert the six-domino cascade and source chip. | Empty trace shows no-events copy; handler/fx/sub/view exception appears in `:other` and links to the parent dispatch. | After 20 mixed dispatches, latest cascade remains selected, rows stay capped/virtualised, no duplicate dominoes. | Selected dispatch id, grouped trace ids by domino, event vector, source coord URI, db changed paths, exception summary. | Xray browser feature gate plus `tools/xray` unit gate. | `covered` |
| L2 Event List (the spine timeline) | L2 event list shows the last N dispatches as single-line rows (latest-on-bottom); rows decorated by gutter glyph (`● ◉ x ▥ ↺`) + badges (`⚠ 🌐 🤖`) + redaction marker; LIVE-tracking + sticky-on-older + auto-scroll-to-bottom; row click → spine focus + L4 detail panel rebinds. Head-row pulse cue indicates LIVE; pinned-row glyph indicates RETRO (the Mode pill widget was dropped). | Drain-depth/load path, multi-frame cascade, agent-origin dispatch (`:origin :re-frame2-pair-mcp`), same-event sibling dispatches. | Dispatch three events, assert row order (latest at bottom), assert gutter + badges per row, click a row, assert L4 detail panel rebinds + spine `:mode` flips to RETRO with the focused row carrying the pinned-row glyph. | Empty list shows cold-start copy ("Click around your app…"); stale/destroyed-frame events do not crash; rows survive without dispatch id (rare). | Drive 20 dispatches and assert latest row remains selected when at head, sticky-on-older holds, virtualisation budget correct. | Row contents (gutter / event-id / badges / redaction marker), focused dispatch-id, spine `:mode` value, virtualised row index range. | Xray browser feature gate. | `covered` |
| Time Travel | Scrubber passively views epoch history; explicit rewind/restore is confirmed and failures are surfaced structurally. | Epoch history, restore success, six named restore failures, pinned snapshots, evicted epochs, multi-frame history. | Open Time Travel, scrub to older epoch, pin, confirm rewind, assert host app-db changes only after confirmation. | No epoch artefact/empty history shows empty copy; aged-out pin is detached; restore failure names reason and preserves live state. | After 20 dispatches, scrubber range/count stays correct, pin store cap holds, restore target is still addressable or visibly evicted. | Epoch index/count, target epoch id, pin store, restore result/failure keyword, app-db-value hash before/after. | Xray browser feature gate plus time-travel unit/view gate. | `covered` |
| App-DB Diff | Changed slices are first-class; before/after diff, clickable path segments (rf2-e9tb0), path-origin chips (rf2-s8r6c), reserved slices, redaction, and large elision are visually distinct. | Non-trivial app-db, deep vector index change, sensitive and large values, handler + flow writers on overlapping paths. | Dispatch deterministic app-db update, open App-DB, assert touched slice, before/after values, unchanged sibling collapse, click a path segment and assert the inspector popup opens at the path-prefix, assert per-slice origin chip ([fx :db] / [flow :id] / [mixed]). | Empty/no diff copy; missing before/after snapshot degrades cleanly; redacted and large-elided markers preserve structure. | After 20 updates across different slices, changed-path summary remains accurate and rendering stays capped. | Changed paths, before/after hashes, visible rows, redacted/elided marker counts, path-origin chip counts. | Xray browser feature gate plus app-db diff unit/view gate. | `covered` |
| Trace | Raw trace feed filters by all canonical axes and remains the common substrate for panel drill-ins. | Mixed operations, severities, frames, origins, source coords, redacted events, clear-buffer control. | Open Trace, assert rows, axis chips, AND-composed filters, clear filters, row click to Event Detail, source chip. | Empty buffer and no-match filters are distinct; sensitive drops increment redaction count; unknown filter axes are ignored. | After 20 mixed dispatches, counts, visible rows, filters, and buffer cap remain consistent. | Active filters, row count/total, last 20 trace compact rows, axis histograms, suppressed count. | Xray browser feature gate plus trace-bus/filter unit gates. | `covered` |
| Views (incl. nested subs) | Views tab shows mounted / re-rendered / unmounted three-group layout; each row lists subs used + return values inline; cluster-large-grids ≥ 50; per-component inline drilldown with props-diff headline. Sub cache status (fresh / re-running / invalidated / cached-no-watcher / error) renders inline as glyph prefix on sub-id. | Render-tracker fixture with mount cascades, re-render cascades, unmount cascades, ≥50 same-identity-key clustering, throwing sub, large/redacted sub return. | Trigger a render cascade, open Views, assert three groups populated, assert per-row subs-used list with return values, assert re-rendered group's two-column "Rerendered because" layout with trigger sub marked `✱` (amber) and non-trigger subs marked `·` (muted-grey); markers carry hover tooltips explaining each meaning. | No views rendered this cascade; clustering threshold > 50; expand-cluster reveals individual instances; per-component inline drilldown headline = props diff; redacted/large output renders per §15. | After 20 mixed dispatches, three-group rows + cluster counts stay accurate and isolation filter holds. | Cascade-id, per-group row counts, cluster counts, trigger sub-ids, isolation filter (no Xray-namespaced components in host frame's Views). | Xray browser feature gate plus views unit/view gate. | `covered` |
| Mounted view read retention (rf2-7gth0) | There is nothing for the Views panel to retain, and that is the claim. The substrate holds ONE row per CONNECTED occurrence and drops it at disconnect — no accumulator, no interval log, no weak store, no ordinal mint and no second retention knob — so the whole class of hazards the donor tier's cumulative projection carried (unbounded growth under churn, a tool keeping an unmounted cell alive, a query→cell cycle) cannot arise here. Xray's consumer adds no state of its own: it holds no ledger, installs nothing, and every read is a projection taken at the moment the panel asks. History remains Spec 009's retained ring under Spec 009's one knob, folded at read time. | Repeated connect/commit/disconnect churn; two simultaneous occurrences of one view; a production `:advanced` + `goog.DEBUG=false` artefact. | Assert the roster returns to its baseline cardinality after churn (a disconnect REMOVES a row rather than labelling it); assert the consumer namespace declares no `defonce` state; execute the production-elision gate, which proves the substrate's index is unreachable under the dev gate Closure folds away. | A row that survived its occurrence's disconnect would fail the churn assertion; a reintroduced lifetime tally fails the donor-shaped-fact EQUALITY pin. | Churn returns to baseline exactly, because the index cannot grow along a time axis it does not have. | Roster cardinality before/after churn, the absence pin's empty intersection, and the production bundle probe. | `mounted_views_cljs_test` (churn + the absence pin), the Freehand substrate's own `occurrence_index_cljs_test`, and the production-elision gate. | `covered` (rf2-7gth0) |
| Mounted view reads (Freehand tool door, rf2-7gth0) | The Views panel reads ONLY `re-frame.freehand.tool` — `read-mounted-views` for the connected-occurrence roster, `explain-render` for the bounded read-time cause fold, and `read-view-manifest`/`read-view-dependencies`/`read-view-event-sites` for the Declared View Sites section. Never a private-state read, never React-tree scraping, and never an ownership claim: the door has no registry, so Xray installs nothing and holds nothing. Occurrence identity, the stated lowering, the latest committed generation, the commit's own reads, the explanation's `:cap`/`:uncorrelated` loss and the evidence schema all render truthfully; nothing inferred is presented as exact, and the donor's lifetime tallies are ABSENT rather than approximated. | A real Freehand cell commit → roster row; two simultaneous occurrences of one view; a commit correlated to a retained run; a commit with no cascade in scope; a correlated commit whose run the window no longer holds; an unrecognised evidence schema; a producer that bumps its own schema var; a real app's `{:compiled true}` declaration with a literal sub site and a literal event site; the same app's INTERPRETED declaration; a host with nothing connected. | Drive a real commit through `cell/commit!`, assert the row carries the occurrence key, `:lowering`, `:generation`, `:connection`, the commit's `:reads` and the explicit `:root :unknown`; assert the cause arm reports `:cause-event-id` + `:sub-ids` and each loss arm names its own reason; assert `schema-status` reports the supported schema; assert `view-sites` projects the app's real sub/event sites with their `:source-coord`, and that the interpreted declaration reports `:basis :opaque` + `:no-static-analysis`; render the real panel fn and assert the row + site text. | An unrecognised schema degrades rows to `[]` + renders the honest schema banner (no mis-parse); a producer bump alone does NOT widen consumer support (the pin is a literal); an uncorrelated commit renders `uncorrelated` and its candidates as `N leads`, never as the cause; an interpreted declaration renders `unknown, not absent` rather than an empty roster; a host with nothing connected renders the empty state + no Declared View Sites section (silent-when-zero); a clean view renders no diagnostics line. | Row `:occurrence`/`:lowering`/`:generation`/`:reads`/`:root`/`:cause`/`:loss`/`:explained?`, `schema-status` `{:schema :supported?}`, site `:basis`/`:complete?`/`:loss`/`:dynamic?`/`:handler`/`:source-coord`, section/banner testids present/absent, and the EQUALITY pin asserting no donor-shaped lifetime fact reappears on a row. | The consumer retains nothing — the substrate holds one row per CONNECTED occurrence and drops it at disconnect, and the explanation is folded from Spec 009's ring under Spec 009's one knob. No second retention mechanism is introduced. | `mounted_views_cljs_test` (real occurrences + the real app's own compiled and interpreted declarations, via `re-frame.freehand.release-app`), `reactive_panel_view_cljs_test` (the real panel fn), `registry_cljs_test` (sub enumeration + the schema-4 governance pin) + the feature-matrix shell sweep (real chrome runtime, section present, EMPTY arm — the counter host is Reagent-backed so no occurrence connects) and, since rf2-6pohj, the `freehand-views` scenario over the Freehand-hosted deck (`tools/xray/testbeds/freehand_views/`), which is the POPULATED arm in a real browser: three connected occurrences, the stated lowering on each row (one interpreted against two compiled), the commit's own read count (`1 read` on the sole reader, `0 reads` elsewhere), the commit's frame, the absence of a printed `:unknown` root, no schema banner, the Declared View Sites section with its `:static-proof` sub site, its `:static-proof` event site and its `:no-static-analysis` interpreted arm, a REACTIVELY-DRIVEN repaint of the `readout` cell on that same page (rf2-2t126 — the dispatch advances the readout's text on the SAME DOM node, so it is the mounted cell repainting rather than a remount reading a current value), and — the fact that MOVES — three FRESH occurrence keys after an unmount/remount, which a projection over a static registry cannot produce. | `covered` |
| Machines | Machine inspector renders state chart, active states, transitions, actors, timers, errors, and source chips. The chart primitive lives in `tools/machines-viz/` post-rf2-o9arp; Xray re-exports via thin shims (the `machines-viz` shim integrity gate below asserts the re-export is wired and not stripped under `:advanced`). | Deep machine with hierarchy/parallel states, child actor, invoke, timer, guard/action failure, transition history, plus a machines-viz shim integrity affordance (chart SVG renders ≥ 1 layout node; `:advanced` build keeps the re-export). | Start machine, drive transitions, open Machines, assert active state highlight, transition log, actor/timer rows, source chip. | No machines registered; guard/action/invoke failure; destroyed machine; missing source coord. | After 20 machine events, transition history remains ordered and chart render stays stable. | Machine id, active state path, child actor ids, transition ids, timer ids, failure keyword, chart node count. | Xray browser feature gate plus machine helper/view gate. | `covered` (machines-viz shim integrity covered by `runDeepMachine` per rf2-bz72m). |
| Routes | Routes panel shows active route, params/query/fragment, registered routes, navigation history, transition state, and stale-token suppression. | Route registry, navigation success, blocked navigation, not-found, loading/error transition, multi-frame route. | Navigate to route with params/query/fragment, open Routes, assert active strip, registry highlight, history row. | No routes registered; blocked navigation; not-found; stale nav token suppressed and visible in history. | After 20 navigations, history caps at 50, active route remains correct, stale rows do not overwrite live route. | Route id, URL parts, params/query, transition state, nav token ids, history count. | Xray browser feature gate plus routes unit/view gate. | `covered` |
| Schemas / Schema Timeline | Schema timeline lists violations with schema id, path, value marker, recovery mode, source, and issue linkage. | Schema violation testbed with event payload, cofx, app-db, sub return, all recovery modes. | Trigger one violation per schema kind, open Schemas, assert rows, severity/recovery badges, and source chip. | No schemas registered; no violations yet; malformed violation payload; redacted violating value. | After 20 valid/invalid events, counts and timeline ordering stay correct. | Schema id, path, recovery mode, operation, source coord, redacted/elided marker, issue row id. | Xray browser feature gate plus schema timeline unit/view gate. | `covered` |
| Hydration | Hydration debugger surfaces SSR mismatch, render-tree diff, hashes, divergent node, and frame-specific payloads. | SSR mismatch testbed, corrupt/missing payload, multi-frame hydration mismatch. | Load mismatch page with Xray, open Hydration, assert server/client hashes, divergent row, source chip. | No SSR detected; clean hydration; corrupt payload; multi-frame mismatch only affects owning frame. | After 20 post-hydration dispatches, mismatch record remains inspectable and does not pollute newer cascades. | Frame id, server/client hashes, divergent node path, payload status, hydration trace id. | Xray browser feature gate plus hydration unit/view gate. | `covered` |
| Issues Ribbon | The dedicated Issues TAB was removed per rf2-gbz39 (Mike's Option (c) ruling); the `:rf.xray/issues-ribbon` projection survives as the always-on ribbon signal's data source. Issues surface inline — in the Epoch panel (per-step pass/fail + the "Exception Thrown" block), via the L2 event-row pink-wash, and via the always-on issues ribbon signal — across errors, warnings, schema violations, hydration mismatches, and advisories. | Deterministic handler/fx/sub/view exceptions, schema violations, hydration mismatch, warning/advisory emits. | Trigger one issue per category, assert the issues ribbon signal fires (auto-open-on-error watcher), assert the focused epoch's Epoch panel surfaces the issue inline + the issuing L2 row carries the pink-wash, assert the source chip resolves. | No issues shows all-clear (ribbon silent, no pink-wash); malformed issue keeps the projection alive. | After 20 mixed success/failure dispatches, issue projection counts + pink-wash attribution remain accurate. | Severity/category counts, ribbon-signal state, issue trace ids, parent dispatch ids, source coords, per-row pink-wash flag. | Xray browser feature gate plus issues-projection unit/view gate. | `covered` |
| 4-layer chrome | L1 **two ribbons** (rf2-4vp5j) — chrome ribbon (frame view-scope dropdown · Dynamic/Static mode dropdown · `⚙` `✕`) + events ribbon (`Events:` · nav · focus-chip · filter pills · `N hidden`/`Clear Filters`) — + L2 event list (8 rows default, resizable to min 2) + L3 tab bar (9 Dynamic tabs) + L4 detail panel mount correctly; no L0 bottom rail; resize handle works between L2/L3; narrow (<800px) + wide (≥1200px) layouts preserve layer order. | Multi-frame app, ≥20 cascades for L2 scrolling, narrow + wide viewport configurations. | Open Xray, assert chrome-ribbon selectors (frame · mode dropdown · icons) + events-ribbon clusters (nav · focus-chip · pills · hidden/clear), assert L2 8 rows visible, drag L2/L3 handle to resize, switch tabs via `1`–`9` + letter mnemonics. The `runShellFeatureSweep` walks the full nine-tab `PANEL_HANDOFFS` (per rf2-tgp6i — Routing tab added 2026-05-19; Resources / Graph / Modules added per EP-0016 / EP-0014 / EP-0013; the Machines Canvas tab was removed 2026-05-21 per rf2-ga16q — its browse-all canvas relocated to the Static Machines sub-tab; the Issues tab was removed 2026-05-31 per rf2-gbz39 — issues surface inline in the Epoch panel + L2 event-row pink-wash + the issues ribbon). | Below 800px tab labels truncate to 3 chars; below 560px tab strip scrolls horizontally; below 600px viewport refuses to mount. | After 20 dispatches, layer geometry stable, virtualised rows render correctly, no L0 ever appears in DOM. | Ribbon cluster widths, L2 row count, L2 row height, L3 tab labels, L4 mount tree, viewport breakpoint state. | Xray browser feature gate plus chrome layout unit gate (`tools/xray/test/.../chrome_layout_test.cljs`). | `partial` (spec 018 §2; new gate). |
| Spine binding (`:rf.xray/focus`) | Clicking any L2 row dispatches `:rf.xray/focus-event`, which atomically rebinds L2 head-row mode cue + L3 count badges + L4 detail panel content. No panel reads `(peek history)`; no panel carries its own `:selected-*-id` slot. LIVE-tracking + sticky-on-older + LIVE-paused state transitions correct. (The Mode pill widget was dropped — `:mode` surfaces in the L2 spine.) | Deterministic cascade chain ≥10 events, LIVE / RETRO / paused mode transitions. | Click row 5 of 8, assert focused-row gutter flips to `◉`, assert L4 Epoch panel content updates to row 5's cascade, assert L3 `Views N` count updates, assert spine `:mode` flips to RETRO. Press `L`, assert spine `:mode` flips back to LIVE, assert L2 auto-scrolls to bottom + head-row pulse resumes. | Empty buffer; selection at boundary (oldest / newest); `Space` pauses LIVE; new event arrives while paused (sticky on selection); `G` snaps to head. | After 20 dispatches with mid-stream selection, sticky-on-older holds; selection only auto-advances when at head. | Spine sub value (`:dispatch-id`, `:epoch-id`, `:frame`, `:mode`, `:head?`), focused-row gutter glyph, L3 count badges, L4 detail panel content hash. | Xray browser feature gate plus spine unit gate (`tools/xray/test/.../spine_test.cljs`). | `partial` (spec 018 §6; new gate) |
| Filter IN/OUT pills | Events-ribbon pills add/edit/delete via popup; AND-across-modes / OR-within-mode filter semantics; **reset-on-load** (rf2-swclw — pills persist within a session but a reload starts unfiltered; `mount.cljs/::reset-transient-filters` clears the stored value); the `N events hidden by filters` + `Clear Filters` cluster surfaces when a pill suppresses rows (rf2-jvghz); Recommended-filters quick-add applies; right-click event-row creates correct pill type. Pills filter at data layer (`:rf.xray/filtered-event-bundles`), not render; the frame view-scope is applied first and is NOT a pill (rf2-4vp5j). | Mixed cascades (errors, HTTP, machine events), pre-populated pill set, localStorage state, Recommended-filters definition. | Add IN pill via trailing `+` (popup), add OUT pill via right-click row, assert pill rendering + filtered cascade count + `N hidden` indicator, edit pill via click, delete pill via popup, **reload page assert pills RESET to empty** (rf2-swclw). | Empty pills (no filter); pattern non-matching (zero filtered cascades); overflow `…N more ▾` collapse; error-override-bypass (filtered errored event surfaces with `⚠ ▽` gutter); active pill that hides nothing → `Clear Filters` present, `N hidden` absent. | After 20 dispatches with active pills, filtered cascade count + virtualised row count stay accurate; events-ribbon nav cluster walks filtered set only. | Active pill set (`:rf.xray/active-filters`), filtered cascade count, raw cascade count, `:rf.xray/hidden-by-filters` summary, localStorage payload, virtualised row index range. | Xray browser feature gate plus filter pills unit gate (`tools/xray/test/.../filter_pills_test.cljs`) + hidden-indicator unit gate (`filters/hidden_cljs_test.cljc`, dual-runtime since rf2-odlm3). | `partial` (spec 018 §7; new gate) |
| Sim mode (UC1) per-feature | Machines tab Sim toggle (per-frame-id, per-machine-id) persists to localStorage; mock `:data` form (schema-derived or type-inferred); failed-guard transitions LISTED but greyed; `Shift+Enter` fires-despite-guard with warning; skip-guards toggle works; sim trail renders state→state sequence; "Save as scenario" emits Clojure form to clipboard. | Deterministic machine with guards (some pass, some fail against initial `:data`), schema-registered + schema-less machine variants, sim-mode toggle, event picker. | Open Machines tab, toggle Sim ON, assert amber banner + amber active-state highlight + Sim header indicator, pick event via `E`, assert failed-guard transitions greyed, `Shift+Enter` fires-despite-guard with warning, `R` resets to initial state. | No machines registered; sim mode with no events available (empty event picker); save-as-scenario clipboard write fails gracefully. | After 20 sim transitions, sim trail accurate (no live timestamps / source coords leak), localStorage persists toggle across reload. | Sim toggle state, banner visible, active-state hue (amber vs cyan), event picker contents, sim trail entries, clipboard payload (when save-as-scenario invoked). | Xray browser feature gate plus machines sim unit gate (`tools/xray/test/.../machines/sim_test.cljs`). | `partial` (spec 003 §UC1; new gate) |
| Mode A/B/C dynamic instances (UC2) | Machines tab auto-selects Mode based on instance count: A (0) → empty hint or sim; B (1–3) → all instances on same diagram with stable per-instance hues + side-tag arrows; C (4+) → cluster-by-state count badges + virtualised table + shift-click divergence (cap 4 lanes); per-instance mini-scrubber in arc strip works without affecting global spine. | Deterministic machine registration, programmatic instance spawn (0 / 1 / 3 / 47 instances), shift-click multi-select. | Spawn instances 0 → 1 → 3 → 47, assert mode transitions A→B→C, assert per-instance hues stable across re-renders, assert Mode C count badges + virtualised table + sort/filter; shift-click 2 instances, assert divergence highlight. | Shift-click cap > 4 returns to single focus; per-instance scrubber doesn't touch `:rf.xray/focus`; recent-deaths buffer 10s fade + 30s preservation. | After 20 mixed spawn/destroy ops, mode auto-detect remains correct, instance hues + table sort stay consistent. | Live instance count, mode keyword, per-instance hue assignments, divergence-lane count, mini-scrubber state, recent-deaths buffer contents. | Xray browser feature gate plus machines dynamic-instances unit gate (`tools/xray/test/.../machines/dynamic_test.cljs`). | `partial` (spec 003 §UC2; new gate) |
| Data classification rendering | Xray renders `:rf/redacted` opaque (`[● REDACTED N]` magenta; **no reveal button ever**); `:rf.size/large-elided` drillable (`[● ELIDED · N bytes]` yellow; click → popover with `:hint` text + "Fetch full value" button that round-trips the marker's `:handle` through `get-path`; size-warned via confirm modal when bytes > 100KB threshold); combination semantics (`:rf/redacted` + bytes dominates: magenta + size disclosed, no drill). Per-surface rendering at L2 (trailing marker) + L4 (per `inspect`/`inspect-diff`). (The Mode pill widget that earlier drafts carried per-session totals on was dropped; per-event markers + Settings → Diagnostics carry the session counts.) | Sensitive dispatcher, large dispatcher (small + > 100KB), combined sensitive-large path, clear-buffer path, egress-profile reveal/redact. | Dispatch sensitive + large events, assert L2 row marker, assert L4 Epoch panel + App-db tab + Views tab render sentinels correctly, click `[● ELIDED]` < 100KB → popover opens directly, click `[● ELIDED]` > 100KB → confirm modal first. | Widening the egress profile to `:rf.egress/local-raw` affects future events only; `:rf/redacted` cannot be fetched (no button); malformed `:rf.size/large-elided` marker renders safe fallback. | After 20 sensitive/large dispatches, marker counts accurate, no raw sensitive string appears in DOM/diagnostics. | Sentinel paths/counts, marker hue, popover state, confirm modal state, DOM text scrub result. | Xray browser feature gate plus classification rendering unit gate (`tools/xray/test/.../classification_rendering_test.cljs`). | `partial` (spec 018 §12 + spec/015; new gate) |
| Frame-isolation invariants (I1–I4) | I1: Frame picker excludes `:rf/xray` by default; reveals when Settings "Show tool frames" ON. I3: Views panel render-attribution scoped to selected frame only (Xray-internal renders MUST NOT bleed into host frame's Views). I4: Browser feature test asserts Xray-self-observation disallowed. **Failure blocks merge.** | Multi-frame app (≥2 host frames + Xray-internal `:rf/xray`), render-tracker `:owning-frame` tagging, deterministic Xray-internal hover-render. | (1) Open frame picker, assert `:rf/xray` absent; (2) select `:rf/default`, trigger Xray-internal hover, open Views, assert NO Xray-namespaced component appears; (3) toggle Settings "Show tool frames" ON, assert `:rf/xray` appears under `── Power user ──` divider. | Empty render set (no host renders this cascade); Xray-internal sub feeding host-data path (caught by I2 dev-time lint). | After 20 host renders + 20 Xray-internal hover-renders, isolation holds. | Frame picker contents, Views render rows (with `:owning-frame` tags), Settings toggle state, dev-time lint output. | Xray browser feature gate (`tools/xray/test/.../isolation_test.cljs`). **Failure blocks merge.** Plus sub-graph lint test (`tools/xray/test/.../sub_graph_lint_test.cljs`). | `partial` (spec 018 §8; new gates) |
| Settings modal popup | Modal opens via `,` / `s` / click `⚙`; modal closes via `Esc` / click outside / `✕`; six sections navigable via left-rail; every field commits to `(xray-config/configure! …)` on change (no Apply/Cancel); "Show tool frames in picker" toggle under View → Power user flips ribbon picker option list. | Default-config Xray, modified-config Xray, schema-version mismatch (corruption). | Open via `,`, navigate to View → Power user, toggle "Show tool frames in picker", assert ribbon picker now lists `:rf/xray`. Open via `s`, navigate to Actions, click `[factory-reset!]`, confirm modal, assert filters/pins cleared. | Schema mismatch on localStorage triggers clean reset; click outside modal closes; `Esc` closes; tab focus returns to ribbon `⚙` icon. | After 20 setting toggles, configure! state matches Settings UI; no toggle drift. | Modal mount state, active section, per-section field values, configure! map, ribbon picker contents (after Power user toggle). | Xray browser feature gate plus settings popup unit gate (`tools/xray/test/.../settings_popup_test.cljs`). | `partial` (spec 018 §9; new gate) |
| Open in Editor / Source Coordinates | Every source chip builds the configured editor URI, hides on missing file, and never inlines custom URI assembly per panel. | Source coords across event, fx (rf2-g1mfc — Epoch FX-step row), trace, app-db, sub, route, machine, flow, hydration, and missing-file case. | Configure each editor keyword/custom template, click chips from multiple panels, assert URI shape or hidden chip. | Missing `:file`; unknown editor keyword fallback; no OS handler no-op; malformed custom template fallback. | After 20 dispatches, source chips remain attached to correct rows after virtualization/reordering. | Source coord map, configured editor, built URI, panel id, row id, hidden-chip reason. | Xray browser feature gate plus config/open-in-editor unit gate. | `covered` |
| Redaction, Sensitive, and Large Values | Sensitive data is dropped and counted; large data is elided with recoverable handle/digest; panels render clear markers without leaking raw values. | Sensitive dispatcher, large dispatcher, combined sensitive-large path, clear-buffer path, egress-profile reveal/redact. | Dispatch sensitive and large events, assert bottom-rail redaction, inline markers, large fetch handle/digest, clear reset. | Widening the egress profile to `:rf.egress/local-raw` affects future events only; redacted values cannot be fetched; malformed elision marker renders safe fallback. | After 20 sensitive/large dispatches, counts are correct, buffer cap is respected, and no raw sensitive string appears in DOM/diagnostics. | Suppressed counters, marker paths/counts, digest/bytes/handle, DOM text scrub result, buffer depth. | Xray browser feature gate plus sensitive trace and large dispatcher gates. | `covered` |
| Pop-out and Default True-Inline Embedding | True inline host is default; pop-out reads opener runtime; `open-overlay!` remains an optional debug surface. (Per `rf2-sbfb7` the `dock!` / `undock!` body-padding surface and the imperative `mount-inline-panel!` debug surface were removed; declarative panel embedding lives at `008-Embedding-Contract.md`.) | Same-origin pop-out, opener-close simulation, default true-inline mount under `[data-rf-xray-host]`. | Load with layout host, pop out, assert second window renders selected panel and shares trace/epoch state. | Missing layout host emits actionable diagnostic; opener gone warns/degrades. | After 20 host dispatches, inline host and pop-out agree on latest cascade without duplicate listeners. | Window mode, opener status, listener count, mount id, selected panel/frame, shared trace count, host selector. | Xray browser feature gate; current pop-out warning remains covered by core unit gate. | `covered` |
| Shell, Keybinding, Config, Preload, Settings, and Production Elision | Xray auto-mounts into `[data-rf-xray-host]` when runtime/substrate is ready, toggles via configured keys, isolates `:rf/xray`, persists/reset settings, obeys preload order, and fully elides from production builds. | Default inline host page, missing-host page, settings corruption/reset, alternate keybinding, config knobs, prod release/elision probe. | Load page, assert inline DOM in host after runtime readiness, open/close CSS-only, configure editor/sensitive/depth, reload/hot-reload idempotency. | Missing adapter retries then diagnoses; missing host reports selector/snippet/status without `alert()`; corrupt settings reset; production build has no pill/keybinding/panel/listeners. | After 20 toggles/dispatches, no duplicate listeners, no remount state loss, no extra trace collectors. | Mounted/visible flags, listener registry, config atoms, settings payload, frame id, production grep/probe result. | `tools/xray` unit gate, Xray browser feature gate, and implementation elision gates. | `covered` |
| Static mode mount + chord | Static mode is unconditionally available (per rf2-8l3uk): the surface composer renders the 3-layer Static chrome silhouette + mode **dropdown** at chrome-ribbon-left (rf2-4vp5j `<select>`, testid `rf-xray-mode-pill`); `Cmd-Shift-M` / `Ctrl-Shift-M` toggles between Dynamic and Static; selected mode persists to localStorage key `xray.mode` and hydrates on reload. | Xray-enabled testbed; localStorage round-trip; chord listener wired. | Assert mode dropdown visible + Dynamic selected; press `Ctrl+Shift+M`; assert Static surface mounts (3-layer silhouette, no L2 event list); select Dynamic in the dropdown; assert Dynamic restored; reload; assert persisted mode round-trips. | localStorage cleared: defaults to `runtime`. Corrupt localStorage value: clean fallback to `runtime`. | After 20 chord toggles, mode-set fx persists each flip, no orphaned listeners, surface composer remains stable. | Mode pill state (`aria-checked`, `data-active-mode`), Static surface presence + `data-rf-xray-mode` attr, localStorage `xray.mode` value, chord-listener installed flag. | Xray browser feature gate (`runStaticModeChromeAndChord` in `scenarios.cjs` per rf2-n39g2 / rf2-o5f5f.1 / rf2-8l3uk). | `covered` |
| Static Machines panel | Topology chart renders the same `mv-chart/MachineChart` (xyflow + elkjs, rf2-gpzb4) primitive the Dynamic panel uses; Browse-list renders one row per registered machine with a `→ Dynamic` JUMP chip; 4-mode sub-strip `[Topology][Sim][Instances][Cascade]` renders with Topology default-active; Cascade pill is greyed + disabled with tooltip; JUMP-to-Dynamic flips mode + opens Dynamic Machines tab focused on the chosen machine-id. | Deep-machine testbed (`/testbeds/deep-machine/`) — multiple registered machines; Static mode unconditionally available (per rf2-8l3uk). | Enter Static via `Ctrl+Shift+M`, switch to Machines sub-tab, assert `rf-xray-static-machines-browse-list` renders ≥ 1 row, assert `rf-xray-static-machines-topology-chart` SVG has ≥ 1 layout-node child, assert `rf-xray-static-machines-sub-strip` carries Topology pill active + Cascade pill `disabled`+`opacity:0.5`, click a row's `→ Dynamic` chip, assert mode flips to Dynamic + Machines tab opens with the selected machine id. | No machines registered: `rf-xray-static-machines-empty` empty state. Sub-mode persistence corrupt: clean fallback to `:topology`. Cascade pill click is a no-op (disabled). | After 20 JUMP toggles, mode round-trips don't drop the selected machine-id; per-machine sub-mode map persists. | Selected machine-id, sub-mode keyword, topology SVG node count, JUMP target tab id + machine-id, Cascade pill `aria-disabled` attr. | Multi-frame e2e CLJS Node test at `tools/xray/test/day8/re_frame2_xray/panels_e2e/static_machines_panel_e2e_cljs_test.cljs` per rf2-7icrs (browse-list ≥ 1 row + Topology SVG ≥ 1 `<g>` layout node + sub-strip Topology-active + Cascade `aria-disabled='true'` + → Dynamic JUMP flips mode + opens Machines tab + lands machine-id). | `covered` (rf2-1laqx) |
| Static Routes panel | Flat-list browse-all surface with substring search, Simulate-URL hermetic preview (zero host nav mutation), per-row inline expand for full registrar meta, per-row hermetic Simulate-navigation preview, and per-row `→ Dynamic` jump chip that flips mode + opens Dynamic Routing tab (the two-verbs-two-homes pattern per `016` §Routes — two verbs, two homes). | Routes-aware testbed (`/examples/routing/` — 4 registered routes); Static mode unconditionally available (per rf2-8l3uk). | Enter Static via `Ctrl+Shift+M`, switch to Routes sub-tab, assert `rf-xray-static-routes-list` renders ≥ 1 row per registered route, type a known URL into `rf-xray-static-routes-sim-input`, assert `rf-xray-static-routes-sim-result` renders with a WINNER row + host `:rf/route` slot UNCHANGED (probe via `page.evaluate`), click a row's `→ Dynamic` jump chip, assert mode flips to Dynamic + Routing tab is selected. | No routes registered: `rf-xray-static-routes-empty` empty state. Simulate-URL with non-matching pattern: empty candidates list. Hermetic preview MUST NOT call `:rf.route/navigate` / `:rf.route/url-requested` / `history.pushState`. | After 20 simulate-URL inputs, projection runs deterministically; per-row expand state persists across re-renders. | Total registered-route row count, Simulate-URL candidate count, host `:rf/route` slot before + after sim, JUMP target tab id, expanded-id set. | Xray unit view gate (`static/routes/panel_cljs_test.cljs` — registry wiring, silent state, flat-list render, search filter, Simulate-URL row, expand toggle, hermetic preview, cross-link mode/tab flip) + Xray multi-frame e2e gate (`static_routes_panel_e2e_cljs_test.cljs` — synthetic 3-route override → tab-data browse list + WINNER candidate + host `:rf/route` hermetic + `:rf.xray.static.routes/jump-to-dynamic` mode/tab flip per rf2-wj46n). | `covered` (rf2-wj46n) |
| Static Flows panel | Flat-list browse-all surface (per Lock #15 — browse-all lives in Static) over every flow registered via `re-frame.flows/reg-flow`. Each row carries `:inputs` paths, `:output-path`, owning frame (flows are frame-scoped per Spec 013), and the doc-string. Substring search across flow-id + frame + inputs + output-path + doc. No jump-to-source chip (flow registration metadata does not surface source-coords in the current registry shape); no Simulate-input verb (flows have no input-event taxonomy to inject — they recompute on app-db changes). | Flows-aware testbed (deterministic `reg-flow` registrations across ≥ 1 frame); Static mode unconditionally available (per rf2-8l3uk). Test-only override seam: `:rf.xray.static.flows/registered-flows-override` (settable via `:rf.xray.static.flows/set-registered-flows-override-for-test`) injects a deterministic `{frame-id {flow-id flow-map}}` snapshot without touching the live flows-registry atom. | Enter Static via `Ctrl+Shift+M`, switch to Flows sub-tab, assert `rf-xray-static-flows-list` renders ≥ 1 row per registered flow, assert each row surfaces flow-id + frame + `inputs:` + `output →` segments, type a known substring into `rf-xray-static-flows-search-input`, assert `rf-xray-static-flows-search-count` flips to `match` + filtered row count drops. | No flows registered: `rf-xray-static-flows-empty` empty state. Search query with no match: `rf-xray-static-flows-empty-filtered` empty-filtered state. Live-registry deref failure: clean `{}` fallback (no crash). | After 20 mixed `reg-flow!` calls, projection ordering stays stable (sort-by flow-id ascending); search query persistence holds across re-renders. | Total registered-flow row count, filtered row count, search query value, per-row testid (`rf-xray-static-flows-row-<id>`), override slot present? | Xray unit view gate (`static/flows/panel_cljs_test.cljs` — registry wiring, pure projection over `project-rows` / `filter-rows` / `project-data`, silent state, flat-list render, search filter). | `covered` (rf2-uhsqb) |
| Static Schemas panel | Flat-list browse-all surface (per Lock #15 — browse-all lives in Static) over three input registries: app-db slot schemas (`re-frame.schemas/reg-app-schema`'s per-frame `{frame-id {path schema-meta}}`), event-spec metadata (`registrar/registrations :event`, `:spec` slot), and sub-spec metadata (`registrar/registrations :sub`, `:spec` slot). Each row surfaces kind (`app-db` / `event` / `sub`) + schema-id/path + the Malli EDN. Substring search across kind + id + schema. Jump-to-source chip per row when registration metadata surfaces `:file` / `:line` — click dispatches `:rf.xray/open-in-editor` (same wiring as Trace + Issues per rf2-evgf5 / rf2-g5q8d). | Schema-aware testbed (deterministic `reg-app-schema` + event/sub `:spec` registrations across ≥ 1 frame); Static mode unconditionally available (per rf2-8l3uk). Test-only override seam: `:rf.xray.static.schemas/set-registry-override-for-test` injects a deterministic `{:app-db {…} :event {…} :sub {…}}` snapshot without touching the live registries. | Enter Static via `Ctrl+Shift+M`, switch to Schemas sub-tab, assert `rf-xray-static-schemas-list` renders ≥ 1 row per registered schema across all three kinds, type a known substring into `rf-xray-static-schemas-search-input`, assert filtered row count drops, click a row's source-coord chip, assert `:rf.xray/open-in-editor` dispatches with the registered `:file` / `:line`. | No schemas registered across any kind: `rf-xray-static-schemas-empty` empty state. Search query with no match: empty-filtered state. Row with no source-coord: chip hidden. Malformed schema EDN in a registration: row still renders with safe fallback. | After 20 mixed schema registrations, three-kind grouping + filter holds; jump-to-source chips remain attached to correct rows after virtualisation/reordering. | Per-kind row counts (app-db / event / sub), filtered row count, search query value, source-coord chip presence per row, dispatched `:rf.xray/open-in-editor` payload. | Xray unit view gate (`static/schemas/panel_cljs_test.cljs` — registry wiring, pure projection across the three input registries, silent state, flat-list render, search filter, source-coord chip dispatch). | `covered` (rf2-o5f5f.4) |
| Static Interceptors panel | Flat-list browse-all surface (per Lock #15 — browse-all lives in Static) over every interceptor surfaced through registered events. A chain entry is an INLINE interceptor value OR a by-reference entry (bare keyword / `[id arg]`) into the `:interceptor` registrar (EP-0022, rf2-0adhqs.7); a reference is surfaced by its authored form (a `ref` badge + the factory `arg`) and enriched from the registered descriptor (`(rf/handler-meta :interceptor id)`). Each row surfaces interceptor id + a before/after/factory-hook indicator + a framework-emitted auto-wrapper badge (rf2-twt7m) + the `ref` badge when applicable. Substring search across the row text incl. the authored ref form. | Interceptor-aware testbed (deterministic interceptor registrations + ref-bearing chains); Static mode unconditionally available (per rf2-8l3uk). | Enter Static via `Ctrl+Shift+M`, switch to Interceptors sub-tab, assert `rf-xray-static-interceptors-list` renders ≥ 1 row per registered interceptor, type a known substring into `rf-xray-static-interceptors-search-input`, assert `rf-xray-static-interceptors-search-count` flips + filtered row count drops. | No interceptors registered: `rf-xray-static-interceptors` empty state. Search query with no match: empty-filtered state. Unregistered/hot-reloaded ref: `default-resolve-ref` is fail-soft (row renders, no hooks). | After 20 mixed registrations, projection ordering stays stable. | Total registered-interceptor row count, filtered row count, search query value, before/after/factory-hook + auto-wrapper + ref flags per row. | Xray unit view gate (`static/interceptors/panel_cljs_test.cljs` — incl. EP-0022 keyword-ref / `[id arg]`-factory-ref / inline-value-non-ref / fail-soft default-resolver coverage). | `covered` |
| Cmd-K palette | `Cmd-K` / `Ctrl-K` chord opens the palette dialog; mode-aware command index (commands filter by `:rf.xray/mode`); 6 new verbs (`:toggle-theme`, `:toggle-reduced-motion`, `:snapshot-db`, `:clear-epoch`, `:mode-toggle`, `:jump-to-settings` per rf2-ybjkx); recents slot persists top-3 invocations to localStorage; Esc closes without dispatching. | Counter testbed; localStorage seed for `re-frame2.xray.palette.recents.v1`; theme slot readable via `cfg.get_setting`. | Press `Ctrl+K`, assert `rf-xray-palette-dialog` mounts + input focused, type "toggle theme", assert fuzzy filter narrows to `:toggle-theme` row, press Enter, assert dialog closes + theme slot flips, re-open palette, assert recents-boost places `:toggle-theme` at row 0. | Empty recents: fuzzy ordering only. Theme slot unreadable: fail with diagnostics. Esc on palette: closes without firing the focused verb. | After 20 invokes, recents capped at 3, slot persistence stable. | Dialog mount state, input focus, first-row source + label, theme slot before/after, persisted recents payload. | Xray browser feature gate (`runPaletteOpenExecute` in `scenarios.cjs` per rf2-z5zip / rf2-ybjkx). | `covered` |
| Density — `--rf-xray-font-size` calc-anchor (rf2-n8i2c + rf2-i40us) | Every type-scale entry resolves through `calc(var(--rf-xray-font-size, 13px) * <multiplier>)`, so a single CSS variable rescales the whole shell on the next style flush. The Settings → General Density radio (`:compact 12px` / `:cosy 13px` / `:comfy 14px`) is the in-shell writer via `effects/apply-density-font-size!`, which writes the resolved px value into `--rf-xray-font-size` on BOTH the Xray shell root AND `<html>` (so popout/fullscreen mounts inherit). A host `:root` rule (`:root { --rf-xray-font-size: 14px }`) rescales every typographic surface without a code change; below `10px` is refused. Distinct from `--rf-xray-text-size` (the Text-size slider's separate var). | Default-config Xray, Settings popup density radio, popout window with no inline-shell ancestor, host stylesheet override fixture, persisted-density localStorage fixture. | Open Settings → General, flip Density radio Cosy → Compact, assert `--rf-xray-font-size` reads `12px` on both shell root + `<html>`, assert every `:body`/`:caption`/`:micro` token rescales via computed style. Open popout, repeat assertion (no inline-shell ancestor). Apply host `:root { --rf-xray-font-size: 14px }` override, reload, assert tokens rescale ~1.08×. | Persisted `:comfy` payload from before the v1 radio drop coerces to `:cosy`. Unknown density keyword → `:cosy` fallback. JVM test runner (no DOM) — writer is a clean no-op. Below `10px`: writer refuses + diagnostic. | After 20 density flips, idempotent writer leaves no orphaned `--rf-xray-font-size` declarations on either root; persisted value round-trips through reload. | Active density keyword, computed `--rf-xray-font-size` on shell root, computed `--rf-xray-font-size` on `<html>`, popout-window computed style, host-override stylesheet presence, persisted localStorage `:density` value. | Xray unit gate (`tools/xray/test/.../settings/density_cljs_test.cljs` — pure-fn JVM coverage of `density->font-size-px`) + Xray browser feature gate (host-override + popout inheritance covered by the shell-feature sweep, sub-row of `runShellFeatureSweep`). | `partial` (spec 007 §Sizes + spec 015 `:density`; popout-inheritance sub-row missing — file follow-on if a regression surfaces). |
| Event-lifecycle status colour | The 5-status taxonomy (`:in-flight` / `:settled-success` / `:settled-error` / `:paused-by-tool` / `:stale`) maps via the central pure fn (`event_status_colour.cljc`). Two consumer sites — L2 event-row (`shell.cljs`), L4 Trace timeline bar (`panels/trace.cljs`) — MUST read the SAME status token for the SAME cascade. Cross-site consistency is the invariant. (Earlier drafts also surfaced the status on a header dot in the retired Event/Handler panel; rf2-ad7zx.17 removed that dot, and rf2-5gl5r subsequently retired the panel itself in favour of the Epoch panel — which carries no status dot either.) | Deliberate-throw testbed (`/testbeds/deliberate-throw/`) — handler-throw + flow-throw fixtures give the `:settled-error` cascade; counter testbed gives `:settled-success`. | Drive one cascade per status (`:settled-success` via counter inc; `:settled-error` via throw-handler); for each, focus the L2 row, open L4 Trace tab; read `data-rf-xray-status` attribute on L2 row + L4 Trace status bar (`rf-xray-trace-event-bundle-status-bar-*`); assert both carry the same status keyword for the same cascade. | Empty trace: no rows to assert. Cascade with no terminal outcome: `:in-flight` fallback. Mode flip to RETRO: `:stale` overrides outcome unless `:settled-error`. | After 20 mixed cascades, every cascade's consumer sites stay in lockstep; no regression where one site forgets to read the central fn. | Per-cascade dispatch-id, L2 `data-rf-xray-status`, L4 Trace bar testid suffix. | Xray unit gate (`event_status_colour_cljs_test.cljc` — pure-fn JVM coverage) + Xray unit view gate (`event_status_colour_view_cljs_test.cljs` — synthetic-trace cross-site walk) + Xray multi-frame e2e gate (`event_status_colour_cross_site_e2e_cljs_test.cljs` — REAL host frame dispatch → trace bus → spine focus → 2-site `data-rf-xray-status` walk per rf2-b8pui). | `covered` (rf2-b8pui) |
| machines-viz chart-import integrity | `tools/machines-viz/` is the canonical home of the chart primitive (per rf2-o9arp / PR #1570; xyflow + elkjs since the rf2-gpzb4 migration); Xray imports `day8.re-frame2-machines-viz.chart/MachineChart` **directly** (the older `chart.svg` / `chart.layout` / `chart.interaction` re-export shims were removed). The `:advanced` build MUST NOT strip the chart; the chart must render with > 0 layout nodes when a non-trivial machine is selected. | Deep-machine testbed (deterministic non-trivial machine definitions); `:advanced` production-elision build. | Open Machines tab against deep-machine, assert the xyflow chart renders ≥ 1 layout-node child; build the artefact under `:advanced` and assert the chart still renders. | Chart ns stripped under `:advanced`: chart fails to render, fail with diagnostic. elkjs unavailable: layout-fallback still renders. | After 20 machine snapshots / re-renders, the chart node count remains stable. | Layout engine string, node count, `:advanced` build presence test, chart ns reachability probe. | Xray browser feature gate (covered transitively by `runDeepMachine`'s `rf2-bz72m` chart-render assertion) + bundle-isolation gate (`tools/machines-viz` jar). | `covered` |
| Routing L3 tab handoff | `PANEL_HANDOFFS` walks all 9 Dynamic L3 tabs including `:routing`; the Routing tab mounts the `rf-xray-routing` canvas; switching to the tab via the tab-button + via the `r` mnemonic both succeed. | Multi-frame app (carries route slots); shell sweep affordance over the nine-tab L3 bar. | Click `rf-xray-tab-routing`; assert `rf-xray-routing` canvas mounts. | Empty route registry: silent empty state. Frame-picker switch: lens re-binds. | After 20 tab switches the handoff is idempotent. | Tab `aria-selected` state, canvas root testid, frame picker selection. | Xray browser feature gate (`runShellFeatureSweep` walks `PANEL_HANDOFFS` per rf2-tgp6i). | `covered` |
| Static surface frame isolation | The Static composer's `reg-view` registrations resolve to `:rf/xray` (the same Spec 004 frame-context the Dynamic panel uses). Sub-rows of the Frame-isolation invariants (I1–I4) row above; called out separately because the Static composer is a new ingress and frame-leak regressions here would be invisible to the existing I1–I4 gate. | Static-flag opt-in plus a multi-frame host; Views panel filters by `:owning-frame`. | Open Static, walk the 5 sub-tabs, open the host's Views panel, assert no Static-namespaced render rows appear in the host frame's Views. | Frame-leak regression: a Static-panel `reg-view` resolves to `:rf/default` instead of `:rf/xray`. **Failure blocks merge.** | After 20 Static sub-tab walks, isolation holds. | Subscribed-frame attribution per render row, `:owning-frame` tags. | Xray browser feature gate (Static isolation sub-row of `isolation_test.cljs`). | `partial` (covered by I1–I4; explicit Static composer ingress sub-row missing — file follow-on if a regression surfaces). |
| Two-verbs-two-homes Routes | Static Routes + Dynamic Routing serve distinct lenses (browse-all vs. focused-event) and BOTH MUST exist; the Static Routes `→ Dynamic` jump chip MUST flip mode + open the Dynamic Routing tab. Normative lock per DESIGN-RATIONALE Lock #15 / `016` §Routes — two verbs, two homes (rf2-o5f5f.3). | Routes-aware testbed (registered routes); Static mode unconditionally available (per rf2-8l3uk). | Assert both Static Routes (`rf-xray-static-routes`) and Dynamic Routing (`rf-xray-routing`) panels mount independently; click the Static `→ Dynamic` jump chip, assert mode flip + tab open. | Only one home present: convention violation, fail with explicit cite to the lock. | After 20 jumps, both lenses survive remount cleanly. | Mode keyword after jump, active tab keyword, both panel canvas testids. | Xray multi-frame e2e gate (`static_routes_panel_e2e_cljs_test.cljs` — jump-chip flips `:rf.xray/mode` `:static → :dynamic` and opens `:rf.xray/selected-tab :routing`, per rf2-wj46n) + Dynamic Routing panel e2e gate (`routing_e2e_cljs_test.cljs`). | `covered` (rf2-wj46n) |
| Mode-signal mechanism (4 stacked signals) | The 4 stacked mode signals — chrome silhouette (3-layer Static vs 4-layer Dynamic), mode **dropdown** at chrome-ribbon-left (rf2-4vp5j), cyan left-edge stripe (Static) / violet (Dynamic), motion dampening (Static reduces transition durations) — MUST all be present + consistent with the active mode. Per `007` §Static-mode + `018` §Static surface architectural section. | Static mode unconditionally available (per rf2-8l3uk). | Assert each of the 4 signals against the active mode: chrome silhouette via DOM structure (presence/absence of L2 event list), mode pill via `aria-checked` + `data-active-mode`, edge stripe via the surface's `data-rf-xray-mode` attribute + computed style, motion dampening via the user-override reduced-motion axis. | Reduced-motion user override: dampening already on regardless of mode. | After 20 mode toggles, all 4 signals remain in lockstep with `:rf.xray/mode`. | Per-signal sentinel: chrome layout DOM hash, pill `data-active-mode`, surface `data-rf-xray-mode`, motion-axis flag. | Xray browser feature gate (chrome silhouette + pill covered by `runStaticModeChromeAndChord`; edge stripe + motion dampening covered by visual-language sub-gate, partial). | `partial` (chrome silhouette + pill covered; edge stripe + motion dampening sub-gate missing — file follow-on if a regression surfaces). |

## Gate ownership

| Gate | Scope |
|---|---|
| `tools/xray` unit gate | CLJ/CLJS helper, registry, config, shell, trace bus, and panel view tests. Intended default local/CI coverage for Xray internals. |
| Frame-singleton guard (rf2-1w07r — EPIC closed via rf2-nesy9) | Source-text JVM guard (`frame_singleton_guard_test.clj`, in the `clojure -M:test` gate) flagging the two singleton-class anti-patterns — a bare `{:frame :rf/xray}` / `(rf/subscribe :rf/xray …)` literal, and a global `rf/dispatch` wired to an `:on-*` handler — in any file under `tools/xray/src`. The rf2-nesy9 sweep migrated EVERY panel / modal / static surface to the captured-instance-frame pattern (reg-view-injected `dispatch`, a threaded `dispatch-fn`, a render-time `(rf/current-frame-id)` capture, or a `(rf/capture-frame)` frame api for async/held ops), so the `pending-migration` allowlist is now **EMPTY** — the whole `tools/xray/src` tree is locked clean (an allowlist-honesty test keeps it empty). The few legitimate production-singleton seams (trace-collector `note-suppressed!`, share-URL on-load restore, per-feature `hydrate!` init) target the shell via the named `defaults/default-frame-id` Var, never a bare map literal. The two-instance state-isolation acceptance (distinct tab/mode/focused-epoch per shell) is pinned by the CLJS `two_instance_isolation_cljs_test.cljs`. |
| Xray browser smoke gate | Existing lightweight browser coverage from `tools/xray/testbeds/two_frame_isolation` (the canonical multi-frame ISOLATION surface — repointed under rf2-wa8my to mount the `standard_epochs` deck twice, one app in two isolated frames `:above` + `:below`) and `tools/xray/testbeds/standard_epochs` (the single-frame deck). Intended default browser smoke coverage. |
| Xray browser feature gate | New deterministic feature matrix gate described by this spec. It owns direct and failure paths for each matrix row. It can be sharded by panel but should report one matrix. Includes the `machine-epochs multi-machine frame-isolated stepper` scenario (rf2-q3lfm) over the `:examples/machine-epochs` testbed — see §The machine-epochs frame-isolated stepper below. |
| Xray 20-event/load gate | Explicit or pre-commit/pre-PR stress gate only. It is not default CI. It reuses the feature testbed and runs the row-specific 20-event/load checks. |
| Production elision gate | Existing implementation production-elision probes plus any Xray-specific release probe proving preload, keybinding, pill, trace collector, and shell are absent under `goog.DEBUG=false`. |

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
| `rf2-5aw5v.12` (L-12) | Embedding-contract Panel surface across every tab namespace; frame isolation; registry-key namespacing | [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) | **Covered by Tier-1.** The current 4-layer chrome's 6 L3 tabs + L4 detail-panel handoff is covered by `tools/xray/testbeds/parallel_frames/spec.cjs` (mount + tabs + isolation) and `tools/xray/testbeds/feature_matrix/scenarios.cjs §runShellFeatureSweep` (per-panel handoff sweep). |
| `rf2-5aw5v.9` (L-9) | Pop-out / inline-host launch-mode duality; opener-close diagnostic | [`011-Launch-Modes.md`](./011-Launch-Modes.md) | **Covered.** `tools/xray/testbeds/feature_matrix/scenarios.cjs §runLaunchModesTwentyEventLoad` pins overlay + popout shared-runtime across 20 host dispatches. The opener-gone watchdog overlay is not specifically exercised post-rf2-qd5r6; file a follow-on if a regression surfaces. |
| `rf2-5aw5v.14` (L-14) | Multi-frame isolation through the panel layer (rf2-tijr Option-C lock) | [`008-Embedding-Contract.md`](./008-Embedding-Contract.md) §State isolation | **Rehomed.** `tools/xray/testbeds/parallel_frames/spec.cjs §5` exercises `:rf.xray/set-target-frame` + `:rf.xray/target-frame-db` against the canonical `:above` / `:below` frames; asserts target-frame round-trips, per-frame `:counter` projection, and Xray-side isolation (no `:counter` slot leak into `:rf/xray`'s app-db). |
| `rf2-5aw5v.10` (L-10) | Shell auto-mount, missing-host diagnostic, settings reset, keybindings, config knobs, production elision probe | [`011-Launch-Modes.md`](./011-Launch-Modes.md) + [`015-Configuration.md`](./015-Configuration.md) | **Rehomed.** `tools/xray/test/day8/re_frame2_xray/panels_e2e/configure_multi_key_e2e_cljs_test.cljs` pins `configure!` multi-key + partial-update semantics plus `set-auto-open!(null)` / `set-layout-host-selector!(null)` reset round-trips. (This row named the Playwright original, `scenarios.cjs §runConfigurePartialUpdate`, for some time after the rf2-rviu8 CLJS port superseded it; the dead JS scenario was deleted and this row corrected under rf2-2rtt6.78, when the new repo-wide ESLint gate's `no-unused-vars` reported the function as unreferenced.) Inline auto-mount + `Ctrl+Shift+C` toggle + missing-host diagnostic are covered by `runShellFeatureSweep` + the production-elision gate. |
| `rf2-5aw5v.11` (L-11) | 20-event/load stress invariant — caps + virtualisation + no duplicate dominoes | This file §20-event/load gate | **Covered.** The canonical heavyweight equivalent lives under `npm run test:xray-feature-gate`'s `runTwentyEventLoad` / `runTraceBudgetSaturation` / `runLaunchModesTwentyEventLoad` scenarios (the matrix's explicit pre-PR gate). |

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

## The freehand-views deck — the POPULATED Views arm (rf2-6pohj)

The `:testbeds/freehand-views` testbed (port 8036,
`tools/xray/testbeds/freehand_views/`) is the only shipped Xray deck whose
views are FREEHAND views. It adds **no new Xray contract**; this section
records its shape and the host constraint it stands on, so the browser feature
gate's `freehand-views populated Views roster` scenario has a normative
reference.

**Why it exists.** Every other staged surface is Reagent-hosted and connects
no Freehand occurrence, so the browser lane could prove the Mounted Views
section RENDERS and nothing about what it renders: a section that is empty and
a section emptied by a broken read through `re-frame.freehand.tool` are the
same DOM. The empty arm stays where it was (the counter surface, in
`runShellFeatureSweep`); this deck is the populated arm.

**Shape.** Three declared views over one `:rf/default` frame — `readout`
(`{:compiled true}`, the only `v/sub` on the page), `controls`
(`{:compiled true}`, the only `:on-click` event site) and `app` (interpreted,
the mounted root). Each puts a DIFFERENT fact on its row, so the scenario can
name what it is reading: one interpreted lowering against two compiled, `1
read` on the sole reader against `0 reads` elsewhere, and — in Declared View
Sites — a `:static-proof` subscription site, a `:static-proof` event site and
the `:no-static-analysis` interpreted arm, all three from the compiler
manifest.

**The fact that moves.** Two HOST controls (`Mount root` / `Unmount root`,
plain buttons wired from the boot script onto `v/mount` / `v/unmount!`)
disconnect and reconnect the three cells. The roster must come back naming
three FRESHLY MINTED occurrence keys — a read of the live occurrence index can
do that; a projection over a static registry, which is what the donor tier had
and what the Freehand door deliberately does not, cannot. `:generation` is NOT
the fact under test: it is the hot-reload body revision, not a render tally,
so `gen 0` on every row is correct for a page that is never hot-reloaded.

**The host shape, and what it no longer bounds.** The deck installs the
REAGENT adapter and renders Freehand views — a mixed application, which the
substrate contract blesses (`re-frame.freehand.substrate`: bring-your-own
adapter stays legitimate under the single-adapter runtime). It is not a
convenience: Xray's shell is hiccup, and `day8.re-frame2-xray.mount` refuses
the React-element-shaped adapters including `:rf.adapter/freehand`
(rf2-qgfo4), so on a host that installed `v/adapter` there is no Views panel
to populate at all. That refusal is unchanged.

What HAS changed is the converse. This section used to record a second
limitation on the Reagent side — a dispatch landed and app-db moved, but the
cell's committed read received no change notification and the cell never
repainted — and concluded that **no single host both repaints a Freehand cell
reactively and renders Xray's Views panel**, so no browser gate over this
panel could assert a reactively-driven re-render. That headline no longer
holds (rf2-8cnxg / rf2-jt8vz). The cause was never the host: the observation
port installed a watch on a `reagent.ratom/Reaction` that had captured no
sources, because a `Reaction` learns them only through `deref-capture` and a
ViewCell — not being a component — supplies no capture context. The port now
ACTIVATES the value through the optional `:adapter/activate-derived-value!`
late-bind hook, which Reagent publishes, so a Reagent host **both** repaints a
Freehand cell and mounts Xray's shell. A browser gate over this panel is no
longer bounded by that constraint.

**The reactively-driven repaint (rf2-2t126).** The scenario asserts it, and it
is the only browser-level proof that the activation holds end to end in a real
DOM — the substrate contract tests cover the port, not a cell repainting on the
very page Xray is reading. Press `+`: app-db moves, the cell that read it
repaints, and the readout's text advances. The mount verb is still how the rest
of the scenario drives commits, and that is precisely why it cannot stand in
here — it reads current values whatever the notification channel is doing, so
it would satisfy an advance-check on a cell that was never notified at all. The
assertion is therefore pinned to the readout's DOM NODE as well as its text: a
repaint writes into the node already standing there, a remount replaces it, and
both halves have to hold.

**Disposition — the deck retires with the panel, and is not independently
migratable (rf2-u5b4).** The reason is the panel's rather than the deck's:
[`021-Dynamic-Panel-Designs.md`](021-Dynamic-Panel-Designs.md) §3.4.3 records
that §3.4.1 and §3.4.2 cannot move to `re-frame.hicasso.tool`, because five of
their eight questions have no answer there, so they stay on
`re-frame.freehand.tool` until the Freehand tree goes. This deck is the
browser-lane populated arm of exactly those two sections and has no other
consumer.

**Five of the nine facts its scenario asserts are the ones with no answer**, and
they are the five this section describes above as the deck's reason for
existing: the view id each row names, the occurrence key (both as row identity
and as three freshly minted keys across a remount), the stated lowering, the
per-row generation the tag prints, and the Declared View Sites section with its
three manifest arms. **The remaining four are not**, and the record must not say
they are — the frame carries across, the commit's reads degrade to a live edge
set, the absent schema banner tests a Freehand-door read Hicasso does not
publish, and the reactively-driven repaint is a substrate fact rather than one of
§3.4's questions. So a re-point would leave a deck still asserting something; it
is the five that make what it asserted stop being a populated ROSTER, and what
survives is already rendered whole by the Hicasso tab. Removing it while the
panel still ships would instead restore the gate-blindness rf2-6pohj closed.

**So it goes when they go, under rf2-hic-062 — and it is EIGHT artefacts, not
four.** Four are the deck; four more name it by build id or by scenario name
from outside it. TWO of the eight red a gate, and they catch different
omissions: leave row 4's scenario and its `STAGED_SURFACES` entry standing while
the staged source and build go, and the PR-smoke run — derived from those
entries rather than from a fixed list — fails; remove the deck without moving
row 7's canonical count pin, and `coverage_matrix_metadata_test` fails. Neither
catches the other's omission. The remaining six go quietly stale, which is why
this is a written checklist rather than a grep.

| # | Artefact | The retirement pass | If left behind |
|---|---|---|---|
| 1 | `tools/xray/testbeds/freehand_views/` — `core.cljs` and `index.html` | delete the tree | dead source under a build id that is also going |
| 2 | the `:testbeds/freehand-views` build in `implementation/shadow-cljs.edn` | delete the build map | a build compiling a deleted tree |
| 3 | its port-8036 `:dev-http` entry in `implementation/shadow-cljs.edn` | delete the entry, freeing the 803x slot | a served root that no longer exists |
| 4 | the `freehand-views populated Views roster` scenario and its `STAGED_SURFACES` entry in `tools/xray/testbeds/feature_matrix/scenarios.cjs` | delete both | **RED — one of the two.** The PR-smoke run is derived from these entries rather than from a fixed list, so leaving them behind while the staged source and build go reds the smoke gate against a panel that is gone |
| 5 | the `DEV_HTTP` entry and the port-band comment in `implementation/scripts/dev-testbed.cjs` | delete both | the launcher advertises a URL for a build id shadow-cljs no longer knows — silently, because the drift guard in `dev-testbed.test.cjs` runs shadow-cljs → `DEV_HTTP` and never the reverse |
| 6 | the build→URL row in `implementation/README.md` | delete the row | a documented testbed nobody can start |
| 7 | the canonical covered-row pin in `coverage_matrix_metadata_test.clj` | drop it by one and rewrite its `12 -> 13` note | **RED — one of the two.** This scenario is the sole claimant of the `Mounted view reads (Freehand tool door, rf2-7gth0)` row, so removing the deck without dropping the pin fails `coverage_matrix_metadata_test` the moment it goes |
| 8 | the PR-smoke enumeration in `.github/workflows/test.yml` | drop the deck from the named list: 5 scenarios → 4, 4 staged surfaces → 3, 4 bundles → 3, and 12 → 11 in the nightly sweep | a comment naming a scenario that no longer exists |

Beyond the eight, three re-reads rather than removals. The dated aggregate
costings in `implementation/scripts/serve-and-run-xray-feature-gate.cjs` and in
this document's own opening move with row 8's numbers, but neither names the
deck and both already carry the date they were measured, so re-date them rather
than treat them as breakage. [`027-Hicasso-Evidence.md`](027-Hicasso-Evidence.md)
records that this deck's build id, port and scenario slot free up together at
rf2-hic-062 — true, and written about the moment rather than outliving it. And
the reactively-driven repaint above is the only browser-level proof that
`:adapter/activate-derived-value!` holds end to end in a real DOM, while
`re-frame.hicasso.impl.collector` calls that hook as well as the Freehand
observation port does — so it is the one asserted fact whose MECHANISM outlives
the deck, and the pass should confirm the surviving caller keeps a witness
rather than assume the proof retires with the Freehand cells.

## Cross-references

- [`000-Vision.md`](./000-Vision.md) - panel inventory and the five canonical questions.
- [`019-Cross-Cutting-Insight.md`](./019-Cross-Cutting-Insight.md) - the bug-class catalogue this matrix must cover.
- [`007-UX-IA.md`](./007-UX-IA.md) - chrome, keyboard, source-coordinate, redaction, launch, and production posture.
- [`011-Launch-Modes.md`](./011-Launch-Modes.md) - true-inline host default, optional overlay/debug chrome, pop-out, MCP coexistence, preload, and mount lifecycle.
- [`013-Trace-Consumer.md`](./013-Trace-Consumer.md) - trace buffer, filter vocabulary, privacy gate, lifecycle, and production elision.
- [`014-Registry-Catalogue.md`](./014-Registry-Catalogue.md) - owning `:rf.xray/*` ids for panel subscriptions/events/effects.
- [`015-Configuration.md`](./015-Configuration.md) - host-visible configuration keys and defaults.
- [`016-Auxiliary-Panels.md`](./016-Auxiliary-Panels.md) - per-tab content contracts (Epoch panel as the numbered cascade — supersedes the retired Event/Handler panel per rf2-5gl5r and absorbs the "fx handlers that ran" block, Issues tab content, Routes content folded into App-db + Trace, Flows content folded into Views).
- [`018-Event-Spine.md`](./018-Event-Spine.md) - 4-layer chrome, spine binding, filter pills, Settings popup, isolation invariants, data-classification rendering contract.
- [`012-Views.md`](./012-Views.md) - Views tab three-group layout, clustering.
- [`../../../testbeds/README.md`](../../../testbeds/README.md) - existing reusable testbeds for schema violation, non-trivial app-db, large/sensitive dispatchers, deep machines, long flows, HTTP, SSR, and multi-frame scenarios.
