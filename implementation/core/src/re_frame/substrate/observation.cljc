(ns re-frame.substrate.observation
  "The internal observation port — the six-operation render-phase protocol a
  compiled-view substrate uses over the REAL per-frame sub-cache. Per Spec 006
  §The internal observation port (adapter-internal).

  ADAPTER-INTERNAL. This namespace is NOT public API, NOT part of the closed
  ten-fn adapter contract, and NOT consumable by apps or adapters — its
  consumers are compiled-view runtimes (the ViewCell/commit reconciler), built
  from the same commit as core rather than resolved against a published
  version. The [[port-abi-version]] guard makes a stale build a boot error.

  The six operations:

      (resolve-target site-ctx)     ; render: the ONLY resolution point → target
      (probe target ?slice-memo)    ; render: pure evidence read
      (acquire! target on-change)   ; commit-only: re-resolves canonical node,
                                    ;   +1 owner → handle
      (current? handle target)       ; the commit kept-check, one predicate
      (read handle)                  ; => {:value v :version n …}; typed error
                                    ;   after release
      (release! handle)              ; synchronous, idempotent

  ## Mapping onto the cache contract

  `acquire!` is the ref-count attach of Spec 006 §Lookup algorithm plus
  callback registration; `release!` is the subscriber detach of §Reference
  counting and disposal (identity-guarded, so a node disposed-and-rebuilt out
  from under a handle is never double-decremented); `probe` is an
  ownership-free read with no prior public name (`subscribe-once`
  attaches-and-detaches; `probe` never attaches). `resolve-target` and
  `current?` are the capture and kept-check layer a concurrent host requires.

  ## Ownership discipline (the six frozen invariants)

  Render resolves and probes WITHOUT ownership: `probe` takes no ref-count,
  registers no watch or callback, and materialises no cache node — a COLD
  probe (no live node) computes pure against the frame's current frame-state
  snapshot through the slice-scoped memo and retains NOTHING (the 10k-cold-
  probes-retain-zero fixture pins it). Ownership is commit-only: `acquire!`
  bumps the cache ref-count, registers the per-handle change watch (on hosts
  whose derived values are watchable), and enrols the handle as an active owner
  behind the node's single, once-installed disposal hook (`release!` de-enrols
  it, so a released handle retains no dormant closure — rf2-vxgfnd.15).

  ## Evidence, versions, and epochs

  Probe evidence and `read` carry three movement signals the commit-side
  evidence comparison (invariant 5) uses:

    - `:node-version` — a per-node counter this port advances whenever it
      OBSERVES the node's value change by `rf=` (at probe/acquire/read and on
      watch fires). Node bookkeeping lives in a WEAK identity-keyed side
      table (`js/WeakMap` / `java.util.WeakHashMap`) keyed by the reaction, so
      a record dies with its reaction — no pruning, no retention. The record's
      VALUE must never transitively STRONG-reference its own reaction key:
      `java.util.WeakHashMap` is NOT an ephemeron map (a value→key path pins
      the key forever), so the owner handles in `:owners` hold their reaction
      WEAKLY on the JVM (rf2-vxgfnd.37). `js/WeakMap` HAS ephemeron semantics,
      so CLJS needs no such care.
    - `:frame-epoch` — `re-frame.frame/frame-commit-epoch`: bumped once per
      physical frame-state install. Any durable-state movement in the
      render→commit gap moves it, so a version tie on a multi-move gap is
      still caught (the belt-and-braces the two-guard rule leans on).
    - `:registry-epoch` — bumped on every `:sub` registration (first-time or
      replacement), so an HMR re-registration in the gap is visible.

  `read` on a node handle additionally returns the node's `:node-key` (its
  process-unique IDENTITY — the SAME key probe emits) and the CURRENT
  `:frame-epoch` / `:registry-epoch` (additive keys — the frozen
  `{:value v :version n}` contract is unchanged) so the commit reconciler gets
  its step-5 comparison inputs from the one acquire-time read instead of a
  second probe. Carrying `:node-key` lets the reconciler distinguish a same-id
  frame REINCARNATION (destroy + recreate mints a fresh reaction, so a
  strictly-greater node-key) from an unmoved live node EVEN WHEN node-version
  and frame/registry epochs coincide across the two incarnations — a
  version+epoch tie `frame/dissoc-frame!`'s commit-epoch restart can produce,
  which the version+epoch-only comparison would misread as unchanged
  (rf2-vxgfnd.14).

  ## Error contract — internally fail-loud

  Every operation throws typed via `re-frame.error/throw-error!`:
  `:rf.error/no-such-sub` (unknown ENTRY sub at probe/acquire — the same
  catalogue id the public surface records; in-graph input resolution keeps
  the graph's own nil-substituting behaviour), `:rf.error/frame-destroyed`
  (probe/acquire against a destroyed frame), `:rf.error/read-after-release`
  (always — a substrate bug), `:rf.error/reentrant-graph-op` (dev-asserted —
  acquire!/release! from inside the owner-notification fan-out). The
  always-on categories ALSO fan a tight record through the production
  error-emit axis (surface #4) before throwing, so a boundary-swallowed
  throw still reaches off-box shippers. The PUBLIC read API
  (`subscribe`/`subscribe-once`) keeps its recover-to-nil semantics — one
  condition, one catalogue id, two surfaces.

  ## Host honesty — the watch channel

  Value-movement `on-change` notifications ride a per-handle `add-watch` on
  the cache node's derived value, and therefore exist exactly where the
  substrate's derived values are watchable (the Reagent family and the React
  spine). The headless hosts (plain-atom JVM/CLJS, test-react) ship
  IDeref-only derived values with no reactive commit loop, so movement there
  is detected at the port's read points (the commit evidence comparison) —
  the honest headless posture, documented rather than simulated.

  ## HMR-disposal notifications

  The cause a former owner is told is INTRINSIC to why its node died, not
  decided by which drain boundary fires (rf2-r8jmdb): each queued entry is a
  `[handle cause]` pair, the cause captured at enqueue time from the disposing
  cache site's `re-frame.subs.cache/*disposal-cause*` (`:hot-reload` → `:hmr`;
  frame-destroy / cache-clear / last-derefer → `:disposed`). Sub re-registration
  disposes the canonical node then notifies former owners ONCE with cause
  `:hmr`: the reaction's dispose hook ENQUEUES the live handles tagged `:hmr`, and
  the queue drains at the notification boundary the re-registration closes (this
  ns's registrar replacement hook — registered AFTER `re-frame.subs.cache`'s
  invalidation hook by require order, so the drain runs once the registry
  mutation + cache eviction completed, never mid-mutation), coalesced once per
  handle and taking ONLY the `:hmr`-tagged entries. Non-registrar disposal paths
  (frame-destroy, explicit cache clears) tag their handles `:disposed` and drain
  on the next tick — so a `:disposed` handle still pending when an unrelated `:sub`
  re-registration drains is left for its own boundary, never swept into the
  `:hmr` drain and mislabelled.

  Readiness is published HONESTLY (rf2-vxgfnd.70): the node record's
  `:hook-installed?` flag is set only AFTER `interop/add-on-dispose!` has
  actually registered the callback, never as the handle merely enrols. So a
  fresh follower — whose `register-owner!` reads that flag — can never observe a
  ready hook before the hook exists. A concurrent follower whose enrolment
  interleaves the install window (flag still unset) is told to install too: it
  registers its OWN node-scoped disposal hook, an independent fallback that
  observes disposal without waiting for the first owner to finish. Duplicate
  hooks are harmless because `take-owners!` is the single-drain point — the
  first to fire snapshots-and-clears the owners, the rest no-op — so the node
  still fans out O(current owners) exactly once, and steady state (every later
  owner a cache HIT behind a confirmed hook) keeps ONE hook per node.

  `acquire!` still closes the disposed-before-my-install window with a
  CANONICALITY RE-CHECK (rf2-vxgfnd.32): because every disposal path evicts the
  cache entry before `interop/dispose!`, a reaction that is no longer the
  frame's canonical node was disposed in the window — the staged owners are
  self-drained and enqueued exactly once (`take-owners!` is the single-drain
  point), so no acquired handle is ever left behind an uninstalled/dead hook
  without its invalidation. If the install itself throws, the owner tears
  itself down (ref/watch/enrolment balanced) and the exception propagates
  through acquire!'s fail-loud path, leaving readiness unpublished so the node
  is never poisoned.

  The same eviction-before-dispose fact means a node the build itself just
  installed can be DISPLACED — invalidated-and-rebuilt to a newer canonical node
  — inside `subs/build-and-classify!`'s build→canonical-check window while the
  frame stays LIVE (a concurrent HMR re-registration or explicit cache clear).
  `build-and-classify!` maps that to a `:frame-destroyed` recovery via its `:else`
  fallback, conflating a normal displacement with a teardown. `acquire!`
  disambiguates against the targeted frame's incarnation token
  (`frame/frame-incarnation-token`, captured while the frame is verified live):
  a still-live incarnation means the node was merely displaced, so `acquire!`
  retargets to the current canonical node by re-running the acquire (bounded
  retry, gated on incarnation liveness); only a nil/changed incarnation is a
  verified destruction of the targeted incarnation and throws
  `:rf.error/frame-destroyed` (rf2-vxgfnd.63)."
  (:refer-clojure :exclude [read])
  (:require [re-frame.error :as error]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.interop :as interop]
            [re-frame.live-frame :as live-frame]
            [re-frame.registrar :as registrar]
            [re-frame.subs :as subs]
            [re-frame.subs.cache :as subs-cache]))

#?(:clj (set! *warn-on-reflection* true))

;; ---- channel-aware, throwable-bound emission provenance (rf2-w55bh0 + rf2-9m4oy7)
;;
;; Several fail-loud port surfaces EMIT their category's canonical record and
;; THEN throw the matching typed error (the emit-then-throw idiom): the ABI
;; guard, the shared `emit-and-throw!` sites (frame-destroyed / no-such-sub /
;; retry-exhausted), and two `throw-acquire-recovery!` arms whose category the
;; sub BUILD already surfaced. A containment drain that CATCHES such a throwable
;; to keep notifying siblings (the disposal-notification drain below) must decide
;; whether PRODUCTION observability (the always-on error-emit axis) is already
;; covered for that one runtime error, so it neither double-reports an
;; already-fanned failure (Spec 009's one-runtime-error law) nor lets a
;; production-elided failure go silent.
;;
;; The attestation is CHANNEL-AWARE and THROWABLE-BOUND:
;;
;;   - CHANNEL-AWARE — it records WHICH error channel(s) the source actually
;;     emitted on, never a single "fanned" Boolean. A source that fanned through
;;     `error-emit/emit-error-both!` covered `#{:always-on :trace}`; a source
;;     whose only emission was the sub build's DIAGNOSTIC `:rf.error/sub-cycle`
;;     trace covered `#{:trace}` — production-elided, so the always-on axis is
;;     NOT covered. Collapsing the two to one Boolean is exactly the bug that made
;;     a cyclic-acquire callback failure vanish under `goog.DEBUG=false`: the
;;     drain mistook trace coverage for full coverage and emitted nothing.
;;   - THROWABLE-BOUND / NON-FORGEABLE (rf2-9m4oy7) — the [[EmissionProvenance]]
;;     token is bound to the EXACT throwable it attests through a PRIVATE weak
;;     identity association (the reload-safe [[provenance-storage]] holder on the
;;     JVM, a `provenance-by-throwable` `js/WeakMap` on CLJS), keyed by the throwable
;;     OBJECT and written ONLY by [[attest-provenance!]] here. It is NOT carried
;;     in the thrown ex-data. This closes two spoof vectors an ex-data token
;;     could not: (a) an application `on-change` can call the cross-namespace
;;     `->EmissionProvenance` constructor (a deftype generates a callable
;;     factory) and stuff a passing token into its OWN ex-data — but the drain
;;     never reads ex-data, and the app cannot write the private weak map, so the
;;     forged token attests nothing; (b) an application can copy an AUTHENTIC
;;     token out of a caught framework throwable's ex-data into an unrelated
;;     exception — but provenance lives keyed by the EXACT original throwable, so
;;     a DIFFERENT throwable is simply absent from the association. Only the
;;     throwable observation itself minted-and-bound reads covered. Token opacity
;;     is no longer the defense (it is belt-and-braces); the private,
;;     throwable-keyed seam is. This REPLACES the reconstructible
;;     `error-emit/fanned-at-source-key` (a public namespaced key whose literal
;;     value was `true`) and the `:rf.error/id`-plus-`:reason` shape test the
;;     drain used to trust, AND the forgeable/transplantable ex-data carriage the
;;     interim rf2-w55bh0 token used.
;;
;; The drain reads coverage through [[source-covered-always-on?]], which looks
;; the caught throwable up in the private weak association; a throwable with NO
;; binding — a diagnostic-only thrown category
;; (`:rf.error/observation-malformed-target` / `…-malformed-handle` /
;; `:rf.error/reentrant-graph-op`), a raw untyped consumer bug, a forged or
;; transplanted token, or any application spoof — reads FALSE, so the drain owns
;; always-on coverage and wraps it in the stable
;; `:rf.error/observation-on-change-failed`, NEVER promoting a diagnostic-only
;; category onto the always-on axis.

;; The channel-aware attestation token — a host object whose `channels` field is
;; the set of axes the source already covered (`#{:always-on :trace}` /
;; `#{:trace}`). Bound to the EXACT throwable it attests through the private weak
;; association below (rf2-9m4oy7), NEVER carried in the thrown ex-data. Its
;; opacity is belt-and-braces; the throwable-keyed private seam is the defense.
(deftype EmissionProvenance [channels])

(def ^:private provenance-both-channels
  "Provenance for a source that fanned through `error-emit/emit-error-both!` —
  the always-on record AND the dev trace event both covered."
  (->EmissionProvenance #{:always-on :trace}))

(def ^:private provenance-trace-only
  "Provenance for a source whose only emission rode the DIAGNOSTIC trace axis
  (the sub build's `:rf.error/sub-cycle`) — production-elided, so the always-on
  axis is NOT covered and a containment drain still owes production a record."
  (->EmissionProvenance #{:trace}))

;; The PRIVATE weak throwable→provenance association (rf2-9m4oy7 + rf2-fs99nq).
;; Provenance is bound to the EXACT throwable it attests — keyed by the throwable
;; OBJECT IDENTITY, WEAK and self-pruning so an entry lives exactly as long as its
;; throwable (a caught-and-processed throwable's binding is reclaimed the moment the
;; throwable is — never a growing global registry, the bead's explicit non-goal).
;; Written ONLY by [[attest-provenance!]] from this ns's own emit-then-throw sites;
;; an application cannot reach it, so a forged `->EmissionProvenance` token in an
;; app's ex-data, or an authentic token transplanted onto a DIFFERENT throwable, is
;; never associated here and reads uncovered.
;;
;; GENUINE IDENTITY, NOT `.equals`/`.hashCode` (rf2-fs99nq): `Throwable` leaves
;; `hashCode` and `equals` VIRTUAL — a throwable is identity-keyed ONLY while it
;; inherits Object's defaults, which an APPLICATION throwable is free to OVERRIDE. So
;; a raw `java.util.WeakHashMap` keyed by the throwable is NOT genuine identity: it
;; keys by `.equals`/`.hashCode`, so two distinct application throwables that are
;; `.equals` (or one with a forged `hashCode`) COLLIDE, and its `.get` returns the
;; bound throwable's provenance for a DIFFERENT one — the drain then reads a raw
;; application exception as already-covered and SUPPRESSES its required
;; `:rf.error/observation-on-change-failed` record; worse, an application
;; `hashCode`/`equals` that THROWS makes provenance lookup itself escape the drain's
;; catch and abort the reduce before healthy siblings drain (breaking the full-drain
;; / first-original-identity law). On the JVM the association is therefore a small
;; ReferenceQueue-backed weak map whose keys hash by `System/identityHashCode` and
;; compare by referent `identical?` — NEVER invoking the throwable's own
;; `hashCode`/`equals` — with cleared entries reclaimed on each access. `js/WeakMap`
;; is genuinely identity-keyed (reference keys, ephemeron semantics) with no such
;; hazard, so CLJS keeps it unchanged.
;; ---- RELOAD-SAFE private provenance storage (rf2-qqvgk1) --------------------
;;
;; On the JVM the storage is a VERSIONED HOLDER — a plain Clojure map
;; `{:version N :by-throwable <HashMap> :queue <ReferenceQueue>}` — `defonce`'d so
;; an ordinary same-version namespace reload KEEPS the live entries, but reconciled
;; at load ([[ensure-current-provenance-storage!]]) so a reload over an INCOMPATIBLE
;; predecessor root is RECOGNIZED and REPLACED rather than silently mis-operated.
;; This closes an HMR/REPL upgrade hazard the bare-`defonce` shape carried: the
;; predecessor representation (rf2-9m4oy7) was a synchronized `WeakHashMap<Throwable>`
;; under this same Var; a plain `defonce` retains that old root across a reload onto
;; the current weak-identity code, so existing raw-Throwable entries are unreachable
;; through the new `WeakReference` lookup key AND a freshly-`defonce`'d queue desyncs
;; from the retained map. Bundling the map + queue in ONE holder makes them
;; impossible to desync, and the version tag makes an incompatible predecessor
;; detectable.
;;
;; The version carrier is a plain Clojure MAP, NOT a `deftype`, ON PURPOSE: a
;; namespace reload REDEFINES a `deftype`'s class, so an `instance?`-based
;; recognition would read false on EVERY reload and drop the entries; a Clojure
;; map's class (`PersistentArrayMap`) is stable across reloads, so version
;; recognition is stable and repeated reloads stay idempotent.
;;
;; A `WeakReference`-subclass identity-key ([[weak-identity-key]]) → a RELOAD-STABLE
;; channel SET (rf2-kia9st). The inner `by-throwable` is a plain `HashMap` guarded by
;; `locking` on that map (the node-records lock discipline); NOT a `WeakHashMap` —
;; weakness comes from the `WeakReference` KEYS + ReferenceQueue expunge, IDENTITY
;; from the keys' own `hashCode`/`equals`. `js/WeakMap` on CLJS is genuinely
;; identity-keyed with ephemeron semantics and NO reload hazard (a page reload is a
;; fresh JS realm), so CLJS keeps the bare `defonce` unchanged.
;;
;; The stored VALUE on the JVM is the [[EmissionProvenance]] token's raw channel SET
;; (`#{:always-on :trace}` / `#{:trace}`), NOT the deftype instance (rf2-kia9st). The
;; SAME reason the version carrier is a plain map, not a deftype, applies to the
;; stored value: a namespace reload REDEFINES the deftype's class, so a retained
;; old-class `EmissionProvenance` VALUE reads `(instance? EmissionProvenance …)` FALSE
;; after a same-version reload and its throwable silently flips covered→uncovered.
;; A persistent set's class (`PersistentHashSet`) and its interned keyword members are
;; stable across reloads, so an entry attested before a reload stays covered after it.
;; Two operations reading the map + queue through SEPARATE Var reads could also pair
;; one holder's map with another holder's queue if a reload reconciliation interleaved
;; between them; [[provenance-holder]] is the single coherent snapshot that closes that
;; desync. CLJS stores the token unchanged.
#?(:clj (def ^:private provenance-storage-version
          ;; Representation version of the private JVM provenance storage. BUMP when
          ;; the holder shape OR its stored VALUE representation changes, so a reload
          ;; over an older root REPLACES it rather than mis-operating on retained
          ;; entries.
          ;;
          ;; v3 (rf2-b0afn) = the weak-identity `HashMap<WeakReference-key>` +
          ;; `ReferenceQueue` holder whose stored VALUES are the reload-stable raw
          ;; channel SETs (`#{:always-on :trace}` / `#{:trace}`) (rf2-kia9st).
          ;;
          ;; v2 was the SAME holder shape but stored the `EmissionProvenance` DEFTYPE
          ;; INSTANCE as each value (pre-rf2-kia9st / #6047). The current set-shaped
          ;; reader [[source-covered-always-on?]] calls `contains?` DIRECTLY on the
          ;; stored value, which THROWS `IllegalArgumentException` on a retained
          ;; deftype instance — so a preserved v2 holder is a MID-READ throw hazard,
          ;; NOT a compatible predecessor. #6047 changed the value shape but left the
          ;; version at 2, so an old-v2→new-v2 upgrade read the pre-upgrade holder
          ;; current and preserved its deftype-valued entries. Bumping 2→3 makes the
          ;; value-shape upgrade an INCOMPATIBLE-holder transition: a v2 root reads
          ;; uncurrent and is REPLACED (its deftype-valued entries dropped; any
          ;; in-flight predecessor throwable reads uncovered so the disposal drain
          ;; fails LOUD — an extra catalogued `:rf.error/observation-on-change-failed`
          ;; record), never silently `contains?`-thrown mid-read.
          ;;
          ;; v1 (conceptual) was a raw synchronized `WeakHashMap<Throwable>` with NO
          ;; version tag, so it too reads uncurrent and is replaced.
          3))

#?(:clj
   (defn- fresh-provenance-storage
     "A fresh CURRENT-version provenance storage holder: an empty identity-keyed
     `HashMap` paired with its OWN `ReferenceQueue`, tagged with the current
     representation version (rf2-qqvgk1)."
     []
     {:version      provenance-storage-version
      :by-throwable (java.util.HashMap.)
      :queue        (java.lang.ref.ReferenceQueue.)}))

#?(:clj
   (defn- current-provenance-storage?
     "True when `s` is a CURRENT-version provenance storage holder — a Clojure map
     tagged with [[provenance-storage-version]]. A predecessor raw `WeakHashMap`
     (`map?` false) reads false; so does an older-version holder — INCLUDING the
     immediate-predecessor v2 holder whose stored VALUES are `EmissionProvenance`
     deftype instances rather than raw channel sets (rf2-b0afn): same holder shape,
     but its retained values would `contains?`-throw in [[source-covered-always-on?]],
     so it is treated as INCOMPATIBLE. The load-time reconciliation replaces any such
     root (rf2-qqvgk1 + rf2-b0afn)."
     [s]
     (and (map? s) (= provenance-storage-version (:version s)))))

#?(:clj
   (defonce ^:private provenance-storage (fresh-provenance-storage))
   :cljs
   (defonce ^:private provenance-by-throwable (js/WeakMap.)))

#?(:clj
   (defn- ensure-current-provenance-storage!
     "Reconcile the `defonce`'d [[provenance-storage]] root to the current
     representation version. Idempotent + atomic — an `alter-var-root` with a pure
     compare-and-replace: a fresh JVM keeps the `defonce` initializer's current
     storage; a RELOAD over an incompatible predecessor / older-version root REPLACES
     it with a fresh current holder; a SAME-version reload is a NO-OP that preserves
     every live entry (and the SAME holder object, so repeated reloads converge).

     The explicit private reload rule (rf2-qqvgk1): a same-version reload PRESERVES
     entries; a version-mismatch REPLACE drops predecessor-bound provenance, so any
     in-flight predecessor throwable reads UNCOVERED — the disposal drain then fails
     LOUD (an extra catalogued `:rf.error/observation-on-change-failed` record),
     never silent or corrupt. Runs on EVERY ns load (not `defonce`-guarded), so
     reverting to a bare `defonce` of the map is a detectable regression: the reload
     fixture, which seeds a predecessor root and re-runs this, reddens."
     []
     (alter-var-root #'provenance-storage
                     (fn [s] (if (current-provenance-storage? s) s (fresh-provenance-storage))))
     nil))

;; Recognize + replace an incompatible predecessor / older-version root on load.
#?(:clj (ensure-current-provenance-storage!))

#?(:clj
   (defn- provenance-holder
     "ONE coherent snapshot of the reconciled provenance storage holder — a SINGLE
     read of the `provenance-storage` Var (rf2-kia9st). Every attest / lookup /
     expunge operation binds this ONCE and pulls BOTH its `:by-throwable` map and
     its paired `:queue` ReferenceQueue from the returned value, NEVER through two
     separate Var reads. `alter-var-root` publishes a holder replacement
     atomically, so a single read always sees ONE complete holder — even if a
     reload reconciliation ([[ensure-current-provenance-storage!]]) runs
     concurrently or reentrantly. This closes the desync where the map came from
     one holder and the queue from another: a key registered on holder B's queue
     but installed in holder A's map is never enqueued to A's queue, so A never
     expunges it (a weak leak) and it is undiscoverable from the live holder if B
     wins. Callers must not re-read the Var mid-operation."
     []
     provenance-storage))

#?(:clj
   (defn- weak-identity-key
     "Wrap throwable `t` as a map key with genuine IDENTITY hash/equality that NEVER
     touches `t`'s own `hashCode`/`equals` (rf2-fs99nq): a `WeakReference` subclass
     whose `hashCode` is the captured `System/identityHashCode` and whose `equals` is
     referent `identical?`. A non-nil `queue` registers the key for reclamation (the
     STORED key); a nil `queue` makes a transient LOOKUP key. `equals` short-circuits
     on `identical? this o` (so expunging a cleared key never dereferences it) and
     otherwise compares referents by identity, treating a cleared referent as no
     match."
     ^java.lang.ref.WeakReference [t ^java.lang.ref.ReferenceQueue queue]
     (let [h (System/identityHashCode t)]
       (proxy [java.lang.ref.WeakReference] [t queue]
         (hashCode [] h)
         (equals [o]
           (or (identical? this o)
               (and (instance? java.lang.ref.WeakReference o)
                    (let [r (.get ^java.lang.ref.WeakReference this)]
                      (and (some? r)
                           (identical? r (.get ^java.lang.ref.WeakReference o)))))))))))

#?(:clj
   (defn- expunge-stale-provenance!
     "Reclaim entries whose throwable has been collected (rf2-fs99nq): poll the
     ReferenceQueue `q` for cleared keys and drop each from the map `m`. Removal
     matches the EXACT enqueued key by identity (`equals` short-circuits on
     `identical? this o`), so a cleared referent is never dereferenced and the
     throwable's own `hashCode`/`equals` are never invoked. `m` + `q` are the paired
     members of the one [[provenance-storage]] holder (rf2-qqvgk1), so they never
     desync. Call under `locking` on `m`."
     [^java.util.Map m ^java.lang.ref.ReferenceQueue q]
     (loop []
       (when-some [k (.poll q)]
         (.remove m k)
         (recur)))))

(defn- attest-provenance!
  "Bind `provenance` to the EXACT throwable `t` in the private weak association,
  then return `t` so an emit-then-throw site reads
  `(throw (attest-provenance! (error/thrown-ex-info …) provenance-both-channels))`
  (rf2-9m4oy7). The binding — never public ex-data — is what
  [[source-covered-always-on?]] reads: only the throwable observation itself
  minted-and-bound is covered. A no-op-safe identity write; the entry dies with
  the throwable."
  [t provenance]
  #?(:clj  (let [holder (provenance-holder)      ;; ONE coherent snapshot (rf2-kia9st)
                 m      ^java.util.Map (:by-throwable holder)
                 q      ^java.lang.ref.ReferenceQueue (:queue holder)]
             (locking m
               (expunge-stale-provenance! m q)
               ;; Store under a WEAK IDENTITY key (rf2-fs99nq): keyed by t's object
               ;; identity, never its own hashCode/equals; the key is registered on
               ;; THIS holder's ReferenceQueue so the entry is reclaimed once t is
               ;; collected. Map + queue come from the one `holder` snapshot, so the
               ;; key can never be registered on a different holder's queue than the
               ;; map it lands in (rf2-kia9st).
               ;;
               ;; The stored VALUE is the RELOAD-STABLE channels SET, NOT the
               ;; `EmissionProvenance` deftype instance (rf2-kia9st): a real
               ;; namespace reload REDEFINES the deftype's class, so a retained
               ;; old-class instance reads `(instance? EmissionProvenance …)` FALSE
               ;; afterward and its throwable flips covered→uncovered — an
               ;; exact-once-coverage violation. A plain persistent set's class
               ;; (`PersistentHashSet`) and its interned keyword members are stable
               ;; across reloads, so the retained value still reads correctly. CLJS
               ;; stores the token unchanged (a page reload is a fresh JS realm).
               (.put m (weak-identity-key t q) (.-channels ^EmissionProvenance provenance))))
     :cljs (.set provenance-by-throwable t provenance))
  t)

(defn- source-covered-always-on?
  "True when caught throwable `t` was minted-and-bound by THIS port with a
  channel-aware [[EmissionProvenance]] attesting its canonical record was ALREADY
  fanned on the ALWAYS-ON axis at the source (with the source's own attribution),
  so a containment drain must add nothing there. NON-FORGEABLE by binding to the
  EXACT throwable identity through the PRIVATE weak provenance association (the
  reload-safe [[provenance-storage]] holder on the JVM, a `js/WeakMap` on CLJS)
  (rf2-9m4oy7): an application cannot write that association, so a
  forged `->EmissionProvenance` token in its ex-data reads false; and provenance
  is keyed by the exact original throwable, so an authentic token TRANSPLANTED
  onto a different exception reads false (that different throwable has no binding).
  Channel-aware — a source that emitted ONLY on the diagnostic trace axis reads
  false, so trace coverage is never mistaken for always-on coverage
  (rf2-w55bh0)."
  [t]
  ;; `channels` is the reload-stable channel SET the source covered, or nil when t
  ;; has no binding. On the JVM the stored VALUE already IS that set (rf2-kia9st);
  ;; on CLJS the stored value is the `EmissionProvenance` token, so its channels are
  ;; extracted here (belt-and-braces `instance?` — an app cannot write the private
  ;; WeakMap anyway).
  (let [channels
        #?(:clj  (let [holder (provenance-holder)      ;; ONE coherent snapshot (rf2-kia9st)
                       m      ^java.util.Map (:by-throwable holder)
                       q      ^java.lang.ref.ReferenceQueue (:queue holder)]
                   (locking m
                     (expunge-stale-provenance! m q)
                     ;; A transient IDENTITY LOOKUP key (nil queue) — HashMap.get
                     ;; discriminates by our key's `System/identityHashCode` +
                     ;; referent `identical?`, NEVER t's own hashCode/equals
                     ;; (rf2-fs99nq). So a distinct application throwable that is
                     ;; `.equals`/hash-collides with a bound one reads uncovered,
                     ;; and a throwable whose hashCode/equals THROWS cannot make
                     ;; this lookup escape the drain's catch. Map + queue are the
                     ;; paired members of the ONE `holder` snapshot (rf2-kia9st).
                     (.get m (weak-identity-key t nil))))
           ;; `WeakMap.prototype.get` returns undefined (never throws) for a
           ;; non-object key, so a raw thrown value (CLJS permits
           ;; `(throw :kw)` / `(throw "x")`) simply reads uncovered — NO guard
           ;; is needed here. `js/WeakMap` is genuinely identity-keyed (reference
           ;; keys), so CLJS carries none of the JVM `.equals`/`.hashCode`
           ;; collision hazard (rf2-fs99nq). In particular do NOT guard with cljs
           ;; `object?`: it is `(identical? (.-constructor x) js/Object)`, which
           ;; is FALSE for an `ExceptionInfo` (its constructor is not
           ;; `js/Object`), so it would wrongly exclude every real framework
           ;; throwable and make the covered path never fire on CLJS.
           :cljs (let [prov (.get provenance-by-throwable t)]
                   (when (instance? EmissionProvenance prov)
                     (.-channels ^EmissionProvenance prov))))]
    (boolean (and channels (contains? channels :always-on)))))

;; ---- ABI version guard -----------------------------------------------------

(def port-abi-version
  "Integer ABI version of this port. A consumer records the version it compiled
  against and asserts it at load via [[assert-port-abi-version!]], failing
  loudly on skew — a stale build is a boot error, never undefined behaviour.
  Per Spec 006 §The internal observation port §Scope.

  v2 (rf2-vxgfnd.14): `read` on a node handle additionally carries `:node-key`
  — the acquired node's process-unique IDENTITY (the same key `probe` already
  emits) — so the S2b commit reconciler detects a same-id frame REINCARNATION
  across the render→commit gap even when node-version and frame/registry
  epochs coincide (a version+epoch tie `frame/dissoc-frame!`'s commit-epoch
  restart can produce). The reconciler's evidence comparison relies on it, so
  a consumer from v2 onward REQUIRES a core that emits it — the guard makes an
  older core a boot error rather than a silently-missed correction."
  2)

(defn assert-port-abi-version!
  "Boot guard for a port consumer: throw `:rf.error/observation-port-
  version-mismatch` (always-on; also fanned through the production error-emit
  axis) when `expected` ≠ [[port-abi-version]]. Returns nil on a match."
  [expected]
  (when (not= expected port-abi-version)
    (let [reason (str "an observation-port consumer was compiled against "
                      "observation-port ABI version " (pr-str expected)
                      " but this core exports " port-abi-version
                      "; this port is adapter-internal and its consumers are "
                      "never resolved independently of core — rebuild the "
                      "consumer against this core (an in-tree consumer must be "
                      "built from the same commit; a published one ships on "
                      "core's lockstep release train).")]
      (error-emit/emit-error-both!
        :rf.error/observation-port-version-mismatch
        nil nil nil nil 0 (interop/now-ms)
        {:where    're-frame.substrate.observation/assert-port-abi-version!
         :expected expected
         :actual   port-abi-version
         :reason   reason
         :recovery :no-recovery})
      (throw
        (attest-provenance!
          (error/thrown-ex-info
            :rf.error/observation-port-version-mismatch
            're-frame.substrate.observation/assert-port-abi-version!
            reason
            {:extra {:expected expected
                     :actual   port-abi-version}})
          ;; Throwable-bound emission provenance (rf2-9m4oy7): the
          ;; emit-error-both! record above covered BOTH axes, so a containment
          ;; drain that catches THIS exact throwable sees always-on coverage and
          ;; re-fans nothing.
          provenance-both-channels))))
  nil)

;; ---- registry epoch ---------------------------------------------------------

(defonce ^:private registry-epoch*
  ;; Monotonic count of `:sub` registrations (first-time AND replacement) —
  ;; the `:registry-epoch` evidence axis. Bumped ONCE per `:sub` registration by
  ;; the registrar hooks installed at the bottom of this ns: a FIRST-TIME
  ;; registration bumps on the registration hook; a RE-REGISTRATION bumps on the
  ;; replacement hook BEFORE the `:hmr` drain runs — so the disposal notification
  ;; reports the SAME (post-bump) epoch the next probe will read, never a stale
  ;; pre-bump value the consumer would misread as phantom registry movement
  ;; (rf2-vxgfnd.36).
  (atom 0))

;; ---- reentrancy guard (dev) -------------------------------------------------

(def ^:dynamic ^:private *in-owner-fan-out?*
  "True while this port is synchronously invoking owner `on-change`
  callbacks (the owner-notification fan-out). `acquire!`/`release!` from
  inside it throw `:rf.error/reentrant-graph-op` (dev-asserted). Bound only
  under `interop/debug-enabled?`, so production builds carry neither the
  binding nor the check (the #5704 dev-only-machinery-must-DCE idiom).
  React-driven acquire/release — the calls inside the layout COMMITS the
  owner-notification schedules via `mark-dirty` (flushed at a later pending
  host checkpoint, coalesced across a batch and decoupled from epoch count)
  — run after the fan-out returns and never see it. Ownership moves in
  commits only; a render probes without acquiring (see `acquire!` below),
  so naming renders here would contradict this port's own contract."
  false)

(defn- assert-not-in-fan-out!
  [where]
  (when interop/debug-enabled?
    (when *in-owner-fan-out?*
      (error/throw-error!
        :rf.error/reentrant-graph-op
        where
        (str where " was called from inside the observation-port "
             "owner-notification fan-out; graph ownership must not mutate "
             "mid-notification — defer the acquire/release to the commit the "
             "notification schedules (mark-dirty is constant-work by "
             "contract).")
        {:recovery :no-recovery}))))

(defn- fan-out!
  "Invoke one owner `on-change` callback with `payload`, marking the
  owner-notification fan-out for the dev reentrancy assert. In production
  the marker binding constant-folds away (`interop/debug-enabled?` is a
  compile-time constant on CLJS)."
  [on-change payload]
  (if interop/debug-enabled?
    (binding [*in-owner-fan-out?* true]
      (on-change payload))
    (on-change payload)))

;; ---- G-13 candidate-work witness (rf2-vxgfnd.250; DEV-ONLY, DCE'd in prod) ---
;;
;; A production-erased witness at the port's per-candidate inspection entry
;; points (`probe` / `read` / `current?` — the ops the compiled-view commit
;; reconciler calls once per candidate site it processes). It counts REAL
;; candidate inspections so the G-13 gate can CAUSALLY prove the invalidation /
;; commit path inspects work proportional to the affected set C, not the mounted
;; total V: a source mutant that scans all V mounted cells — even one that
;; ENROLLS / DELIVERS to only C, so every delivered-work count (rf2-vxgfnd.210's
;; `port-fan-out`) stays identical — must route its extra inspections through
;; these same port entries, inflating the witness past f(C) and turning G-13
;; deterministically red. That is the original rf2-vxgfnd.210 criterion 1 the
;; delivered-work projection provably cannot see.
;;
;; DEBUG-GATED / PRODUCTION-ERASED: every increment sits behind
;; `interop/debug-enabled?` (a compile-time constant under CLJS `:advanced` +
;; `goog.DEBUG=false`, the same idiom `fan-out!` uses above), so the counter
;; atoms, the increments, and the `RF2_G13_PORT_CANDIDATE_SENTINEL` that binds
;; the witness to its owning branch all DCE out of ordinary builds — never a
;; runtime-global counter, never port ABI, never production bookkeeping. The
;; G-13 advanced-bundle scan proves the sentinel absent; the `console.debug`
;; side effect (latched to one emit, off the hot path) binds the token to this
;; executable branch so the scan's absence is CAUSAL, not incidental.
(def ^:private g13-candidate-sentinel "RF2_G13_PORT_CANDIDATE_SENTINEL")

(defonce ^:private g13-candidate-counts (atom {:probe 0 :read 0 :current? 0}))
(defonce ^:private g13-candidate-witnessed? (atom false))

(defn- note-candidate-inspection!
  "DEV-ONLY: record one port candidate inspection of kind `op` (`:probe` /
  `:read` / `:current?`) for the G-13 witness (rf2-vxgfnd.250). Called ONLY from
  behind an `interop/debug-enabled?` call-site gate, so it has no live caller —
  and thus DCEs wholesale, together with its sentinel — under `:advanced` +
  `goog.DEBUG=false`."
  [op]
  (when (compare-and-set! g13-candidate-witnessed? false true)
    #?(:cljs (.debug js/console g13-candidate-sentinel)))
  (swap! g13-candidate-counts update op (fnil inc 0)))

(defn ^:no-doc g13-candidate-inspection-snapshot
  "DEV/TEST-ONLY G-13 witness read (rf2-vxgfnd.250): the per-op port
  candidate-inspection counts since the last reset, or nil in production. NOT
  part of the port ABI — exists only under `interop/debug-enabled?`."
  []
  (when interop/debug-enabled? @g13-candidate-counts))

(defn ^:no-doc reset-g13-candidate-inspections!
  "DEV/TEST-ONLY G-13 witness reset (rf2-vxgfnd.250)."
  []
  (when interop/debug-enabled?
    (reset! g13-candidate-counts {:probe 0 :read 0 :current? 0})))

;; ---- node records (weak identity-keyed) -------------------------------------
;;
;; Per-node observation bookkeeping — `{:node-key <int> :version <int>
;; :value <last-observed> :owners #{handle…} :hook-installed? bool}` — keyed by the
;; cache node's reaction OBJECT in a WEAK identity-keyed table, so a record's
;; lifetime is exactly its reaction's: an evicted/disposed node's record
;; becomes unreachable with the node, and the port never prunes, scans, or
;; retains. The cache entry map itself stays exactly
;; `{:reaction :inputs :ref-count}` (Spec 006 §Cache shape advertises EXACTLY
;; that key-set; a port slot inside it would be a contract break).
;;
;; REACHABILITY INVARIANT (rf2-vxgfnd.37): the record VALUE must never
;; transitively STRONG-reference its own reaction KEY. `java.util.WeakHashMap`
;; is NOT an ephemeron map — it holds values strongly and never inspects
;; value→key paths, so a value reaching its key pins that key (and the whole
;; entry) for the process lifetime. The record's `:owners` handles reach their
;; reaction, so each handle holds it WEAKLY on the JVM (`handle-reaction`) and the
;; value→key edge is broken; an abandoned node (interrupted teardown, no
;; `release!`) is then collectable. `js/WeakMap` HAS ephemeron semantics, so on
;; CLJS the reaction is held plainly and no self-reference care is needed.

#?(:clj
   (defonce ^:private ^java.util.Map node-records
     (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))
   :cljs
   (defonce ^:private node-records (js/WeakMap.)))

(defonce ^:private node-key-counter (atom 0))

(defn- node-value=
  "The observation-node spelling of the UI substrate's ruled `rf=` equality,
  kept core-local so core does not depend on the UI artefact: identity or `=`,
  plus numeric equality on the JVM and NaN self-equality on both hosts."
  [a b]
  (or (identical? a b)
      (= a b)
      (and (number? a)
           (number? b)
           #?(:clj  (or (== (double a) (double b))
                         (and (Double/isNaN (double a))
                              (Double/isNaN (double b))))
              :cljs (and (js/isNaN a) (js/isNaN b))))))

(defn- advance-node-record!
  "Record that value `v` was OBSERVED on `reaction`: mint the node record on
  first observation (a fresh process-unique `:node-key`), advance `:version`
  when `v` differs from the last observed value by the core-local `rf=`
  spelling, else leave the record untouched. Returns the (post) record.
  Constant work — one compare + one small map write on movement."
  [reaction v]
  #?(:clj
     (locking node-records
       (let [rec  (.get node-records reaction)
             rec' (cond
                    (nil? rec)
                    {:node-key (swap! node-key-counter inc) :version 0 :value v}

                    (not (node-value= (:value rec) v))
                    (assoc rec :version (inc (:version rec)) :value v)

                    :else rec)]
         (when-not (identical? rec rec')
           (.put node-records reaction rec'))
         rec'))
     :cljs
     (let [rec  (.get node-records reaction)
           rec' (cond
                  (nil? rec)
                  {:node-key (swap! node-key-counter inc) :version 0 :value v}

                  (not (node-value= (:value rec) v))
                  (assoc rec :version (inc (:version rec)) :value v)

                  :else rec)]
       (when-not (identical? rec rec')
         (.set node-records reaction rec'))
       rec')))

(defn- observe-node!
  "Deref `reaction` fresh and advance its node record against the observed
  value. Returns `[record value]`."
  [reaction]
  (let [v @reaction]
    [(advance-node-record! reaction v) v]))

(defn- watchable?
  [reaction]
  #?(:clj  (instance? clojure.lang.IRef reaction)
     :cljs (satisfies? IWatchable reaction)))

;; ---- fail-loud throw helpers -------------------------------------------------

(defn- emit-and-throw!
  "Fan `error-id` through the always-on error-emit axis (so a
  boundary-swallowed port throw still reaches off-box shippers) then throw
  the canonical thrown-error. Never returns.

  `record-attrs` (the optional trailing arg) is the axis-1 always-on record
  attribution the always-on error record should carry INDEPENDENT of the
  dev-trace `extra` tags — currently `{:op :subscribe}` from
  `throw-frame-destroyed!` alone (rf2-alk8a): the port is subscribe-realm by
  construction, so the `:subscribe` realm stamp lets `error-emit` resolve the
  `:source-coord` under the EXACT `[:sub id]` realm and egress the handle's query
  vector on `:event` VERBATIM as raw IDENTITY (rf2-zwgqe). Every other caller
  (`throw-no-such-sub!`, `retry-exhausted`) passes nil — unchanged."
  ([error-id where frame-id query-v reason extra]
   (emit-and-throw! error-id where frame-id query-v reason extra nil))
  ([error-id where frame-id query-v reason extra record-attrs]
   (error-emit/emit-error-both!
     error-id
     query-v                        ;; attempted query-vector (as :event)
     (when query-v (first query-v)) ;; sub-id (as :event-id)
     frame-id
     nil                            ;; no exception — invalid op
     0                              ;; elapsed-ms
     (interop/now-ms)
     (merge {:where    where
             :frame    frame-id
             :recovery :no-recovery}
            (when query-v {:rf.sub/id      (first query-v)
                           :rf.sub/query-v query-v})
            extra)
     record-attrs)                  ;; axis-1 always-on record attrs (:op realm)
   ;; Throwable-bound emission provenance (rf2-9m4oy7): the emit-error-both! record
   ;; above IS this failure's exactly-once emission on BOTH axes. Binding it to
   ;; THIS exact throwable — not carrying a forgeable token in ex-data — lets a
   ;; containment drain catching this throw (an on-change that called a port op)
   ;; see always-on coverage and re-fan it on neither channel, while a forged or
   ;; transplanted token on any OTHER throwable reads uncovered.
   (throw
     (attest-provenance!
       (error/thrown-ex-info error-id where reason
                             {:extra (merge {:frame          frame-id
                                             :rf.sub/query-v query-v}
                                            extra)})
       provenance-both-channels))))

(defn- throw-frame-destroyed!
  [where frame-id query-v]
  (emit-and-throw!
    :rf.error/frame-destroyed where frame-id query-v
    (str where " targeted frame " frame-id ", which is not registered or has "
         "been destroyed; the observation port is fail-loud — the ViewCell "
         "maps this to the view error boundary (the public subscribe surface "
         "keeps its recover-to-nil semantics).")
    nil
    ;; rf2-alk8a: the observation port is subscribe-realm by construction, so
    ;; stamp `:op :subscribe` as the always-on record attribution — the query
    ;; vector on `:event` egresses raw (identity), and the source-coord resolves
    ;; realm-exact `[:sub id]`. ONLY this caller passes it.
    {:op :subscribe}))

(defn- throw-no-such-sub!
  [where frame-id query-v]
  (emit-and-throw!
    :rf.error/no-such-sub where frame-id query-v
    (str where " targeted subscription " (pr-str (first query-v)) " which has "
         "no registration; register it with rf/reg-sub before the view "
         "reads it (the observation port throws on an unknown ENTRY sub; "
         "the public subscribe surface keeps its recover-to-nil semantics).")
    {:unresolved-input query-v
     :resolved-inputs  []}))

(defn- valid-query-v?
  "The port's query-vector DOMAIN (rf2-vxgfnd.183): a non-empty vector whose
  head is the sub-id keyword — exactly what `resolve-target` threads through as
  `:query`. A non-vector (`42`), an empty vector (`[]`), or a non-keyword-headed
  vector (`[42]`) is NOT a query, so a target carrying it is malformed and must
  be rejected BEFORE `(first query)` reaches the registry / cache."
  [q]
  (and (vector? q)
       (seq q)
       (keyword? (first q))))

(defn- query-shape-class
  "Bounded, leak-safe classification of a MALFORMED query-vector for
  [[resolve-target]]'s rejection evidence (rf2-vxgfnd.241): which
  [[valid-query-v?]] clause it violated — `:non-vector`, `:empty-vector`, or
  `:non-keyword-head`. NEVER the query CONTENTS: a valid query's args can carry
  app values, so even a malformed query is summarized STRUCTURALLY, never
  serialized."
  [q]
  (cond
    (not (vector? q)) :non-vector
    (not (seq q))     :empty-vector
    :else             :non-keyword-head))

(defn- throw-malformed-query!
  "Reject a malformed query-vector at [[resolve-target]] — the port's ONLY
  resolution point (rf2-vxgfnd.241). resolve-target must validate the query
  SHAPE before any sequence access (`(first query-v)`) and before minting a
  target: an unvalidated scalar / empty / non-keyword-headed query-v otherwise
  leaks a raw host `(first …)` error (the ambient-frame path threads
  `(first query-v)` as the no-frame-context event-id) or DEFERS failure to the
  downstream target validator at an unrelated probe/acquire call site. A
  malformed query cannot yield a valid closed-grammar target, so resolve-target
  throws the SAME typed `:rf.error/observation-malformed-target` its downstream
  gate raises — closing the boundary at the earliest point rather than deferring.
  Pure `throw-error!` (diagnostic channel) — a malformed query is a
  substrate/consumer bug, unreachable in correct generated code, so it does NOT
  fan the always-on axis. Carries BOUNDED structural evidence only
  ([[query-shape-class]] plus a vector's element count), never the query
  CONTENTS. Never returns."
  [where query]
  (let [shape (query-shape-class query)]
    (error/throw-error!
      :rf.error/observation-malformed-target
      where
      (str where " received a malformed query-vector (" shape
           (when (vector? query) (str ", " (count query) " elements"))
           "): a query must be a non-empty keyword-headed vector "
           "[<sub-id> & args]. resolve-target is the port's ONLY resolution "
           "point; a malformed query cannot yield a valid closed-grammar target "
           "(rf2-vxgfnd.241).")
      {:recovery :no-recovery
       :extra    {:query-class shape
                  :query-count (when (vector? query) (count query))}})))

(defn- valid-target?
  "True iff `target` is EXACTLY one of the two complete shapes `resolve-target`
  emits (rf2-vxgfnd.183) — the port's CLOSED target grammar. Structural, pure,
  and ALLOCATION-FREE on the hot success path (rf2-vxgfnd.241): a
  FIXED-VOCABULARY key check — `count` (O(1); never enumerates the map's keys)
  plus `contains?` of the port's OWN known keys — so BOTH a missing AND an extra
  key fail WITHOUT allocating a key-set or hashing the target's
  attacker-controllable extra keys. This replaces the prior
  `(= #{…} (set (keys target)))`, which allocated a set and HASHED every key on
  every hot probe/acquire, letting a hostile extra key's `hashCode`/`equals`
  escape as an untyped host error and scaling with attacker-controlled extras. A
  legitimately-nil override value/token stays a VALUE because its KEY is present
  (`contains?`, never `some?`). The `:kind` discriminator compares with the known
  keyword FIRST (`=` dispatches through the keyword's own equality), so a hostile
  `:kind` value's own `hashCode`/`equals` is never invoked. A `:subscription`
  needs EXACTLY `{:kind :frame-id :query}` with a present keyword `:frame-id`
  (frame ids are registry-keyed keywords per `re-frame.frame`) and a non-empty
  keyword-headed `:query`; a `:story-override` needs EXACTLY
  `{:kind :query :value :override-id :version}` with the same query domain. A
  non-map, an unknown `:kind`, a supported-kind-but-INCOMPLETE target (a bare
  `{:kind :story-override}`), an EXTRA key, a wrong-domain field, or a malformed
  query all read false — and a hostile-hash extra key can never escape as a raw
  error."
  [target]
  (and (map? target)
       (cond
         ;; count is the fixed-vocabulary cardinality gate: the discriminator
         ;; already proves `:kind` present, and `contains?` proves the remaining
         ;; named keys, so an exact count pins the key-set to EXACTLY the closed
         ;; grammar without ever touching (hashing/enumerating) an extra key.
         (= :subscription (:kind target))
         (and (= 3 (count target))
              (contains? target :frame-id)
              (contains? target :query)
              (keyword? (:frame-id target))
              (valid-query-v? (:query target)))

         (= :story-override (:kind target))
         (and (= 5 (count target))
              (contains? target :query)
              (contains? target :value)
              (contains? target :override-id)
              (contains? target :version)
              (valid-query-v? (:query target)))

         :else false)))

(def ^:private target-known-keys
  "The port's CLOSED target grammar vocabulary — the union of the two shapes'
  keys. Malformed-target rejection evidence names ONLY which of THESE (the
  port's OWN keys) are present, never the target's raw/attacker keys
  (rf2-vxgfnd.241)."
  [:kind :frame-id :query :value :override-id :version])

(defn- target-kind-class
  "Bounded, leak-safe classification of a target's `:kind` for rejection
  evidence (rf2-vxgfnd.241): the recognized closed-grammar kind (`:subscription`
  / `:story-override`) echoed as-is — a known interned constant, bounded and
  non-sensitive — or `:unrecognized` for anything else (a non-keyword, an unknown
  keyword, an absent `:kind`, or a non-map target). So a structured or secret
  `:kind` VALUE is never serialized into the diagnostic. Compares with the known
  keyword FIRST so dispatch rides the keyword's own equality — a hostile `:kind`
  value's `hashCode`/`equals` is never invoked."
  [target]
  (let [k (when (map? target) (:kind target))]
    (cond
      (= :subscription k)   :subscription
      (= :story-override k) :story-override
      :else                 :unrecognized)))

(defn- throw-malformed-target!
  "Reject a target that violates the port's CLOSED target grammar
  (rf2-vxgfnd.183): a non-map, an unknown `:kind`, missing/extra keys, a
  wrong-domain frame identity, an empty/non-keyword-headed query, or a
  supported-kind-but-INCOMPLETE target (a bare `{:kind :story-override}`) could
  not have come from [[resolve-target]] (the port's ONLY resolution point) — it
  is a substrate/consumer bug. Per Spec 006 §Error contract every port op throws
  TYPED; without an up-front grammar gate the target-taking ops would reach
  `(first query)` / a frame-registry op on a malformed value and leak a BARE host
  error (a `case` \"No matching clause\", a `ClassCastException`, an NPE) the
  ViewCell error boundary cannot classify. Throws the typed
  `:rf.error/observation-malformed-target`. Pure `throw-error!` (diagnostic
  channel) — a corrupted target is a programming defect, unreachable in correct
  generated code, so it does NOT fan the always-on axis.

  Evidence is BOUNDED + NORMALIZED (rf2-vxgfnd.241) — the prior evidence
  serialized the raw `:kind` and the FULL key vector (`(vec (keys target))`),
  which enumerated a 10k-key map into an unbounded message and leaked
  structured/secret KEYS. It now carries only: the [[target-kind-class]] (a
  recognized kind or `:unrecognized`, never a raw/secret kind value), the total
  `:key-count` (an O(1) `count`, never the keys themselves), and
  `:known-keys-present` — which of the port's OWN [[target-known-keys]] the map
  carries (a `contains?` probe of fixed vocabulary, so an attacker's extra key is
  never named, hashed, or enumerated). Never the field VALUES (a
  `:story-override` embeds an app value under `:value`). Never returns."
  [where target]
  (let [is-map     (map? target)
        kind-class (target-kind-class target)
        key-count  (when is-map (count target))
        known      (when is-map
                     (into #{}
                           (filter #(contains? target %))
                           target-known-keys))]
    (error/throw-error!
      :rf.error/observation-malformed-target
      where
      (str where " received a malformed observation target (kind-class "
           kind-class
           (when key-count (str ", " key-count " keys"))
           "): it is not one of the two closed shapes resolve-target emits — a "
           ":subscription with a keyword :frame-id and a non-empty keyword-headed "
           ":query, or a :story-override carrying "
           ":query/:value/:override-id/:version. A target must come from "
           "resolve-target (the port's ONLY resolution point); a hand-constructed, "
           "incomplete, or corrupted target is a substrate bug. Diagnostic "
           "evidence is bounded + normalized — kind-class, total key count, and "
           "known-key presence only, never raw key/value material "
           "(rf2-vxgfnd.241).")
      {:recovery :no-recovery
       :extra    {:kind-class         kind-class
                  :key-count          key-count
                  :known-keys-present known}})))

(defn- validate-target!
  "Grammar gate for the target-taking ops (`probe` / `acquire!`): throw the typed
  `:rf.error/observation-malformed-target` when `target` is not EXACTLY one of
  the two closed shapes resolve-target emits ([[valid-target?]]) — BEFORE any
  host op (`(first query)`, a frame-registry lookup) can leak a bare host error
  (rf2-vxgfnd.183). Returns `target` when valid."
  [where target]
  (if (valid-target? target)
    target
    (throw-malformed-target! where target)))

(defn- throw-acquire-recovery!
  "rf2-vxgfnd.27 — the ENTRY node's OWN build produced a NEVER-CACHED, zero-ref
  recovery reaction instead of a canonical cache node: a cyclic entry sub, a
  parametric `input-fn` failure, or a frame destroyed mid-build. `acquire!` IS
  the cache's ref-count attach; there is no node to own, so the port is
  fail-loud and throws the typed error mirroring the recovery taxonomy rather
  than handle a lying `owned?`-true zero-ref reaction that re-churns every
  commit. `result` is the `{:recovery kind …}` map from
  `subs/acquire-cache-reaction!`. Never returns.

  Emit discipline — exactly one always-on record, no duplicate:
    - `:frame-destroyed` → fan the always-on record + throw via
      `throw-frame-destroyed!` (the 009 frame-destroyed row already carries the
      port's throwing surface; the build emitted nothing for the race). Also the
      catch-all for any unexpected classification (safe fail-loud).
    - `:input-fn-exception` / `:input-fn-bad-return` → throw the matching id;
      the build ALREADY fanned the always-on `:rf.error/sub-input-fn-*` record,
      so the port does NOT re-fan (one record, one throw).
    - `:cycle` → throw the typed `:rf.error/sub-cycle` to the ViewCell error
      boundary; it stays DIAGNOSTIC (009 catalogue) — already emitted on the
      trace channel by the build — so the port does NOT promote it to the
      always-on axis."
  [frame-id query-v result]
  (case (:recovery result)
    :cycle
    (throw
      (attest-provenance!
        (error/thrown-ex-info
          :rf.error/sub-cycle
          're-frame.substrate.observation/acquire!
          (str "acquire! targeted subscription " (pr-str (first query-v))
               " which sits on a :<- dependency cycle " (pr-str (:cycle result))
               "; a cyclic sub has no cacheable node, so the observation port is "
               "fail-loud (the public subscribe surface keeps its recover-to-nil "
               "semantics). Break the cycle so no sub transitively lists itself "
               "among its inputs.")
          {:recovery :no-recovery
           :extra    {:frame          frame-id
                      :rf.sub/query-v query-v
                      :cycle          (:cycle result)}})
        ;; Throwable-bound emission provenance (rf2-9m4oy7): the build surfaced
        ;; :rf.error/sub-cycle on the DIAGNOSTIC TRACE axis ONLY (production-
        ;; elided) — so this attests `#{:trace}`, NOT full coverage. A containment
        ;; drain catching THIS throwable must not re-emit the diagnostic sub-cycle
        ;; (nor promote it onto the always-on axis), but production observability
        ;; is NOT yet covered, so the drain still adds the stable always-on
        ;; callback-failure record. A channel-blind Boolean here is the bug that
        ;; silenced a cyclic-acquire callback failure under goog.DEBUG=false.
        provenance-trace-only))

    (:input-fn-exception :input-fn-bad-return)
    (throw
      (attest-provenance!
        (error/thrown-ex-info
          (:error-kw result)
          're-frame.substrate.observation/acquire!
          (str "acquire! targeted parametric subscription " (pr-str (first query-v))
               " whose input-fn failed at materialization, so there is no cacheable "
               "node to own; the observation port is fail-loud (the public "
               "subscribe surface keeps its recover-to-nil semantics). "
               (:reason result))
          {:recovery :no-recovery
           :extra    {:frame          frame-id
                      :rf.sub/query-v query-v}})
        ;; Throwable-bound emission provenance (rf2-9m4oy7): the build ALREADY
        ;; fanned the always-on :rf.error/sub-input-fn-* record on both axes (one
        ;; record, one throw), so this attests `#{:always-on :trace}` and a
        ;; containment drain catching THIS throwable re-fans nothing.
        provenance-both-channels))

    ;; :frame-destroyed and any unexpected classification — fan + throw typed.
    (throw-frame-destroyed! 're-frame.substrate.observation/acquire!
                            frame-id query-v)))

;; ---- the handle ---------------------------------------------------------------

(deftype ObservationHandle [state]
  ;; The handle IS the owner token — an opaque host object compared by
  ;; IDENTITY (deftype default; never `=`). Owners are keyed by handle
  ;; identity with per-handle callbacks, so the sibling-callback-clobber bug
  ;; class is structurally impossible and StrictMode release/reacquire is
  ;; naturally balanced. `state` is an atom:
  ;;
  ;;   node handle   {:handle-kind :node :target t :frame-id f :query-v q
  ;;                 :reaction r :on-change f :watch-key k|absent
  ;;                 :status :live|:released
  ;;                 :last {:value v :version n :node-key nk}}
  ;;   static handle {:handle-kind :static :target t :status :live}
  )

(defn- handle-state
  [handle]
  (.-state ^ObservationHandle handle))

(defn handle?
  "True when `x` is an observation-port handle (either kind)."
  [x]
  (instance? ObservationHandle x))

(defn owned?
  "True when `handle` owns a real sub-cache node (`acquire!` on a
  `:subscription` target). TOTAL and no-throw (rf2-vxgfnd.241): a non-handle reads
  `false` rather than field-accessing [[handle-state]] and leaking a raw host
  error (a JVM NPE / a CLJS TypeError) — the same ruled malformed-value contract
  `current?` follows, since a value that is not a live node handle simply owns no
  node. `handle?` short-circuits the `and` before `@(handle-state handle)`, so no
  raw host error escapes. The static override handle reports `false` honestly — a
  pinned value owns nothing."
  [handle]
  (and (handle? handle)
       (= :node (:handle-kind @(handle-state handle)))))

(defn- throw-malformed-handle!
  "Reject a value that is not a real [[ObservationHandle]] at the shared handle
  boundary (rf2-vxgfnd.183): `read` / `release!` field-access [[handle-state]]
  and then DEREF the result, so a nil / a map / any arbitrary host object would
  otherwise throw a raw `NullPointerException` (JVM) or an untyped host error
  (CLJS) carrying no `:rf.error/id` the ViewCell error boundary can classify —
  the half-hardened boundary this bead closes. Throws the typed
  `:rf.error/observation-malformed-handle` — a distinct diagnostic in the same
  `observation-malformed-*` family as the target category (a handle is not a
  target, so it earns its own id, not a shared one). Pure `throw-error!`
  (diagnostic channel) — a non-handle reaching a handle-taking op is a
  substrate/consumer bug, unreachable in correct generated code, so it does NOT
  fan the always-on axis. Carries BOUNDED structural evidence only — the
  argument's host TYPE, never the value. Never returns."
  [where handle]
  (error/throw-error!
    :rf.error/observation-malformed-handle
    where
    (str where " received a malformed observation handle (type "
         (pr-str (type handle)) "): it is not an ObservationHandle. A handle must "
         "come from acquire!; nil, a map, or any other host object is a "
         "substrate bug — read / release! deref the handle state, so an "
         "unvalidated non-handle would leak a bare host NPE / TypeError the "
         "ViewCell error boundary cannot classify.")
    {:recovery :no-recovery
     :extra    {:handle-type (pr-str (type handle))}}))

(defn- validate-handle!
  "Grammar gate for the handle-taking ops (`read` / `release!`): throw the typed
  `:rf.error/observation-malformed-handle` when `handle` is not a real
  [[ObservationHandle]], BEFORE [[handle-state]] field-accesses + derefs it
  (rf2-vxgfnd.183). Returns `handle` when valid. (`current?` does NOT route here —
  it is a pure no-throw kept-check predicate and returns `false` for a non-handle
  per its ruled contract.)"
  [where handle]
  (if (handle? handle)
    handle
    (throw-malformed-handle! where handle)))

;; ---- weak reaction reference (JVM WeakHashMap self-reference break) ----------
;;
;; rf2-vxgfnd.37: the JVM node-records table is a `java.util.WeakHashMap` keyed
;; by REACTION, and its VALUE carries the node's active-owner set (`:owners`) of
;; `ObservationHandle` objects. `java.util.WeakHashMap` is NOT an ephemeron map:
;; a value that transitively STRONG-references its own weak key pins that key
;; forever (the map holds values strongly and never inspects value→key paths).
;; So a handle MUST NOT strong-reference its reaction, or the chain
;;
;;     node-records value → :owners → handle → state → reaction (= the weak key)
;;
;; would defeat the weak key and retain every abandoned node's handles for the
;; process lifetime. The handle state therefore holds its reaction WEAKLY on the
;; JVM (deriving the strong reaction on demand via [[handle-reaction]]), so an
;; otherwise-unreachable reaction — e.g. an interrupted teardown that drops a
;; cache/frame without completing `release!`/dispose — is GC-collectable and its
;; node record dies with it. CLJS needs no such care: `js/WeakMap` HAS ephemeron
;; semantics (a value→key path never pins the key), so the reaction is held as a
;; plain reference there.

(defn- weak-reaction-ref
  "Wrap `reaction` for storage in a node handle's state: a `WeakReference` on the
  JVM (so the weak node-records value cannot strong-reference its own weak key —
  rf2-vxgfnd.37), the plain reaction on CLJS (`js/WeakMap` is ephemeron)."
  [reaction]
  #?(:clj  (java.lang.ref.WeakReference. reaction)
     :cljs reaction))

(defn- handle-reaction
  "Derive the STRONG reaction from a node handle's `state` map — or nil when the
  JVM `WeakReference` has already been collected (the reaction became
  unreachable while the handle outlived it: an abandoned or displaced node). CLJS
  returns the plain reference. Every caller guards the nil case — a collected
  reaction is never the live canonical node, so `current?`/`read` fall back to
  the last committed observation and `release!` no-ops the cache detach."
  [state]
  #?(:clj  (when-let [^java.lang.ref.WeakReference ref (:reaction state)]
             (.get ref))
     :cljs (:reaction state)))

;; ---- HMR / disposal notification queue ---------------------------------------
;;
;; Each queued entry is a `[handle cause]` PAIR (rf2-r8jmdb): the cause is
;; INTRINSIC to why the node died — captured at enqueue time from the disposing
;; cache site's `re-frame.subs.cache/*disposal-cause*` — NOT inferred from which
;; drain boundary fires. So a frame-destroy / cache-clear handle still pending
;; when an unrelated `:sub` HMR re-registration drains is NEVER swept into the
;; `:hmr` drain and mislabelled: `drain-pending-disposals!` takes only the
;; entries whose intrinsic cause matches the boundary it serves (the registrar
;; HMR hook drains `:hmr`, the next-tick fallback drains `:disposed`), leaving
;; the rest queued for their own boundary and delivering each handle its OWN
;; cause. A documented `on-change` payload contract fix (a consumer branching
;; `:hmr` = re-acquire vs `:disposed` = gone must not re-acquire a destroyed
;; frame).

(defonce ^:private pending-disposals (atom []))
(defonce ^:private disposal-drain-scheduled? (atom false))

(defn- enqueue-cause
  "Map the disposing cache site's intrinsic `re-frame.subs.cache/*disposal-cause*`
  reason to the port's `on-change` cause enum: `:hot-reload` → `:hmr` (the node
  re-registered and WILL rebuild, so a former owner re-acquires), every other
  reason (`:no-more-derefers` / `:cache-clear` / `:frame-destroy`) AND the
  unbound-var default → `:disposed` (the node is gone). The default is
  deliberately `:disposed`, never the re-acquire-signalling `:hmr`: an
  `acquire!`-stack canonicality re-check (rf2-vxgfnd.32) enqueues OFF the dispose
  stack where the var is unbound, and an unknown cause must fail safe as gone."
  []
  (if (= :hot-reload subs-cache/*disposal-cause*) :hmr :disposed))

(defn- notify-disposal!
  [handle cause]
  (let [st @(handle-state handle)]
    (when (and (= :node (:handle-kind st))
               (= :live (:status st)))
      (let [{:keys [on-change target frame-id last]} st]
        (fan-out! on-change
                  {:cause          cause
                   :target         target
                   :node-key       (:node-key last)
                   :node-version   (:version last)
                   :frame-epoch    (frame/frame-commit-epoch frame-id)
                   :registry-epoch @registry-epoch*})))))

(defn- report-disposal-notify-escape!
  "Surface an escaping former-owner `on-change` failure EXACTLY ONCE per
  runtime error (Spec 009's one-runtime-error law) even though the drain
  boundary swallows the propagated throw. The `:hmr` drain runs INSIDE the
  registrar replacement hook, whose per-hook `try/catch` DROPS the throw
  (registrar isolates replacement-hook failures — `re-frame.registrar`), and
  the `:disposed` drain rides `interop/next-tick` (a JVM Future whose result
  is never inspected); either boundary would otherwise make the escape
  invisible exactly where it matters — so no `on-change` failure may depend on
  the rethrow being observed.

  Classification is CHANNEL-AWARE and by THROWABLE-BOUND PROVENANCE (rf2-w55bh0 +
  rf2-9m4oy7) — never by a channel-blind `fanned` Boolean, never by `:rf.error/id`
  truthiness / reconstructible ex-data shape, never by a forgeable ex-data token,
  and never a global seen-error registry. The drain owns PRODUCTION (always-on)
  coverage for this one callback failure UNLESS [[source-covered-always-on?]]
  proves the EXACT caught throwable was minted-and-bound by this port with an
  always-on-covering provenance:

    - ALREADY COVERED ON THE ALWAYS-ON AXIS — the port's own emit-then-throw
      surfaces that fanned through `error-emit/emit-error-both!` (`read` on a
      released handle, the probe/acquire fail-loud throws, the ABI guard, the
      retry-exhausted throw, and the acquire-recovery input-fn arms whose
      always-on record the sub BUILD fanned) bind the `#{:always-on :trace}`
      provenance to THAT exact throwable in the private weak association. Their
      record IS the exactly-once emission, carrying the SOURCE's correct
      frame/query attribution. Adding anything here would double-report the one
      runtime error and overwrite that attribution with the NOTIFYING owner's
      context, so nothing more is emitted on either channel.
    - NOT COVERED ON THE ALWAYS-ON AXIS — a source that emitted ONLY on the
      diagnostic trace axis (the build's production-elided `:rf.error/sub-cycle`,
      provenance `#{:trace}`), a DIAGNOSTIC-ONLY thrown category with no fan
      of its own (`:rf.error/observation-malformed-target` / `…-malformed-handle`
      / the dev `:rf.error/reentrant-graph-op` assert), a raw untyped
      consumer-callback bug (a `TypeError` / `AssertionError` / host
      `RuntimeException`), or an application ex-info trying to SPOOF a framework
      category (a reserved-but-uncatalogued or imitated `:rf.error/id`, a
      forged `->EmissionProvenance` token in its own ex-data, or an authentic
      token TRANSPLANTED onto a different exception) — all read FALSE, because
      only the exact throwable this port minted-and-bound is associated.
      Production observability is still owed, so the drain adds EXACTLY
      ONE stable catalogued `:rf.error/observation-on-change-failed` record,
      carrying the original throwable as the record's `:exception` cause. The
      escape's own diagnostic category is NEVER promoted onto the always-on
      axis; its detail rides as the wrapper's cause.

  The drain-owned wrapper rides the shared TWO-CHANNEL fan-out
  (`error-emit/emit-error-both!`, rf2-q3fmqm): the always-on record for off-box
  shippers PLUS the dev diagnostic-trace event Xray's trace-tooling listener
  consumes — the registrar/next-tick boundaries swallow the rethrow, so without
  the trace leg a real HMR/disposed callback failure was invisible in the
  primary debugging surface. The category-specific trace tags carry the disposal
  `cause` (`:hmr` / `:disposed`), the former owner's entry-sub coordinates
  (`:rf.sub/id` / `:rf.sub/query-v`), and the original throwable. In advanced
  production the trace leg is DCE'd inside `trace/emit-error!` while the
  always-on record survives. The record's `:event-id` carries the ENTRY SUB id,
  and `error-emit` classifies the wrapper category subscription-owned, so
  `:source-coord` resolves under `[:sub id]` — a macro-registered sub yields its
  exact coordinate, a programmatic one omits the slot, and a same-id event
  registration cannot steal attribution."
  [handle cause exception]
  (when-not (source-covered-always-on? exception)
    (let [{:keys [frame-id query-v]} @(handle-state handle)
          sub-id (first query-v)]
      (error-emit/emit-error-both!
        :rf.error/observation-on-change-failed
        query-v sub-id frame-id exception 0 (interop/now-ms)
        {:rf.sub/id         sub-id
         :rf.sub/query-v    query-v
         :where             're-frame.substrate.observation/drain-pending-disposals!
         :frame             frame-id
         :cause             cause
         :exception         exception
         :exception-message (error/ex-message-safe exception)
         :reason            (str "a former-owner on-change callback for "
                                 (pr-str sub-id) " failed during the " cause
                                 " disposal-notification drain; surfaced here on "
                                 "the always-on axis exactly once because the "
                                 "drain boundary swallows the rethrow and the "
                                 "failure's source did not already cover "
                                 "production observability. The escape is "
                                 "contained (siblings still notified) and rides "
                                 "as this record's :exception cause — the "
                                 "underlying bug is a defect in the "
                                 "observation-port consumer that registered the "
                                 "callback.")
         :recovery          :no-recovery}))))

(defn ^:no-doc drain-pending-disposals!
  "Drain the queued node-disposed notifications whose INTRINSIC cause is
  `cause`, coalesced once per handle (identity), delivering only to still-live
  handles. `cause` is `:hmr` at the sub re-registration boundary (the registrar
  replacement hook below), `:disposed` on the next-tick fallback (frame-destroy
  / explicit cache clears). INTERNAL — exposed un-private only so the port's own
  tests can drive the fallback boundary deterministically.

  Each queued entry is a `[handle intrinsic-cause]` pair (rf2-r8jmdb): this drain
  takes ONLY the entries whose intrinsic cause equals `cause` — atomically, via
  `swap-vals!` filtering them out in one CAS — and LEAVES the rest queued for
  their own boundary. So a `:disposed`-tagged frame-destroy/cache-clear handle
  still pending when this fires with `:hmr` is never mislabelled `:hmr`; it waits
  for the next-tick `:disposed` fallback. Because every taken entry already
  carries `cause`, delivering `cause` to each IS delivering its own cause.

  Each handle's notification is CONTAINED in its own `try/catch` so one owner's
  throwing `on-change` cannot starve its siblings (rf2-vxgfnd.28 — this was the
  one uncontained fan-out; it mirrors registrar's per-hook and subs.cache's
  per-reaction dispose containment). Every sibling is notified; EVERY escape is
  surfaced EXACTLY ONCE so it survives a swallowing boundary without violating
  Spec 009's one-runtime-error law (rf2-6ui49w + rf2-wbkjk9 + rf2-w55bh0 +
  rf2-9m4oy7: the drain owns PRODUCTION coverage unless the escape's
  THROWABLE-BOUND, channel-aware provenance proves the source already fanned an
  always-on record for THIS exact throwable; an escape whose source covered only
  the diagnostic trace axis, a diagnostic-only thrown category, a raw untyped
  consumer bug, a forged or transplanted token, or an application spoof is wrapped
  once
  in `:rf.error/observation-on-change-failed` WITHOUT promoting its own category
  onto the always-on axis — see [[report-disposal-notify-escape!]]); then the
  first escape is re-thrown AFTER the whole drain for any DIRECT caller, with its
  identity/cause intact, but correctness never depends on the registrar /
  next-tick boundary observing that rethrow — the surfaced record IS the
  visibility. Never silent, never starving, never double-reported."
  [cause]
  (let [[old _new] (swap-vals! pending-disposals
                               (fn [pending]
                                 (filterv #(not= cause (second %)) pending)))
        taken      (into [] (comp (filter #(= cause (second %)))
                                  (map first)
                                  (distinct))
                         old)
        escapes    (reduce
                     (fn [acc handle]
                       (try
                         (notify-disposal! handle cause)
                         acc
                         (catch #?(:clj Throwable :cljs :default) t
                           ;; Surface EVERY escape EXACTLY ONCE before the
                           ;; boundary swallows the rethrow (rf2-6ui49w +
                           ;; rf2-wbkjk9 + rf2-w55bh0 + rf2-9m4oy7): channel-aware,
                           ;; throwable-bound provenance decides. An escape whose
                           ;; source already covered the always-on axis for THIS
                           ;; exact throwable keeps its source's
                           ;; emission; everything else (trace-only coverage, a
                           ;; diagnostic-only thrown category, an untyped bug, a
                           ;; spoof) is wrapped once in the always-on
                           ;; :rf.error/observation-on-change-failed via the
                           ;; two-channel fan-out (rf2-q3fmqm), never promoting
                           ;; its own category onto the always-on axis.
                           (report-disposal-notify-escape! handle cause t)
                           (conj acc t))))
                     []
                     taken)]
    (when (seq escapes)
      (throw (first escapes))))
  nil)

(defn- enqueue-disposal!
  [handle]
  ;; Capture the node's INTRINSIC cause NOW (rf2-r8jmdb): on the dispose stack
  ;; the disposing cache site has `subs-cache/*disposal-cause*` bound, so the
  ;; entry carries the cause the node ACTUALLY died of rather than the boundary
  ;; that later drains it. Off the dispose stack (the acquire! re-check) the var
  ;; is unbound and [[enqueue-cause]] fails safe to `:disposed`.
  (swap! pending-disposals conj [handle (enqueue-cause)])
  ;; Fallback drain boundary for non-registrar disposal paths. The HMR path
  ;; drains its `:hmr`-tagged entries earlier and synchronously (the replacement
  ;; hook below); this tick then drains any `:disposed`-tagged entries left.
  (when (compare-and-set! disposal-drain-scheduled? false true)
    (interop/next-tick
      (fn []
        (reset! disposal-drain-scheduled? false)
        (drain-pending-disposals! :disposed)))))

;; ---- evidence ----------------------------------------------------------------

(defn- evidence
  [frame-id value node-version node-key live?]
  {:value          value
   :node-version   node-version
   :node-key       node-key
   :live?          live?
   :frame-epoch    (frame/frame-commit-epoch frame-id)
   :registry-epoch @registry-epoch*})

;; ---- the slice-scoped probe memo ----------------------------------------------

(defn make-slice-memo
  "Return a fresh slice-memo HANDLE for [[probe]]'s optional second argument.
  Cold probes threading the same handle share computed derivation parents (N
  sibling rows probing `[:orders/by-id id]` compute shared parents once per
  slice, not once per row). The table inside is created lazily on first cold
  probe, tagged with `(frame, frame-epoch, registry-epoch)` PLUS the exact
  frame-incarnation token, and invalidated on any mismatch — the token by
  identity, because the epoch triple can tie across a same-id destroy+recreate
  (rf2-vxgfnd.160).

  How far the sharing reaches — the SLICE — is bounded PER HOST, because the
  hosts' scheduling models genuinely differ, yet both reach the same law:
  probes may share within one slice, but no holder or table survives past that
  boundary into the next slice. On the JVM sharing ends with its synchronous
  render thunk — a thread-local render scope discards the table when the thunk
  returns (there is no microtask there). On CLJS sharing MAY span later
  callbacks within the bounded host-microtask window: one module holder lives
  until the `queueMicrotask` checkpoint, so a genuinely-later render interposed
  before that checkpoint (queueMicrotask is FIFO) reuses the still-installed
  holder — a bounded within-window economy — while no holder survives past the
  checkpoint into the next window (rf2-2g7pxq). `re-frame.ui.reactive` owns
  both.

  Bounded reuse is never stale-value authority: an interposed later render at a
  moved epoch fails the tag check and mints a fresh table rather than serving
  the stale value. The memo is an ECONOMY, never an authority — for a
  COMMITTING reader the commit evidence comparison (invariant 5) corrects any
  staleness before paint; the incarnation-complete tag additionally keeps a
  COMMIT-FREE reader (a Tier-1 probe outside a ViewCell, which has no commit
  step 5) correct on its own. Per Spec 006 §The slice-scoped probe memo."
  []
  (atom {:tag nil :memo nil}))

(defn- seed-observation-opts!
  [memo frame-id]
  (swap! memo assoc subs/observation-opts-key {:frame frame-id})
  memo)

(defn- slice-memo-table!
  "Resolve the compute memo atom for a cold probe against `frame-id`:
  validate the handle's `(frame, frame-epoch, registry-epoch)` tag AND the
  exact frame-incarnation token (by IDENTITY), reuse the table on a full
  match, else install a fresh one (arming the CLJS microtask clear). A nil
  handle means no cross-probe sharing — a fresh per-call table.

  The incarnation token is part of the identity because the
  (frame, frame-epoch, registry-epoch) triple can TIE across a same-id
  destroy+recreate: `frame/dissoc-frame!` restarts the commit epoch, so a
  fresh incarnation B can present the EXACT epochs a destroyed incarnation A
  did. Without the token, a COMMIT-FREE consumer (a Tier-1 `ui.test/render`
  outside any ViewCell) would receive A's memoized parent for B — and there is
  no commit step 5 to correct a commit-free read. The memo remains an ECONOMY
  for a COMMITTING reader, but its tag is itself incarnation-complete so the
  commit-free path is correct on its own (rf2-vxgfnd.160)."
  [handle frame-id]
  (if (nil? handle)
    (seed-observation-opts! (atom {}) frame-id)
    (let [token (frame/frame-incarnation-token frame-id)
          tag   [frame-id
                 (frame/frame-commit-epoch frame-id)
                 @registry-epoch*]
          {existing-tag :tag existing-token :token existing :memo} @handle]
      (if (and existing
               (= existing-tag tag)
               (identical? existing-token token))
        existing
        (let [fresh (seed-observation-opts! (atom {}) frame-id)]
          (reset! handle {:tag tag :token token :memo fresh})
          #?(:cljs
             (when (exists? js/queueMicrotask)
               (js/queueMicrotask
                 (fn []
                   ;; Release only OUR table — a later probe in this window may
                   ;; have re-tagged and installed a fresh one already.
                   (when (identical? fresh (:memo @handle))
                     (reset! handle {:tag nil :token nil :memo nil}))))))
          fresh)))))

;; ---- resolve-target -----------------------------------------------------------

(defn resolve-target
  "Render-side: resolve a compiled site's context to a first-class
  observation TARGET — the ONLY resolution point (ambient frame, explicit
  pins, and the Story override context all land here; no later phase
  re-resolves context). A target is a stable identity carrying no node
  handle and no value/version for the `:subscription` kind.

  `site-ctx` is the host-internal carrier (not part of the port ABI):

    {:query-v  [<sub-id> & args]        ;; REQUIRED; the stabilized query —
                                        ;; stabilization (I-8) is the
                                        ;; caller's: pass the prior query
                                        ;; object while args are rf=
     :frame    <frame-id | frame value> ;; optional explicit pin; ambient
                                        ;; scope resolution when absent
                                        ;; (:rf.error/no-frame-context when
                                        ;; no scope is established)
     :override {:value v                ;; optional — the consumer's
                :override-id o          ;; already-read + already-VALIDATED
                :version n}}            ;; Story HIT for this query

  ## The override change token (OPAQUE to this port)

  `:override-id` and `:version` are OPAQUE change tokens SUPPLIED by the
  consumer (the compiled-view artefact's `re-frame.ui.reactive`). This port
  never interprets them: `:override-id` is the slot-identity token (compared
  by `=`), while `:version` is the movement token (compared by the core-local
  spelling of the frozen `rf=` law, so an `rf=`-equal version keeps and a
  moved version retargets). The consumer's LOWERING (which app-level value
  becomes the id vs. the version) is recorded on the consumer side
  (`re-frame.ui.reactive/*sub-overrides*`), NOT here — so the query-as-id /
  value-as-version choice never leaks into this port's contract, and the
  port stays correct whatever opaque tokens the consumer mints. Schema
  validation + recover-to-nil happen ON the consumer side BEFORE the HIT
  reaches here: `:value` is the ALREADY-VALIDATED value.

  An `:override` HIT resolves to `{:kind :story-override …}` — the pinned
  value rides the target because the value IS the resolution; there is no
  node to re-resolve, and commit acquires this exact captured target (no
  deref-time re-consult). Otherwise `{:kind :subscription :frame-id id
  :query q}` — the node named by identity, re-resolved at acquire."
  [site-ctx]
  (let [{:keys [query-v override]} site-ctx]
    ;; Grammar gate FIRST (rf2-vxgfnd.241): validate the query SHAPE before any
    ;; sequence access (`(first query-v)`) and before minting a target. An
    ;; unvalidated scalar / empty / non-keyword-headed query-v otherwise leaks a
    ;; raw host `(first …)` error (the ambient-frame path threads
    ;; `(first query-v)` as the no-frame-context event-id) or DEFERS failure to
    ;; the downstream target validator at an unrelated probe/acquire call site.
    ;; resolve-target is the port's ONLY resolution point, so it rejects here.
    (when-not (valid-query-v? query-v)
      (throw-malformed-query! 're-frame.substrate.observation/resolve-target
                              query-v))
    (if (some? override)
      {:kind        :story-override
       :query       query-v
       :value       (:value override)
       :override-id (:override-id override)
       :version     (:version override)}
      (let [frame-id (if-some [target (:frame site-ctx)]
                       (frame/frame-target->id target)
                       (frame/require-current-frame!
                         :subscribe
                         {:where    're-frame.substrate.observation/resolve-target
                          :event-id (first query-v)}))]
        {:kind     :subscription
         :frame-id frame-id
         :query    query-v}))))

;; ---- probe ---------------------------------------------------------------------

(defn probe
  "Render-side: pure, ownership-free evidence read of a resolved `target`.
  Returns what THIS read observed — never a handle:

      {:value <v>
       :node-version 42 | nil    ;; nil = probed cold (no live node)
       :node-key k | nil
       :live? true|false
       :frame-epoch 17
       :registry-epoch 3}

  A live cached node is read via deref; otherwise the probe computes PURE
  against the frame's current frame-state snapshot through the slice memo
  (`slice-memo` — see [[make-slice-memo]]; nil for no cross-probe sharing),
  creating NO cache entry, NO watch, and NO disposal obligation. Cold probes
  (`:node-version nil`) are first-class: the commit evidence comparison
  falls back to `rf=` on value for them.

  Fail-loud: throws `:rf.error/frame-destroyed` against a destroyed frame
  and `:rf.error/no-such-sub` when the target's OWN query names an
  unregistered sub. In-graph input resolution keeps the graph's documented
  behaviour — an unknown `:<-`/parametric input mid-graph emits one
  always-on `:rf.error/no-such-sub` error event, substitutes nil, and the
  body still runs, identically cold and live; a sub BODY that throws follows
  the graph's own recovery (`:rf.error/sub-exception` + nil), identically
  cold and live, so probe temperature is never observable; a `:<-` cycle
  recovers via the structured `:rf.error/sub-cycle`, identically cold and
  live.

  An override target probes to its pinned value with cold-shaped node
  evidence (`:live? false`, no node) — there is no node, and the kept-check
  for override sites rides the target's own id/version, not node evidence."
  ([target] (probe target nil))
  ([target slice-memo]
   ;; Grammar gate FIRST (rf2-vxgfnd.183): reject a malformed target with the
   ;; typed :rf.error/observation-malformed-target before `(first query)` / a
   ;; frame-registry op can leak a bare host error the ViewCell cannot classify.
   (validate-target! 're-frame.substrate.observation/probe target)
   (when interop/debug-enabled? (note-candidate-inspection! :probe))
   (case (:kind target)
     :story-override
     {:value          (:value target)
      :node-version   nil
      :node-key       nil
      :live?          false
      :frame-epoch    nil
      :registry-epoch @registry-epoch*}

     :subscription
     (let [{:keys [frame-id query]} target
           frame-record (frame/frame frame-id)]
       (when (nil? frame-record)
         (throw-frame-destroyed! 're-frame.substrate.observation/probe
                                 frame-id query))
       (live-frame/call-with-frame-resolution
         (live-frame/frame-resolution-target frame-id)
         (fn []
           (when (nil? (registrar/lookup :sub (first query)))
             (throw-no-such-sub! 're-frame.substrate.observation/probe
                                 frame-id query))
           (let [cache    (:sub-cache frame-record)
                 reaction (when cache (:reaction (get @cache query)))]
             (if (some? reaction)
               ;; Live node — evidence off the real cache node. Value is
               ;; read BEFORE the epochs so concurrent movement makes the
               ;; epochs look moved (over-correct), never missed.
               (let [[rec v] (observe-node! reaction)]
                 (evidence frame-id v (:version rec) (:node-key rec) true))
               ;; Cold — pure compute against the coherent frame-state
               ;; snapshot through the slice memo. No cache entry, no
               ;; watch, no disposal obligation.
               (let [fs   (frame/frame-state-value frame-id)
                     memo (slice-memo-table! slice-memo frame-id)
                     v    (subs/compute-sub-with-memo query fs memo)]
                 (evidence frame-id v nil nil false)))))))

     (throw-malformed-target! 're-frame.substrate.observation/probe target))))

;; ---- active-owner tracking (the released-handle-retention fix) ----------------
;;
;; rf2-vxgfnd.15: a released handle MUST NOT stay reachable from a live shared
;; reaction. Rather than one non-removable `interop/add-on-dispose!` closure
;; per handle — which accumulates one dormant closure per historical owner on
;; any node an owner keeps live (an unbounded leak, and O(all owners ever) at
;; the eventual disposal) — the port keeps an IDENTITY-keyed set of the node's
;; CURRENTLY-active owner handles inside the weak node record, and installs ONE
;; node-scoped disposal hook per reaction (steady state). `acquire!` enrols the
;; handle and installs the hook when none is CONFIRMED installed yet — the first
;; owner, plus any follower whose enrolment interleaves that install window
;; (rf2-vxgfnd.70), whose own hook is an independent disposal fallback; `release!`
;; de-enrols it; node disposal snapshots-and-clears the live owners and enqueues
;; each once (`take-owners!` the single-drain point, so duplicate hooks are
;; harmless). The owner set
;; rides the SAME weak record (and the same JVM lock discipline) as the version
;; bookkeeping, so it dies with the reaction — no pruning, no scan of historical
;; owners, storage O(current owners) not O(all owners ever acquired). The handle
;; is a `deftype` compared by identity, so a plain persistent set keys it by
;; identity on both hosts.
;;
;; Because the record VALUE now reaches handles (and a handle reaches its
;; reaction, the weak KEY), the value→key STRONG-reference that would defeat the
;; JVM `java.util.WeakHashMap` weak key is broken by holding the reaction WEAKLY
;; in each handle's state (`handle-reaction`; rf2-vxgfnd.37) — see the node-records
;; §REACHABILITY INVARIANT above. So even a handle left enrolled by an interrupted
;; teardown cannot pin its own node's reaction: the record dies with the
;; reaction on the JVM exactly as `js/WeakMap`'s ephemeron semantics already
;; guarantee on CLJS.

(defn- register-owner!
  "Enrol owner `handle` in `reaction`'s active-owner set within the weak node
  record (minting the record if a prior probe/observe has not — in practice
  `acquire!` observes first, so the record exists). Returns true iff the node's
  disposal hook is NOT yet CONFIRMED installed — i.e. THIS caller must install
  it (rf2-vxgfnd.70). Crucially it does NOT flip the readiness flag: readiness
  is published by [[mark-hook-installed!]] only AFTER `interop/add-on-dispose!`
  has actually registered the callback, so a fresh follower can never observe a
  ready hook before the hook exists. A follower whose enrolment interleaves the
  install window (`:hook-installed?` still unset) is told to install too — its
  own node-scoped hook is an independent disposal fallback, and duplicate hooks
  are harmless because `take-owners!` is the single-drain point. JVM: under the
  node-records lock, the same discipline the version records use."
  [reaction handle]
  #?(:clj
     (locking node-records
       (let [rec        (or (.get node-records reaction)
                            {:node-key (swap! node-key-counter inc) :version 0 :value nil})
             installed? (:hook-installed? rec)]
         (.put node-records reaction
               (assoc rec :owners (conj (or (:owners rec) #{}) handle)))
         (not installed?)))
     :cljs
     (let [rec        (or (.get node-records reaction)
                          {:node-key (swap! node-key-counter inc) :version 0 :value nil})
           installed? (:hook-installed? rec)]
       (.set node-records reaction
             (assoc rec :owners (conj (or (:owners rec) #{}) handle)))
       (not installed?))))

(defn- mark-hook-installed!
  "Publish the node's disposal-hook readiness: set `:hook-installed?` on the
  weak node record AFTER `interop/add-on-dispose!` has actually registered the
  callback (rf2-vxgfnd.70). Only from this point does a fresh follower's
  [[register-owner!]] see a confirmed hook and skip installing its own; a
  follower that enrolled earlier (in the install window) already installed an
  independent backstop hook. Idempotent — a co-staged backstop installer may
  set it too. No-op when the record has already died with a disposed reaction.
  JVM: under the node-records lock. Returns nil."
  [reaction]
  #?(:clj
     (locking node-records
       (when-let [rec (.get node-records reaction)]
         (.put node-records reaction (assoc rec :hook-installed? true))))
     :cljs
     (when-let [rec (.get node-records reaction)]
       (.set node-records reaction (assoc rec :hook-installed? true))))
  nil)

(defn- deregister-owner!
  "Remove owner `handle` from `reaction`'s active-owner set. No-op when the
  record or the handle is absent (a disposed/rebuilt node, or a handle already
  drained). JVM: under the node-records lock. Returns nil."
  [reaction handle]
  #?(:clj
     (locking node-records
       (when-let [rec (.get node-records reaction)]
         (when (contains? (:owners rec) handle)
           (.put node-records reaction (update rec :owners disj handle)))))
     :cljs
     (when-let [rec (.get node-records reaction)]
       (when (contains? (:owners rec) handle)
         (.set node-records reaction (update rec :owners disj handle)))))
  nil)

(defn- take-owners!
  "Snapshot-and-clear `reaction`'s active-owner set, returning the handles that
  were active (nil/empty when none). Called once from the node's disposal hook,
  so a re-entrant or duplicate disposal drains nothing the second time. JVM:
  under the node-records lock."
  [reaction]
  #?(:clj
     (locking node-records
       (when-let [rec (.get node-records reaction)]
         (let [owners (:owners rec)]
           (when (seq owners)
             (.put node-records reaction (assoc rec :owners #{})))
           owners)))
     :cljs
     (when-let [rec (.get node-records reaction)]
       (let [owners (:owners rec)]
         (when (seq owners)
           (.set node-records reaction (assoc rec :owners #{})))
         owners))))

(defn ^:no-doc active-owner-count
  "INTERNAL — the number of active owner handles the port currently tracks for
  `reaction` (0 when the node has no record or no live owners). Exposed
  un-private only so the port's own retention tests can assert active ownership
  returns to the current set after acquire/release churn (rf2-vxgfnd.15), on
  both hosts. Not part of the port ABI."
  [reaction]
  (count #?(:clj  (locking node-records (:owners (.get node-records reaction)))
            :cljs (:owners (.get node-records reaction)))))

(defn- drain-owners!
  "Snapshot-and-clear `reaction`'s active owners and enqueue a former-owner
  disposal notification for each still-live handle. `take-owners!` is the
  SINGLE-DRAIN point (idempotent under the node-records lock), so whichever of
  the node's disposal hook OR `acquire!`'s handshake re-check (rf2-vxgfnd.32)
  reaches a disposed node first drains its owners; the other call finds an empty
  set and no-ops — every active owner is enqueued exactly once. Delivery is
  QUEUED (never a synchronous `on-change`) and rides the drain boundary (see the
  ns docstring's HMR section)."
  [reaction]
  (doseq [handle (take-owners! reaction)]
    (when (= :live (:status @(handle-state handle)))
      (enqueue-disposal! handle)))
  nil)

(defn- node-disposed-hook
  "The ONE node-scoped on-dispose callback for `reaction` (installed exactly
  once, on the first owner). On node disposal it drains the CURRENT active
  owners and enqueues a former-owner notification for each still-live handle —
  coalesced once per handle at the drain boundary (see the ns docstring's HMR
  section). It closes over the reaction only, never a handle, so released owners
  (de-enrolled by `release!`) are unreachable from it."
  [reaction]
  (fn observation-node-disposed []
    (drain-owners! reaction)))

(defn- node-still-canonical?
  "True when the handle's acquired reaction is still the frame's live cache node
  for its query — i.e. not disposed, not evicted, not rebuilt. A false result
  means a disposal / eviction / HMR-replacement has linearized: EVERY disposal
  path in `re-frame.subs.cache` evicts the cache entry (a cache-atom swap/reset)
  BEFORE it calls `interop/dispose!`, and the cache atom's volatile semantics
  order that eviction before this read — so once a reaction has been disposed it
  is no longer the entry's `:reaction`. This is the authority `acquire!`'s
  first-owner handshake (rf2-vxgfnd.32) and the `current?` kept-check both read."
  ([state]
   ;; Derive the strong reaction (held WEAKLY in the state on the JVM —
   ;; rf2-vxgfnd.37). A collected reaction is nil and can never be the live
   ;; canonical node, so the `when-let` short-circuits — never falling into the
   ;; `(identical? nil (:reaction absent-entry))` ≡ `(identical? nil nil)` trap.
   (boolean
     (when-let [rx (handle-reaction state)]
       (node-still-canonical? state rx))))
  ([{:keys [frame-id query-v]} rx]
   (boolean
     (when-let [cache (:sub-cache (frame/frame frame-id))]
       (identical? rx (:reaction (get @cache query-v)))))))

;; ---- acquire! -------------------------------------------------------------------

;; `build-node-handle!`'s install-failure path reuses the full `release!`
;; teardown (ref-count / watch / enrolment), which is defined below.
(declare release!)

(defn- make-watch-handler
  "Per-handle change watch: constant-work — advance the node record with the
  DELIVERED new value (no recompute, per I-5) and fan the mark-dirty payload
  to this handle's own `on-change`.

  The fan-out is gated on the SAME movement law that governs node-version
  advancement — the core-local `node-value=` `rf=` spelling, NOT raw `not=`
  (rf2-vxgfnd.185). A watchable host notifies its watches on EVERY write,
  value-blind, so a NaN→NaN recompute fires this callback with `prev`/`nu`
  both NaN; raw `not=` treats NaN≠NaN and would fan a `:cause :subscription`
  notification for NO value movement, while `advance-node-record!` (also
  `node-value=`) leaves the version unchanged — a value-movement notification
  without value movement that dirties a downstream ViewCell against a stable
  node. Sharing `node-value=` here keeps the two decisions from drifting."
  [state]
  (fn observation-watch [_key _ref prev nu]
    (let [st @state]
      (when (and (= :live (:status st))
                 (not (node-value= prev nu)))
        ;; The watch only fires while its reaction is live, so `handle-reaction`
        ;; resolves; the `when-some` is belt-and-braces for a JVM weak reaction.
        (when-some [rx (handle-reaction st)]
          (let [rec   (advance-node-record! rx nu)
                last' {:value nu :version (:version rec) :node-key (:node-key rec)}]
            (swap! state assoc :last last')
            (fan-out! (:on-change st)
                      {:cause          :subscription
                       :target         (:target st)
                       :node-key       (:node-key rec)
                       :node-version   (:version rec)
                       :frame-epoch    (frame/frame-commit-epoch (:frame-id st))
                       :registry-epoch @registry-epoch*})))))))

(defn- build-node-handle!
  "Wrap a CANONICAL cached `reaction` — one `subs/acquire-cache-reaction!` has
  already taken a real +1 reference on — in a live NODE handle. Register the
  per-handle change watch (on watchable hosts; `add-watch` never fires
  synchronously), take the baseline observation through the activated node,
  enrol the handle as an active owner, and — whenever the node hook is not yet
  CONFIRMED installed — install the node's disposal hook (delivery is queued;
  see the ns docstring's HMR section). `release!` de-enrols the handle, so a
  released handle is no longer reachable from the live reaction: disposal work
  stays O(current owners), never O(all owners ever acquired) (rf2-vxgfnd.15).

  Publishes readiness HONESTLY (rf2-vxgfnd.70): [[mark-hook-installed!]] sets
  the record's `:hook-installed?` flag only AFTER `add-on-dispose!` has actually
  registered the callback — never as the handle enrols — so a fresh follower can
  never observe a ready hook before the hook exists. A concurrent follower whose
  enrolment interleaves this install window ([[register-owner!]] still saw the
  flag unset) installs its OWN node-scoped hook: an independent fallback that
  observes disposal without waiting for the first owner, so no owner is ever
  published behind a not-yet-installed hook. Duplicate hooks are harmless
  because `take-owners!` is the single-drain point.

  Closes the disposed-before-my-install window with the CANONICALITY RE-CHECK
  (rf2-vxgfnd.32): since every disposal path evicts the cache entry BEFORE
  `interop/dispose!` (`re-frame.subs.cache`), a reaction that is no longer the
  frame's canonical node WAS disposed in the window; self-drain the staged
  owners so each is enqueued exactly once (`take-owners!` is the single-drain
  point; a hook that did fire already drained). No synchronous `on-change`: the
  drain only ENQUEUES — delivery rides the next-tick / HMR drain boundary off
  the acquire stack, preserving acquire!'s no-fan-out guarantee.

  If `add-on-dispose!` itself throws, this owner has no hook of its own: tear it
  down cleanly (`release!` balances ref-count / watch / enrolment) and rethrow,
  leaving `:hook-installed?` unset so the node is not poisoned — a co-staged
  backstop owner keeps its own hook and future acquirers install afresh. Returns
  the handle."
  [target frame-id query reaction on-change]
  (let [state (atom {:handle-kind :node
                     :target     target
                     :frame-id   frame-id
                     :query-v    query
                     ;; Held WEAKLY on the JVM so the process-global weak
                     ;; node-records table's value cannot strong-reference its
                     ;; own weak key back into liveness (rf2-vxgfnd.37); plain
                     ;; on CLJS (js/WeakMap is ephemeron). Derived via
                     ;; `handle-reaction` at every read.
                     :reaction   (weak-reaction-ref reaction)
                     :on-change  on-change
                     :status     :live})
        handle (->ObservationHandle state)]
    ;; Watch BEFORE the baseline observe: a watched reaction is live on the
    ;; reactive hosts, so the observe below reads through the activated node.
    (when (watchable? reaction)
      (let [wk (gensym "rf-obs-handle")]
        (add-watch reaction wk (make-watch-handler state))
        (swap! state assoc :watch-key wk)))
    (let [[rec v] (observe-node! reaction)]
      (swap! state assoc :last {:value    v
                                :version  (:version rec)
                                :node-key (:node-key rec)}))
    (when (register-owner! reaction handle)
      ;; Install the node's disposal hook, THEN publish readiness
      ;; (rf2-vxgfnd.70): a fresh follower never observes a ready hook before
      ;; the callback exists, and an install-window follower installs its OWN
      ;; backstop hook (idempotent via take-owners!). On install failure, tear
      ;; THIS owner down cleanly and rethrow — readiness stays unpublished so
      ;; the node is not poisoned.
      (try
        (interop/add-on-dispose! reaction (node-disposed-hook reaction))
        (mark-hook-installed! reaction)
        (catch #?(:clj Throwable :cljs :default) t
          (release! handle)
          (throw t))))
    (when-not (node-still-canonical? @state)
      (drain-owners! reaction))
    handle))

(def ^:private max-displacement-retries
  "Retry budget for acquire!'s live-cache-displacement retarget (rf2-vxgfnd.63).
  A DISPLACEMENT — the just-built canonical node invalidated-and-rebuilt (an HMR
  sub re-registration or an explicit cache clear) in the build→canonical-check
  window while the frame stays LIVE — is a NORMAL condition, not frame
  destruction, so acquire! retargets by re-running `subs/acquire-cache-reaction!`
  against the now-current cache. Each real displacement is a discrete finite
  event whose very next build settles a canonical node, so convergence is
  effectively immediate (attempt 2 wins); the retry is additionally gated on the
  targeted incarnation staying live (`targeted-incarnation-still-live?`), so any
  incarnation movement terminates it at once. This fixed budget is the belt: it
  forecloses an unbounded live-lock under a pathological displacer that could
  otherwise re-race every window, guaranteeing acquire! cannot spin forever."
  32)

(defn- targeted-incarnation-still-live?
  "True when `frame-id`'s CURRENTLY-live incarnation is identical to the
  `incarnation` token captured at acquire entry — i.e. the SAME frame
  incarnation acquire! is targeting is still live (rf2-vxgfnd.63). A sub
  re-registration or an explicit cache clear leaves the frame record — and its
  `:drain-lock` incarnation token — untouched, so a live-cache displacement
  reads true here (retarget). A nil-or-changed token means the targeted
  incarnation was destroyed, or destroyed-and-reincarnated under a reused id;
  either is a VERIFIED destruction of the targeted incarnation (frame-destroyed),
  never a displacement."
  [frame-id incarnation]
  (let [now (frame/frame-incarnation-token frame-id)]
    (and (some? now) (identical? now incarnation))))

(defn- throw-retry-exhausted!
  "rf2-vxgfnd.79 — `acquire!`'s bounded live-cache-displacement retry
  ([[max-displacement-retries]]) exhausted its budget while the targeted frame
  incarnation stayed VERIFIABLY LIVE (`targeted-incarnation-still-live?` held on
  every attempt): the sub-cache kept displacing the just-built canonical node — a
  pathological-but-legal storm of HMR re-registrations / explicit cache clears —
  winning every build→canonical-check window. That is an acquire-path LIVELOCK,
  NOT a destruction: the code just PROVED the incarnation is alive, so the port
  reports the TRUTHFUL always-on `:rf.error/observation-retry-exhausted` and
  throws it (fanned before the throw so a boundary-swallowed throw still reaches
  off-box shippers, like the sibling `:rf.error/frame-destroyed`), NEVER lying
  `:rf.error/frame-destroyed` for a frame it knows is live. `attempts` is the
  number of build attempts made (the budget + 1). The ViewCell maps the throw to
  the view error boundary; the actionable recovery is to retry the commit once the
  re-registration / cache-clear storm settles (a persistent storm is a
  registration / tooling bug). Carries `:attempts` / `:max-retries` /
  `:frame-incarnation :live` evidence alongside `:frame` / `:rf.sub/query-v`.
  Never returns."
  [frame-id query-v attempts]
  (emit-and-throw!
    :rf.error/observation-retry-exhausted
    're-frame.substrate.observation/acquire!
    frame-id query-v
    (str "acquire! exhausted its live-cache displacement retry budget ("
         max-displacement-retries " retries, " attempts " build attempts) for "
         (pr-str (first query-v)) " while frame " frame-id "'s targeted "
         "incarnation stayed live: the sub-cache kept displacing the just-built "
         "canonical node (a rapid HMR re-registration / cache-clear storm) every "
         "build->canonical-check window. The frame is NOT destroyed — retry the "
         "commit once the storm settles; a persistent storm is a registration / "
         "tooling bug to fix.")
    {:attempts          attempts
     :max-retries       max-displacement-retries
     :frame-incarnation :live}))

(defn acquire!
  "Commit-only: acquire ownership of `target`, returning a HANDLE — the owner
  token (identity equality, never `=`).

  For a `:subscription` target the canonical node is RE-RESOLVED by
  `(frame, query)` here — the captured target is an identity, never a node
  handle, so an HMR-disposed render-time node can never be pinned. The
  acquire is the cache's ref-count attach (Spec 006 §Lookup algorithm; a
  miss builds the node through the real cache-install path), plus per-handle
  callback registration: a unique change watch (on watchable hosts). The handle
  is also enrolled as an active owner behind the node's disposal hook (one hook
  per node in steady state); `release!` de-enrols it, so a released handle never
  leaks a dormant closure (rf2-vxgfnd.15). Readiness is published only AFTER the
  hook is actually installed, and a follower that enrols in the install window
  installs its own independent backstop hook rather than trusting a not-yet-
  installed one (rf2-vxgfnd.70); a disposal that races the install is also
  caught by a canonicality re-check that self-drains the staged owners
  (rf2-vxgfnd.32 — see the ns docstring's HMR section), so no acquired handle is
  left behind an uninstalled/dead hook without its invalidation. `acquire!`
  never invokes `on-change` synchronously — no
  fan-out during acquire (movement in the render→commit gap is the commit
  evidence comparison's job, invariant 5).

  `on-change` MUST be constant-work (mark-dirty with the payload
  `{:cause :subscription|:hmr|:disposed :target … :node-key … :node-version …
  :frame-epoch … :registry-epoch …}`; it never computes — I-5).

  For a `:story-override` target returns the STATIC handle: `:owned? false`
  reported honestly, `read` yields the pinned value/version, `release!`
  no-ops, and NO callback is registered (a pinned value never invalidates);
  `current?` fails when the site's override id/version moved, which
  retargets through the normal staged commit path.

  Fail-loud: `:rf.error/frame-destroyed`, `:rf.error/no-such-sub` (entry),
  `:rf.error/reentrant-graph-op` (dev — called from inside the fan-out). When
  the entry node's OWN build cannot produce a canonical cache node — a cyclic
  entry sub, a parametric `input-fn` failure, or a frame destroyed mid-build —
  `acquire!` throws the matching typed error (`:rf.error/sub-cycle`,
  `:rf.error/sub-input-fn-exception` / `:rf.error/sub-input-fn-bad-return`,
  `:rf.error/frame-destroyed`) rather than handle a zero-ref recovery reaction
  (rf2-vxgfnd.27): a handle MUST NOT report `owned?` without a real cache ref +
  attach. `:rf.error/frame-destroyed` is reserved for a VERIFIED destruction of
  the targeted frame incarnation: a `:frame-destroyed` recovery over a STILL-LIVE
  incarnation is a live-cache DISPLACEMENT (the node invalidated-and-rebuilt in
  the build→canonical-check window — HMR re-registration or an explicit cache
  clear), NOT a teardown, so `acquire!` retargets to the current canonical node
  (bounded retry, gated on the incarnation staying live) instead of throwing
  (rf2-vxgfnd.63). If that bounded budget is EXHAUSTED while the incarnation is
  still verifiably live (a pathological displacement storm winning every window),
  `acquire!` throws the TRUTHFUL `:rf.error/observation-retry-exhausted` — a
  livelock, never `:rf.error/frame-destroyed` for a frame it just proved alive
  (rf2-vxgfnd.79). The static override handle and the public subscribe surface
  keep their recover-to-nil semantics."
  [target on-change]
  (assert-not-in-fan-out! 're-frame.substrate.observation/acquire!)
  ;; Grammar gate FIRST (rf2-vxgfnd.183): reject a malformed target — including a
  ;; supported-kind-but-INCOMPLETE {:kind :story-override} that would otherwise
  ;; mint a nil-shaped static handle — with the typed
  ;; :rf.error/observation-malformed-target before any ownership host op.
  (validate-target! 're-frame.substrate.observation/acquire! target)
  (case (:kind target)
    :story-override
    (->ObservationHandle (atom {:handle-kind :static
                               :target     target
                               :status     :live}))

    :subscription
    (let [{:keys [frame-id query]} target]
      (when (nil? (frame/frame frame-id))
        (throw-frame-destroyed! 're-frame.substrate.observation/acquire!
                                frame-id query))
      (live-frame/call-with-frame-resolution
        (live-frame/frame-resolution-target frame-id)
        (fn []
          (when (nil? (registrar/lookup :sub (first query)))
            (throw-no-such-sub! 're-frame.substrate.observation/acquire!
                                frame-id query))))
      ;; Capture the targeted frame incarnation while it is verified live
      ;; (rf2-vxgfnd.63). A :frame-destroyed recovery below is disambiguated
      ;; against THIS token, so :rf.error/frame-destroyed is reserved for a
      ;; VERIFIED destruction of the targeted incarnation.
      (let [incarnation (frame/frame-incarnation-token frame-id)]
        (loop [attempt 0]
          (let [result (subs/acquire-cache-reaction! frame-id query)]
            (if-some [reaction (:reaction result)]
              ;; Canonical cached node — the real ref-count attach. Take
              ;; ownership (watch + baseline observe + first-owner disposal-hook
              ;; handshake, rf2-vxgfnd.32/.15).
              (build-node-handle! target frame-id query reaction on-change)
              ;; No canonical node. The build handed back a NEVER-CACHED, zero-ref
              ;; recovery reaction; there is no node to attach to — `acquire!` IS
              ;; the ref-count attach — so the port is fail-loud rather than handle
              ;; an owned?-true reaction that owns nothing (rf2-vxgfnd.27). But
              ;; first DISAMBIGUATE a live-cache DISPLACEMENT from genuine frame
              ;; destruction (rf2-vxgfnd.63): `subs/build-and-classify!` maps a
              ;; just-built node that was invalidated-and-rebuilt (an HMR sub
              ;; re-registration or an explicit cache clear) in the
              ;; build→canonical-check window to :frame-destroyed via its :else
              ;; fallback — conflating a NORMAL displacement (the frame is LIVE,
              ;; the node merely displaced by a newer canonical node) with a real
              ;; teardown. When the targeted incarnation is still live, the node
              ;; was displaced, not destroyed: retarget by re-running
              ;; acquire-cache-reaction! against the now-current cache (the very
              ;; next build settles a canonical node), bounded by the retry budget
              ;; so a pathological displacer cannot spin acquire! forever. Only a
              ;; nil/changed incarnation (verified destruction) or a
              ;; non-displacement recovery (cycle / input-fn failure —
              ;; deterministic, retry is futile) throws :frame-destroyed / the
              ;; matching typed error; an EXHAUSTED budget over a still-LIVE
              ;; incarnation throws the TRUTHFUL retry-exhausted livelock
              ;; (rf2-vxgfnd.79), never :frame-destroyed for a frame just proved
              ;; alive.
              (if (and (= :frame-destroyed (:recovery result))
                       (targeted-incarnation-still-live? frame-id incarnation))
                (if (< attempt max-displacement-retries)
                  (recur (inc attempt))
                  (throw-retry-exhausted! frame-id query (inc attempt)))
                (throw-acquire-recovery! frame-id query result)))))))

    (throw-malformed-target! 're-frame.substrate.observation/acquire! target)))

;; ---- current? -------------------------------------------------------------------

(defn current?
  "The commit kept-check, one predicate: `handle` still exactly covers
  `target` ≡ not released ∧ node not disposed ∧ same frame ∧ same stabilized
  query. An unchanged live handle is retained untouched by the caller; a
  disposed node (HMR), a destroyed/swapped frame, a restabilized query, or a
  moved/removed override fails the check and classifies the site as
  retargeted. TOTAL, pure read; never throws.

  Static (override) handles compare the consumer's OPAQUE slot identity by
  `=` and its OPAQUE movement token by the core-local spelling of the frozen
  `rf=` law. Thus an `rf=`-equal provider replacement (including NaN→NaN)
  retains, while any value/schema movement retargets, without this port
  interpreting either token. Those slot/movement tokens (and a subscription
  query's app args) are APP-SUPPLIED, so their host equality implementation
  MAY THROW: a comparison that cannot ESTABLISH sameness classifies the site
  as NOT current — the conservative kept-check result, since a site that
  cannot be proven kept is retargeted through the normal staged commit path,
  never painted stale — so the throw never propagates out of the predicate
  (rf2-sbfqy)."
  [handle target]
  ;; TOTAL no-throw predicate (rf2-vxgfnd.183 + rf2-sbfqy): a non-handle reads
  ;; FALSE rather than field-accessing handle-state and leaking a host error, and
  ;; a THROWING opaque-token equality (the app-supplied :override-id/:version, or
  ;; a subscription query's app args) is caught and read as NOT current. current?
  ;; is the commit kept-check, and a value that cannot be PROVEN kept is simply
  ;; "not current". `and` short-circuits before `@(handle-state handle)` so a
  ;; non-handle never derefs; the `try` guards the ONE kept-check-comparison seam
  ;; so no raw equality throw escapes. The catch is LOCAL to this predicate — the
  ;; port's non-predicate ops (probe/acquire!/read/release!) keep their typed
  ;; fail-loud contracts, never blanket-swallowing equality failures.
  (when interop/debug-enabled? (note-candidate-inspection! :current?))
  (and (handle? handle)
       (try
         (let [st @(handle-state handle)]
           (case (:handle-kind st)
             :static
             (let [lt (:target st)]
               (and (= :story-override (:kind target))
                    (= (:override-id lt) (:override-id target))
                    (node-value= (:version lt) (:version target))
                    (= (:query lt) (:query target))))

             :node
             (and (= :live (:status st))
                  (= :subscription (:kind target))
                  (= (:frame-id st) (:frame-id target))
                  (= (:query-v st) (:query target))
                  (node-still-canonical? st))

             false))
         (catch #?(:clj Throwable :cljs :default) _
           ;; A throwing opaque-token equality cannot ESTABLISH sameness →
           ;; conservatively NOT current, so the site retargets through the
           ;; normal staged commit path rather than escaping the predicate
           ;; (rf2-sbfqy).
           false))))

;; ---- read -----------------------------------------------------------------------

(defn read
  "Commit-side read through a handle. On a live node handle, derefs the owned
  node fresh and returns `{:value v :version n :node-key nk :frame-epoch fe
  :registry-epoch re}` (the `:node-key`/epoch keys are additive — they hand
  the commit reconciler its invariant-5 comparison inputs without a second
  probe). On the static override handle, returns the pinned `{:value v
  :version n}`.

  `:node-key` carries the acquired node's process-unique IDENTITY (the same
  key `probe` already emits), so the reconciler can tell the node the RENDER
  observed apart from a DIFFERENT node acquired at COMMIT — including a same-id
  frame REINCARNATION (destroy + recreate) whose node-version and
  frame/registry epochs coincide with the destroyed incarnation's:
  `frame/dissoc-frame!` restarts the frame's commit epoch, so version+epoch
  ALONE can tie across incarnations (rf2-vxgfnd.14). A reincarnated frame
  builds a fresh reaction, which mints a strictly-greater node-key, so a
  changed key is MOVEMENT the reconciler MUST correct before paint even when
  version+epoch coincide. The unchanged-node fast path is preserved: the same
  live node reads the same key/version/epochs, so no correction fires.

  `read` on a RELEASED handle throws `:rf.error/read-after-release` — always,
  production included; it is a substrate bug, unreachable in correct
  generated code (the render path checks `current?` first). A live handle
  whose node was disposed out from under it (HMR mid-gap) returns its last
  committed observation rather than touching the disposed node — `current?`
  is the gate that routes such a site to retarget."
  [handle]
  ;; Handle grammar gate FIRST (rf2-vxgfnd.183): reject a non-handle (nil / a map /
  ;; any host object) with the typed :rf.error/observation-malformed-handle before
  ;; handle-state field-accesses + derefs it and leaks a raw NPE / TypeError.
  (validate-handle! 're-frame.substrate.observation/read handle)
  (when interop/debug-enabled? (note-candidate-inspection! :read))
  (let [st @(handle-state handle)]
    (case (:handle-kind st)
      :static
      (let [t (:target st)]
        {:value (:value t) :version (:version t)})

      :node
      (do
        (when (= :released (:status st))
          (let [reason (str "the observation port read a handle after release!"
                            " for " (pr-str (:query-v st)) " in frame "
                            (:frame-id st) "; this is a substrate bug — the "
                            "generated commit path must current?-check before "
                            "reading and never read a released handle.")]
            (error-emit/emit-error-both!
              :rf.error/read-after-release
              (:query-v st) (first (:query-v st)) (:frame-id st)
              nil 0 (interop/now-ms)
              {:where    're-frame.substrate.observation/read
               :frame    (:frame-id st)
               :rf.sub/query-v (:query-v st)
               :reason   reason
               :recovery :no-recovery})
            (throw
              (attest-provenance!
                (error/thrown-ex-info
                  :rf.error/read-after-release
                  're-frame.substrate.observation/read
                  reason
                  {:extra {:frame          (:frame-id st)
                           :rf.sub/query-v (:query-v st)}})
                ;; Throwable-bound emission provenance (rf2-9m4oy7): the
                ;; emit-error-both! record above is the exactly-once emission on
                ;; BOTH axes, attributed to THIS released handle's own frame/query.
                ;; Binding it to THIS exact throwable lets a containment drain
                ;; catching this throw (an on-change reading a released handle) see
                ;; always-on coverage and not re-fan it under the notifying owner's
                ;; context — while the SAME token transplanted onto a different
                ;; exception reads uncovered.
                provenance-both-channels))))
        ;; Resolve the JVM WeakReference ONCE and hold the result strongly across
        ;; the canonicality check + deref. A GC between two lookups could clear
        ;; the second lookup even after the first proved the node canonical.
        (let [rx (handle-reaction st)]
          (if (and (some? rx) (node-still-canonical? st rx))
            (let [[rec v] (observe-node! rx)]
              (swap! (handle-state handle) assoc
                     :last {:value v :version (:version rec)
                            :node-key (:node-key rec)})
              {:value          v
               :version        (:version rec)
               :node-key       (:node-key rec)
               :frame-epoch    (frame/frame-commit-epoch (:frame-id st))
               :registry-epoch @registry-epoch*})
            (let [{:keys [value version node-key]} (:last st)]
              {:value          value
               :version        version
               :node-key       node-key
               :frame-epoch    (frame/frame-commit-epoch (:frame-id st))
               :registry-epoch @registry-epoch*})))))))

;; ---- release! -------------------------------------------------------------------

(defn release!
  "Synchronously release `handle` — the subscriber detach of Spec 006
  §Reference counting and disposal: the handle's change watch is removed, the
  handle is de-enrolled from the node's active-owner set (so a released handle is
  no longer reachable from the live reaction — rf2-vxgfnd.15), and the cache
  ref-count decremented under an IDENTITY GUARD (only while the
  cache still holds the handle's own reaction), so the 1 → 0 edge disposes
  the node in-tick and a node disposed-or-rebuilt out from under the handle
  is a no-op (its reference died with the eviction). Idempotent — a second
  `release!` no-ops; the static override handle no-ops entirely. Never
  invokes `on-change` (no fan-out during release). Dev-asserted
  `:rf.error/reentrant-graph-op` from inside the fan-out. The live→released
  transition also nils the handle's `:reaction` and `:on-change` — both unused
  after release — so a consumer-retained released handle pins neither the
  on-change closure nor (on CLJS) the disposed reaction (rf2-x76af2.34). Returns
  nil."
  [handle]
  (assert-not-in-fan-out! 're-frame.substrate.observation/release!)
  ;; Handle grammar gate (rf2-vxgfnd.183): reject a non-handle with the typed
  ;; :rf.error/observation-malformed-handle before handle-state field-accesses it —
  ;; (release! nil) must throw typed, never a raw NPE / untyped host error.
  (validate-handle! 're-frame.substrate.observation/release! handle)
  (let [state (handle-state handle)
        [old _new] (swap-vals! state
                               (fn [st]
                                 (if (and (= :node (:handle-kind st))
                                          (= :live (:status st)))
                                   ;; Drop the reaction + on-change refs as the
                                   ;; handle goes released (rf2-x76af2.34 FINDING
                                   ;; 2): both are unused after release (read /
                                   ;; current?/notify all short-circuit on
                                   ;; :released, and read-after-release needs
                                   ;; only :query-v/:frame-id), so a
                                   ;; consumer-retained released handle pins
                                   ;; neither the on-change closure (either host)
                                   ;; nor the CLJS reaction. #5753's .37 already
                                   ;; broke the JVM reaction strong-pin (weak
                                   ;; ref); this drops the remaining dangling
                                   ;; refs on both hosts.
                                   (assoc st :status :released
                                             :reaction nil :on-change nil)
                                   st)))]
    ;; We won the live→released transition iff `old` was a live node handle —
    ;; exactly-once teardown under the same swap-vals! discipline the cache
    ;; uses.
    (when (and (= :node (:handle-kind old))
               (= :live (:status old)))
      (let [{:keys [watch-key frame-id query-v]} old
            ;; Derived weakly on the JVM (rf2-vxgfnd.37); nil iff the reaction
            ;; was already collected (an abandoned node the handle outlived), in
            ;; which case every teardown step below is a correct no-op — the
            ;; reference died with the eviction, exactly as for a rebuilt node.
            reaction (handle-reaction old)]
        (when (and watch-key reaction)
          (remove-watch reaction watch-key))
        ;; De-enrol this handle from the node's active-owner set so a released
        ;; handle is no longer reachable from the live reaction — the node's
        ;; single disposal hook then never sees it (rf2-vxgfnd.15).
        (when reaction
          (deregister-owner! reaction handle))
        (when-let [cache (:sub-cache (frame/frame frame-id))]
          (let [[o n] (swap-vals! cache
                                  (fn [m]
                                    (if-let [entry (get m query-v)]
                                      (if (identical? reaction (:reaction entry))
                                        (assoc-in m [query-v :ref-count]
                                                  (max 0 (dec (or (:ref-count entry) 1))))
                                        m)
                                      m)))
                dropped-to-zero?
                (and (identical? reaction (get-in n [query-v :reaction]))
                     (= 1 (or (get-in o [query-v :ref-count]) 1))
                     (zero? (or (get-in n [query-v :ref-count]) 0)))]
            (when dropped-to-zero?
              (subs-cache/dispose-entry-now! cache query-v frame-id)))))))
  nil)

;; ---- registrar hooks --------------------------------------------------------------
;;
;; Installed once at ns load (defonce), mirroring `re-frame.subs.cache`'s
;; hot-reload hook. ORDER MATTERS for the replacement hook: registrar hooks
;; run in registration order, and this ns requires `re-frame.subs` (which
;; requires `re-frame.subs.cache`), so the cache's invalidation hook is
;; ALWAYS registered first — by the time this hook drains, the registry
;; mutation AND the cache eviction (which enqueued the former owners via the
;; reactions' dispose hooks) have completed. That IS "the notification
;; boundary the re-registration closes": queued, coalesced once per handle,
;; never delivered mid-registry-mutation.

(defonce ^:private _registrar-hooks
  (do
    ;; FIRST-TIME `:sub` registrations bump the registry epoch here. The
    ;; registration hook fires on EVERY register! (first-time AND replacement),
    ;; so the `(nil? was)` guard restricts THIS bump to first-time registrations;
    ;; a re-registration's bump rides the replacement hook below instead. That
    ;; split is load-bearing for the `:hmr` disposal notification (rf2-vxgfnd.36):
    ;; registrar runs replacement hooks BEFORE registration hooks, so bumping a
    ;; re-registration here (last) would leave the earlier `:hmr` drain reading a
    ;; STALE pre-bump epoch — one the consumer diffing notification-epoch vs the
    ;; next probe-epoch would misread as phantom registry movement.
    (registrar/add-registration-hook!
      (fn observation-registry-epoch-hook [{:keys [kind was]}]
        (when (and (= :sub kind) (nil? was))
          (swap! registry-epoch* inc))))
    ;; A `:sub` RE-REGISTRATION bumps the epoch FIRST, THEN drains the queued
    ;; `:hmr` disposal notifications — so each notification's `:registry-epoch`
    ;; equals the value a probe issued right after the re-registration reports
    ;; (no phantom movement — rf2-vxgfnd.36). This replacement hook is registered
    ;; AFTER `re-frame.subs.cache`'s invalidation hook (require order), so the
    ;; cache eviction that enqueued the former owners has already completed; the
    ;; bump-then-drain keeps the whole re-registration internally consistent (an
    ;; in-drain probe, and the notification epoch, agree).
    (registrar/add-replacement-hook!
      (fn observation-hmr-drain-hook [{:keys [kind]}]
        (when (= :sub kind)
          (swap! registry-epoch* inc)
          (drain-pending-disposals! :hmr))))
    :installed))
