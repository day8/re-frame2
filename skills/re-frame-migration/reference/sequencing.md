# sequencing

The recommended order to walk the migration rules. Restated so a partial migration can resume cleanly without re-reading the full [`MIGRATION.md`](../../../spec/MIGRATION.md) ordering.

## Top-level shape

```
Phase 2 — Bump (M-0)
   │
   └──> compile + tests
            │
            ├── all green ────> Phase 6 (report). Done.
            │
            └── failures ────> Phase 3 (sweep) ───> compile + tests ───> Phase 6
```

[`MIGRATION.md`](../../../spec/MIGRATION.md) Part 2 §"Your task" makes the headline expectation explicit: *most codebases require no changes at all beyond M-0*. Verify that against this project before doing anything else.

## When failures land — the sweep order

The M-rule numbering in [`MIGRATION.md`](../../../spec/MIGRATION.md) *is* the sweep order. Walk them low-to-high. Later rules sometimes depend on earlier ones being resolved (M-1 surfaces private-namespace requires; M-15's seeding rewrite assumes the M-1 fix has run; M-21's `on-changes` rewrite assumes the flows artefact M-30 has been added).

### Group 1 — Coord and private namespaces (foundation)

| Order | Rule | Why first |
|---|---|---|
| 1 | **M-0** | Already done in Phase 2. The whole migration runs against the new classpath. |
| 2 | **M-1** | Every other rule assumes `re-frame.core` is the only allowed re-frame namespace. Private-namespace requires would cause spurious compile errors elsewhere. |
| 3 | **M-38** | Substrate-adapter ns rename (`re-frame.substrate.<name>` → `re-frame.adapter.<name>`). Codebases that explicitly required the substrate (rare; usually only set up code) hit this. |
| 4 | **M-40** | `(rf/init!)` requires the adapter spec map. Boot path doesn't compile without it. |

### Group 2 — Macro / value-form / shape rewrites (compile-level)

| Order | Rule | Why here |
|---|---|---|
| 5 | **M-5** | `reg-*` are macros now; higher-order use breaks at compile time. Surface before behaviour-shape rules. |
| 6 | **M-22** | `reg-view` is a defn-shape macro; keyword-shape calls fail to expand. Compile-level. |
| 7 | **M-23** | `re-frame.alpha` namespace removed. Compile-level (require fails). |
| 8 | **M-24** | `rf/h` removed. Compile-level (symbol unresolved). |
| 9 | **M-25** | `re-frame.test` renamed to `re-frame.test-support`. Compile-level. |
| 10 | **M-26** | Drift-sweep drops — most are symbol-not-found at compile time. The Type B `add-post-event-callback` half waits for behavioural review. |

### Group 3 — Effect map / dispatch shape (compile-or-warning)

| Order | Rule | Why here |
|---|---|---|
| 11 | **M-4** | Master's `dispatch-with` / `dispatch-sync-with` removed. Most codebases unaffected. |
| 12 | **M-8** | Fold top-level `:dispatch` / `:dispatch-later` / `:dispatch-n` / user-fx-id keys into `:fx`. High-impact mechanical rewrite. |
| 13 | **M-9** | `dispatch-sync` inside handlers → `:fx [[:dispatch ...]]`. |
| 14 | **M-16** | `^:flush-dom` metadata → `:dispatch-later {:ms 0}`. |

### Group 4 — Reserved-namespace renames (mechanical)

| Order | Rule | Why here |
|---|---|---|
| 15 | **M-20** | Framework keyword consolidation. Closed mechanical rename table. Apply before M-10 so M-10's collision audit doesn't false-positive on legacy framework ids. |
| 16 | **M-10** | Reserved-namespace collision audit. Type B; surfaces user registrations under `:rf/*` for human review. |
| 17 | **M-35** | Actor-lifecycle fx-id rename (`:spawn` → `:rf.machine/spawn`). |
| 18 | **M-34** | Spawn-id path rename (`[:data :pending]` → `[:rf/spawned ...]`). |

### Group 5 — Interceptors and registration metadata

| Order | Rule | Why here |
|---|---|---|
| 19 | **M-21** | Drop `debug` / `trim-v` (mechanical). Flag `on-changes` / `enrich` / `after` (Type B). |
| 20 | **M-17** | `reg-global-interceptor` / `clear-global-interceptor` removed. Single-frame: mechanical. Multi-frame: ask. |
| 21 | **M-7** | `reg-fx` / `reg-cofx` `:platforms` default; add `:platforms #{:client}` for browser-only fx. |

### Group 6 — Run-to-completion / cache / counts (behaviour)

| Order | Rule | Why here |
|---|---|---|
| 22 | **M-3** | Dispatch ordering — run-to-completion drain. Type B; flag every `:dispatch` inside a handler and every test asserting queue / intermediate-render shape. |
| 23 | **M-6** | Drain-depth limit. Most codebases unaffected; runtime-error-triggered. |
| 24 | **M-12** | Sub-cache invalidation changes render counts. Type B; flag render-count assertions. |
| 25 | **M-44** | `:timeout-ms` removed from `:invoke` / `:invoke-all`. Use parent state's `:after` timer. |

### Group 7 — Private-state / lifecycle / handler dropouts

| Order | Rule | Why here |
|---|---|---|
| 26 | **M-15** | App-db seeding via `:on-create` (pairs with M-1's private-ns rewrite). |
| 27 | **M-11** | Plain Reagent fns under non-default frames. Type B. Only surfaces in multi-frame apps. |
| 28 | **M-13** | `reg-event-error-handler` removed. Frame-level `:on-error` or trace listener. Type B. |
| 29 | **M-18** | `reg-sub-raw` removed. Four rewrite paths (read-only-app-db, fx-driven, machine, anti-pattern). Type B. |
| 30 | **M-42** | React-19-removed Reagent surfaces (throw-on-call shims). Mechanical rewrite per call site. |

### Group 8 — Per-feature artefact splits (dep-only adds; pair with the feature-trigger rules)

| Order | Rule | Pairs with |
|---|---|---|
| 31 | **M-27** | Triggered by `reg-app-schema` / `:spec` keys / `reg-event-schema`. Add `day8/re-frame2-schemas`. |
| 32 | **M-28** | Triggered by `reg-machine` / `sub-machine`. Add `day8/re-frame2-machines`. |
| 33 | **M-29** | Triggered by `reg-route` / `:rf.route/*` events. Add `day8/re-frame2-routing`. Pairs with M-14 (the `not-found` requirement). |
| 34 | **M-30** | Triggered by `reg-flow` or by M-21's `on-changes` rewrite. Add `day8/re-frame2-flows`. |
| 35 | **M-31** | Triggered by `:rf.http/managed` fx. Add `day8/re-frame2-http`. |
| 36 | **M-32** | Triggered by `render-to-string` (SSR). Add `day8/re-frame2-ssr`. |
| 37 | **M-33** | Triggered by `epoch-history` / `restore-epoch`. Add `day8/re-frame2-epoch`. |
| 38 | **M-39** | If the codebase uses `reg-http-interceptor` / `clear-http-interceptor`. Pairs with M-31. |

### Group 9 — Conditional / opt-trigger rules

| Order | Rule | Trigger |
|---|---|---|
| 39 | **M-14** | Only if the user is adopting Spec 012's routing surface (paired with M-29). Otherwise N/A. |
| 40 | **M-19** | Opt-in shift to map-payload event vectors. Off by default; only run if the user has explicitly asked to modernise. |

## When to pause for human review

The Type B rules each have a documented question. Group them at the end of the sweep and present them in one batch — the author makes all the decisions in a single sitting rather than getting interrupted N times during the migration.

Order of presentation within the batch (most-blocking first):

1. **M-3** — run-to-completion impact: any animation timing, queue-peek tests, intermediate-render dependencies.
2. **M-18** — `reg-sub-raw` rewrites: each call site needs the user's read on what the raw body is doing.
3. **M-11** — plain-Reagent fns under non-default frames: each component-frame pair.
4. **M-17** — multi-frame `reg-global-interceptor`: each-frame vs trace-listener vs default-only.
5. **M-21** — `on-changes` / `enrich` / `after`: flow / schema / `->interceptor` / fx routing.
6. **M-10** — reserved-namespace collisions.
7. **M-5** Var-aliasing — refactor to direct invocation.
8. **M-13** — `reg-event-error-handler` policy.
9. **M-12** — render-count test re-baselines.
10. **M-19** (only if requested) — opt-in map-payload migration per event-id.

Apply all the Type A rewrites first, present the Type B batch second. The author shouldn't have to context-switch every five minutes.

## Resuming a partial migration

If the sweep is interrupted mid-flight:

1. **Ask the author** to run a clean compile and paste the output. The compile errors tell you which group you're stuck in. (The skill never runs the compile itself — see [`../SKILL.md`](../SKILL.md) cardinal rule 10.)
2. Look up the symbol or pattern in `reference/breaking-changes.md`. Find the rule id.
3. Find the rule's group in this leaf — the groups before it should already be applied; the groups after it haven't started.
4. Apply the rule. Continue with the rest of its group.

The groups are self-contained — finishing a group before starting the next means each compile-and-test cycle is a meaningful checkpoint. Don't half-apply a group.

## Verification is the author's loop

Every "compile + tests" arrow in the diagram above is **the author running compile + tests**, not the skill. The skill prints the exact command for the project's build tool (`shadow-cljs compile <build>` / `clj -M:test` / `npm run test` / etc.) and waits for the author to paste the output. See [`../SKILL.md`](../SKILL.md) cardinal rule 10 — the trust boundary that excludes arbitrary code execution from this skill's loop, even on a long-standing repo.
