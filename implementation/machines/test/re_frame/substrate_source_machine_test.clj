(ns re-frame.substrate-source-machine-test
  "Per rf2-ejtpd — substrate-internal `:source` values stamped at the
  machine-substrate dispatch sites. JVM coverage; the substrate stamp
  paths are platform-agnostic `.cljc` so the assertions hold uniformly
  across CLJS reagent / plain-atom / JVM hosts.

  | new `:source` value | stamped by                            | when                                                    |
  |---------------------|---------------------------------------|---------------------------------------------------------|
  | `:after-timer`      | machine substrate's `:after` timer path| timer's delay elapses + substrate dispatches trigger   |
  | `:machine-spawn`    | spawn-fx + `:rf.machine/spawn` handler | substrate dispatches the spawned actor's `:start` event|
  | `:always`           | `:rf.machine.microstep/transition` trace| stamped on every `:always` microstep trace event      |

  The `:always` value is NOT observable on a dispatch envelope (an
  `:always` microstep does not produce its own envelope — it runs
  intra-macrostep per Spec 005 §Microstep loop within drain); the value
  is stamped on the per-microstep trace event so the trigger-kind
  vocabulary is uniform across substrate surfaces (Spec-Schemas
  §`:rf/dispatch-envelope`).

  The after-timer test stubs `re-frame.interop/schedule-after!` to
  invoke the callback synchronously — bypasses the
  ScheduledExecutorService while still routing through the substrate's
  real stamp site in `re-frame.machines.timer`."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            ;; Load the machines artefact so the late-bind hooks for
            ;; `rf/reg-machine`, the `:rf.machine/spawn` / `:rf.machine/destroy`
            ;; reserved fxs, and the `:after`-timer surface register at ns load.
            [re-frame.machines]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.test-support :as test-support]
            [re-frame.trace.tooling :as trace-tooling]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; ---- :after-timer --------------------------------------------------------

(deftest after-timer-stamps-source-after-timer
  (testing ":after timer's substrate-dispatch stamps :source :after-timer"
    ;; Stub schedule-after! to invoke the callback synchronously — this
    ;; bypasses the ScheduledExecutorService while still routing the
    ;; substrate's real dispatch path (the stamp site lives in
    ;; re-frame.machines.timer; the executor only governs timing).
    (let [seen (atom [])]
      (trace-tooling/register-listener! ::after-src
        (fn [ev] (when (= :rf.event/dispatched (:operation ev))
                   (swap! seen conj ev))))
      (try
        (with-redefs [interop/schedule-after! (fn [f _ms] (f) :stub-handle)]
          (let [machine
                {:initial :idle
                 :data    {}
                 :states
                 {:idle    {:on {:fetch :loading}}
                  :loading {:after {1000 :timeout}
                            :on    {:loaded :ready}}
                  :timeout {}
                  :ready   {}}}]
            (rf/reg-machine :after-source/flow machine)
            ;; Entering :loading schedules the timer; the stub runs the
            ;; callback immediately, dispatching the synthetic
            ;; [parent-id [:rf.machine.timer/after-elapsed ...]] event
            ;; with :source :after-timer.
            (rf/dispatch-sync [:after-source/flow [:fetch]])))
        (let [timer-ev (first
                         (filter (fn [ev]
                                   (let [v (get-in ev [:tags :rf.event/v])]
                                     (and (vector? v)
                                          (vector? (second v))
                                          (= :rf.machine.timer/after-elapsed
                                             (first (second v))))))
                                 @seen))]
          (is (some? timer-ev)
              "the substrate dispatched :rf.machine.timer/after-elapsed via the timer-fire path")
          (is (= :after-timer (:source timer-ev))
              ":source :after-timer stamped on the timer dispatch (rf2-ejtpd)")
          (is (= [:after-source/flow [:rf.machine.timer/after-elapsed 1000 1 [:loading]]]
                 (get-in timer-ev [:tags :rf.event/v]))
              "the dispatched event vector carries the machine id + synthetic trigger")
          ;; The pre-rf2-ejtpd :rf/dispatch-origin :timer tag MUST also
          ;; ride alongside — the two axes are independent (per spec
          ;; 002 §Dispatch origin tagging).
          (is (= :timer (get-in timer-ev [:tags :rf/dispatch-origin]))
              ":rf/dispatch-origin :timer still stamped (preserved rf2-t1lxr behaviour)"))
        (finally (trace-tooling/unregister-listener! ::after-src))))))

;; ---- :machine-spawn ------------------------------------------------------

(deftest machine-spawn-stamps-source-machine-spawn
  (testing "machine spawn fx stamps :source :machine-spawn on the spawned actor's first event"
    (let [parent-machine
          {:initial :idle
           :data    {:credentials {:user "alice"}}
           :on-spawn-actions
           {:rec/store-id (fn [{data :data id :id}] (assoc data :pending id))}
           :states
           {:idle
            {:on {:submit :spawning}}
            :spawning
            {:spawn {:machine-id :child-source/worker
                      :data       {}
                      :on-spawn   :rec/store-id
                      :start      [:begin]}
             :on    {:done :finished
                     :fail :idle}}
            :finished {}}}
          child-machine
          {:initial :running
           :data    {}
           :states  {:running {:on {:begin :working}}
                     :working {}}}
          seen (atom [])]
      (rf/reg-machine :parent-source/proto parent-machine)
      (rf/reg-machine :child-source/worker  child-machine)
      (trace-tooling/register-listener! ::spawn-src
        (fn [ev] (when (= :rf.event/dispatched (:operation ev))
                   (swap! seen conj ev))))
      (try
        ;; Enter :spawning — substrate spawns the child + dispatches
        ;; [<child-id> [:begin]] into the new actor.
        (rf/dispatch-sync [:parent-source/proto [:submit]])
        (let [spawn-ev (first
                         (filter (fn [ev]
                                   (let [v (get-in ev [:tags :rf.event/v])]
                                     (and (vector? v)
                                          (= [:begin] (second v)))))
                                 @seen))]
          (is (some? spawn-ev)
              "the substrate dispatched the spawned actor's :start event")
          (is (= :machine-spawn (:source spawn-ev))
              ":source :machine-spawn stamped on the spawn dispatch (rf2-ejtpd)"))
        (finally (trace-tooling/unregister-listener! ::spawn-src))))))

(deftest machine-spawn-synthetic-spawned-stamps-source-machine-spawn
  (testing "machine spawn fx stamps :source :machine-spawn on the synthetic [:rf.machine/spawned] event"
    ;; When the spawn args omit :start, the runtime dispatches
    ;; [<child-id> [:rf.machine/spawned]] (rf2-ijm7) — same stamp path.
    (let [parent-machine
          {:initial :idle
           :data    {}
           :on-spawn-actions
           {:rec/store-id (fn [{data :data id :id}] (assoc data :pending id))}
           :states
           {:idle     {:on {:submit :spawning}}
            :spawning {:spawn {:machine-id :child-source2/proto
                                :data       {}
                                :on-spawn   :rec/store-id}}}}
          child-machine
          {:initial :ready
           :data    {}
           :states  {:ready {}}}
          seen (atom [])]
      (rf/reg-machine :parent-source2/proto parent-machine)
      (rf/reg-machine :child-source2/proto  child-machine)
      (trace-tooling/register-listener! ::spawn-src2
        (fn [ev] (when (= :rf.event/dispatched (:operation ev))
                   (swap! seen conj ev))))
      (try
        (rf/dispatch-sync [:parent-source2/proto [:submit]])
        (let [synthetic-ev (first
                             (filter (fn [ev]
                                       (let [v (get-in ev [:tags :rf.event/v])]
                                         (and (vector? v)
                                              (= [:rf.machine/spawned] (second v)))))
                                     @seen))]
          (is (some? synthetic-ev)
              "the substrate dispatched the synthetic [:rf.machine/spawned] event")
          (is (= :machine-spawn (:source synthetic-ev))
              ":source :machine-spawn stamped on the synthetic spawn dispatch (rf2-ejtpd)"))
        (finally (trace-tooling/unregister-listener! ::spawn-src2))))))

;; ---- :always (microstep trace) ------------------------------------------

(deftest always-microstep-trace-carries-source-always
  (testing ":always microstep stamps :source :always on the :rf.machine.microstep/transition trace"
    ;; `:always` runs intra-macrostep — it does not produce its own
    ;; dispatch envelope. The microstep trace is where the closed-set
    ;; value is observable.
    (let [machine
          {:initial :a
           :data    {:ready? false}
           :guards  {:ready?     (fn [{data :data}] (:ready? data))
                     :not-ready? (fn [{data :data}] (not (:ready? data)))}
           :actions {:flip-ready (fn [{data :data}] {:data (assoc data :ready? true)})}
           :states
           {:a {:on {:go :b}}
            :b {:entry  :flip-ready
                :always [{:guard :ready? :target :c}]}
            :c {}}}
          microsteps (atom [])]
      (rf/reg-machine :always-source/flow machine)
      (trace-tooling/register-listener! ::always-src
        (fn [ev]
          (when (= :rf.machine.microstep/transition (:operation ev))
            (swap! microsteps conj ev))))
      (try
        ;; Enter :b — entry action flips :ready?; the :always guard
        ;; passes; microstep fires :b → :c.
        (rf/dispatch-sync [:always-source/flow [:go]])
        (is (seq @microsteps)
            "at least one :always microstep trace was emitted")
        (let [step (first @microsteps)]
          (is (= :always (:source step))
              ":source :always stamped on the microstep trace (rf2-ejtpd)")
          (is (= :b (get-in step [:tags :from])))
          (is (= :c (get-in step [:tags :to]))))
        (finally (trace-tooling/unregister-listener! ::always-src))))))
