(ns re-frame.substrate.adapter
  "The reactive substrate adapter contract. Per Spec 006.

  The runtime is substrate-agnostic; every host-specific reactivity concern
  goes through the installed adapter map. The reference ships TWO first-class,
  actively-supported browser adapters — the Reagent and UIx renderer
  adapters. Neither is transitional, a fallback, or scheduled for removal
  (EP-0036); they are permanent siblings. Alongside them, the Fresco view
  layer ships its own adapter, and plain-atom and ssr cover JVM / SSR /
  headless use — five shipped adapter kinds in all.

  Required functions (6):
    make-state-container, read-container, replace-container!,
    make-derived-value, render, render-to-string

  Optional (3):
    subscribe-container, register-context-provider, flush-render!

  Lifecycle (1):
    dispose-adapter!

  Capabilities (optional keys):
    current-frame, current-component, ratom, ratom?, make-reaction,
    activate-derived-value!, add-on-dispose!, dispose!, reactive?,
    reactive-owner, read-container-untracked, derived-container?,
    after-render, wrap-view, componentize-view — published into their
    `:adapter/<key>` late-bind slots at install (`publish-capabilities!`) —
    and arm-hiccup-emitter-if-unarmed!, which `install-adapter!` reads off
    the map itself.

  An adapter is a Clojure map with these keys plus a `:kind` discriminator
  keyword. Canonical framework members live under the reserved
  `:rf.adapter/*` namespace (`:rf.adapter/reagent`
  / `:rf.adapter/uix` / `:rf.adapter/fresco` / `:rf.adapter/plain-atom` /
  `:rf.adapter/ssr`);
  a user-supplied adapter that picks no canonical kind simply has no
  `:kind` — nothing is synthesised for it. Retired members —
  `:rf.adapter/helix`, `:rf.adapter/ui`, `:rf.adapter/freehand`,
  `:rf.adapter/reagent-slim`, `:rf.adapter/test-react` — stay
  reserved and are never recycled, per the Conventions tombstone rule;
  nothing here can produce one. It is installed into the process via
  install-adapter! and introspected via current-adapter, which answers
  the full map; the discriminator is its `:kind` key.

  There is no default-adapter registry and no ns-load side-effect.
  Each adapter ns (re-frame.adapter.reagent / .uix,
  re-frame.fresco.substrate, re-frame.substrate.plain-atom, re-frame.ssr)
  exports an `adapter`
  var (the spec map); consumers require the ns and pass the var
  explicitly via `(rf/init! reagent/adapter)`. Explicit > implicit
  at the call site, and an app requiring only the adapter it needs
  ships only that adapter's code. Loading an adapter namespace publishes
  nothing: its capabilities answer only while its map is installed."
  (:require [re-frame.error :as rf.error]
            [re-frame.interop :as rf.interop]
            [re-frame.late-bind :as rf.late-bind]
            [re-frame.trace :as rf.trace]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- adapter installation — process-owned (single adapter per process) ----
;;
;; The adapter SELECTION is a process-global fact: a re-frame2 process installs
;; ONE adapter via `(rf/init! <adapter>)`. One `defonce` cell carries the
;; lifecycle state atomically:
;;
;;   `:installed` — nil, or the exact generation token + adapter spec map.
;;   `:disposed?` — the dispose breadcrumb. `true` from the exact generation's
;;                  terminal claim until a fresh install.
;;
;; The breadcrumb distinguishes:
;;   (a) `no adapter installed yet` — fresh process, init! never ran;
;;       the right diagnosis is `:rf.error/no-adapter-installed`;
;;   (b) `adapter was installed and then torn down` — usually a test
;;       fixture or a hot-reload swap that hasn't reinstalled yet;
;;       the right diagnosis is `:rf.error/adapter-disposed`.
;;
;; Teardown marks its generation `:disposing?` before invoking adapter-owned
;; cleanup, then clears ONLY that generation in a finally boundary. A cleanup
;; failure therefore cannot leave a half-live adapter seated, while a stale
;; finalizer can never clear a replacement generation. The breadcrumb clears
;; atomically with the next successful install.

(defn- fresh-adapter-generation []
  #?(:clj (Object.) :cljs (js-obj)))

(defonce
  ^{:doc "The process-owned adapter lifecycle state. `:installed` is nil or an
  exact `{:adapter spec-map :generation opaque-token}` entry (temporarily also
  `:disposing? true` after terminal teardown begins). `:disposed?` distinguishes
  a never-installed process from a torn-down generation. Owned by the
  `install-adapter!` / `dispose-adapter!` pair."}
  adapter-lifecycle-state
  (atom {:installed nil :disposed? false}))

;; ---- adapter identity ------------------------------------------------------
;;
;; "Is this the adapter already seated?" is a question about the adapter, and a
;; map's object identity is the wrong answer to it: a user (or a test's
;; adapter-swap) that `assoc`/`merge`/`update`s a canonical adapter map for
;; instrumentation or local overrides holds a value-equal map with a DIFFERENT
;; identity, and an adapter Var re-evaluated by a reload is a fresh map too. So
;; the answer is a STABLE token carried in the map: the canonical `:kind`
;; discriminator (`:rf.adapter/reagent`, `:rf.adapter/uix`, …), which survives
;; `assoc`/`merge`/copy and re-evaluation. A custom adapter that did NOT pick a
;; canonical `:rf.adapter/*` kind has no distinguishing token, so it falls back
;; to object identity — two distinct kind-less adapters are never conflated.

(defn- canonical-kind?
  "True when `kind` is a canonical framework adapter kind — a keyword in the
  reserved `:rf.adapter/*` namespace (per Spec 006 §Adapter introspection /
  Conventions §Reserved namespaces). A `nil` / absent kind is NOT canonical:
  it carries no per-adapter distinguishing token, so the comparison for it
  falls back to object identity."
  [kind]
  (and (keyword? kind) (= "rf.adapter" (namespace kind))))

(defn same-adapter?
  "True when adapter spec map `a` is the SAME adapter as `b`. Stable-token
  comparison: when both maps carry the same canonical `:rf.adapter/*` `:kind`,
  they are the same adapter even across a copy/`assoc`/`merge` or a reload's
  re-evaluation. Otherwise (a non-canonical or kindless adapter, or mismatched
  kinds) it falls back to object identity so two distinct custom adapters are
  never conflated. Nil-safe: a nil `b` (no adapter installed) is never the same
  as a non-nil `a`.

  Public so the installed-adapter checks outside this ns — `init!`'s
  idempotence guard and the view and subscription caches' built-under
  checks — share one predicate rather than re-deriving an object-identity
  check that would reject a copied canonical map.

  The flat nest of `if`s is deliberate: ClojureScript emits a `let` in
  EXPRESSION position (under `boolean`/`and`) as an IIFE (compiler.cljc
  L1139-1162, r1.12.145), which allocates a closure per call, and the view-head
  lookup asks this on every `(rf/view id)`. Every leaf is already boolean, so
  the flat spelling means exactly what the nested `and` would."
  [a b]
  (if a
    (if b
      (let [ka (:kind a)]
        (if (canonical-kind? ka)
          (= ka (:kind b))
          (identical? a b)))
      false)
    false))

;; ---- capability publication -----------------------------------------------
;;
;; An adapter's capabilities are optional keys on its map. Core reads each one
;; through a late-bind slot — `(rf.late-bind/get-fn-cached :adapter/after-render)`
;; and its siblings — because the readers sit in namespaces the adapter map
;; never reaches and several run on every render, where a cached slot costs
;; one lookup and a `(:k (current-adapter))` read would cost an atom deref and
;; a map lookup per call. So `install-adapter!` publishes the installed map's
;; capabilities into those slots, and the generation's terminal teardown
;; clears them.
;;
;; Publication covers EVERY slot, whatever the map carries: a key the map lacks
;; is published as absent, so no other adapter's capability can answer for it.
;; Loading an adapter namespace publishes nothing.
;;
;; Every write to the slots, and every release of the install seat, happens
;; under `capability-lock`, and only while the writing generation still holds
;; the seat. Seating needs the seat EMPTY, so while a generation holds it no
;; other can seat itself or publish: a generation that checks it still holds
;; the seat and then writes, under the lock, cannot overwrite or erase another
;; generation's capabilities — the same exact-generation rule the lifecycle
;; state follows.

(defonce ^:private capability-lock #?(:clj (Object.) :cljs (js-obj)))

(defn- publish-capabilities!
  "Publish `adapter`'s capability keys into their `:adapter/*` late-bind slots.
  Every slot is written, so a key the map lacks is published as absent; a nil
  `adapter` clears them all. Callers hold `capability-lock`."
  [adapter]
  (rf.late-bind/set-fns!
    {:adapter/current-frame            (:current-frame adapter)
     :adapter/current-component        (:current-component adapter)
     :adapter/ratom                    (:ratom adapter)
     :adapter/ratom?                   (:ratom? adapter)
     :adapter/make-reaction            (:make-reaction adapter)
     :adapter/activate-derived-value!  (:activate-derived-value! adapter)
     :adapter/add-on-dispose!          (:add-on-dispose! adapter)
     :adapter/dispose!                 (:dispose! adapter)
     :adapter/reactive?                (:reactive? adapter)
     :adapter/reactive-owner           (:reactive-owner adapter)
     :adapter/read-container-untracked (:read-container-untracked adapter)
     :adapter/derived-container?       (:derived-container? adapter)
     :adapter/after-render             (:after-render adapter)
     :adapter/wrap-view                (:wrap-view adapter)
     :adapter/componentize-view        (:componentize-view adapter)}))

(defn- holds-seat?
  "True while `generation` is the one in the install seat — seated or claimed
  for teardown."
  [generation]
  (identical? generation (get-in @adapter-lifecycle-state [:installed :generation])))

(defn- publish-for-generation!
  "Publish `adapter`'s capabilities, provided `generation` still holds the
  install seat."
  [generation adapter]
  (locking capability-lock
    (when (holds-seat? generation)
      (publish-capabilities! adapter)))
  nil)

(defn- release-generation!
  "Clear the published capabilities and empty the install seat — both only
  while `generation` still holds it, so a stale caller touches neither a
  replacement generation's seat nor its capabilities. `disposed?` is the
  breadcrumb the release leaves: true after a teardown, false after a failed
  install, which disposed nothing."
  [generation disposed?]
  (locking capability-lock
    (when (holds-seat? generation)
      (publish-capabilities! nil)
      (swap! adapter-lifecycle-state
             (fn [state]
               (if (identical? generation (get-in state [:installed :generation]))
                 (assoc state :installed nil :disposed? disposed?)
                 state)))))
  nil)

(defn- rearm-hiccup-emitter!
  "Re-arm the retained SSR hiccup emitter into the freshly-installed adapter
  generation, through `adapter`'s own `:arm-hiccup-emitter-if-unarmed!`.
  `re-frame.ssr.emit` retains the current emitter durably under
  `:ssr/current-hiccup-emitter` at ns-load; the React-shaped adapter (UIx) and
  the ratom family (Reagent) clear their per-generation `emitter-cell` on
  `dispose-adapter!`, so a public destroy → re-init cycle — or an
  SSR-before-adapter load order — would otherwise leave `render-to-string`
  unarmed (`:rf.error/no-hiccup-emitter-bound`).

  Two properties, both load-bearing:

    1. THE INSTALLED MAP ALONE — the arm is read off the map being installed,
       never a broadcast like `:reagent/set-hiccup-emitter!`. A loaded but
       uninstalled adapter's setter never runs, so its throw cannot break the
       installed adapter's boot.
    2. PRECEDENCE-SAFE — the arm fills the slot ONLY when it is otherwise
       unarmed. A pre-init explicit custom emitter (or reset) is therefore NOT
       silently overwritten by the retained default; the explicit direct
       override remains authoritative (per Spec 006 §`set-hiccup-emitter!`
       last-call-wins), and the default is supplied only to an otherwise-unarmed
       fresh generation.

  Stale-safe: it always applies the SAME current emitter and never clears, so it
  cannot overwrite a replacement generation's slot with a disposed generation's
  emitter. A no-op when re-frame.ssr is not loaded (the durable slot is unset) —
  `render-to-string` then throws the honest `:rf.error/no-hiccup-emitter-bound`
  — and for a map carrying no arm (the JVM/plain-atom emitter is retained
  across its dispose, so no re-arm is required). Raising is the caller's
  contract: `install-adapter!` runs this inside its failure-atomic
  transaction, so a throwing arm rolls the install back rather than
  half-installing."
  [adapter]
  (when-let [emitter (rf.late-bind/get-fn :ssr/current-hiccup-emitter)]
    (when-let [arm-if-unarmed! (:arm-hiccup-emitter-if-unarmed! adapter)]
      (arm-if-unarmed! emitter))))

(defn install-adapter!
  "Install the adapter for this process. Once. A second call without an
  intervening dispose-adapter! raises :rf.error/adapter-already-installed
  (per Spec 006 §Single adapter per process). Clears the disposed
  breadcrumb so subsequent delegation calls see a fresh adapter rather
  than `:rf.error/adapter-disposed`.

  Publishes the map's capabilities into the `:adapter/*` late-bind slots — a
  key the map lacks as absent — and re-arms the retained SSR hiccup emitter for
  the fresh generation (`rearm-hiccup-emitter!`), so a destroy → re-init cycle
  — or an SSR-before-adapter load order — leaves `render-to-string` correctly
  armed after disposal cleared the previous generation's emitter slot.

  FAILURE-ATOMIC. Seating the generation, publishing its capabilities and
  re-arming its retained SSR emitter is ONE transaction. If the re-arm throws,
  the just-seated generation is rolled back — its capabilities cleared and its
  seat emptied, both bounded to THIS generation (`identical?` on the
  generation token, so a replacement installed meanwhile keeps both) — the
  re-arm exception preserved as PRIMARY, the process left in a clean
  never-installed state so an immediate retry installs fresh. Boot is
  therefore all-or-nothing: fully installed, or cleanly failed with a
  consistent adapter state."
  [adapter]
  (let [{:keys [generation] :as entry} {:adapter    adapter
                                        :generation (fresh-adapter-generation)}]
    (swap! adapter-lifecycle-state
           (fn [{:keys [installed] :as state}]
             (when installed
               (rf.error/throw-error!
                :rf.error/adapter-already-installed
                'rf/init!
                "A second install-adapter! — or an (rf/init!) with a DIFFERENT adapter — was called without an intervening (rf/destroy-adapter!); the existing adapter remains installed (per Spec 006 §Single adapter per process). Re-calling init! with the SAME adapter stays an idempotent no-op; to swap substrates, destroy the seated adapter first."
                {:extra {:installed (:adapter installed)
                         :attempted adapter}}))
             (assoc state :installed entry :disposed? false)))
    ;; The generation is now seated. Publication and the re-arm belong to the
    ;; SAME install transaction: a throw rolls THIS generation back, and
    ;; `:disposed? false` restores the never-installed diagnosis (a failed
    ;; install disposed nothing), so a retry surfaces
    ;; `:rf.error/no-adapter-installed`, not `:rf.error/adapter-disposed`.
    (try
      (publish-for-generation! generation adapter)
      (rearm-hiccup-emitter! adapter)
      (catch #?(:clj Throwable :cljs :default) e
        (release-generation! generation false)
        (throw e)))
    adapter))

(defn refresh-capabilities!
  "Republish `adapter`'s capabilities over the seated generation's, when
  `adapter` is the seated adapter by `same-adapter?` but not the identical map.
  `init!` calls this on its idempotent path; anything else is a no-op.

  That path is hot reload. Every adapter Var is a plain `def`, so reloading an
  adapter namespace re-evaluates its map with fresh function identities, and a
  `^:dev/after-load` boot re-calls `init!` with that map. The re-call does not
  reinstall: the seat, and the contract functions `current-adapter` answers,
  stay the generation that owns the live containers and roots. The reloaded
  capabilities are published, so the renders, reads and hooks that run next use
  the code that was just loaded. A map identical to the seated one has nothing
  new to publish and leaves the slots as they are."
  [adapter]
  (let [{:keys [installed]} @adapter-lifecycle-state]
    (when (and installed
               (not (:disposing? installed))
               (not (identical? adapter (:adapter installed)))
               (same-adapter? adapter (:adapter installed)))
      (publish-for-generation! (:generation installed) adapter)))
  nil)

(defn current-adapter
  "Return the installed adapter SPEC MAP — the exact value passed to
  `init!` — or nil if none is installed. The map carries the adapter
  contract fns (`:make-state-container`, `:replace-container!`,
  `:make-derived-value`, …) and any capability keys, per Spec 006 §The
  adapter API contract, plus a `:kind` discriminator keyword.

  ONE read, map-shaped. Branch code asks for the discriminator as a KEY:

      (:kind (current-adapter))   ;; => :rf.adapter/reagent … or nil

  A custom adapter map that picked no canonical `:kind` reads `nil`
  there — nothing is synthesised for it. PRESENCE is therefore a question
  about the MAP, never about `:kind`: a kind-less installed adapter is
  present even though `(:kind (current-adapter))` is nil.

  Returns nil during terminal disposal, so a caller mid-teardown reads
  \"nothing installed\" rather than a half-torn-down spec.

  There is deliberately no separate keyword-returning read: it would be
  the `:kind` of this same map with a synthesised fallback, and one name
  cannot diverge in return type from itself."
  []
  (let [entry (:installed @adapter-lifecycle-state)]
    (when-not (:disposing? entry)
      (:adapter entry))))

(defn- claim-installed-for-dispose!
  "Atomically claim the current generation for one terminal teardown. Returns
  its entry, or nil when the slot is empty/already being destroyed. Claiming
  prevents a re-entrant destroy from invoking cleanup twice and atomically
  closes public delegation through the disposed breadcrumb. The exact internal
  slot remains claimed until cleanup reaches its finally boundary."
  []
  (loop []
    (let [{:keys [installed] :as before} @adapter-lifecycle-state]
      (cond
        (or (nil? installed) (:disposing? installed)) nil
        (compare-and-set! adapter-lifecycle-state
                          before
                          (-> before
                              (assoc-in [:installed :disposing?] true)
                              (assoc :disposed? true)))
        installed
        :else (recur)))))

(defn- drain-fresco-client-roots!
  "Release every React Root `re-frame.fresco`'s own client-root door still
  holds, through the `:fresco/drain-client-roots!` late-bind hook the
  package publishes at `re-frame.fresco.impl.mount`'s ns load. A no-op
  when the day8/re-frame2-fresco artefact is absent (unbound), and on
  every JVM host.

  Why this sits at the PROCESS teardown boundary rather than on an adapter.
  A Fresco root is the PACKAGE's, not any adapter's: `h/render!` reaches
  `createRoot` / `hydrateRoot` through `re-frame.fresco.impl.mount` and
  never through the substrate contract's `render` slot, so no adapter's own
  active-root set ever sees one. Hung off the Fresco adapter's
  `dispose-adapter!`, the drain would be reached only when Fresco was ALSO
  the installed adapter — and Fresco over UIx or over Reagent is supported
  use, not malformed input. In that composition `rf/destroy-adapter!` would
  leave the Root mounted with its `:live?` closure still true, and a
  `render!` through the retained handle would UPDATE the leaked Root instead
  of mounting afresh, against what Spec 006 §The client root's Teardown
  clause promises without an adapter qualifier."
  []
  (when-let [drain! (rf.late-bind/get-fn :fresco/drain-client-roots!)]
    (drain!))
  nil)

(defn- clear-frame-sub-caches!
  "Dispose and empty every live frame's sub-cache. Returns the values the
  per-entry disposes threw, in traversal order — `[]` when nothing threw.

  Frames outlive the adapter, so their caches are process teardown core owns,
  whichever adapter is installed. Each cached derived value is disposed
  through `re-frame.interop/dispose!`, which on CLJS reads the
  `:adapter/dispose!` slot — still the DEPARTING map's `:dispose!` here, since
  the claim hid the generation from `current-adapter` while its capabilities
  stay published until finalization. Reached through the
  `:subs.cache/clear-all-frame-sub-caches!` late-bind hook because
  `re-frame.subs.cache` requires `re-frame.frame`, which requires this ns. A
  no-op when that namespace is not loaded, since then no frame holds a cache."
  []
  (if-let [clear-all! (rf.late-bind/get-fn :subs.cache/clear-all-frame-sub-caches!)]
    (clear-all!)
    []))

#?(:cljs
   (def ^:private teardown-secondary-errors-key
     "The property carrying a teardown's later cleanup failures on the
     rethrown primary — the same string key `re-frame.substrate.spine` writes,
     so a consumer reads one name whichever step failed."
     "rfAdapterTeardownSecondaryErrors"))

(defn- attach-secondary-teardown-failure!
  "Attach `later`, a cleanup failure raised after `primary`, to `primary` as
  secondary diagnostic evidence (Spec 006 §Adapter disposal lifecycle). The
  JVM uses the throwable's suppressed exceptions; CLJS appends to the
  `rfAdapterTeardownSecondaryErrors` array. A primary that cannot carry the
  attachment — a thrown non-object, or a frozen one — loses the evidence and
  keeps its identity."
  [primary later]
  #?(:clj  (when-not (identical? primary later)
             (.addSuppressed ^Throwable primary ^Throwable later))
     :cljs (when (instance? js/Object primary)
             (try
               (let [existing (unchecked-get primary teardown-secondary-errors-key)]
                 (if (array? existing)
                   (.push existing later)
                   (unchecked-set primary teardown-secondary-errors-key (array later))))
               (catch :default _ nil))))
  nil)

(defn- run-teardown-step!
  "Run `step!` and return `failures` with anything it threw appended. Kept by
  PRESENCE, never truthiness: `nil` and `false` are legal CLJS throws."
  [failures step!]
  (try (step!) failures
       (catch #?(:clj Throwable :cljs :default) e (conj failures e))))

(defn dispose-adapter!
  "Tear down the installed adapter. Teardown is a one-way terminal boundary:
  it claims the exact installed generation (so `current-adapter` reads nil and
  public delegation raises `:rf.error/adapter-disposed` from that moment), runs
  cleanup, and then ALWAYS clears that generation's published capabilities and
  install slot and leaves the disposed breadcrumb set, so a new adapter can
  install. Finalization touches only the generation this call claimed: stale
  teardown can never erase a replacement installation or its capabilities.
  Calling dispose with no adapter installed leaves the breadcrumb untouched —
  it doesn't pretend a never-installed adapter was disposed.

  Cleanup runs in this order, every step attempted even when an earlier one
  throws:

    1. Package-owned host roots — `drain-fresco-client-roots!` — so React
       unmounts run while the substrate is still whole.
    2. Every live frame's sub-cache — `clear-frame-sub-caches!` — each cached
       derived value disposed through the departing map's `:dispose!`.
    3. The adapter's own `:dispose-adapter!` (if present).

  Cleanup reaches the departing map's capabilities because they stay
  published until finalization; what the claim closes is admission of new
  public work. The first failure is rethrown unchanged once finalization is
  done; later ones ride it as secondary evidence (Spec 006 §Adapter disposal
  lifecycle): among its suppressed exceptions on the JVM, and in its
  `rfAdapterTeardownSecondaryErrors` array on CLJS."
  []
  (when-let [{:keys [adapter generation]} (claim-installed-for-dispose!)]
    (try
      (let [failures (-> []
                         (run-teardown-step! drain-fresco-client-roots!)
                         (as-> fs (try (into fs (clear-frame-sub-caches!))
                                       (catch #?(:clj Throwable :cljs :default) e
                                         (conj fs e))))
                         (run-teardown-step! (fn adapter-cleanup []
                                               (when-let [f (:dispose-adapter! adapter)]
                                                 (f)))))]
        (when (seq failures)
          (let [primary (first failures)]
            (doseq [later (rest failures)]
              (attach-secondary-teardown-failure! primary later))
            (throw primary))))
      (finally
        ;; Lifecycle finalization is process ownership, not adapter-owned
        ;; cleanup. It must happen even when cleanup reports failure, but an old
        ;; generation must never clear a replacement generation.
        (release-generation! generation true))))
  nil)

(defn adapter-disposed?
  "Return true iff the most recent lifecycle event was an attempted
  `dispose-adapter!` of an installed adapter and no `install-adapter!` has
  fired since. This remains true when adapter-owned cleanup threw: the
  process slot still reached its terminal state. False for `never installed`
  (fresh process) and after a fresh install.
  Read-only — the breadcrumb is owned by the install / dispose pair."
  []
  (:disposed? @adapter-lifecycle-state))

(defn ^:no-doc reset-lifecycle-state-for-tests!
  "Test-only seam — resets the adapter slot and the disposed breadcrumb to a
  never-installed cold state, and clears the published capabilities with
  them. NOT part of the runtime contract; cold-start test fixtures (e.g.
  `boot_test/cold-start`) use this to wipe the lifecycle state between cases
  so the no-adapter-installed throw can be asserted independently of the
  adapter-disposed throw."
  []
  (locking capability-lock
    (publish-capabilities! nil)
    (reset! adapter-lifecycle-state {:installed nil :disposed? false}))
  nil)

;; ---- delegation helpers ---------------------------------------------------
;; These let other namespaces call adapter functions without knowing which
;; adapter is installed.
;;
;; Every delegation fn routes through `require-adapter!` so an app that
;; calls into the runtime before `(rf/init! ...)` sees one uniform
;; `:rf.error/no-adapter-installed` ex-info regardless of which surface
;; it hit first. A mix of a bare `(ex-info "no adapter installed" {})` on
;; one delegation fn and silent NPEs on the others would be strictly worse
;; than a structured throw: background-thread NPEs are hard to diagnose,
;; and the bare ex-info shape would not match the documented
;; missing-artefact contract used elsewhere in core.

(defn- require-adapter!
  "Return the installed adapter spec map or throw a uniform
  ex-info naming the offending delegation surface via `where-sym`.

  Two throw shapes:

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

  Reads the process-global adapter SELECTION."
  [where-sym]
  (let [{:keys [installed disposed?]} @adapter-lifecycle-state]
    (or (when-not (:disposing? installed) (:adapter installed))
      (if disposed?
        (rf.error/throw-error!
          :rf.error/adapter-disposed
          where-sym
          (str where-sym " was called after"
               " (rf/destroy-adapter!); the previously"
               " installed adapter was torn down."
               " Install a fresh adapter via"
               " (rf/init! <adapter>) before calling.")
          {:recovery :install-a-fresh-adapter})
        (rf.error/throw-error!
          :rf.error/no-adapter-installed
          where-sym
          (str where-sym " was called before (rf/init! ...);"
               " require an adapter ns and pass its `adapter` Var,"
               " e.g. (rf/init! reagent/adapter)."))))))

(defn make-state-container
  "Build a fresh, writable substrate state container holding
  `initial-value` (the per-frame `app-db` cell). Delegates to the
  installed adapter's `:make-state-container`. Required contract fn —
  raises `:rf.error/no-adapter-installed` before `(rf/init! ...)` and
  `:rf.error/adapter-disposed` after teardown (per `require-adapter!`).
  Per Spec 006.

  Construction-failure obligation. A RETURNED physical
  state container is GC-OWNED: it must not require an explicit per-container
  teardown verb (there is none — the adapter contract exposes no
  state-container disposal, and the container is reclaimed by GC with the
  dropped frame record). So `make-state-container` must either THROW BEFORE
  returning or return a DISPOSAL-FREE value. `new-frame-record` relies on
  this: when a later partition projection fails, the state container is left
  to GC and only the disposable projections are unwound in reverse order."
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

(defn ^:no-doc read-container-untracked
  "Read `container` exactly as `read-container` would, WITHOUT recording a
  dependency on the enclosing reactive computation. The value is a snapshot:
  a later change to `container` does not re-run the computation that read it.

  For a read made inside a computation it DESCRIBES rather than feeds — trace
  classification reads the frame's elision registry while a sub computes or a
  view renders — so that observing a computation never adds an input to it.

  Uses the installed adapter's optional `:read-container-untracked`
  capability, published at install into the `:adapter/read-container-untracked`
  slot (Spec 006 §`make-derived-value`). A substrate whose reads capture
  dependencies carries it. On every other substrate a read captures nothing,
  so `read-container` is already untracked and this falls back to it when the
  slot is absent.

  On the per-dispatch hot path (the elision registry feeds every dispatch's
  redaction paths), so the slot resolves through `get-fn-cached`."
  [container]
  (if-let [hook (rf.late-bind/get-fn-cached :adapter/read-container-untracked)]
    (hook container)
    (read-container container)))

(def container-class-unknown
  "The answer an adapter's `:derived-container?` capability gives for a
  container it has NO opinion on — distinct from a genuine `false` (\"this
  is one of MY base containers\"). The choke point (`derived-container?`)
  treats this sentinel — and an absent capability (`nil` slot) — as the
  signal to fall back to the host atom-marker heuristic.

  Why a sentinel and not `false`: `false` is the adapter's authoritative
  \"this is one of my base containers\" answer, on which the choke point
  delegates the write and skips its atom-marker arm. An adapter answering
  `false` for a container it does not recognise would have that write
  delegated unchecked; the sentinel hands such a container to the heuristic
  instead, so \"no opinion\" (use the heuristic) stays distinguishable from
  \"installed adapter says base\" (trust it; skip the heuristic).

  Public so any adapter's `:derived-container?` can answer it."
  ::container-class-unknown)

(defn- base-atom-shaped-container?
  "True when `container` is a base, writable atom-shape: it satisfies the
  host's atom marker protocol — `clojure.lang.IAtom` on the JVM,
  `cljs.core/IAtom` on CLJS. The host-uniform fall-back discriminator for
  `derived-container?` when the installed adapter has NO opinion on the
  container's class (its map carries no `:derived-container?`, or that
  capability answers the `container-class-unknown` sentinel).

  Why `IAtom`, not `ISwap`/`IReset`: a CLJS `cljs.core/Atom` implements
  `IAtom` but NOT `ISwap`/`IReset` — `swap!` / `reset!` fast-path on
  `(instance? Atom …)` and only dispatch the `ISwap`/`IReset` protocols
  for non-Atom types. So `(satisfies? ISwap (atom 0))` is FALSE; only
  `IAtom` reliably marks a base atom on both hosts.

  Sound ONLY for adapters whose base container IS atom-shaped (plain-atom,
  UIx): their base container is a `clojure.core/atom`
  (an `IAtom`) and their derived value is an `IDeref`-only reify (plain-atom
  JVM), an `IDeref`+`IDisposable` reify (plain-atom CLJS), or
  an `IDeref`+`IWatchable`+`IDisposable` reify (the React spine) — none of
  which is an `IAtom`. NOT sound for the Reagent family (a Reagent
  `Reaction` reifies `IAtom` too) NOR for a custom adapter whose base
  container is a non-atom host shape (a JS class instance, signal/store
  object, or record). Those adapters MUST carry a `:derived-container?`
  capability (the ratom family via `spine/make-ratom-adapter`; a custom
  adapter on its own map) so `derived-container?` consults the adapter and
  never reaches this heuristic for their containers."
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
  is the authority on its own container shapes and declares them through its
  `:derived-container?` capability, published at install into the
  `:adapter/derived-container?` slot (the ratom adapters key it on their
  reaction's substrate disposal protocol — a `Reaction` is disposable, an
  `r/atom` is not; a custom adapter answers from whatever distinguishes its
  own base from its own derived containers). Published at install, it answers
  only while its adapter is installed.

  Three-way reading of the capability, where the installed adapter is the
  authority WHENEVER it has an opinion:

    * hook returns truthy → DERIVED (the installed adapter classifies this
      as one of its `make-derived-value` results) — reject the write.
    * hook returns `false` → BASE (the installed adapter classifies this as
      one of ITS writable base containers) — NOT derived; delegate the
      write. The host atom-marker heuristic is NOT consulted, so a custom
      adapter whose legitimate base container is not `IAtom`-shaped is not
      misclassified.
    * hook returns the `container-class-unknown` sentinel, or the installed
      map carries no `:derived-container?` (`nil` slot) → the installed
      adapter has NO opinion; fall back to the host atom-marker heuristic
      (sound only for adapters whose base container IS atom-shaped —
      plain-atom / UIx — whose derived values are NOT atom-shaped).

  For the ratom family the hook is decisive (truthy for a `Reaction`,
  `false` for a base `r/atom`), so the heuristic is never reached for their
  containers. Plain-atom and UIx carry no `:derived-container?`, so the
  atom-marker arm decides — exactly where it is sound."
  [container]
  (let [hook (rf.late-bind/get-fn :adapter/derived-container?)
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

  Defense-in-depth nil guard: if `container` is nil — e.g. a
  scheduled drain races frame destruction and reaches the per-event :db
  commit after `frame/app-db-container` has started returning nil for the
  destroyed frame — the write is silently skipped and a production-
  survivable `:rf.error/write-after-destroy` rides the always-on error-emit
  axis (EP-0008; default `:recovery :ignored` — the write is
  dropped, the frame is gone, mirroring `:rf.error/frame-destroyed`).
  Without the guard the write would NPE on a background thread. Adapter
  implementations may assume `container` is non-nil; this wrapper is the
  single choke point through which every frame app-db write flows, so
  guarding here covers the router :db commit, drain rollback, flows, epoch
  restore, and SSR write paths in one place.

  Derived-container guard: a derived container (the result
  of `make-derived-value`) supports `read-container` but NOT
  `replace-container!` — partial/whole replacement of a value computed
  from sources is meaningless (per Spec 006 §`make-derived-value`).
  Writing to one is a programmer error. The choke point detects the
  derived container (`derived-container?` — the installed adapter's
  `:derived-container?` capability is authoritative whenever it
  has an opinion (truthy = derived → reject; `false` = one of the adapter's
  base containers → delegate), and the host atom-marker heuristic is the
  fall-back only when the installed adapter has no opinion),
  emits a `:rf.error/derived-container-replaced` trace so error-listeners
  observe it, and throws the canonical thrown-error ex-info (per Spec 009
  §The thrown-error shape) so a `try`/`catch` and
  `(:rf.error/id (ex-data e))` pivot on one vocabulary. The underlying
  adapter `replace-container!` is NOT invoked. Guarding here (rather than
  per-adapter) gives one uniform contract across every reference adapter —
  the same single choke point that carries the nil guard above. A custom
  adapter whose legitimate base container is NOT atom-shaped carries a
  `:derived-container?` (answering `false` for its base) so its writes
  delegate rather than being rejected as derived."
  [container new-value]
  (if (nil? container)
    ;; The nil guard runs BEFORE the adapter lookup (a scheduled drain
    ;; racing frame destruction must not surface a misleading
    ;; no-adapter-installed throw — see `replace-container-nil-container-
    ;; skips-adapter-check`).
    ;;
    ;; EP-0008: this is a SUPPRESSED WRITE — the dropped :db
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
      (when-let [dispatch-on-error! (rf.late-bind/get-fn :error-emit/dispatch-on-error)]
        (dispatch-on-error!
          :rf.error/write-after-destroy
          nil                              ;; no event vector — a dropped write, not a throw on a dispatch
          nil                              ;; no event-id — the write path carries none
          nil                              ;; no frame — it was destroyed (the whole point)
          nil                              ;; no exception — a suppressed write, not a throw
          0                                ;; elapsed-ms
          (rf.interop/now-ms)))               ;; time
      (rf.trace/emit-error! :rf.error/write-after-destroy
                         {:category :rf.error/write-after-destroy
                          :reason   reason
                          :recovery :ignored}))
    ;; Resolve the adapter FIRST so a write before `(rf/init! ...)` or after
    ;; dispose surfaces the uniform `:rf.error/no-adapter-installed` /
    ;; `:rf.error/adapter-disposed` throw — the derived-container
    ;; classification is meaningless without a seated adapter (you cannot say
    ;; what shape a container is for a substrate that isn't installed).
    (let [a (require-adapter! 'rf/replace-container!)]
      (if (derived-container? container)
        (let [reason "replace-container! is not supported on a derived container; derived containers are read-only (per Spec 006 §make-derived-value). Write to the source container(s) the derived value is computed from instead."]
          (rf.trace/emit-error! :rf.error/derived-container-replaced
                             {:category  :rf.error/derived-container-replaced
                              :recovery  :no-recovery
                              :reason    reason})
          (rf.error/throw-error!
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
  §`make-derived-value`.

  Failure-atomicity obligations:

    - INTERNAL failure-atomicity. If `make-derived-value` throws BEFORE it
      returns, it must have removed any watches / host resources it installed
      — a caller (`new-frame-record`) that never received the value cannot
      dispose it, so an un-returned partial allocation must unwind itself.
    - DISPOSABLE result. Every successfully RETURNED derived value is
      disposable through the `re-frame.interop/dispose!` seam (the
      same seam that releases it at normal frame teardown, and the one the
      installed map's `:dispose!` answers on CLJS). This is what lets a
      caller reverse-order-dispose earlier projections when a LATER
      construction step fails."
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
  (let [a (require-adapter! 'ssr/render-to-string)]
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
  backgrounded. Distinct from a test-only `act()` flush: this is a
  production-grade contract surface, not a test helper.

  Optional contract fn — returns nil and is a no-op when the installed
  adapter ships no `:flush-render!`. Three of the five shipped adapter kinds
  install one — Reagent, UIx and Fresco; the last two get it
  from `spine/make-react-adapter`, which wires the React spine's slot
  unconditionally. The other two omit it for one shared reason: plain-atom
  and SSR render without a live React commit, so there is nothing to flush.
  Calling it before `(rf/init! ...)` raises
  `:rf.error/no-adapter-installed` — the
  optional-fn nil return is reserved for `adapter installed, fn absent`,
  not for `no adapter installed at all`."
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
  `:rf.error/no-adapter-installed` — the optional-fn nil
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
  `:rf.error/no-adapter-installed` — the optional-fn nil
  return is reserved for `adapter installed, fn absent`, not for `no
  adapter installed at all`."
  [frame-keyword]
  (let [a (require-adapter! 'rf/register-context-provider)
        f (:register-context-provider a)]
    (when f (f frame-keyword))))
