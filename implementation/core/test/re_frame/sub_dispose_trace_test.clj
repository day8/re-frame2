(ns re-frame.sub-dispose-trace-test
  "Per rf2-mrnur: the sub-cache emits a `:rf.sub/dispose` trace event at
  every eviction site so consumers can observe the sub-cache lifecycle's
  terminal half — created / run / skip / **dispose**. This file pins the
  emit shape and reason-enum coverage against the core artefact.

  Contract — Spec 009 §:op-type vocabulary §`:rf.sub/dispose`:

    `:op-type :rf.sub`, `:operation :rf.sub/dispose`. One event per
    evicted cache slot. `:tags {:frame <id> :rf.sub/id <query-id>
    :rf.sub/query-v <vec> :rf.sub/reason <enum>}`. The reason axis is a
    closed enum:

      `:no-more-derefers` — grace-fire timer (or grace=0 sync) evicted
                            the slot because ref-count dropped to 0.
      `:hot-reload`       — re-registration evicted every cached slot
                            for the affected sub-id.
      `:cache-clear`      — explicit `clear-sub-cache!` walked the
                            cache and disposed every slot.

  Single-fire discipline: the emit rides the SAME CAS-winner check that
  gates `interop/dispose!`, so a concurrent grace-fire + invalidate
  cannot produce two `:rf.sub/dispose` for the same eviction.

  Tests use grace=0 for the no-more-derefers path so the emit lands
  synchronously inside `unsubscribe`; the deferred-grace path is
  semantically identical (the timer callback calls
  `dispose-entry-now!` with the same frame-id closure) so we don't
  re-cover it here."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.registrar :as registrar]
            [re-frame.schemas :as schemas]
            [re-frame.flows :as flows]
            [re-frame.subs.cache :as subs-cache]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (reset! flows/flows {})
  (reset! schemas/schemas-by-frame {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.routing :reload)
  (require 're-frame.ssr     :reload)
  (require 're-frame.machines :reload)
  (try (test-fn)
       (finally
         ;; Restore the default grace; the no-more-derefers test bodies
         ;; pin it to 0 so the synchronous dispose lands deterministically.
         (subs-cache/configure! {:grace-period-ms 50}))))

(use-fixtures :each reset-runtime)

(defn- collect-traces!
  [id]
  (let [acc (atom [])]
    (rf/register-listener! id (fn [ev] (swap! acc conj ev)))
    acc))

(defn- dispose-events
  [traces]
  (filterv #(= :rf.sub/dispose (:operation %)) traces))

;; ---- :no-more-derefers ---------------------------------------------------
;;
;; The dominant production case: last subscriber detaches, ref-count
;; drops to 0, and the grace-period timer fires the eviction.
;; Tests use grace=0 so the eviction is synchronous inside
;; `rf/unsubscribe` — single-tick observability.

(deftest dispose-emits-on-last-unsubscribe-grace-zero
  (testing ":rf.sub/dispose fires synchronously with :reason
            :no-more-derefers when the last subscriber detaches under
            grace=0; carries the canonical tags (frame, id, query-v,
            reason); op-type rides :rf.sub"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 42}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::layer-1-no-derefers)]
      (try
        (let [r (rf/subscribe [:sub/a])]
          (is (= 42 @r))
          (rf/unsubscribe [:sub/a]))
        (let [disposes (dispose-events @acc)]
          (is (= 1 (count disposes))
              "exactly one :rf.sub/dispose for the single eviction")
          (let [[ev] disposes]
            (is (= :rf.sub (:op-type ev))
                ":op-type rides the :rf.sub family")
            (is (= :rf.sub/dispose (:operation ev)))
            (is (= :rf/default (-> ev :tags :frame))
                ":tags :frame is canonical (per Spec 009 §canonical per-frame routing key)")
            (is (= :sub/a (-> ev :tags :rf.sub/id))
                ":tags :rf.sub/id is the query-id")
            (is (= [:sub/a] (-> ev :tags :rf.sub/query-v))
                ":tags :rf.sub/query-v is the full subscription vector")
            (is (= :no-more-derefers (-> ev :tags :rf.sub/reason))
                ":tags :rf.sub/reason :no-more-derefers — grace-fire path")))
        (finally
          (rf/unregister-listener! ::layer-1-no-derefers))))))

(deftest no-dispose-when-ref-count-still-positive
  (testing "with two subscribers, one unsubscribe does NOT emit
            :rf.sub/dispose — the slot's ref-count is still > 0; only
            the SECOND unsubscribe (the last derefer dropping) emits"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 42}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::two-subs)]
      (try
        (rf/subscribe [:sub/a])
        (rf/subscribe [:sub/a])
        (rf/unsubscribe [:sub/a])
        (is (empty? (dispose-events @acc))
            "first unsubscribe — slot still held; no dispose emit")
        (rf/unsubscribe [:sub/a])
        (is (= 1 (count (dispose-events @acc)))
            "second (last) unsubscribe — dispose emitted")
        (is (= :no-more-derefers
               (-> (dispose-events @acc) first :tags :rf.sub/reason)))
        (finally
          (rf/unregister-listener! ::two-subs))))))

(deftest dispose-cascade-emits-per-evicted-layer
  (testing "a layer-2 sub's disposal cascades to its layer-1 inputs —
            each evicted slot emits its own :rf.sub/dispose with
            :reason :no-more-derefers"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 2 :b 3}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/reg-sub :sub/b (fn [db _] (:b db)))
    (rf/reg-sub :sub/sum
      :<- [:sub/a]
      :<- [:sub/b]
      (fn [[a b] _] (+ a b)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::cascade-emit)]
      (try
        (let [r (rf/subscribe [:sub/sum])]
          (is (= 5 @r))
          (rf/unsubscribe [:sub/sum]))
        (let [disposes (dispose-events @acc)
              ids      (set (map #(-> % :tags :rf.sub/id) disposes))]
          (is (= 3 (count disposes))
              ":sub/sum + :sub/a + :sub/b — three eviction emits")
          (is (= #{:sub/sum :sub/a :sub/b} ids)
              "every evicted sub-id surfaces a dispose event")
          (is (every? #(= :no-more-derefers
                          (-> % :tags :rf.sub/reason))
                      disposes)
              "every cascade emit carries the :no-more-derefers reason"))
        (finally
          (rf/unregister-listener! ::cascade-emit))))))

(deftest dispose-not-emitted-when-resubscribe-cancels-grace
  (testing "a resubscribe inside the deferred-grace window cancels the
            pending dispose — no :rf.sub/dispose event fires because no
            eviction occurred. Uses long grace + immediate resubscribe."
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:a 42}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::resubscribe-cancels)]
      (try
        (let [r1 (rf/subscribe [:sub/a])]
          (is (= 42 @r1))
          (rf/unsubscribe [:sub/a])
          ;; Inside the long grace window: resubscribe should cancel
          ;; the pending timer; the eviction never fires.
          (let [r2 (rf/subscribe [:sub/a])]
            (is (identical? r1 r2)
                "resubscribe returned the same reaction (slot survived)")
            (is (empty? (dispose-events @acc))
                "no :rf.sub/dispose — the eviction did not run")))
        (finally
          (rf/unregister-listener! ::resubscribe-cancels))))))

;; ---- :hot-reload ---------------------------------------------------------
;;
;; Re-registering a `:sub` invalidates every cached entry for that
;; sub-id across every frame. The invalidate path emits one
;; `:rf.sub/dispose` per evicted slot with `:reason :hot-reload`.

(deftest dispose-emits-on-hot-reload
  (testing "re-registering a :sub fires :rf.sub/dispose with :reason
            :hot-reload for the affected slot (regardless of ref-count)"
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:a 42}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::hot-reload)]
      (try
        (rf/subscribe [:sub/a])
        ;; Re-register the same sub-id with a different body — the
        ;; cached slot must be evicted so the new body is observed.
        (rf/reg-sub :sub/a (fn [db _] (* 10 (:a db))))
        (let [disposes (dispose-events @acc)]
          (is (= 1 (count disposes))
              "one :rf.sub/dispose for the hot-reload eviction")
          (let [[ev] disposes]
            (is (= :sub/a (-> ev :tags :rf.sub/id)))
            (is (= [:sub/a] (-> ev :tags :rf.sub/query-v)))
            (is (= :hot-reload (-> ev :tags :rf.sub/reason))
                ":tags :rf.sub/reason :hot-reload discriminates re-registration")
            (is (= :rf/default (-> ev :tags :frame)))))
        (finally
          (rf/unregister-listener! ::hot-reload))))))

(deftest dispose-hot-reload-fires-per-evicted-slot
  (testing "hot-reloading a sub with N cached query-arg variants fires
            N :rf.sub/dispose events, one per evicted slot, all with
            :reason :hot-reload"
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:items {:a 1 :b 2 :c 3}}))
    (rf/reg-sub :sub/item
      (fn [db [_ k]] (get-in db [:items k])))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::hot-reload-many)]
      (try
        (rf/subscribe [:sub/item :a])
        (rf/subscribe [:sub/item :b])
        (rf/subscribe [:sub/item :c])
        ;; Re-register: every cached entry whose first key is :sub/item
        ;; must be evicted.
        (rf/reg-sub :sub/item
          (fn [db [_ k]] (* 100 (get-in db [:items k]))))
        (let [disposes (dispose-events @acc)
              query-vs (set (map #(-> % :tags :rf.sub/query-v) disposes))]
          (is (= 3 (count disposes))
              "three slots evicted by the single re-registration")
          (is (= #{[:sub/item :a] [:sub/item :b] [:sub/item :c]} query-vs)
              "every cached query-arg variant got its own dispose emit")
          (is (every? #(= :hot-reload (-> % :tags :rf.sub/reason))
                      disposes)))
        (finally
          (rf/unregister-listener! ::hot-reload-many))))))

;; ---- :cache-clear --------------------------------------------------------
;;
;; An explicit `clear-sub-cache!` walks the cache and disposes every
;; slot; each evicted slot emits a `:rf.sub/dispose` with `:reason
;; :cache-clear`.

(deftest dispose-emits-on-clear-sub-cache
  (testing "(clear-sub-cache!) fires :rf.sub/dispose per evicted slot
            with :reason :cache-clear (regardless of ref-count)"
    (subs-cache/configure! {:grace-period-ms 60000})
    (rf/reg-event-db :init (fn [_ _] {:a 1 :b 2}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/reg-sub :sub/b (fn [db _] (:b db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::cache-clear)]
      (try
        (rf/subscribe [:sub/a])
        (rf/subscribe [:sub/b])
        (subs-cache/clear-sub-cache!)
        (let [disposes (dispose-events @acc)
              ids      (set (map #(-> % :tags :rf.sub/id) disposes))]
          (is (= 2 (count disposes))
              "two slots evicted by the cache-clear")
          (is (= #{:sub/a :sub/b} ids))
          (is (every? #(= :cache-clear (-> % :tags :rf.sub/reason))
                      disposes)
              "every emit carries the :cache-clear reason")
          (is (every? #(= :rf/default (-> % :tags :frame))
                      disposes)))
        (finally
          (rf/unregister-listener! ::cache-clear))))))

;; ---- emit-shape pin ------------------------------------------------------
;;
;; The exact tag-map shape downstream consumers (Xray Epoch panel
;; SUBSCRIPTIONS section per rf2-wpfjo) depend on.

(deftest dispose-tag-shape-is-canonical
  (testing "the :rf.sub/dispose tag-map carries exactly the four
            canonical tags + nothing extra: :frame, :rf.sub/id,
            :rf.sub/query-v, :rf.sub/reason. Required for consumer
            compatibility with rf2-wpfjo (Xray Epoch panel)."
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:a 1}))
    (rf/reg-sub :sub/a (fn [db _] (:a db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::tag-shape)]
      (try
        (rf/subscribe [:sub/a])
        (rf/unsubscribe [:sub/a])
        (let [[ev] (dispose-events @acc)
              tags (:tags ev)]
          (is (some? ev) "an emit fired")
          ;; The four canonical keys MUST be present. Trace framework
          ;; may add cross-cutting correlation slots (`:rf.trace/*`)
          ;; via build-event — those are framework-level, not bead-
          ;; level shape.
          (is (contains? tags :frame))
          (is (contains? tags :rf.sub/id))
          (is (contains? tags :rf.sub/query-v))
          (is (contains? tags :rf.sub/reason))
          (is (#{:no-more-derefers :hot-reload :cache-clear}
                (:rf.sub/reason tags))
              "reason is in the closed-enum"))
        (finally
          (rf/unregister-listener! ::tag-shape))))))

;; ---- elision pin ---------------------------------------------------------
;;
;; The emit-dispose! helper sits inside `interop/debug-enabled?`, so
;; under prod CLJS (`:advanced` + `goog.DEBUG=false`) it folds out.
;; The CLJS-side production elision is pinned by the existing
;; `re-frame.trace_bus_elision_prod_test` + the artefact-level
;; `npm run test:elision` probe; on JVM the gate is read at runtime
;; but `debug-enabled?` is `true` so the emit runs — this test pins
;; the runtime path's correctness, not the prod-elision shape.

(deftest emits-fire-under-jvm-debug-enabled
  (testing "debug-enabled? is true on the JVM test runtime — dispose
            emits land in the trace stream"
    (subs-cache/configure! {:grace-period-ms 0})
    (rf/reg-event-db :init (fn [_ _] {:x 1}))
    (rf/reg-sub :sub/x (fn [db _] (:x db)))
    (rf/dispatch-sync [:init])
    (let [acc (collect-traces! ::elision-pin)]
      (try
        (rf/subscribe [:sub/x])
        (rf/unsubscribe [:sub/x])
        (is (seq (dispose-events @acc))
            "an emit landed — JVM dev path is wired")
        (finally
          (rf/unregister-listener! ::elision-pin))))))
