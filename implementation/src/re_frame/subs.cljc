(ns re-frame.subs
  "Subscriptions: registration, the per-frame sub-cache, and lookup.

  Per Spec 002 §Subscriptions composing across the signal graph and
  Spec 006 §Subscription cache — contract and operational semantics.

  Layer-1 sub: reads app-db directly via (fn [db query]).
  Layer-2 sub: reads other subs via :<- chain; (fn [inputs query]).
  Layer-3+: same shape as Layer-2 with deeper chains.

  The cache is per-frame, keyed by query-vector. Each entry holds:
    {:value v :reaction r :inputs [...] :ref-count n :on-dispose [...]}

  Invalidation runs as part of replace-container! — when app-db changes,
  the substrate adapter's reaction graph fires; layer-1 subs recompute
  if their reader's value changed by =, layer-2+ subs cascade
  topologically."
  (:require [re-frame.registrar :as registrar]
            [re-frame.frame :as frame]
            [re-frame.substrate.adapter :as adapter]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
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
        (throw (ex-info "re-frame-2: bad reg-sub args"
                        {:id id :remaining remaining}))))))

(defn reg-sub
  "Register a subscription. The only sub-registration form in v2 (per
  Spec 002 §Subscriptions composing — reg-sub-raw is dropped)."
  [id & args]
  (let [{:keys [meta handler-fn input-signals]} (parse-reg-sub-args id args)]
    (registrar/register! :sub id
      (assoc meta
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
  v1 used a composite key with :re-frame/lifecycle for forward-compat;
  v2 simplifies to the vector."
  [query-v]
  query-v)

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
  the user's body is suppressed."
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
                               v (try
                                   (let [v (if (empty? input-signals)
                                             (body-fn (first in-vals) query-v)
                                             ;; Layer-2+: deliver inputs as a coll if many,
                                             ;; or singleton when only one chain entry.
                                             (if (= 1 (count input-signals))
                                               (body-fn (first in-vals) query-v)
                                               (body-fn (vec in-vals) query-v)))]
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
    (when cache
      (swap! cache assoc k {:reaction      reaction
                            :inputs        input-signals
                            :ref-count     1
                            :on-dispose    []})
      (interop/add-on-dispose! reaction
        (fn []
          (swap! cache (fn [m]
                         (if (identical? reaction (get-in m [k :reaction]))
                           (dissoc m k)
                           m))))))
    reaction))

(defn subscribe
  "Per Spec 006 §Lookup algorithm. Returns the reaction for query-v;
  build-and-cache on miss; reuse on hit. The 1-arity form resolves
  the active frame via re-frame.frame/current-frame so subscribe
  inside a with-frame or under a frame-provider auto-routes."
  ([query-v] (subscribe (frame/current-frame) query-v))
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
           (do (swap! cache update-in [k :ref-count] (fnil inc 1))
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
  ([query-v] (subscribe-value (frame/current-frame) query-v))
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

(defn unsubscribe
  "Decrement the ref-count on the cached subscription for query-v.
  When ref-count reaches 0, dispose the reaction and remove the
  cache slot. Per Spec 006 §Reference counting and disposal.

  Reagent views auto-dispose via the reaction lifecycle and don't
  need to call this explicitly. Tests, REPL sessions, and tools that
  subscribe imperatively should call unsubscribe when they're done
  to release the cache slot."
  ([query-v] (unsubscribe (frame/current-frame) query-v))
  ([frame-id query-v]
   (when-let [cache (:sub-cache (frame/frame frame-id))]
     (let [k                   (cache-key query-v)
           reaction-to-dispose (atom nil)]
       (swap! cache
              (fn [m]
                (if-let [entry (get m k)]
                  (let [n (dec (or (:ref-count entry) 1))]
                    (if (<= n 0)
                      (do (reset! reaction-to-dispose (:reaction entry))
                          (dissoc m k))
                      (assoc-in m [k :ref-count] n)))
                  m)))
       (when-let [r @reaction-to-dispose]
         (try (interop/dispose! r)
              (catch #?(:clj Throwable :cljs :default) _ nil)))
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
        (let [evictions (atom [])]
          (swap! cache
                 (fn [m]
                   (let [hit-keys (->> (keys m)
                                       (filter #(= id (first %))))]
                     (doseq [k hit-keys]
                       (when-let [r (get-in m [k :reaction])]
                         (swap! evictions conj r)))
                     (apply dissoc m hit-keys))))
          (doseq [r @evictions]
            (try (interop/dispose! r)
                 (catch #?(:clj Throwable :cljs :default) _ nil))))))))

(defonce ^:private _hot-reload-hook
  (do (registrar/add-replacement-hook! invalidate-sub-on-replace!)
      :installed))

(defn clear-subscription-cache!
  "Dispose every cached entry and clear the cache. Test fixtures use this."
  ([] (clear-subscription-cache! :rf/default))
  ([frame-id]
   (when-let [cache (:sub-cache (frame/frame frame-id))]
     (doseq [[_k entry] @cache]
       (when-let [r (:reaction entry)]
         (try (interop/dispose! r)
              (catch #?(:clj Throwable :cljs :default) _ nil))))
     (reset! cache {}))))

;; ---- late-bind hook registration ------------------------------------------
;;
;; re-frame.routing needs to call subscribe-value but cannot `:require`
;; this namespace without a cyclic load order. Publish entry point
;; through the late-bind hook registry. See re-frame.late-bind.

(late-bind/set-fn! :subs/subscribe-value subscribe-value)
