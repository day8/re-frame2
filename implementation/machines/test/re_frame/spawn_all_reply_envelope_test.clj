(ns re-frame.spawn-all-reply-envelope-test
  "`:spawn-all` join-child completions lower through the uniform reply
  envelope (Managed-Effects §The reply map / §Stale suppression), and a
  decisive failed child is never also closed `:cancelled`. The post-resolution
  late completion's `:stale` trace is pinned by spawn-all-test's
  late-completion-of-known-child-is-stale-record-frozen."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.reply :as rf.reply]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(deftest join-child-reply-is-canonical
  (let [ctx {:parent-id :sup/all :invoke-id [:hydrating] :child-id :a
             :spawned-id :child/a#1 :work-generation 1 :frame :rf/default}
        base {:rf.reply/work-id   [:rf.work/machine :child/a#1 [:hydrating] 1]
              :rf.reply/work-kind :machine
              :rf.frame/id        :rf/default
              :correlation        {:parent-id :sup/all :invoke-id [:hydrating]
                                   :child-id :a :spawned-id :child/a#1}}]
    (doseq [[label r expected]
            [["done is :ok, carrying the result"
              (rf.machines.reply/join-child-reply ctx :done {:loaded true})
              (assoc base :status :ok :rf.reply/work-status :completed :value {:loaded true})]
             ["failed is :error, the payload wrapped under a family :kind"
              (rf.machines.reply/join-child-reply ctx :failed :boom)
              (assoc base :status :error :rf.reply/work-status :failed
                          :error {:kind :rf.machine/spawn-all-child-error :value :boom})]
             ["post-resolution late completion is :stale and carries no :value"
              (rf.machines.reply/stale-join-child-reply ctx :done)
              (-> base
                  (assoc :status :stale :stale? true :rf.reply/work-status :suppressed
                         :rf.reply/stale-reason :rf.machine.spawn-all/join-resolved)
                  (assoc-in [:correlation :kind] :done))]]]
      (testing label
        (is (= expected r))
        (is (rf.reply/valid-reply? r) (str (rf.reply/validate-reply r)))))))

(defn- terminal-rows
  "`[<trace-op> <work-status>]` for every terminal reply row on `spawned-id`'s work-id."
  [captured spawned-id]
  (into []
        (comp (filter #(= spawned-id (second (:rf.reply/work-id (:tags %)))))
              (keep (fn [ev]
                      (let [st (:rf.reply/work-status (:tags ev))]
                        (when (#{:completed :failed :cancelled} st)
                          [(:operation ev) st])))))
        @captured))

;; A failed child has already closed its attempt at its own finality; tearing it
;; down as an explicit destroy at resolution would add a contradictory
;; :cancelled terminal on the same work-id.
(deftest failed-any-child-terminals-all-agree-never-cancelled
  (testing "the decisive failed child's terminals are its own finality and the join's fold, both
            :failed; the surviving sibling alone is cancelled"
    (let [child {:initial :running
                 :states  {:running {:on {:fail {:target :failed}}}
                           :failed  {:final? true :error? true}}}]
      (rf/reg-machine :tjf/child child)
      (rf/reg-machine :sup/tj-fail
        {:initial :idle
         :states  {:idle   {:on {:start :racing}}
                   ;; no :on for :race/lost, so the parent stays in :racing
                   :racing {:spawn-all {:children         [{:id :a :machine-id :tjf/child}
                                                           {:id :b :machine-id :tjf/child}]
                                        :join             :any
                                        :on-some-complete [:race/won]
                                        :on-any-failed    [:race/lost]}}}})
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sup/tj-fail [:start]])
        (let [{a :a b :b} (get-in (rf.machines.test-support/runtime-db)
                                  [:rf.runtime/machines :spawned :sup/tj-fail [:racing] :children])]
          (rf/dispatch-sync [a [:fail]])
          (is (= [[:rf.machine/done :failed] [:rf.machine.spawn-all/any-failed :failed]]
                 (terminal-rows captured a)))
          (is (= [[:rf.machine.spawn/cancelled-on-join-resolution :cancelled]
                  [:rf.machine/destroyed :cancelled]]
                 (terminal-rows captured b))))))))
