# breaking-changes

A one-page index keyed to v1 trigger surfaces. The author asks *"is `X` covered by a rule?"* — you grep here, find the rule id, then load that rule's full text from [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md).

**This leaf does not replace [`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md).** It points at it. The authoritative `What to look for` / `What to do` / `Why` per rule lives in MIGRATION.md. Every entry here gives just enough to identify the rule.

## Contents

- Required rules (M-N) by trigger surface
- Opt-in modernisations (O-N) by trigger surface
- Type A vs Type B at a glance
- What stays the same (preserved v1 surfaces)

---

## Required (M-rules) by trigger surface

| Trigger in v1 code | Rule | Type | One-line summary |
|---|---|---|---|
| `re-frame/re-frame` Maven coord | **M-0** | A | Swap to `day8/re-frame2` + a substrate adapter at the same VERSION. |
| `(:require [re-frame.db ...])`, `re-frame.router`, `re-frame.subs`, `re-frame.events`, `re-frame.registrar` | **M-1** | A | Private namespaces are off-contract. `@re-frame.db/app-db` → `(rf/get-frame-db :rf/default)`; per-namespace replacements documented inline. |
| Code asserting subscription return is a Reagent reaction type | M-2 | — | Demoted; see O-6 (opt-in future-proofing). |
| `:dispatch` inside a handler relied on async behaviour / intermediate render | **M-3** | B | re-frame2 drains run-to-completion. Animation chains, queue-peek tests, intermediate-render dependencies break. Each call site flagged. |
| `(rf/dispatch-with ...)` / `(rf/dispatch-sync-with ...)` (master users only) | **M-4** | A | Use two-arg `dispatch` / `dispatch-sync` with the opts map. `:fx-overrides` carries the stub-fn slot. |
| `(apply rf/reg-event-db ...)` or `(def my-reg rf/reg-event-db)` Var-aliasing | **M-5** | A/B | `reg-*` are macros; can't be `apply`d or Var-aliased. Direct calls unaffected. Higher-order patterns need rewriting. |
| Long synchronous dispatch chains; runtime error `:reason :drain-depth-exceeded` | **M-6** | A | Bump `:drain-depth` on the frame (default 100) or refactor to `:dispatch-later`. |
| `reg-fx` / `reg-cofx` that touches browser globals (`js/window`, `js/localStorage`) | **M-7** | A | Default `:platforms` is universal. Add `:platforms #{:client}` explicitly for fx that can't run JVM-side / SSR. |
| Top-level `:dispatch`, `:dispatch-later`, `:dispatch-n`, `:http`, user-fx-id keys in effect maps | **M-8** | A | Fold every non-`:db` key into `:fx`. The effect map is `{:db ... :fx [[fx-id args] ...]}` only. |
| `rf/dispatch-sync` called *inside* an event handler body | **M-9** | A | Move to `:fx [[:dispatch event]]`. Promote `reg-event-db` to `reg-event-fx` if needed. |
| User registrations under `:rf/*`, `:rf.machine/*`, `:rf.route/*`, `:rf.nav/*` ... | **M-10** | B | Reserved namespace collision. Rename to the project's own feature prefix unless deliberately overriding a documented extension point. |
| Plain Reagent fns referenced inside `(rf/frame-provider {:frame <non-default>} ...)` | **M-11** | B | Plain fns silently route subscribe/dispatch to `:rf/default`. Convert to `reg-view` *or* use `(rf/dispatcher)` / `(rf/subscriber)` *or* accept the default-frame routing. |
| Tests asserting on exact render counts | **M-12** | B | The new sub-cache changes counts (usually fewer renders). Re-baseline test expectations. |
| `(rf/reg-event-error-handler ...)` | **M-13** | B | Moved to frame-level `:on-error` (per-frame policy) or `register-trace-cb!` (cross-frame observer). Cross-references M-26. |
| Adopting Spec 012 routing without `:rf.route/not-found` registered | **M-14** | B | Add `(rf/reg-route :rf.route/not-found ...)` + a not-found view. N/A if keeping a third-party router. |
| `(reset! re-frame.db/app-db {...})` at top level (seeding) | **M-15** | B | Seed via `(rf/reg-frame :rf/default {:on-create [[:app/seed initial]]})`. Pairs with M-1's private-ns rewrite. |
| `^:flush-dom` event-vector metadata | **M-16** | A | Replace with `:dispatch-later {:ms 0 :dispatch <event-vec>}`. |
| `(rf/reg-global-interceptor ...)` / `(rf/clear-global-interceptor ...)` | **M-17** | A/B | Single-frame: move to default-frame `:interceptors`. Multi-frame: ask (each-frame / trace-listener / default-frame-only). |
| `(rf/reg-sub-raw ...)` | **M-18** | B | Four rewrite paths depending on body: read-only-app-db → `reg-sub`; non-app-db source → fx-driven; lifecycle-managing → state machine; side-effecting → anti-pattern, move side effect to handler. |
| Multi-positional event vectors `[:user/login email password]` | **M-19** | B | Opt-in. Map-payload form is canonical (`[:user/login {:email ... :password ...}]`). Multi-positional continues to work; linter nudges. |
| `:re-frame/*`, `:machine/*`, `:route/*`, `:nav/*`, `:registry/*` framework keywords | **M-20** | A | Closed mechanical rename table to `:rf/*` / `:rf.machine/*` / `:rf.route/*` / `:rf.nav/*` / `:rf.registry/*`. User-defined `:route/<name>` ids preserved or rewritten to feature prefix per codebase convention. |
| `rf/debug`, `rf/trim-v`, `rf/on-changes`, `rf/enrich`, `rf/after` interceptors | **M-21** | A/B | `debug` / `trim-v` mechanical drop (the trace surface and M-19 cover them). `on-changes` → flow. `enrich` / `after` → flow OR schema OR `->interceptor` OR registered fx — depends on the body. |
| `(rf/reg-view :ns/id render-fn)` keyword-shape calls | **M-22** | A | Rewrite to defn-shape: `(rf/reg-view view-name [args] body)`. Drop `(rf/dispatcher)` / `(rf/subscriber)` captures (auto-injected). Use `^{:rf/id ...}` metadata for explicit ids; use `reg-view*` for computed ids / non-fn bodies. |
| `(:require [re-frame.alpha ...])` — `reg`, `sub`, `reg-sub-lifecycle`, `:re-frame/lifecycle` annotations | **M-23** | A/B | Mechanical: `reg :event-fx` → `reg-event-fx`; query-map `sub` → vector `subscribe`. Drop lifecycle policy annotations (file follow-up bead if a real edge case). |
| `(rf/h ...)` hiccup walker | **M-24** | A | Var-ref form (`[counter "..."]`) for the common case; `(rf/view :id)` for late-binding; drop the wrapper for HTML-only hiccup. |
| `(:require [re-frame.test ...])` or `[day8.re-frame.test ...]` | **M-25** | A | Rename to `re-frame.test-support` (helpers `dispatch-sequence` / `assert-state` keep their names). `run-test-sync` is dropped — hoist body to inline `dispatch-sync` under `reset-runtime-fixture` per **M-52**. Drop `day8/re-frame-test` Maven coord — ships in core. |
| `with-trace`, `merge-trace!`, `finish-trace`, `trace-api-version`, `add-post-event-callback`, `remove-post-event-callback`, `purge-event-queue`, `dispatch-and-settle`, `spawn-machine`, `destroy-machine` | **M-26** | A/B | Drift-sweep drops. Each maps to a v2 surface; see the full table inline. `add-post-event-callback` is Type B; the rest mostly A. |
| `reg-app-schema` / `reg-event-schema` / `:spec` metadata | **M-27** | A | Add `day8/re-frame2-schemas` artefact; user code's API surface in `re-frame.core` is unchanged. |
| `reg-machine` / `create-machine-handler` / `sub-machine` | **M-28** | A | Add `day8/re-frame2-machines` artefact; require `re-frame.machines` in any namespace using machine surfaces. |
| `reg-route` / `:rf.route/*` events / `:rf/route` subs | **M-29** | A | Add `day8/re-frame2-routing` artefact; require `re-frame.routing` in any namespace using routing surfaces. |
| `reg-flow` / `:rf.fx/reg-flow` (including post-M-21 `on-changes` rewrites) | **M-30** | A | Add `day8/re-frame2-flows` artefact; require `re-frame.flows` in any namespace using flow surfaces. |
| `[:rf.http/managed ...]` fx, child-invokable HTTP machine | **M-31** | A | Add `day8/re-frame2-http` artefact; require `re-frame.http` in any namespace using managed HTTP. |
| `render-to-string` (server-side SSR) | **M-32** | A | Add `day8/re-frame2-ssr` artefact; require `re-frame.ssr` server-side. |
| `epoch-history`, `restore-epoch` | **M-33** | A | Add `day8/re-frame2-epoch` artefact; require `re-frame.epoch`. Mostly pulled transitively via `re-frame2-pair`. |
| Spawned-actor tracking that reads `[:data :pending]` | M-34 | A | Move to runtime-owned `[:rf/spawned ...]` path. Detail in MIGRATION.md. |
| `:spawn` / `:destroy-machine` fx ids | M-35 | A | Rename to `:rf.machine/spawn` / `:rf.machine/destroy`. |
| Drift sweep: `:rf/route` cross-spec — no user-side action | M-36 | — | Reconciled; informational only. |
| Adapter moves to `implementation/adapters/<name>/` — no user-side action | M-37 | — | Informational only. |
| `(:require [re-frame.substrate.<name>])` | **M-38** | A | Rename to `re-frame.adapter.<name>`. |
| `reg-http-interceptor` / `clear-http-interceptor` | M-39 | A | Additive request-side middleware; rename per inline table. |
| `(rf/init!)` with no args | **M-40** | A | Now requires an explicit adapter spec map: `(rf/init! reagent-adapter/adapter)` (or the chosen adapter). |
| `subscribe` / `dispatch` inside a React context boundary | M-41 | — | Additive: consults React-context tier. No user-side action. |
| React-19-removed Reagent surfaces (specific list in MIGRATION.md) | M-42 | A | Ship as throw-on-call shims under `day8/reagent-slim`. Mechanical: the shims throw at runtime if used; rewrite per the call-site. |
| `:invoke-all` slot in machine specs | M-43 | — | Additive; opt-in via O-15. No user-side action for v1→v2 migration. |
| `:timeout-ms` on `:invoke` / `:invoke-all` | **M-44** | A | Removed. Use the parent state's `:after` timer instead. |
| `:rf.http/managed` requests from spawned actors | M-45 | — | Additive (abort on actor-destroy). No user-side action. |
| `:rf.http/managed` as a child-invokable machine | M-46 | — | Additive; the fx form continues to work. |
| Machine `:tags` slot and `:rf/machine-has-tag?` sub | M-47 | — | Additive. No user-side action. |
| `:type :parallel` machines with map-shaped `:state` | M-48 | — | Additive. No user-side action. |
| Snapshot `:state` reader pattern-matching against the third arm (map) | M-49 | — | Additive; widens to a third arm. Readers that pattern-match exhaustively on `:state` may need to widen the dispatch. |
| `(rf/with-overrides ...)` test-support macro | **M-50** | A | Mechanical rename to `with-fx-overrides`. Body, override-map shape, and composition with `with-frame` are unchanged — only the macro name moves, for symmetry with the `:fx-overrides` opt key and `*fx-overrides*` dynvar. |
| Unary `reg-fx` handler `(fn [args] ...)` | **M-51** | A | Mechanical: `(fn [args] body)` → `(fn [_ args] body)`. The unary back-compat path is cut; the runtime invokes every fx with two args. Async handlers should additionally capture `(rf/dispatcher)` for frame-aware callbacks. |
| `(ts/run-test-sync ...)` / `(re-frame-test/run-test-sync ...)` | **M-52** | A | Removed. Hoist body to inline `dispatch-sync` calls under the standard `reset-runtime-fixture` (or `with-fresh-registrar` for ad-hoc bracketing). v2's `dispatch-sync` is already settle-by-default; the macro was pure migration tax. |
| `(rf/dispose-adapter!)` | **M-53** | A | Tear-down verb axis discipline (rf2-cmabc). `dispose-adapter!` → `destroy-adapter!` (lifecycle boundary on the `destroy-` cluster). Old name retained as deprecated alias for one cycle. Adapter-spec **map key** `:dispose-adapter!` is unchanged. `rf/unsubscribe` is carved out (not renamed — `clear-sub` is already taken by the registrar decrement). |

The M-numbered slots that are "informational only" / "additive" / "—" still appear so an agent walking the rule list doesn't get confused by gaps. There is no user-side action required for those rules.

## Opt-in modernisations (O-rules) by trigger surface

The author **must** ask for these. They are never auto-applied as part of a routine v1→v2 migration.

| Trigger / motivation | Rule | One-line summary |
|---|---|---|
| Register `:doc` / `:spec` / `:tags` metadata on existing events / subs / fx | **O-1** | Replace plain `[interceptors]` slot with `{:doc ... :spec ... :tags ...}` metadata map + positional `[interceptors]` afterward. |
| Adopt `reg-view` for plain Reagent fns | **O-2** | Drop `defn`; replace with `(rf/reg-view view-name [args] body)`. Frame-aware. |
| Add Malli schemas on `app-db` paths and on event payloads | **O-3** | Pull `day8/re-frame2-schemas`; register via `reg-app-schema`. Boundary-only — don't schema-fence every internal key. |
| Lift a namespaced sub-tree of `app-db` into its own frame | **O-4** | Use `reg-frame :feature-name`; existing `:rf/default`-targeted events stay where they are. Multi-instance enabler. |
| Update fx handlers to binary form `(fn [m _] ...)` | O-5 | Promoted to M-51; now a required mechanical rewrite, not opt-in. |
| Future-proof code that introspected Reagent-reaction subscription return types | **O-6** | Drop type checks; use `(rf/subscribe-once [...])` if you need the value outside a reactive context. |
| `:dispatch-n` to `:fx` | O-7 | Absorbed into M-8 (no longer opt-in). |
| Adopt the Spec 012 routing surface | **O-8** | If you have a third-party router (reitit/secretary/bidi), this is the move-to-`reg-route` rewrite. Pairs with M-14. |
| Adopt `:system-id` named-machine addressing | **O-9** | Spec 005 addressing. Useful for multi-instance machines. |
| Orient against Spec 000's `C-000.NN` contract clauses (implementor-facing) | O-10 | No user-code rewrite. |
| Switch generated-machine-spec callers from `reg-machine` to `reg-machine*` | O-11 | Source-coord stamping. Codegen pipelines only. |
| Introspect static sub-graph via `(rf/sub-topology)` | O-12 | Replaces ad-hoc walks of private sub state. |
| Move from Reagent to UIx via `day8/re-frame2-uix` | **O-13** | Substrate swap. Not part of v1→v2; never auto-applied. |
| Move from Reagent to Helix via `day8/re-frame2-helix` | **O-14** | Substrate swap. Not part of v1→v2; never auto-applied. |
| Replace hand-rolled spawn-and-join with `:invoke-all` | **O-15** | Machine modernisation. The hand-rolled form continues to work. |

---

## Type A vs Type B — at a glance

**Type A — apply automatically.** The pattern is unambiguous, the rewrite is structural, the result is observably identical (or strictly better). M-0, M-1, M-4, M-5 (direct half), M-6, M-7, M-8, M-9, M-16, M-17 (single-frame half), M-20, M-21 (`debug` / `trim-v` half), M-22, M-23 (the find-and-replace half), M-24, M-25, M-26 (most), M-27 through M-40 (mostly dep-only adds), M-42, M-50, M-51, M-52, M-53.

**Type B — ask before applying.** The rewrite depends on intent the agent cannot recover statically. M-3, M-5 (Var-aliasing half), M-10, M-11, M-12, M-13, M-14, M-15, M-17 (multi-frame half), M-18, M-19, M-21 (`on-changes` / `enrich` / `after` half), M-23 (lifecycle policy half), M-26 (`add-post-event-callback` / error-handler half).

If a rule is hybrid (A for one shape, B for another), the type column above lists `A/B` and the rule's text in MIGRATION.md spells out which half is which.

## Devtools (not an M-rule, but author-visible)

| Trigger in v1 dev deps | Successor | Where |
|---|---|---|
| `day8.re-frame/re-frame-10x` Maven coord; `day8.re-frame-10x.preload` `:preloads` entry | **Causa** (`day8/re-frame2-causa`) | [`causa-replaces-10x.md`](causa-replaces-10x.md) |

Causa is a from-scratch reimplementation against re-frame2's trace bus and epoch-history surfaces, not a port of 10x. It is **dev-only by construction** and elides cleanly under `:advanced` + `goog.DEBUG=false`. The swap is not an M-rule (no application code triggers it) but is the natural companion to M-0 — see [`causa-replaces-10x.md`](causa-replaces-10x.md) for the drop / add / host / keybinding shape and the 10x→Causa parity matrix.

## What stays the same (do not change)

[`MIGRATION.md`](../../../migration/from-re-frame-v1/README.md) has a fully-enumerated *"What stays the same"* section near the end of Part 1. The headline non-changes:

- `reg-event-db` / `reg-event-fx` / `reg-event-ctx` / `reg-sub` / `reg-fx` / `reg-cofx` direct invocation — same names, same call shapes.
- Handler signatures `(fn [db [_ args]] ...)` and `(fn [{:keys [db]} event] ...)`.
- `dispatch` / `dispatch-sync` (optional second `opts` arg is additive).
- `subscribe`; `@(subscribe [...])` deref-to-read pattern.
- `:<-` chained subs and `reg-sub` sugar variants.
- `path`, `unwrap`, `inject-cofx` interceptors; `->interceptor` primitive.
- The `:fx` slot in effect maps (the inner shape `[[fx-id args] ...]`).
- `reg-fx` / `reg-cofx` without `:platforms` (defaults to universal).
- `reg-flow` / `flow<-` / `clear-flow` (in `re-frame.core`; underlying impl ships under `re-frame2-flows`).
- `re-frame.std-interceptors` namespace.
- JVM interop layer (`re-frame.interop`).
- Hot-reload semantics on the default frame.

If a usage doesn't match any M-rule in this table AND isn't on the preserved list, **flag it for human review** rather than guessing. The MIGRATION.md preserved list is non-exhaustive; the M-rule list is the authoritative list of breakages.
