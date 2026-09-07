(ns day8.re-frame2-xray.panels.machine-work-identity-cljs-test
  "Actual-consumer counterfixture for fixed-id `:spawn-all` attempt identity.

  The fixture feeds the real tag maps emitted by the machines runtime into
  Xray's production `races-by-work-id` projection. No hand-authored work ids
  sit between producer and consumer, so a producer regression back to constant
  fixed-id generation 1 deterministically collapses the two arcs on both CLJ
  and CLJS."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   #?(:clj  [re-frame.test-support :as rf.test-support
             :refer [with-trace-recorder!]]
      :cljs [re-frame.test-support :as rf.test-support
             :refer-macros [with-trace-recorder!]])
   [day8.re-frame2-xray.panels.reply-envelope :as reply-envelope]
   [re-frame.core :as rf]
   [re-frame.frame :as rf.frame]
   [re-frame.machines]
   [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

(def ^:private parent-id :xray-work-id/parent)
(def ^:private child-type :xray-work-id/child-type)
(def ^:private fixed-child :xray-work-id/fixed-child#7)

(defn- join-state []
  (get-in (rf.frame/frame-runtime-db-value :rf/default)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- join-attempt
  "The live `:rf/join-child` membership record on `actor` — the exact-attempt
  coordinate the runtime stamped at spawn. Read it while the child is ALIVE:
  completion is finality, so a completed child has already torn itself down."
  ([] (join-attempt fixed-child))
  ([actor]
   (get-in (rf.frame/frame-runtime-db-value :rf/default)
           [:rf.runtime/machines :snapshots actor :data :rf/join-child])))

(defn- forged-completion!
  "Hand-dispatch the reserved completion carrier
  `[<parent> [:rf.machine.spawn/done <invoke-id> <completion>]]` that the
  machines runtime mints at a child's finality, carrying `auth` (a
  `:rf/join-child` record captured while the child was live) as its
  exact-attempt coordinate. This is how the fixture drives a straggler the
  runtime itself would never re-mint — the arrivals whose Xray classification
  these tests are about."
  [parent auth]
  (rf/dispatch-sync
    [parent [:rf.machine.spawn/done (:invoke-id auth)
             (assoc (select-keys auth [:parent-id :invoke-id :child-id
                                       :spawned-id :attempt :work-generation])
                    :result (:child-id auth)
                    :error? false)]]))

;; The children below complete the ONE way every machine completes: they reach a
;; top-level `:final?` leaf and the runtime mints the completion carrier from
;; their own membership record. They name no parent and no completion event.

(defn- register-machines! []
  (rf/reg-machine
    child-type
    {:initial :running
     :states {:running {:on {:go {:target :done}}}
              :done {:final? true}}})
  (rf/reg-machine
    parent-id
    {:initial :idle
     :states {:idle {:on {:start :racing}}
              :racing
              {:spawn-all
               {:children [{:id :only :machine-id child-type
                            :fixed-actor-id fixed-child}]
                :join :all
                :on-all-complete [:join/done]}
               ;; Keep the resolved join record live; `:abort` is the explicit
               ;; re-entry boundary that tears A down before B is seeded.
               :on {:abort :idle}}}}))

(defn- register-pre-resolution-exit-machines! []
  (let [parent :xray-work-id/exit-parent
        child  {:initial :running
                :data    {:id nil}
                :actions {:remember (fn [{data :data event :event}]
                                      {:data (assoc data :id (second event))})}
                :states {:running {:on {:set-id {:action :remember}
                                        :go {:target :done}}}
                         :done {:final? true :output-key :id}}}]
    (rf/reg-machine :xray-work-id/exit-a-type child)
    (rf/reg-machine :xray-work-id/exit-b-type child)
    (rf/reg-machine
      parent
      {:initial :idle
       :states {:idle {:on {:start :racing}}
                :racing
                {:spawn-all
                 {:children [{:id :a :machine-id :xray-work-id/exit-a-type
                              :fixed-actor-id :xray-work-id/exit-a#7
                              :start [:set-id :a]}
                             {:id :b :machine-id :xray-work-id/exit-b-type
                              :fixed-actor-id :xray-work-id/exit-b
                              :start [:set-id :b]}]
                  :join :all
                  :on-all-complete [:join/done]}
                 :on {:abort :idle}}}})))

(defn- register-cancel-race-machines!
  "A single-child `:all` join whose child never completes on its own, plus a
  parent event that imperatively destroys it. The race this fixture models — a
  completion carrier landing after its attempt closed — used to be built by
  having the child queue its own completion and then destroy itself in one fx
  batch. Completion is finality now, so a child cannot both complete and be
  cancelled; the carrier is instead captured while the child is live and
  delivered after the destroy, which is the same arrival from Xray's side."
  []
  (let [parent :xray-work-id/cancel-race-parent
        actor  :xray-work-id/cancel-race-child#7]
    (rf/reg-machine
      :xray-work-id/cancel-race-child-type
      {:initial :running
       :states {:running {:on {:go {:target :done}}}
                :done {:final? true}}})
    (rf/reg-machine
      parent
      {:initial :idle
       :actions {:destroy-child (fn [_] {:fx [[:rf.machine/destroy actor]]})}
       :states {:idle {:on {:start :racing}}
                :racing
                {:spawn-all
                 {:children [{:id :only
                              :machine-id :xray-work-id/cancel-race-child-type
                              :fixed-actor-id actor}]
                  :join :all
                  :on-all-complete [:join/done]}
                 :on {:destroy-child {:action :destroy-child}}}}})
    (rf/dispatch-sync [parent [:start]])))

(deftest emitted-fixed-id-attempts-remain-distinct-in-xray
  (testing "a stale carrier from A cannot suppress B's actual Xray arc"
    (register-machines!)
    (with-trace-recorder!
      [traces {:pred #(contains?
                       #{:rf.machine/destroyed
                         :rf.machine.spawn-all/stale-completion
                         :rf.machine.spawn-all/all-completed}
                       (:operation %))}]
      ;; Attempt A is cancelled by parent exit, but retain its exact carried
      ;; join-attempt coordinate to model a delayed completion arriving after re-entry.
      (rf/dispatch-sync [parent-id [:start]])
      (let [attempt-a (:rf/attempt (join-state))
            auth-a    (join-attempt)]
        (rf/dispatch-sync [parent-id [:abort]])
        (rf/dispatch-sync [parent-id [:start]])
        (let [attempt-b (:rf/attempt (join-state))]
          (is (not= attempt-a attempt-b))

          ;; Attempt A's carrier — captured off its live membership record
          ;; above — drains now, against attempt B's join.
          (forged-completion! parent-id auth-a)
          ;; Attempt B completes normally through the current fixed child.
          (rf/dispatch-sync [fixed-child [:go]])

          (let [work-a [:rf.work/machine fixed-child [:racing] attempt-a]
                work-b [:rf.work/machine fixed-child [:racing] attempt-b]
                ;; This is the production Xray consumer operating directly on
                ;; the runtime-emitted events captured above.
                arcs   (reply-envelope/races-by-work-id @traces)
                arc-a  (get arcs work-a)
                arc-b  (get arcs work-b)]
            (is (= #{work-a work-b} (set (keys arcs)))
                "the same fixed actor address yields two canonical attempt arcs")
            (is (= :cancelled (:terminal-status arc-a))
                "A's real cancellation terminal outranks later stale evidence")
            (is (true? (:suppressed? arc-a)))
            (is (= :ok (:terminal-status arc-b)))
            (is (false? (:suppressed? arc-b))
                "attempt A suppression never contaminates attempt B")))))))

(deftest post-resolution-superseded-straggler-is-stale-completion-not-late
  ;; rf2-ixjd48 / rf2-w82021 — THE POST-RESOLUTION EXACT-ATTEMPT PATH, driven
  ;; through the REAL producer (not a hand-authored trace). Attempt A's
  ;; completion is held; the parent re-enters, attempt B is seeded and RESOLVES;
  ;; THEN A's exact carrier drains against B's already-resolved join. The
  ;; producer runs the `attempt-superseded` exact-attempt gate BEFORE the
  ;; `:resolved?` branch (join.cljc), so A is classified `:attempt-superseded`
  ;; `stale-completion` — NOT a `join-resolved` late-completion. This pins the
  ;; behaviour the bead found the Xray docs/fixtures had framed as
  ;; pre-resolution-only: the `attempt-unverified` / `attempt-superseded`
  ;; suppression fires on the POST-resolution path too, and the Xray consumer
  ;; must see an attempt-suppression arc for attempt A, never a late-completion
  ;; terminal.
  (testing "a superseded straggler arriving AFTER the successor join resolved is
            a stale-completion (:attempt-superseded), and NO late-completion fires"
    (register-machines!)
    (with-trace-recorder!
      [traces {:pred #(contains?
                       #{:rf.machine.spawn-all/stale-completion
                         :rf.machine.spawn-all/late-completion
                         :rf.machine.spawn-all/all-completed}
                       (:operation %))}]
      (rf/dispatch-sync [parent-id [:start]])
      (let [attempt-a (:rf/attempt (join-state))
            auth-a    (join-attempt)]
        (rf/dispatch-sync [parent-id [:abort]])
        (rf/dispatch-sync [parent-id [:start]])
        (let [attempt-b (:rf/attempt (join-state))]
          (is (not= attempt-a attempt-b))
          ;; Attempt B completes and RESOLVES first — the parent has no `:on`
          ;; for `:join/done`, so it stays on `:racing` and the resolved join
          ;; slot survives for the post-resolution probe.
          (rf/dispatch-sync [fixed-child [:go]])
          (is (true? (:resolved? (join-state))) "attempt B resolved")
          ;; NOW A's exact carrier drains, POST-resolution, with attempt-A auth.
          (forged-completion! parent-id auth-a)
          ;; (1) the producer emits a stale-completion, NOT a late-completion.
          (let [ops (map :operation @traces)]
            (is (some #{:rf.machine.spawn-all/stale-completion} ops)
                "the superseded straggler emits stale-completion")
            (is (not-any? #{:rf.machine.spawn-all/late-completion} ops)
                "a post-resolution exact-attempt failure is NOT a late-completion"))
          ;; (2) the stale row carries the :attempt-superseded reason + attempt-A's
          ;; OWN work identity (never attempt B's current one).
          (let [stale  (->> @traces
                            (filter #(= :rf.machine.spawn-all/stale-completion
                                        (:operation %)))
                            first)
                work-a (get-in stale [:tags :rf.reply/work-id])]
            (is (= :rf.machine.spawn-all/attempt-superseded
                   (get-in stale [:tags :rf.reply/stale-reason]))
                "post-resolution exact-attempt failure carries :attempt-superseded")
            (is (= attempt-a (nth work-a 3))
                "the superseded evidence carries attempt A's own token, not B's")
            ;; (3) the Xray consumer classifies attempt A's arc as
            ;; attempt-suppression, and attempt B's resolved arc is untouched.
            (let [arcs  (reply-envelope/races-by-work-id @traces)
                  arc-a (get arcs work-a)]
              (is (contains? (:phases arc-a) :stale-suppressed)
                  "attempt A's arc is a stale-suppression in Xray")
              (is (true? (:suppressed? arc-a)))
              (is (some (fn [[wid arc]]
                          (and (not= wid work-a) (= :ok (:terminal-status arc))))
                        arcs)
                  "attempt B resolved :ok, unaffected by A's post-resolution straggler"))))))))

(deftest cancellation-only-attempt-closes-without-a-stale-carrier
  (testing "destroyed cancellation A and completed reuse B are two closed Xray arcs"
    (register-machines!)
    (with-trace-recorder!
      [traces {:pred #(contains?
                       #{:rf.machine/destroyed
                         :rf.machine.spawn-all/all-completed}
                       (:operation %))}]
      (rf/dispatch-sync [parent-id [:start]])
      (let [attempt-a (:rf/attempt (join-state))]
        ;; Cancel A by leaving the spawn-all state. There is deliberately no
        ;; late/stale completion carrier to manufacture an Xray row for A.
        (rf/dispatch-sync [parent-id [:abort]])
        (rf/dispatch-sync [parent-id [:start]])
        (let [attempt-b (:rf/attempt (join-state))]
          (rf/dispatch-sync [fixed-child [:go]])
          (let [work-a [:rf.work/machine fixed-child [:racing] attempt-a]
                work-b [:rf.work/machine fixed-child [:racing] attempt-b]
                arcs   (reply-envelope/races-by-work-id @traces)
                arc-a  (get arcs work-a)
                arc-b  (get arcs work-b)]
            (is (= #{work-a work-b} (set (keys arcs)))
                "ordinary cancellation and successful reuse both reach Xray")
            (is (= :cancelled (:terminal-status arc-a)))
            (is (= #{:completed} (:phases arc-a))
                "the canonical destroyed reply closes A, not cancel-requested")
            (is (false? (:suppressed? arc-a)))
            (is (= :ok (:terminal-status arc-b)))
            (is (false? (:suppressed? arc-b)))))))))

(deftest folded-child-and-cancelled-sibling-have-one-xray-terminal-each
  (testing "pre-resolution parent exit never adds cancellation to a folded child"
    (register-pre-resolution-exit-machines!)
    (with-trace-recorder!
      [traces {:pred #(contains?
                       #{:rf.machine.spawn-all/child-completed
                         :rf.machine/destroyed}
                       (:operation %))}]
      (rf/dispatch-sync [:xray-work-id/exit-parent [:start]])
      (let [join-state (get-in (rf.frame/frame-runtime-db-value :rf/default)
                               [:rf.runtime/machines :spawned
                                :xray-work-id/exit-parent [:racing]])
            attempt    (:rf/attempt join-state)
            work-a     [:rf.work/machine :xray-work-id/exit-a#7
                        [:racing] attempt]
            work-b     [:rf.work/machine :xray-work-id/exit-b
                        [:racing] attempt]]
        (rf/dispatch-sync [:xray-work-id/exit-a#7 [:go]])
        (rf/dispatch-sync [:xray-work-id/exit-parent [:abort]])
        (let [arcs  (reply-envelope/races-by-work-id @traces)
              arc-a (get arcs work-a)
              arc-b (get arcs work-b)]
          (is (= #{work-a work-b} (set (keys arcs))))
          (is (= :ok (:terminal-status arc-a)))
          (is (= 1 (count (filter #(= :completed (:phase %))
                                  (:rows arc-a))))
              "A contributes exactly one terminal Xray row")
          (is (= :cancelled (:terminal-status arc-b)))
          (is (= 1 (count (filter #(= :completed (:phase %))
                                  (:rows arc-b))))
              "B contributes exactly one cancellation terminal"))))))

(deftest cancelled-then-suppressed-carrier-has-one-xray-terminal
  (testing "an exact carrier after cancellation adds suppression, not a terminal"
    (register-cancel-race-machines!)
    (with-trace-recorder!
      [traces {:pred #(contains?
                       #{:rf.machine/destroyed
                         :rf.machine.spawn-all/stale-completion}
                       (:operation %))}]
      (let [join-state (get-in (rf.frame/frame-runtime-db-value :rf/default)
                               [:rf.runtime/machines :spawned
                                :xray-work-id/cancel-race-parent [:racing]])
            work-id    [:rf.work/machine
                        :xray-work-id/cancel-race-child#7
                        [:racing] (:rf/attempt join-state)]
            ;; Capture the exact-current coordinate while the child is LIVE.
            auth       (join-attempt :xray-work-id/cancel-race-child#7)]
        ;; Cancel the child, THEN let its held carrier land.
        (rf/dispatch-sync [:xray-work-id/cancel-race-parent [:destroy-child]])
        (forged-completion! :xray-work-id/cancel-race-parent auth)
        (let [arc (get (reply-envelope/races-by-work-id @traces) work-id)]
          (is (= #{:completed :stale-suppressed} (:phases arc)))
          (is (= :cancelled (:terminal-status arc))
              "the actual terminal row outranks later suppression evidence")
          (is (= 1 (count (filter #(= :completed (:phase %)) (:rows arc))))
              "destroyed cancellation is the sole terminal-phase row")
          (is (= :cancelled
                 (->> (:rows arc)
                      (filter #(= :completed (:phase %)))
                      first :status)))
          (is (true? (:suppressed? arc))))))))
