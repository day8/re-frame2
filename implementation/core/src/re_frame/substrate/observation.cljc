(ns re-frame.substrate.observation
  "The internal observation port — the six-operation read-side protocol the
  compiled UI substrate (`day8/re-frame2-ui`) uses over the REAL per-frame
  sub-cache. Per Spec 006 §The internal observation port (adapter-internal).

  ADAPTER-INTERNAL. This namespace is NOT public API, NOT part of the closed
  ten-fn adapter contract, and NOT consumable by apps or adapters — its sole
  consumer is the `day8/re-frame2-ui` view runtime (the ViewCell/commit
  reconciler), riding the R-6 lockstep release train with core. The
  [[port-abi-version]] guard makes artifact drift a boot error.

  The six operations:

      (resolve-target site-ctx)     ; render: the ONLY resolution point → target
      (probe target ?slice-memo)    ; render: pure evidence read
      (acquire! target on-change)   ; commit-only: re-resolves canonical node,
                                    ;   +1 owner → lease
      (current? lease target)       ; the commit kept-check, one predicate
      (read lease)                  ; => {:value v :version n …}; typed error
                                    ;   after release
      (release! lease)              ; synchronous, idempotent

  ## Mapping onto the cache contract

  `acquire!` is the ref-count attach of Spec 006 §Lookup algorithm plus
  callback registration; `release!` is the subscriber detach of §Reference
  counting and disposal (identity-guarded, so a node disposed-and-rebuilt out
  from under a lease is never double-decremented); `probe` is an
  ownership-free read with no prior public name (`subscribe-once`
  attaches-and-detaches; `probe` never attaches). `resolve-target` and
  `current?` are the capture and kept-check layer a concurrent host requires.

  ## Ownership discipline (the six frozen invariants)

  Render resolves and probes WITHOUT ownership: `probe` takes no ref-count,
  registers no watch or callback, and materialises no cache node — a COLD
  probe (no live node) computes pure against the frame's current frame-state
  snapshot through the slice-scoped memo and retains NOTHING (the 10k-cold-
  probes-retain-zero fixture pins it). Ownership is commit-only: `acquire!`
  bumps the cache ref-count, registers the per-lease change watch (on hosts
  whose derived values are watchable), and enrols the lease as an active owner
  behind the node's single, once-installed disposal hook (`release!` de-enrols
  it, so a released lease retains no dormant closure — rf2-vxgfnd.15).

  ## Evidence, versions, and epochs

  Probe evidence and `read` carry three movement signals the commit-side
  evidence comparison (invariant 5) uses:

    - `:node-version` — a per-node counter this port advances whenever it
      OBSERVES the node's value change by `rf=` (at probe/acquire/read and on
      watch fires). Node bookkeeping lives in a WEAK identity-keyed side
      table (`js/WeakMap` / `java.util.WeakHashMap`), so records die with
      their reactions — no pruning, no retention.
    - `:frame-epoch` — `re-frame.frame/frame-commit-epoch`: bumped once per
      physical frame-state install. Any durable-state movement in the
      render→commit gap moves it, so a version tie on a multi-move gap is
      still caught (the belt-and-braces the two-guard rule leans on).
    - `:registry-epoch` — bumped on every `:sub` registration (first-time or
      replacement), so an HMR re-registration in the gap is visible.

  `read` on a node lease additionally returns the CURRENT `:frame-epoch` /
  `:registry-epoch` (additive keys — the frozen `{:value v :version n}`
  contract is unchanged) so the commit reconciler gets its step-5 comparison
  inputs from the one acquire-time read instead of a second probe.

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

  Value-movement `on-change` notifications ride a per-lease `add-watch` on
  the cache node's derived value, and therefore exist exactly where the
  substrate's derived values are watchable (the Reagent family and the React
  spine). The headless hosts (plain-atom JVM/CLJS, test-react) ship
  IDeref-only derived values with no reactive commit loop, so movement there
  is detected at the port's read points (the commit evidence comparison) —
  the honest headless posture, documented rather than simulated.

  ## HMR-disposal notifications

  Sub re-registration disposes the canonical node then notifies former
  owners ONCE with cause `:hmr`: the reaction's dispose hook ENQUEUES the
  live leases, and the queue drains at the notification boundary the
  re-registration closes (this ns's registrar replacement hook — registered
  AFTER `re-frame.subs.cache`'s invalidation hook by require order, so the
  drain runs once the registry mutation + cache eviction completed, never
  mid-mutation), coalesced once per lease. Non-registrar disposal paths
  (frame-destroy, explicit cache clears) drain on the next tick with cause
  `:disposed`."
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

;; ---- ABI version guard -----------------------------------------------------

(def port-abi-version
  "Integer ABI version of this port. `day8/re-frame2-ui` records the version
  it compiled against and asserts it at load via [[assert-port-abi-version!]],
  failing loudly on skew — artifact drift is a boot error, never undefined
  behaviour. Per Spec 006 §The internal observation port §Scope."
  1)

(defn assert-port-abi-version!
  "Boot guard for the sole consumer: throw `:rf.error/observation-port-
  version-mismatch` (always-on; also fanned through the production error-emit
  axis) when `expected` ≠ [[port-abi-version]]. Returns nil on a match."
  [expected]
  (when (not= expected port-abi-version)
    (let [reason (str "re-frame2-ui was compiled against observation-port ABI "
                      "version " (pr-str expected) " but this core exports "
                      port-abi-version "; core and re-frame2-ui release on a "
                      "lockstep train — align the two artifact versions.")]
      (error-emit/emit-error-both!
        :rf.error/observation-port-version-mismatch
        nil nil nil nil 0 (interop/now-ms)
        {:where    're-frame.substrate.observation/assert-port-abi-version!
         :expected expected
         :actual   port-abi-version
         :reason   reason
         :recovery :no-recovery})
      (error/throw-error!
        :rf.error/observation-port-version-mismatch
        're-frame.substrate.observation/assert-port-abi-version!
        reason
        {:extra {:expected expected
                 :actual   port-abi-version}})))
  nil)

;; ---- registry epoch ---------------------------------------------------------

(defonce ^:private registry-epoch*
  ;; Monotonic count of `:sub` registrations (first-time AND replacement) —
  ;; the `:registry-epoch` evidence axis. Bumped by the registration hook
  ;; installed at the bottom of this ns.
  (atom 0))

;; ---- reentrancy guard (dev) -------------------------------------------------

(def ^:dynamic ^:private *in-owner-fan-out?*
  "True while this port is synchronously invoking owner `on-change`
  callbacks (the owner-notification fan-out). `acquire!`/`release!` from
  inside it throw `:rf.error/reentrant-graph-op` (dev-asserted). Bound only
  under `interop/debug-enabled?`, so production builds carry neither the
  binding nor the check (the #5704 dev-only-machinery-must-DCE idiom).
  React-driven acquire/release — renders and commits *caused by* the
  epoch-close notify — run after the fan-out returns and never see it."
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

;; ---- node records (weak identity-keyed) -------------------------------------
;;
;; Per-node observation bookkeeping — `{:node-key <int> :version <int>
;; :value <last-observed>}` — keyed by the cache node's reaction OBJECT in a
;; WEAK identity-keyed table, so a record's lifetime is exactly its
;; reaction's: an evicted/disposed node's record becomes unreachable with the
;; node, and the port never prunes, scans, or retains. The cache entry map
;; itself stays exactly `{:reaction :inputs :ref-count}` (Spec 006 §Cache
;; shape advertises EXACTLY that key-set; a port slot inside it would be a
;; contract break).

#?(:clj
   (defonce ^:private ^java.util.Map node-records
     (java.util.Collections/synchronizedMap (java.util.WeakHashMap.)))
   :cljs
   (defonce ^:private node-records (js/WeakMap.)))

(defonce ^:private node-key-counter (atom 0))

(defn- advance-node-record!
  "Record that value `v` was OBSERVED on `reaction`: mint the node record on
  first observation (a fresh process-unique `:node-key`), advance `:version`
  when `v` differs from the last observed value by `rf=` (`=` on the CLJS
  reference), else leave the record untouched. Returns the (post) record.
  Constant work — one compare + one small map write on movement."
  [reaction v]
  #?(:clj
     (locking node-records
       (let [rec  (.get node-records reaction)
             rec' (cond
                    (nil? rec)
                    {:node-key (swap! node-key-counter inc) :version 0 :value v}

                    (not= (:value rec) v)
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

                  (not= (:value rec) v)
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
  the canonical thrown-error. Never returns."
  [error-id where frame-id query-v reason extra]
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
           extra))
  (error/throw-error! error-id where reason
                      {:extra (merge {:frame          frame-id
                                      :rf.sub/query-v query-v}
                                     extra)}))

(defn- throw-frame-destroyed!
  [where frame-id query-v]
  (emit-and-throw!
    :rf.error/frame-destroyed where frame-id query-v
    (str where " targeted frame " frame-id ", which is not registered or has "
         "been destroyed; the observation port is fail-loud — the ViewCell "
         "maps this to the view error boundary (the public subscribe surface "
         "keeps its recover-to-nil semantics).")
    nil))

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

;; ---- the lease ---------------------------------------------------------------

(deftype ObservationLease [state]
  ;; The lease IS the owner token — an opaque host object compared by
  ;; IDENTITY (deftype default; never `=`). Owners are keyed by lease
  ;; identity with per-lease callbacks, so the sibling-callback-clobber bug
  ;; class is structurally impossible and StrictMode release/reacquire is
  ;; naturally balanced. `state` is an atom:
  ;;
  ;;   node lease   {:lease-kind :node :target t :frame-id f :query-v q
  ;;                 :reaction r :on-change f :watch-key k|absent
  ;;                 :status :live|:released
  ;;                 :last {:value v :version n :node-key nk}}
  ;;   static lease {:lease-kind :static :target t :status :live}
  )

(defn- lease-state
  [lease]
  (.-state ^ObservationLease lease))

(defn lease?
  "True when `x` is an observation-port lease (either kind)."
  [x]
  (instance? ObservationLease x))

(defn owned?
  "True when `lease` owns a real sub-cache node (`acquire!` on a
  `:subscription` target). The static override lease reports `false`
  honestly — a pinned value owns nothing."
  [lease]
  (= :node (:lease-kind @(lease-state lease))))

;; ---- HMR / disposal notification queue ---------------------------------------

(defonce ^:private pending-disposals (atom []))
(defonce ^:private disposal-drain-scheduled? (atom false))

(defn- notify-disposal!
  [lease cause]
  (let [st @(lease-state lease)]
    (when (and (= :node (:lease-kind st))
               (= :live (:status st)))
      (let [{:keys [on-change target frame-id last]} st]
        (fan-out! on-change
                  {:cause          cause
                   :target         target
                   :node-key       (:node-key last)
                   :node-version   (:version last)
                   :frame-epoch    (frame/frame-commit-epoch frame-id)
                   :registry-epoch @registry-epoch*})))))

(defn ^:no-doc drain-pending-disposals!
  "Drain the queued node-disposed notifications, coalesced once per lease
  (identity), delivering only to still-live leases. `cause` is `:hmr` when
  drained at the sub re-registration boundary (the registrar replacement
  hook below), `:disposed` on the next-tick fallback (frame-destroy /
  explicit cache clears). INTERNAL — exposed un-private only so the port's
  own tests can drive the fallback boundary deterministically."
  [cause]
  (let [[pending _] (reset-vals! pending-disposals [])]
    (doseq [lease (distinct pending)]
      (notify-disposal! lease cause)))
  nil)

(defn- enqueue-disposal!
  [lease]
  (swap! pending-disposals conj lease)
  ;; Fallback drain boundary for non-registrar disposal paths. The HMR path
  ;; drains earlier and synchronously (the replacement hook below); this
  ;; tick then finds an empty queue and no-ops.
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
  Within one synchronous execution slice, cold probes threading the same
  handle share computed derivation parents (N sibling rows probing
  `[:orders/by-id id]` compute shared parents once per slice, not once per
  row). The table inside is created lazily on first cold probe,
  belt-and-braces tagged with `(frame, frame-epoch, registry-epoch)` and
  invalidated on any mismatch, and (CLJS) cleared by `queueMicrotask` so an
  abandoned slice's table is released. The memo is an ECONOMY, never an
  authority — the commit evidence comparison (invariant 5) already corrects
  any staleness before paint. Per Spec 006 §The slice-scoped probe memo."
  []
  (atom {:tag nil :memo nil}))

(defn- seed-observation-opts!
  [memo frame-id]
  (swap! memo assoc subs/observation-opts-key {:frame frame-id})
  memo)

(defn- slice-memo-table!
  "Resolve the compute memo atom for a cold probe against `frame-id`:
  validate the handle's `(frame, frame-epoch, registry-epoch)` tag, reuse
  the table on a match, else install a fresh one (arming the CLJS microtask
  clear). A nil handle means no cross-probe sharing — a fresh per-call
  table."
  [handle frame-id]
  (if (nil? handle)
    (seed-observation-opts! (atom {}) frame-id)
    (let [tag [frame-id
               (frame/frame-commit-epoch frame-id)
               @registry-epoch*]
          {existing-tag :tag existing :memo} @handle]
      (if (and existing (= existing-tag tag))
        existing
        (let [fresh (seed-observation-opts! (atom {}) frame-id)]
          (reset! handle {:tag tag :memo fresh})
          #?(:cljs
             (when (exists? js/queueMicrotask)
               (js/queueMicrotask
                 (fn []
                   ;; Release only OUR table — a later slice may have
                   ;; installed a fresh one already.
                   (when (identical? fresh (:memo @handle))
                     (reset! handle {:tag nil :memo nil}))))))
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
  never interprets them — it compares each by `=` only: `:override-id` is
  the slot-identity token (the kept-check's `= :override-id`), `:version`
  is the movement token (the kept-check's `= :version`, so a moved value
  retargets). The consumer's LOWERING (which app-level value becomes the id
  vs. the version) is recorded on the consumer side
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
                 (evidence frame-id v nil nil false))))))))))

;; ---- active-owner tracking (the released-lease-retention fix) ----------------
;;
;; rf2-vxgfnd.15: a released lease MUST NOT stay reachable from a live shared
;; reaction. Rather than one non-removable `interop/add-on-dispose!` closure
;; per lease — which accumulates one dormant closure per historical owner on
;; any node an owner keeps live (an unbounded leak, and O(all owners ever) at
;; the eventual disposal) — the port keeps an IDENTITY-keyed set of the node's
;; CURRENTLY-active owner leases inside the weak node record, and installs ONE
;; node-scoped disposal hook per reaction. `acquire!` enrols the lease and
;; installs the hook on the first owner; `release!` de-enrols it; node disposal
;; snapshots-and-clears the live owners and enqueues each once. The owner set
;; rides the SAME weak record (and the same JVM lock discipline) as the version
;; bookkeeping, so it dies with the reaction — no pruning, no scan of historical
;; owners, storage O(current owners) not O(all owners ever acquired). The lease
;; is a `deftype` compared by identity, so a plain persistent set keys it by
;; identity on both hosts.

(defn- register-owner!
  "Enrol owner `lease` in `reaction`'s active-owner set within the weak node
  record (minting the record if a prior probe/observe has not — in practice
  `acquire!` observes first, so the record exists). Returns true iff THIS call
  must install the node's single disposal hook — i.e. it was the first owner
  (`:hooked?` false → true). JVM: under the node-records lock, the same
  discipline the version records use."
  [reaction lease]
  #?(:clj
     (locking node-records
       (let [rec     (or (.get node-records reaction)
                         {:node-key (swap! node-key-counter inc) :version 0 :value nil})
             hooked? (:hooked? rec)]
         (.put node-records reaction
               (assoc rec :owners (conj (or (:owners rec) #{}) lease) :hooked? true))
         (not hooked?)))
     :cljs
     (let [rec     (or (.get node-records reaction)
                       {:node-key (swap! node-key-counter inc) :version 0 :value nil})
           hooked? (:hooked? rec)]
       (.set node-records reaction
             (assoc rec :owners (conj (or (:owners rec) #{}) lease) :hooked? true))
       (not hooked?))))

(defn- deregister-owner!
  "Remove owner `lease` from `reaction`'s active-owner set. No-op when the
  record or the lease is absent (a disposed/rebuilt node, or a lease already
  drained). JVM: under the node-records lock. Returns nil."
  [reaction lease]
  #?(:clj
     (locking node-records
       (when-let [rec (.get node-records reaction)]
         (when (contains? (:owners rec) lease)
           (.put node-records reaction (update rec :owners disj lease)))))
     :cljs
     (when-let [rec (.get node-records reaction)]
       (when (contains? (:owners rec) lease)
         (.set node-records reaction (update rec :owners disj lease)))))
  nil)

(defn- take-owners!
  "Snapshot-and-clear `reaction`'s active-owner set, returning the leases that
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
  "INTERNAL — the number of active owner leases the port currently tracks for
  `reaction` (0 when the node has no record or no live owners). Exposed
  un-private only so the port's own retention tests can assert active ownership
  returns to the current set after acquire/release churn (rf2-vxgfnd.15), on
  both hosts. Not part of the port ABI."
  [reaction]
  (count #?(:clj  (locking node-records (:owners (.get node-records reaction)))
            :cljs (:owners (.get node-records reaction)))))

(defn- node-disposed-hook
  "The ONE node-scoped on-dispose callback for `reaction` (installed exactly
  once, on the first owner). On node disposal it snapshots-and-clears the
  CURRENT active owners and enqueues a former-owner notification for each
  still-live lease — coalesced once per lease at the drain boundary (see the ns
  docstring's HMR section). It closes over the reaction only, never a lease, so
  released owners (de-enrolled by `release!`) are unreachable from it."
  [reaction]
  (fn observation-node-disposed []
    (doseq [lease (take-owners! reaction)]
      (when (= :live (:status @(lease-state lease)))
        (enqueue-disposal! lease)))))

;; ---- acquire! -------------------------------------------------------------------

(defn- make-watch-handler
  "Per-lease change watch: constant-work — advance the node record with the
  DELIVERED new value (no recompute, per I-5) and fan the mark-dirty payload
  to this lease's own `on-change`."
  [state]
  (fn observation-watch [_key _ref prev nu]
    (let [st @state]
      (when (and (= :live (:status st))
                 (not= prev nu))
        (let [rec (advance-node-record! (:reaction st) nu)
              last' {:value nu :version (:version rec) :node-key (:node-key rec)}]
          (swap! state assoc :last last')
          (fan-out! (:on-change st)
                    {:cause          :value
                     :target         (:target st)
                     :node-key       (:node-key rec)
                     :node-version   (:version rec)
                     :frame-epoch    (frame/frame-commit-epoch (:frame-id st))
                     :registry-epoch @registry-epoch*}))))))

(defn acquire!
  "Commit-only: acquire ownership of `target`, returning a LEASE — the owner
  token (identity equality, never `=`).

  For a `:subscription` target the canonical node is RE-RESOLVED by
  `(frame, query)` here — the captured target is an identity, never a node
  handle, so an HMR-disposed render-time node can never be pinned. The
  acquire is the cache's ref-count attach (Spec 006 §Lookup algorithm; a
  miss builds the node through the real cache-install path), plus per-lease
  callback registration: a unique change watch (on watchable hosts). The lease
  is also enrolled as an active owner behind the node's single, once-installed
  disposal hook; `release!` de-enrols it, so a released lease never leaks a
  dormant closure (rf2-vxgfnd.15). `acquire!` never invokes `on-change`
  synchronously — no fan-out during acquire (movement in the render→commit
  gap is the commit evidence comparison's job, invariant 5).

  `on-change` MUST be constant-work (mark-dirty with the payload
  `{:cause :value|:hmr|:disposed :target … :node-key … :node-version …
  :frame-epoch … :registry-epoch …}`; it never computes — I-5).

  For a `:story-override` target returns the STATIC lease: `:owned? false`
  reported honestly, `read` yields the pinned value/version, `release!`
  no-ops, and NO callback is registered (a pinned value never invalidates);
  `current?` fails when the site's override id/version moved, which
  retargets through the normal staged commit path.

  Fail-loud: `:rf.error/frame-destroyed`, `:rf.error/no-such-sub` (entry),
  `:rf.error/reentrant-graph-op` (dev — called from inside the fan-out)."
  [target on-change]
  (assert-not-in-fan-out! 're-frame.substrate.observation/acquire!)
  (case (:kind target)
    :story-override
    (->ObservationLease (atom {:lease-kind :static
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
      (let [reaction (subs/acquire-cache-reaction! frame-id query)]
        (when (nil? reaction)
          ;; The frame's cache vanished in the check→acquire window.
          (throw-frame-destroyed! 're-frame.substrate.observation/acquire!
                                  frame-id query))
        (let [state (atom {:lease-kind :node
                           :target     target
                           :frame-id   frame-id
                           :query-v    query
                           :reaction   reaction
                           :on-change  on-change
                           :status     :live})
              lease (->ObservationLease state)]
          ;; Watch BEFORE the baseline observe: a watched reaction is live
          ;; on the reactive hosts, so the observe below reads through the
          ;; activated node. `add-watch` never fires synchronously.
          (when (watchable? reaction)
            (let [wk (gensym "rf-obs-lease")]
              (add-watch reaction wk (make-watch-handler state))
              (swap! state assoc :watch-key wk)))
          (let [[rec v] (observe-node! reaction)]
            (swap! state assoc :last {:value    v
                                      :version  (:version rec)
                                      :node-key (:node-key rec)}))
          ;; Node-disposed notification. Enrol this lease as an active owner
          ;; and — only for the FIRST owner — install the node's single
          ;; disposal hook (delivery is queued; see the ns docstring's HMR
          ;; section). `release!` de-enrols the lease, so a released lease is no
          ;; longer reachable from the live reaction: disposal work stays
          ;; O(current owners), never O(all owners ever acquired) (rf2-vxgfnd.15).
          (when (register-owner! reaction lease)
            (interop/add-on-dispose! reaction (node-disposed-hook reaction)))
          lease)))))

;; ---- current? -------------------------------------------------------------------

(defn- node-still-canonical?
  "True when the lease's acquired reaction is still the frame's live cache
  node for its query — i.e. not disposed, not evicted, not rebuilt."
  [{:keys [frame-id query-v reaction]}]
  (boolean
    (when-let [cache (:sub-cache (frame/frame frame-id))]
      (identical? reaction (:reaction (get @cache query-v))))))

(defn current?
  "The commit kept-check, one predicate: `lease` still exactly covers
  `target` ≡ not released ∧ node not disposed ∧ same frame ∧ same stabilized
  query. An unchanged live lease is retained untouched by the caller; a
  disposed node (HMR), a destroyed/swapped frame, a restabilized query, or a
  moved/removed override fails the check and classifies the site as
  retargeted. Pure read; never throws.

  Static (override) leases compare the consumer's OPAQUE change token by
  `=` only — `:override-id` (slot identity) and `:version` (movement) — so
  an equal-value provider replacement retains and any value/schema move
  retargets, without this port interpreting either token."
  [lease target]
  (let [st @(lease-state lease)]
    (case (:lease-kind st)
      :static
      (let [lt (:target st)]
        (and (= :story-override (:kind target))
             (= (:override-id lt) (:override-id target))
             (= (:version lt) (:version target))
             (= (:query lt) (:query target))))

      :node
      (and (= :live (:status st))
           (= :subscription (:kind target))
           (= (:frame-id st) (:frame-id target))
           (= (:query-v st) (:query target))
           (node-still-canonical? st))

      false)))

;; ---- read -----------------------------------------------------------------------

(defn read
  "Commit-side read through a lease. On a live node lease, derefs the owned
  node fresh and returns `{:value v :version n :frame-epoch fe
  :registry-epoch re}` (the epoch keys are additive — they hand the commit
  reconciler its invariant-5 comparison inputs without a second probe). On
  the static override lease, returns the pinned `{:value v :version n}`.

  `read` on a RELEASED lease throws `:rf.error/read-after-release` — always,
  production included; it is a substrate bug, unreachable in correct
  generated code (the render path checks `current?` first). A live lease
  whose node was disposed out from under it (HMR mid-gap) returns its last
  committed observation rather than touching the disposed node — `current?`
  is the gate that routes such a site to retarget."
  [lease]
  (let [st @(lease-state lease)]
    (case (:lease-kind st)
      :static
      (let [t (:target st)]
        {:value (:value t) :version (:version t)})

      :node
      (do
        (when (= :released (:status st))
          (let [reason (str "the observation port read a lease after release!"
                            " for " (pr-str (:query-v st)) " in frame "
                            (:frame-id st) "; this is a substrate bug — the "
                            "generated commit path must current?-check before "
                            "reading and never read a released lease.")]
            (error-emit/emit-error-both!
              :rf.error/read-after-release
              (:query-v st) (first (:query-v st)) (:frame-id st)
              nil 0 (interop/now-ms)
              {:where    're-frame.substrate.observation/read
               :frame    (:frame-id st)
               :rf.sub/query-v (:query-v st)
               :reason   reason
               :recovery :no-recovery})
            (error/throw-error!
              :rf.error/read-after-release
              're-frame.substrate.observation/read
              reason
              {:extra {:frame          (:frame-id st)
                       :rf.sub/query-v (:query-v st)}})))
        (if (node-still-canonical? st)
          (let [[rec v] (observe-node! (:reaction st))]
            (swap! (lease-state lease) assoc
                   :last {:value v :version (:version rec)
                          :node-key (:node-key rec)})
            {:value          v
             :version        (:version rec)
             :frame-epoch    (frame/frame-commit-epoch (:frame-id st))
             :registry-epoch @registry-epoch*})
          (let [{:keys [value version]} (:last st)]
            {:value          value
             :version        version
             :frame-epoch    (frame/frame-commit-epoch (:frame-id st))
             :registry-epoch @registry-epoch*}))))))

;; ---- release! -------------------------------------------------------------------

(defn release!
  "Synchronously release `lease` — the subscriber detach of Spec 006
  §Reference counting and disposal: the lease's change watch is removed, the
  lease is de-enrolled from the node's active-owner set (so a released lease is
  no longer reachable from the live reaction — rf2-vxgfnd.15), and the cache
  ref-count decremented under an IDENTITY GUARD (only while the
  cache still holds the lease's own reaction), so the 1 → 0 edge disposes
  the node in-tick and a node disposed-or-rebuilt out from under the lease
  is a no-op (its reference died with the eviction). Idempotent — a second
  `release!` no-ops; the static override lease no-ops entirely. Never
  invokes `on-change` (no fan-out during release). Dev-asserted
  `:rf.error/reentrant-graph-op` from inside the fan-out. Returns nil."
  [lease]
  (assert-not-in-fan-out! 're-frame.substrate.observation/release!)
  (let [state (lease-state lease)
        [old _new] (swap-vals! state
                               (fn [st]
                                 (if (and (= :node (:lease-kind st))
                                          (= :live (:status st)))
                                   (assoc st :status :released)
                                   st)))]
    ;; We won the live→released transition iff `old` was a live node lease —
    ;; exactly-once teardown under the same swap-vals! discipline the cache
    ;; uses.
    (when (and (= :node (:lease-kind old))
               (= :live (:status old)))
      (let [{:keys [reaction watch-key frame-id query-v]} old]
        (when watch-key
          (remove-watch reaction watch-key))
        ;; De-enrol this lease from the node's active-owner set so a released
        ;; lease is no longer reachable from the live reaction — the node's
        ;; single disposal hook then never sees it (rf2-vxgfnd.15).
        (deregister-owner! reaction lease)
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
;; boundary the re-registration closes": queued, coalesced once per lease,
;; never delivered mid-registry-mutation.

(defonce ^:private _registrar-hooks
  (do
    (registrar/add-registration-hook!
      (fn observation-registry-epoch-hook [{:keys [kind]}]
        (when (= :sub kind)
          (swap! registry-epoch* inc))))
    (registrar/add-replacement-hook!
      (fn observation-hmr-drain-hook [{:keys [kind]}]
        (when (= :sub kind)
          (drain-pending-disposals! :hmr))))
    :installed))
