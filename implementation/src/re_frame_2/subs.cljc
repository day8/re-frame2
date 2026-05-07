(ns re-frame-2.subs
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
  (:require [re-frame-2.registrar :as registrar]
            [re-frame-2.frame :as frame]
            [re-frame-2.substrate.adapter :as adapter]
            [re-frame-2.interop :as interop]
            [re-frame-2.trace :as trace]))

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

(defn- compute-and-cache!
  "Build the reaction for query-v and cache it. Per Spec 006 §Lookup
  algorithm: recursively resolve :<- chain, build the reaction, attach
  on-dispose to evict the cache slot."
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
        reaction (adapter/make-derived-value
                   inputs
                   (fn [& in-vals]
                     (when body-fn
                       (try
                         (if (empty? input-signals)
                           (body-fn (first in-vals) query-v)
                           ;; Layer-2+: deliver inputs as a coll if many,
                           ;; or singleton when only one chain entry.
                           (if (= 1 (count input-signals))
                             (body-fn (first in-vals) query-v)
                             (body-fn (vec in-vals) query-v)))
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
                           nil)))))
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
  build-and-cache on miss; reuse on hit."
  ([query-v] (subscribe :rf/default query-v))
  ([frame-id query-v]
   (let [frame-record (frame/frame frame-id)
         _ (when (nil? frame-record)
             (trace/emit-error! :rf.error/frame-destroyed
                                {:frame frame-id :query-v query-v}))
         cache (:sub-cache frame-record)
         k     (cache-key query-v)]
     (if-let [entry (get @cache k)]
       (do (swap! cache update-in [k :ref-count] (fnil inc 1))
           (:reaction entry))
       (compute-and-cache! frame-id query-v)))))

(declare unsubscribe)

(defn subscribe-value
  "Subscribe and immediately deref. Useful in handler bodies where a
  reaction isn't needed — the caller wants the value now.

  subscribe-value also calls unsubscribe immediately so it does NOT
  retain a ref on the cache entry — the caller asked a one-shot
  question. Reactive callers (Reagent views, tools holding the
  reaction) should use subscribe."
  ([query-v] (subscribe-value :rf/default query-v))
  ([frame-id query-v]
   (let [reaction (subscribe frame-id query-v)
         v        (when reaction @reaction)]
     (unsubscribe frame-id query-v)
     v)))

(defn unsubscribe
  "Decrement the ref-count on the cached subscription for query-v.
  When ref-count reaches 0, dispose the reaction and remove the
  cache slot. Per Spec 006 §Reference counting and disposal.

  Reagent views auto-dispose via the reaction lifecycle and don't
  need to call this explicitly. Tests, REPL sessions, and tools that
  subscribe imperatively should call unsubscribe when they're done
  to release the cache slot."
  ([query-v] (unsubscribe :rf/default query-v))
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
