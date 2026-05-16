# 017-Test-Coverage-Matrix

This spec defines the per-tool gate matrix `tools/causa-mcp/` must
grow around its existing unit/server/wire-pipeline tests. It is
intentionally a coverage contract, not an implementation plan: rows
name the per-tool wire-contract surface, the deterministic affordance
required to drive it, the direct happy path, the failure/empty/error
path, the cross-cutting re-checks every tool must respect
(degraded boot, runtime-not-preloaded, overflow envelope, privacy
default-drop, size elision, origin tagging), the diagnostics a failing
gate must print, the owning command/gate, and the current status.

Mirrors the shape pioneered by
[`tools/causa/spec/017-Test-Coverage-Matrix.md`](../../causa/spec/017-Test-Coverage-Matrix.md)
(rf2-f89ce — Causa-the-panel's per-surface matrix); applied here to
the eighteen-tool catalogue at
[`004-Tools-Catalogue.md`](004-Tools-Catalogue.md).

Current status values are as of 2026-05-17:

| Status | Meaning |
|---|---|
| `covered` | Every wire-contract slot (mechanism set, `:typical-tokens`, `:cap-reached`, defaults) plus the failure-envelope shapes named in §"Failure/empty/error path" have at least one corresponding `deftest` in `tools/causa-mcp/test/`. |
| `partial` | Some unit/wire coverage exists, but at least one named failure-envelope, cap-reached path, or cross-cutting re-check is missing. |
| `deferred` | Bookkeeping placeholder: promotion to `covered` is gated on a specific follow-on bead (referenced inline in the Status cell). |
| `missing` | No meaningful causa-mcp-specific automated coverage exists yet. |

The matrix supersedes the per-bead "Tests" subsections — a tool's
matrix row is the authoritative inventory of what its tests must
exercise. New per-tool tests land alongside their tool beads; the
matrix row's Status cell ticks `partial → covered` when the last
required deftest lands.

## Required shared affordances

The gate corpus prefers a small number of deterministic test
affordances over many narrow ad-hoc mocks. Every per-tool test file
draws from this shared set:

| Affordance | Requirement |
|---|---|
| Stub nREPL bridge | `set!` `day8.re-frame2-causa-mcp.nrepl/cljs-eval-value` to a fixture-returning promise; restore on `:after`. Lets the unit tier exercise the full envelope-shape pipeline without a live shadow-cljs build. |
| Stub runtime probe | `set!` `day8.re-frame2-causa-mcp.probe/ensure-runtime!` to a no-op or a rejected promise. The rejected-promise path exercises the `:runtime-not-preloaded` envelope every direct-read and mutation tool must surface. |
| Degraded-boot harness | `day8.re-frame2-causa-mcp.server/build-degraded-handler` returns a handler that responds to every `tools/call` with `{:ok? false :reason :nrepl-port-not-found}` — exercised by `server_test.cljs`. |
| Synthesised trace events | Hand-built `:rf/trace-event` maps the strip/elision/dedup helpers walk without a live runtime. Pinned by `privacy_test.cljs`, `elision_test.cljs`, `dedup_test.cljs`. |
| Synthesised epoch records | Hand-built `:rf/epoch-record` vectors the cursor / pagination paths slice without a live frame. Pinned by `cursor_test.cljs`, `get_epoch_history_test.cljs`. |
| EDN payload fixtures | Round-tripped through `pr-str` ↔ `edn/read-string` so the JS-string ↔ EDN keyword boundary is exercised. Every tool test parses the SDK `content[0].text` slot back through `edn/read-string`. |
| Token-cap harness | `apply-cap` invoked against a synthesised over-budget payload to exercise the `:rf.mcp/overflow` envelope shape. Pinned by `token_cap_test.cljs`. |
| Size-elision harness | Synthesised values carrying `{:rf.size/large-elided {...}}` markers so the walker doesn't need to nominate slots. Pinned by `elision_test.cljs`. |
| Allow-eval toggle | `set!` on the `eval-cljs` server-launch flag, restored on `:after`. Lets `eval_cljs_test.cljs` exercise both gate-OFF (refusal envelope, no nREPL hit) and gate-ON (happy path via stub) without a real CLI argv parse. |

## Cross-cutting re-checks

Every tool MUST honour these cross-cutting contracts. A per-tool row
that does not exercise the applicable re-checks ships `partial` until
the gap is closed.

| Re-check | Required of | Where exercised |
|---|---|---|
| **Degraded boot** | every tool | `server_test.cljs` exercises the catalogue-wide handler shape; individual tool tests pin per-tool happy path against a runtime-up stub. |
| **Runtime-not-preloaded** | every tool that touches `:probe/ensure-runtime!` | per-tool test surfaces `{:ok? false :reason :runtime-not-preloaded}` via a `probe/ensure-runtime!` rejection. |
| **`:rf.mcp/overflow` envelope** | every tool returning to the agent | catalogue-wide shape pinned by `token_cap_test.cljs`; per-tool `dispatcher-overflow-path` test pins the per-tool `:cap-reached` hint slot. |
| **Privacy default-drop** | every tool surfacing trace-stream-shaped payloads (`get-trace-buffer`, `subscribe`, `get-epoch-history`, `get-issues`) plus every direct-read tool (`get-app-db`, `get-app-db-diff`, `get-machine-state`) | `privacy_test.cljs` pins the catalogue-wide shape; per-tool tests pin the `:dropped-sensitive` stamp and `:include-sensitive?` opt-in. |
| **Size elision** | every tool emitting a tree-typed payload | `elision_test.cljs` pins the catalogue-wide walker; per-tool tests pin the `:elided-large` stamp and `:include-large?` opt-in. |
| **Origin tagging** | every mutation tool (`dispatch`, `restore-epoch`, `reset-frame-db`, `eval-cljs`) | per-tool `build-form-wraps-origin-…` test exercises the `:causa-mcp` token in the eval form; cross-cutting coverage of the `*current-origin*` binding contract pinned by I1 / I6 of the MUST-inventory. |
| **`--allow-eval` gate** | `eval-cljs` only | `eval_cljs_test.cljs` pins `gate-default-off`, `gate-toggle`, `gate-off-returns-refusal-without-nrepl-hit`. |
| **Bundle isolation** | catalogue-wide (Lock #11 — server-ns must not appear in browser bundles) | `implementation/scripts/check-bundle-isolation.cjs` greps the counter release bundle for the `bundle-isolation-sentinel` defonce string planted at the bottom of `server.cljs`. Owning gate: `npm run test:bundle-isolation` (rf2-8xzoe.35 / C-3). |

## Diagnostics required on failure

Every per-tool gate failure MUST print enough runtime state to debug
without re-running under a debugger:

| Diagnostic | Required contents |
|---|---|
| Scenario header | Tool name, test ns, deftest name, fixture state (allow-eval flag, stubbed nREPL?), arg map, expected vs actual envelope. |
| Wire payload | The `content[0].text` slot's raw string AND its `edn/read-string` projection; `isError` flag; any `progress` notifications observed (streaming tools). |
| Envelope state | `:ok?` keyword, `:reason` (on failure), `:hint` (when present), counter stamps (`:dropped-sensitive`, `:elided-large`, `:total`, `:remaining`), cap state (`:rf.mcp/overflow` slot if present). |
| Runtime stub state | Last form the stub `cljs-eval-value` was invoked with, last value it returned, whether `probe/ensure-runtime!` resolved or rejected. |
| Cross-cutting context | Token-cap state (configured cap, measured-token-count, would-be-token-count); elision marker count walked from the payload; dedup-table size (when applicable); allow-eval flag for `eval-cljs`. |

## Coverage matrix

The eighteen rows below — one per catalogue entry — name the
direct happy path, the failure/empty/error paths, the cross-cutting
re-checks each tool must respect, the diagnostics a failing test
must print, the owning gate, and the current status. Cap-reached
hints reference the keywords pinned per tool in
[`004-Tools-Catalogue.md`](004-Tools-Catalogue.md).

| Tool | Wire contract (mechanisms · :typical-tokens · :cap-reached · defaults) | Required affordance | Happy path | Failure/empty/error path | Cross-cutting re-checks | Diagnostics on failure | Owning command/gate | Status |
|---|---|---|---|---|---|---|---|---|
| `get-trace-buffer` | B-1, W-1, W-3, W-5, W-6 · ~2,000 (trace-burst) · `:paginate` · `:limit 50` `:offset 0` `:dedup? true` | Synthesised trace events; stub nREPL; token-cap harness; privacy + elision harnesses. | Dispatch fixture → `tools/call` → `:ok? true` with `:events` vector, `:count`, `:total`, `:limit`, `:offset`. | `:runtime-not-preloaded`; cap-reached → `:rf.mcp/overflow` with `:hint :paginate`; sensitive items dropped (`:dropped-sensitive` stamp). | Degraded boot · `:runtime-not-preloaded` · overflow envelope · privacy · size elision. | Stub state · returned `:events` count vs `:total` · drop/elide counters · cap-state. | `tools/causa-mcp` `npm test` (unit). | `covered` |
| `get-machine-list` | W-1, W-6 · ~800 · `:narrow-filter` · no `:dedup?` / `:mode` / `:limit` | Stub nREPL; synthesised registry map. | Stub returns registry-map → `:ok? true` with `:machines` map + `:count`. | `:runtime-not-preloaded`; cap-reached → `:rf.mcp/overflow` with `:hint :narrow-filter`. | Degraded boot · `:runtime-not-preloaded` · overflow · size elision. | Stub state · `:machines` keys · `:count`. | `tools/causa-mcp` `npm test`. | `covered` |
| `get-handlers` | W-1, W-6 · ~1,500 · `:narrow-filter` (re-call with `:kind`) · `:group-by-kind? true` | Stub nREPL; synthesised registrar metadata vector. | Stub returns `{kind handlers}` → `:ok? true` with `:handlers` map keyed by `:kind` + `:count` + `:kinds`. | `:runtime-not-preloaded`; cap-reached → `:rf.mcp/overflow`; flat mode (`:group-by-kind? false`) returns vector. | Degraded boot · `:runtime-not-preloaded` · overflow · size elision. | Stub state · `:kinds` vector · `:handlers` shape (grouped vs flat). | `tools/causa-mcp` `npm test`. | `covered` |
| `get-source-coord` | W-1 · ~50 · `:narrow-filter` · no `:dedup?` / `:mode` / `:limit` | Stub nREPL; synthesised handler metadata with `:source-coord` slot. | `:kind`/`:id` resolves → `:ok? true` with `:source-coord {:ns :line :column :file}`. | `:missing-kind`; `:missing-id`; `:no-source-coord`; `:runtime-not-preloaded`. | Degraded boot · `:runtime-not-preloaded` · overflow envelope. | Stub state · arg map · which keyword was missing. | `tools/causa-mcp` `npm test`. | `covered` |
| `get-machine-state` | W-1, W-2, W-4, W-6 · ~600 `:summary` / ~3,000+ `:full` · `:slice` / `:switch-mode` · `:mode :summary` | Stub nREPL; synthesised machine spec; path-slice harness; summary-projection harness. | `:mode :summary` returns state-names + tags; `:mode :full` returns whole spec; `:path` slices subtree. | `:runtime-not-preloaded`; `:no-frame-resolved`; cap-reached → `:rf.mcp/overflow` with `:hint :slice` / `:switch-mode`. | Degraded boot · `:runtime-not-preloaded` · overflow · size elision · path slicing. | Stub state · `:mode` chosen · `:path` arg · `:state` keys. | `tools/causa-mcp` `npm test`. | `covered` |
| `get-issues` | B-1, W-1, W-3, W-6 · ~1,200 (issues are sparse — `:dedup? false`) · `:paginate` · `:limit 50` `:offset 0` `:severity :all` | Stub nREPL; synthesised issue-tier events; privacy harness. | Severity filter narrows; pagination via `:limit` / `:offset`; `:ok? true` with `:issues` vector + counts. | `:runtime-not-preloaded`; cap-reached → `:rf.mcp/overflow`; sensitive issues dropped by default; severity opt-in (`:error` / `:warning` / `:all`). | Degraded boot · `:runtime-not-preloaded` · overflow · privacy · size elision. | Stub state · `:severity` arg · `:dropped-sensitive` counter · `:count` vs `:total`. | `tools/causa-mcp` `npm test`. | `covered` |
| `get-epoch-history` | B-1, W-1, W-3, W-5, W-6 · ~3,500 (epoch-slice 5-10× dedup) · `:paginate` · `:limit 50` `:dedup? true` | Stub nREPL; synthesised epoch records; cursor harness; privacy + elision harnesses. | Stub returns history → first page `:ok? true` with `:epochs` + `:next-cursor`; resume via cursor; oldest-first ordering. | `:runtime-not-preloaded`; `:cursor-stale` (cursor's `:after-id` evicted); cap-reached → `:rf.mcp/overflow` with `:hint :paginate`; sensitive epochs dropped. | Degraded boot · `:runtime-not-preloaded` · overflow · privacy · size elision · cursor stability. | Stub state · `:cursor` arg decoded · `:requested-id` on stale-cursor · `:dropped-sensitive` / `:elided-large` counters. | `tools/causa-mcp` `npm test`. | `covered` |
| `get-app-db` | W-1, W-2, W-4, W-6 · ~400 `:summary` / ~3,000+ `:full` · `:slice` / `:switch-mode` · `:mode :summary` · `:include-sensitive? false` (MUST 19) · `:include-large? false` (MUST 19) | Stub nREPL; synthesised app-db; path-slice harness; summary-projection harness. | `:mode :summary` returns `{:rf.mcp/summary {:type :keys :count}}`; `:mode :full` returns full value; `:path` slices subtree. | `:runtime-not-preloaded`; `:invalid-mode`; cap-reached → `:rf.mcp/overflow` with `:hint :slice` / `:switch-mode`. | Degraded boot · `:runtime-not-preloaded` · overflow · direct-read privacy + elision defaults (MUST 19) · path slicing. | Stub state · `:mode` chosen · `:path` arg · top-keys (`:summary`) · `:elided-large` counter. | `tools/causa-mcp` `npm test`. | `covered` |
| `get-app-db-diff` | W-1, W-4, W-6 (in `:nested` mode) · ~300 `:changed-paths` / ~4,000+ `:nested` · `:switch-mode` / `:slice` · `:mode :changed-paths` (MUST 13) | Stub nREPL; synthesised diff payload (changed/added/removed paths); summary-projection harness. | `:mode :changed-paths` (default per MUST 13) returns path vectors + cardinality counts; `:mode :nested` returns before/after diff. | `:missing-epoch-id`; `:invalid-mode`; `:runtime-not-preloaded`; cap-reached → `:rf.mcp/overflow` with `:hint :switch-mode` / `:slice`. | Degraded boot · `:runtime-not-preloaded` · overflow · direct-read privacy + elision defaults · MUST-13 default-mode check. | Stub state · `:mode` chosen · path-set sizes · `:elided-large` counter (`:nested` mode). | `tools/causa-mcp` `npm test`. | `covered` |
| `dispatch` | W-1 · ~100 · `:narrow-filter` · `:sync? false` (`:mode :queued`) | Stub nREPL; origin-tag harness. | Event vector parsed → form built with `*current-origin* :causa-mcp` binding → `:ok? true` with `:event-id`, `:frame`, `:origin :causa-mcp`, `:mode :queued|:sync`. | `:missing-event`; `:event-malformed`; `:not-an-event-vector`; `:no-frame-resolved`; `:runtime-not-preloaded`. | Degraded boot · `:runtime-not-preloaded` · origin tagging (I1) · overflow envelope. | Stub state · raw `:event` arg · parsed event vector · `:origin` slot · `:sync?` flag. | `tools/causa-mcp` `npm test`. | `covered` |
| `restore-epoch` | W-1 · ~120 · `:narrow-filter` · no `:dedup?` / `:mode` / `:limit` | Stub nREPL; origin-tag harness; six-row failure-table harness. | `:epoch-id` resolved → `:ok? true` with `:frame`, `:epoch-id`, `:origin :causa-mcp`. | Six rows from §"Six-row failure table" — each surfaces `:reason :rf.epoch/restore-failed` with hint pointing at trace bus for the structured `:rf.epoch/*` row; `:runtime-not-preloaded`. | Degraded boot · `:runtime-not-preloaded` · origin tagging · overflow envelope. | Stub state · `:epoch-id` arg · returned `:reason` · trace-bus row count. | `tools/causa-mcp` `npm test`. | `covered` |
| `reset-frame-db` | W-1 · ~80 · `:narrow-filter` · no `:dedup?` / `:mode` / `:limit` | Stub nREPL; origin-tag harness; three-row failure-table harness. | `:value` map applied → `:ok? true` with `:frame`, `:origin :causa-mcp`. | Three rows from §"Three-row failure table" — each surfaces `:reason :rf.epoch/reset-failed` with hint pointing at trace bus; `:runtime-not-preloaded`. | Degraded boot · `:runtime-not-preloaded` · origin tagging · overflow envelope. | Stub state · `:value` arg · returned `:reason` · trace-bus row count. | `tools/causa-mcp` `npm test`. | `covered` |
| `subscribe` | B-1, W-1 (per-tick + terminal), W-5 (per-tick dedup), W-6 · ~1,800/tick `:trace` (1.4× burst), ~10× recurring-cascade · `:paginate` · `:poll-ms 100` `:dedup? true` `:max-events 0` `:max-ms 0` | Stub nREPL; synthesised drain-batch fixtures; progress-notification harness. | `:topic` resolved → `notifications/progress` per non-empty tick with `:events` vector → terminal summary with `:delivered`, `:ticks`, `:reason`. | `:unknown-topic`; `:runtime-not-preloaded`; `:max-events-reached` / `:max-ms-reached` / `:aborted` / `:unsubscribed`; cap-reached → `:rf.mcp/overflow`. | Degraded boot · `:runtime-not-preloaded` · overflow · privacy · size elision · per-drain-batch shape (MUST 21). | Stub state · `:topic` arg · tick count · per-tick `:events` count · `:reason` on close · `:dropped-sensitive` / `:elided-large` cumulative counters. | `tools/causa-mcp` `npm test`. | `covered` |
| `unsubscribe` | W-1 · ~60 · `:narrow-filter` · no `:dedup?` / `:mode` / `:limit` | Stub nREPL; sub-registry harness. | `:sub-id` resolved → `:ok? true` with `:sub-id`, `:existed? true|false`. | `:missing-sub-id` (returned as `isError`); `:runtime-not-preloaded`. | Degraded boot · `:runtime-not-preloaded` · idempotence (re-call after close → `:existed? false`). | Stub state · `:sub-id` arg · `:existed?` flag · registry state. | `tools/causa-mcp` `npm test`. | `covered` |
| `list-subscriptions` | W-1, W-6 · ~400 · `:narrow-filter` (scope via `:topic`) · no `:dedup?` / `:mode` / `:limit` | Stub nREPL; sub-registry harness. | Stub returns sub-list → `:ok? true` with `:subs` vector + `:count`. | `:runtime-not-preloaded`; cap-reached → `:rf.mcp/overflow` with `:hint :narrow-filter`. | Degraded boot · `:runtime-not-preloaded` · overflow · size elision. | Stub state · `:topic` / `:sub-id` filter args · `:count`. | `tools/causa-mcp` `npm test`. | `covered` |
| `eval-cljs` | W-1, W-6 (when enabled) · variable, cap-bounded · `:narrow-filter` · `:include-sensitive? false`, `:include-large? false`, gate default **OFF** (MUST 20) | Stub nREPL; allow-eval toggle; origin-tag harness; size-elision harness. | Gate ON → form wrapped with `*current-origin* :causa-mcp` binding → runtime eval → `:ok? true` with `:value`. | Gate OFF → `:reason :rf.error/eval-cljs-disabled` refusal envelope **without** nREPL hit; `:missing-form`; `:blank-form`; `:runtime-not-preloaded`; `:eval-error :message`. | Degraded boot · `:runtime-not-preloaded` · `--allow-eval` gate (MUST 20) · origin tagging on synchronous-extent (I1 + I6 — async-escape documented per Lock #4) · size elision · overflow envelope. | Stub state · allow-eval flag · raw `:form` string · gate-OFF-verified-no-nREPL-hit · `:elided-large` counter. | `tools/causa-mcp` `npm test`. | `covered` |
| `discover-app` | W-1 · ~250 · `:narrow-filter` · no `:dedup?` / `:mode` / `:limit` | Stub nREPL; runtime-status fixtures (debug-on/off, frame-registered/empty/ambiguous, coord-annotation on/off, allow-eval state). | Healthy → `:ok? true` with full health envelope including `:eval-cljs-enabled?` and `:origin :causa-mcp`. | Warning ladder: `:debug-disabled` (`:ok? false`); `:no-frames-registered` (`:ok? false`); `:ambiguous-frame` (`:ok? true` + `:warning`); `:no-source-coord-annotation` (`:ok? true` + `:warning`); `:runtime-not-preloaded`. | Degraded boot · `:runtime-not-preloaded` · overflow envelope · stamps `:eval-cljs-enabled?` flag for the agent. | Stub state · runtime-status fixture · returned `:warning` keyword · returned `:eval-cljs-enabled?` flag. | `tools/causa-mcp` `npm test`. | `covered` |
| `tail-build` | W-1 · ~40 · `:narrow-filter` · `:wait-ms 5000` · `:mode :probe` when `:probe` supplied, else `:soft-delay` | Stub nREPL; clock harness (to advance `:wait-ms` without real-time sleeping). | Probe-mode → value-change detected → `:ok? true` with `:t`, `:soft? false`; soft-delay-mode → `:ok? true` with `:soft? true` after 300ms. | Probe-mode timeout → `:reason :timed-out`; probe eval threw → `:reason :probe-failed`; `:runtime-not-preloaded`. | Degraded boot · `:runtime-not-preloaded` · overflow envelope · clock-driven timeout (no real sleeps in tests). | Stub state · `:probe` arg · returned `:t` · `:soft?` flag · `:wait-ms` budget · clock state. | `tools/causa-mcp` `npm test`. | `covered` |

## Gate ownership

| Gate | Scope |
|---|---|
| `tools/causa-mcp` unit gate | `npm test` from `tools/causa-mcp/` — shadow-cljs `:server-test` build runs every `*-test.cljs` under `test/`. Default local/CI coverage for the catalogue and wire-pipeline cross-cuts. |
| `tools/mcp-conformance` cross-server gate | `npm test` from `tools/mcp-conformance/` — exercises the cross-server reservations (`:rf.mcp/overflow` shape, `:sensitive?` default-drop, `:rf.size/large-elided` marker, `max-tokens` arg name) shared with pair2-mcp + story-mcp. Causa-MCP rows in the cross-server matrix are tracked by rf2-zvv65. |
| `npm run test:bundle-isolation` | implementation-side script gate (`implementation/scripts/check-bundle-isolation.cjs`) that greps the counter release bundle for the `bundle-isolation-sentinel` defonce string planted in `server.cljs`. Pins Lock #11 — server-ns must never appear in a browser-targeted CLJS bundle (rf2-8xzoe.35). |
| `mkdocs build --strict` | catches stale cross-references between this matrix, `004-Tools-Catalogue.md`, `004-Wire-Pipeline.md`, and the per-tool source comments. |

## Promotion from `partial` to `covered`

A row promotes from `partial` (or `missing`) to `covered` when **all
five** of the following are true:

1. **Happy path** has at least one `deftest` exercising the full
   envelope shape through a stubbed nREPL (no live runtime needed).
2. **Failure/empty/error paths** each have at least one `deftest`
   matching the row's `Failure/empty/error path` cell.
3. **Cross-cutting re-checks** named in the row's `Cross-cutting`
   cell are each covered — either inline in the per-tool test or by
   a cross-cutting test (`server_test.cljs` for degraded boot,
   `privacy_test.cljs` for the privacy boundary, `elision_test.cljs`
   for the size walker, `token_cap_test.cljs` for the overflow
   envelope).
4. **Diagnostics** — failing tests print enough state (per
   §"Diagnostics required on failure") to debug without re-running.
   Where a test prints less, the gap is filed as a follow-on.
5. **Mechanism set** declared in
   [`004-Tools-Catalogue.md`](004-Tools-Catalogue.md) matches the
   set the tool actually applies at runtime — drift between catalogue
   and implementation is a `partial` regression.

A row regresses from `covered` to `partial` when any of the five
slips (e.g. a new MUST lands in `004-Wire-Pipeline.md`, the
corresponding inventory row is added, but no test pins it yet).

## Cross-references

- [`000-Vision.md`](000-Vision.md) — what Causa-MCP is, the
  eighteen-tool catalogue, the two-namespace split.
- [`Principles.md`](Principles.md) — load-bearing tie-breakers
  (origin tagging, EDN canonical, closed-set catalogue,
  single persistent nREPL socket, degraded boot).
- [`004-Tools-Catalogue.md`](004-Tools-Catalogue.md) — the
  normative per-tool catalogue (signatures + return shapes +
  wire-pipeline contract) the matrix above gates.
- [`004-Wire-Pipeline.md`](004-Wire-Pipeline.md) — the six
  mechanisms and the privacy + streaming-over-batch cross-cuts
  the cross-cutting re-checks above pin.
- [`findings/MUST-inventory.md`](findings/MUST-inventory.md) — the
  21 explicit + 6 implicit MUSTs that bind the cross-cutting
  re-checks to concrete deftest names (rf2-8xzoe.33 / C-1).
- [`tools/causa/spec/017-Test-Coverage-Matrix.md`](../../causa/spec/017-Test-Coverage-Matrix.md) —
  the rf2-f89ce / Causa-the-panel matrix this doc mirrors.
- [`tools/mcp-conformance/`](../../mcp-conformance/) — the
  cross-server reservation gates (`:rf.mcp/overflow`,
  `:rf.size/large-elided`, `:sensitive?` default-drop) shared
  with pair2-mcp + story-mcp.
