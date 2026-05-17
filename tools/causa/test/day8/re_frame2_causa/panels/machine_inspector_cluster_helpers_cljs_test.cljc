(ns day8.re-frame2-causa.panels.machine-inspector-cluster-helpers-cljs-test
  "Pure-data tests for Causa's Machine Inspector UC2 Mode C helpers
  (rf2-juon8, Phase 3).

  Dual-target via the `_cljs_test.cljc` extension — Cognitect's CLJ
  test-runner picks the ns up via the `.*-test$` regex; Shadow's
  `:node-test` build picks it up via `cljs-test$`. Same pattern every
  Causa helper test uses.

  ## What's under test

    1. `mode-c-suggested?`     — threshold predicate.
    2. `snapshot->instance` /
       `snapshots->instances` — Phase 1/2 → Mode C widening.
    3. `normalise-instances`   — slot completeness.
    4. `cluster-instances`     — group-by `:state` / `:context-key`
                                 / `:parent-machine` with
                                 deterministic ordering.
    5. `sparkline-buckets`     — per-bucket transition counts.
    6. `cluster-pred-for`      — cluster-by → trace event match.
    7. `attach-sparkline-samples` — compose clusters + sparklines.
    8. Selection-set helpers   — add / remove / toggle / clear.
    9. `compare-table`         — diff'd cell projection."
  (:require #?(:clj  [clojure.test :refer [deftest is testing]]
               :cljs [cljs.test    :refer-macros [deftest is testing]])
            [day8.re-frame2-causa.panels.machine-inspector-cluster-helpers
             :as ch]))

;; ---- (1) mode-c-suggested? ---------------------------------------------

(deftest mode-c-suggested-respects-default-threshold
  (is (false? (ch/mode-c-suggested? 0)))
  (is (false? (ch/mode-c-suggested? 1)))
  (is (false? (ch/mode-c-suggested? 10))
      "threshold is exclusive — exactly 10 instances stays in Mode B")
  (is (true?  (ch/mode-c-suggested? 11)))
  (is (true?  (ch/mode-c-suggested? 100))))

(deftest mode-c-suggested-honours-custom-threshold
  (is (false? (ch/mode-c-suggested? 4 5)))
  (is (true?  (ch/mode-c-suggested? 6 5))))

(deftest mode-c-suggested-tolerates-nil
  (is (false? (ch/mode-c-suggested? nil)))
  (is (false? (ch/mode-c-suggested? :not-a-number))))

;; ---- (2) snapshot widening ---------------------------------------------

(deftest snapshot->instance-builds-the-mode-c-shape
  (let [inst (ch/snapshot->instance :auth/login
                                    {:state :authing :data {:user "ada"}})]
    (is (= :auth/login (:instance-id inst)))
    (is (= :auth/login (:machine-id inst)))
    (is (= :authing    (:state inst)))
    (is (= {:user "ada"} (:context inst)))
    (is (nil? (:age-ms inst)))
    (is (nil? (:spawn-source inst)))
    (is (nil? (:parent-machine inst)))))

(deftest snapshot->instance-nil-when-snapshot-is-nil
  (is (nil? (ch/snapshot->instance :auth/login nil))))

(deftest snapshot->instance-honours-rich-snapshot-slots
  (let [inst (ch/snapshot->instance :auth/login
                                    {:state          :authing
                                     :data           {:user "ada"}
                                     :age-ms         1500
                                     :spawn-source   [:auth/begin]
                                     :parent-machine :session/root})]
    (is (= 1500            (:age-ms inst)))
    (is (= [:auth/begin]   (:spawn-source inst)))
    (is (= :session/root   (:parent-machine inst)))))

(deftest snapshots->instances-returns-singleton-vector-when-present
  (let [v (ch/snapshots->instances :auth/login
                                   {:auth/login {:state :authing
                                                 :data  {:x 1}}})]
    (is (= 1 (count v)))
    (is (= :authing (-> v first :state)))))

(deftest snapshots->instances-empty-when-snapshot-missing
  (is (= [] (ch/snapshots->instances :auth/login {})))
  (is (= [] (ch/snapshots->instances :auth/login nil))))

;; ---- (3) normalise-instances -------------------------------------------

(deftest normalise-instances-fills-missing-slots
  (let [v (ch/normalise-instances [{:machine-id :m :state :a}
                                   {:instance-id :inst-1 :state :b}])]
    (is (= 2 (count v)))
    (is (every? :instance-id v)
        "instance-id falls back to machine-id when only the latter is present")
    (is (every? #(map? (:context %)) v))
    (is (= :a (-> v first  :state)))
    (is (= :b (-> v second :state)))
    (is (= :m (-> v first  :instance-id))
        "first instance got its instance-id from :machine-id")))

(deftest normalise-instances-defaults-context-to-empty-map
  (let [v (ch/normalise-instances [{:instance-id :a :state :idle}])]
    (is (= {} (-> v first :context)))))

(deftest normalise-instances-empty-tolerant
  (is (= [] (ch/normalise-instances nil)))
  (is (= [] (ch/normalise-instances []))))

;; ---- (4) cluster-instances ---------------------------------------------

(def ^:private ten-instances
  [{:instance-id :i1  :machine-id :req :state :idle    :context {:user "ada"}}
   {:instance-id :i2  :machine-id :req :state :idle    :context {:user "bob"}}
   {:instance-id :i3  :machine-id :req :state :idle    :context {:user "cat"}}
   {:instance-id :i4  :machine-id :req :state :authing :context {:user "ada"}}
   {:instance-id :i5  :machine-id :req :state :authing :context {:user "bob"}}
   {:instance-id :i6  :machine-id :req :state :sending :context {:user "ada"}}
   {:instance-id :i7  :machine-id :req :state :sending :context {:user "ada"}}
   {:instance-id :i8  :machine-id :req :state :sending :context {:user "bob"}}
   {:instance-id :i9  :machine-id :req :state :error   :context {:user "ada"}}
   {:instance-id :i10 :machine-id :req :state :ok      :context {:user "cat"}}])

(deftest cluster-by-state-groups-correctly
  (let [clusters (ch/cluster-instances ten-instances)]
    (is (= 5 (count clusters))
        ":idle :authing :sending :error :ok = 5 distinct states")
    (let [by-key (into {} (map (juxt :cluster-key :count) clusters))]
      (is (= 3 (get by-key :idle)))
      (is (= 2 (get by-key :authing)))
      (is (= 3 (get by-key :sending)))
      (is (= 1 (get by-key :error)))
      (is (= 1 (get by-key :ok))))))

(deftest cluster-by-state-orders-deterministically
  (let [clusters (ch/cluster-instances ten-instances)
        keys-out (mapv :cluster-key clusters)]
    (is (= keys-out (sort-by str keys-out))
        "cluster keys appear in lexicographic order for stable test output")))

(deftest cluster-by-state-default-when-no-options
  (let [clusters (ch/cluster-instances ten-instances)
        first-c  (first clusters)]
    (is (vector? (:instances first-c)))
    (is (every? map? (:instances first-c)))
    (is (= [] (:rate-samples first-c))
        "rate-samples is empty until attach-sparkline-samples runs")))

(deftest cluster-by-context-key-groups-on-the-sub-selector
  (let [clusters (ch/cluster-instances ten-instances
                                       {:cluster-by :context-key
                                        :context-key :user})
        by-key   (into {} (map (juxt :cluster-key :count) clusters))]
    (is (= 3 (count clusters))
        "three distinct :user values: ada, bob, cat")
    (is (= 5 (get by-key "ada")))
    (is (= 3 (get by-key "bob")))
    (is (= 2 (get by-key "cat")))))

(deftest cluster-by-parent-machine-groups-correctly
  (let [insts    [{:instance-id :a :state :s1 :parent-machine :p1}
                  {:instance-id :b :state :s1 :parent-machine :p1}
                  {:instance-id :c :state :s2 :parent-machine :p2}]
        clusters (ch/cluster-instances insts {:cluster-by :parent-machine})
        by-key   (into {} (map (juxt :cluster-key :count) clusters))]
    (is (= 2 (count clusters)))
    (is (= 2 (get by-key :p1)))
    (is (= 1 (get by-key :p2)))))

(deftest cluster-by-empty-returns-empty
  (is (= [] (ch/cluster-instances [])))
  (is (= [] (ch/cluster-instances nil))))

(deftest cluster-label-renders-keywords-and-nil
  (let [clusters (ch/cluster-instances [{:state :authing}
                                        {:state nil}])
        by-label (into #{} (map :cluster-label clusters))]
    (is (contains? by-label ":authing"))
    (is (contains? by-label "(none)"))))

;; ---- (5) sparkline-buckets ---------------------------------------------

(deftest sparkline-buckets-counts-transitions-into-buckets
  ;; Now = 10000; 2 buckets of 5000ms => oldest = 0.
  ;; bucket 0 = [0, 5000)   -> id 1 (time 1000)
  ;; bucket 1 = [5000,10000) -> ids 2, 3 (time 6500, 6800)
  (let [buf [{:id 1 :operation :rf.machine/transition
              :time 1000
              :tags {:machine-id :req :to :authing}}
             {:id 2 :operation :rf.machine/transition
              :time 6500
              :tags {:machine-id :req :to :authing}}
             {:id 3 :operation :rf.machine/transition
              :time 6800
              :tags {:machine-id :req :to :authing}}]
        samples (ch/sparkline-buckets
                  buf :req
                  (fn [ev] (= :authing (get-in ev [:tags :to])))
                  10000
                  {:bucket-count 2 :bucket-ms 5000})]
    (is (= [1 2] samples)
        "buckets are oldest-first; expected per-bucket counts")))

(deftest sparkline-buckets-filters-by-machine-id
  (let [buf [{:id 1 :operation :rf.machine/transition
              :time 1000 :tags {:machine-id :other :to :authing}}
             {:id 2 :operation :rf.machine/transition
              :time 1000 :tags {:machine-id :req   :to :authing}}]
        samples (ch/sparkline-buckets buf :req (constantly true) 5000
                                      {:bucket-count 1 :bucket-ms 5000})]
    (is (= [1] samples)
        ":other machine's transition is filtered out")))

(deftest sparkline-buckets-drops-non-transition-events
  (let [buf [{:id 1 :operation :event/dispatched :time 1000
              :tags {:machine-id :req :to :authing}}
             {:id 2 :operation :rf.machine/transition :time 1000
              :tags {:machine-id :req :to :authing}}]
        samples (ch/sparkline-buckets buf :req (constantly true) 5000
                                      {:bucket-count 1 :bucket-ms 5000})]
    (is (= [1] samples)
        ":event/dispatched is not a transition event")))

(deftest sparkline-buckets-empty-when-no-trace
  (is (= [0 0 0 0]
         (ch/sparkline-buckets nil :req (constantly true) 1000
                               {:bucket-count 4 :bucket-ms 250}))))

;; ---- (6) cluster-pred-for ----------------------------------------------

(deftest cluster-pred-for-state-matches-on-tags-to
  (let [pred (ch/cluster-pred-for :state :authing nil)]
    (is (true?  (pred {:tags {:to :authing}})))
    (is (true?  (pred {:tags {:to-state :authing}}))
        "tolerates the :to-state alias")
    (is (false? (pred {:tags {:to :idle}})))))

(deftest cluster-pred-for-parent-machine-matches-on-tags
  (let [pred (ch/cluster-pred-for :parent-machine :session/root nil)]
    (is (true?  (pred {:tags {:parent-machine :session/root}})))
    (is (false? (pred {:tags {:parent-machine :other}})))))

;; ---- (7) attach-sparkline-samples --------------------------------------

(deftest attach-sparkline-samples-fills-rate-samples-per-cluster
  ;; With now-ms=3000 + 2 buckets of 1500ms, oldest = 0.
  ;; bucket 0 = [0, 1500); bucket 1 = [1500, 3000).
  (let [buf       [{:id 1 :operation :rf.machine/transition
                    :time 1000 :tags {:machine-id :req :to :idle}}
                   {:id 2 :operation :rf.machine/transition
                    :time 2000 :tags {:machine-id :req :to :authing}}]
        clusters  (ch/cluster-instances ten-instances)
        attached  (ch/attach-sparkline-samples
                    clusters
                    {:trace-buffer buf
                     :machine-id   :req
                     :cluster-by   :state
                     :now-ms       3000
                     :opts {:bucket-count 2 :bucket-ms 1500}})
        by-key    (into {} (map (juxt :cluster-key :rate-samples) attached))]
    (is (= [1 0] (get by-key :idle))
        ":idle saw one transition at t=1000 (bucket 0)")
    (is (= [0 1] (get by-key :authing))
        ":authing saw one transition at t=2000 (bucket 1)")
    (is (= [0 0] (get by-key :sending))
        ":sending has no matching transitions in window")))

;; ---- (8) selection-set helpers -----------------------------------------

(deftest empty-selection-is-a-set
  (is (set? (ch/empty-selection)))
  (is (zero? (count (ch/empty-selection)))))

(deftest selection-add-is-idempotent
  (let [s1 (ch/selection-add (ch/empty-selection) :i1)
        s2 (ch/selection-add s1 :i1)]
    (is (= #{:i1} s1))
    (is (= s1 s2))))

(deftest selection-add-handles-nil-selection
  (is (= #{:i1} (ch/selection-add nil :i1))))

(deftest selection-add-tolerates-nil-id
  (is (= #{} (ch/selection-add nil nil)))
  (is (= #{:i1} (ch/selection-add #{:i1} nil))))

(deftest selection-remove-drops-id
  (is (= #{:i2} (ch/selection-remove #{:i1 :i2} :i1)))
  (is (= #{}    (ch/selection-remove #{:i1} :i1))))

(deftest selection-toggle-roundtrip
  (let [s0 (ch/empty-selection)
        s1 (ch/selection-toggle s0 :i1)
        s2 (ch/selection-toggle s1 :i1)
        s3 (ch/selection-toggle s2 :i2)
        s4 (ch/selection-toggle s3 :i1)]
    (is (= #{:i1}     s1))
    (is (= #{}        s2))
    (is (= #{:i2}     s3))
    (is (= #{:i1 :i2} s4))))

(deftest selection-clear-returns-empty
  (is (= #{} (ch/selection-clear #{:i1 :i2}))))

(deftest selection-contains?-and-count
  (is (true?  (ch/selection-contains? #{:i1} :i1)))
  (is (false? (ch/selection-contains? #{:i1} :i2)))
  (is (false? (ch/selection-contains? nil :i1)))
  (is (= 2 (ch/selection-count #{:i1 :i2})))
  (is (= 0 (ch/selection-count nil))))

;; ---- (9) compare-table -------------------------------------------------

(deftest compare-table-nil-when-fewer-than-two-selected
  (is (nil? (ch/compare-table ten-instances #{})))
  (is (nil? (ch/compare-table ten-instances #{:i1}))))

(deftest compare-table-renders-diff-flags-correctly
  ;; i1 :idle  user ada
  ;; i2 :idle  user bob   (state same; user differs)
  ;; i4 :authing user ada (state differs)
  (let [ct (ch/compare-table ten-instances #{:i1 :i2 :i4})]
    (is (some? ct))
    (is (= 3 (count (:instances ct))))
    (let [state-row (:state-row ct)]
      (is (true? (:diff? state-row))
          ":idle / :idle / :authing → differs"))
    (let [user-row (some #(when (= :user (-> % :column :key)) %) (:rows ct))]
      (is (some? user-row))
      (is (true? (:diff? user-row))
          "user values ada / bob / ada → differs"))))

(deftest compare-table-no-diff-when-all-instances-share-values
  (let [insts [{:instance-id :a :state :idle :context {:user "ada"}}
               {:instance-id :b :state :idle :context {:user "ada"}}]
        ct    (ch/compare-table insts #{:a :b})]
    (is (some? ct))
    (is (false? (:diff? (:state-row ct))))
    (let [user-row (some #(when (= :user (-> % :column :key)) %) (:rows ct))]
      (is (false? (:diff? user-row))))))

(deftest compare-table-columns-cover-the-union-of-context-keys
  (let [insts [{:instance-id :a :state :s :context {:k1 1}}
               {:instance-id :b :state :s :context {:k2 2}}]
        ct    (ch/compare-table insts #{:a :b})
        col-keys (set (map (comp :key :column) (:rows ct)))]
    (is (= #{:k1 :k2} col-keys))))

;; ---- (10) expanded set --------------------------------------------------

(deftest expanded-toggle-roundtrip
  (let [e0 (ch/empty-expanded)
        e1 (ch/expanded-toggle e0 :authing)
        e2 (ch/expanded-toggle e1 :authing)]
    (is (= #{:authing} e1))
    (is (= #{}         e2))))

(deftest expanded-contains?-tests
  (is (true?  (ch/expanded-contains? #{:authing} :authing)))
  (is (false? (ch/expanded-contains? #{:idle}    :authing)))
  (is (false? (ch/expanded-contains? nil         :authing))))

;; ---- (11) formatting ----------------------------------------------------

(deftest format-instance-id-keyword
  (is (= ":auth/login" (ch/format-instance-id :auth/login)))
  (is (= ""            (ch/format-instance-id nil))))

(deftest format-context-value
  (is (= "—"        (ch/format-context-value nil)))
  (is (= "\"ada\""  (ch/format-context-value "ada")))
  (is (= ":authing" (ch/format-context-value :authing))))
