(ns re-frame.machine-macrostep-snapshot-rules-test
  "What a macrostep may write to an actor's SNAPSHOT, and what it reports
  having written.

    - `:always` microstep traces — one
      `:rf.machine.microstep/transition` per microstep, and the outer
      `:rf.machine/transition` stamped with `:microsteps <count>` (0 when no
      `:always` cascade ran). Spec 005 §Trace events.
    - `:rf.error/machine-action-wrote-db` — an action's effect map may carry
      `:data`, never `:db`. The app-db is not a machine action's to write
      (Spec 005:463); the offending value is redacted at trace egress.
    - `:rf.machine/update-snapshot` — the one sanctioned snapshot patch
      (Spec 005:489). It merges the spec-permitted keys, and a `:db` key in
      the patch meets the SAME hard-disallow."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as mtest]
            [re-frame.substrate.plain-atom :as plain-atom]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture {:adapter plain-atom/adapter}))

;; Routed through the shared `mtest/with-trace-capture` — guaranteed
;; unregister in a `finally`.
(defn- record-traces! [body-fn]
  (mtest/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op] (filterv #(= op (:operation %)) evs))

(defn- snap-of [machine-id]
  (get-in @(rf/subscribe [:rf/machine machine-id]) [:state]))

;; ---- :always microstep traces + the outer :microsteps count ---------------

(deftest always-emits-microstep-traces-and-count
  (testing "an :always-driven cascade emits one
   :rf.machine.microstep/transition per microstep AND stamps :microsteps
   on the outer :rf.machine/transition"
    (rf/reg-machine :rem/quiz
      {:initial :asking
       :data    {:correct 9}
       :guards  {:enough? (fn [{d :data}] (>= (:correct d) 10))}
       :actions {:count   (fn [{d :data}] {:data {:correct (inc (:correct d))}})}
       :states  {:asking {:always [{:guard :enough? :target :winner}]
                          :on     {:answer {:action :count}}}
                 :winner {}}})
    (let [evs   (record-traces!
                  (fn [] (rf/dispatch-sync [:rem/quiz [:answer]])))
          micro (ops evs :rf.machine.microstep/transition)
          outer (ops evs :rf.machine/transition)]
      (is (= :winner (snap-of :rem/quiz)) "the :always microstep flipped to :winner")
      (is (= 1 (count micro)) "exactly one microstep trace")
      (let [m (first micro)]
        (is (= :asking (-> m :tags :from)))
        (is (= :winner (-> m :tags :to)))
        (is (= 0 (-> m :tags :microstep-index))))
      (is (= 1 (count outer)) "one outer macrostep trace")
      (is (= 1 (-> outer first :tags :microsteps))
          "outer trace carries :microsteps 1"))))

(deftest no-always-stamps-zero-microsteps
  (testing "a plain transition with no :always cascade stamps :microsteps 0"
    (rf/reg-machine :rem/plain
      {:initial :a :states {:a {:on {:go {:target :b}}} :b {}}})
    (let [evs   (record-traces!
                  (fn [] (rf/dispatch-sync [:rem/plain [:go]])))
          outer (ops evs :rf.machine/transition)]
      (is (= 0 (-> outer first :tags :microsteps)))
      (is (empty? (ops evs :rf.machine.microstep/transition))))))

;; ---- :rf.error/machine-action-wrote-db — the app-db is not an action's ---

(deftest action-returning-db-emits-error-and-drops-db
  (testing "an action whose effect map carries :db emits
   :rf.error/machine-action-wrote-db; :data still flows, :db is dropped"
    (rf/reg-machine :rem/wrote-db
      {:initial :a
       :actions {:bad (fn [_] {:db {:hacked true} :data {:legit 1}})}
       :states  {:a {:on {:go {:target :b :action :bad}}} :b {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:rem/wrote-db [:go]])))
          ws  (ops evs :rf.error/machine-action-wrote-db)]
      (is (= 1 (count ws)) "exactly one wrote-db error")
      (is (= :bad (-> ws first :tags :action-id)))
      ;; `:offending-value` (the whole app-db the action wrongly returned)
      ;; is summarized to `:rf/redacted` at the trace egress chokepoint
      ;; (`marks/project-machine-wrote-db-tags`) so it never leaks raw to
      ;; listeners / epoch / MCP / logs. The `:action-id` locates the
      ;; offending action; the operator does not need the app-db contents.
      (is (= :rf/redacted (-> ws first :tags :offending-value))
          "the offending app-db value is redacted at egress (rf2-x9haxl)")
      ;; :data flowed through; the FSM is at :b; app-db root was NOT clobbered.
      (is (= {:legit 1} (:data @(rf/subscribe [:rf/machine :rem/wrote-db]))))
      (is (= :b (snap-of :rem/wrote-db)))
      (is (not (contains? @(rf/subscribe [:rf/machine :rem/wrote-db]) :db))))))

;; ---- :rf.machine/update-snapshot — the one sanctioned snapshot patch ------

(deftest update-snapshot-fx-merges-permitted-keys
  (testing "[:rf.machine/update-snapshot {:rf/machine-id id :rf/patch {...}}]
   merges the spec-permitted keys onto the actor snapshot; user
   error/status state lives under :data (rf2-gqmrcx fold)"
    (rf/reg-machine :rem/escape
      {:initial :a
       :actions {:patch
                 (fn [_]
                   {:fx [[:rf.machine/update-snapshot
                          {:rf/machine-id :rem/escape
                           :rf/patch      {:data {:status :degraded
                                                  :errors [:boom]}
                                           :db   {:nope true}}}]]})}
       :states  {:a {:on {:go {:target :a :action :patch}}}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:rem/escape [:go]])))
          snap @(rf/subscribe [:rf/machine :rem/escape])]
      (is (= :degraded (-> snap :data :status)) ":status patched under :data")
      (is (= [:boom] (-> snap :data :errors)) ":errors patched under :data")
      ;; :db in the patch is the same hard-disallow.
      (is (= 1 (count (ops evs :rf.error/machine-action-wrote-db)))
          ":db in the patch surfaces the hard-disallow error"))))
