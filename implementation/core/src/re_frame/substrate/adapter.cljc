(ns re-frame.substrate.adapter
  "The reactive substrate adapter contract. Per Spec 006.

  The runtime is substrate-agnostic; every host-specific reactivity concern
  goes through these 9 functions. The CLJS reference ships four adapter
  artefacts: Reagent (browser), UIx (browser), Helix (browser), and
  plain-atom (JVM / SSR / headless).

  Required functions (6):
    make-state-container, read-container, replace-container!,
    make-derived-value, render, render-to-string

  Optional (2):
    subscribe-container, register-context-provider

  Lifecycle (1):
    dispose-adapter!

  An adapter is a Clojure map with these keys plus a `:kind` discriminator
  keyword. Canonical framework members live under the reserved
  `:rf.adapter/*` namespace (`:rf.adapter/reagent` / `:rf.adapter/reagent-slim`
  / `:rf.adapter/uix` / `:rf.adapter/helix` / `:rf.adapter/plain-atom` /
  `:rf.adapter/ssr`); user-supplied adapters report as `:custom` when they
  don't pick a canonical kind. It is installed into the process via
  install-adapter! and introspected via current-adapter (keyword) and
  current-adapter-spec (the full map).

  There is no default-adapter registry and no ns-load side-effect.
  Each adapter ns (re-frame.adapter.reagent / .uix / .helix,
  re-frame.substrate.plain-atom, re-frame.ssr) exports an `adapter`
  var (the spec map); consumers require the ns and pass the var
  explicitly via `(rf/init! reagent/adapter)`. Explicit > implicit
  at the call site, and an app requiring only the adapter it needs
  ships only that adapter's code."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- adapter installation -------------------------------------------------
;;
;; Two cells, not one. `installed-adapter` holds the live spec
;; map when an adapter is installed and `nil` otherwise. `disposed?` is a
;; boolean breadcrumb left behind by the most recent
;; `(dispose-adapter!)` so subsequent runtime calls can distinguish
;;
;;   (a) `no adapter installed yet` — fresh process, init! never ran;
;;       the right diagnosis is `:rf.error/no-adapter-installed`;
;;   (b) `adapter was installed and then torn down` — usually a test
;;       fixture or a hot-reload swap that hasn't reinstalled yet;
;;       the right diagnosis is `:rf.error/adapter-disposed`.
;;
;; Both states leave the install slot empty so a fresh adapter can
;; install via `install-adapter!`; the breadcrumb clears on the next
;; successful install.

(defonce ^:private installed-adapter (atom nil))
(defonce ^:private disposed?         (atom false))

(defn install-adapter!
  "Install the adapter for this process. Once. A second call without an
  intervening dispose-adapter! raises :rf.error/adapter-already-installed
  (per Spec 006 §Single adapter per process). Clears the disposed
  breadcrumb so subsequent delegation calls see a fresh adapter rather
  than `:rf.error/adapter-disposed`."
  [adapter]
  (when @installed-adapter
    (throw (ex-info ":rf.error/adapter-already-installed"
                    {:installed @installed-adapter :attempted adapter})))
  (reset! installed-adapter adapter)
  (reset! disposed? false)
  adapter)

(defn current-adapter
  "Return the discriminator keyword identifying the installed adapter, or
  nil if none. Per Spec 006 §Adapter introspection: one of
  `:rf.adapter/reagent`, `:rf.adapter/reagent-slim`, `:rf.adapter/uix`,
  `:rf.adapter/helix`, `:rf.adapter/plain-atom`, `:rf.adapter/ssr`, or
  `:custom` for user-supplied adapters that didn't pick a canonical kind.

  This answers \"what substrate am I on?\" — predicate / branch code.
  For \"give me the adapter spec map\" (fn handles, hot-swap, identity
  checks across install/dispose), use `current-adapter-spec`."
  []
  (when-let [a @installed-adapter]
    (:kind a :custom)))

(defn current-adapter-spec
  "Return the installed adapter spec map, or nil if none. The map carries
  the adapter contract fns (`:make-state-container`, `:replace-container!`,
  `:make-derived-value`, …) per Spec 006 §The reactive-substrate adapter
  contract plus the `:kind` discriminator keyword (per `current-adapter`).

  This answers \"give me the adapter fns to call\" — tools, routing,
  identity checks across the install/dispose lifecycle. For the
  human-readable discriminator (predicate / branch code), use
  `current-adapter`."
  []
  @installed-adapter)

(defn dispose-adapter!
  "Tear down the installed adapter. Calls the adapter's :dispose-adapter!
  fn (if present), clears the install slot so a new adapter can install,
  and sets the disposed breadcrumb so subsequent delegation calls raise
  `:rf.error/adapter-disposed` instead of `:rf.error/no-adapter-installed`
  (rf2-6wxys). Calling dispose with no adapter installed leaves the
  breadcrumb untouched — it doesn't pretend a never-installed adapter
  was disposed."
  []
  (when-let [adapter @installed-adapter]
    (when-let [f (:dispose-adapter! adapter)]
      (f))
    (reset! installed-adapter nil)
    (reset! disposed? true))
  nil)

(defn adapter-disposed?
  "Return true iff the most recent lifecycle event was a successful
  `dispose-adapter!` and no `install-adapter!` has fired since. False
  for `never installed` (fresh process) and after a fresh install.
  Read-only — the breadcrumb is owned by the install / dispose pair."
  []
  @disposed?)

(defn ^:no-doc reset-lifecycle-state-for-tests!
  "Test-only seam — resets the installed-adapter slot and the disposed
  breadcrumb to a never-installed cold state. NOT part of the runtime
  contract; cold-start test fixtures (e.g. `boot_test/cold-start`) use
  this to wipe the lifecycle state between cases so the no-adapter-
  installed throw can be asserted independently of the adapter-
  disposed throw."
  []
  (reset! installed-adapter nil)
  (reset! disposed? false)
  nil)

;; ---- delegation helpers ---------------------------------------------------
;; These let other namespaces call adapter functions without knowing which
;; adapter is installed.
;;
;; Every delegation fn routes through `require-adapter!` so an app that
;; calls into the runtime before `(rf/init! ...)` sees one uniform
;; `:rf.error/no-adapter-installed` ex-info regardless of which surface
;; it hit first (rf2-zdfi1). The earlier shape was a mix of
;; `(ex-info "no adapter installed" {})` on `make-state-container` and
;; silent NPEs on the other delegation fns — strictly worse than a
;; structured throw because background-thread NPEs are hard to diagnose
;; and the ex-info shape did not match the documented missing-artefact
;; contract used elsewhere in core (see rf2-h824v + rf2-uchhp).

(defn- require-adapter!
  "Return the installed adapter spec map or throw a uniform
  ex-info naming the offending delegation surface via `where-sym`
  (rf2-zdfi1).

  Two throw shapes (rf2-6wxys):

    1. `:rf.error/no-adapter-installed` — no adapter has ever been
       installed (the disposed breadcrumb is false).
    2. `:rf.error/adapter-disposed` — an adapter was previously
       installed and has since been torn down by `dispose-adapter!`
       without a subsequent install. Distinguishes the
       fixture-tore-down-the-runtime case from the never-bootstrapped
       case so test diagnostics, post-mortem traces, and pair-tool
       overlays can point at the right remedy.

  Shape matches the broader missing-fn throw contract (the
  `re-frame.late-bind/require-fn!` helper, rf2-h824v, rf2-uchhp):

    Message: \":rf.error/<tag>\"
    ex-data: {:where    <where-sym>
              :recovery :no-recovery
              :reason   <human-readable string>}

  `where-sym` is stamped on the throw so grep-for-symbol finds the
  delegation site in user code. Private — callers in this ns thread
  the symbol of the public surface they implement (e.g.
  `'rf/make-state-container`)."
  [where-sym]
  (or @installed-adapter
      (if @disposed?
        (throw (ex-info ":rf.error/adapter-disposed"
                        {:where    where-sym
                         :recovery :no-recovery
                         :reason   (str where-sym " was called after"
                                        " (rf/destroy-adapter!); the previously"
                                        " installed adapter was torn down."
                                        " Install a fresh adapter via"
                                        " (rf/init! <adapter>) before calling.")}))
        (throw (ex-info ":rf.error/no-adapter-installed"
                        {:where    where-sym
                         :recovery :no-recovery
                         :reason   (str where-sym " was called before (rf/init! ...);"
                                        " require an adapter ns and pass its `adapter` Var,"
                                        " e.g. (rf/init! reagent/adapter).")})))))

(defn make-state-container [initial-value]
  (let [a (require-adapter! 'rf/make-state-container)]
    ((:make-state-container a) initial-value)))

(defn read-container [container]
  (let [a (require-adapter! 'rf/read-container)]
    ((:read-container a) container)))

(defn replace-container!
  "Write `new-value` into `container` via the installed adapter.

  Defense-in-depth nil guard (rf2-ft2b): if `container` is nil — e.g. a
  scheduled drain races frame destruction and reaches the per-event :db
  commit after `frame/get-frame-db` has started returning nil for the
  destroyed frame — the write is silently skipped and a
  `:rf.warning/write-after-destroy` trace fires. The earlier behaviour
  was an NPE on a background thread (see the rf2-ft2b reproducer). Adapter
  implementations may assume `container` is non-nil; this wrapper is the
  single choke point through which every frame app-db write flows, so
  guarding here covers the router :db commit, drain rollback, flows, epoch
  restore, and SSR write paths in one place."
  [container new-value]
  (if (nil? container)
    (trace/emit! :warning :rf.warning/write-after-destroy
                 {:reason   "replace-container! called with nil container; the frame was likely destroyed mid-drain or before a scheduled write fired"
                  :recovery :no-recovery})
    (let [a (require-adapter! 'rf/replace-container!)]
      ((:replace-container! a) container new-value))))

(defn make-derived-value [source-containers compute-fn]
  (let [a (require-adapter! 'rf/make-derived-value)]
    ((:make-derived-value a) source-containers compute-fn)))

(defn render [render-tree mount-point opts]
  (let [a (require-adapter! 'rf/render)]
    ((:render a) render-tree mount-point opts)))

(defn render-to-string [render-tree opts]
  (let [a (require-adapter! 'rf/render-to-string)]
    ((:render-to-string a) render-tree opts)))

(defn subscribe-container
  "Optional — adapters may omit. Returns nil if the installed adapter
  doesn't implement it; callers fall back to running invalidation inline
  within replace-container! (per Spec 006).

  Calling this before `(rf/init! ...)` raises
  `:rf.error/no-adapter-installed` (rf2-zdfi1) — the optional-fn nil
  return is reserved for `adapter installed, fn absent`, not for `no
  adapter installed at all`."
  [container on-change]
  (let [a (require-adapter! 'rf/subscribe-container)
        f (:subscribe-container a)]
    (when f (f container on-change))))

(defn register-context-provider
  "Optional. Returns the substrate's context-provider component for this
  frame keyword, or nil if the substrate has no context concept.

  Calling this before `(rf/init! ...)` raises
  `:rf.error/no-adapter-installed` (rf2-zdfi1) — the optional-fn nil
  return is reserved for `adapter installed, fn absent`, not for `no
  adapter installed at all`."
  [frame-keyword]
  (let [a (require-adapter! 'rf/register-context-provider)
        f (:register-context-provider a)]
    (when f (f frame-keyword))))

;; ---- late-bind hook routing (rf2-0d35) ------------------------------------
;;
;; Each CLJS adapter (reagent, reagent-slim, uix, helix) publishes ~7-9
;; late-bind hooks at ns-load time so consumers in core (subs, views,
;; interop) reach the installed adapter's substrate-specific impls
;; without a static :require. In test bundles that load multiple adapter
;; ns's, a plain (late-bind/set-fn! k impl) means only the LAST-LOADED
;; adapter's impl survives — and an app installed via
;; `(rf/init! reagent/adapter)` then silently uses (say) UIx's impl,
;; breaking adapter-specific contracts.
;;
;; The fix is to wrap each adapter's impl in a routing closure that
;; runs the impl ONLY when this adapter is the (rf/init!)-installed
;; one; otherwise the closure chains to the previously-registered
;; handler (which does the same active-adapter check for ITS adapter).
;; The chain terminates with fallback-fn — typically `(constantly nil)`,
;; `(constantly false)` for predicates, or `#(frame/current-frame)`
;; for the React-context-tier `:adapter/current-frame` hook.

(defn route-hook!
  "Install `impl-fn` under late-bind hook `hook-key`, wrapped so the call
  dispatches to `impl-fn` ONLY when `adapter-spec` is the currently
  (rf/init!)-installed adapter; otherwise it chains to the previously-
  registered handler. When no previous handler is registered (this is
  the first/only adapter to publish this hook), the routed closure
  returns `(fallback-fn)`.

  See `spec/006-ReactiveSubstrate.md` for the adapter routing contract.

  3-arg form: defaults `fallback-fn` to `(constantly nil)` — the
  most common shape across the adapters (nil-fallback semantics).
  4-arg form: callers pass an explicit thunk for predicate hooks
  (`(constantly false)`) or the React-context tier
  (`#(frame/current-frame)`)."
  ([adapter-spec hook-key impl-fn]
   (route-hook! adapter-spec hook-key impl-fn (constantly nil)))
  ([adapter-spec hook-key impl-fn fallback-fn]
   (let [previous (late-bind/get-fn hook-key)]
     (late-bind/set-fn! hook-key
       (fn routed-hook [& args]
         (if (identical? adapter-spec (current-adapter-spec))
           (apply impl-fn args)
           (if previous (apply previous args) (fallback-fn))))))))
