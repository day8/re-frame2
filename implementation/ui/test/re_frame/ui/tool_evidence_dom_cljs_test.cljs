(ns re-frame.ui.tool-evidence-dom-cljs-test
  "rf2-vxgfnd.75 — mounted proof for the tool-tier invalidation-evidence
  projection (`re-frame.ui.tool.evidence`).

  A REAL first-party ViewCell under a live client root: a dispatched event
  moves the sub, the observation port's `on-change` fires, the ViewCell
  flush delivers the coalesced bounded evidence into the installed
  projection — never a direct sink call (the AC's integration axis). The
  projection row carries the developer-facing identity (view id + the LIVE
  root's root-id) and the bounded accumulator; tearing the root down removes
  the cell from the projection, and uninstalling releases everything."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            ["react" :as React]
            [re-frame.core :as rf]
            [re-frame.test-support :as test-support]
            [re-frame.ui :as ui :refer [defview]]
            [re-frame.ui.reactive :as reactive]
            [re-frame.ui.test :as uit]
            [re-frame.ui.tool.evidence :as evidence]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
   {:adapter ui/adapter
    :ambient-frame nil
    :async? true
    :init-fn (fn []
               (reactive/reset-scheduler!)
               (evidence/force-release!))}))

(defview evidence-probe []
  [:output {:data-role "evidence-probe"}
   (str (ui/sub [::value]))])

(defn- probe-row []
  (some #(when (= ::evidence-probe (:view-id %)) %) (evidence/projection)))

(def ^:private Activity React/Activity)

(def ^:private gc-request-key "__RF2_TOOL_EVIDENCE_GC_REQUEST__")
(def ^:private gc-canonical-key "__RF2_TOOL_EVIDENCE_GC_CANONICAL__")

(defonce ^:private gc-request-seq* (atom 0))

(defn- request-browser-gc!
  "Ask the Playwright browser-test runner for one deterministic collection.
  The runner acknowledges unsupported engines explicitly; a short fallback
  also clears the request when this test page is opened without that runner."
  []
  (js/Promise.
   (fn [resolve _reject]
     (let [token   (str "tool-evidence-" (swap! gc-request-seq* inc))
           settled? (atom false)
           finish! (fn [result]
                     (when (compare-and-set! settled? false true)
                       (js-delete js/globalThis gc-request-key)
                       (resolve result)))
           request #js {:token token :ack finish!}]
       (aset js/globalThis gc-request-key request)
       ;; Manual/foreign runner: do not strand cljs.test waiting for a control
       ;; it cannot supply. The canonical Playwright loop polls every 200ms.
       (js/setTimeout #(finish! #js {:supported false
                                     :error "Playwright requestGC handshake absent"})
                      2000)))))

(defn- rows-for [view-id]
  (filterv #(= view-id (:view-id %)) (evidence/projection)))

(defview churn-leaf [{:keys [generation]}]
  [:output {:data-role "evidence-churn-leaf"
            :data-generation generation}
   (str (ui/sub [::churn-value]))])

(defview retained-activity-leaf []
  [:output {:data-role "evidence-activity-leaf"}
   (str (ui/sub [::churn-value]))])

(defview churn-host []
  [:section {:data-role "evidence-churn-host"}
   [Activity {:mode (if (ui/sub [::activity-hidden?]) "hidden" "visible")}
    [retained-activity-leaf]]
   (when (ui/sub [::churn-visible?])
     [churn-leaf {:generation (ui/sub [::churn-generation])}])])

(defn- canonical-gc-runner?
  []
  (true? (aget js/globalThis gc-canonical-key)))

(defn- collect-until!
  "Request at least one GC handshake, then bounded collection/compaction rounds
  until `done?`. The mandatory first request is the canonical CI positive
  control even when the host happened to collect before the explicit request."
  [done? remaining requests]
  (if (and (pos? requests) (done?))
    (js/Promise.resolve {:supported? true
                         :bounded? true
                         :requests requests})
    (if (zero? remaining)
      (js/Promise.resolve {:supported? true
                           :bounded? false
                           :requests requests})
      (-> (request-browser-gc!)
          (.then (fn [result]
                   (if-not (.-supported result)
                     {:supported? false
                      :bounded? false
                      :requests requests
                      :error (.-error result)}
                     ;; Yield one job boundary after collection before the
                     ;; projection dereferences/compacts its WeakRef roster.
                     (-> (js/Promise.resolve nil)
                         (.then #(collect-until! done?
                                                (dec remaining)
                                                (inc requests)))))))))))

(defn- churn-cycles!
  "Run `cycles` mount -> invalidate -> ordinary-remove cycles serially under
  one live root. Explicit recursion keeps each returned value a Promise (and
  avoids a collection reduce retaining completed callback frames/cells)."
  [f generation cycles]
  (if (= generation cycles)
    (js/Promise.resolve nil)
    (-> (uit/flush!
         #(rf/dispatch-sync [::churn-set {:visible? true
                                          :generation generation}] {:frame f}))
        (.then #(uit/flush!
                 (fn []
                   (rf/dispatch-sync [::churn-set
                                      {:value (+ 2 generation)}] {:frame f}))))
        (.then #(uit/flush!
                 (fn []
                   (rf/dispatch-sync [::churn-set {:visible? false}] {:frame f}))))
        (.then #(churn-cycles! f (inc generation) cycles)))))

(def ^:private cycle-evidence
  {:first-epoch 1 :latest-epoch 1 :count 1
   :causes #{:subscription} :targets [] :dropped #{} :dropped-exact? true})

(defn- publish-cycle-refs!
  "Publish direct + nested query→ViewCell cycles, assert the public projection
  is already opaque, then return only WeakRefs so collection is observable."
  []
  (let [sink   (evidence/installed-sink)
        direct (reactive/make-cell ::cycle-direct)
        nested (reactive/make-cell ::cycle-nested)]
    (sink direct (assoc cycle-evidence
                        :targets [[:sub :rf/default [::q direct]]]))
    (sink nested (assoc cycle-evidence
                        :targets [[:sub :rf/default
                                   [::q {:nested {:cell nested}}]]]))
    (doseq [vid [::cycle-direct ::cycle-nested]]
      (let [ev (:evidence (first (rows-for vid)))]
        (is (false? (:targets-exact? ev)))
        (is (= {:rf.ui.evidence/opaque :dynamic}
               (get-in ev [:targets 0 2 1])))))
    [(js/WeakRef. direct) (js/WeakRef. nested)]))

(deftest query-cycles-are-collectible-in-the-real-browser-heap
  (when (browser?)
    (is (true? (evidence/install! ::cycle-proof)))
    (async done
      (let [refs (publish-cycle-refs!)]
        (-> (collect-until!
             #(and (every? (fn [ref] (nil? (.deref ref))) refs)
                   (empty? (rows-for ::cycle-direct))
                   (empty? (rows-for ::cycle-nested)))
             10 0)
            (.then
             (fn [{:keys [supported? bounded? requests error]}]
               (cond
                 supported?
                 (do
                   (is (pos? requests))
                   (is (true? bounded?)
                       "direct/nested query cycles collected after projection"))

                 (canonical-gc-runner?)
                 (is false (str "canonical requestGC unavailable: " error))

                 :else
                 (is true (str "SKIP GC-dependent assertions: " error)))
               (is (true? (evidence/uninstall! ::cycle-proof)))))
            ;; Reports and releases; it never finishes (rf2-fyba). `force-release!`
            ;; stays failure-only: the success path asserts the orderly `uninstall!`
            ;; above, and forcing after that would mask a leak rather than clean up.
            (.catch
             (fn [e]
               (evidence/force-release!)
               (is false (str "unexpected rejection: " e))
               nil))
            (.then (fn [_] (done))))))))

(deftest a-real-invalidation-flows-through-the-flush-into-the-projection
  (when (browser?)
    (rf/reg-sub ::value (fn [db _] (:value db)))
    (rf/reg-event ::set-value
                  (fn [{:keys [db]} [_ v]] {:db (assoc db :value v)}))
    (let [f (rf/make-frame {:initial-events [[:rf/set-db {:value 0}]]})]
      (is (true? (evidence/install! ::xray-panel)))
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f}
                                  [evidence-probe]]]
              (testing "mount alone projects nothing — no invalidation yet"
                (is (= "0" (.-textContent
                            (.querySelector root "[data-role='evidence-probe']"))))
                (is (nil? (probe-row))))
              (-> (uit/flush! #(rf/dispatch-sync [::set-value 1] {:frame f}))
                  (.then
                   (fn []
                     (testing "the port movement rendered…"
                       (is (= "1" (.-textContent
                                   (.querySelector root
                                              "[data-role='evidence-probe']")))))
                     (testing "…and the flush projected the bounded evidence
                               with developer-facing identity (AC1/AC2/AC6)"
                       (let [{:keys [cell-id view-id root-id evidence]}
                             (probe-row)]
                         (is (some? cell-id) "a stable projection ordinal")
                         (is (= ::evidence-probe view-id))
                         (is (keyword? root-id) "the LIVE root's root-id")
                         (is (= "rf.ui.test.root" (namespace root-id))
                             "…the with-root-minted client root")
                         (is (pos? (:count evidence)))
                         (is (= 1 (:batches evidence)) "one coalesced flush")
                         (is (contains? (:causes evidence) :subscription)
                             "the port's real :subscription cause")
                         (is (some #(= [::value] (nth % 2)) (:targets evidence))
                             "the moving sub is a shown target")
                         (is (some? (:latest-epoch evidence))
                             "epoch attribution rode the port payload")
                         (is (= #{} (:dropped evidence))
                             "one target — the honest loss field is empty")
                         (is (true? (:dropped-exact? evidence)))))
                     (testing "the scheduler stayed authoritative — no escape"
                       (is (nil? (reactive/last-evidence-sink-escape))))))))
            (.then
             (fn []
               (testing "root teardown removed the cell from the projection (AC4)"
                 (is (nil? (probe-row)))
                 (is (= [] (evidence/projection))))
               (testing "uninstall releases the registration"
                 (is (true? (evidence/uninstall! ::xray-panel)))
                 (is (nil? (evidence/installed-owner))))))
            (.catch
             (fn [e]
               (evidence/force-release!)
               (is false (str "unexpected rejection: " e))
               nil))
            ;; Frame teardown is what both paths shared, so it rides the single
            ;; trailing step and `done` is the last thing this row does.
            (.then (fn [_] (rf/destroy-frame! f) (done))))))))

(deftest ordinary-unmount-churn-is-bounded-under-one-live-browser-root
  (when (browser?)
    (rf/reg-sub ::churn-value (fn [db _] (:value db)))
    (rf/reg-sub ::churn-visible? (fn [db _] (:visible? db)))
    (rf/reg-sub ::churn-generation (fn [db _] (:generation db)))
    (rf/reg-sub ::activity-hidden? (fn [db _] (:activity-hidden? db)))
    (rf/reg-event ::churn-set
                  (fn [{:keys [db]} [_ attrs]] {:db (merge db attrs)}))
    (let [f      (rf/make-frame
                  {:initial-events [[:rf/set-db {:value 0
                                                 :visible? false
                                                 :generation 0
                                                 :activity-hidden? false}]]})
          cycles 24]
      (is (true? (evidence/install! ::xray-panel)))
      (async done
        (-> (uit/with-root [root [ui/frame-provider {:frame f} [churn-host]]]
              ;; Give the Activity leaf real evidence, then hide it. React
              ;; retains that fiber/cell even though the lifecycle disconnects.
              (-> (uit/flush! #(rf/dispatch-sync [::churn-set {:value 1}] {:frame f}))
                  (.then #(uit/flush!
                           (fn []
                             (rf/dispatch-sync [::churn-set
                                                {:activity-hidden? true}]
                                               {:frame f}))))
                  (.then
                   (fn []
                     (let [activity-row (first (rows-for
                                                ::retained-activity-leaf))
                           activity-id  (:cell-id activity-row)
                           activity-ev  (:evidence activity-row)]
                       (is (some? activity-id)
                           "precondition: hidden Activity cell published a row")
                       ;; Repeatedly mount a fresh conditional child, invalidate
                       ;; it, then remove it under the SAME still-live root.
                       (-> (churn-cycles! f 0 cycles)
                           (.then
                            (fn []
                              ;; The engine may collect before our explicit
                              ;; request. Only the upper bound is deterministic.
                              (is (<= (count (rows-for ::churn-leaf)) cycles))
                              (collect-until!
                               #(zero? (count (rows-for ::churn-leaf)))
                               10 0)))
                           (.then
                            (fn [{:keys [supported? bounded? requests error]}]
                              (cond
                                supported?
                                (do
                                  (is (pos? requests)
                                      "at least one GC handshake executed")
                                  (is (true? bounded?)
                                      "ordinary-unmount rows collected and the
                                       live projection returned to baseline")
                                  (is (= [] (rows-for ::churn-leaf))))

                                (canonical-gc-runner?)
                                (is false
                                    (str "canonical Playwright runner MUST "
                                         "provide requestGC: " error))

                                :else
                                (is true
                                    (str "SKIP GC-dependent assertions: " error)))
                              (let [activity-row
                                    (first (rows-for
                                            ::retained-activity-leaf))]
                                (is (= activity-id (:cell-id activity-row))
                                    "Activity-hidden cell retained the SAME row")
                                (is (= activity-ev (:evidence activity-row))
                                    "GC preserved exact classification/loss"))
                              (uit/flush!
                               #(rf/dispatch-sync
                                 [::churn-set {:activity-hidden? false}] {:frame f}))))
                           (.then
                            (fn []
                              (is (= "25"
                                     (.-textContent
                                      (.querySelector
                                       root
                                       "[data-role='evidence-activity-leaf']")))
                                  "Activity reveal reconnected the retained leaf")
                              (is (= activity-id
                                     (:cell-id
                                      (first (rows-for
                                              ::retained-activity-leaf)))))
                              (is (= activity-ev
                                     (:evidence
                                      (first (rows-for
                                              ::retained-activity-leaf)))))))))))
            (.then
             (fn []
               (is (true? (evidence/uninstall! ::xray-panel)))
               (is (= [] (evidence/projection))
                   "tool uninstall immediately drops the Activity-retained row")))
            (.catch
             (fn [e]
               (js-delete js/globalThis gc-request-key)
               (evidence/force-release!)
               (is false (str "unexpected rejection: " e "\n" (.-stack e)))
               nil))
            (.then (fn [_] (rf/destroy-frame! f) (done))))))))))
