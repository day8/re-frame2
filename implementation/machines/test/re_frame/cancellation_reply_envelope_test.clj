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
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

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
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sfunt8/timer [:fetch]])
        (is (= :loading (:state (rf.machines.test-support/snapshot :sfunt8/timer))))
        ;; Exit :loading before the timer fires — cancels the :after timer
        ;; with :reason :on-exit.
        (rf/dispatch-sync [:sfunt8/timer [:loaded]])
        (is (= :ready (:state (rf.machines.test-support/snapshot :sfunt8/timer))))
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
          (is (= :timer (:rf.reply/work-kind (:tags cancelled))))
          (is (some? (:rf.reply/work-id (:tags cancelled)))
              "canonical timer :work/id closes the cancelled work attempt")
          (is (= :rf.work/timer (first (:rf.reply/work-id (:tags cancelled))))))))))

;; ---- region :after timer work-id correlation (rf2-cttpk4) --------------
;;
;; An `:after` declared inside a parallel REGION carries a region-PREFIXED
;; invoke-id (`prefix-region-invoke-id` prepends the region name). The FIRED /
;; STALE timer replies strip that region head (`pick-after-transition`'s
;; `carried-decl-path`) when building their `:rf.reply/work-id`, but the
;; CANCELLED reply historically used the raw region-prefixed `:spawn` — so the
;; SAME logical `:after`'s cancelled row landed under a different
;; `[:rf.work/timer <logical-id> <epoch>]` than its fired / stale rows,
;; splitting one timer across the work/reply ledger. This drives ONE region
;; `:after` to BOTH fire and (on the firing exit) cancel its still-pending host
;; handle in a single dispatch, and asserts the two rows share ONE work-id.

(deftest region-after-fired-and-cancelled-share-one-work-id
  (testing "rf2-cttpk4 — a region :after's :fired and :cancelled rows carry the
            SAME region-stripped :rf.reply/work-id (was split by the region head)"
    (rf/reg-machine :cttpk4-tw/timer
      {:type    :parallel
       :data    {}
       :regions {:loader {:initial :working
                          :states  {:working {:after {30000 :timeout}}
                                    :timeout {}}}
                 :other  {:initial :idle
                          :states  {:idle {}}}}})
    (rf.machines.test-support/with-trace-capture captured
      ;; Birth the singleton parallel machine (schedules :loader/:working's
      ;; :after at its per-region epoch — a still-pending 30s host handle).
      (rf/dispatch-sync [:cttpk4-tw/timer [:rf.machine.spawn/spawned]])
      (let [snap  (rf.machines.test-support/snapshot :cttpk4-tw/timer)
            epoch (get-in snap [:data :rf/after-epoch-by-region :loader [:working]])]
        (is (= :working (get-in snap [:state :loader])) ":loader entered :working")
        (is (some? epoch) "the region :after was scheduled at a per-region epoch")
        ;; Fire the :loader :after via its synthetic elapsed event carrying the
        ;; region-PREFIXED decl-path (exactly what the real host timer
        ;; dispatches). The :working→:timeout transition ALSO exits :working,
        ;; cancelling the still-pending host handle → ONE dispatch emits BOTH
        ;; :fired and :cancelled for the SAME logical :after.
        (rf/dispatch-sync
          [:cttpk4-tw/timer
           [:rf.machine.timer/after-elapsed 30000 epoch [:loader :working]]])
        (is (= :timeout (get-in (rf.machines.test-support/snapshot :cttpk4-tw/timer) [:state :loader]))
            "the region :after fired → :loader moved to :timeout")
        (let [fired         (->> @captured
                                 (filter #(and (= :rf.machine.timer/fired (:operation %))
                                               (true? (:fired? (:tags %)))))
                                 first)
              cancelled     (->> @captured
                                 (filter #(= :rf.machine.timer/cancelled (:operation %)))
                                 first)
              fired-wid     (:rf.reply/work-id (:tags fired))
              cancelled-wid (:rf.reply/work-id (:tags cancelled))]
          (is (some? fired)     ":rf.machine.timer/fired trace fired")
          (is (some? cancelled) ":rf.machine.timer/cancelled trace fired (the firing exit released the host handle)")
          (is (= :rf.work/timer (first cancelled-wid)))
          ;; The cancelled work-id's logical-id is region-STRIPPED: it ends in
          ;; the region-RELATIVE state :working and does NOT carry the region
          ;; name :loader (which the raw region-prefixed :spawn had put at
          ;; position 1 of the logical-id, splitting the ledger row).
          (is (= :working (last (second cancelled-wid))))
          (is (not (some #{:loader} (second cancelled-wid)))
              "the region name is stripped from the cancelled work-id logical-id")
          ;; The headline correlation: fired and cancelled share ONE work-id, so
          ;; both rows of this ONE :after join the same work/reply ledger row.
          (is (= fired-wid cancelled-wid)
              "the region :after's :fired and :cancelled rows share one :rf.reply/work-id"))))))

;; ---- parallel-root :after :state consistency (rf2-cttpk4) --------------
;;
;; A parallel-ROOT `:after` (decl-path `[]`) is scheduled via
;; `schedule-root-after-fx` → `build-after-fx` with an EMPTY prefix, so
;; `(last prefix)` is nil. The FIRED / STALE resolvers stamp the root sentinel
;; `:rf/parallel-root` as the `:state`; the SCHEDULED / CANCELLED traces used to
;; emit `:state nil`, breaking the `(actor, state, epoch)` pairing
;; `emit-cancelled!`'s docstring promises. Both the scheduled and cancelled
;; root-timer traces must carry `:rf/parallel-root` too.

(deftest parallel-root-after-scheduled-and-cancelled-state-is-parallel-root
  (testing "rf2-cttpk4 — a parallel-root :after's :scheduled and :cancelled traces
            carry :state :rf/parallel-root (matching :fired / :stale), not nil"
    (rf/reg-machine :cttpk4-root/m
      {:type    :parallel
       :data    {}
       :after   {30000 {:target [[:a :two]]}}
       :regions {:a {:initial :one :states {:one {} :two {}}}}})
    (rf/make-frame {:id :cttpk4-root/f :doc "root-after :state test frame"})
    (rf.machines.test-support/with-trace-capture captured
      ;; Birth in the named frame → schedules the root :after (a still-pending
      ;; 30s host handle) and emits the :scheduled trace.
      (rf/dispatch-sync [:cttpk4-root/m [:rf.machine/start]] {:frame :cttpk4-root/f})
      (let [scheduled (->> @captured
                           (filter #(and (= :rf.machine.timer/scheduled (:operation %))
                                         (= 30000 (:delay (:tags %)))))
                           first)]
        (is (some? scheduled) "the root :after emitted a :scheduled trace at birth")
        (is (= :rf/parallel-root (:state (:tags scheduled)))
            "the scheduled root-timer :state is the :rf/parallel-root sentinel (was nil)"))
      ;; Frame teardown cancels the still-pending root timer with
      ;; :reason :on-frame-destroy → a :cancelled trace.
      (rf/destroy-frame! :cttpk4-root/f)
      (let [cancelled (->> @captured
                           (filter #(and (= :rf.machine.timer/cancelled (:operation %))
                                         (= :on-frame-destroy (:reason (:tags %)))))
                           first)]
        (is (some? cancelled) "frame teardown cancelled the pending root timer")
        (is (= :rf/parallel-root (:state (:tags cancelled)))
            "the cancelled root-timer :state is the :rf/parallel-root sentinel (was nil)")))))

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
      (rf.machines.test-support/with-trace-capture captured
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
          (is (= :machine (:rf.reply/work-kind (:tags destroyed))))
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
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sfunt8/fparent [:rf.machine.spawn/spawned]])
        (let [spawned-id (get-in (rf.machines.test-support/runtime-db)
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
      (rf.machines.test-support/with-trace-capture captured
        (rf/dispatch-sync [:sup/sfunt8 [:start]])
        (let [ids (get-in (rf.machines.test-support/runtime-db)
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
          (is (= :machine (:rf.reply/work-kind (:tags cancel))))
          (is (some? (:rf.reply/work-id (:tags cancel)))
              "canonical :work/id closes the survivor's cancelled attempt"))))))
