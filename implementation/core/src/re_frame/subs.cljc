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
  policies. The v1 alpha namespace exposed `:safe`, `:no-cache`,
  `:reactive`, and `:forever` lifecycles; v2 does not (rf2-7cb2 / rf2-s9dn)."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            [re-frame.performance :as performance
             #?@(:cljs [:include-macros true])]
            [re-frame.source-coords :as source-coords]
            [re-frame.trace :as trace]))

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
        (throw (ex-info "re-frame2: bad reg-sub args"
                        {:id id :remaining remaining}))))))

(defn reg-sub
  "Register a subscription. The only sub-registration form in v2 (per
  Spec 002 §Subscriptions composing — reg-sub-raw is dropped)."
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
  ([] (registrar/clear-kind! :sub))
  ([id] (registrar/unregister! :sub id)))

;; ---- the cache ------------------------------------------------------------

(defn- cache-key
  "Per Spec 006 §Cache shape, the key is the query-vector itself.
  v2 has a single disposal algorithm (deferred ref-counting); the
  v1-era composite-key / lifecycle-policy plumbing is removed
  (rf2-7cb2 / rf2-s9dn)."
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

(def ^:private default-grace-period-ms 50)

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

(declare subscribe)

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

(defn- compute-and-cache!
  "Build the reaction for query-v and cache it. Per Spec 006 §Lookup
  algorithm: recursively resolve :<- chain, build the reaction, attach
  on-dispose to evict the cache slot.

  Per Spec 006 §No-op via value equality (rf2-719e): the user's body fn
  is wrapped in a value-equality memoization layer. Reagent's auto-run
  reaction unconditionally invokes the compute fn on any source-watch
  fire, then dedups *downstream notification* by `=`. That's one level
  too late for the spec — the body fn itself must NOT re-run when the
  resolved input value is `=` to the last-seen. The wrapper compares
  `in-vals` against the previous invocation and short-circuits to the
  cached return value when equal. Reagent's dependency tracking still
  observes every `deref` because the wrapper *is* the compute fn — only
  the user's body is suppressed.

  Per Spec 006 §What happens when a sub references an unknown sub
  (rf2-l9u5): when the registrar lookup misses, emit
  `:rf.error/no-such-sub` and build a nil-yielding reaction, but
  DO NOT store it in the cache. The miss is transient — a later
  registration (boot order, lazy load) must let the next subscribe
  build a fresh reaction against the real body. v1 had the same
  semantic by virtue of not caching the nil path; v2 preserves it
  by branching here on nil meta."
  [frame-id query-v]
  (let [query-id     (first query-v)
        meta         (registrar/lookup :sub query-id)
        _            (when (nil? meta)
                       (trace/emit-error! :rf.error/no-such-sub
                                          {:query-v query-v :frame frame-id}))
        body-fn      (:handler-fn meta)
        input-signals (:input-signals meta)
        ;; Resolve inputs: layer-1 → frame's app-db; layer-2+ → recursive subs.
        inputs (if (empty? input-signals)
                 [(frame/get-frame-db frame-id)]
                 (mapv (fn [input-q] (subscribe frame-id input-q)) input-signals))
        ;; Per Spec 006 §No-op via value equality (rf2-719e): memoize the
        ;; body call against the last-seen in-vals. The sentinel ensures
        ;; the first invocation always runs.
        last-in-vals (volatile! ::unset)
        last-result  (volatile! nil)
        reaction (adapter/make-derived-value
                   inputs
                   (fn [& in-vals]
                     (when body-fn
                       (if (= @last-in-vals in-vals)
                         @last-result
                         (let [;; Per Spec 009 §:op-type vocabulary: :sub/run
                               ;; marks subscription recompute — emitted each
                               ;; time the body actually runs against fresh
                               ;; inputs. The memo path above does NOT count
                               ;; as a re-run per Spec 006 §No-op via value
                               ;; equality.
                               _ (trace/emit! :sub/run :sub/run
                                              {:sub-id  query-id
                                               :query-v query-v
                                               :frame   frame-id})
                               ;; Per Spec 009 §Performance instrumentation
                               ;; (rf2-du3i): bracket the sub recompute in
                               ;; performance marks so prod builds with the
                               ;; perf flag enabled produce a
                               ;; `rf:sub:<sub-id>` measure entry. Default-
                               ;; off; under `:advanced` +
                               ;; `re-frame.performance/enabled?=false` the
                               ;; bracket DCEs.
                               v (try
                                   (let [v (performance/mark-and-measure :sub query-id
                                             (if (empty? input-signals)
                                               (body-fn (first in-vals) query-v)
                                               ;; Layer-2+: deliver inputs as a coll if many,
                                               ;; or singleton when only one chain entry.
                                               (if (= 1 (count input-signals))
                                                 (body-fn (first in-vals) query-v)
                                                 (body-fn (vec in-vals) query-v))))]
                                     ;; Per Spec 010 §step 6: validate sub-return
                                     ;; post-compute against the sub's :spec.
                                     ;; Failures emit :rf.error/schema-validation-failure
                                     ;; and the sub yields nil (recovery
                                     ;; :replaced-with-default).
                                     (maybe-validate-sub-return! v query-v query-id meta))
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
                                     ;; Per Spec 009 §Error contract: replaced-with-default
                                     ;; means return nil.
                                     nil))]
                           (vreset! last-in-vals in-vals)
                           (vreset! last-result v)
                           v)))))
        cache (:sub-cache (frame/frame frame-id))
        k     (cache-key query-v)]
    ;; Skip caching the no-such-sub miss — see the rf2-l9u5 note in the
    ;; docstring. The reaction is built so callers that hold a reference
    ;; deref to nil (per Spec 009 §Error contract recovery
    ;; :replaced-with-default), but the cache slot stays empty so a later
    ;; registration is observed by the next subscribe.
    (when (and cache meta)
      (swap! cache assoc k {:reaction        reaction
                            :inputs          input-signals
                            :ref-count       1
                            :on-dispose      []
                            :pending-dispose nil})
      (interop/add-on-dispose! reaction
        (fn []
          (swap! cache (fn [m]
                         (if (identical? reaction (get-in m [k :reaction]))
                           (dissoc m k)
                           m))))))
    reaction))

(defn- resolve-current-frame
  "Resolve the active frame at a no-explicit-frame call site. On CLJS
  consults the `:adapter/current-frame` late-bind hook (rf2-d4sf) so
  the React-context tier of the 3-tier resolution chain (dynamic var
  → React context → :rf/default) is LIVE — adapters publish their
  React-context-aware impl through the hook at ns-load time. When the
  hook is unbound (no adapter loaded yet, or JVM build) the fallback
  is `re-frame.frame/current-frame` which honours the dynamic-var
  tier and the `:rf/default` tier; the React-context tier silently
  no-ops in that case."
  []
  #?(:cljs (if-let [f (late-bind/get-fn :adapter/current-frame)]
             (f)
             (frame/current-frame))
     :clj  (frame/current-frame)))

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
  `goog.DEBUG=false`) elides via `interop/debug-enabled?`."
  ([query-v]
   #?(:cljs
      (let [frame-id (resolve-current-frame)]
        (when interop/debug-enabled?
          (when-let [warn! (late-bind/get-fn
                            :views/maybe-warn-plain-fn-under-non-default-frame!)]
            (warn! frame-id query-v)))
        (subscribe frame-id query-v))
      :clj
      (subscribe (resolve-current-frame) query-v)))
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

(declare unsubscribe)

(defn subscribe-value
  "Subscribe and immediately deref. Useful in handler bodies where a
  reaction isn't needed — the caller wants the value now.

  subscribe-value also calls unsubscribe immediately so it does NOT
  retain a ref on the cache entry — the caller asked a one-shot
  question. Reactive callers (Reagent views, tools holding the
  reaction) should use subscribe."
  ([query-v] (subscribe-value (resolve-current-frame) query-v))
  ([frame-id query-v]
   (let [reaction (subscribe frame-id query-v)
         v        (when reaction @reaction)]
     (unsubscribe frame-id query-v)
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
  Idempotent — a second call is a no-op because the slot is gone."
  [cache k]
  (let [reaction-to-dispose (atom nil)]
    (swap! cache
           (fn [m]
             (if-let [entry (get m k)]
               (if (<= (or (:ref-count entry) 0) 0)
                 (do (reset! reaction-to-dispose (:reaction entry))
                     (dissoc m k))
                 ;; Resubscribe arrived between schedule and fire — keep entry.
                 m)
               m)))
    (when-let [r @reaction-to-dispose]
      (try (interop/dispose! r)
           (catch #?(:clj Throwable :cljs :default) _ nil)))
    nil))

(defn unsubscribe
  "Decrement the ref-count on the cached subscription for query-v.
  When ref-count reaches 0, schedule the entry for disposal after the
  configured grace-period (default 50ms; see configure!). If a new
  subscriber arrives within the window, disposal is cancelled and the
  cached value is reused. Per Spec 006 §Reference counting and disposal.

  When grace-period is 0, disposal is synchronous — useful for tests.

  Reagent views auto-dispose via the reaction lifecycle and don't
  need to call this explicitly. Tests, REPL sessions, and tools that
  subscribe imperatively should call unsubscribe when they're done
  to release the cache slot."
  ([query-v] (unsubscribe (resolve-current-frame) query-v))
  ([frame-id query-v]
   (when-let [cache (:sub-cache (frame/frame frame-id))]
     (let [k     (cache-key query-v)
           grace (grace-period-ms)
           dropped-to-zero? (atom false)]
       (swap! cache
              (fn [m]
                (if-let [entry (get m k)]
                  (let [old-n (or (:ref-count entry) 1)
                        n     (max 0 (dec old-n))]
                    ;; Only trigger drop-to-zero on the 1→0 transition AND only
                    ;; when no grace-period timer is already in flight. Calling
                    ;; `unsubscribe` past zero (idempotent misuse — e.g. cleanup
                    ;; in both a teardown hook and a `finally`) must not stack
                    ;; new `pending-dispose` timers on top of the prior handle.
                    (if (and (= 1 old-n)
                             (zero? n)
                             (nil? (:pending-dispose entry)))
                      (do (reset! dropped-to-zero? true)
                          (assoc m k (assoc entry :ref-count 0)))
                      (assoc-in m [k :ref-count] n)))
                  m)))
       (when @dropped-to-zero?
         (if (zero? grace)
           ;; Grace = 0: dispose synchronously (the test/explicit-tear-down path).
           (dispose-entry-now! cache k)
           ;; Grace > 0: schedule deferred disposal. Stash the timer handle
           ;; so a re-subscribe inside the window can cancel it.
           (let [handle (interop/set-timeout!
                          (fn []
                            (dispose-entry-now! cache k))
                          grace)]
             (swap! cache
                    (fn [m]
                      (if-let [entry (get m k)]
                        ;; Only stash the handle if ref-count is still 0 —
                        ;; a subscriber may have arrived between our swap!
                        ;; above and set-timeout! returning.
                        (if (<= (or (:ref-count entry) 0) 0)
                          (assoc m k (assoc entry :pending-dispose handle))
                          (do (try (interop/clear-timeout! handle)
                                   (catch #?(:clj Throwable :cljs :default) _ nil))
                              m))
                        (do (try (interop/clear-timeout! handle)
                                 (catch #?(:clj Throwable :cljs :default) _ nil))
                            m)))))))
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
        (let [evictions (atom [])
              pending   (atom [])]
          (swap! cache
                 (fn [m]
                   (let [hit-keys (->> (keys m)
                                       (filter #(= id (first %))))]
                     (doseq [k hit-keys]
                       (when-let [r (get-in m [k :reaction])]
                         (swap! evictions conj r))
                       (when-let [h (get-in m [k :pending-dispose])]
                         (swap! pending conj h)))
                     (apply dissoc m hit-keys))))
          ;; Cancel any pending grace-period timers for the evicted slots —
          ;; the reaction is being disposed now, so the deferred path
          ;; would fire against a stale closure.
          (doseq [h @pending]
            (try (interop/clear-timeout! h)
                 (catch #?(:clj Throwable :cljs :default) _ nil)))
          (doseq [r @evictions]
            (try (interop/dispose! r)
                 (catch #?(:clj Throwable :cljs :default) _ nil))))))))

(defonce ^:private _hot-reload-hook
  (do (registrar/add-replacement-hook! invalidate-sub-on-replace!)
      :installed))

(defn clear-subscription-cache!
  "Dispose every cached entry and clear the cache. Test fixtures use this.
  Cancels any pending grace-period timers before disposing — a deferred
  disposal landing after this fn returned would close over a stale
  reaction."
  ([] (clear-subscription-cache! :rf/default))
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
  (let [subs-meta (registrar/handlers :sub)]
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
;; re-frame.routing needs to call subscribe-value but cannot `:require`
;; this namespace without a cyclic load order. Publish entry point
;; through the late-bind hook registry. See re-frame.late-bind.

(late-bind/set-fn! :subs/subscribe-value subscribe-value)
