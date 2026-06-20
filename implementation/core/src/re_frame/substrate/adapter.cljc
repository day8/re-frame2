(ns re-frame.substrate.adapter
  "The reactive substrate adapter contract. Per Spec 006.

  The runtime is substrate-agnostic; every host-specific reactivity concern
  goes through these ten functions. The CLJS reference ships four adapter
  artefacts: Reagent (browser), UIx (browser), Helix (browser), and
  plain-atom (JVM / SSR / headless).

  Required functions (6):
    make-state-container, read-container, replace-container!,
    make-derived-value, render, render-to-string

  Optional (3):
    subscribe-container, register-context-provider, flush-render!

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
  (:require [re-frame.error :as error]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.realm :as realm]
            [re-frame.trace :as trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- adapter installation — realm-owned (EP-0013 D1 stage 4, rf2-0lq5cd) --
;;
;; The adapter SELECTION is OWNED BY the realm (Runtime-Subsystems §Adapter
;; Ownership). Stage 4 moved the two process-global `defonce` cells that used
;; to live HERE into the default realm's record — `:adapter` (the live spec
;; map, or absent) and `:adapter-disposed?` (the dispose breadcrumb). This ns
;; is now the ACCESS SEAM over those realm slots; the public surface below is
;; byte-identical and every call in a single-realm app routes through the one
;; default realm, so the install/dispose lifecycle and the "single adapter per
;; process" diagnostics behave exactly as before. The two-cell shape is
;; preserved across the move:
;;
;;   `realm/realm-adapter`            ← the old `@installed-adapter` read
;;   `realm/install-realm-adapter!`   ← the old `(reset! installed-adapter …)`
;;   `realm/dispose-realm-adapter!`   ← clears the slot + sets the breadcrumb
;;   `realm/realm-adapter-disposed?`  ← the old `@disposed?` read
;;
;; The breadcrumb still distinguishes:
;;   (a) `no adapter installed yet` — fresh process, init! never ran;
;;       the right diagnosis is `:rf.error/no-adapter-installed`;
;;   (b) `adapter was installed and then torn down` — usually a test
;;       fixture or a hot-reload swap that hasn't reinstalled yet;
;;       the right diagnosis is `:rf.error/adapter-disposed`.
;;
;; Both states leave the install slot empty so a fresh adapter can
;; install via `install-adapter!`; the breadcrumb clears on the next
;; successful install. No cycle: `re-frame.realm` requires only `registrar` +
;; `late-bind` (neither pulls this ns), so the static require above is sound.

(defn install-adapter!
  "Install the adapter for this process. Once. A second call without an
  intervening dispose-adapter! raises :rf.error/adapter-already-installed
  (per Spec 006 §Single adapter per process). Clears the disposed
  breadcrumb so subsequent delegation calls see a fresh adapter rather
  than `:rf.error/adapter-disposed`.

  The adapter SELECTION is owned by the default realm (EP-0013 D1 stage 4);
  this fn guards the single-adapter invariant and then seats the adapter via
  `realm/install-realm-adapter!`."
  [adapter]
  (when-let [installed (realm/realm-adapter)]
    (error/throw-error!
      :rf.error/adapter-already-installed
      'rf/init!
      "A second install-adapter! was called without an intervening (rf/destroy-adapter!); the existing adapter remains installed (per Spec 006 §Single adapter per process)."
      {:extra {:installed installed
               :attempted adapter}}))
  (realm/install-realm-adapter! adapter)
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
  (when-let [a (realm/realm-adapter)]
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
  (realm/realm-adapter))

(defn dispose-adapter!
  "Tear down the installed adapter. Calls the adapter's :dispose-adapter!
  fn (if present), clears the install slot so a new adapter can install,
  and sets the disposed breadcrumb so subsequent delegation calls raise
  `:rf.error/adapter-disposed` instead of `:rf.error/no-adapter-installed`
  (rf2-6wxys). Calling dispose with no adapter installed leaves the
  breadcrumb untouched — it doesn't pretend a never-installed adapter
  was disposed.

  The adapter SELECTION is owned by the default realm (EP-0013 D1 stage 4);
  this fn runs the seated adapter's own teardown and then clears the realm's
  adapter slot via `realm/dispose-realm-adapter!`."
  []
  (when-let [adapter (realm/realm-adapter)]
    (when-let [f (:dispose-adapter! adapter)]
      (f))
    (realm/dispose-realm-adapter!))
  nil)

(defn adapter-disposed?
  "Return true iff the most recent lifecycle event was a successful
  `dispose-adapter!` and no `install-adapter!` has fired since. False
  for `never installed` (fresh process) and after a fresh install.
  Read-only — the breadcrumb is owned by the install / dispose pair (the
  default realm's `:adapter-disposed?` slot, EP-0013 D1 stage 4)."
  []
  (realm/realm-adapter-disposed?))

(defn ^:no-doc reset-lifecycle-state-for-tests!
  "Test-only seam — resets the default realm's adapter slot and the disposed
  breadcrumb to a never-installed cold state. NOT part of the runtime
  contract; cold-start test fixtures (e.g. `boot_test/cold-start`) use
  this to wipe the lifecycle state between cases so the no-adapter-
  installed throw can be asserted independently of the adapter-
  disposed throw."
  []
  (realm/reset-realm-adapter-lifecycle!)
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

  Carries the canonical thrown-error shape (per Spec 009 §The
  thrown-error shape), matching the broader missing-fn throw contract
  (the `re-frame.late-bind/require-fn!` helper):

    Message: \":rf.error/<tag>\"
    ex-data: {:rf.error/id :rf.error/<tag>  ;; canonical discriminator
              :where       <where-sym>
              :recovery    :no-recovery
              :reason      <human-readable string>}

  The `:rf.error/id` slot is the canonical discriminator consumers read
  uniformly; the message string is the stringified kw so `.getMessage`
  pivots to the same category. `where-sym` is stamped on the throw so
  grep-for-symbol finds the delegation site in user code. Private —
  callers in this ns thread the symbol of the public surface they
  implement (e.g. `'rf/make-state-container`).

  Reads the adapter SELECTION from the default realm (EP-0013 D1 stage 4)."
  [where-sym]
  (or (realm/realm-adapter)
      (if (realm/realm-adapter-disposed?)
        (error/throw-error!
          :rf.error/adapter-disposed
          where-sym
          (str where-sym " was called after"
               " (rf/destroy-adapter!); the previously"
               " installed adapter was torn down."
               " Install a fresh adapter via"
               " (rf/init! <adapter>) before calling."))
        (error/throw-error!
          :rf.error/no-adapter-installed
          where-sym
          (str where-sym " was called before (rf/init! ...);"
               " require an adapter ns and pass its `adapter` Var,"
               " e.g. (rf/init! reagent/adapter).")))))

(defn make-state-container
  "Build a fresh, writable substrate state container holding
  `initial-value` (the per-frame `app-db` cell). Delegates to the
  installed adapter's `:make-state-container`. Required contract fn —
  raises `:rf.error/no-adapter-installed` before `(rf/init! ...)` and
  `:rf.error/adapter-disposed` after teardown (per `require-adapter!`).
  Per Spec 006."
  [initial-value]
  (let [a (require-adapter! 'rf/make-state-container)]
    ((:make-state-container a) initial-value)))

(defn read-container
  "Read the current value out of a substrate `container` (deref). Works
  on both base writable containers and derived values. Delegates to the
  installed adapter's `:read-container`. Required contract fn — raises
  `:rf.error/no-adapter-installed` / `:rf.error/adapter-disposed` when no
  adapter is seated (per `require-adapter!`). Per Spec 006."
  [container]
  (let [a (require-adapter! 'rf/read-container)]
    ((:read-container a) container)))

(def container-class-unknown
  "Sentinel the routed `:adapter/derived-container?` hook returns from its
  chain-bottom fallback to mean \"the installed adapter has NO opinion on
  this container's base-vs-derived class\" — distinct from a genuine
  `false` (\"the installed adapter classifies this as one of ITS base
  containers\"). The choke point (`derived-container?`) treats this
  sentinel — and a never-published hook (`nil`) — as the signal to fall
  back to the host atom-marker heuristic.

  Why a sentinel and not `false` (rf2-oitw37): the hook's old fallback was
  `(constantly false)`, which the choke point could not tell apart from an
  installed adapter's authoritative \"this is a base container\" answer. So
  even when an installed adapter published the hook and answered `false`
  for one of ITS base containers, the choke point's atom-marker arm still
  ran and — for a custom adapter whose base container is NOT atom-shaped —
  WRONGLY reclassified that legitimate base container as derived and
  rejected the write before the adapter's own `:replace-container!` could
  run. The sentinel makes \"no opinion\" (use the heuristic) distinguishable
  from \"installed adapter says base\" (trust it; skip the heuristic).

  Public so `re-frame.substrate.spine` (the ratom hook producer) and any
  custom adapter that publishes a routed `:adapter/derived-container?` hook
  return the SAME sentinel from their `route-hook!` fallback thunk."
  ::container-class-unknown)

(defn- base-atom-shaped-container?
  "True when `container` is a base, writable atom-shape: it satisfies the
  host's atom marker protocol — `clojure.lang.IAtom` on the JVM,
  `cljs.core/IAtom` on CLJS. The host-uniform fall-back discriminator for
  `derived-container?` when the installed adapter has NO opinion on the
  container's class (no `:adapter/derived-container?` hook published, or the
  installed adapter's routed hook returns the `container-class-unknown`
  sentinel).

  Why `IAtom`, not `ISwap`/`IReset`: a CLJS `cljs.core/Atom` implements
  `IAtom` but NOT `ISwap`/`IReset` — `swap!` / `reset!` fast-path on
  `(instance? Atom …)` and only dispatch the `ISwap`/`IReset` protocols
  for non-Atom types. So `(satisfies? ISwap (atom 0))` is FALSE; only
  `IAtom` reliably marks a base atom on both hosts.

  Sound ONLY for adapters whose base container IS atom-shaped (plain-atom,
  test-react, UIx, Helix): their base container is a `clojure.core/atom`
  (an `IAtom`) and their derived value is an `IDeref`-only reify (plain-atom
  JVM / test-react), an `IDeref`+`IDisposable` reify (plain-atom CLJS), or
  an `IDeref`+`IWatchable`+`IDisposable` reify (the React spine) — none of
  which is an `IAtom`. NOT sound for the Reagent family (a Reagent
  `Reaction` reifies `IAtom` too) NOR for a custom adapter whose base
  container is a non-atom host shape (a JS class instance, signal/store
  object, or record). Those adapters MUST publish an
  `:adapter/derived-container?` late-bind hook (the ratom family via
  `spine/make-ratom-adapter`; a custom adapter via its own `route-hook!`)
  so `derived-container?` consults the adapter and never reaches this
  heuristic for their containers (rf2-oitw37)."
  [container]
  #?(:clj  (instance? clojure.lang.IAtom container)
     :cljs (satisfies? IAtom container)))

(defn- derived-container?
  "True when `container` is a derived value (a `make-derived-value`
  result) rather than a base, writable container. Per Spec 006
  §`make-derived-value` a derived container supports `read-container` but
  NOT `replace-container!`.

  No single host protocol distinguishes the two shapes across every
  reference adapter — a Reagent `Reaction` reifies `IAtom` exactly like a
  base `r/atom`, and a custom adapter's base container may be a non-atom
  host shape entirely. So `detecting a derived container is the adapter's
  responsibility` (Spec 006 §`make-derived-value`): the installed adapter
  is the authority on its own container shapes and declares them via the
  routed `:adapter/derived-container?` late-bind hook (the ratom adapters
  key it on their reaction's substrate disposal protocol — a `Reaction` is
  disposable, an `r/atom` is not; a custom adapter answers from whatever
  distinguishes its own base from its own derived containers). Routed (per
  `route-hook!`) so it answers authoritatively only while its adapter is
  installed.

  Three-way reading of the routed hook, where the installed adapter is the
  authority WHENEVER it has an opinion (rf2-oitw37):

    * hook returns truthy → DERIVED (the installed adapter classifies this
      as one of its `make-derived-value` results) — reject the write.
    * hook returns `false` → BASE (the installed adapter classifies this as
      one of ITS writable base containers) — NOT derived; delegate the
      write. The host atom-marker heuristic is NOT consulted, so a custom
      adapter whose legitimate base container is not `IAtom`-shaped is no
      longer misclassified.
    * hook returns the `container-class-unknown` sentinel, or no adapter
      published the hook (`nil`) → the installed adapter has NO opinion;
      fall back to the host atom-marker heuristic (sound only for adapters
      whose base container IS atom-shaped — plain-atom / test-react / UIx /
      Helix, whose derived values are NOT atom-shaped).

  For the ratom family the hook is decisive (truthy for a `Reaction`,
  `false` for a base `r/atom`), so the heuristic is never reached for their
  containers. For plain-atom / test-react / UIx / Helix the routed hook is
  absent or returns the sentinel (chain fallback), so the atom-marker arm
  decides — exactly where it is sound.

  The hook lives in the late-bind table (not the adapter spec map) so the
  ten-fn adapter contract shape is preserved."
  [container]
  (let [hook (late-bind/get-fn :adapter/derived-container?)
        verdict (when hook (hook container))]
    (cond
      ;; Installed adapter says DERIVED — authoritative reject.
      (and (some? verdict) (not= verdict container-class-unknown) verdict)
      true
      ;; Installed adapter says BASE (an explicit `false`, not the sentinel
      ;; / nil) — authoritative accept; skip the atom-marker heuristic so a
      ;; non-atom-shaped custom base container is not misclassified.
      (false? verdict)
      false
      ;; No installed-adapter opinion (sentinel or no hook published) —
      ;; host atom-marker heuristic.
      :else
      (not (base-atom-shaped-container? container)))))

(defn replace-container!
  "Write `new-value` into `container` via the installed adapter.

  Defense-in-depth nil guard (rf2-ft2b): if `container` is nil — e.g. a
  scheduled drain races frame destruction and reaches the per-event :db
  commit after `frame/app-db-container` has started returning nil for the
  destroyed frame — the write is silently skipped and a production-
  survivable `:rf.error/write-after-destroy` rides the always-on error-emit
  axis (EP-0008, rf2-500ech; default `:recovery :ignored` — the write is
  dropped, the frame is gone, mirroring `:rf.error/frame-destroyed`). The
  earlier behaviour was an NPE on a background thread (see the rf2-ft2b
  reproducer). Adapter
  implementations may assume `container` is non-nil; this wrapper is the
  single choke point through which every frame app-db write flows, so
  guarding here covers the router :db commit, drain rollback, flows, epoch
  restore, and SSR write paths in one place.

  Derived-container guard (rf2-8wrzz.3): a derived container (the result
  of `make-derived-value`) supports `read-container` but NOT
  `replace-container!` — partial/whole replacement of a value computed
  from sources is meaningless (per Spec 006 §`make-derived-value`).
  Writing to one is a programmer error. The choke point detects the
  derived container (`derived-container?` — the installed adapter's routed
  `:adapter/derived-container?` late-bind hook is authoritative whenever it
  has an opinion (truthy = derived → reject; `false` = one of the adapter's
  base containers → delegate), and the host atom-marker heuristic is the
  fall-back only when the installed adapter has no opinion, rf2-oitw37),
  emits a `:rf.error/derived-container-replaced` trace so error-listeners
  observe it, and throws the canonical thrown-error ex-info (per Spec 009
  §The thrown-error shape) so a `try`/`catch` and
  `(:rf.error/id (ex-data e))` pivot on one vocabulary. The underlying
  adapter `replace-container!` is NOT invoked. Guarding here (rather than
  per-adapter) gives one uniform contract across every reference adapter —
  the same single choke point that carries the nil guard above. A custom
  adapter whose legitimate base container is NOT atom-shaped publishes the
  routed hook (answering `false` for its base) so its writes delegate
  rather than being rejected as derived (rf2-oitw37)."
  [container new-value]
  (if (nil? container)
    ;; rf2-ft2b nil guard runs BEFORE the adapter lookup (a scheduled drain
    ;; racing frame destruction must not surface a misleading
    ;; no-adapter-installed throw — see `replace-container-nil-container-
    ;; skips-adapter-check`).
    ;;
    ;; EP-0008 (rf2-500ech): this is a SUPPRESSED WRITE — the dropped :db
    ;; commit is invisible to the next op and compounds with process lifetime
    ;; in long-lived SSR / multi-frame hosts. The SAME destroy-race already
    ;; surfaces as the production-survivable :rf.error/frame-destroyed on the
    ;; dispatch / subscribe paths, so the write path rides the same always-on
    ;; axis. The substrate cannot static-require re-frame.error-emit (load
    ;; order), so we reach `dispatch-on-error!` through the published
    ;; `:error-emit/dispatch-on-error` late-bind hook — the producer always
    ;; loads at boot, so the lookup never misses in production. The dev error
    ;; trace below stays for the in-process tooling surface (DCE'd in prod).
    (let [reason "replace-container! called with nil container; the frame was likely destroyed mid-drain or before a scheduled write fired"]
      (when-let [dispatch-on-error! (late-bind/get-fn :error-emit/dispatch-on-error)]
        (dispatch-on-error!
          :rf.error/write-after-destroy
          nil                              ;; no event vector — a dropped write, not a throw on a dispatch
          nil                              ;; no event-id — the write path carries none
          nil                              ;; no frame — it was destroyed (the whole point)
          nil                              ;; no exception — a suppressed write, not a throw
          0                                ;; elapsed-ms
          (interop/now-ms)))               ;; time
      (trace/emit-error! :rf.error/write-after-destroy
                         {:category :rf.error/write-after-destroy
                          :reason   reason
                          :recovery :ignored}))
    ;; Resolve the adapter FIRST so a write before `(rf/init! ...)` or after
    ;; dispose surfaces the uniform `:rf.error/no-adapter-installed` /
    ;; `:rf.error/adapter-disposed` throw (rf2-zdfi1) — the derived-container
    ;; classification is meaningless without a seated adapter (you cannot say
    ;; what shape a container is for a substrate that isn't installed).
    (let [a (require-adapter! 'rf/replace-container!)]
      (if (derived-container? container)
        (let [reason "replace-container! is not supported on a derived container; derived containers are read-only (per Spec 006 §make-derived-value). Write to the source container(s) the derived value is computed from instead."]
          (trace/emit-error! :rf.error/derived-container-replaced
                             {:category  :rf.error/derived-container-replaced
                              :recovery  :no-recovery
                              :reason    reason})
          (error/throw-error!
            :rf.error/derived-container-replaced
            'rf/replace-container!
            reason))
        ((:replace-container! a) container new-value)))))

(defn make-derived-value
  "Build a read-only derived value: a container that recomputes
  `(compute-fn)` from `source-containers` and is observable via
  `read-container` but NOT writable via `replace-container!` (the
  substrate backing for layer-2+ subs). Delegates to the installed
  adapter's `:make-derived-value`. Required contract fn — raises
  `:rf.error/no-adapter-installed` / `:rf.error/adapter-disposed` when no
  adapter is seated (per `require-adapter!`). Per Spec 006
  §`make-derived-value`."
  [source-containers compute-fn]
  (let [a (require-adapter! 'rf/make-derived-value)]
    ((:make-derived-value a) source-containers compute-fn)))

(defn render
  "Mount/render `render-tree` (a hiccup tree) into `mount-point` via the
  installed adapter's `:render`. The client-side mount entry point.
  Required contract fn — raises `:rf.error/no-adapter-installed` /
  `:rf.error/adapter-disposed` when no adapter is seated (per
  `require-adapter!`). Per Spec 006."
  [render-tree mount-point opts]
  (let [a (require-adapter! 'rf/render)]
    ((:render a) render-tree mount-point opts)))

(defn render-to-string
  "Render `render-tree` to an HTML string (server-side / SSR) via the
  installed adapter's `:render-to-string`. The non-mounting counterpart
  of `render`. Required contract fn — raises
  `:rf.error/no-adapter-installed` / `:rf.error/adapter-disposed` when no
  adapter is seated (per `require-adapter!`). Per Spec 006."
  [render-tree opts]
  (let [a (require-adapter! 'rf/render-to-string)]
    ((:render-to-string a) render-tree opts)))

(defn flush-render!
  "Optional. SYNCHRONOUSLY commit the substrate's pending renders to the
  DOM (per Spec 006 §`flush-render!`). Runs the 1-arity `f` inside the
  substrate's synchronous-commit path (React `flushSync` for the React-hook
  substrates; `reagent.core/flush` for the ratom family) so any state change
  `f` schedules — and any render already pending — is committed before this
  returns; the 0-arity form flushes already-pending work.

  Why it exists. The reference substrates schedule re-renders through a
  `requestAnimationFrame`-style tick that fires AFTER an eval'd `dispatch`
  returns and is throttled to ~never in a backgrounded / unfocused tab.
  `flush-render!` is NOT rAF-scheduled, so headless tooling (the
  re-frame2-pair MCP — Spec Tool-Pair) can drive a
  `dispatch → flush-render! → observe-settled-DOM` loop even when the tab is
  backgrounded. Distinct from a test-only `act()` flush: this is the
  production-grade contract surface every adapter implements.

  Optional contract fn — returns nil and is a no-op when the installed
  adapter ships no `:flush-render!` (the plain-atom / SSR adapters render
  without a live React commit, so there is nothing to flush). Calling it
  before `(rf/init! ...)` raises `:rf.error/no-adapter-installed`
  (rf2-zdfi1) — the optional-fn nil return is reserved for `adapter
  installed, fn absent`, not for `no adapter installed at all`."
  ([] (flush-render! (fn [] nil)))
  ([f]
   (let [a (require-adapter! 'rf/flush-render!)
         g (:flush-render! a)]
     (when g (g f))
     nil)))

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

;; ---- adapter routing token (rf2-dkl5z1) -----------------------------------
;;
;; Hook routing (below) and the public test-react `mount!` guard must answer
;; one question: "is the adapter THIS hook/driver belongs to the one
;; (rf/init!)-installed right now?" The original answer was raw object
;; identity — `(identical? adapter-spec (current-adapter-spec))`. That is
;; WRONG against a copied or wrapped canonical adapter map: a user (or the
;; already-tested adapter-swap pattern in `boot_test`) that
;; `assoc`/`merge`/`update`s a canonical adapter map for instrumentation or
;; local overrides installs a value-equal map with a DIFFERENT identity, so
;; every routed hook silently falls through to the chain/fallback — boot is
;; green but UIx/Helix lose `:adapter/current-frame` React-context
;; resolution, view source/view-id wrapping, after-render, and derived-value
;; disposal; Reagent/reagent-slim lose the ratom-family hooks including the
;; `:adapter/derived-container?` guard; test-react `mount!` throws even
;; though a test-react-shaped map is installed. Spec 006
;; §Frame-provider via React context requires the `:adapter/current-frame`
;; hook to resolve to the LIVE routed impl, so a frozen-identity guard that
;; serves stale (inert) hooks for a copied map is a correctness bug.
;;
;; The fix: route by a STABLE token carried in the installed map rather than
;; object identity. The token is the adapter's canonical `:kind`
;; discriminator (`:rf.adapter/reagent`, `:rf.adapter/uix`, …) — a value
;; that survives `assoc`/`merge`/copy, so a copied canonical map still
;; dispatches to its adapter's live hooks. A genuinely custom adapter that
;; did NOT pick a canonical `:rf.adapter/*` kind (its `:kind` is absent or
;; `:custom`) has no distinguishing token, so it falls back to object
;; identity — two distinct `:custom` adapters can never be conflated by a
;; shared `:custom` keyword.

(defn- canonical-kind?
  "True when `kind` is a canonical framework adapter kind — a keyword in the
  reserved `:rf.adapter/*` namespace (per Spec 006 §Adapter introspection /
  Conventions §Reserved namespaces). `:custom` and `nil` are NOT canonical:
  they carry no per-adapter distinguishing token, so routing for those
  falls back to object identity."
  [kind]
  (and (keyword? kind) (= "rf.adapter" (namespace kind))))

(defn same-adapter?
  "True when adapter spec map `a` is, for hook-routing / driver-guard
  purposes, the SAME adapter as `b`. Stable-token comparison (rf2-dkl5z1):
  when both maps carry the same canonical `:rf.adapter/*` `:kind`, they are
  the same adapter even across a copy/`assoc`/`merge` (the token survives
  structural edits), so a copied canonical adapter map still routes to its
  live hooks. Otherwise (a non-canonical / `:custom` / kindless adapter, or
  mismatched kinds) it falls back to object identity so two distinct custom
  adapters are never conflated by a shared `:custom` keyword. Nil-safe: a
  nil `b` (no adapter installed) is never the same as a non-nil `a`.

  Public so adapter-side driver guards that gate on \"is MY adapter the
  installed one?\" (e.g. the Test-React `mount!` driver) share the one
  stable-token predicate `route-hook!` uses, rather than re-deriving an
  object-identity check that would reject a copied canonical map."
  [a b]
  (boolean
    (and a b
         (let [ka (:kind a)]
           (if (canonical-kind? ka)
             (= ka (:kind b))
             (identical? a b))))))

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
;;
;; The active-adapter check is `same-adapter?` (stable-token routing,
;; rf2-dkl5z1), NOT raw object identity — so a copied / wrapped canonical
;; adapter map still dispatches to its adapter's live hooks.

(defn route-hook!
  "Install `impl-fn` under late-bind hook `hook-key`, wrapped so the call
  dispatches to `impl-fn` ONLY when `adapter-spec` is — by stable token —
  the currently (rf/init!)-installed adapter; otherwise it chains to the
  previously-registered handler. When no previous handler is registered
  (this is the first/only adapter to publish this hook), the routed closure
  returns `(fallback-fn)`.

  Routing is by stable token (`same-adapter?`, rf2-dkl5z1): a copied or
  wrapped canonical adapter map (same `:rf.adapter/*` `:kind`) still
  dispatches to its adapter's live impl, rather than falling through to the
  chain/fallback because object identity changed.

  See `spec/006-ReactiveSubstrate.md` §Frame-provider via React context for
  the adapter routing contract.

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
         (if (same-adapter? adapter-spec (current-adapter-spec))
           (apply impl-fn args)
           (if previous (apply previous args) (fallback-fn))))))))
