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
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace
             #?@(:cljs [:include-macros true])]))

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

(defn- maybe-validate-sub-return!
  "Per Spec 010 §Validation order step 6 (rf2-wcam) — after a sub
  recomputes, validate its return value against any :spec on the sub
  meta. On failure, emit :rf.error/schema-validation-failure and
  return nil per :replaced-with-default recovery; otherwise return
  the value unchanged.

  Looked up lazily through the late-bind registry so this namespace
  stays free of a hard re-frame.schemas dep (avoids load-order
  surprises)."
  [value query-v sub-id sub-meta]
  (if (and sub-meta (:spec sub-meta))
    (if-let [validate (late-bind/get-fn :schemas/validate-sub-return!)]
      (if (try (validate sub-id query-v value sub-meta)
               (catch #?(:clj Throwable :cljs :default) _ true))
        value
        nil)
      value)
    value))

(defn- validate-and-trace
  "Run the user's sub body fn once and project the result through the
  trace + performance + validate + error-recovery layer. Called by the
  memo wrapper (`make-layer-1-memoised-body` for layer-1 subs,
  `make-layer-n-memoised-body` for layer-2+) on a true recompute —
  the memo path skips this entire function when input is `=` to
  last-seen.

  Concerns folded in here, in order:

  1. Spec 009 §:op-type vocabulary — emit :sub/run for the recompute.
     The memo-hit path does NOT emit (per Spec 006 §No-op via value
     equality).
  2. Spec 009 §Performance instrumentation (rf2-du3i) — bracket the
     body call in performance marks so prod builds with the perf flag
     enabled produce a `rf:sub:<sub-id>` measure entry. Default-off;
     under `:advanced` + `re-frame.performance/enabled?=false` the
     bracket DCEs.
  3. Spec 010 §Validation order step 6 (rf2-wcam) — validate the body's
     return value against the sub's `:spec` meta. Failures emit
     :rf.error/schema-validation-failure and yield nil (recovery
     :replaced-with-default).
  4. Spec 009 §Error contract — `try/catch` around (1)+(2)+(3). On
     exception emit :rf.error/sub-exception and yield nil (recovery
     :replaced-with-default)."
  [body-fn in-vals query-id query-v frame-id input-signals sub-meta]
  ;; Publish the sub's HandlerScope for the duration of `:sub/run` emit
  ;; + body-fn invocation + validation. Per Spec 009 §:rf.trace/
  ;; trigger-handler the sub's source-coord rides every emit (`:sub/run`
  ;; success, `:rf.error/sub-exception` / schema-validation / transitive
  ;; sub-miss errors). The emit MUST sit inside the scope.
  (trace/with-handler-scope
    (trace/handler-scope-from-meta :sub query-id sub-meta)
    (trace/emit! :sub/run :sub/run
                 {:sub-id  query-id
                  :query-v query-v
                  :frame   frame-id})
    (try
      (let [computed (performance/mark-and-measure :sub query-id
                      (if (empty? input-signals)
                        (body-fn (first in-vals) query-v)
                        ;; Layer-2+: deliver inputs as a coll if many,
                        ;; or singleton when only one chain entry.
                        (if (= 1 (count input-signals))
                          (body-fn (first in-vals) query-v)
                          (body-fn (vec in-vals) query-v))))]
        (maybe-validate-sub-return! computed query-v query-id sub-meta))
      (catch #?(:clj Throwable :cljs :default) e
        (let [msg #?(:clj (.getMessage e) :cljs (.-message e))]
          (trace/emit-error!
            :rf.error/sub-exception
            {:failing-id        query-id
             :sub-id            query-id
             :sub-query         query-v
             :exception         e
             :exception-message msg
             :reason            (str "Subscription `" query-id
                                     "` threw while computing: "
                                     msg ". Returning nil.")
             :recovery          :replaced-with-default}))
        nil))))

;; ---- memoisation wrappers ------------------------------------------------
;;
;; Per Spec 006 §No-op via value equality (rf2-719e). Reagent's auto-run
;; reaction unconditionally invokes the compute fn on any source-watch
;; fire, then dedups *downstream notification* by `=`. That's one level
;; too late for the spec — the body fn itself must NOT re-run when the
;; resolved input value is `=` to the last-seen. The memo wrappers
;; compare the inputs against the previous invocation and short-circuit
;; to the memoised return value when equal. Reagent's dependency
;; tracking still observes every `deref` because the wrapper *is* the
;; compute fn — only the user's body (and the trace+validate+perf+
;; recovery layer that brackets it) is suppressed.
;;
;; The layer-1 path is specialised to a fixed-arity-1 wrapper that
;; compares the db value directly. This skips the varargs-seq allocation
;; a `(fn [& in-vals])` form would force on every recompute, and
;; replaces the seq-vs-seq `=` walk with a direct value compare. Every
;; layer-1 sub × every dispatch that touches it pays this — the hottest
;; allocation in the artefact.

(defn- make-layer-1-memoised-body
  "Specialised memo wrapper for layer-1 subs (which read app-db
  directly). Fixed-arity-1 — avoids the varargs-seq allocation that a
  `(fn [& in-vals])` form would force on every reaction recompute, and
  compares the db value to the last-seen scalar (no seq-vs-seq walk).

  Returns a `(fn [db])`. When `body-fn` is nil (the unknown-sub path
  — see `compute-and-cache!`) the wrapper yields nil on every call
  without touching the memo cells.

  The `::unset` sentinel guarantees the first invocation always
  recomputes (the sentinel is never `=` to any db value)."
  [body-fn query-id query-v frame-id sub-meta]
  (let [last-db     (volatile! ::unset)
        last-result (volatile! nil)]
    (fn [db]
      (when body-fn
        (if (= @last-db db)
          @last-result
          (let [computed (validate-and-trace
                           body-fn (list db) query-id query-v
                           frame-id [] sub-meta)]
            (vreset! last-db db)
            (vreset! last-result computed)
            computed))))))

(defn- make-layer-n-memoised-body
  "Memo wrapper for layer-2+ subs (which chain off one or more upstream
  subs). Varargs — the input arity matches the count of `:<-` entries
  on the registration, and the wrapper compares the seq of input
  values against the last-seen seq.

  Returns a `(fn [& in-vals])`. When `body-fn` is nil the wrapper
  yields nil on every call without touching the memo cells.

  See `make-layer-1-memoised-body` for the layer-1 specialisation."
  [body-fn query-id query-v frame-id input-signals sub-meta]
  (let [last-in-vals (volatile! ::unset)
        last-result  (volatile! nil)]
    (fn [& in-vals]
      (when body-fn
        (if (= @last-in-vals in-vals)
          @last-result
          (let [computed (validate-and-trace
                           body-fn in-vals query-id query-v
                           frame-id input-signals sub-meta)]
            (vreset! last-in-vals in-vals)
            (vreset! last-result computed)
            computed))))))

(defn- compute-and-cache!
  "Build the reaction for query-v and cache it. Per Spec 006 §Lookup
  algorithm: recursively resolve :<- chain, build the reaction, attach
  on-dispose to evict the cache slot.

  The compute fn handed to the substrate adapter is built in two
  layers, each named:

    - `make-layer-1-memoised-body` / `make-layer-n-memoised-body` —
      Spec 006 §No-op via value equality (rf2-719e). Wraps the user's
      body in a `=`-skipping memo. The layer-1 form is fixed-arity-1
      and compares the db scalar directly (avoids per-recompute
      varargs-seq allocation); layer-2+ keeps the vec-of-inputs shape.
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
        memoised-body (if layer-1?
                        (make-layer-1-memoised-body
                          body-fn query-id query-v frame-id sub-meta)
                        (make-layer-n-memoised-body
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
  yield nil (default :replaced-with-default recovery)."
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
            inputs  (:input-signals meta)]
        (try
          (let [v (cond
                    (empty? inputs)
                    (body-fn db query-v)

                    (= 1 (count inputs))
                    (body-fn (compute-sub (first inputs) db) query-v)

                    :else
                    (body-fn (mapv #(compute-sub % db) inputs) query-v))]
            (maybe-validate-sub-return! v query-v query-id meta))
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

;; ---- static topology ------------------------------------------------------
;;
;; Per Spec 002 §The public registrar query API and Spec 006 §Subscription
;; topology vs subscription tracking. `sub-topology` is the static ":<- chain"
;; you can derive from registrations alone — pure data over the registrar,
;; no app-db, no reactive runtime, no per-frame cache. JVM-runnable.
;;
;; Shape (per Spec 002 §The public registrar query API row): a map of
;;   sub-id → {:inputs [<input-sub-ids>] :doc <str?> :ns sym :line int :file str}
;; with :inputs always present (empty vector for layer-1 / direct-app-db subs)
;; and the source-coord / :doc keys included only when the registration
;; carries them.

(defn sub-topology
  "Return the static dependency graph of every registered subscription.

  Pure data over the registrar — no app-db, no per-frame cache, no
  reactive runtime. Per Spec 002 §The public registrar query API and
  Spec 006 §Subscription topology vs subscription tracking.

  Shape: `{sub-id {:inputs [<input-sub-ids>] :doc ... :ns ... :line ... :file ...}}`.

  - `:inputs` is the vector of upstream sub-ids declared via `:<-` at
    registration time. It is always present; layer-1 subs (which read
    `app-db` directly) report `:inputs []`. The order matches the
    declaration order so that downstream tools can reconstruct the
    chain shape the body fn expects.
  - `:doc`, `:ns`, `:line`, `:file` are present when the registration
    carried them (`:ns` / `:line` / `:file` are auto-captured by the
    `reg-sub` macro per Spec 001 §Source-coordinate capture; `:doc`
    is user-supplied via the meta-map first arg).

  Returns `{}` when no subs are registered.

  JVM-runnable. The runtime cache state (`sub-cache`) is the dynamic
  counterpart and is CLJS-only."
  []
  (let [subs-meta (registrar/registrations :sub)]
    (reduce-kv
      (fn [acc sub-id meta]
        (let [inputs (mapv first (:input-signals meta))
              entry  (cond-> {:inputs inputs}
                       (contains? meta :doc)  (assoc :doc  (:doc  meta))
                       (contains? meta :ns)   (assoc :ns   (:ns   meta))
                       (contains? meta :line) (assoc :line (:line meta))
                       (contains? meta :file) (assoc :file (:file meta)))]
          (assoc acc sub-id entry)))
      {}
      subs-meta)))

(defn sub-cache-snapshot
  "Public read-only snapshot of a frame's sub-cache, projected to a
  Tool-Pair-friendly shape: `{query-v {:value v :ref-count n}}`.

  CLJS-only — on the JVM the cache exists for ref-counting purposes but
  the cached reactions are not deref-able, so this fn returns `nil`. Per
  Spec 002 §The public registrar query API and Tool-Pair §How AI tools
  attach.

  Dev-only on CLJS too — the body is gated on `interop/debug-enabled?`
  (the `goog.DEBUG` mirror) so production builds elide both the cache
  walk and the deref-and-collect machinery. Pair tools that attach in
  production explicitly opt in by toggling the gate.

  Returns `nil` for missing or destroyed frames, and `nil` in
  production builds."
  [frame-id]
  #?(:cljs
     (when interop/debug-enabled?
       (when-let [cache (:sub-cache (frame/frame frame-id))]
         (reduce-kv
           (fn [acc query-v entry]
             (assoc acc query-v
                    {:value     (when-let [r (:reaction entry)]
                                  (try @r (catch :default _ nil)))
                     :ref-count (or (:ref-count entry) 0)}))
           {}
           @cache)))
     :clj
     nil))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.routing needs to call subscribe-once but cannot `:require`
;; this namespace without a cyclic load order. Publish entry point
;; through the late-bind hook registry. See re-frame.late-bind.

(late-bind/set-fn! :subs/subscribe-once subscribe-once)
