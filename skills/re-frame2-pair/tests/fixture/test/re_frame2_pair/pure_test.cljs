(ns re-frame2-pair.pure-test
  (:require [cljs.test :refer [deftest is testing]]
            [re-frame2-pair.pure :as pure]))

(deftest app-frame-ids-excludes-tool-frames-retains-default
  (is (= [:rf/default :stories :rf-default] (pure/app-frame-ids [:rf/default :stories :rf-default :rf/xray :rf/ssr]))
      "order mirrors the input frame-ids; the un-namespaced :rf-default lookalike is a user frame")
  (is (= [] (pure/app-frame-ids [:rf/xray]))
      "only tool frames -> no app frames"))

(deftest resolve-operating-frame-tiers
  (testing "override -> selected -> sole app frame -> nil"
    (is (nil? (pure/resolve-operating-frame nil nil [])))
    (is (= :rf/default (pure/resolve-operating-frame nil nil [:rf/default]))
        "the sole app frame auto-resolves")
    (is (nil? (pure/resolve-operating-frame nil nil [:rf/default :stories]))
        "two app frames -> ambiguous (nil), never a silent :rf/default guess")
    (is (= :stories (pure/resolve-operating-frame nil :stories [:rf/default :stories]))
        "session pin resolves ambiguity")
    (is (= :rf/default (pure/resolve-operating-frame :rf/default :stories [:rf/default :stories]))
        "explicit override beats the session pin")))

(deftest ambiguous-frame-envelope-shape
  (let [env (pure/ambiguous-frame-envelope :dispatch [:rf/default :stories] nil
                                           {:event [:cart/apply-coupon "SPRING25"]})]
    (is (= {:ok? false :reason :ambiguous-frame :operation :dispatch
            :available-frames [:rf/default :stories] :selected-frame nil
            :event [:cart/apply-coupon "SPRING25"]}
           (dissoc env :hint))
        "extra context rides through beside the refusal")
    (is (re-find #"select-frame!" (:hint env)) "hint names the fix")))

(deftest nearest-ids-ranks-by-edit-distance
  (let [known [:cart/items :cart/total :checkout/step :user/name :current-user]]
    (is (= :current-user (first (pure/nearest-ids :current-userr known)))
        "a typo ranks its real target first")
    (is (= 3 (count (pure/nearest-ids :x known))) "capped at 3 by default")
    (is (= 1 (count (pure/nearest-ids :x known 1))) "n bounds the result")))

(deftest validate-against-known-hit-and-miss
  (testing "a registered id validates"
    (is (= {:ok? true :kind :sub :id :current-user}
           (pure/validate-against-known :sub :current-user [:current-user :cart/total]))))
  (testing "an unknown id fails with :unknown-id + :nearest (no silent swallow)"
    (let [r (pure/validate-against-known :sub :current-userr [:current-user :cart/total])]
      (is (= {:ok? false :reason :unknown-id :kind :sub :id :current-userr
              :nearest [:current-user :cart/total] :known-count 2}
             (dissoc r :hint)))
      (is (re-find #"did you mean" (:hint r)))))
  (testing "an empty registrar reports :known-count 0 with a distinct hint"
    (let [r (pure/validate-against-known :sub :anything [])]
      (is (= {:ok? false :reason :unknown-id :kind :sub :id :anything :nearest [] :known-count 0}
             (dissoc r :hint)))
      (is (re-find #"nothing is registered" (:hint r))))))

(deftest sort-ids-orders-mixed-type-paths-and-keeps-keyword-order
  (let [kws [:zeta :b/x :a :a.b/c :a/d :counter/inc :counter]]
    (is (= (vec (sort kws)) (pure/sort-ids kws))
        "keyword ids keep compare's order"))
  (is (= [[:todos] [:todos 0] [:todos 1] [:todos "label"] [:todos :filter] [:user :name]]
         (pure/sort-ids [[:user :name] [:todos :filter] [:todos 1] [:todos] [:todos "label"] [:todos 0]]))
      "app-db paths order length-first, then segment by segment, segments of different types by type"))

(deftest epoch-elapsed-ms-spans-run-start-to-last-run-end
  (let [run (fn [op t] {:op-type :rf.event :operation op :time t})]
    (is (nil? (pure/epoch-elapsed-ms {:trace-events [(run :rf.event/run-end 1000)]}))
        "no run-start -> nil")
    (is (nil? (pure/epoch-elapsed-ms {:trace-events [(run :rf.event/run-start 1000)]}))
        "no run-end -> nil")
    (testing "spans FIRST run-start to LAST run-end across nested dispatches"
      (is (= 300 (pure/epoch-elapsed-ms
                  {:trace-events [(run :rf.event/run-start 1000) (run :rf.event/run-end 1050)
                                  (run :rf.event/run-start 1060) (run :rf.event/run-end 1300)]}))))))

(deftest parse-timing-pred-number-and-string-forms
  (doseq [[query times expected] [[100 [100 50] [true false]]
                                [">100" [101 100] [true false]]
                                ["<=50" [50 51] [true false]]]]
    (is (= expected (mapv (pure/parse-timing-pred query) times))))
  (is (nil? (pure/parse-timing-pred "not-a-number"))))

(deftest epoch-matches-filters-on-each-axis
  (let [slow {:event-id :cart/add
              :trace-events [{:op-type :rf.event :operation :rf.event/run-start :time 1000}
                             {:op-type :rf.event :operation :rf.event/run-end   :time 1150}]}]
    (is (pure/epoch-matches? {:event-id :cart/add} slow))
    (is (not (pure/epoch-matches? {:event-id :cart/remove} slow)))
    (is (pure/epoch-matches? {:effects :http} {:effects [{:fx-id :http} {:fx-id :db}]}))
    (is (pure/epoch-matches? {:event-id-prefix :cart} slow) "prefix via str on both sides")
    (is (pure/epoch-matches? {:timing-ms 100} slow) "slow epoch clears >=100")
    (is (not (pure/epoch-matches? {:timing-ms ">100"}
                                  {:event-id :x :trace-events []}))
        "an epoch with no timing bracket does not clear a numeric threshold")))

(defn- dispatched-epoch
  "An assembled epoch record carrying the `:rf.event/dispatched` origin trace
   the listener classifies on."
  [frame epoch-id origin]
  {:frame frame :epoch-id epoch-id
   :trace-events [{:operation :rf.event/dispatched
                   :tags {:rf.event/origin origin}}]})

(deftest origin-matches-reads-the-dispatched-trace
  (let [pair (:trace-events (dispatched-epoch :rf/default "ep-1" :pair))
        app  (:trace-events (dispatched-epoch :rf/default "ep-1" :app))]
    (is (true?  (pure/origin-matches? :pair pair)))
    (is (false? (pure/origin-matches? :pair app)) "an :app cascade is not pair-origin")
    (is (false? (pure/origin-matches? :pair []))  "no dispatched trace -> false")
    (is (false? (pure/origin-matches? :pair
                  [{:operation :rf.event/run-start :tags {:rf.event/origin :pair}}]))
        "origin rides on :rf.event/dispatched, not an arbitrary trace op")))

(deftest epoch-matches-origin-axis-routes-through-origin-matches
  (let [rec {:frame :rf/default
             :trace-events (:trace-events (dispatched-epoch :rf/default "ep-1" :pair))}]
    (is (pure/epoch-matches? {:origin :pair} rec))
    (is (not (pure/epoch-matches? {:origin :app} rec)))))

(deftest attribute-pair-epoch-attributes-queued-and-frame-qualifies
  (testing "a queued pair epoch is attributed when it settles at the listener"
    (let [store (pure/attribute-pair-epoch {} (dispatched-epoch :A "ep-1" :pair)
                                           #{:A} #{"ep-1"})]
      (is (= {:A #{"ep-1"}} store))
      (is (= "ep-1" (:epoch-id (pure/pick-pair-epoch (get store :A)
                                                     [(dispatched-epoch :A "ep-1" :pair)])))
          "last-pair-epoch surfaces the queued record")))
  (testing "two frames sharing an epoch-id stay isolated"
    (let [store (-> {}
                    (pure/attribute-pair-epoch (dispatched-epoch :A "ep-7" :pair) #{:A :B} #{"ep-7"})
                    (pure/attribute-pair-epoch (dispatched-epoch :B "ep-7" :app) #{:A :B} #{"ep-7"}))]
      (is (= {:A #{"ep-7"}} store)))))

(deftest attribute-pair-epoch-is-bounded-and-cleans-up
  (testing "bounded to the live ring — rolled-over ids are pruned"
    (let [depth 2
          store (reduce (fn [s i]
                          (let [ring-ids (into #{} (map #(str "ep-" %))
                                              (range (max 0 (- i (dec depth))) (inc i)))]
                            (pure/attribute-pair-epoch s (dispatched-epoch :A (str "ep-" i) :pair)
                                                       #{:A} ring-ids)))
                        {}
                        (range 4))]
      (is (= depth (count (get store :A)))
          "the attribution set never exceeds the live ring depth across repeated ring rollovers")))
  (testing "destroyed-frame state is removed"
    (let [store (-> {}
                    (pure/attribute-pair-epoch (dispatched-epoch :A "a-1" :pair) #{:A :Z} #{"a-1"})
                    (pure/attribute-pair-epoch (dispatched-epoch :Z "z-1" :pair) #{:A :Z} #{"z-1"}))]
      (is (= #{:A :Z} (set (keys store))))
      (let [store' (pure/attribute-pair-epoch store (dispatched-epoch :A "a-2" :pair)
                                              #{:A} #{"a-1" "a-2"})]
        (is (= #{:A} (set (keys store'))) "unregistered frame :Z is pruned"))))
  (testing "a frame whose set empties is dropped, never left as an empty entry"
    (let [store (-> {}
                    (pure/attribute-pair-epoch (dispatched-epoch :A "a-1" :pair) #{:A} #{"a-1"})
                    (pure/attribute-pair-epoch (dispatched-epoch :A "a-2" :app) #{:A} #{"a-2"}))]
      (is (not (contains? store :A)) "no empty #{} entry retained"))))

(deftest pick-pair-epoch-newest-first-and-survives-elision
  (let [ring [(dispatched-epoch :A "ep-1" :pair)
              (dispatched-epoch :A "ep-2" :app)
              (dispatched-epoch :A "ep-3" :pair)]]
    (is (= "ep-3" (:epoch-id (pure/pick-pair-epoch #{"ep-1" "ep-3"} ring)))
        "walks the ring backward — newest attributed epoch wins")
    (is (nil? (pure/pick-pair-epoch #{} ring)) "no attributed ids -> nil"))
  (testing "survives trace elision — attribution is remembered, not re-parsed"
    (let [aged-ring [{:frame :A :epoch-id "ep-1"}          ; trace elided
                     (dispatched-epoch :A "ep-2" :app)]]
      (is (= "ep-1" (:epoch-id (pure/pick-pair-epoch #{"ep-1"} aged-ring)))))))

(deftest db-diff-summary-depth-1-delta
  (is (= {:added-paths [[:b]] :removed-paths [] :changed-paths [[:a]]}
         (pure/db-diff-summary {:a 1} {:a 2 :b 3})))
  (is (= {:added-paths [] :removed-paths [] :changed-paths [[]]}
         (pure/db-diff-summary 1 2) )
      "non-map, non-equal -> the whole-db changed marker [[]]"))

(deftest db-diff-summary-orders-mixed-type-top-level-keys
  (let [kws [:zeta :b/x :a :a.b/c :a/d :counter]]
    (is (= (mapv vector (sort kws))
           (:added-paths (pure/db-diff-summary {} (zipmap kws (repeat 1)))))
        "keyword keys keep compare's order"))
  (let [before {:a 1 "b" 2 [:v] 3 :gone 0 "gone" 0}
        after  {:a 9 "b" 2 [:v] 4 :new 1 "new" 1 7 1}
        diff   {:added-paths   [[7] ["new"] [:new]]
                :removed-paths [["gone"] [:gone]]
                :changed-paths [[:a] [[:v]]]}]
    (is (= diff (pure/db-diff-summary before after))
        "keys of different types order by type in every bucket")
    (is (= diff (:db-diff (pure/cascade-summary {:epoch-id 1 :db-before before :db-after after} false))))
    (is (= diff (get-in (pure/restore-cascade-projection before {:db-after after} :rf/default 1 false nil)
                        [:cascade-summary :db-diff])))))

(deftest outcome-tier-three-tier-projection
  (is (= [:ok :blocked :blocked :error :ok]
         (map pure/outcome-tier [:ok :halted-depth :halted-destroy :halted-handler-exception :some-unknown-cause]))
      "an unknown cause defaults to :ok"))

(def ^:private thrown-action-epoch
  {:epoch-id 11 :frame :machine-epochs :outcome :ok
   :db-before {:baseline 5} :db-after {:baseline 5} :effects []
   :trace-events [{:operation :rf.machine/action-ran :tags {:machine-id :fuse/box}}
                  {:operation :rf.error/machine-action-exception
                   :tags {:machine-id :fuse/box :action-id :blow-fuse
                          :exception-message "fuse blew"}}]})

(deftest cascade-summary-contained-throw-forces-error
  (let [summary (pure/cascade-summary thrown-action-epoch false)]
    (is (= :error (:outcome summary)) "a contained throw forces :outcome :error, not :ok")
    (is (= [{:operation :rf.error/machine-action-exception :machine-id :fuse/box
             :action-id :blow-fuse :message "fuse blew"}]
           (:errors summary)))))

(deftest cascade-summary-benign-no-op-stays-ok
  (let [summary (pure/cascade-summary
                 {:epoch-id 7 :frame :f :outcome :ok
                  :db-before {:x 1} :db-after {:x 1} :effects []
                  :trace-events [{:operation :rf.event/run-end}]}
                 false)]
    (is (= :ok (:outcome summary)))
    (is (nil? (:errors summary)))))

(deftest consequence-from-summary-throw-is-not-a-no-op
  (let [summary (pure/cascade-summary thrown-action-epoch false)
        conseq  (pure/consequence-from-summary {:ok? true :cascade-summary summary})]
    (is (false? (:no-op? conseq)) "a thrown-action epoch is NOT a no-op")
    (is (= :error (:outcome conseq))))
  (testing "a genuine quiescent cascade is a visible no-op"
    (let [summary (pure/cascade-summary
                   {:epoch-id 1 :frame :f :outcome :ok
                    :db-before {:x 1} :db-after {:x 1} :effects [] :trace-events []}
                   false)
          conseq  (pure/consequence-from-summary {:ok? true :cascade-summary summary})]
      (is (true? (:no-op? conseq)))
      (is (false? (:db-changed? conseq))))))

(deftest cascade-summary-event-vector-fail-closed-and-opt-in
  (let [epoch {:epoch-id 42 :event-id :auth/login
               :trigger-event [:auth/login {:username "ada" :password "hunter2"}]
               :frame :rf/default :rf.epoch/sensitive? true
               :db-before {} :db-after {:auth {:user "ada"}} :effects []}]
    (testing "gate OFF (allow-raw-state? false): head retained, args redacted"
      (let [summary (pure/cascade-summary epoch false)]
        (is (= [:auth/login :rf/redacted] (:event-vector summary)))
        (is (not (clojure.string/includes? (pr-str summary) "hunter2"))
            "the password literal appears nowhere")
        (is (true? (:sensitive? summary)))))
    (testing "gate ON (allow-raw-state? true): raw vector rides through"
      (is (= [:auth/login {:username "ada" :password "hunter2"}]
             (:event-vector (pure/cascade-summary epoch true)))))
    (testing "fail-close fires even for a NON-declared-sensitive epoch"
      (let [summary (pure/cascade-summary
                     {:epoch-id 99 :event-id :login :trigger-event [:login "topsecret"]
                      :frame :rf/default :db-before {} :db-after {:auth {:ok true}} :effects []}
                     false)]
        (is (= [:login :rf/redacted] (:event-vector summary)))
        (is (not (clojure.string/includes? (pr-str summary) "topsecret")))
        (is (nil? (:sensitive? summary)) "no :sensitive? annotation, yet args still redact")))))

(deftest redact-sensitive-event-vector-edge-shapes
  (is (= :rf/redacted (pure/redact-sensitive-event-vector "scalar" false false))
      "a degenerate non-vector redacts wholesale"))

(deftest restore-cascade-projection-shape
  (let [target {:epoch-id 5 :event-id :auth/login
                :trigger-event [:auth/login {:password "hunter2"}]
                :frame :rf/default :rf.epoch/sensitive? true
                :db-after {:auth {:user "ada"}}
                :effects [{:fx-id :http :coord "app:1:1"} {:fx-id :db}]}
        {:keys [cascade-summary unreplayable-effects]}
        (pure/restore-cascade-projection {} target :rf/default 5 false nil)]
    (is (true? (:restore? cascade-summary)))
    (is (= [:auth/login :rf/redacted] (:event-vector cascade-summary))
        "the restored trigger-event fails closed under the OFF gate")
    (is (= [{:fx-id :http :coord "app:1:1"} {:fx-id :db}] unreplayable-effects)
        "every fx the original cascade fired is unreplayable")))

(deftest resolve-snapshot-frames-scopes
  (is (= [:rf/default :stories]
         (pure/resolve-snapshot-frames :app [:rf/default :stories :rf/xray] [:rf/default :stories]))
      ":app -> app frames (tool frames excluded)")
  (is (= [:rf/default :stories :rf/xray]
         (pure/resolve-snapshot-frames :all [:rf/default :stories :rf/xray] [:rf/default :stories]))
      ":all -> every registered frame")
  (is (= [:stories]
         (pure/resolve-snapshot-frames [:stories] [:rf/default :stories :rf/xray] [:rf/default :stories]))
      "an explicit vector is honoured verbatim (a tool frame named explicitly opts in)")
  (is (= [:app-db :sub-cache :machines :epochs :traces] pure/all-snapshot-slices)))

(deftest orient-counts-app-schemas
  (is (some #{:app-schema} pure/orient-registrar-kinds)))

(deftest top-keys-sorted-or-nil
  (is (= [:cart :route :user] (pure/top-keys {:user 2 :cart 1 :route 3})))
  (is (nil? (pure/top-keys [1 2 3])) "a non-map app-db value has no top-keys"))

(deftest assemble-orient-summary-shape
  (let [registry {:basis :process
                  :counts {:event 2 :sub 2 :fx 1}
                  :events [:cart/add :cart/checkout]
                  :subs [:cart/total :current-user]
                  :fx [:http]}]
    (is (= {:ok? true
            :liveness {:debug-enabled? true :frame-count 2 :app-frame-count 1
                       :ambiguous-frame? false :runtime-instance-id "abc"}
            :frames {:all [:rf/default :rf/xray] :app [:rf/default] :operating :rf/default}
            :app-db-top-keys {:rf/default [:cart :route :user]}
            :registry registry
            :machines [:checkout]}
           (pure/assemble-orient
            {:debug-enabled? true
             :all-frames [:rf/default :rf/xray]
             :operating-frame :rf/default
             :ambiguous-frame? false
             :runtime-instance-id "abc"
             :app-fids [:rf/default]
             :app-db-top-keys {:rf/default [:cart :route :user]}
             :registry registry
             :machines [:checkout]})))))
