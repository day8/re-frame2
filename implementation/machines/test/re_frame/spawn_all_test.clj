(ns re-frame.spawn-all-test
  "Spec 005 §Spawn-and-join via `:spawn-all`: the closed `:all` / `:any` join
  grammar, sibling cancellation on resolution, an explicit destroy counting as
  a failure while the join is live, the unsatisfiable-join warning, completion-carrier ownership, and
  registration-time validation."
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
  `opts` adds `:states` beside `:joining`, an `:exit` on it, and with `:fixed?`
  spawns each child at the fixed address `:<parent-name>/<child-id>`.
  Returns the spawned ids by child id."
  ([parent-id child-ids spawn-all on]
   (start-join! parent-id child-ids spawn-all on {}))
  ([parent-id child-ids spawn-all on {:keys [states exit fixed?]}]
   (rf/reg-machine :spawn-all-test/child child)
   (rf/reg-machine parent-id
     {:initial :idle
      :actions {:record (fn [{data :data ev :event}] {:data (assoc data :join-event ev)})}
      :states  (merge {:idle    {:on {:start :joining}}
                       :joining (cond-> {:spawn-all (assoc spawn-all :children
                                                           (mapv (fn [id]
                                                                   (cond-> {:id id :machine-id :spawn-all-test/child
                                                                            :start [:set-id id]}
                                                                     fixed? (assoc :fixed-actor-id
                                                                                   (keyword (name parent-id) (name id)))))
                                                                 child-ids))
                                         :on        on}
                                  exit (assoc :exit exit))
                       :over    {}}
                      states)})
   (rf/dispatch-sync [parent-id [:start]])
   (:children (join-state parent-id))))

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

(def ^:private cancellation-result
  "The `<result>` a resolution carries for an explicitly destroyed child."
  {:status :cancelled :rf.reply/cancel-reason :explicit})

(deftest destroying-a-join-child-fires-on-any-failed
  (rf/reg-event :sup/fire (fn [_ [_ id]] {:fx [[:rf.machine/destroy id]]}))
  (doseq [[parent spawn-all]
          [[:sup/destroy-all {:on-all-complete [:hydrate/done] :on-any-failed [:hydrate/failed]}]
           [:sup/destroy-any {:join :any :on-some-complete [:hydrate/done]
                              :on-any-failed [:hydrate/failed]}]]]
    (testing (str parent ": an explicit destroy of one child counts as it failing, so
                  :on-any-failed fires at once, naming the cancellation, and the
                  survivors are cancelled")
      (rf.machines.test-support/reset-captured!)
      (let [ids (start-join! parent [:a :b :c] spawn-all (over-on :hydrate/done :hydrate/failed))
            any-failed #(events-of :rf.machine.spawn-all/any-failed)]
        (rf/dispatch-sync [:sup/fire (:a ids)])
        (is (= [:hydrate/failed :a cancellation-result] (join-event parent)))
        (is (= [nil nil nil] (mapv snapshot (vals ids))))
        (is (= [{:failed-id :a :reason cancellation-result :failed #{} :cancelled #{:a}}]
               (mapv #(select-keys (:tags %) [:failed-id :reason :failed :cancelled]) (any-failed)))
            "the destroyed child stays in :cancelled alone, and the trace fires once")
        (is (not-any? #(contains? (:tags %) :rf.reply/work-id) (any-failed))
            "the child's terminal is its own :cancelled destroyed row, not a second one here")
        (is (= #{[:b :on-any-failed] [:c :on-any-failed]}
               (set (map (comp (juxt :child-id :join-event) :tags)
                         (events-of :rf.machine.spawn/cancelled-on-join-resolution)))))
        (is (empty? (events-of :rf.warning/spawn-all-join-unsatisfiable)))))))

(deftest runtime-cancellations-never-fire-on-any-failed
  (testing "the cancel-on-decision cascade: a success resolution's survivors are
            cancelled without firing :on-any-failed"
    (let [ids (start-join! :sup/won [:a :b :c]
                           {:join :any :on-some-complete [:race/won] :on-any-failed [:race/failed]}
                           (over-on :race/won :race/failed))]
      (rf/dispatch-sync [(:b ids) [:go]])
      (is (= [:race/won :b :b] (join-event :sup/won)))
      (is (= #{:a :c} (set (map (comp :child-id :tags)
                                (events-of :rf.machine.spawn/cancelled-on-join-resolution)))))
      (is (empty? (events-of :rf.machine.spawn-all/any-failed)))))
  (testing "the exit cascade: leaving the :spawn-all state cancels every live child
            without firing :on-any-failed"
    (rf.machines.test-support/reset-captured!)
    (let [ids (start-join! :sup/left [:a :b]
                           {:on-all-complete [:phase/done] :on-any-failed [:phase/failed]}
                           (assoc (over-on :phase/done :phase/failed) :leave {:target :over}))]
      (rf/dispatch-sync [:sup/left [:leave]])
      (is (= [:over nil] [(:state (snapshot :sup/left)) (join-event :sup/left)]))
      (is (= [nil nil] (mapv snapshot (vals ids))))
      (is (empty? (events-of :rf.machine.spawn-all/any-failed))))))

(deftest a-destroy-while-the-parent-leaves-the-join-ends-it
  (let [kill      (fn [{[_ id] :event}] {:fx [[:rf.machine/destroy id]]})
        spawn-all {:on-all-complete [:phase/done] :on-any-failed [:phase/failed]}]
    (testing "a destroy in the leaving transition's action: the parent rests in
              its leaving target and receives no :on-any-failed"
      (let [ids (start-join! :sup/leave-kill [:a :b] spawn-all
                             {:cancel {:target :cancelled :action kill}}
                             {:states {:cancelled {:on (over-on :phase/failed)}}})]
        (rf/dispatch-sync [:sup/leave-kill [:cancel (:a ids)]])
        (is (= [:cancelled nil] [(:state (snapshot :sup/leave-kill)) (join-event :sup/leave-kill)]))
        (is (= [nil nil] (mapv snapshot (vals ids))))
        (is (empty? (events-of :rf.machine.spawn-all/any-failed)))
        (is (empty? (events-of :rf.warning/spawn-all-join-unsatisfiable)))))
    (testing "control: the same destroy with the parent staying in the join fires
              :on-any-failed"
      (rf.machines.test-support/reset-captured!)
      (let [ids (start-join! :sup/stay-kill [:a :b] spawn-all
                             (assoc (over-on :phase/failed) :cancel {:action kill}))]
        (rf/dispatch-sync [:sup/stay-kill [:cancel (:a ids)]])
        (is (= [:over [:phase/failed :a cancellation-result]]
               [(:state (snapshot :sup/stay-kill)) (join-event :sup/stay-kill)]))
        (is (= 1 (count (events-of :rf.machine.spawn-all/any-failed))))))
    (testing "a destroy in the :spawn-all state's :exit while the parent is torn
              down: no :on-any-failed re-creates the destroyed singleton"
      (rf.machines.test-support/reset-captured!)
      (rf/reg-event :sup/destroy (fn [_ [_ id]] {:fx [[:rf.machine/destroy id]]}))
      (start-join! :sup/torn [:a :b] spawn-all (over-on :phase/failed)
                   {:fixed? true
                    :exit   (fn [_] {:fx [[:rf.machine/destroy :torn/a]]})})
      (rf/dispatch-sync [:sup/destroy :sup/torn])
      (is (= [nil nil nil] (mapv snapshot [:sup/torn :torn/a :torn/b])))
      (is (empty? (events-of :rf.machine.spawn-all/any-failed))))))

(deftest a-destroy-in-a-re-entering-transition-ends-the-old-attempt
  (let [kill      (fn [{[_ id] :event}] {:fx [[:rf.machine/destroy id]]})
        spawn-all {:on-all-complete [:phase/done] :on-any-failed [:phase/failed]}
        on        (over-on :phase/done :phase/failed)]
    (doseq [[addressing fixed?] [["generated" false] ["fixed" true]]
            :let [parent (keyword "sup" (str "re-enter-" addressing))]]
      (testing (str addressing " addresses: a destroy in a :reenter? transition
                    back into the :spawn-all state ends the attempt it leaves, and
                    the attempt it enters neither receives that attempt's
                    :on-any-failed nor inherits its cancellation")
        (rf.machines.test-support/reset-captured!)
        (let [ids (start-join! parent [:a :b] spawn-all
                               (assoc on :cancel {:target :joining :reenter? true :action kill})
                               {:fixed? fixed?})]
          (rf/dispatch-sync [parent [:cancel (:a ids)]])
          (is (= [:joining nil] [(:state (snapshot parent)) (join-event parent)]))
          (is (empty? (events-of :rf.machine.spawn-all/any-failed)))
          (is (empty? (events-of :rf.machine.spawn/cancelled-on-join-resolution)))
          (let [fresh (:children (join-state parent))]
            (is (= #{} (:cancelled (join-state parent))))
            (is (every? some? (map snapshot (vals fresh))))
            (doseq [id (keep #(get fresh %) [:a :b])] (rf/dispatch-sync [id [:go]]))
            (is (= [:over [:phase/done :b :b]]
                   [(:state (snapshot parent)) (join-event parent)])
                "the fresh attempt resolves on its own children's completions"))))
      (testing (str addressing " addresses, control: the same destroy leaving for
                    another state rests there with no :on-any-failed")
        (rf.machines.test-support/reset-captured!)
        (let [leaver (keyword "sup" (str "leave-" addressing))
              ids    (start-join! leaver [:a :b] spawn-all
                                  (assoc on :cancel {:target :cancelled :action kill})
                                  {:fixed? fixed? :states {:cancelled {:on on}}})]
          (rf/dispatch-sync [leaver [:cancel (:a ids)]])
          (is (= [:cancelled nil] [(:state (snapshot leaver)) (join-event leaver)]))
          (is (empty? (events-of :rf.machine.spawn-all/any-failed)))))
      (doseq [[shape cancel] [["targetless" {:action kill}]
                              ["non-reentering self-targeted" {:target :joining :action kill}]]
              :let [stayer (keyword "sup" (str "stay-" addressing "-" (count shape)))]]
        (testing (str addressing " addresses, control: the same destroy from a " shape
                      " transition stays in the join and fires :on-any-failed")
          (rf.machines.test-support/reset-captured!)
          (let [ids (start-join! stayer [:a :b] spawn-all (assoc on :cancel cancel)
                                 {:fixed? fixed?})]
            (rf/dispatch-sync [stayer [:cancel (:a ids)]])
            (is (= [:over [:phase/failed :a cancellation-result]]
                   [(:state (snapshot stayer)) (join-event stayer)]))
            (is (= 1 (count (events-of :rf.machine.spawn-all/any-failed))))))))))

(deftest destroying-a-join-child-that-strands-the-join-warns
  (testing "with no :on-any-failed, an explicit destroy of one :all child is
            tracked as a failure is: the join can no longer succeed, the advisory
            fires when it strands the join, and the parent leaving the join state
            raises none"
    (rf/reg-event :sup/fire (fn [_ [_ id]] {:fx [[:rf.machine/destroy id]]}))
    (let [ids   (start-join! :sup/cancel [:a :b]
                             {:on-all-complete [:phase/done]}
                             (assoc (over-on :phase/done) :leave {:target :over}))
          warns #(mapv (comp (juxt :actor-id :cancelled) :tags)
                       (events-of :rf.warning/spawn-all-join-unsatisfiable))]
      (rf/dispatch-sync [:sup/fire (:a ids)])
      (is (= [[:sup/cancel #{:a}]] (warns)))
      (is (= [#{} #{:a} false] ((juxt :failed :cancelled :resolved?) (join-state :sup/cancel))))
      (rf/dispatch-sync [(:b ids) [:go]])
      (is (= :joining (:state (snapshot :sup/cancel))) "the stranded join stays hung")
      (rf/dispatch-sync [:sup/cancel [:leave]])
      (is (= [:over 1] [(:state (snapshot :sup/cancel)) (count (warns))])
          "leaving the join state ends it with no second advisory"))))

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
           [":spawn-all child carrying an inline :definition beside its :machine-id"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children        [{:id         :x
                                            :machine-id :foo
                                            :definition {:initial :i
                                                         :states  {:i {}}}}]
                         :on-all-complete [:done]}}]
           [":spawn-all child naming no :machine-id"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children        [{:id :x :fixed-actor-id :x/kid}]
                         :on-all-complete [:done]}}]
           ;; nothing arms a per-child deadline; the join's is the state's own
           [":spawn-all child carrying a spawn-level :timeout / :on-timeout"
            #"machine-unknown-spawn-key"
            {:spawn-all {:children        [{:id :x :machine-id :foo
                                            :timeout 600000 :on-timeout {:target :s}}]
                         :on-all-complete [:done]}}]
           [":spawn-all whose :on-any-failed is a keyword, not an event vector"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children        [{:id :x :machine-id :foo}]
                         :on-all-complete [:done]
                         :on-any-failed   :failed}}]
           ;; the natural spelling of a runtime-sized fan-out; refused by the
           ;; grammar, never by a host exception
           [":spawn-all whose :children is a fn"
            #"machine-spawn-all-bad-shape"
            {:spawn-all {:children        (fn [_] [{:id :x :machine-id :foo}])
                         :on-all-complete [:done]}}]
           [":spawn naming no :machine-id"
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
