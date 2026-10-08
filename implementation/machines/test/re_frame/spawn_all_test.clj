(ns re-frame.spawn-all-test
  "Spec 005 §Spawn-and-join via `:spawn-all`: the closed `:all` / `:any` join
  grammar, sibling cancellation on resolution, the unsatisfiable-join warning,
  completion-carrier ownership, and registration-time validation."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private events-of rf.machines.test-support/events-of)
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:joining]]))

(def ^:private child
  "Completes on `:go` (plain final) or `:fail` (`:error?` final); its result is
  the id its `:start` recorded."
  {:initial :running
   :data    {:id nil}
   :actions {:record-id (fn [{data :data ev :event}] {:data (assoc data :id (second ev))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done}
                            :fail   {:target :failed}}}
             :done    {:final? true :output-key :id}
             :failed  {:final? true :error? true :output-key :id}}})

(defn- over-on
  "`:joining` transitions to `:over` on each of `event-ids`, recording the event."
  [& event-ids]
  (zipmap event-ids (repeat {:target :over :action :record})))

(defn- start-join!
  "Register and start `parent-id`, whose `:joining` state spawn-alls one `child`
  per id in `child-ids` under `spawn-all` and takes the transitions `on`.
  Returns the spawned ids by child id."
  [parent-id child-ids spawn-all on]
  (rf/reg-machine :spawn-all-test/child child)
  (rf/reg-machine parent-id
    {:initial :idle
     :actions {:record (fn [{data :data ev :event}] {:data (assoc data :join-event ev)})}
     :states  {:idle    {:on {:start :joining}}
               :joining {:spawn-all (assoc spawn-all :children
                                           (mapv (fn [id] {:id id :machine-id :spawn-all-test/child
                                                           :start [:set-id id]})
                                                 child-ids))
                         :on        on}
               :over    {}}})
  (rf/dispatch-sync [parent-id [:start]])
  (:children (join-state parent-id)))

(defn- join-event [parent-id]
  (get-in (snapshot parent-id) [:data :join-event]))

(deftest join-all-fires-on-all-complete
  (testing "the default :all join resolves on the LAST completion, carrying the decisive child id and its :output-key result"
    (let [ids (start-join! :sup/all [:a :b :c]
                           {:on-all-complete [:hydrate/done] :on-any-failed [:hydrate/failed]}
                           (over-on :hydrate/done :hydrate/failed))]
      (doseq [k [:a :b :c]] (rf/dispatch-sync [(k ids) [:go]]))
      (is (= [:hydrate/done :c :c] (join-event :sup/all))))))

(deftest join-all-with-one-failure-fires-on-any-failed-and-cancels
  (testing "one failure fires :on-any-failed, destroys the survivors, and raises no unsatisfiable warning"
    (let [ids (start-join! :sup/fail [:a :b :c]
                           {:on-all-complete [:hydrate/done] :on-any-failed [:hydrate/failed]}
                           (over-on :hydrate/done :hydrate/failed))]
      (rf/dispatch-sync [(:a ids) [:fail]])
      (is (= [:hydrate/failed :a :a] (join-event :sup/fail)))
      (is (= [nil nil nil] (mapv snapshot (vals ids))))
      (is (nil? (join-state :sup/fail)) "leaving the state clears the join slot")
      (is (= [:a] (mapv (comp :reason :tags) (events-of :rf.machine.spawn-all/any-failed)))
          "the any-failed trace carries the decisive child's :output-key result")
      (is (empty? (events-of :rf.warning/spawn-all-join-unsatisfiable))))))

(deftest join-any-fires-on-first-child
  (testing ":join :any fires :on-some-complete on the first completion and destroys the siblings"
    (let [ids (start-join! :sup/any [:a :b :c]
                           {:join :any :on-some-complete [:race/won]}
                           (over-on :race/won))]
      (rf/dispatch-sync [(:b ids) [:go]])
      (is (= [:race/won :b :b] (join-event :sup/any)))
      (is (= [nil nil] (mapv snapshot [(:a ids) (:c ids)]))))))

(deftest all-join-unsatisfiable-after-failure-warns
  (testing "an :all join with no :on-any-failed warns once, on the fold that makes it unsatisfiable, and stays hung"
    (let [ids   (start-join! :sup/unsat [:a :b :c]
                             {:on-all-complete [:phase/done]}
                             (over-on :phase/done))
          warns #(events-of :rf.warning/spawn-all-join-unsatisfiable)]
      (is (= [0 1 1] (mapv (fn [[k ev]] (rf/dispatch-sync [(k ids) [ev]]) (count (warns)))
                           [[:a :go] [:b :fail] [:c :fail]])))
      (is (= [{:actor-id :sup/unsat :join :all}] (mapv #(select-keys (:tags %) [:actor-id :join]) (warns))))
      (is (= :joining (:state (snapshot :sup/unsat)))))))

(deftest registration-time-rejects-bad-shape
  (doseq [[label error-re state-node]
          [[":spawn-all with no :id on a child"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children        [{:machine-id :foo}]
                         :on-all-complete [:done]}}]
           [":spawn-all with duplicate child ids"
            #"machine-spawn-all-duplicate-id"
            {:spawn-all {:children        [{:id :x :machine-id :foo}
                                           {:id :x :machine-id :bar}]
                         :on-all-complete [:done]}}]
           [":spawn + :spawn-all on same state"
            #"machine-spawn-all-with-spawn"
            {:spawn     {:machine-id :foo}
             :spawn-all {:children        [{:id :x :machine-id :bar}]
                         :on-all-complete [:done]}}]
           [":spawn-all with :join :all but no :on-all-complete"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children [{:id :x :machine-id :foo}]}}]
           [":spawn-all with :join :any but no :on-some-complete"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children [{:id :x :machine-id :foo}]
                         :join     :any}}]
           [":spawn-all with a :join outside the closed :all / :any enum"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children         [{:id :x :machine-id :foo}]
                         :join             {:n 2}
                         :on-some-complete [:some]}}]
           [":spawn-all with an unknown bare key"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children            [{:id :x :machine-id :foo}]
                         :join                :any
                         :cancel-on-decision? false
                         :on-some-complete    [:some]}}]
           [":spawn-all child with BOTH :machine-id AND :definition (XOR)"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children        [{:id         :x
                                            :machine-id :foo
                                            :definition {:initial :i
                                                         :states  {:i {}}}}]
                         :on-all-complete [:done]}}]
           ;; the natural spelling of a runtime-sized fan-out; refused by the
           ;; grammar, never by a host exception
           [":spawn-all whose :children is a fn"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children        (fn [_] [{:id :x :machine-id :foo}])
                         :on-all-complete [:done]}}]
           [":spawn declaring NEITHER :machine-id nor :definition"
            #"machine-spawn-bad-shape"
            {:spawn {:start [:begin]}}]]]
    (testing label
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            error-re
            (rf/reg-machine (keyword "bad" (str (gensym "spawn-all")))
                            {:initial :s
                             :states  {:s state-node}}))))))

(deftest late-completion-of-known-child-is-stale-record-frozen
  (testing "an exact-current carrier arriving after the join resolved is traced :stale late-completion and folds nothing"
    ;; No :on for :race/won, so the resolved join slot survives.
    (let [ids (start-join! :sup/late [:a :b] {:join :any :on-some-complete [:race/won]} {})
          _   (rf/dispatch-sync [(:a ids) [:go]])
          j   (join-state :sup/late)]
      (rf/dispatch-sync [:sup/late [:rf.machine.spawn/done [:joining]
                                    {:result     :a
                                     :error?     false
                                     :parent-id  :sup/late
                                     :invoke-id  [:joining]
                                     :child-id   :a
                                     :spawned-id (:a ids)
                                     :attempt    (:rf/attempt j)}]])
      (is (= j (join-state :sup/late)) "the record stays frozen")
      (is (= [{:child-id              :a
               :rf.reply/status       :stale
               :rf.reply/work-status  :suppressed
               :rf.reply/stale-reason :rf.machine.spawn-all/join-resolved
               :rf.reply/work-id      [:rf.work/machine (:a ids) [:joining] 1]}]
             (mapv #(select-keys (:tags %) [:child-id :rf.reply/status :rf.reply/work-status
                                            :rf.reply/stale-reason :rf.reply/work-id])
                   (events-of :rf.machine.spawn-all/late-completion)))))))

(deftest unknown-child-id-is-rejected-and-leaves-the-join-untouched
  (testing "a carrier naming a child-id outside the parent's spawned set raises bad-child-id and mutates nothing"
    (start-join! :sup/forge [:a :b]
                 {:on-all-complete [:hydrate/done] :on-any-failed [:hydrate/failed]}
                 (over-on :hydrate/done :hydrate/failed))
    (let [pre (join-state :sup/forge)]
      (rf/dispatch-sync [:sup/forge [:rf.machine.spawn/done [:joining]
                                     {:child-id :totally-fake-id :result nil :error? false}]])
      (is (= [{:actor-id  :sup/forge
               :invoke-id [:joining]
               :child-id  :totally-fake-id
               :children  #{:a :b}
               :kind      :done
               :recovery  :event-dropped}]
             (mapv #(assoc (select-keys (:tags %) [:actor-id :invoke-id :child-id :children :kind])
                           :recovery (:recovery %))
                   (events-of :rf.error/machine-spawn-all-bad-child-id))))
      (is (= pre (join-state :sup/forge))))))
