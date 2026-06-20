(ns re-frame.machine-after-timer-fresh-causal-token-test
  "Per EP-0010 §Dispatch Envelope Stamping + Spec 002 §The World-Input Rule.

  THE CONTRACT. A machine `:after` timer fires a synthetic
  `[<parent-id> [:rf.machine.timer/after-elapsed ...]]` dispatch back into
  the parent machine. That dispatch is a DISPATCH ENVELOPE in its own right
  — and per EP-0010 every dispatch envelope stamps or supplies its OWN
  `:rf.cofx` at its OWN causal boundary. Timer/child tokens MUST
  NOT inherit a parent's `:time-ms`: the timer-fire envelope is a DISTINCT
  causal token, stamped fresh at FIRE time.

  THE IMPL (verified correct, NOT changed by this test). `timer.cljc`'s
  `schedule-after-timer!` installs the host-clock callback via
  `interop/schedule-after!`; that callback calls the late-bound
  `:router/dispatch!` with `{:frame ... :source :after-timer}` and
  DELIBERATELY supplies NO `:rf.cofx`. So the router (build-envelope)
  stamps a fresh `{:rf/time-ms (interop/epoch-now-ms)}` at fire time — the
  timer-driven guard/action reads the FIRE-time token, not the scheduling-
  time parent token.

  THE GAP this test closes. Every case in `machine_world_inputs_ctx_test`
  proves a callback reads a SCRIPTED token supplied verbatim on a
  `dispatch-sync`. NONE drive the REAL `:after` timer-fire boundary, where
  the token is router-STAMPED (not supplied). `after_test.clj` drives the
  `:after` semantics by dispatching the synthetic `after-elapsed` event by
  HAND (no `:rf.cofx`), so it never exercises the stamping either. A
  regression that made the timer callback INHERIT the parent/scheduling
  token (e.g. by threading the entry dispatch's `:rf.cofx` through to
  the `after-elapsed` dispatch opts) would pass every existing test. This
  test fails on exactly that regression.

  THE RECIPE (adversarial clock separation).
    1. Stub `interop/schedule-after!` to CAPTURE the timer-callback thunk
       (rather than scheduling it on the host clock) so the test owns the
       fire boundary deterministically.
    2. Stub `interop/epoch-now-ms` to read a MUTABLE clock atom — the
       router's fire-time stamp source.
    3. Enter the `:after`-bearing state under a parent dispatch supplying a
       known PARENT `:time-ms`. The clock atom holds the PARENT value at
       this point; the timer is armed (thunk captured, NOT fired).
    4. ADVANCE the clock atom to a distinct CHILD value — adversarially
       separating fire-time from schedule/enqueue-time.
    5. Route the late-bound `:router/dispatch!` through `dispatch-sync!`
       (the captured thunk is invoked OUTSIDE any handler, so sync routing
       is legal) and invoke the captured thunk — the real timer-fire path.
    6. Assert the `:after` transition's guard AND action saw the CHILD
       (fire-time, router-stamped) `:time-ms`, NOT the PARENT scripted one."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.interop :as interop]
            [re-frame.late-bind :as late-bind]
            ;; Loaded for its boot side-effect: installs the machines
            ;; artefact's late-bind hooks (`rf/reg-machine` requires them).
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.router :as router]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

;; Two clearly-non-ambient, clearly-distinct sentinels. PARENT is the
;; scripted token on the state-entry dispatch; CHILD is what the mutable
;; clock returns once we advance it before the timer fires. If the timer
;; callback INHERITED the parent token, the guard/action would read PARENT;
;; the correct (fresh-stamp) behaviour reads CHILD.
(def ^:private PARENT-TIME-MS 1000000000)
(def ^:private CHILD-TIME-MS  2000000000)

(deftest after-timer-fire-stamps-fresh-causal-token
  (testing "the :after timer's dispatched event carries a FRESH router-
            stamped :rf.cofx :time-ms at FIRE time (not the parent
            scheduling-time token) — driven through the REAL
            interop/schedule-after! fire boundary (rf2-hg39nf)"
    (let [;; The mutable host clock the router's epoch-now-ms reads. Starts
          ;; at the PARENT value; advanced to CHILD between schedule + fire.
          clock           (atom PARENT-TIME-MS)
          ;; The timer-callback thunk captured by the stubbed schedule-after!.
          captured-thunk  (atom nil)
          ;; What the :after-transition guard + action actually observed off
          ;; (:rf.cofx ctx) at fire time.
          guard-saw       (atom ::unset)
          action-saw      (atom ::unset)
          ;; The machine: :loading bears an :after whose transition both
          ;; guards (recording + always-pass) and acts (recording + folding
          ;; the causal :time-ms into a durable :data write).
          m {:initial :idle
             :data    {}
             :guards  {:capture-fire-time
                       (fn [{cofx :rf.cofx}]
                         (reset! guard-saw (:rf/time-ms cofx))
                         true)}
             :actions {:stamp-fire-time
                       (fn [{cofx :rf.cofx}]
                         (reset! action-saw (:rf/time-ms cofx))
                         {:data {:fired-at (:rf/time-ms cofx)}})}
             :states  {:idle    {:on {:fetch :loading}}
                       :loading {:after {5000 {:target :timeout
                                               :guard  :capture-fire-time
                                               :action :stamp-fire-time}}}
                       :timeout {}}}
          ;; Preserve the production hooks/clock we are about to redefine.
          orig-schedule   interop/schedule-after!
          orig-dispatch!  (late-bind/get-fn :router/dispatch!)]
      (rf/reg-machine :after-fresh/m m)
      (with-redefs [;; Capture the callback thunk instead of arming a real
                    ;; host timer — the test owns the fire boundary.
                    interop/schedule-after!
                    (fn [f _ms] (reset! captured-thunk f) ::stub-handle)
                    ;; The router's fire-time stamp source — reads the
                    ;; mutable clock so advancing it shifts the fresh stamp.
                    interop/epoch-now-ms (fn [] @clock)]
        ;; (1) Enter :loading under the PARENT token. The clock holds PARENT,
        ;; so even if the timer-fire wrongly inherited the parent token it
        ;; would equal PARENT here — but we ADVANCE the clock before firing,
        ;; so a fresh stamp MUST diverge.
        (rf/dispatch-sync [:after-fresh/m [:fetch]]
                          {:rf.cofx {:rf/time-ms PARENT-TIME-MS}})
        (is (= :loading (:state (snapshot :after-fresh/m)))
            "entered the :after-bearing state")
        (is (some? @captured-thunk)
            "the :after schedule armed a host-clock callback (captured)")

        ;; (2) Adversarial separation: the wall clock moves on between
        ;; arming the timer and the timer firing.
        (reset! clock CHILD-TIME-MS)

        ;; (3) Fire the REAL callback. It runs OUTSIDE any handler now (the
        ;; entry dispatch-sync has returned), so routing :router/dispatch!
        ;; through dispatch-sync! is legal and keeps the cascade inline +
        ;; deterministic on the JVM. The thunk calls dispatch! with
        ;; {:frame ... :source :after-timer} and NO :rf.cofx, so the
        ;; router stamps {:rf/time-ms @clock} = CHILD afresh.
        (try
          (late-bind/set-fn! :router/dispatch! router/dispatch-sync!)
          (@captured-thunk)
          (finally
            (late-bind/set-fn! :router/dispatch! orig-dispatch!))))

      ;; (4) The transition actually fired through the real boundary.
      (is (= :timeout (:state (snapshot :after-fresh/m)))
          "the :after timer-fire drove the transition :loading → :timeout")

      ;; (5) THE CONTRACT: both callbacks saw the FRESH FIRE-TIME token —
      ;; CHILD — not the PARENT scheduling-time token. This is the
      ;; assertion that fails if the timer callback ever inherits the
      ;; parent envelope's :rf.cofx.
      (is (= CHILD-TIME-MS @guard-saw)
          "the :after guard read the FRESH fire-time :time-ms (router-
           stamped at fire), NOT the parent scheduling-time token")
      (is (= CHILD-TIME-MS @action-saw)
          "the :after action read the FRESH fire-time :time-ms")
      (is (not= PARENT-TIME-MS @action-saw)
          "the timer-fire token did NOT inherit the parent token — fresh,
           distinct causal token per EP-0010 §Dispatch Envelope Stamping")
      (is (= CHILD-TIME-MS (:fired-at (:data (snapshot :after-fresh/m))))
          "the durable :data write folded the fresh fire-time :time-ms — a
           replay-stable causal write keyed off the timer-fire token")

      ;; sanity-restore guard (with-redefs already restored the clock + stub)
      (is (identical? orig-schedule interop/schedule-after!)
          "interop/schedule-after! restored after the redef scope"))))
