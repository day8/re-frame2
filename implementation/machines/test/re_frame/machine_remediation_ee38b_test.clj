(ns re-frame.machine-remediation-ee38b-test
  "Pins these machines behaviours:

    - P1 root (top-level) `:on` fallback is consulted at runtime
      (Spec 005 §Transition resolution steps 6-7) — keyword + vector
      targets, `:*` wildcard, deepest-wins override, guard gating.
    - P2 the benign `:rf.machine.event/unhandled-no-op` is emitted when no
      level matches an unknown USER event (xstate-v5 parity; op-type
      :rf.machine, NOT an error). An unknown USER event is NOT an error: no
      `:rf.error/machine-unhandled-event` advisory is emitted. Reserved-
      `:rf/*` framework lifecycle traffic (bootstrap,
      `:rf.machine.spawn/spawned`, stories pings) does NOT emit the no-op —
      it is framework init, not an unknown user event (severity stays
      benign). The case pinned below uses a DOMAIN event (`[:nope]`), so it
      emits the no-op.
    - P2 `:always` microstep traces — per-microstep
      `:rf.machine.microstep/transition` + the outer
      `:rf.machine/transition` carrying `:microsteps <count>`
      (Spec 005 §Trace events).
    - P2 `:rf.error/machine-action-wrote-db` hard-disallow (Spec 005:463).
    - P2 `:rf.machine/update-snapshot` reserved fx (Spec 005:489).
    - P3 history grammar placement validated at registration — a
      `:type :history` node MUST have an owning compound
      (`:rf.error/machine-history-misplaced`); a well-placed node validates
      (history is first-class — Spec 005 §History states).
    - P3 `:after` + root-`:on` guard/action ref validation at
      registration (Spec 005:1334)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
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

;; ---- P1: root `:on` fallback consulted at runtime --------------------------

(deftest root-on-keyword-target-fires-from-state-without-handler
  (testing "a root `:on` keyword target fires from a leaf lacking its own
   handler; the keyword resolves root-relative"
    (rf/reg-machine :rem/root-kw
      {:initial :authenticated
       :on      {:logout :idle}                 ;; root fallback
       :states  {:idle {}
                 :authenticated
                 {:initial :dashboard
                  :states  {:dashboard {}}}}})  ;; no :logout anywhere on the path
    (rf/dispatch-sync [:rem/root-kw [:logout]])
    (is (= :idle (snap-of :rem/root-kw))
        "root :on :logout drove [:authenticated :dashboard] → :idle")))

(deftest root-on-vector-target-is-absolute
  (testing "a root `:on` vector target is an absolute path from root"
    (rf/reg-machine :rem/root-vec
      {:initial :authenticated
       :on      {:goto [:authenticated :settings]}
       :states  {:authenticated
                 {:initial :dashboard
                  :states  {:dashboard {} :settings {}}}}})
    (rf/dispatch-sync [:rem/root-vec [:goto]])
    (is (= [:authenticated :settings] (snap-of :rem/root-vec)))))

(deftest root-on-wildcard-fires-for-unhandled-event
  (testing "root `:on` `:*` wildcard fires for an otherwise-unhandled event"
    (let [hits (atom 0)]
      (rf/reg-machine :rem/root-wild
        {:initial :a
         :actions {:tap (fn [_] (swap! hits inc) nil)}
         :on      {:* {:action :tap}}            ;; internal — no :target
         :states  {:a {}}})
      (rf/dispatch-sync [:rem/root-wild [:anything]])
      (is (= 1 @hits) "root :* fired for the unhandled event")
      (is (= :a (snap-of :rem/root-wild)) "internal — state unchanged"))))

(deftest state-level-handler-overrides-root-on
  (testing "deepest-wins — a leaf handler shadows the root `:on` for the
   same event id (the root fallback is consulted only on a miss)"
    (let [leaf (atom 0) root (atom 0)]
      (rf/reg-machine :rem/override
        {:initial :on-page
         :actions {:leaf (fn [_] (swap! leaf inc) nil)
                   :root (fn [_] (swap! root inc) nil)}
         :on      {:ev {:action :root}}
         :states  {:on-page {:on {:ev {:action :leaf}}}}})
      (rf/dispatch-sync [:rem/override [:ev]])
      (is (= 1 @leaf) "leaf handler ran")
      (is (= 0 @root) "root fallback shadowed — did NOT run"))))

(deftest root-on-guard-gates-the-fallback
  (testing "a false guard on a root `:on` transition means no level matched"
    (rf/reg-machine :rem/root-guard
      {:initial :a
       :data    {:ok? false}
       :guards  {:ok? (fn [{d :data}] (:ok? d))}
       :on      {:go {:target :b :guard :ok?}}
       :states  {:a {} :b {}}})
    (rf/dispatch-sync [:rem/root-guard [:go]])
    (is (= :a (snap-of :rem/root-guard)) "guard false → unhandled, no transition")))

;; ---- P2: :rf.machine.event/unhandled-no-op (xstate-v5 parity) ----------

(deftest unhandled-event-emits-benign-no-op
  (testing "no level matches → benign :rf.machine.event/unhandled-no-op with
   :actor-id / :event / :state, op-type :rf.machine (NOT an error)"
    (rf/reg-machine :rem/unhandled
      {:initial :a :states {:a {:on {:known {:target :a}}}}})
    (let [evs    (record-traces!
                   (fn [] (rf/dispatch-sync [:rem/unhandled [:nope]])))
          no-ops (ops evs :rf.machine.event/unhandled-no-op)]
      (is (= 1 (count no-ops)) "exactly one benign no-op trace")
      (is (empty? (ops evs :rf.error/machine-unhandled-event))
          "the retired error advisory is NEVER emitted")
      (let [u (first no-ops)]
        (is (= :rf.machine (:op-type u))
            "op-type is the machine-activity family, not a severity")
        (is (= :rem/unhandled (-> u :tags :actor-id))
            "the live actor INSTANCE addresses the no-op (rf2-yyvtk5)")
        (is (= [:nope] (-> u :tags :event)))
        (is (= :a (-> u :tags :state)))))))

(deftest handled-event-emits-no-unhandled-no-op
  (testing "a matched transition emits no unhandled-no-op trace"
    (rf/reg-machine :rem/handled
      {:initial :a :states {:a {:on {:go {:target :b}}} :b {}}})
    (let [evs (record-traces!
                (fn [] (rf/dispatch-sync [:rem/handled [:go]])))]
      (is (empty? (ops evs :rf.machine.event/unhandled-no-op)))
      (is (empty? (ops evs :rf.error/machine-unhandled-event))))))

;; ---- P2: :always microstep traces ------------------------------------------

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

;; ---- P2: :rf.error/machine-action-wrote-db ---------------------------------

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

;; ---- P2: :rf.machine/update-snapshot escape-hatch fx -----------------------

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

;; ---- P3: history grammar placement validated at registration ----------------
;; History is first-class; the registration validator enforces the PLACEMENT
;; constraint — a `:type :history` node MUST have an owning compound.

(deftest history-misplaced-rejected-at-registration
  (testing "a `:type :history` node with NO owning compound (machine root,
   or a flat top-level state) is rejected with
   :rf.error/machine-history-misplaced"
    (doseq [bad [;; root `:type :history`
                 {:type :history :initial :a :states {:a {}}}
                 ;; flat top-level `:type :history` (no enclosing compound)
                 {:initial :a :states {:a {} :h {:type :history}}}]]
      (let [e (try (machines/validate-machine! bad) nil
                   (catch clojure.lang.ExceptionInfo ex ex))]
        (is (= :rf.error/machine-history-misplaced
               (:rf.error/id (ex-data e)))
            (str "rejected: " (pr-str bad)))
        (is (= :history (:feature (ex-data e))) ":feature names the history grammar"))))
  (testing "a WELL-PLACED `:type :history` node (inside a compound's :states)
   validates silently"
    (is (nil? (machines/validate-machine!
                {:initial :c
                 :states  {:c {:initial :a
                               :states  {:a {} :h {:type :history :deep? true}}}}}))))
  (testing "a well-formed history-free hierarchical machine validates silently"
    (is (nil? (machines/validate-machine!
                {:initial :p
                 :states  {:p {:initial :a :states {:a {} :b {}}}}})))))

;; ---- P3: :after + root-`:on` ref validation at registration ----------------

(deftest after-guard-action-refs-validated-at-registration
  (testing "a dangling :after :guard ref fails registration (not at runtime)"
    (let [e (try (machines/validate-machine!
                   {:initial :a
                    :states  {:a {:after {1000 {:target :b :guard :missing?}}}
                              :b {}}})
                 nil (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data e)))))
    (let [e (try (machines/validate-machine!
                   {:initial :a
                    :states  {:a {:after {1000 {:target :b :action :nope}}}
                              :b {}}})
                 nil (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :rf.error/machine-unresolved-action (:rf.error/id (ex-data e)))))))

(deftest root-on-refs-validated-at-registration
  (testing "a dangling root-`:on` :guard / :action ref fails registration"
    (let [e (try (machines/validate-machine!
                   {:initial :a
                    :on      {:go {:target :a :guard :missing?}}
                    :states  {:a {}}})
                 nil (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data e)))))
    (testing "a resolvable root-`:on` ref validates silently"
      (is (nil? (machines/validate-machine!
                  {:initial :a
                   :guards  {:ok? (fn [_] true)}
                   :on      {:go {:target :a :guard :ok?}}
                   :states  {:a {}}}))))))
