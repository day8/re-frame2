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
| `(rf/reg-event-error-handler ...)` | **M-13** | B | Moved to frame-level `:on-error` (per-frame policy) or `register-trace-listener!` (cross-frame observer). Cross-references M-26. |
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
| `(:require [re-frame.test ...])` or `[day8.re-frame.test ...]` | **M-25** | A | Rename to `re-frame.test-support`. `dispatch-sequence` keeps its name; `assert-state` is split into `assert-path-equals` + `assert-db-equals` per **M-62** (so the fn-side shares a name root with the `:rf.assert/*` Story event-family). `run-test-sync` is dropped — hoist body to inline `dispatch-sync` under `make-reset-runtime-fixture` per **M-52**. Drop `day8/re-frame-test` Maven coord — ships in core. |
| `with-trace`, `merge-trace!`, `finish-trace`, `trace-api-version`, `add-post-event-callback`, `remove-post-event-callback`, `purge-event-queue`, `dispatch-and-settle`, `spawn-machine`, `destroy-machine` | **M-26** | A/B | Drift-sweep drops. Each maps to a v2 surface; see the full table inline. `add-post-event-callback` is Type B; the rest mostly A. |
| `reg-app-schema` / `reg-event-schema` / `:spec` metadata | **M-27** | A | Add `day8/re-frame2-schemas` artefact; user code's API surface in `re-frame.core` is unchanged. |
| `reg-machine` / `make-machine-handler` / `sub-machine` | **M-28** | A | Add `day8/re-frame2-machines` artefact; require `re-frame.machines` in any namespace using machine surfaces. |
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
| `:spawn-all` slot in machine specs | M-43 | — | Additive; opt-in via O-15. No user-side action for v1→v2 migration. |
| `:timeout-ms` on `:spawn` / `:spawn-all` | **M-44** | A | Removed. Use the parent state's `:after` timer instead. |
| `:rf.http/managed` requests from spawned actors | M-45 | — | Additive (abort on actor-destroy). No user-side action. |
| `:rf.http/managed` as a child-invokable machine | M-46 | — | Additive; the fx form continues to work. |
| Machine `:tags` slot and `:rf/machine-has-tag?` sub | M-47 | — | Additive. No user-side action. |
| `:type :parallel` machines with map-shaped `:state` | M-48 | — | Additive. No user-side action. |
| Snapshot `:state` reader pattern-matching against the third arm (map) | M-49 | — | Additive; widens to a third arm. Readers that pattern-match exhaustively on `:state` may need to widen the dispatch. |
| `(rf/with-overrides ...)` test-support macro | **M-50** | A | Mechanical rename to `with-fx-overrides`. Body, override-map shape, and composition with `with-frame` are unchanged — only the macro name moves, for symmetry with the `:fx-overrides` opt key and `*fx-overrides*` dynvar. |
| Unary `reg-fx` handler `(fn [args] ...)` | **M-51** | A | Mechanical: `(fn [args] body)` → `(fn [_ args] body)`. The unary back-compat path is cut; the runtime invokes every fx with two args. Async handlers should additionally capture `(rf/dispatcher)` for frame-aware callbacks. |
| `(ts/run-test-sync ...)` / `(re-frame-test/run-test-sync ...)` | **M-52** | A | Removed. Hoist body to inline `dispatch-sync` calls under the standard `make-reset-runtime-fixture` (or `with-fresh-registrar` for ad-hoc bracketing). v2's `dispatch-sync` is already settle-by-default; the macro was pure migration tax. |
| `(rf/dispose-adapter!)` | **M-53** | A | Tear-down verb axis discipline (rf2-cmabc). `dispose-adapter!` → `destroy-adapter!` (lifecycle boundary on the `destroy-` cluster). Per rf2-0zlcd (pre-alpha: no back-compat shims) the old name is **removed** — stale call sites raise unresolved-symbol. Adapter-spec **map key** `:dispose-adapter!` is unchanged. `rf/unsubscribe` is carved out (not renamed — `clear-sub` is already taken by the registrar decrement). |
| `:spec` per-`reg-*` metadata key; `:rf.spec/violation` trace; `:spec/at-boundary` interceptor `:id`; `:spec-id` trace tag | **M-54** | A | Vocabulary unification: framework speaks `:schema` end-to-end after rf2-ieu0i. Closed mechanical rename: `:spec` → `:schema` (metadata-map slot only), `:rf.spec/violation` → `:rf.schema/violation`, `:spec/at-boundary` → `:rf.schema/at-boundary`, `:spec-id` → `:schema-id`. The `re-frame.spec` namespace is NOT renamed. Per rf2-0zlcd, the dual-key read `(or (:schema meta) (:spec meta))` and the `:rf.warning/deprecated-schema-alias` were stripped — `:spec` slots are silently ignored, so partial rewrites are a correctness hazard. |
| `(rf/register-trace-cb! ...)` / `(rf/remove-trace-cb! ...)` / `(rf/clear-trace-cbs! ...)` / `(rf/register-epoch-cb! ...)` / `(rf/remove-epoch-cb! ...)` / `(rf/clear-epoch-cbs! ...)` | **M-55** | A | Listener-registration verb unification (rf2-dcyjm). `register-*-cb!` → `register-*-listener!`, `remove-*-cb!` → `unregister-*-listener!`, `clear-*-cbs!` → `clear-*-listeners!`. Per pre-alpha posture, old names are **removed** — stale call sites raise unresolved-symbol. v2-pre-rename codebases only — v1 had no trace/epoch-listener concept (v1's `add-post-event-callback` lands on the new name via M-26). |
| `:invoke` / `:invoke-all` machine state-node keys; `:invoke-id` / `:invoke-all-id` slots; `:rf/invoke-id` / `:rf/invoke-all-id` / `:rf/invoke-all-child-id` snapshot-internal keys; `:rf.machine.invoke*/*` trace ops; `:rf.error/machine-invoke-*` error keys; `:rf.invoke/*` generated-action namespace | **M-56** | A | Machine vocabulary divergence (rf2-5r4q2). Closed rename table: `:invoke` → `:spawn`, `:invoke-all` → `:spawn-all`, plus all sibling keys / trace ops / error categories. Deliberate xstate divergence — see Spec 005 §Deliberate name divergence. Per pre-alpha posture old names are **removed** — `make-machine-handler` treats them as unknown state-node keys. v2-pre-rename codebases only. |
| `(rf/create-machine-handler ...)` / `:machines/create-machine-handler` late-bind hook key | **M-57** | A | Machine-handler builder verb unification (rf2-g0bbk). `create-machine-handler` → `make-machine-handler` (and the matching late-bind hook key). Aligns the machine-handler builder with `make-frame`'s factory-verb shape. Per pre-alpha posture the old name is **removed** — stale call sites raise unresolved-symbol. v2-pre-rename codebases only — v1 had no machine substrate. |
| `(rf/with-redacted [...])` / `:rf/with-redacted` interceptor `:id` | **M-58** | A | Trace-redaction factory rename (rf2-aas6o). `with-redacted` → `redact-interceptor`. The `with-*` prefix misled (it returns an interceptor value, not a body-taking macro); the new name matches value-shape and aligns with the M-59 interceptor-value family. Per pre-alpha posture the old name is **removed**. v2-pre-rename codebases only — v1 had no trace-redaction surface. |
| `rf/at-boundary` Var (production-side schema validator); `rf/unwrap` Var (payload-shape interceptor) | **M-59** | A | Interceptor-value family suffix (rf2-k367k + rf2-todvi). `at-boundary` → `validate-at-boundary-interceptor` (the `validate-` prefix telegraphs the mode-gated semantic — no-op in dev, validates in prod); `unwrap` → `unwrap-interceptor`. Interceptor `:id` keywords (`:rf.schema/at-boundary`, `:unwrap`) are **unchanged** — only the Var names move. `path` (factory fn) keeps its name per Conventions §Value-vs-fn naming. Per pre-alpha posture old names are **removed**. v2-pre-rename codebases only — v1's `at-boundary` rode the prior M-54 `:spec` → `:schema` sweep. |
| `:rf/url-changed` (event); `:rf.route/url-changed` (trace op) | **M-60** | A | Route event + trace rename (rf2-ixezs). `:rf/url-changed` → `:rf.route/transitioned` (event moves into the `:rf.route/*` reserved namespace alongside its siblings); `:rf.route/url-changed` → `:rf.route/fragment-changed` (trace op — the new name is more accurate; it only fires on fragment-only transitions). Per pre-alpha posture old names are **removed** — stale handler registrations sit unfired; stale trace-filter `=` checks silently mismatch. v2-pre-rename codebases only — v1 had no routing substrate. |
| `re-frame.schemas/validate-app-db!` / `re-frame.schemas/validate-sub-return!` Var calls; `:schemas/validate-app-db!` / `:schemas/validate-sub-return!` late-bind hook keys | **M-61** | A | Validator family rename (rf2-s2jgz). `validate-app-db!` → `validate-app-schema!`; `validate-sub-return!` → `validate-sub!`. Aligns the validator family on the **kind axis** (`validate-event!` / `validate-cofx!` / `validate-fx!` / `validate-sub!` / `validate-app-schema!`); the two pre-rename names were off-axis. Reaches users only when they call validators directly or publish custom late-bind hooks — standard registrations through `reg-app-schema` are untouched. Per pre-alpha posture old names are **removed**. v2-pre-rename codebases only — v1 had no schema validator surface. |
| `:retry :on` set on `:rf.http/managed` containing `:rf.http/aborted` / `:rf.http/decode-failure` / `:rf.http/accept-failure` (or any non-`:rf.http/*` keyword) | **M-31b** | A | Per rf2-apwkm, `:retry :on` tightened to the closed set `#{:rf.http/transport :rf.http/cors :rf.http/timeout :rf.http/http-4xx :rf.http/http-5xx}`. Out-of-set keywords raise `:rf.error/http-bad-retry-on` at fx-call time. v1 had no `:rf.http/managed` fx; v2-pre-rename only. |
| `(rf/reg-http-interceptor {:id ... :before ... :frame ...})` (single-map signature) | **M-63** | A | Per rf2-eyjbn, reshape to positional id + opts kwarg + positional handler: `(rf/reg-http-interceptor id opts? before)` matching `reg-flow`'s precedent. v1 had no `reg-http-interceptor` surface; v2-pre-rename only. `:rf.fx/reg-http-interceptor` fx args remain map-shaped (EDN constraint). |
| `(ts/reset-runtime-fixture-factory ...)` | **M-64** | A | Test-fixture builder verb unification (rf2-v779c). `reset-runtime-fixture-factory` → `make-reset-runtime-fixture`. Aligns the per-test fixture builder with the `make-*` factory-verb convention (sibling of M-57's `make-machine-handler`). Per pre-alpha posture the old name is **removed** — stale call sites raise unresolved-symbol. v2-pre-rename codebases only — v1 had no equivalent builder. |
| `(ts/assert-state ...)` (path-form or full-db-form) | **M-62** | A | Test assertion fn-family alignment (rf2-8j9m6). `assert-state` is split into `assert-path-equals` (vector path form) + `assert-db-equals` (full-db form). The fn-family now shares the `path-equals` root with the `:rf.assert/*` event-family used in Story `:play` blocks. Per pre-alpha posture the old name is **removed** — stale call sites raise unresolved-symbol. v1's `re-frame.test/assert-state` (path form only) lands on `assert-path-equals`. |

The M-numbered slots that are "informational only" / "additive" / "—" still appear so an agent walking the rule list doesn't get confused by gaps. There is no user-side action required for those rules.

## Opt-in modernisations (O-rules) by trigger surface

The author **must** ask for these. They are never auto-applied as part of a routine v1→v2 migration.

| Trigger / motivation | Rule | One-line summary |
|---|---|---|
| Register `:doc` / `:schema` / `:tags` metadata on existing events / subs / fx | **O-1** | Replace plain `[interceptors]` slot with `{:doc ... :schema ... :tags ...}` metadata map + positional `[interceptors]` afterward. (`:schema` is the canonical name post-rf2-ieu0i; per rf2-0zlcd the legacy `:spec` key is no longer accepted — see M-54.) |
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
| Replace hand-rolled spawn-and-join with `:spawn-all` | **O-15** | Machine modernisation. The hand-rolled form continues to work. |

---

## Type A vs Type B — at a glance

**Type A — apply automatically.** The pattern is unambiguous, the rewrite is structural, the result is observably identical (or strictly better). M-0, M-1, M-4, M-5 (direct half), M-6, M-7, M-8, M-9, M-16, M-17 (single-frame half), M-20, M-21 (`debug` / `trim-v` half), M-22, M-23 (the find-and-replace half), M-24, M-25, M-26 (most), M-27 through M-40 (mostly dep-only adds), M-31b, M-42, M-50, M-51, M-52, M-53, M-54, M-55, M-56, M-57, M-58, M-59, M-60, M-61 (validator rename), M-62, M-63 (`reg-http-interceptor` reshape), M-64 (`make-reset-runtime-fixture`).

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
