(ns re-frame.spawn-all-reply-envelope-test
  "`:spawn-all` join-child completions lower through the uniform reply
  envelope (EP-0011 §Machine Completion / Managed-Effects §Status taxonomy /
  §Stale suppression / §Tracing).

  Single `:spawn` finality lowers through `re-frame.machines.reply` and stamps
  `:work/id` + `:rf.reply/status` on `:rf.machine/done`. The `:spawn-all`
  child completion folds into join state AND carries a canonical reply map.
  These tests pin that:

   1. the DECISIVE child completion that drives a join resolution rides
      reply-envelope facts (`:work/id`, `:rf.reply/status :ok` / `:error`,
      `:rf.reply/work-status`) on the resolution trace
      (`:rf.machine.spawn-all/all-completed` / `*/some-completed` /
      `*/any-failed`);
   2. a post-resolution late completion (a surviving sibling under
      `:cancel-on-decision? false`) rides a `:status :stale` /
      `:work/status :suppressed` reply on the `:late-completion` trace —
      the spawn-all analogue of the single-`:spawn` stale path.

  Every reply fact asserted here corresponds to a canonical reply map built
  by `re-frame.machines.reply` and validated by the shared reply contract
  in the pure `machines-reply` test."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.reply :as m-reply]
            [re-frame.machines.test-support :as mtest]
            [re-frame.reply :as reply]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(defn- mk-child
  [parent-id done-event-kw error-event-kw]
  {:initial :running
   :data    {:id nil}
   :actions {:dispatch-done
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [done-event-kw (:id data)]]]]})
             :dispatch-error
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [error-event-kw (:id data)]]]]})
             :record-id
             (fn [{data :data ev :event}]
               {:data (assoc data :id (second ev))})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done   :action :dispatch-done}
                   :fail   {:target :failed :action :dispatch-error}}}
    :done   {}
    :failed {}}})

;; ---- pure reply-helper level ----------------------------------------------

(deftest join-child-reply-is-canonical
  (testing "rf2-d63qtp — a done join-child reply is canonical :status :ok"
    (let [r (m-reply/join-child-reply
              {:parent-id :sup/all :invoke-id [:hydrating]
               :child-id :a :spawned-id :child/a#1 :frame :rf/default}
              :done {:loaded true})]
      (is (= :ok (:status r)))
      (is (= :machine (:work/kind r)))
      (is (= :completed (:work/status r)))
      (is (= {:loaded true} (:value r)))
      (is (= [:rf.work/machine :child/a#1 [:hydrating] 1] (:work/id r)))
      (is (= :a (-> r :correlation :child-id)))
      (is (= :child/a#1 (-> r :correlation :spawned-id)))
      (is (reply/valid-reply? r) (str (reply/validate-reply r)))))
  (testing "a failed join-child reply is canonical :status :error with family :kind"
    (let [r (m-reply/join-child-reply
              {:parent-id :sup/all :invoke-id [:hydrating]
               :child-id :b :spawned-id :child/b#1 :frame :rf/default}
              :failed :boom)]
      (is (= :error (:status r)))
      (is (= :failed (:work/status r)))
      (is (some? (:kind (:error r))) ":error carries a family :kind")
      (is (reply/valid-reply? r) (str (reply/validate-reply r))))))

(deftest stale-join-child-reply-is-canonical
  (testing "rf2-d63qtp — a post-resolution late join-child completion is :status :stale"
    (let [r (m-reply/stale-join-child-reply
              {:parent-id :sup/all :invoke-id [:hydrating]
               :child-id :c :spawned-id :child/c#1 :frame :rf/default}
              :done)]
      (is (= :stale (:status r)))
      (is (true? (:stale? r)))
      (is (= :rf.machine.spawn-all/join-resolved (:stale/reason r)))
      (is (= :suppressed (:work/status r)))
      (is (not (contains? r :value)) ":stale carries no :value (no app mutation)")
      (is (= [:rf.work/machine :child/c#1 [:hydrating] 1] (:work/id r)))
      (is (reply/valid-reply? r) (str (reply/validate-reply r))))))

;; ---- integration: resolution trace carries reply facts ----------------

(deftest all-completed-trace-carries-decisive-child-reply
  (testing "rf2-d63qtp — the :all-completed resolution trace carries the
            decisive child's reply-envelope facts (:work/id, :status :ok)"
    (let [child  (mk-child :sup/relp1 :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :relp1/a :start [:set-id :a]}
                                        {:id :b :machine-id :relp1/b :start [:set-id :b]}]
                     :join             :all
                     :on-child-done    :asset/loaded
                     :on-child-error   :asset/failed
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready     {}
                   :error     {}}}]
      (rf/reg-machine :relp1/a child)
      (rf/reg-machine :relp1/b child)
      (rf/reg-machine :sup/relp1 parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/relp1 [:start]])
        (rf/dispatch-sync [:relp1/a#1 [:go]])
        (rf/dispatch-sync [:relp1/b#1 [:go]])
        (let [done (->> @captured
                        (filter #(= :rf.machine.spawn-all/all-completed (:operation %)))
                        first)]
          (is (some? done) ":all-completed trace fired")
          (is (= :ok (:rf.reply/status (:tags done)))
              "the decisive child completion classified as :ok")
          (is (= :completed (:rf.reply/work-status (:tags done))))
          (is (= :machine (:work/kind (:tags done))))
          (is (some? (:work/id (:tags done)))
              "the decisive child's canonical :work/id rides the resolution trace")
          (is (= (first (:work/id (:tags done))) :rf.work/machine)))))))

(deftest any-failed-trace-carries-decisive-child-reply
  (testing "rf2-d63qtp — the :any-failed resolution trace carries the
            decisive child's reply-envelope facts (:status :error)"
    (let [child  (mk-child :sup/relp2 :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :relp2/a :start [:set-id :a]}
                                        {:id :b :machine-id :relp2/b :start [:set-id :b]}]
                     :join             :all
                     :on-child-done    :asset/loaded
                     :on-child-error   :asset/failed
                     :on-all-complete  [:hydrate/done]
                     :on-any-failed    [:hydrate/failed]}
                    :on    {:hydrate/done   :ready
                            :hydrate/failed :error}}
                   :ready     {}
                   :error     {}}}]
      (rf/reg-machine :relp2/a child)
      (rf/reg-machine :relp2/b child)
      (rf/reg-machine :sup/relp2 parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/relp2 [:start]])
        (rf/dispatch-sync [:relp2/a#1 [:fail]])
        (let [failed (->> @captured
                          (filter #(= :rf.machine.spawn-all/any-failed (:operation %)))
                          first)]
          (is (some? failed) ":any-failed trace fired")
          (is (= :error (:rf.reply/status (:tags failed)))
              "the decisive failing child classified as :error")
          (is (= :failed (:rf.reply/work-status (:tags failed))))
          (is (some? (:work/id (:tags failed)))))))))

;; ---- integration: late completion carries a stale reply ----------------

(deftest late-completion-carries-stale-reply
  (testing "rf2-d63qtp — a post-resolution late completion (surviving sibling
            under :cancel-on-decision? false) rides a :status :stale /
            :work/status :suppressed reply on the late-completion trace"
    (let [child  (mk-child :sup/relp3 :asset/loaded :asset/failed)
          ;; Parent stays in :hydrating across resolution by NOT declaring
          ;; an :on for the resolution event (the runtime still dispatches
          ;; :hydrate/some, but no transition fires) — so the join slot
          ;; survives and the surviving sibling's later completion flows
          ;; through the :resolved? late-completion branch.
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children            [{:id :a :machine-id :relp3/a :start [:set-id :a]}
                                           {:id :b :machine-id :relp3/b :start [:set-id :b]}]
                     :join                :any
                     :cancel-on-decision? false
                     :on-child-done       :asset/loaded
                     :on-child-error      :asset/failed
                     :on-some-complete    [:hydrate/some]}}}}]
      (rf/reg-machine :relp3/a child)
      (rf/reg-machine :relp3/b child)
      (rf/reg-machine :sup/relp3 parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/relp3 [:start]])
        (let [ids (get-in (mtest/runtime-db)
                          [:rf.runtime/machines :spawned :sup/relp3 [:hydrating] :children])]
          ;; First child resolves the :any join.
          (rf/dispatch-sync [(:a ids) [:go]])
          ;; Surviving sibling completes LATE (join already resolved). Under
          ;; :cancel-on-decision? false it is not torn down, so its
          ;; :on-child-done lands as a post-resolution late-completion.
          (rf/dispatch-sync [(:b ids) [:go]]))
        (let [late (->> @captured
                        (filter #(= :rf.machine.spawn-all/late-completion (:operation %)))
                        first)]
          (is (some? late) ":late-completion trace fired")
          (is (= :stale (:rf.reply/status (:tags late)))
              "the late completion classified as :stale")
          (is (= :suppressed (:rf.reply/work-status (:tags late))))
          (is (= :rf.machine.spawn-all/join-resolved
                 (:rf.reply/stale-reason (:tags late))))
          (is (some? (:work/id (:tags late)))
              "the suppressed late completion carries the canonical :work/id"))))))
