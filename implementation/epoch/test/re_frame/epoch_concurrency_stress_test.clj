(ns re-frame.epoch-concurrency-stress-test
  "JVM concurrency stress for epoch's recorder, listener registry and ring.
  Deterministic tests cover each contract in isolation; these drive the hot
  paths concurrently and assert the same invariants hold under contention.
  CLJS is single-threaded, so these races cannot arise there.

  Stress dial: `n-threads` × `stress-iters` (default 5000, override with
  `RF2_RD7A7_STRESS_ITERS`); scenario 6 uses `RF2_J538F75_ITERS` (default 500)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            ;; Side-effect: publishes the `:epoch/*` late-bind hooks.
            [re-frame.epoch]
            [re-frame.epoch.state :as rf.epoch.state]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]
            [re-frame.test-support :as rf.test-support]
            ;; Loaded so the fixture's ns-load registrar baseline includes the
            ;; machines registrations, as in the rest of the epoch suite.
            [re-frame.machines])
  (:import [java.util.concurrent CountDownLatch]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf/configure! {:epoch-history {:trace-events-keep 5}}))}))

(def ^:private stress-iters
  (or (some-> (System/getenv "RF2_RD7A7_STRESS_ITERS") Long/parseLong)
      5000))

(def ^:private n-threads 8)

;; Bounded join, so a hang under contention is a failure rather than a stuck run.
(def ^:private join-timeout-ms 180000)

(defn- await-future [f]
  (deref f join-timeout-ms ::timeout))

(defn- all-completed?
  "Join every future; true when none timed out."
  [futures]
  (not-any? #{::timeout} (mapv await-future futures)))

(defn- bumps [n]
  (mapv (fn [i] [:bump i]) (range n)))

;; ---- Scenario 1: N concurrent settles from N independent frames ----------

(deftest n-frames-parallel-settle-stress
  (testing "parallel settles across independent frames — no drops, no doubles,
            per-frame order kept, epoch ids unique across frames"
    ;; Depth above iters so the cap never evicts; `ring-depth-evicts-oldest`
    ;; covers the cap.
    (rf/configure! {:epoch-history {:depth (* 2 stress-iters)}})
    (let [frames (mapv #(keyword "rd7a7.settle" (str "f" %)) (range n-threads))]
      (doseq [frame-id frames] (rf/make-frame {:id frame-id}))
      (rf/reg-event :bump (fn [{:keys [db]} [_ i]] {:db (assoc db :last i)}))
      (let [latch   (CountDownLatch. 1)
            futures (mapv (fn [frame-id]
                            (future
                              (.await latch)
                              (dotimes [i stress-iters]
                                (rf/dispatch-sync [:bump i] {:frame frame-id}))))
                          frames)]
        (.countDown latch)
        (is (all-completed? futures) "every settle thread completed")
        (let [histories (mapv rf/epoch-history frames)
              expected  (bumps stress-iters)]
          ;; Exact per-frame order also rules out a dropped, doubled or
          ;; cross-keyed record.
          (is (empty? (for [[frame-id h] (map vector frames histories)
                            :when (not= expected (mapv :trigger-event h))]
                        frame-id))
              "each frame's :trigger-event sequence equals its dispatch order")
          (let [ids (mapcat #(map :epoch-id %) histories)]
            (is (= (count ids) (count (set ids)))
                "epoch ids are unique across all frames")))))))

;; ---- Scenario 2: listener registry churn vs settle fan-out ---------------

(deftest register-deregister-vs-settle-fanout-stress
  (testing "register/unregister churn vs settle fan-out — a throwing listener
            stays isolated and a pinned sibling sees every settle"
    (rf/configure! {:epoch-history {:depth (* 2 stress-iters)}})
    (rf/make-frame {:id :rd7a7.fanout/main})
    (rf/reg-event :bump (fn [{:keys [db]} [_ i]] {:db (assoc db :last i)}))
    (let [seen-count (atom 0)
          churn-stop (atom false)
          latch      (CountDownLatch. 1)]
      (rf/register-listener! :epoch ::throwing (fn [_] (throw (ex-info "intentional" {}))))
      (rf/register-listener! :epoch ::counter (fn [_] (swap! seen-count inc)))
      (let [churners (mapv (fn [i]
                             (let [cb-id (keyword "rd7a7.fanout" (str "churner-" i))]
                               (future
                                 (.await latch)
                                 (while (not @churn-stop)
                                   (rf/register-listener! :epoch cb-id (fn [_] nil))
                                   (rf/unregister-listener! :epoch cb-id)))))
                           (range (max 2 (quot n-threads 2))))
            driver   (future
                       (.await latch)
                       (try
                         (dotimes [i stress-iters]
                           (rf/dispatch-sync [:bump i] {:frame :rd7a7.fanout/main}))
                         (finally (reset! churn-stop true))))]
        (.countDown latch)
        (is (all-completed? (cons driver churners))
            "the driver ran every settle (no listener throw escaped) and the churners stopped")
        (is (= stress-iters @seen-count)
            "the non-throwing sibling fired once per settle — never skipped after a throwing peer or lost under churn")))))

;; ---- Scenario 3: concurrent record + history read + restore --------------

(deftest ring-buffer-write-read-race-stress
  (testing "concurrent settles + epoch-history reads + restore attempts — no
            exception, every record lands in dispatch order"
    (rf/configure! {:epoch-history {:depth (* 2 stress-iters)}})
    (rf/make-frame {:id :rd7a7.race/main})
    (rf/reg-event :bump (fn [{:keys [db]} [_ i]] {:db (assoc db :last i)}))
    (let [consumer-stop  (atom false)
          consumer-error (atom nil)
          read-count     (atom 0)
          latch          (CountDownLatch. 1)
          producer       (future
                           (.await latch)
                           (try
                             (dotimes [i stress-iters]
                               (rf/dispatch-sync [:bump i] {:frame :rd7a7.race/main}))
                             (finally (reset! consumer-stop true))))
          consumer       (future
                           (.await latch)
                           (try
                             (while (not @consumer-stop)
                               (let [history (rf/epoch-history :rd7a7.race/main)]
                                 (swap! read-count inc)
                                 (when-not (vector? history)
                                   (throw (ex-info "epoch-history returned non-vector"
                                                   {:got history})))
                                 ;; Often refused as :rf.epoch/restore-during-drain —
                                 ;; that refusal is the contract.
                                 (when (seq history)
                                   (rf/restore-epoch! :rd7a7.race/main
                                                      (:epoch-id (rand-nth history))))))
                             (catch Throwable t
                               (reset! consumer-error t))))]
      (.countDown latch)
      (is (all-completed? [producer consumer]) "producer and consumer completed")
      (is (nil? @consumer-error)
          (str "the consumer must not throw; got " (some-> @consumer-error .getMessage)))
      (is (pos? @read-count) "the consumer actually read")
      ;; Restores replace app-db, never the ring, so the ring is the producer's alone.
      (is (= (bumps stress-iters) (mapv :trigger-event (rf/epoch-history :rd7a7.race/main)))
          "every producer record landed, in dispatch order"))))

;; ---- Scenario 4: raw back-fill under CAS contention ----------------------
;;
;; Each thread back-fills sub-runs onto its own frame's settled epoch, but all
;; frames share the one global `histories` atom, so its CAS retries. The splice
;; inside the swap is pure, so a retry must neither lose nor double a row.

(defn- sub-run-event [frame-id sub-id value]
  {:op-type   :rf.sub
   :operation :rf.sub/run
   :tags      {:rf.sub/id      sub-id
               :rf.sub/query-v [sub-id]
               :frame          frame-id
               :rf.sub/value   value}})

(deftest raw-back-fill-lands-exactly-once-under-cas-contention
  (testing "contended sub-run back-fills — every raw delta lands exactly once"
    (rf/configure! {:epoch-history {:depth (* 2 stress-iters)}})
    (let [frames (mapv #(keyword "ep0015.cas" (str "frame-" %)) (range n-threads))]
      (rf/reg-event :seed (fn [_ _] {:db {:n 0}}))
      (doseq [frame-id frames]
        (rf/make-frame {:id frame-id})
        (rf/dispatch-sync [:seed] {:frame frame-id}))
      (let [latch   (CountDownLatch. 1)
            futures (mapv
                      (fn [frame-id]
                        (future
                          (.await latch)
                          (let [epoch-id (-> (rf/epoch-history frame-id) first :epoch-id)]
                            (dotimes [i stress-iters]
                              (let [sid (keyword (str "s" i))]
                                (rf.epoch.state/back-fill-sub-run!
                                  frame-id epoch-id
                                  (sub-run-event frame-id sid i)
                                  {:sub-id sid :value i}))))))
                      frames)]
        (.countDown latch)
        (is (all-completed? futures) "every back-fill thread completed")
        (is (empty? (for [frame-id frames
                          :let  [values (->> (rf/epoch-history frame-id)
                                             first :sub-runs (map :value) sort)]
                          :when (not= (range stress-iters) values)]
                      frame-id))
            "each frame's epoch holds exactly one raw row per back-fill — none lost, none doubled")))))

;; ---- Scenario 5: back-fill vs interleaved eviction at ring cap -----------
;;
;; A back-fill runs outside any drain, so a same-frame `record!` can interleave.
;; At cap every append evicts the front and shifts indices, so the back-fill
;; must resolve its target index inside the one CAS-retried swap; resolving it
;; against an earlier deref would splice onto a positional neighbour. Each row
;; embeds the epoch-id it was aimed at, so a wrong-record splice is visible.

(deftest back-fill-snapshot-consistent-under-interleaved-eviction-stress
  (testing "back-fill vs cap-evicting settles — every accepted back-fill lands
            on the epoch it was aimed at"
    (let [cap 8]
      (rf/configure! {:epoch-history {:depth cap :trace-events-keep 50}})
      (rf/make-frame {:id :qh13yf.race/main})
      (rf/reg-event :bump (fn [{:keys [db]} [_ i]] {:db (assoc db :n i)}))
      (dotimes [i cap] (rf/dispatch-sync [:bump i] {:frame :qh13yf.race/main}))
      (let [recorder-done (atom false)
            errors        (atom [])
            accepted      (atom 0)
            latch         (CountDownLatch. 1)
            bf-event      (fn [target-id]
                            {:op-type   :rf.sub
                             :operation :rf.sub/run
                             :tags      {:rf.sub/id    :race-sub
                                         :frame        :qh13yf.race/main
                                         :rf.sub/value target-id}})
            recorder      (future
                            (.await latch)
                            (try
                              (dotimes [i stress-iters]
                                (rf/dispatch-sync [:bump (+ cap i)] {:frame :qh13yf.race/main}))
                              (catch Throwable t (swap! errors conj t))
                              (finally (reset! recorder-done true))))
            fillers       (mapv
                            (fn [_]
                              (future
                                (.await latch)
                                (try
                                  (while (not @recorder-done)
                                    (when-let [hist (seq (rf/epoch-history :qh13yf.race/main))]
                                      (let [target-id (:epoch-id (rand-nth (vec hist)))]
                                        ;; nil when the target evicted before the splice.
                                        (when (rf.epoch.state/back-fill-sub-run!
                                                :qh13yf.race/main target-id
                                                (bf-event target-id)
                                                {:sub-id :race-sub :value target-id})
                                          (swap! accepted inc)))))
                                  (catch Throwable t (swap! errors conj t)))))
                            (range (max 2 (quot n-threads 2))))]
        (.countDown latch)
        (is (all-completed? (cons recorder fillers)) "recorder and back-fillers completed")
        (is (empty? @errors)
            (str "no thread threw; got "
                 (pr-str (mapv #(.getMessage ^Throwable %) (take 3 @errors)))))
        (is (empty? (for [record (rf/epoch-history :qh13yf.race/main)
                          row    (:sub-runs record)
                          :when  (= :race-sub (:sub-id row))
                          :when  (not= (:value row) (:epoch-id record))]
                      {:landed-on (:epoch-id record) :aimed-at (:value row)}))
            "every accepted back-fill landed on the epoch it embeds — no stale-index splice")
        (is (pos? @accepted) "the back-fillers accepted splices, so the race was exercised")))))

;; ---- Scenario 6: same-id listener replacement vs fan-out / destroy -------
;;
;; One driver owns the frame lifecycle (settle → destroy → recreate) while
;; churners re-register ONE shared id, minting a generation per call. A
;; replacement must install the new generation's observation rather than erase
;; it, and no destroy may emit a torn double silence.

(def ^:private gen-churn-iters
  (or (some-> (System/getenv "RF2_J538F75_ITERS") Long/parseLong)
      500))

(deftest same-id-replacement-vs-fanout-destroy-stress
  (testing "same-id replacement churn vs settle/destroy cycles — at most one
            silence per destroy, and the live generation silences exactly once"
    (rf/make-frame {:id :j538.gen/main})
    (rf/reg-event :bump (fn [{:keys [db]} [_ i]] {:db (assoc db :last i)}))
    (let [cb-id       ::churned
          silence-cnt (atom 0)
          errors      (atom [])
          churn-stop  (atom false)
          latch       (CountDownLatch. 1)]
      (rf/register-listener! :trace ::silence-rec
                             (fn [ev]
                               (when (and (= :rf.epoch.cb/silenced-on-frame-destroy
                                             (:operation ev))
                                          (= cb-id (:cb-id (:tags ev))))
                                 (swap! silence-cnt inc))))
      (rf/register-listener! :epoch cb-id (fn [_] nil))
      (let [churners (mapv (fn [_]
                             (future
                               (.await latch)
                               (try
                                 (while (not @churn-stop)
                                   (rf/register-listener! :epoch cb-id (fn [_] nil)))
                                 (catch Throwable t (swap! errors conj t)))))
                           (range (max 2 (quot n-threads 2))))
            driver   (future
                       (.await latch)
                       (try
                         (dotimes [i gen-churn-iters]
                           (rf/dispatch-sync [:bump i] {:frame :j538.gen/main})
                           (let [before @silence-cnt]
                             (rf/destroy-frame! :j538.gen/main)
                             (let [delta (- @silence-cnt before)]
                               (when (> delta 1)
                                 (swap! errors conj
                                        (ex-info "torn double-silence for one destroy"
                                                 {:delta delta :cycle i})))))
                           (rf/make-frame {:id :j538.gen/main}))
                         (catch Throwable t (swap! errors conj t))
                         (finally (reset! churn-stop true))))]
        (.countDown latch)
        (is (all-completed? (cons driver churners)) "driver and churners completed")
        (is (empty? @errors)
            (str "no thread threw and no destroy double-silenced; got "
                 (pr-str (mapv #(.getMessage ^Throwable %) (take 3 @errors)))))
        ;; Churn stopped: a fresh registration's observation survives, carries
        ;; the live generation, and its destroy silences exactly once.
        (rf/register-listener! :epoch cb-id (fn [_] nil))
        (rf/dispatch-sync [:bump -1] {:frame :j538.gen/main})
        (let [before @silence-cnt]
          (rf/destroy-frame! :j538.gen/main)
          (is (= 1 (- @silence-cnt before))
              "the fresh generation's destroy silences exactly once"))))))
