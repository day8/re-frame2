(ns re-frame.cancellation-reply-envelope-test
  "Machine cancellation traces close their work attempt as a canonical
  `:cancelled` reply (EP-0011 §Cancellation): a cancelled `:after` timer and a
  `:spawn-all` join survivor."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private reply-keys
  [:rf.reply/status :rf.reply/work-status :rf.reply/cancelled? :rf.reply/cancel-reason
   :rf.reply/work-kind :rf.reply/work-id])

(defn- first-tags [operation]
  (:tags (first (rf.machines.test-support/events-of operation))))

(deftest timer-cancelled-trace-carries-reply-envelope
  (rf/reg-machine :sfunt8/timer
    {:initial :idle
     :data    {}
     :states  {:idle    {:on {:fetch :loading}}
               :loading {:after {5000 :timeout}
                         :on    {:loaded :ready}}
               :timeout {}
               :ready   {}}})
  (rf/dispatch-sync [:sfunt8/timer [:fetch]])
  ;; Exiting :loading before the timer fires cancels it with :reason :on-exit.
  (rf/dispatch-sync [:sfunt8/timer [:loaded]])
  (let [tags (first-tags :rf.machine.timer/cancelled)]
    (is (= {:reason                 :on-exit
            :rf.reply/status        :cancelled
            :rf.reply/work-status   :cancelled
            :rf.reply/cancelled?    true
            :rf.reply/cancel-reason :on-exit
            :rf.reply/work-kind     :timer
            :rf.reply/work-id       [:rf.work/timer [:sfunt8/timer :loading] (:epoch tags)]}
           (select-keys tags (cons :reason reply-keys))))))

;; A region `:after` is scheduled under a region-PREFIXED invoke-id. Its
;; :cancelled row must strip the region head exactly as its :fired row does,
;; or one logical timer splits across two work/reply ledger rows.
(deftest region-after-fired-and-cancelled-share-one-work-id
  (rf/reg-machine :cttpk4-tw/timer
    {:type    :parallel
     :data    {}
     :regions {:loader {:initial :working
                        :states  {:working {:after {30000 :timeout}}
                                  :timeout {}}}
               :other  {:initial :idle
                        :states  {:idle {}}}}})
  (rf/dispatch-sync [:cttpk4-tw/timer [:rf.machine.spawn/spawned]])
  (let [epoch (get-in (rf.machines.test-support/snapshot :cttpk4-tw/timer)
                      [:data :rf/after-epoch-by-region :loader [:working]])]
    ;; Fire the region :after with the region-prefixed decl-path the host timer
    ;; carries; the :working→:timeout exit also cancels its pending handle.
    (rf/dispatch-sync
      [:cttpk4-tw/timer [:rf.machine.timer/after-elapsed 30000 epoch [:loader :working]]])
    (let [fired (->> (rf.machines.test-support/events-of :rf.machine.timer/fired)
                     (filter #(true? (:fired? (:tags %))))
                     first)
          cancelled-wid (:rf.reply/work-id (first-tags :rf.machine.timer/cancelled))]
      (is (= [:rf.work/timer [:cttpk4-tw/timer :working] epoch] cancelled-wid))
      (is (= (:rf.reply/work-id (:tags fired)) cancelled-wid)))))

;; A parallel-ROOT `:after` has decl-path `[]`; its :scheduled and :cancelled
;; rows carry the `:rf/parallel-root` sentinel as :state (as :fired / :stale
;; do), never nil, so the (actor, state, epoch) pairing holds.
(deftest parallel-root-after-scheduled-and-cancelled-state-is-parallel-root
  (rf/reg-machine :cttpk4-root/m
    {:type    :parallel
     :data    {}
     :after   {30000 {:target [[:a :two]]}}
     :regions {:a {:initial :one :states {:one {} :two {}}}}})
  (rf/make-frame {:id :cttpk4-root/f})
  (rf/dispatch-sync [:cttpk4-root/m [:rf.machine/start]] {:frame :cttpk4-root/f})
  (rf/destroy-frame! :cttpk4-root/f)
  (let [state-of (fn [op pred]
                   (->> (rf.machines.test-support/events-of op)
                        (map :tags)
                        (filter pred)
                        first
                        :state))]
    (is (= [:rf/parallel-root :rf/parallel-root]
           [(state-of :rf.machine.timer/scheduled #(= 30000 (:delay %)))
            (state-of :rf.machine.timer/cancelled #(= :on-frame-destroy (:reason %)))]))))

(deftest join-survivor-cancel-trace-carries-cancelled-reply
  (let [child {:initial :running
               :states  {:running {:on {:go :done}}
                         :done    {:final? true}}}]
    (rf/reg-machine :sfunt8/sa child)
    (rf/reg-machine :sfunt8/sb child)
    (rf/reg-machine :sup/sfunt8
      {:initial :idle
       :states  {:idle      {:on {:start :hydrating}}
                 :hydrating {:spawn-all {:children         [{:id :a :machine-id :sfunt8/sa}
                                                            {:id :b :machine-id :sfunt8/sb}]
                                         :join             :any
                                         :on-some-complete [:hydrate/some]}
                             :on        {:hydrate/some :ready}}
                 :ready     {}}})
    (rf/dispatch-sync [:sup/sfunt8 [:start]])
    (let [ids (get-in (rf.machines.test-support/runtime-db)
                      [:rf.runtime/machines :spawned :sup/sfunt8 [:hydrating] :children])]
      ;; :a resolves the :any join, so the surviving sibling :b is cancelled.
      (rf/dispatch-sync [(:a ids) [:go]])
      (is (= {:rf.reply/status        :cancelled
              :rf.reply/work-status   :cancelled
              :rf.reply/cancelled?    true
              :rf.reply/cancel-reason :on-join-resolution
              :rf.reply/work-kind     :machine
              :rf.reply/work-id       [:rf.work/machine (:b ids) [:hydrating] 1]}
             (select-keys (first-tags :rf.machine.spawn/cancelled-on-join-resolution)
                          reply-keys))))))
