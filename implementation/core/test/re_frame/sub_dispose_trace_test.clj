(ns re-frame.sub-dispose-trace-test
  "Every sub-cache eviction emits one `:rf.sub/dispose` trace (Spec 009
  §`:rf.sub/dispose`): `:op-type :rf.sub`, `:tags {:frame :rf.sub/id
  :rf.sub/query-v :rf.sub/reason}`, the reason one of `:no-more-derefers`,
  `:hot-reload`, `:cache-clear` and `:frame-destroy`. The emit rides the same
  CAS-winner check that gates `rf.interop/dispose!`, so one eviction emits once.

  The emit is dev instrumentation, elided under `-Dre-frame.debug=false`, so a
  deftest whose every claim is about it is tagged `^:requires-debug`, which the
  `:prod-gate` lane excludes. The untagged ones pin production cache behaviour
  and keep their emit assertions behind `rf.interop/debug-enabled?`."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.registrar :as rf.registrar]
            [re-frame.schemas :as rf.schemas]
            [re-frame.flows :as rf.flows]
            [re-frame.interop :as rf.interop]
            [re-frame.subs :as rf.subs]
            [re-frame.subs.cache :as rf.subs.cache]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(defn- reset-runtime [test-fn]
  (rf.registrar/clear-all!)
  (reset! rf.frame/frames {})
  (rf.flows/reset-flows!)
  (rf.schemas/clear-schemas-by-frame!)
  (rf/init! rf.substrate.plain-atom/adapter)
  ;; `init!` does not synthesise `:rf/default` and ambient reads need a
  ;; carried frame (EP-0002), so register it and pin it as the scope.
  (rf.frame/ensure-default-frame!)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  [id]
  (let [acc (atom [])]
    (rf/register-listener! :trace id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- dispose-events
  [traces]
  (filterv #(= :rf.sub/dispose (:operation %)) traces))

(defn- dispose-tags
  "The frequencies of each dispose event's canonical tags."
  [acc]
  (frequencies (map #(select-keys (:tags %) [:frame :rf.sub/id :rf.sub/query-v :rf.sub/reason])
                    (dispose-events @acc))))

(defn- tags-for [reason & query-vs]
  (frequencies (for [q query-vs]
                 {:frame :rf/default :rf.sub/id (first q) :rf.sub/query-v q :rf.sub/reason reason})))

(defn- reg-sum-subs! []
  (rf/reg-event :init (fn [_ _] {:db {:a 2 :b 3}}))
  (rf/reg-sub :sub/a (fn [db _] (:a db)))
  (rf/reg-sub :sub/b (fn [db _] (:b db)))
  (rf/reg-sub :sub/sum
    {:inputs [[:sub/a] [:sub/b]]}
    (fn [[a b] _] (+ a b)))
  (rf/dispatch-sync [:init]))

(defn- slot-ref-counts
  "`{query-v ref-count}` for the three slots, nil for an evicted one."
  [cache]
  (into {}
        (map (fn [q] [q (some-> (get @cache q) :ref-count)]))
        [[:sub/sum] [:sub/a] [:sub/b]]))

(deftest ^:requires-debug dispose-emits-on-last-unsubscribe
  (rf/reg-event :init (fn [_ _] {:db {:a 42}}))
  (rf/reg-sub :sub/a (fn [db _] (:a db)))
  (rf/dispatch-sync [:init])
  (let [acc (collect-traces! ::layer-1-no-derefers)]
    (try
      (let [r (rf/subscribe [:sub/a])]
        @r
        (rf/unsubscribe r))
      (is (= [:rf.sub] (mapv :op-type (dispose-events @acc))))
      (is (= (tags-for :no-more-derefers [:sub/a]) (dispose-tags acc)))
      (finally
        (rf/unregister-listener! :trace ::layer-1-no-derefers)))))

(deftest ^:requires-debug dispose-hot-reload-fires-per-evicted-slot
  ;; Re-registering a sub evicts every cached query-arg variant of it.
  (rf/reg-event :init (fn [_ _] {:db {:items {:a 1 :b 2 :c 3}}}))
  (rf/reg-sub :sub/item (fn [db [_ k]] (get-in db [:items k])))
  (rf/dispatch-sync [:init])
  (let [acc (collect-traces! ::hot-reload-many)]
    (try
      (rf/subscribe [:sub/item :a])
      (rf/subscribe [:sub/item :b])
      (rf/subscribe [:sub/item :c])
      (rf/reg-sub :sub/item (fn [db [_ k]] (* 100 (get-in db [:items k]))))
      (is (= (tags-for :hot-reload [:sub/item :a] [:sub/item :b] [:sub/item :c])
             (dispose-tags acc)))
      (finally
        (rf/unregister-listener! :trace ::hot-reload-many)))))

(deftest ^:requires-debug clear-sub-cache-emits-exactly-one-dispose-per-slot-for-layered-sub
  ;; The on-dispose cascade finds the already-cleared cache, so no input is
  ;; emitted twice or re-reasoned `:no-more-derefers`.
  (reg-sum-subs!)
  (let [acc (collect-traces! ::cache-clear-layered)]
    (try
      @(rf/subscribe [:sub/sum])
      (rf.subs.cache/clear-sub-cache!)
      (is (= (tags-for :cache-clear [:sub/sum] [:sub/a] [:sub/b]) (dispose-tags acc)))
      (finally
        (rf/unregister-listener! :trace ::cache-clear-layered)))))

(deftest input-dispose-throw-is-surfaced-and-isolated
  ;; One declared input's release throwing during a layer-2 disposal must not
  ;; abort the walk over its siblings (production behaviour: the try/catch is
  ;; not gated), and in dev the throw is surfaced as
  ;; `:rf.warning/sub-input-dispose-exception` rather than discarded.
  (reg-sum-subs!)
  (let [acc           (collect-traces! ::input-dispose-throw)
        ;; The parent's own release goes straight to the cache's
        ;; identity-guarded decrement, so redefining the per-input release
        ;; (`unsubscribe-if-reaction`, which the on-dispose walk calls) leaves
        ;; the parent's release real.
        real-unsub    @#'rf.subs/unsubscribe
        real-unsub-if @#'rf.subs/unsubscribe-if-reaction
        cache         (:sub-cache (rf.frame/frame :rf/default))
        r             (rf/subscribe [:sub/sum])]
    (try
      @r
      (with-redefs [rf.subs/unsubscribe-if-reaction
                    (fn [frame-id query-v reaction]
                      (if (= query-v [:sub/a])
                        (throw (ex-info "boom: custom adapter -dispose threw" {:query-v query-v}))
                        (real-unsub-if frame-id query-v reaction)))]
        (real-unsub r))
      (is (= {[:sub/sum] nil [:sub/b] nil}
             (select-keys (slot-ref-counts cache) [[:sub/sum] [:sub/b]]))
          ":sub/b released and the parent evicted despite the :sub/a throw")
      (when rf.interop/debug-enabled?
        ;; `emit-error!` builds an `:error` envelope; the warning category
        ;; rides `:operation` and `[:tags :category]`, `:recovery` the top level.
        (is (= [{:category :rf.warning/sub-input-dispose-exception :frame :rf/default
                 :rf.sub/query-v [:sub/a] :where :on-dispose :recovery :ignored :exception? true}]
               (for [ev @acc
                     :when (= :rf.warning/sub-input-dispose-exception (:operation ev))]
                 (assoc (select-keys (:tags ev) [:category :frame :rf.sub/query-v :where])
                        :recovery (:recovery ev)
                        :exception? (some? (:exception (:tags ev))))))))
      (finally
        (rf/unregister-listener! :trace ::input-dispose-throw)))))

;; A ratom-family substrate disposes a cached reaction by its own route when its
;; last watcher drops, without consulting `:ref-count` (Spec 006 §Which lifetime
;; governs a ratom adapter). An explicit `subscribe` holds the slot on every
;; adapter, so that dispose keeps it: the on-dispose hook releases only the
;; render-owned share (always zero on the JVM) and registers itself again, and
;; the `unsubscribe` that finally drives 1 -> 0 still releases the inputs and
;; emits once per evicted layer. `rf.interop/dispose!` on the cached reaction is
;; exactly the call that route makes.
(deftest substrate-dispose-keeps-a-held-slot-until-its-unsubscribe
  (reg-sum-subs!)
  (let [acc   (collect-traces! ::held-substrate-dispose)
        cache (:sub-cache (rf.frame/frame :rf/default))
        held  {[:sub/sum] 1 [:sub/a] 1 [:sub/b] 1}]
    (try
      (let [r (rf/subscribe [:sub/sum])]
        @r
        (rf.interop/dispose! r)
        (is (= [held true]
               [(slot-ref-counts cache) (identical? r (get-in @cache [[:sub/sum] :reaction]))])
            "the substrate's dispose evicted nothing the explicit hold keeps")
        ;; A second substrate dispose reaches the hook the first one re-armed.
        (rf.interop/dispose! r)
        (is (= held (slot-ref-counts cache)))
        (when rf.interop/debug-enabled?
          (is (empty? (dispose-events @acc)) "no :rf.sub/dispose while the explicit hold lives"))
        (rf/unsubscribe r)
        (is (= {[:sub/sum] nil [:sub/a] nil [:sub/b] nil} (slot-ref-counts cache))
            "the unsubscribe evicted the parent and cascaded to both inputs")
        (when rf.interop/debug-enabled?
          (is (= (tags-for :no-more-derefers [:sub/sum] [:sub/a] [:sub/b]) (dispose-tags acc))
              "one :rf.sub/dispose per evicted layer, at the unsubscribe")))
      (finally
        (rf/unregister-listener! :trace ::held-substrate-dispose)))))
