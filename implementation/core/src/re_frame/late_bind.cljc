(ns re-frame.late-bind
  "Late-binding hook registry for cross-namespace forward references.

  Some leaf namespaces (re-frame.frame, re-frame.fx, re-frame.cofx,
  re-frame.subs, re-frame.routing, re-frame.router) need to call into
  higher-level namespaces (re-frame.router, re-frame.flows,
  re-frame.schemas, re-frame.subs) but cannot `:require` them without
  introducing a cyclic load order.

  Per rf2-p7va the same mechanism carries cross-artefact references:
  `re-frame.schemas` ships in a separate Maven artefact
  (day8/re-frame2-schemas), so re-frame.core / re-frame.test-support
  MUST NOT statically `:require` it — they look the schemas API up
  through the hook table at call time. When the schemas artefact is
  not on the classpath, the lookup returns nil and the consumer
  no-ops (or, in the case of `rf/reg-app-schema`, throws a clear
  `:rf.error/schemas-artefact-missing` so the misconfiguration is
  surfaced rather than silently absorbed).

  On the JVM we historically used `(resolve 'ns/sym)` to defer the
  lookup to runtime. That works on the JVM because `clojure.core/resolve`
  is a runtime fn — but ClojureScript has no runtime `resolve` (the
  symbol exists only as a compile-time analyzer affordance), so every
  CLJS call site silently no-op'd. That manifested as e.g.
  `(rf/make-frame {:on-create [:foo]})` returning a frame whose app-db
  was never seeded by `:on-create`, and `:fx [[:dispatch [...]]]`
  becoming a no-op.

  This namespace replaces those `resolve` calls with an explicit hook
  registry. The producing namespace registers its callable at load
  time via `set-fn!`; the consuming namespace fetches it via `get-fn`
  at call time. Identical behaviour on JVM and CLJS.

  Per Spec 002 §reg-frame is atomic: `:on-create` runs synchronously
  inside `reg-frame`; `make-frame` is a thin wrapper, so by the time
  `make-frame` returns the frame's app-db reflects the init handler's
  commits. Callers MUST register the `:on-create` event's handler
  BEFORE calling `make-frame` / `reg-frame`. (When the JVM/CLJS host
  loads `re-frame.core`, the canonical re-frame.router namespace is
  loaded — and registers its hooks — before any user code runs.)")

(defonce
  ^{:doc "Map of hook-key → fn. Populated by the producing namespace at
  load time. The set of keys is documented at each call site; v1 keys:

    :router/dispatch!         re-frame.router/dispatch!
    :router/dispatch-sync!    re-frame.router/dispatch-sync!
    :flows/reg-flow            re-frame.flows/reg-flow
    :flows/clear-flow          re-frame.flows/clear-flow
    :flows/reg-flow-fx!        re-frame.flows/reg-flow-fx!
    :flows/clear-flow-fx!      re-frame.flows/clear-flow-fx!
    :flows/run-flows!          re-frame.flows/run-flows!
    :flows/reset-last-inputs!  re-frame.flows/reset-last-inputs!
    :flows/reset-flows!        re-frame.flows/reset-flows!
    :schemas/validate-event!      re-frame.schemas/validate-event!
    :schemas/validate-app-db!     re-frame.schemas/validate-app-db!
    :schemas/validate-cofx!       re-frame.schemas/validate-cofx!
    :schemas/validate-sub-return! re-frame.schemas/validate-sub-return!
    :schemas/frame-schema-entries re-frame.schemas/frame-schema-entries
    :schemas/reg-app-schema       re-frame.schemas/reg-app-schema
    :schemas/app-schema-at        re-frame.schemas/app-schema-at
    :schemas/app-schemas          re-frame.schemas/app-schemas
    :schemas/app-schemas-digest   re-frame.schemas/app-schemas-digest
    :schemas/snapshot-by-frame    re-frame.schemas/snapshot-schemas-by-frame
    :schemas/restore-by-frame!    re-frame.schemas/restore-schemas-by-frame!
    :schemas/clear-by-frame!      re-frame.schemas/clear-schemas-by-frame!
    :schemas/set-schema-validator!         re-frame.schemas/set-schema-validator! ;; rf2-froe — pluggable validator
    :schemas/set-schema-explainer!         re-frame.schemas/set-schema-explainer! ;; rf2-froe — companion explainer
    :schemas/validate-with-registered-fn   re-frame.schemas/validate-with-registered-fn ;; rf2-r2uh boundary seam
    :schemas/explain-with-registered-fn    re-frame.schemas/explain-with-registered-fn  ;; rf2-r2uh boundary seam
    :machines/reg-machine             re-frame.machines/reg-machine* ;; rf2-8bp3 — plain-fn surface
    :machines/create-machine-handler  re-frame.machines/create-machine-handler
    :machines/machine-transition      re-frame.machines/machine-transition
    :machines/machines                re-frame.machines/machines
    :machines/machine-meta            re-frame.machines/machine-meta
    :machines/machine-by-system-id    re-frame.machines/machine-by-system-id
    :machines/reset-counters!         re-frame.machines/reset-counters!
    :machines/spawn-fx                re-frame.machines/spawn-fx
    :machines/destroy-machine-fx      re-frame.machines/destroy-machine-fx
    :routing/reg-route                re-frame.routing/reg-route
    :routing/match-url                re-frame.routing/match-url
    :routing/route-url                re-frame.routing/route-url
    :routing/reset-counters!          re-frame.routing/reset-counters!
    :routing/route-sub-fn             re-frame.routing/route-sub-fn
    :http/install-managed-request-stubs!   re-frame.http-managed/install-managed-request-stubs!
    :http/uninstall-managed-request-stubs! re-frame.http-managed/uninstall-managed-request-stubs!
    :http/with-managed-request-stubs*      re-frame.http-managed/with-managed-request-stubs*
    :http/clear-all-in-flight!             re-frame.http-managed/clear-all-in-flight!
    :http/reg-http-interceptor             re-frame.http-managed/reg-http-interceptor       ;; rf2-6y3q
    :http/clear-http-interceptor           re-frame.http-managed/clear-http-interceptor     ;; rf2-6y3q
    :http/clear-all-http-interceptors!     re-frame.http-managed/clear-all-http-interceptors! ;; rf2-6y3q
    :http/abort-on-actor-destroy           re-frame.http-managed/abort-on-actor-destroy     ;; rf2-wvkn — :invoke cancellation cascade
    :subs/subscribe-value     re-frame.subs/subscribe-value
    :ssr/render-tree-hash        re-frame.ssr/render-tree-hash
    :ssr/render-to-string        re-frame.ssr/render-to-string
    :ssr/reg-error-projector     re-frame.ssr/reg-error-projector
    :ssr/project-error           re-frame.ssr/project-error
    :reagent/set-hiccup-emitter! re-frame.adapter.reagent/set-hiccup-emitter!
    :views/maybe-warn-plain-fn-under-non-default-frame!  re-frame.views/maybe-warn-plain-fn-under-non-default-frame!
    :views/clear-plain-fn-warned-pairs!                  re-frame.views/clear-plain-fn-warned-pairs!
    :adapter/clear-warn-once-caches!  chained no-arg hook — each adapter that holds a `warned-non-dom-roots` defonce contributes a clear-step (re-frame.views/clear-warned-non-dom-roots!, re-frame.adapter.helix/clear-warned-non-dom-roots!, re-frame.adapter.uix/clear-warned-non-dom-roots!). Unlike most `:adapter/...` keys, this hook is NOT routed through the currently-installed adapter — every loaded adapter's cache must clear because test bundles can mount different adapters across tests and each adapter's defonce persists independently. `reset-runtime-fixture` invokes the top of the chain so every registered clear-step runs. Per rf2-4edk.
    :adapter/current-frame    re-frame.views/current-frame                                       ;; rf2-d4sf — Reagent path; class-component (.-context cmp)
                              re-frame.adapter.context/function-component-current-frame          ;;          — UIx / Helix path; function-component _currentValue read
                                                                                                  ;; Published by each adapter ns at load time so subscribe / dispatch consult the React-context tier of the resolution chain. Signature: (fn []) -> frame-id keyword. Per rf2-0d35: each adapter wraps its reader in a routing closure that runs the impl ONLY when (substrate-adapter/current-adapter) is identical to that adapter — otherwise chains to the previously-installed reader, falling back to frame/current-frame at the chain's end. This fixes a regression where, in test bundles that load multiple adapter ns's (e.g. reagent + reagent-slim + uix + helix), the LAST-LOADED adapter's reader silently won — and an app installed via `(rf/init! reagent/adapter)` could end up reading frames via UIx's `function-component-current-frame` path (which reads `_currentValue` directly), breaking the plain-fn-under-non-default-frame-once warning's narrowness contract for plain Reagent fns.
    :adapter/current-component reagent.core/current-component                                    ;; rf2-wbnl — classic-bridge path; stock Reagent's in-flight component
                               reagent2.core/current-component                                   ;;          — slim adapter path; reagent2.core's in-flight component
                                                                                                  ;; Published by each adapter ns at load time so re-frame.views can resolve the current Reagent component without hard-binding to one Reagent build. Signature: (fn []) -> Reagent component or nil. Per rf2-0d35: each adapter ns wraps its reader in a routing closure that runs the impl ONLY when (substrate-adapter/current-adapter) is identical to that adapter — otherwise chains to the previously-installed reader. This fixes a regression where, in test bundles that load multiple adapter ns's, the LAST-LOADED adapter's reader silently won at the hook regardless of which adapter was actually `(rf/init!)`-installed; under that condition warnings like plain-fn-under-non-default-frame would silently drop because the hook was reading the wrong substrate's `*current-component*` (always nil during the OTHER substrate's renders). nil when no adapter is installed; views.cljs treats nil cleanly (skips React-context tier, falls through to :rf/default).
    :adapter/ratom            (fn [v]) -> ratom                                                  ;; rf2-s36l — `re-frame.interop/ratom`; classic-bridge → reagent.core/atom, slim → reagent2.core/atom, uix/helix → reagent.core/atom.
    :adapter/ratom?           (fn [x]) -> boolean                                                ;; rf2-s36l — `re-frame.interop/ratom?`; classic-bridge → (satisfies? reagent.ratom/IReactiveAtom x), slim → (satisfies? reagent2.ratom/IReactiveAtom x), uix/helix → (satisfies? reagent.ratom/IReactiveAtom x).
    :adapter/make-reaction    (fn [f]) -> reaction                                               ;; rf2-s36l — `re-frame.interop/make-reaction`; classic-bridge → reagent.ratom/make-reaction, slim → reagent2.ratom/make-reaction, uix/helix → reagent.ratom/make-reaction.
    :adapter/add-on-dispose!  (fn [a-ratom f]) -> nil                                            ;; rf2-s36l — `re-frame.interop/add-on-dispose!`; classic-bridge → reagent.ratom/add-on-dispose!, slim → reagent2.ratom/add-on-dispose!, uix/helix → reagent.ratom/add-on-dispose! (UIx + Helix derived values reify stock Reagent's IDisposable directly).
    :adapter/dispose!         (fn [a-ratom]) -> nil                                              ;; rf2-s36l — `re-frame.interop/dispose!`; classic-bridge → reagent.ratom/dispose!, slim → reagent2.ratom/dispose!, uix/helix → reagent.ratom/dispose!.
    :adapter/reactive?        (fn []) -> boolean                                                 ;; rf2-s36l — `re-frame.interop/reactive?`; classic-bridge → reagent.ratom/reactive?, slim → reagent2.ratom/reactive?, uix/helix → reagent.ratom/reactive?.
    :adapter/after-render     (fn [f]) -> nil                                                    ;; rf2-s36l — `re-frame.interop/after-render`; classic-bridge → reagent.core/after-render, slim → reagent2.core/after-render, uix/helix → reagent.core/after-render. Each adapter routes through (substrate-adapter/current-adapter) per rf2-0d35 so the installed adapter's substrate is the one whose IDisposable / IReactiveAtom dispatch handles `re-frame.subs` slot-teardown wiring; this is the seam that frees the slim Maven artefact to drop its stock-Reagent dep.
    :adapter/wrap-view        (fn [id metadata user-fn]) -> wrapped-user-fn                      ;; rf2-00li — substrate-side source-coord injection. UIx + Helix register a wrapper that uses `React.cloneElement` to add `data-rf2-source-coord` to the rendered React element's root; the Reagent adapter does the equivalent hiccup-walk inline inside `views/reg-view*` so it does NOT register this hook. `views/reg-view*` consults the hook before its inline walk: when present, the substrate-supplied wrap-view replaces both the user-fn-supplied render and the inline hiccup walk (so a substrate that returns React elements gets React-side injection rather than mis-classified-as-non-DOM hiccup-walk output). Each adapter routes its impl through (substrate-adapter/current-adapter) per rf2-0d35 so the installed adapter's wrap-view is the one that runs. Production-elision (per Spec 009 §Production builds): each adapter's wrap-view body sits inside `(when interop/debug-enabled? ...)` so closure-folds under :advanced + goog.DEBUG=false.
    :epoch/settle!            re-frame.epoch/settle!
    :epoch/discard-buffer!    re-frame.epoch/discard-buffer!
    :epoch/in-flight-buffer   re-frame.epoch/in-flight-buffer
    :epoch/capture-event      re-frame.epoch/capture-event!
    :epoch/epoch-history      re-frame.epoch/epoch-history
    :epoch/restore-epoch      re-frame.epoch/restore-epoch
    :epoch/register-epoch-cb  re-frame.epoch/register-epoch-cb!
    :epoch/remove-epoch-cb    re-frame.epoch/remove-epoch-cb!
    :epoch/configure!         re-frame.epoch/configure!
    :epoch/clear-history!     re-frame.epoch/clear-history!
    :epoch/clear-epoch-cbs!   re-frame.epoch/clear-epoch-cbs!
    :epoch/on-frame-destroyed re-frame.epoch/on-frame-destroyed!"}
  hooks
  (atom {}))

(defn set-fn!
  "Register a fn under hook-key. The producing namespace calls this at
  the bottom of its file; consumers look it up via `get-fn`."
  [hook-key f]
  (swap! hooks assoc hook-key f)
  nil)

(defn get-fn
  "Return the fn registered under hook-key, or nil if no producer has
  registered it yet. Callers MUST handle the nil case (the common
  pattern is `(when-let [f (late-bind/get-fn ...)] (f args))`)."
  [hook-key]
  (get @hooks hook-key))
