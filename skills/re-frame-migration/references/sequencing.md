# sequencing

The recommended order to walk the migration rules. Restated so a partial migration can resume cleanly without re-reading the full [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) ordering.

## Top-level shape

```
Phase 0a — Inventory-and-plan  (incl. the SILENT-fail app-source grep)
 │
 ▼
Phase 2 — Bump (M-0)
 │
 └──> compile + tests
       │
       ├── failures ──> Phase 3 (sweep) ─┐
       │                                  │
       └── clean compile ─────────────────┤
                                          ▼
                          Apply / triage the Phase-0a SILENT-fail plan
                          + the M-70 metadata-interceptors sweep
                          (none surface at compile — applied
                           regardless of whether the compile failed;
                           M-70 is loud-at-RUNTIME, not silent)
                                          │
                                          ▼
                          Phase 4 — compile + tests + BOOT SMOKE-TEST
                                          │
                          ┌── smoke fails ─┘
                          │  (back to the relevant sweep group)
                          ▼
                  all green AND smoke clean ──> Phase 6 (report). Done.
```

**"All green" is not "clean compile."** Phase 4's done-bar is compile + tests + a clean **boot smoke-test** with live `app-db` introspection, with every Type B decision resolved — *not* a clean compile (see [`../SKILL.md`](../SKILL.md) Phase 4 + Done checklist, and [`runtime-smoke-test.md`](runtime-smoke-test.md)). v2 moves a large class of v1 failures from compile-time to **runtime**; a clean compile means "the rewrites parse," not "the app boots and runs."

[`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) Part 2 §"Your task" makes the headline expectation explicit: *most codebases require no changes at all beyond M-0*. That holds for the **forced/loud** axis — but the silent-fail rules still need applying, so verify *and* run the smoke-test before calling it done.

The sweep order below is for the failures a compile *surfaces*. Two classes of breakage are **not** in that bucket and must be carried in regardless of whether the compile fails:

- **Forced removals/conversions** — broken add-ons, off-contract requires, classpath-colliding transitives — are known and planned from **Phase 0a** ([`inventory-and-plan.md`](inventory-and-plan.md)): the inventory scans each add-on's source up front so they're fixed in one sweep rather than one-per-recompile. Use the Phase-0a plan's ordering to clear the forced compile-blockers (so the post-M-0 compile gate is reachable).
- **SILENT-fail rules** ([`breaking-changes.md` §Failure-visibility axis](breaking-changes.md#failure-visibility-axis--loud-fail-vs-silent-fail-orthogonal-to-type-ab)) — `{:db fresh}` boots (M-15b), top-level `:dispatch` keys (M-8), the signal-fn `reg-sub` (M-71), `^:flush-dom` (M-16), unary `reg-fx` handlers (M-51 — `(fn [args] …)` binds the ctx-map to `args` on CLJS and silently drops the real fx args) — **compile clean** and never appear as a compile failure. They're caught by the Phase-0a app-source grep and **applied/triaged whether or not the compile failed**; the [boot smoke-test](runtime-smoke-test.md) is the only thing that confirms the fix landed. A clean compile does NOT route you straight to the report — the planned silent-fail hits still apply, and the smoke-test still runs.
- **The M-70 metadata-interceptors sweep** — *loud-at-runtime, not silent, but carried the same way.* Event interceptor chains outside metadata `:interceptors` **compile clean** and then throw at ns-load (aborting the offending ns → the app hangs at boot). Because the *compile* never surfaces them, this rides the **same Phase-0a up-front structural grep** as the silent rules (scan every `reg-event-*` post-id shape; flag bare interceptors, positional vectors, and metadata-plus-vector forms regardless of identity), is **applied whether or not the compile failed**, and the [boot smoke-test](runtime-smoke-test.md) (row #6) surfaces any survivor's throw on the console. Its sweep-group slot is **Group 5 (item 19a)** below; it differs from the silent rows only in failure mode, not in detector. → [`auto-cross-cutting.md` §M-70](auto-cross-cutting.md#event-interceptor-chains--metadata-interceptors-m-70--mechanical-loud-at-runtime-not-loud-at-compile).

Use the sweep order below for whatever the compile surfaces.

## When failures land — the sweep order

The M-rule numbering in [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) *is* the sweep order. Walk them low-to-high. Later rules sometimes depend on earlier ones being resolved (M-1 surfaces private-namespace requires; M-15's seeding rewrite assumes the M-1 fix has run; M-21's `on-changes` rewrite assumes the flows artefact M-30 has been added).

### Group 1 — Coord and private namespaces (foundation)

| Order | Rule | Why first |
|---|---|---|
| 1 | **M-0** | Already done in Phase 2. The whole migration runs against the new classpath. |
| 2 | **M-1** | Every other rule assumes `re-frame.core` is the only allowed re-frame namespace. Private-namespace requires would cause spurious compile errors elsewhere. |
| 3 | **M-38** | Substrate-adapter ns rename (`re-frame.substrate.<name>` → `re-frame.adapter.<name>`). Codebases that explicitly required the substrate (rare; usually only set up code) hit this. |
| 4 | **M-40** | `(rf/init!)` requires the adapter spec map. **Type B** — the rewrite is mechanical given a chosen adapter, but surface every `init!` call site so the author confirms which adapter the app boots against. **For a genuine v1 app there is no `init!` to find — ADD one, ahead of the first dispatch and the first render** (`init!` creates **no** frame under EP-0002; you ALSO `reg-frame` an explicit app frame and wrap the render in `frame-provider-existing` — the EP-0024 scope-only React-context member, since the frame already exists from `reg-frame` — else a boot dispatch under no scope fails loudly with `:rf.error/no-frame-context`). See [`auto-cross-cutting.md` §Init / adapter](auto-cross-cutting.md#init--adapter-m-40) + §Boot-sequence invariant. |

### Group 2 — Macro / value-form / shape rewrites (compile-level)

| Order | Rule | Why here |
|---|---|---|
| 5 | **M-5** | `reg-*` are macros now; higher-order use breaks at compile time. Surface before behaviour-shape rules. |
| 6 | **M-22** | `reg-view` is a defn-shape macro; keyword-shape calls fail to expand. Compile-level. |
| 7 | **M-23** | `re-frame.alpha` namespace removed. Compile-level (require fails). |
| 8 | **M-24** | `rf/h` removed. Compile-level (symbol unresolved). |
| 9 | **M-25** | `re-frame.test` renamed to `re-frame.test-support`. Compile-level. |
| 9a | **M-64** | If the codebase uses `reset-runtime-fixture-factory`. Rename to `make-reset-runtime-fixture`. Closed mechanical rename. Pairs with M-25 (same `re-frame.test-support` ns). v2-pre-rename only. |
| 9b | **M-62** | If the codebase uses `assert-state`. Split into `assert-path-equals` (vector path form) + `assert-db-equals` (full-db form) per. Disambiguation moves from arity to call site. Pairs with M-25. v1's `assert-state` (path form only) → `assert-path-equals` directly. |
| 10 | **M-26** | Drift-sweep drops — most are symbol-not-found at compile time. The Type B `add-post-event-callback` half waits for behavioural review. |

### Group 3 — Effect map / dispatch shape (compile-or-warning)

| Order | Rule | Why here |
|---|---|---|
| 11 | **M-4** | Master's `dispatch-with` / `dispatch-sync-with` removed. Most codebases unaffected. |
| 12 | **M-8** | Fold top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` / user-fx-id keys into `:fx`. High-impact mechanical rewrite. |
| 13 | **M-9** | `dispatch-sync` inside handlers → `:fx [[:dispatch ...]]`. |
| 14 | **M-16** | `^:flush-dom` metadata. **M-16a** (inside an effect map): mechanical `:fx [[:dispatch-later {:ms 0 :event …}]]` rewrite, Type A. **M-16b** (top-level `(rf/dispatch ^:flush-dom …)`): NO `:fx` rewrite — `(rf/dispatch-later …)` is not a fn and throws; classify by location and flag for human review (drop the latency, or route through a one-shot trampoline). |
| 14a | **M-51** | Unary `reg-fx` handler `(fn [args] …)` → binary `(fn [_ args] …)`. Mechanical (Type A) but **SILENT-fail** — on CLJS the unary form parses + compiles clean, binds the runtime's context-map to `args`, and drops the real fx args with no error. **Exhaustive up-front grep, never march-the-wall** (the compile never surfaces it); confirm via the boot smoke-test. Async handlers should additionally hold `(rf/frame-handle)` and dispatch via `(:dispatch h)`. |

### Group 4 — Reserved-namespace renames (mechanical)

| Order | Rule | Why here |
|---|---|---|
| 15 | **M-20** | Framework keyword consolidation. Closed mechanical rename table. Apply before M-10 so M-10's collision audit doesn't false-positive on legacy framework ids. |
| 16 | **M-10** | Reserved-namespace collision audit. Type B; surfaces user registrations under `:rf/*` for human review. |
| 17 | **M-35** | Actor-lifecycle fx-id rename (`:spawn` → `:rf.machine/spawn`). |
| 18 | **M-34** | Spawn-id tracking moved (`[:data :pending]` → runtime-owned `[:rf.runtime/machines :spawned ...]`); `:on-spawn` becomes advisory. **Type B** — listed here because it composes with M-35's fx-id rename, but the rewrite is asked-first: flag every declarative-`:spawn` site and especially any test asserting on the old leak-on-missing-`:on-spawn` behaviour or a stale `[:rf.runtime/machines :snapshots]` entry after exit. Do not apply silently. |
| 18a | **M-56** | Machine vocabulary divergence. Closed rename table: `:invoke` → `:spawn`, `:invoke-all` → `:spawn-all`, plus all sibling `:rf/invoke-*` snapshot keys, `:rf.machine.invoke*/*` trace ops, `:rf.error/machine-invoke-*` error categories, `:rf.invoke/*` generated-action ns. Apply alongside M-35 (the fx-id sibling). v2-pre-rename only. |
| 18b | **M-60** | Route event + trace rename. `:rf/url-changed` → `:rf.route/transitioned`; `:rf.route/url-changed` → `:rf.route/fragment-changed`. Closed two-keyword rename. Pairs with M-29 (routing artefact). v2-pre-rename only. |

### Group 5 — Interceptors and registration metadata

| Order | Rule | Why here |
|---|---|---|
| 19 | **M-21** | Drop `debug` / `trim-v` (mechanical). Flag `on-changes` / `enrich` / `after` (Type B). |
| 19a | **M-70** | Event interceptor chains outside metadata `:interceptors` → register each value with `reg-interceptor` and reference it by id in `{:interceptors [...]}` (chains are reference-only under EP-0022 — an inline value throws `:rf.error/inline-interceptor-removed`). Mechanical (Type A), but **loud-at-RUNTIME, not loud-at-compile** — bare/retired positional shapes throw at ns-load while the compile passes. So it can't ride march-the-wall: it's found by the **Phase-0a up-front structural grep** (scan every `reg-event-*` post-id shape; flag bare, vector, and metadata-plus-vector chain shapes by *shape*, not identity) and confirmed by the boot smoke-test. Pairs with M-21 (same interceptor-chain surface). See [`auto-cross-cutting.md` §M-70](auto-cross-cutting.md#event-interceptor-chains--metadata-interceptors-m-70--mechanical-loud-at-runtime-not-loud-at-compile). |
| 20 | **M-17** | `reg-global-interceptor` / `clear-global-interceptor` removed. Single-frame: mechanical. Multi-frame: ask. |
| 21 | **M-7** | `reg-fx` / `reg-cofx` `:platforms` default; add `:platforms #{:client}` for browser-only fx. |
| 21a | **M-58** | Trace-redaction factory rename. `with-redacted` → `redact-interceptor`. Single-symbol mechanical rename. v2-pre-rename only. |
| 21b | **M-59** | Interceptor-value family suffix. `at-boundary` → `validate-at-boundary-interceptor`; `unwrap` → `unwrap-interceptor`. Two-symbol Var rename — interceptor `:id` keywords unchanged. v2-pre-rename only. (Both are interceptor **values** used by-reference under EP-0022 — registered then referenced by id, never inline. A v1→v2 `unwrap` migrates to handler destructuring or a project-registered interceptor, not this rename.) |
| 21c | **M-55 / M-69** | Listener-registration verb + namespace consolidation. Apply **M-69**'s table (`register-trace-listener!` → `register-listener!`, `register-event-emit-listener!` → `register-event-listener!`, `register-error-emit-listener!` → `register-error-listener!`); M-69 supersedes M-55's event/error-emit targets. Closed mechanical rename. v2-pre-rename only — a v1→v2 migration lands on the current names via M-26. |

### Group 6 — Run-to-completion / cache / counts (behaviour)

| Order | Rule | Why here |
|---|---|---|
| 22 | **M-3** | Dispatch ordering — run-to-completion drain. Type B; flag every `:dispatch` inside a handler and every test asserting queue / intermediate-render shape. |
| 23 | **M-6** | Drain-depth limit. Most codebases unaffected; runtime-error-triggered. |
| 24 | **M-12** | Sub-cache invalidation changes render counts. Type B; flag render-count assertions. |
| 25 | **M-44** | `:timeout-ms` removed from `:spawn` / `:spawn-all`. Use parent state's `:after` timer. |

### Group 7 — Private-state / lifecycle / handler dropouts

| Order | Rule | Why here |
|---|---|---|
| 26 | **M-15** | App-db seeding via `:on-create` (pairs with M-1's private-ns rewrite). |
| 27 | **M-11** | Plain (non-`reg-view`) Reagent fns can't read a surrounding `frame-provider`'s frame (no `:contextType`); a bare ambient `subscribe`/`dispatch` raises `:rf.error/no-frame-context` (EP-0002). Fix: `reg-view`, a captured `frame-handle`, or a `with-frame` scope. Type B. Most acute in multi-frame apps, but a bare ambient call in an unregistered plain fn raises even under a single root provider. |
| 28 | **M-13** | `reg-event-error-handler` removed; **no** app-steering frame-level `:on-error` recovery policy (recovery is framework-owned). Observability → `register-error-listener!` (always-on) / `register-listener!` (dev-only). Type B. |
| 29 | **M-18** | `reg-sub-raw` removed. Four rewrite paths (read-only-app-db, fx-driven, machine, anti-pattern). Type B. |
| 29b | **M-71** | **v1 signal-function `reg-sub` form** (`(reg-sub :id signal-fn computation-fn)`) → v2 `input-fn`. The first fn returns a **vector of query vectors** (`[[:item id] [:selected]]`), not `subscribe` reactions. Static inputs prefer `:<-`; three return shapes rewrite differently — vector (drop `subscribe`), map (pick an explicit order + vector destructure), single-signal (`[[:item id]]`); an `app-db`-reading signal fn threads the param through the outer query vector. Type B. |
| 30 | **M-42** | React-19-removed Reagent surfaces (throw-on-call shims under the slim adapter). **A/B split** — the `render` / `unmount-component-at-node` mount-path rewrites are Type A (mechanical once the `container` ref is identified). `dom-node` and `force-update-all` are **Type B** — no static replacement (`findDOMNode` consumers need a `:ref` at the *parent* call site; `force-update-all` had no documented use beyond global-rebuild scripts). Flag both for human review; do not rewrite silently. |

### Group 8 — Per-feature artefact splits (dep-only adds; pair with the feature-trigger rules)

| Order | Rule | Pairs with |
|---|---|---|
| 31 | **M-27** | Triggered by `reg-app-schema` / `:schema` keys (incl. `:schema` on `reg-event-*` — the key is `:schema` post-M-54, was `:spec` pre-M-54). Add `day8/re-frame2-schemas`. |
| 31a | **M-61** | If the codebase calls `re-frame.schemas/validate-app-db!` / `validate-sub-return!` directly or publishes the matching late-bind hook keys. Rename to `validate-app-schema!` / `validate-sub!`. Pairs with M-27. v2-pre-rename only. |
| 32 | **M-28** | Triggered by `reg-machine` / `sub-machine`. Add `day8/re-frame2-machines`. |
| 32a | **M-57** | If the codebase uses `(rf/create-machine-handler ...)`. Rename to `make-machine-handler`. Also rename `:machines/create-machine-handler` late-bind hook key. Pairs with M-28. v2-pre-rename only. |
| 33 | **M-29** | Triggered by `reg-route` / `:rf.route/*` events. Add `day8/re-frame2-routing`. Pairs with M-14 (the `not-found` requirement). |
| 34 | **M-30** | Triggered by `reg-flow` or by M-21's `on-changes` rewrite. Add `day8/re-frame2-flows`. |
| 35 | **M-31** | Triggered by `:rf.http/managed` fx. Add `day8/re-frame2-http`. |
| 36 | **M-32** | Triggered by `render-to-string` (SSR). Add `day8/re-frame2-ssr`. |
| 37 | **M-33** | Triggered by `epoch-history` / `restore-epoch`. Add `day8/re-frame2-epoch`. |
| 38 | **M-39** | If the codebase uses `reg-http-interceptor` / `clear-http-interceptor`. Pairs with M-31. |
| 38a | **M-63** | If the codebase uses `reg-http-interceptor`. Reshape signature to single interceptor-map `(reg-http-interceptor id {:before … :after …})`. Pairs with M-39. |
| 38b | **M-65** | If the codebase uses the HTTP stubbing macros (`with-managed-request-stubs` / `install-managed-request-stubs!` family). Add `[re-frame.http-test-support]` to the test ns require closure. Pairs with M-31. |

### Group 9 — Conditional / opt-trigger rules

| Order | Rule | Trigger |
|---|---|---|
| 39 | **M-14** | Only if the user is adopting Spec 012's routing surface (paired with M-29). Otherwise N/A. |
| 40 | **M-19** | Opt-in shift to map-payload event vectors. Off by default; only run if the user has explicitly asked to modernise. |
| 41 | **O-16** | v1 add-on-lib **conversion** (opt-in). Detected by `day8.re-frame/async-flow-fx` coord + `:async-flow` fx fingerprint. Convert flows → `reg-machine`. Type B (ask first). **But the add-on does NOT keep working:** `async-flow-fx` calls the removed `re-frame.core/console` and fails to compile on v2, so removal-or-conversion is a **forced compile-gate pre-step** (runs in the Group 1 sweep, surfaced at the post-M-0 compile gate), not a Phase-5 leisure item. The *conversion* is the opt-in part; *acting* is not. See [`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in). |
| 42 | **O-17** | v1 add-on-lib **conversion** (opt-in). Detected by `day8.re-frame/http-fx` coord + `:http-xhrio` fx fingerprint. Convert `:http-xhrio` → `:rf.http/managed` (pairs with M-31's `day8/re-frame2-http` artefact add). Type B (ask first). **But the add-on does NOT keep working:** `http-fx` `:refer`s the removed `re-frame.core/console` and fails to compile on v2, so removal-or-conversion is a **forced compile-gate pre-step**, not deferrable. Same forced/opt-in split as O-16. See [`breaking-changes.md` §v1 add-on libraries fail to COMPILE on v2](breaking-changes.md#v1-add-on-libraries-fail-to-compile-on-v2--replacementremoval-is-forced-not-opt-in). |
| 43 | **O-18** | Security + operational logging sweep (opt-in). Detected by the observability sites M-13 / M-17 surface (audit loggers, telemetry forwarders). Compose privacy + oversize defenses per observer egress + propose `{:sensitive? true}` schema annotations. Type B (flag per site). Run after M-13 / M-17 have classified the observers. |

## When to pause for human review

The Type B rules each have a documented question. Group them at the end of the sweep and present them in one batch — the author makes all the decisions in a single sitting rather than getting interrupted N times during the migration.

Order of presentation within the batch (most-blocking first):

1. **M-3** — run-to-completion impact: any animation timing, queue-peek tests, intermediate-render dependencies.
2. **M-18** — `reg-sub-raw` rewrites: each call site needs the user's read on what the raw body is doing.
3. **M-71** — the **v1 signal-function `reg-sub` form** → v2 `input-fn`: the agent must learn whether the signal fn's inputs are query-dependent (else prefer `:<-`) and which return shape it has (vector / map / single-signal) before picking the rewrite — a map return forces an explicit input-order choice; an `app-db`-reading signal fn must thread the param through the outer query vector.
4. **M-11** — plain-Reagent fns under non-default frames: each component-frame pair.
5. **M-17** — multi-frame `reg-global-interceptor`: each-frame vs trace-listener vs default-only.
6. **M-21** — `on-changes` / `enrich` / `after`: flow / schema / registered-interceptor (`reg-interceptor` + ref by id) / fx routing.
7. **M-10** — reserved-namespace collisions.
8. **M-5** Var-aliasing — refactor to direct invocation.
9. **M-13** — `reg-event-error-handler` policy.
10. **M-12** — render-count test re-baselines.
11. **M-19** (only if requested) — opt-in map-payload migration per event-id.

Apply all the Type A rewrites first, present the Type B batch second. The author shouldn't have to context-switch every five minutes.

## Resuming a partial migration

If the sweep is interrupted mid-flight:

1. **Ask the author** to run a clean compile and paste the output. The compile errors tell you which group you're stuck in. (The skill never runs the compile itself — see [`../SKILL.md`](../SKILL.md) cardinal rule 5.)
2. Look up the symbol or pattern in `references/breaking-changes.md`. Find the rule id.
3. Find the rule's group in this leaf — the groups before it should already be applied; the groups after it haven't started.
4. Apply the rule. Continue with the rest of its group.

The groups are self-contained — finishing a group before starting the next means each compile-and-test cycle is a meaningful checkpoint. Don't half-apply a group.

## Verification is the author's loop

Every "compile + tests" and "boot smoke-test" arrow in the diagram above is **the author running** compile / tests / the booted-app smoke-test, not the skill. The skill prints the exact command for the project's build tool (`shadow-cljs compile <build>` / `clj -M:test` / `npm run test` / etc.) and the smoke-test loop (`re-frame2-pair` MCP / a shadow-cljs nREPL reading live `app-db`), then waits for the author to paste the output. See [`../SKILL.md`](../SKILL.md) cardinal rule 5 — the trust boundary that excludes arbitrary code execution from this skill's loop, even on a long-standing repo.
