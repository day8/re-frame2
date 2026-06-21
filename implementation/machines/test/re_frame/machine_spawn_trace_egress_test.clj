(ns re-frame.machine-spawn-trace-egress-test
  "Spawn-trace egress projection.

  Three RAW child-owned payloads that could leave through the parent / spawn
  trace surfaces are projected at the egress chokepoint
  (`re-frame.classification/project-trace-event`), like every other machine `:data`
  slot:

   1. **`:start` payload.** The accepted spawn path emits
      `:rf.machine.spawn/spawned` carrying `:start (:start args)`. A child's
      `:start` event can hold credentials / large payloads, so
      `project-machine-tags` summarizes `:start` at egress (the same posture
      `reject-unregistered-spawn!` takes by omitting all spawn args).

   2/3. **synthetic `:on-error` payload.** When a spawned child FAILS, the
      runtime dispatches the reserved event
      `[:rf.machine.spawn/error <invoke-id> <error>]` into the parent. `<error>`
      is CHILD-owned: the child's raw `:output-key` result (error-leaf trigger)
      or an exception envelope including `:exception-data` (action-exception
      trigger). The parent's `:rf.machine/event-received` / `:rf.machine/
      transition` carry that event vector under `:event`, and the parent's
      `:rf.machine/guard-evaluated` / `:rf.machine/action-ran` carry it under
      `:input :event`. The event-vector projection keys off the PARENT
      event-id's marks (`:rf.machine.spawn/error` is a reserved id with no
      marks), and the payload sits at the THIRD vector position, so the
      projection summarizes the child error at egress.

  Parent control flow stays RAW LOCAL (the transition reads the raw error off
  `:event` via `(nth ev 2)`); the synthetic on-error payload + the `:start`
  payload are PROJECTED at trace egress only. The posture is the same
  conservative fail-closed seam `project-machine-error-tags` uses: a
  child-owned payload that cannot be classified against the parent's marks is
  summarized to the `:rf/redacted` sentinel before it crosses the bus /
  epoch-capture / AI-MCP egress boundary."
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.classification :as classification]))

;; ---- shared helpers -------------------------------------------------------

(def ^:private parent-id :rf.spawn-egress/supervisor)
(def ^:private invoke-id [:working])

(defn- sensitive-error
  "A child-owned error payload that could carry app secrets — what the runtime
  dispatches as the third element of the synthetic spawn-error event."
  []
  {:rf.error/id       :rf.error/machine-action-exception
   :actor-id          :rf.spawn-egress/worker#1
   :action-id         :charge-card
   :exception-message "boom"
   ;; the developer's arbitrary ex-data — could embed the same app secrets a
   ;; sensitive machine's :data marks gate
   :exception-data    {:card "4111-1111-1111-1111" :token "secret-child-jwt"}
   :reason            "uncaught child action exception"})

(defn- raw-child-output
  "A child :output-key result (the error-leaf trigger) — also child-owned."
  []
  {:account "acct-99" :secret "secret-output-leaf"})

;; ---- :start payload on :rf.machine.spawn/spawned -------------------------

(deftest spawned-start-payload-redacted-in-egress
  (testing "rf2-mxboxi — the :rf.machine.spawn/spawned trace's :start payload is
            summarized at egress (it can hold credentials / large data the way
            reject-unregistered-spawn! already refuses to carry)"
    (let [ev   {:operation :rf.machine.spawn/spawned
                :tags      {:frame      :rf/default
                            :machine-id :rf.spawn-egress/worker-type
                            :spawned-id :rf.spawn-egress/worker#1
                            :invoke-id  invoke-id
                            :start      [:begin {:token "secret-start-jwt"
                                                 :password "hunter2"}]}}
          out  (classification/project-trace-event ev)
          tags (:tags out)]
      ;; structural slots survive — consumers locate the spawn
      (is (= :rf.spawn-egress/worker#1 (:spawned-id tags)))
      (is (= invoke-id (:invoke-id tags)))
      ;; the start payload must NOT egress raw
      (is (not (.contains (pr-str out) "secret-start-jwt"))
          "no raw :start credential leaked into the projected spawn trace")
      (is (not (.contains (pr-str out) "hunter2"))
          "no raw :start password leaked"))))

;; ---- synthetic on-error payload at the parent -----------------------------

(defn- spawn-error-event [error]
  ;; the inner event the parent handler sees after the router strips parent-id
  [:rf.machine.spawn/error invoke-id error])

(deftest event-received-spawn-error-payload-redacted
  (testing "rf2-0gdic7/lft14p — :rf.machine/event-received carrying the
            synthetic spawn-error event has its child-owned error payload
            (3rd element) summarized at egress; the reserved id + invoke-id
            survive so the trace is still locatable"
    (let [err  (sensitive-error)
          ev   {:operation :rf.machine/event-received
                :tags      {:machine-id parent-id
                            :frame      :rf/default
                            :event      (spawn-error-event err)}}
          out  (classification/project-trace-event ev)
          tags (:tags out)
          proj-event (:event tags)]
      (is (= :rf.machine.spawn/error (first proj-event))
          "reserved event-id survives")
      (is (= invoke-id (second proj-event))
          "the invoke-id (routing key) survives")
      (is (not (.contains (pr-str out) "4111-1111-1111-1111"))
          "no raw child card number leaked through the parent event-received trace")
      (is (not (.contains (pr-str out) "secret-child-jwt"))
          "no raw child :exception-data token leaked"))))

(deftest transition-spawn-error-payload-redacted
  (testing "rf2-0gdic7/lft14p — :rf.machine/transition carrying the synthetic
            spawn-error event under :event summarizes the child error payload"
    (let [err  (raw-child-output)
          ev   {:operation :rf.machine/transition
                :tags      {:actor-id parent-id
                            :frame    :rf/default
                            :event    (spawn-error-event err)
                            :before   {:state :working :data {}}
                            :after    {:state :failed  :data {}}}}
          out  (classification/project-trace-event ev)
          proj-event (get-in out [:tags :event])]
      (is (= :rf.machine.spawn/error (first proj-event)))
      (is (= invoke-id (second proj-event)))
      (is (not (.contains (pr-str out) "secret-output-leaf"))
          "no raw child :output-key result leaked through the parent transition trace"))))

(deftest guard-evaluated-spawn-error-input-event-redacted
  (testing "rf2-0gdic7/lft14p — a parent :on-error guard's :rf.machine/
            guard-evaluated carries the synthetic event under :input :event;
            its child error payload is summarized at egress"
    (let [err  (sensitive-error)
          ev   {:operation :rf.machine/guard-evaluated
                :tags      {:actor-id parent-id
                            :frame    :rf/default
                            :guard-id :retryable?
                            :state    :working
                            :outcome  :pass
                            :input    {:data  {}
                                       :event (spawn-error-event err)}}}
          out  (classification/project-trace-event ev)
          proj-event (get-in out [:tags :input :event])]
      (is (= :rf.machine.spawn/error (first proj-event)))
      (is (= invoke-id (second proj-event)))
      (is (not (.contains (pr-str out) "4111-1111-1111-1111"))
          "no raw child card number leaked through the parent guard :input :event")
      (is (not (.contains (pr-str out) "secret-child-jwt"))))))

(deftest action-ran-spawn-error-input-event-redacted
  (testing "rf2-0gdic7/lft14p — a parent :on-error action's :rf.machine/
            action-ran carries the synthetic event under :input :event;
            its child error payload is summarized at egress"
    (let [err  (sensitive-error)
          ev   {:operation :rf.machine/action-ran
                :tags      {:actor-id  parent-id
                            :frame     :rf/default
                            :action-id :record-failure
                            :phase     :transition
                            :outcome   :ok
                            :input     {:data  {}
                                        :event (spawn-error-event err)}}}
          out  (classification/project-trace-event ev)
          proj-event (get-in out [:tags :input :event])]
      (is (= :rf.machine.spawn/error (first proj-event)))
      (is (not (.contains (pr-str out) "4111-1111-1111-1111")))
      (is (not (.contains (pr-str out) "secret-child-jwt"))))))

;; ---- precision: a NORMAL (non-spawn-error) machine event is untouched -----

(deftest normal-machine-event-not-summarized
  (testing "the spawn-error projection is PRECISE — a normal parent event
            vector (not the reserved :rf.machine.spawn/error id) rides
            untouched through the machine trace (its own marks, if any, still
            apply via project-event-tags)"
    (let [ev   {:operation :rf.machine/event-received
                :tags      {:machine-id parent-id
                            :frame      :rf/default
                            ;; a plain app event with a plain payload
                            :event      [:user/login {:user "alice"}]}}
          out  (classification/project-trace-event ev)
          proj-event (get-in out [:tags :event])]
      (is (= [:user/login {:user "alice"}] proj-event)
          "a non-spawn-error machine event vector is not summarized"))))
