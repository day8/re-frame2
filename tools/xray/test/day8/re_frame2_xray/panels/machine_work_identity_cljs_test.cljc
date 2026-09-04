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

(defn- join-attempt []
  (get-in (rf.frame/frame-runtime-db-value :rf/default)
          [:rf.runtime/machines :snapshots fixed-child :data :rf/join-child]))

(defn- register-machines! []
  (rf/reg-machine
    child-type
    {:initial :running
     :actions {:complete (fn [_]
                           {:fx [[:dispatch
                                  [parent-id [:child/done :only]]]]})}
     :states {:running {:on {:go {:target :done :action :complete}}}
              :done {}}})
  (rf/reg-machine
    parent-id
    {:initial :idle
     :states {:idle {:on {:start :racing}}
              :racing
              {:spawn-all
               {:children [{:id :only :machine-id child-type
                            :fixed-actor-id fixed-child}]
                :join :all
                :on-child-done :child/done
                :on-child-error :child/error
                :on-all-complete [:join/done]}
               ;; Keep the resolved join record live; `:abort` is the explicit
               ;; re-entry boundary that tears A down before B is seeded.
               :on {:abort :idle}}}}))

(defn- register-pre-resolution-exit-machines! []
  (let [parent :xray-work-id/exit-parent
        child  {:initial :running
                :data    {:id nil}
                :actions {:remember (fn [{data :data event :event}]
                                      {:data (assoc data :id (second event))})
                          :complete (fn [{data :data}]
                                      {:fx [[:dispatch
                                             [parent [:child/done (:id data)]]]]})}
                :states {:running {:on {:set-id {:action :remember}
                                        :go {:target :done :action :complete}}}
                         :done {}}}]
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
                  :on-child-done :child/done
                  :on-child-error :child/error
                  :on-all-complete [:join/done]}
                 :on {:abort :idle}}}})))

(defn- register-cancel-race-machines! []
  (let [parent :xray-work-id/cancel-race-parent
        actor  :xray-work-id/cancel-race-child#7]
    (rf/reg-machine
      :xray-work-id/cancel-race-child-type
      {:initial :running
       :actions {:queue-then-destroy
                 (fn [_]
                   {:fx [[:dispatch [parent [:child/done :only]]]
                         [:rf.machine/destroy actor]]})}
       :states {:running {:on {:go {:target :done
                                    :action :queue-then-destroy}}}
                :done {}}})
    (rf/reg-machine
      parent
      {:initial :idle
       :states {:idle {:on {:start :racing}}
                :racing
                {:spawn-all
                 {:children [{:id :only
                              :machine-id :xray-work-id/cancel-race-child-type
                              :fixed-actor-id actor}]
                  :join :all
                  :on-child-done :child/done
                  :on-child-error :child/error
                  :on-all-complete [:join/done]}}}})
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

          ;; The unchanged PUBLIC completion event is delivered with the
          ;; framework's carried recordable join-attempt coordinate for attempt A.
          (rf/dispatch-sync
            [parent-id [:child/done :only]]
            {:rf.cofx {:rf.machine/join-attempt auth-a}})
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
          (rf/dispatch-sync
            [parent-id [:child/done :only]]
            {:rf.cofx {:rf.machine/join-attempt auth-a}})
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
                        [:racing] (:rf/attempt join-state)]]
        (rf/dispatch-sync [:xray-work-id/cancel-race-child#7 [:go]])
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
