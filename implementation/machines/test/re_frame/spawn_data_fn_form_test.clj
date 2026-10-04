(ns re-frame.spawn-data-fn-form-test
  "Per Spec 005 §Declarative `:spawn`
  §Spec-spec keys: `:data` admits a function form `(fn [snap ev] data)`
  so the spawned child's initial data can be derived from the parent's
  post-action snapshot + the triggering event.

  The invariants under test:

   1. **fn-form `:data` is materialised from the triggering event.** When
      `:data` is a fn, the runtime invokes it with the inbound event and
      passes the resulting map (NOT the fn itself) to the spawned child. A
      literal map passing through verbatim is pinned by the
      spawn-on-entry-destroy-on-exit conformance fixture.

   2. **fn-form sees the post-action snapshot.** A transition's
      `:action` writes to `:data`; the fn-form sees those writes.
      Per Spec 005 line 1511 — pinned, for one spawn and for two in one
      cascade, in `spawn_ordering_ep0029_cljs_test`.

   3. **fn-form throw routes to :rf.error/machine-action-exception.**
      Per Spec 005 §Errors line 1597 — same category as any
      user-supplied fn that throws during a machine action.

  Also covers `:spawn-all` symmetrically — each child's `:data` admits
  the same fn-form per Spec 005 §Spec-spec keys (line 1818)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines …]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)

;; ---- (1) fn-form sees the triggering event --------------------------------

(deftest fn-form-data-sees-triggering-event
  (testing "fn-form `:data` receives the inbound event vector as its second arg"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle    {:on {:fetch :working}}
                   :working {:spawn {:machine-id :worker/proc
                                      :data       (fn [{ev :event}]
                                                    {:from-event ev})}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/event-form parent)
      (rf/dispatch-sync [:sup/event-form [:fetch :req-1]])
      (is (= [:fetch :req-1]
             (:from-event (:data (snapshot :worker/proc#1))))
          "fn-form's second arg was the triggering event"))))

;; ---- (3) fn-form throw routes to :rf.error/machine-action-exception ------

(deftest fn-form-data-throw-routes-to-machine-action-exception
  (testing "fn-form `:data` throw halts the cascade and emits :rf.error/machine-action-exception (Spec 005:1597)"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states
                  {:idle    {:on {:start :working}}
                   :working {:spawn {:machine-id :worker/proc
                                      :data       (fn [_]
                                                    (throw (ex-info "boom" {:why :test})))}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/throwing parent)
      ;; Shared `with-trace-capture` — guaranteed unregister in a `finally`,
      ;; no hand-rolled register/try/finally.
      (rf.machines.test-support/with-trace-capture traces
        (rf/dispatch-sync [:sup/throwing [:start]])
        ;; The cascade halted: no actor was spawned. Per Spec 005 §Errors,
        ;; the snapshot does NOT commit — the parent's lazy initial snapshot
        ;; is preserved (or stays absent if it was never materialised).
        (is (nil? (snapshot :worker/proc#1))
            "no spawned actor — the cascade halted before spawn-fx ran")
        (let [parent-snap (snapshot :sup/throwing)]
          (is (or (nil? parent-snap)
                  (= :idle (:state parent-snap)))
              "parent did not commit the transition (snapshot is either absent or still :idle)"))
        ;; The error trace fired with the canonical category. Per Spec 009,
        ;; error traces carry :op-type :error and the category as :operation.
        (is (some #(and (= :error (:op-type %))
                        (= :rf.error/machine-action-exception (:operation %)))
                  @traces)
            "an :rf.error/machine-action-exception trace was emitted")))))

;; ---- :spawn-all child :data fn-form is materialised ----------------------

(deftest spawn-all-child-data-fn-form-is-materialised
  (testing "each :spawn-all child's `:data` admits the same fn-form per Spec 005:1818"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :data    {:base "/api"}
                  :states
                  {:idle      {:on {:fan-out :hydrating}}
                   :hydrating {:spawn-all
                               {:children
                                [{:id         :one
                                  :machine-id :hydra/leaf
                                  :data       (fn [{snap :snapshot}]
                                                {:url (str (-> snap :data :base) "/one")})}
                                 {:id         :two
                                  :machine-id :hydra/leaf
                                  :data       (fn [{snap :snapshot}]
                                                {:url (str (-> snap :data :base) "/two")})}]
                                :on-all-complete [:done]}}}}]
      (rf/reg-machine :hydra/leaf child)
      (rf/reg-machine :sup/all parent)
      (rf/dispatch-sync [:sup/all [:fan-out]])
      ;; Two children spawned, each with materialised :data.
      (is (= "/api/one"
             (:url (:data (snapshot :hydra/leaf#1))))
          "first child's fn-form derived :url from parent's :data")
      (is (= "/api/two"
             (:url (:data (snapshot :hydra/leaf#2))))
          "second child's fn-form derived :url from parent's :data"))))
