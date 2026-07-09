(ns re-frame.cancellation-reply-envelope-test
  "Machine cancellation terminal paths close the work attempt the
  reply-envelope way (EP-0011 §Cancellation / Managed-Effects §Cancellation:
  \"Cancellation is represented as data, not as the absence of a reply\").

  A cancelled `:after` timer, a destroyed actor, and a `:spawn-all`
  join-survivor cancellation each carry a canonical terminal reply: their
  traces carry reason / state / epoch ALONGSIDE a canonical `:work/id`,
  `:rf.reply/status :cancelled`, `:rf.reply/work-status`, and `:rf.reply/cancel-reason`
  — so a cancelled timer / actor closes its scheduled / spawned START with a
  terminal EP-0011 reply row.

  These tests pin the reply-envelope facts on the three cancellation traces:
   1. `:rf.machine.timer/cancelled` (on state exit) →
      `:rf.reply/status :cancelled` + canonical timer `:work/id`;
   2. `:rf.machine/destroyed` `:reason :explicit` (a genuine cancellation) →
      `:rf.reply/status :cancelled` + canonical machine `:work/id`;
      `:reason :rf.machine/finished` carries NO cancelled facts (the actor
      already closed through `:rf.machine/done`);
   3. `:rf.machine.spawn/cancelled-on-join-resolution` →
      `:rf.reply/status :cancelled` + `:rf.reply/cancel-reason :on-join-resolution`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- timer cancel (state exit) -----------------------------------------

(deftest timer-cancelled-trace-carries-reply-envelope
  (testing "rf2-sfunt8 — :rf.machine.timer/cancelled (state exit) carries the
            reply-envelope :status :cancelled facts + canonical timer :work/id"
    (let [m {:initial :idle
             :data    {}
             :states  {:idle    {:on {:fetch :loading}}
                       :loading {:after {5000 :timeout}
                                 :on    {:loaded :ready}}
                       :timeout {}
                       :ready   {}}}]
      (rf/reg-machine :sfunt8/timer m)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sfunt8/timer [:fetch]])
        (is (= :loading (:state (mtest/snapshot :sfunt8/timer))))
        ;; Exit :loading before the timer fires — cancels the :after timer
        ;; with :reason :on-exit.
        (rf/dispatch-sync [:sfunt8/timer [:loaded]])
        (is (= :ready (:state (mtest/snapshot :sfunt8/timer))))
        (let [cancelled (->> @captured
                             (filter #(= :rf.machine.timer/cancelled (:operation %)))
                             first)]
          (is (some? cancelled) ":rf.machine.timer/cancelled trace fired")
          (is (= :on-exit (:reason (:tags cancelled))) "public reason preserved")
          (is (= :cancelled (:rf.reply/status (:tags cancelled)))
              "reply-envelope :status :cancelled")
          (is (= :cancelled (:rf.reply/work-status (:tags cancelled))))
          (is (true? (:rf.reply/cancelled? (:tags cancelled))))
          (is (= :on-exit (:rf.reply/cancel-reason (:tags cancelled))))
          (is (= :timer (:work/kind (:tags cancelled))))
          (is (some? (:rf.reply/work-id (:tags cancelled)))
              "canonical timer :work/id closes the cancelled work attempt")
          (is (= :rf.work/timer (first (:rf.reply/work-id (:tags cancelled))))))))))

;; ---- actor destroy (explicit cancellation) -----------------------------

(deftest explicit-destroy-trace-carries-cancelled-reply
  (testing "rf2-sfunt8 — an :explicit :rf.machine/destroyed (actor torn down
            before :final?) carries the reply-envelope :status :cancelled facts"
    (let [child  {:initial :running
                  :data    {}
                  :states  {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :sfunt8/child}
                             :on     {:stop :idle}}}}]
      (rf/reg-machine :sfunt8/child child)
      (rf/reg-machine :sfunt8/parent parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sfunt8/parent [:start]])
        ;; Exit the :spawn-bearing state — the spawned child is destroyed
        ;; (cancelled) before reaching a :final? leaf.
        (rf/dispatch-sync [:sfunt8/parent [:stop]])
        (let [destroyed (->> @captured
                             (filter #(and (= :rf.machine/destroyed (:operation %))
                                           (= :explicit (:reason (:tags %)))))
                             first)]
          (is (some? destroyed) "an :explicit :rf.machine/destroyed fired")
          (is (= :cancelled (:rf.reply/status (:tags destroyed)))
              "explicit destroy closes the work attempt as :cancelled")
          (is (= :cancelled (:rf.reply/work-status (:tags destroyed))))
          (is (true? (:rf.reply/cancelled? (:tags destroyed))))
          (is (= :explicit (:rf.reply/cancel-reason (:tags destroyed))))
          (is (= :machine (:work/kind (:tags destroyed))))
          (is (some? (:rf.reply/work-id (:tags destroyed)))
              "canonical machine :work/id closes the cancelled actor attempt"))))))

(deftest finished-destroy-carries-no-cancelled-reply
  (testing "rf2-sfunt8 — a :rf.machine/finished destroy is NOT a cancellation
            (the actor closed through :rf.machine/done) — no cancelled facts"
    (let [child  {:initial :running
                  :data    {}
                  :states  {:running {:on {:end :done}}
                            :done    {:final? true}}}
          parent {:initial :working
                  :states  {:working {:spawn {:machine-id :sfunt8/fchild}}}}]
      (rf/reg-machine :sfunt8/fchild child)
      (rf/reg-machine :sfunt8/fparent parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sfunt8/fparent [:rf.machine.spawn/spawned]])
        (let [spawned-id (get-in (mtest/runtime-db)
                                 [:rf.runtime/machines :spawned :sfunt8/fparent [:working]])]
          (rf/dispatch-sync [spawned-id [:end]]))
        (let [finished (->> @captured
                            (filter #(and (= :rf.machine/destroyed (:operation %))
                                          (= :rf.machine/finished (:reason (:tags %)))))
                            first)]
          (is (some? finished) "a :rf.machine/finished destroy fired")
          (is (not (contains? (:tags finished) :rf.reply/status))
              "a finished destroy carries no cancelled reply facts")
          (is (not (contains? (:tags finished) :work/id))))))))

;; ---- join-survivor cancellation ----------------------------------------

(defn- mk-child
  [parent-id done-event-kw error-event-kw]
  {:initial :running
   :data    {:id nil}
   :actions {:dispatch-done
             (fn [{data :data}]
               {:fx [[:dispatch [parent-id [done-event-kw (:id data)]]]]})
             :record-id
             (fn [{data :data ev :event}]
               {:data (assoc data :id (second ev))})}
   :states
   {:running {:on {:set-id {:action :record-id}
                   :go     {:target :done :action :dispatch-done}}}
    :done   {}}})

(deftest join-survivor-cancel-trace-carries-cancelled-reply
  (testing "rf2-sfunt8 — :rf.machine.spawn/cancelled-on-join-resolution carries
            the reply-envelope :status :cancelled facts (:rf.reply/cancel-reason
            :on-join-resolution)"
    (let [child  (mk-child :sup/sfunt8 :asset/loaded :asset/failed)
          parent {:initial :idle
                  :states
                  {:idle      {:on {:start :hydrating}}
                   :hydrating
                   {:spawn-all
                    {:children         [{:id :a :machine-id :sfunt8/sa :start [:set-id :a]}
                                        {:id :b :machine-id :sfunt8/sb :start [:set-id :b]}]
                     :join             :any
                     ;; sibling cancellation on the join decision is
                     ;; unconditional → surviving sibling is torn down on
                     ;; resolution.
                     :on-child-done    :asset/loaded
                     :on-child-error   :asset/failed
                     :on-some-complete [:hydrate/some]}
                    :on    {:hydrate/some :ready}}
                   :ready     {}}}]
      (rf/reg-machine :sfunt8/sa child)
      (rf/reg-machine :sfunt8/sb child)
      (rf/reg-machine :sup/sfunt8 parent)
      (mtest/with-trace-capture captured
        (rf/dispatch-sync [:sup/sfunt8 [:start]])
        (let [ids (get-in (mtest/runtime-db)
                          [:rf.runtime/machines :spawned :sup/sfunt8 [:hydrating] :children])]
          ;; First child resolves the :any join; sibling :b is cancelled.
          (rf/dispatch-sync [(:a ids) [:go]]))
        (let [cancel (->> @captured
                          (filter #(= :rf.machine.spawn/cancelled-on-join-resolution
                                      (:operation %)))
                          first)]
          (is (some? cancel) "join-survivor cancellation trace fired")
          (is (= :cancelled (:rf.reply/status (:tags cancel)))
              "the survivor cancellation is :status :cancelled")
          (is (= :cancelled (:rf.reply/work-status (:tags cancel))))
          (is (= :on-join-resolution (:rf.reply/cancel-reason (:tags cancel))))
          (is (= :machine (:work/kind (:tags cancel))))
          (is (some? (:rf.reply/work-id (:tags cancel)))
              "canonical :work/id closes the survivor's cancelled attempt"))))))
