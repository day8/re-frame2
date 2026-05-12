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

  An adapter is a Clojure map with these keys; it is installed into the
  process via install-adapter! and read via current-adapter.

  Per rf2-agql (replaces rf2-84po) there is no default-adapter registry
  and no ns-load side-effect. Each adapter ns
  (re-frame.adapter.reagent / .uix / .helix, re-frame.substrate.plain-atom,
  re-frame.ssr) exports an `adapter` var (the spec map); consumers
  require the ns and pass the var explicitly via
  `(rf/init! reagent/adapter)` etc. The registry was removed because:
  (1) explicit > implicit at the call site; (2) the registry is bundle
  weight even when unused — under rf2-agql an app that requires only
  the adapter it needs ships only that adapter's code."
  (:require [re-frame.late-bind :as late-bind]
            [re-frame.trace :as trace]))

;; ---- adapter installation -------------------------------------------------

(defonce ^:private installed-adapter (atom nil))

(defn install-adapter!
  "Install the adapter for this process. Once. A second call without an
  intervening dispose-adapter! raises :rf.error/adapter-already-installed
  (per Spec 006 §Single adapter per process)."
  [adapter]
  (when @installed-adapter
    (throw (ex-info ":rf.error/adapter-already-installed"
                    {:installed @installed-adapter :attempted adapter})))
  (reset! installed-adapter adapter)
  adapter)

(defn current-adapter
  "Return the installed adapter, or nil if none."
  []
  @installed-adapter)

(defn dispose-adapter!
  "Tear down the installed adapter. Calls the adapter's :dispose-adapter!
  fn (if present), then clears the slot so a new adapter can install."
  []
  (when-let [adapter @installed-adapter]
    (when-let [f (:dispose-adapter! adapter)]
      (f))
    (reset! installed-adapter nil))
  nil)

;; ---- delegation helpers ---------------------------------------------------
;; These let other namespaces call adapter functions without knowing which
;; adapter is installed.

(defn make-state-container [initial-value]
  (let [a (or @installed-adapter
              (throw (ex-info "no adapter installed" {})))]
    ((:make-state-container a) initial-value)))

(defn read-container [container]
  (let [a @installed-adapter] ((:read-container a) container)))

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
    (let [a @installed-adapter] ((:replace-container! a) container new-value))))

(defn make-derived-value [source-containers compute-fn]
  (let [a @installed-adapter]
    ((:make-derived-value a) source-containers compute-fn)))

(defn render [render-tree mount-point opts]
  (let [a @installed-adapter] ((:render a) render-tree mount-point opts)))

(defn render-to-string [render-tree opts]
  (let [a @installed-adapter] ((:render-to-string a) render-tree opts)))

(defn subscribe-container
  "Optional — adapters may omit. Returns nil if the adapter doesn't
  implement it; callers fall back to running invalidation inline within
  replace-container! (per Spec 006)."
  [container on-change]
  (let [a @installed-adapter
        f (:subscribe-container a)]
    (when f (f container on-change))))

(defn register-context-provider
  "Optional. Returns the substrate's context-provider component for this
  frame keyword, or nil if the substrate has no context concept."
  [frame-keyword]
  (let [a @installed-adapter
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
;; Per rf2-0d35 the fix is to wrap each adapter's impl in a routing
;; closure that runs the impl ONLY when this adapter is the
;; (rf/init!)-installed one; otherwise the closure chains to the
;; previously-registered handler (which itself does the same
;; active-adapter check for ITS adapter). The chain terminates with
;; fallback-fn when no previous handler is registered — typically
;; `(constantly nil)` (default), `(constantly false)` for predicates,
;; or `#(frame/current-frame)` for the React-context-tier
;; `:adapter/current-frame` hook (whose chain-bottom is the
;; dynamic-var / :rf/default resolution in re-frame.frame).

(defn route-hook!
  "Install `impl-fn` under late-bind hook `hook-key`, wrapped so the call
  dispatches to `impl-fn` ONLY when `adapter-spec` is the currently
  (rf/init!)-installed adapter; otherwise it chains to the previously-
  registered handler. When no previous handler is registered (this is
  the first/only adapter to publish this hook), the routed closure
  returns `(fallback-fn)`.

  Per rf2-0d35. See `spec/006-ReactiveSubstrate.md` for the adapter
  routing contract.

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
         (if (identical? adapter-spec (current-adapter))
           (apply impl-fn args)
           (if previous (apply previous args) (fallback-fn))))))))
