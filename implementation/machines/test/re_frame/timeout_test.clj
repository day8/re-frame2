(ns re-frame.timeout-test
  "EP-0029 A4 — state-level + spawn-level `:timeout` / `:on-timeout`
  (delayed transitions).

  Covers:
    - the duration grammar (integer-ms OR ISO-8601 only; the XState
      `\"5s\"` / `\"10ms\"` shorthand is REJECTED — operator-ruled
      divergence);
    - registration-time fail-loud validation (timeout requires on-timeout
      and vice-versa; bad duration; :after collision);
    - desugaring `:timeout` / `:on-timeout` onto the existing `:after`
      timer mechanism (distinct intent, ONE mechanism);
    - the dispatch boundary — the timeout actually arms an `:after`
      timer on state entry and the synthetic timer-elapsed event fires the
      `:on-timeout` transition through the real runtime;
    - `:timeout` and `:after` coexisting on the same state node.

  The dispatch-boundary tests dispatch the synthetic
  `[:rf.machine.timer/after-elapsed delay-key epoch decl-path]` event
  manually (the same way after_test.clj does) so verification is
  deterministic without depending on setTimeout firing — the desugared
  timeout IS an `:after` timer, so the synthetic-event path exercises it
  end-to-end."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as mtest]
            [re-frame.machines.timeout :as timeout]
            [re-frame.substrate.plain-atom :as plain-atom]
            [re-frame.subs]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

(def ^:private snapshot mtest/snapshot)

;; ---- duration grammar -----------------------------------------------------

(deftest duration-integer-ms
  (testing "a positive integer is literal ms"
    (is (= 5000 (timeout/resolve-duration-ms 5000)))
    (is (= 1    (timeout/resolve-duration-ms 1))))
  (testing "a non-positive / non-integer number resolves to nil"
    (is (nil? (timeout/resolve-duration-ms 0)))
    (is (nil? (timeout/resolve-duration-ms -5)))
    (is (nil? (timeout/resolve-duration-ms 1.5)))))

(deftest duration-iso-8601
  (testing "ISO-8601 durations resolve to ms"
    (is (= 5000    (timeout/resolve-duration-ms "PT5S")))
    (is (= 120000  (timeout/resolve-duration-ms "PT2M")))
    (is (= 5400000 (timeout/resolve-duration-ms "PT1H30M")))
    (is (= 500     (timeout/resolve-duration-ms "PT0.5S")))
    (is (= 86400000 (timeout/resolve-duration-ms "P1D")))
    (is (= 1800000  (timeout/resolve-duration-ms "PT30M"))))
  (testing "lower-case `pt5s` is accepted (case-insensitive)"
    (is (= 5000 (timeout/resolve-duration-ms "pt5s"))))
  (testing "the bare `P` (no component) is not a duration"
    (is (nil? (timeout/resolve-duration-ms "P")))
    (is (nil? (timeout/resolve-duration-ms "PT")))))

(deftest duration-rejects-xstate-shorthand
  (testing "the XState `5s` / `10ms` shorthand is REJECTED (operator-ruled divergence)"
    (is (nil? (timeout/resolve-duration-ms "5s")))
    (is (nil? (timeout/resolve-duration-ms "10ms")))
    (is (nil? (timeout/resolve-duration-ms "2m")))
    (is (nil? (timeout/resolve-duration-ms "1h"))))
  (testing "other malformed / non-duration forms resolve to nil"
    (is (nil? (timeout/resolve-duration-ms "soon")))
    (is (nil? (timeout/resolve-duration-ms "")))
    (is (nil? (timeout/resolve-duration-ms nil)))
    (is (nil? (timeout/resolve-duration-ms [1000])))
    (is (nil? (timeout/resolve-duration-ms (fn [_] 5000))))))

;; ---- registration-time fail-loud validation -------------------------------

(defn- reg-error-id [machine]
  (try (rf/reg-machine (keyword "tt" (str (gensym))) machine) nil
       (catch clojure.lang.ExceptionInfo e (:rf.error/id (ex-data e)))))

(deftest state-timeout-accepts-valid
  (testing "a well-formed state-level :timeout (integer + ISO) registers cleanly"
    (is (nil? (reg-error-id {:initial :w
                             :states {:w {:timeout 5000 :on-timeout {:target :d}}
                                      :d {}}})))
    (is (nil? (reg-error-id {:initial :w
                             :states {:w {:timeout "PT5S" :on-timeout :d}
                                      :d {}}})))))

(deftest state-timeout-fail-loud
  (testing ":timeout without :on-timeout fails loud"
    (is (= :rf.error/machine-timeout-without-on-timeout
           (reg-error-id {:initial :w :states {:w {:timeout 5000} :d {}}}))))
  (testing ":on-timeout without :timeout fails loud"
    (is (= :rf.error/machine-on-timeout-without-timeout
           (reg-error-id {:initial :w :states {:w {:on-timeout :d} :d {}}}))))
  (testing "the XState `5s` shorthand duration fails loud"
    (is (= :rf.error/machine-bad-timeout-duration
           (reg-error-id {:initial :w :states {:w {:timeout "5s" :on-timeout :d} :d {}}}))))
  (testing "a `10ms` shorthand duration fails loud"
    (is (= :rf.error/machine-bad-timeout-duration
           (reg-error-id {:initial :w :states {:w {:timeout "10ms" :on-timeout :d} :d {}}}))))
  (testing "a non-positive integer duration fails loud"
    (is (= :rf.error/machine-bad-timeout-duration
           (reg-error-id {:initial :w :states {:w {:timeout 0 :on-timeout :d} :d {}}}))))
  (testing "a fn duration fails loud (timeouts are fixed wall-clock deadlines)"
    (is (= :rf.error/machine-bad-timeout-duration
           (reg-error-id {:initial :w :states {:w {:timeout (fn [_] 5) :on-timeout :d} :d {}}})))))

(deftest spawn-timeout-fail-loud
  (testing "a well-formed spawn-level :timeout registers cleanly"
    (is (nil? (reg-error-id {:initial :l
                             :states {:l {:spawn {:machine-id :stub
                                                  :timeout 10000
                                                  :on-timeout {:target :to}}}
                                      :to {}}}))))
  (testing "a spawn :timeout without :on-timeout fails loud"
    (is (= :rf.error/machine-timeout-without-on-timeout
           (reg-error-id {:initial :l
                          :states {:l {:spawn {:machine-id :stub :timeout 10000}}
                                   :to {}}}))))
  (testing "a spawn :timeout with the `5s` shorthand fails loud"
    (is (= :rf.error/machine-bad-timeout-duration
           (reg-error-id {:initial :l
                          :states {:l {:spawn {:machine-id :stub
                                               :timeout "5s" :on-timeout :to}}
                                   :to {}}})))))

(deftest timeout-after-collision-fail-loud
  (testing "a timeout ms colliding with an explicit :after delay-key fails loud"
    (is (= :rf.error/machine-timeout-after-collision
           (reg-error-id {:initial :w
                          :states {:w {:after {5000 {:target :x}}
                                       :timeout 5000 :on-timeout {:target :d}}
                                   :x {} :d {}}}))))
  (testing "a state-level and a spawn-level timeout resolving to the same ms collide"
    (is (= :rf.error/machine-timeout-after-collision
           (reg-error-id {:initial :l
                          :states {:l {:timeout 5000 :on-timeout {:target :st}
                                       :spawn {:machine-id :stub
                                               :timeout 5000 :on-timeout {:target :sp}}}
                                   :st {} :sp {}}}))))
  (testing "distinct state-level and spawn-level timeout durations register cleanly"
    (is (nil? (reg-error-id {:initial :l
                             :states {:l {:timeout 3000 :on-timeout {:target :st}
                                          :spawn {:machine-id :stub
                                                  :timeout 5000 :on-timeout {:target :sp}}}
                                      :st {} :sp {}}})))))

(deftest timeout-target-resolution
  (testing "an :on-timeout target that resolves to no state fails loud"
    (is (= :rf.error/machine-unresolved-target
           (reg-error-id {:initial :w
                          :states {:w {:timeout 5000 :on-timeout {:target :nowhere}}
                                   :d {}}})))))

;; ---- desugaring (distinct intent, one mechanism) --------------------------

(deftest desugar-state-timeout
  (testing "state :timeout / :on-timeout lowers to an :after entry keyed by ms"
    (is (= {:initial :w :states {:w {:after {5000 {:target :d}}} :d {}}}
           (timeout/desugar-timeouts
             {:initial :w :states {:w {:timeout "PT5S" :on-timeout {:target :d}} :d {}}})))))

(deftest desugar-spawn-timeout
  (testing "spawn :timeout / :on-timeout lowers onto the spawn-bearing state's :after"
    (is (= {:initial :l
            :states {:l {:spawn {:machine-id :c} :after {10000 {:target :to}}}
                     :to {}}}
           (timeout/desugar-timeouts
             {:initial :l
              :states {:l {:spawn {:machine-id :c :timeout 10000 :on-timeout {:target :to}}}
                       :to {}}})))))

(deftest desugar-coexists-with-after
  (testing ":timeout and an explicit :after coexist on the same node (A4)"
    (let [out (timeout/desugar-timeouts
                {:initial :w
                 :states {:w {:after {1000 :warn} :timeout "PT5S" :on-timeout :done}
                          :warn {} :done {}}})]
      (is (= {1000 :warn 5000 :done} (get-in out [:states :w :after])))
      (is (not (contains? (get-in out [:states :w]) :timeout))))))

(deftest desugar-idempotent
  (testing "desugaring an already-desugared spec is a no-op"
    (let [m {:initial :w :states {:w {:after {5000 {:target :d}}} :d {}}}]
      (is (= m (timeout/desugar-timeouts (timeout/desugar-timeouts m)))))))

;; ---- dispatch boundary — the timeout actually fires the transition --------

(deftest state-timeout-arms-and-fires
  (testing "entering a :timeout-bearing state arms an :after timer at the resolved ms"
    (let [m {:initial :idle :data {}
             :states {:idle    {:on {:go :waiting}}
                      :waiting {:timeout "PT5S" :on-timeout {:target :timed-out}}
                      :timed-out {}}}
          traces (atom [])]
      (rf/reg-machine :tt/fire m)
      (rf/register-listener! :trace ::s (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:tt/fire [:go]])
      (let [s (snapshot :tt/fire)]
        (is (= :waiting (:state s)) "transitioned into the timeout-bearing state")
        (is (= 1 (get-in s [:data :rf/after-epoch [:waiting]]))
            "the desugared :after timer armed with epoch 1"))
      (is (some #(and (= :rf.machine.timer/scheduled (:operation %))
                      (= 5000 (:delay (:tags %))))
                @traces)
          "the resolved 5000ms `PT5S` timer scheduled")
      (rf/register-listener! :trace ::s (fn [_]) )
      ;; Fire the synthetic timer-elapsed event at the resolved ms.
      (rf/dispatch-sync [:tt/fire [:rf.machine.timer/after-elapsed 5000 1 [:waiting]]])
      (is (= :timed-out (:state (snapshot :tt/fire)))
          "the :on-timeout transition fired when the timer elapsed"))))

(deftest leaving-state-cancels-timeout
  (testing "a normal :on transition out of the state cancels the timeout timer"
    (let [m {:initial :idle :data {}
             :states {:idle    {:on {:go :waiting}}
                      :waiting {:timeout 5000 :on-timeout {:target :timed-out}
                                :on {:done :ready}}
                      :timed-out {}
                      :ready {}}}
          traces (atom [])]
      (rf/reg-machine :tt/cancel m)
      (rf/dispatch-sync [:tt/cancel [:go]])
      (rf/register-listener! :trace ::c (fn [ev] (swap! traces conj ev)))
      ;; Leave the state before the timeout elapses.
      (rf/dispatch-sync [:tt/cancel [:done]])
      (is (= :ready (:state (snapshot :tt/cancel))))
      (is (some #(= :rf.machine.timer/cancelled (:operation %)) @traces)
          "the exit cascade cancelled the in-flight timeout timer")
      ;; A late timer carrying the pre-exit epoch is now stale — firing it
      ;; does NOT move the machine.
      (rf/dispatch-sync [:tt/cancel [:rf.machine.timer/after-elapsed 5000 1 [:waiting]]])
      (is (= :ready (:state (snapshot :tt/cancel)))
          "the cancelled (stale-epoch) timeout does not fire after the state was left"))))

(deftest timeout-coexists-with-after-at-runtime
  (testing "a node with both :after and :timeout arms BOTH timers; each fires its own transition"
    (let [m {:initial :idle :data {}
             :states {:idle    {:on {:go :waiting}}
                      :waiting {:after {1000 {:target :warn}}
                                :timeout 5000 :on-timeout {:target :timed-out}}
                      :warn {} :timed-out {}}}
          traces (atom [])]
      (rf/reg-machine :tt/both m)
      (rf/register-listener! :trace ::b (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [:tt/both [:go]])
      (let [scheduled (->> @traces
                           (filter #(= :rf.machine.timer/scheduled (:operation %)))
                           (map #(:delay (:tags %)))
                           set)]
        (is (= #{1000 5000} scheduled)
            "both the explicit :after (1000) and the desugared :timeout (5000) timers armed")))))
