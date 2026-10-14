(ns re-frame.spawn-all-reply-envelope-test
  "`:spawn-all` join-child completions lower through the uniform reply
  envelope (Managed-Effects §The reply map / §Stale suppression). The
  post-resolution late completion's `:stale` trace is pinned by
  spawn-all-test's late-completion-of-known-child-is-stale-record-frozen."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines.reply :as rf.machines.reply]
            [re-frame.reply :as rf.reply]))

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
