(ns re-frame.substrate.adapter
  "The reactive substrate adapter contract. Per Spec 006.

  The runtime is substrate-agnostic; every host-specific reactivity concern
  goes through these 9 functions. The CLJS reference ships two adapters:
  Reagent (browser) and plain-atom (JVM / SSR / headless).

  Required functions (6):
    make-state-container, read-container, replace-container!,
    make-derived-value, render, render-to-string

  Optional (2):
    subscribe-container, register-context-provider

  Lifecycle (1):
    dispose-adapter!

  An adapter is a Clojure map with these keys; it is installed into the
  process via install-adapter! and read via current-adapter.

  Per rf2-84po (resolves rf2-4cb6) this namespace also owns the
  default-adapter registry consulted by `re-frame.core/init!` when called
  with no args. Substrate-adapter namespaces (re-frame.substrate.reagent,
  re-frame.substrate.uix, the JVM half of re-frame.substrate.plain-atom)
  call `register-default-adapter!` at ns-load time; `(rf/init!)` looks up
  the registered default and either uses it (exactly-one), raises
  `:rf.error/no-adapter-registered` (zero), or
  `:rf.error/multiple-default-adapters` (more than one — the consumer
  must disambiguate via `(rf/init! :reagent)` / `(rf/init! :uix)`)."
  (:require [re-frame.trace :as trace]))

;; ---- adapter installation -------------------------------------------------

(defonce ^:private installed-adapter (atom nil))

;; ---- default-adapter registry (rf2-84po) ---------------------------------
;;
;; Each substrate-adapter ns calls `register-default-adapter!` at load
;; time. The registry is a plain map `{adapter-key adapter-spec}`; the
;; resolver is consulted by `re-frame.core/init!` when called with no
;; args. Register-time `defonce` semantics in the calling ns guarantee
;; idempotency under reload.

(defonce ^:private default-adapters (atom {}))

(defn register-default-adapter!
  "Register `adapter-spec` (the adapter map) as a default-resolution
  candidate under `adapter-key` (a keyword such as `:reagent`, `:uix`,
  `:plain-atom`). Idempotent: a re-registration under the same key
  replaces the prior entry, so a hot-reload of the substrate ns leaves
  the registry consistent.

  Per rf2-84po (resolves rf2-4cb6). Substrate adapters call this at
  ns-load time, so consumers who `(:require [re-frame.substrate.reagent])`
  pick up the default via `(rf/init!)` without an explicit adapter arg."
  [adapter-key adapter-spec]
  (swap! default-adapters assoc adapter-key adapter-spec)
  nil)

(defn unregister-default-adapter!
  "Remove `adapter-key` from the default-adapter registry. Test-only —
  consumers MUST NOT call this from app code. Per rf2-84po the
  default-adapter registry is populated at ns-load time and is not
  expected to mutate at runtime; this entry point exists so the
  multi-adapter-error tests can put the registry into a known shape
  without reloading namespaces."
  [adapter-key]
  (swap! default-adapters dissoc adapter-key)
  nil)

(defn registered-default-adapters
  "Return the current default-adapter registry as `{key adapter-spec}`.
  Read-only snapshot. Per rf2-84po."
  []
  @default-adapters)

(defn resolve-default-adapter
  "Resolve the default adapter for `(rf/init!)` (no args).

  Returns either:
    {:adapter <spec> :key <kw>}            — exactly-one, success
    {:error :rf.error/no-adapter-registered}
                                            — zero registered
    {:error :rf.error/multiple-default-adapters
     :keys [<kw> ...]}                      — more than one registered

  Per rf2-84po (resolves rf2-4cb6). The multi-adapter case is a hard
  error rather than a last-wins fallback: mixed-substrate apps post
  rf2-3yij can have both Reagent and UIx on the classpath, and silently
  picking one would be a subtle bug surface. The consumer disambiguates
  by calling `(rf/init! :reagent)` / `(rf/init! :uix)` explicitly."
  []
  (let [registry @default-adapters
        ks       (vec (sort (keys registry)))]
    (case (count ks)
      0 {:error :rf.error/no-adapter-registered}
      1 (let [k (first ks)] {:adapter (get registry k) :key k})
      {:error :rf.error/multiple-default-adapters
       :keys  ks})))

(defn lookup-default-adapter
  "Look up an explicitly-named default adapter by key (e.g. `:reagent`,
  `:uix`). Returns the adapter spec or nil. Per rf2-84po: the
  `(rf/init! :reagent)` disambiguation path."
  [adapter-key]
  (get @default-adapters adapter-key))

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
