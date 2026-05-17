(ns re-frame.subs
  "Subscriptions: registration, the per-frame sub-cache, and lookup.

  Per Spec 002 §Subscriptions composing across the signal graph and
  Spec 006 §Subscription cache — contract and operational semantics.

  Layer-1 sub: reads app-db directly via (fn [db query]).
  Layer-2 sub: reads other subs via :<- chain; (fn [inputs query]).
  Layer-3+: same shape as Layer-2 with deeper chains.

  The cache is per-frame, keyed by query-vector. Each entry holds:
    {:value v :reaction r :inputs [...] :ref-count n :on-dispose [...]
     :pending-dispose <timer-handle-or-nil>}

  Invalidation runs as part of replace-container! — when app-db changes,
  the substrate adapter's reaction graph fires; layer-1 subs recompute
  if their reader's value changed by =, layer-2+ subs cascade
  topologically.

  Disposal is **deferred ref-counting with a grace-period** (rf2-s9dn,
  per Spec 006 §Reference counting and disposal). When the last subscriber
  drops, the cache entry is scheduled for disposal after the configured
  grace-period (default 50ms — see grace-period-ms / configure!). If a
  new subscriber arrives within that window, disposal is cancelled and
  the cached value is reused.

  This is the only disposal algorithm — there are no pluggable lifecycle
  policies."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.source-coords :as source-coords]
            [re-frame.subs.memo :as subs-memo]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]
            ;; JVM autoload (rf2-bmzq0): the tooling sibling has zero
            ;; artefact cost on JVM and we keep the legacy
            ;; `re-frame.subs/<name>` shape working for JVM test
            ;; fixtures via the alias block at the bottom of the
            ;; file. CLJS deliberately omits this require so the
            ;; tooling sibling stays out of production bundles. The
            ;; reciprocal — `subs.tooling` requires `subs` to drive
            ;; the alias chain — is avoided because subs.tooling
            ;; only needs `registrar` / `frame` / `interop` and a
            ;; cyclic require would break the JVM autoload.
            #?@(:clj [[re-frame.subs.tooling :as subs-tooling]])))

#?(:clj (set! *warn-on-reflection* true))

;; ---- registration ---------------------------------------------------------
;;
;; A sub registration carries:
;;   :handler-fn     the body fn — (fn [inputs query]) for layer-2+,
;;                                  (fn [db query]) for layer-1.
;;   :input-signals  for layer-2+, a vector of [query-id arg ...] forms
;;                   that resolve to other registered subs. Empty for
;;                   layer-1 (which reads app-db directly).

(defn- parse-reg-sub-args
  "Accept the :<- shorthand and the fn-tail forms.

  Forms supported:
    (reg-sub :id (fn [db query] ...))                         ;; layer-1
    (reg-sub :id :<- [:other-sub] (fn [other-val q] ...))     ;; layer-2 single
    (reg-sub :id :<- [:a] :<- [:b] (fn [[a b] q] ...))         ;; layer-2 multi
  "
  [id args]
  (let [meta? (and (map? (first args)) (not (vector? (first args))))
        meta  (if meta? (first args) {})
        rest-args (if meta? (next args) args)]
    (loop [chain []
           remaining rest-args]
      (cond
        (and (= :<- (first remaining))
             (vector? (second remaining)))
        (recur (conj chain (second remaining))
               (drop 2 remaining))

        (= 1 (count remaining))
        {:id            id
         :meta          meta
         :input-signals chain
         :handler-fn    (first remaining)}

        :else
        (throw (ex-info
                 "reg-sub: bad args — expected layer-1 (handler-fn), layer-2 single (:<- [:upstream] handler-fn), or layer-2 multi (:<- [:a] :<- [:b] handler-fn)"
                 {:id id :remaining remaining}))))))

(defn reg-sub
  "Register a subscription under `id`. The only sub-registration form
  in v2 — `reg-sub-raw` is dropped (per Spec 002 §Subscriptions
  composing).

  Three shapes:

      ;; Layer-1 — reads `app-db` directly.
      (reg-sub :id
        (fn [db query-v] ...derived-value...))

      ;; Layer-2, single input — chains off one upstream sub.
      (reg-sub :id
        :<- [:upstream-id]
        (fn [upstream-val query-v] ...derived-value...))

      ;; Layer-2, multi input — chains off N upstream subs.
      (reg-sub :id
        :<- [:a-sub]
        :<- [:b-sub]
        (fn [[a-val b-val] query-v] ...derived-value...))

  An optional metadata-map may precede the `:<-` chain / handler:
  `(reg-sub :id {:doc \"...\" :spec ...} ...)`. The `query-v` arg the
  handler receives is the full `[sub-id & args]` subscription vector
  the caller passed to `subscribe`.

  Returns `id`. Re-registering an existing `id` replaces the prior
  registration; cached entries for the affected sub are invalidated
  (hot-reload-safe).

  Example:

      (rf/reg-sub :user/name (fn [db _] (get-in db [:user :name])))

      (rf/reg-sub :user/initials
        :<- [:user/name]
        (fn [name _]
          (clojure.string/join (map first (clojure.string/split name #\"\\s+\")))))

  See also: `subscribe` (reactive form), `subscribe-once` (one-shot
  read), `compute-sub` (pure compute against a db value), `clear-sub`."
  [id & args]
  (let [{:keys [meta handler-fn input-signals]} (parse-reg-sub-args id args)]
    (registrar/register! :sub id
      (assoc (source-coords/merge-coords meta)
             :handler-fn    handler-fn
             :input-signals input-signals))
    ;; Per Spec 009 §:op-type vocabulary: :sub/create marks subscription
    ;; materialisation — emitted at registration time so tools see when
    ;; the sub becomes available in the registry.
    (trace/emit! :sub/create :sub/create
                 {:sub-id        id
                  :input-signals input-signals})
    id))

(defn clear-sub
  "Unregister a subscription. Zero-arity clears every registered sub
  in the registrar; one-arity clears the named one. Hot-reload tools
  and test fixtures call this between rebuilds; production code rarely
  needs it.

  Returns nil. See also: `reg-sub`, `clear-sub-cache!`
  (the runtime-cache counterpart)."
  ([] (registrar/clear-kind! :sub))
  ([id] (registrar/unregister! :sub id)))

;; ---- the cache ------------------------------------------------------------

(defn- cache-key
  "Identity now; reserved as the chokepoint if cache-key shape changes
  (per Spec 006 §Cache shape — currently the query-vector itself)."
  [query-v]
  query-v)

;; ---- grace-period configuration -------------------------------------------
;;
;; Per Spec 006 §Reference counting and disposal. When the last subscriber
;; detaches, we don't dispose immediately — we wait grace-period-ms in case
;; a new subscriber arrives (e.g. across a React re-render). The default
;; is short enough not to leak under genuine disposal but long enough to
;; bridge typical React render churn. Tests that want to assert on disposal
;; configure a short or zero value via configure!.

(def ^:private default-grace-period-ms
  ;; Long enough to bridge typical React render churn; short enough not
  ;; to leak under genuine disposal.
  50)

(defonce ^:private config
  ;; Map shape so future :sub-cache configure-keys land additively.
  (atom {:grace-period-ms default-grace-period-ms}))

(defn configure!
  "Update the sub-cache configuration. Currently supports
  `{:grace-period-ms N}` — a non-negative integer (or 0 to dispose
  synchronously when ref-count drops to zero). Per Spec 006."
  [opts]
  (when (map? opts)
    (swap! config merge (select-keys opts [:grace-period-ms])))
  nil)

(defn current-config
  "Return the current sub-cache configuration map. Public for tests
  and tools that want to display the current grace-period."
  []
  @config)

(defn- grace-period-ms
  []
  (or (:grace-period-ms @config) 0))

(declare subscribe unsubscribe)

;; The memo wrappers (`make-layer-1-memoised-body`,
;; `make-layer-n-1-memoised-body`, `make-layer-n-memoised-body`) and
;; the trace/perf/validate/recover bracket (`validate-and-trace`,
;; `maybe-validate-sub-return!`) live in `re-frame.subs.memo` — extracted
;; per rf2-0ytl4 Phase-2 seam S-B. Per-recompute hot path is the closure
;; body (in-process); only the per-miss constructor call from
;; `compute-and-cache!` below crosses the ns boundary.

(defn- compute-and-cache!
  "Build the reaction for query-v and cache it. Per Spec 006 §Lookup
  algorithm: recursively resolve :<- chain, build the reaction, attach
  on-dispose to evict the cache slot.

  The compute fn handed to the substrate adapter is built in two
  layers, each named:

    - `make-layer-1-memoised-body` / `make-layer-n-1-memoised-body` /
      `make-layer-n-memoised-body` — Spec 006 §No-op via value
      equality (rf2-719e). Wraps the user's body in a `=`-skipping
      memo. The layer-1 form is fixed-arity-1 and compares the db
      scalar directly (avoids per-recompute varargs-seq allocation).
      Layer-2 with a single `:<-` input gets the same fixed-arity-1
      treatment (rf2-0y2bp — the dominant layer-2 shape per
      rf2-v1nu0). Layer-2+ with ≥2 inputs keeps the vec-of-inputs
      varargs shape.
    - `validate-and-trace`  — Spec 009 :sub/run trace emit, perf bracket,
      Spec 010 step 6 validation, error contract
      (`:replaced-with-default` on throw).

  Per Spec 006 §What happens when a sub references an unknown sub: when
  the registrar lookup misses, emit `:rf.error/no-such-sub` and build a
  nil-yielding reaction, but DO NOT store it in the cache. The miss is
  transient — a later registration (boot order, lazy load) must let the
  next subscribe build a fresh reaction against the real body. We
  achieve this by branching here on nil meta."
  [frame-id query-v]
  (let [query-id      (first query-v)
        sub-meta      (registrar/lookup :sub query-id)
        _             (when (nil? sub-meta)
                        (trace/emit-error! :rf.error/no-such-sub
                                           {:query-v query-v :frame frame-id}))
        body-fn       (:handler-fn sub-meta)
        input-signals (:input-signals sub-meta)
        layer-1?      (empty? input-signals)
        ;; Resolve inputs: layer-1 → frame's app-db; layer-2+ → recursive subs.
        inputs        (if layer-1?
                        [(frame/get-frame-db frame-id)]
                        (mapv (fn [input-q] (subscribe frame-id input-q)) input-signals))
        memoised-body (cond
                        layer-1?
                        (subs-memo/make-layer-1-memoised-body
                          body-fn query-id query-v frame-id sub-meta)
                        ;; Layer-2 with a single `:<-` input — dominant
                        ;; shape per rf2-v1nu0; specialise to fixed-arity-1
                        ;; for parity with layer-1 (rf2-0y2bp).
                        (= 1 (count input-signals))
                        (subs-memo/make-layer-n-1-memoised-body
                          body-fn query-id query-v frame-id input-signals sub-meta)
                        :else
                        (subs-memo/make-layer-n-memoised-body
                          body-fn query-id query-v frame-id input-signals sub-meta))
        reaction      (adapter/make-derived-value inputs memoised-body)
        cache         (:sub-cache (frame/frame frame-id))
        k             (cache-key query-v)]
    ;; Skip caching the no-such-sub miss — see the rf2-l9u5 note in the
    ;; docstring. The reaction is built so callers that hold a reference
    ;; deref to nil (per Spec 009 §Error contract recovery
    ;; :replaced-with-default), but the cache slot stays empty so a later
    ;; registration is observed by the next subscribe.
    (when (and cache sub-meta)
      (swap! cache assoc k {:reaction        reaction
                            :inputs          input-signals
                            :ref-count       1
                            :on-dispose      []
                            :pending-dispose nil})
      (interop/add-on-dispose! reaction
        (fn []
          ;; A layer-2+ sub's construction called `subscribe` once per
          ;; `:<-` input, each incrementing the input's `:ref-count`.
          ;; The disposal must release those refs symmetrically —
          ;; without this, input ref-counts leak after Reagent auto-
          ;; disposes the parent. Decrement inputs BEFORE clearing the
          ;; parent slot so the cache invariant ("ref-count reflects
          ;; live refs") holds at every observable moment.
          (doseq [input-q input-signals]
            (try (unsubscribe frame-id input-q)
                 (catch #?(:clj Throwable :cljs :default) _ nil)))
          (swap! cache (fn [m]
                         (if (identical? reaction (get-in m [k :reaction]))
                           (dissoc m k)
                           m))))))
    reaction))

(defn subscribe
  "Per Spec 006 §Lookup algorithm. Returns the reaction for query-v;
  build-and-cache on miss; reuse on hit. The 1-arity form resolves
  the active frame via the `:adapter/current-frame` late-bind hook
  (rf2-d4sf) so subscribe inside a with-frame or under a
  frame-provider auto-routes through the 3-tier chain (dynamic var
  → React context → :rf/default).

  Per Spec 006 §Plain-fn-under-non-default-frame warning (rf2-d3k3):
  the 1-arity form runs the plain-fn detection check — if the
  surrounding React-context Provider names a non-default frame and the
  rendering component is NOT reg-view-wrapped (so its subscribe call
  has fallen through to :rf/default), `:rf.warning/plain-fn-under-
  non-default-frame-once` fires once per (component-id, frame-id)
  pair. The check is late-bound through re-frame.views (CLJS-only) so
  the JVM build never loads it; production (`:advanced` +
  `goog.DEBUG=false`) elides via `interop/debug-enabled?`.

  The 2-arity `(subscribe frame-id query-v)` form **deliberately
  skips** the plain-fn detection check (rf2-r0zf2). Supplying an
  explicit `frame-id` IS the opt-out — the caller has told the runtime
  exactly which frame to target, so a fall-through-to-`:rf/default`
  diagnostic doesn't apply. Use the 2-arity form from a plain
  Reagent fn body when you want to subscribe against a known frame
  without triggering the warning surface.

  This is the runtime-callable fn form. The macro form
  `re-frame.core/subscribe` captures `(meta &form)` and delegates here
  through `re-frame.core/subscribe*`, wrapping the call in
  `trace/with-call-site` so any error emitted inside the synchronous
  miss path (`:rf.error/no-such-sub`, `:rf.error/frame-destroyed`)
  carries the invocation coord."
  ([query-v]
   #?(:cljs
      (let [frame-id (frame/resolve-current-frame)]
        (when interop/debug-enabled?
          (when-let [warn! (late-bind/get-fn
                            :views/maybe-warn-plain-fn-under-non-default-frame!)]
            (warn! frame-id query-v)))
        (subscribe frame-id query-v))
      :clj
      (subscribe (frame/resolve-current-frame) query-v)))
  ([frame-id query-v]
   (let [frame-record (frame/frame frame-id)]
     (cond
       ;; Missing or destroyed frame: trace and return nil rather than
       ;; deref-ing nil and exploding. Per Spec 009 §Error contract:
       ;; recovery is :replaced-with-default — the sub resolves to nil.
       (nil? frame-record)
       (do (trace/emit-error! :rf.error/frame-destroyed
                              {:frame    frame-id
                               :query-v  query-v
                               :recovery :replaced-with-default})
           nil)

       :else
       (let [cache (:sub-cache frame-record)
             k     (cache-key query-v)]
         (if-let [entry (get @cache k)]
           ;; Hit. If a deferred-dispose was pending (ref-count had dropped
           ;; to zero), cancel it: a new subscriber arrived inside the
           ;; grace-period window, so the cached value is reused. Per
           ;; Spec 006 §Reference counting and disposal.
           (let [pending (:pending-dispose entry)]
             (when pending
               (try (interop/clear-timeout! pending)
                    (catch #?(:clj Throwable :cljs :default) _ nil)))
             (swap! cache update k
                    (fn [e]
                      (-> e
                          (update :ref-count (fnil inc 0))
                          (assoc :pending-dispose nil))))
             (:reaction entry))
           (compute-and-cache! frame-id query-v)))))))

(defn subscribe-once
  "One-shot read of a sub's current value. Subscribes, derefs, then
  unsubscribes — does NOT retain a reference on the cache entry and
  does NOT register the caller for reactive re-render.

  Use in tests, REPL sessions, machine-action bodies, SSR builders,
  or any non-reactive consumer that wants the value right now. For
  reactive consumers (Reagent views, tools holding the reaction) use
  `subscribe`. For event handlers prefer `(inject-cofx :sub-as-cofx)`
  so the read is part of the cofx contract rather than a side-effect
  inside the handler body.

  The teardown unsubscribe runs with `{:grace 0}` so the one-shot
  read's whole lifetime — subscribe, deref, dispose — completes in
  the calling tick (without it the fresh-sub would schedule a grace-
  period timer leaking dispose side-effects past the call's
  observable lifetime).

  See also: `subscribe`, `unsubscribe`, `compute-sub`, `inject-cofx`."
  ([query-v] (subscribe-once (frame/resolve-current-frame) query-v))
  ([frame-id query-v]
   (let [reaction (subscribe frame-id query-v)
         v        (when reaction @reaction)]
     ;; `{:grace 0}` overrides the configured grace-period for this
     ;; call only — concurrent subscribers keep the slot alive via
     ;; ref-count and are unaffected.
     (unsubscribe frame-id query-v {:grace 0})
     v)))

(defn compute-sub
  "Compute a subscription's value against a supplied db, bypassing the
  reactive cache. Useful in tests that want to inspect what a sub
  WOULD compute given a snapshot of state without going through the
  per-frame cache. Supports the same :<- chain shape as subscribe.

  Per Spec 008 §Testing — pure compute-sub form. Per Spec 010 §step 6
  (rf2-wcam): the return value is validated against any :spec on the
  sub's meta — failures emit :rf.error/schema-validation-failure and
  yield nil (default :replaced-with-default recovery).

  ## Cost — N^2 on deep `:<-` chains (rf2-r0zf2)

  Each `:<-` input is resolved by RE-CALLING `compute-sub` against the
  shared `db` — there is no memoisation across the recurse. A diamond
  dependency (`:c` depends on `:a` and `:b`; both depend on `:root`)
  computes `:root` twice; a longer reused-leaf chain compounds the
  cost. The reactive `subscribe` path is immune (the per-frame
  sub-cache deduplicates layer-2+ inputs across the dependency graph),
  but `compute-sub` consciously bypasses that cache to stay pure.

  Consumers that walk deep chains in tests / SSR / Story expecting
  'fast pure compute' should pin the sub-result by hand and pass it
  in instead of re-resolving the same input multiple times. The
  shallow case (a sub with no `:<-` inputs, or a chain of distinct
  intermediates) carries no quadratic risk."
  [query-v db]
  (let [query-id (first query-v)
        meta     (registrar/lookup :sub query-id)]
    (when meta
      ;; Per Spec 009 §:op-type vocabulary: :sub/run marks a sub recompute.
      ;; The pure compute-sub form fires the same op-type as the reactive
      ;; recompute path so tools can observe both call sites uniformly.
      (trace/emit! :sub/run :sub/run
                   {:sub-id  query-id
                    :query-v query-v})
      (let [body-fn (:handler-fn meta)
            inputs  (:input-signals meta)
            ;; Bind n once — `(empty? inputs)` then `(= 1 (count inputs))`
            ;; counted twice on the multi-input path (rf2-r1rma).
            n       (count inputs)]
        (try
          (let [v (cond
                    (zero? n)
                    (body-fn db query-v)

                    (= 1 n)
                    (body-fn (compute-sub (first inputs) db) query-v)

                    :else
                    (body-fn (mapv #(compute-sub % db) inputs) query-v))]
            (subs-memo/maybe-validate-sub-return! v query-v query-id meta))
          (catch #?(:clj Throwable :cljs :default) _
            nil))))))

(defn- dispose-entry-now!
  "Synchronous disposal: remove the cache slot for k iff its ref-count
  is still <= 0 (no resubscribe arrived) and dispose the reaction.
  Idempotent — a second call is a no-op because the slot is gone.

  The swap-fn body is pure — it returns the new cache map and nothing
  else; the reaction to dispose is read from the PRE-swap snapshot
  returned by `swap-vals!` and acted on AFTER the CAS commits. `swap!`
  is allowed to retry on contention on the JVM, so any side-effect
  (`interop/dispose!`) inside the swap-fn could fire 2+ times under
  concurrent invalidate + grace-fire."
  [cache k]
  (let [[old new] (swap-vals! cache
                              (fn [m]
                                (if-let [entry (get m k)]
                                  (if (<= (or (:ref-count entry) 0) 0)
                                    (dissoc m k)
                                    ;; Resubscribe arrived between schedule
                                    ;; and fire — keep entry.
                                    m)
                                  m)))]
    ;; The slot was evicted by THIS call iff it was present in `old` and
    ;; absent in `new`. A concurrent evictor (e.g. invalidate-sub-on-
    ;; replace! or clear-sub-cache!) that won the CAS race would
    ;; have left the slot absent in `old` too, so we don't double-dispose.
    (when (and (contains? old k) (not (contains? new k)))
      (when-let [r (get-in old [k :reaction])]
        (try (interop/dispose! r)
             (catch #?(:clj Throwable :cljs :default) _ nil))))
    nil))

(defn unsubscribe
  "Decrement the ref-count on the cached subscription for query-v.
  When ref-count reaches 0, schedule the entry for disposal after the
  configured grace-period (default 50ms; see configure!). If a new
  subscriber arrives within the window, disposal is cancelled and the
  cached value is reused. Per Spec 006 §Reference counting and disposal.

  When grace-period is 0, disposal is synchronous — useful for tests.

  The 3-arity form accepts an opts map:

      {:grace N}   — override the configured grace-period for THIS
                     call only. `{:grace 0}` forces synchronous
                     disposal on the 1→0 transition; useful for
                     callers that want their unsubscribe observable
                     in the same tick (e.g. `subscribe-once`'s
                     internal teardown). When `:grace` is absent,
                     the configured per-runtime grace-period is used.

  Reagent views auto-dispose via the reaction lifecycle and don't
  need to call this explicitly. Tests, REPL sessions, and tools that
  subscribe imperatively should call unsubscribe when they're done
  to release the cache slot."
  ([query-v]
   (unsubscribe (frame/resolve-current-frame) query-v nil))
  ([frame-id query-v]
   (unsubscribe frame-id query-v nil))
  ([frame-id query-v opts]
   (when-let [cache (:sub-cache (frame/frame frame-id))]
     (let [k     (cache-key query-v)
           ;; An explicit `:grace` in opts overrides the per-runtime
           ;; configured grace-period. `contains?` (not `(:grace opts)`)
           ;; so `{:grace 0}` is honoured.
           grace (if (and (map? opts) (contains? opts :grace))
                   (:grace opts)
                   (grace-period-ms))
           ;; The swap-fn body is pure — it returns only the new cache
           ;; map. The drop-to-zero signal is read from the diff between
           ;; `old` and `new` AFTER the CAS commits. `swap!` is allowed
           ;; to retry on JVM contention, so a side-effecting
           ;; `(reset! dropped-to-zero? true)` inside the swap-fn body
           ;; could fire on a discarded retry whose CAS lost — leading
           ;; to a spurious dispose schedule.
           [old new] (swap-vals! cache
                                 (fn [m]
                                   (if-let [entry (get m k)]
                                     (let [old-n (or (:ref-count entry) 1)
                                           n     (max 0 (dec old-n))]
                                       ;; Only trigger drop-to-zero on the 1→0
                                       ;; transition AND only when no grace-period
                                       ;; timer is already in flight. Calling
                                       ;; `unsubscribe` past zero (idempotent
                                       ;; misuse — e.g. cleanup in both a
                                       ;; teardown hook and a `finally`) must not
                                       ;; stack new `pending-dispose` timers on
                                       ;; top of the prior handle.
                                       (if (and (= 1 old-n)
                                                (zero? n)
                                                (nil? (:pending-dispose entry)))
                                         (assoc m k (assoc entry :ref-count 0))
                                         (assoc-in m [k :ref-count] n)))
                                     m)))
           ;; This swap drove the 1→0 transition (under no pending-dispose)
           ;; iff the entry was present in both old and new AND old's
           ;; ref-count was 1 AND new's ref-count is 0 AND old had no
           ;; pending-dispose timer. Reading from the snapshots avoids
           ;; the side-effect-in-swap-fn race.
           dropped-to-zero? (and (contains? new k)
                                 (= 1 (or (get-in old [k :ref-count]) 1))
                                 (zero? (or (get-in new [k :ref-count]) 0))
                                 (nil? (get-in old [k :pending-dispose])))]
       (when dropped-to-zero?
         (if (zero? grace)
           ;; Grace = 0: dispose synchronously (the test/explicit-tear-down path).
           (dispose-entry-now! cache k)
           ;; Grace > 0: schedule deferred disposal. Stash the timer handle
           ;; so a re-subscribe inside the window can cancel it.
           (let [handle (interop/set-timeout!
                          (fn []
                            (dispose-entry-now! cache k))
                          grace)
                 ;; Pure swap-fn: return the new map and a flag indicating
                 ;; whether the handle was actually stashed. The clear-
                 ;; timeout! side-effect runs AFTER the CAS commits so
                 ;; a discarded retry can't double-clear a live timer.
                 [_ new2] (swap-vals! cache
                                      (fn [m]
                                        (if-let [entry (get m k)]
                                          (if (<= (or (:ref-count entry) 0) 0)
                                            (assoc m k (assoc entry :pending-dispose handle))
                                            ;; Subscriber arrived between our swap!
                                            ;; above and set-timeout! returning —
                                            ;; do NOT stash; we'll cancel post-swap.
                                            m)
                                          m)))]
             ;; If the handle did NOT land on the entry, cancel it once,
             ;; outside the swap. Reading from the post-swap snapshot
             ;; (`new2`) means we make this decision exactly once.
             (when-not (identical? handle (get-in new2 [k :pending-dispose]))
               (try (interop/clear-timeout! handle)
                    (catch #?(:clj Throwable :cljs :default) _ nil))))))
       nil))))

;; ---- hot-reload invalidation ---------------------------------------------
;;
;; Per Spec 001 §Hot-reload semantics: when a :sub re-registers, every
;; cached reaction whose query-id is that sub MUST be disposed and
;; evicted across every frame's cache. Cached reactions hold the OLD
;; body via closure; without explicit invalidation, they'd silently
;; serve stale values.

(defn- invalidate-sub-on-replace!
  [{:keys [kind id]}]
  (when (= kind :sub)
    (doseq [frame-id (frame/frame-ids)]
      (when-let [cache (:sub-cache (frame/frame frame-id))]
        ;; The swap-fn body is pure — it returns only the new cache map.
        ;; Reactions to dispose and timers to cancel are read from the
        ;; diff between `old` and `new` AFTER the CAS commits (so a
        ;; retried `swap!` can't fire dispose 2+ times).
        (let [[old new] (swap-vals! cache
                                    (fn [m]
                                      (let [hit-keys (->> (keys m)
                                                          (filter #(= id (first %))))]
                                        (apply dissoc m hit-keys))))
              ;; The keys actually evicted by THIS swap are those present
              ;; in `old` but absent in `new`. A concurrent evictor that
              ;; won the CAS race would have removed its keys before our
              ;; swap saw them, so the diff names ONLY the keys we own.
              evicted-keys (filterv #(not (contains? new %))
                                    (keys old))]
          ;; Cancel any pending grace-period timers for the evicted slots —
          ;; the reaction is being disposed now, so the deferred path
          ;; would fire against a stale closure.
          (doseq [k evicted-keys]
            (when-let [h (get-in old [k :pending-dispose])]
              (try (interop/clear-timeout! h)
                   (catch #?(:clj Throwable :cljs :default) _ nil))))
          (doseq [k evicted-keys]
            (when-let [r (get-in old [k :reaction])]
              (try (interop/dispose! r)
                   (catch #?(:clj Throwable :cljs :default) _ nil)))))))))

(defonce ^:private _hot-reload-hook
  (do (registrar/add-replacement-hook! invalidate-sub-on-replace!)
      :installed))

(defn clear-sub-cache!
  "Dispose every cached entry in a frame's runtime sub-cache and clear
  the cache. Cancels any pending grace-period timers before disposing —
  a deferred disposal landing after this fn returned would close over
  a stale reaction.

  Test fixtures and REPL-driven reloads call this between scenarios
  to ensure the cache is empty before re-subscribing. Test code
  generally prefers `reset-runtime-fixture` (per `test_support`) which
  bundles cache-clearing with registrar / frame state reset.

  Zero-arity targets `:rf/default`; one-arity targets the named frame.
  Returns nil. See also: `clear-sub` (registrar-side counterpart)."
  ([] (clear-sub-cache! :rf/default))
  ([frame-id]
   (when-let [cache (:sub-cache (frame/frame frame-id))]
     (doseq [[_k entry] @cache]
       (when-let [h (:pending-dispose entry)]
         (try (interop/clear-timeout! h)
              (catch #?(:clj Throwable :cljs :default) _ nil)))
       (when-let [r (:reaction entry)]
         (try (interop/dispose! r)
              (catch #?(:clj Throwable :cljs :default) _ nil))))
     (reset! cache {}))))

;; ---- tooling sibling --------------------------------------------------
;;
;; Per rf2-bmzq0: the static-topology query (`sub-topology`) and the
;; reactive-cache snapshot (`sub-cache-snapshot`) moved to
;; `re-frame.subs.tooling` so production counter bundles DCE their
;; bodies. CLJS consumers needing the introspection surface load the
;; tooling sibling explicitly; JVM consumers reach the legacy
;; `re-frame.subs/<name>` shape via the convenience aliases in the
;; JVM-only block at the bottom of this file.

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.routing needs to call subscribe-once but cannot `:require`
;; this namespace without a cyclic load order. Publish entry point
;; through the late-bind hook registry. See re-frame.late-bind.

(late-bind/set-fn! :subs/subscribe-once subscribe-once)

;; ---- JVM-side convenience aliases (rf2-bmzq0) ----------------------------
;;
;; On the JVM we preserve the legacy `re-frame.subs/<name>` shape for
;; the tooling surface so the cascade of `.clj` test fixtures stays
;; unchanged. The aliases are gated under `#?(:clj ...)` so they never
;; appear in CLJS compilation — production counter bundles still DCE
;; the tooling sibling wholesale because `re-frame.subs` on CLJS has
;; no static reference to it. Mirror of the trace/tooling pattern
;; per rf2-qwm0a.

#?(:clj
   (do
     (def sub-topology       subs-tooling/sub-topology)
     (def sub-cache-snapshot subs-tooling/sub-cache-snapshot)))
