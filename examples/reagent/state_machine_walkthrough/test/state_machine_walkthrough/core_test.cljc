(ns state-machine-walkthrough.core-test
  "Headless tests for state-machine-walkthrough.core — chapter §Headless
  testing. Kept out of the example source so the example a learner reads is
  pure demonstrative code (the example tree is test-free by convention; the
  same split realworld / nine_states / boot / long_running_work use).

  Two flavours of test:

  1. Pure machine-transition: pass a snapshot + event, get back the next
     snapshot. No frame, no app-db, no fx execution. JVM-runnable.

  2. Drain-level: spin up a frame with a `:fx-overrides` map that redirects
     `:rf.http/managed` to a per-test stub, dispatch into the machine id,
     read the resulting app-db slice. Exercises the full
     registration → drain → snapshot-write path including the
     `:on-success` / `:on-failure` callback fold.

  Driven on the JVM by re-frame.examples-test and under CLJS node-test via
  re-frame.state-machine-walkthrough-cljs-test."
  (:require [re-frame.core :as rf]
            [re-frame.machines.result :as result]
            [state-machine-walkthrough.core :as core]))

;; The `:auth.login/canned-success` and `:auth.login/canned-failure` stubs
;; used by the drain tests below are registered in
;; `state-machine-walkthrough.core` (loaded via the `:as core` require above)
;; so both the browser demo and the tests share one registration point.

;; ============================================================================
;; HEADLESS TESTS — chapter §Headless testing
;; ============================================================================

(defn pure-happy-path-test
  "Drives the transition table directly via machine-transition. No
  frame, no app-db. The chapter's first test."
  []
  (let [s0 {:state :idle :data {:attempts 0 :error nil}}
        {s1 ::result/snap fx1 ::result/fx}
        (rf/machine-transition core/login-flow s0
                               [:auth.login/submit
                                {:email "a@b.com" :password "secret"}])]
    (assert (= :submitting (:state s1))
            (str "expected :submitting, got " (:state s1)))
    ;; Entering :submitting fires the :issue-request action's :fx.
    (assert (= 1 (count fx1)) (str "expected one :rf.http/managed fx, got " fx1))
    (assert (= :rf.http/managed (ffirst fx1))
            (str "expected :rf.http/managed fx-id, got " (ffirst fx1)))

    (let [{s2 ::result/snap} (rf/machine-transition core/login-flow s1
                                                    [:auth.login/success {:value {:token "t"}}])]
      (assert (= :authed (:state s2))
              (str "expected :authed, got " (:state s2))))
    :ok))

(defn pure-lockout-test
  "Once :data :attempts reaches the retry limit, the :under-retry-limit
  guard fails and the second :auth.login/failure clause's :locked-out
  target wins. The guard checks the snapshot BEFORE the action runs;
  :record-error then bumps the counter on hits, so attempts=3 is the
  first counter value at which the guard rejects."
  []
  (let [snapshot {:state :submitting :data {:attempts 3 :error nil}}
        {s ::result/snap}
        (rf/machine-transition core/login-flow snapshot
                               [:auth.login/failure
                                {:failure {:kind :rf.http/http-4xx
                                           :message "bad creds"}}])]
    (assert (= :locked-out (:state s))
            (str "expected :locked-out at attempts=3, got " (:state s)))
    :ok))

(defn drain-happy-path-test
  "Full drain: registers the machine, dispatches into it, asserts the
  app-db landed at :authed. Uses the `:fx-overrides` seam to swap
  `:rf.http/managed` for the per-test canned-success stub."
  []
  (let [f (rf/make-frame {:fx-overrides {:rf.http/managed :auth.login/canned-success}})]
    (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                          {:email "a@b.com"
                                           :password "secret"}]]
                      {:frame f})
    (let [state (rf/compute-sub [:auth.login/state] (rf/app-db-value f))]
      (assert (= :authed state)
              (str "expected :authed after canned success, got " state)))
    :ok))

(defn drain-retry-then-lockout-test
  "Three failures cycle :submitting → :error-shown → :idle ×3, then a
  fourth :submit fails the guard and lands at :locked-out.

  Uses `rf/with-fx-overrides` — the lexical-scope counterpart to
  the per-frame `:fx-overrides` opt on `make-frame`: every dispatch
  inside the macro body inherits the override map, so the seven
  identical `:rf.http/managed` swaps don't need to thread the override
  through each call. Composes with `with-frame`."
  []
  (let [f (rf/make-frame {})]
    (rf/with-fx-overrides {:rf.http/managed :auth.login/canned-failure}
      (dotimes [_ 3]
        (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                              {:email "x@y.z" :password "wrong"}]]
                          {:frame f})
        (rf/dispatch-sync [:auth.login/flow [:auth.login/dismiss]] {:frame f}))
      (rf/dispatch-sync [:auth.login/flow [:auth.login/submit
                                            {:email "x@y.z" :password "wrong"}]]
                        {:frame f}))
    (let [state (rf/compute-sub [:auth.login/state] (rf/app-db-value f))]
      (assert (= :locked-out state)
              (str "expected :locked-out on 4th attempt, got " state)))
    :ok))

(defn smoke-tests
  "Run all four headless tests. Returns :ok or throws."
  []
  (pure-happy-path-test)
  (pure-lockout-test)
  (drain-happy-path-test)
  (drain-retry-then-lockout-test)
  :ok)
