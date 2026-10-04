(ns re-frame.machine-root-on-fallback-test
  "The machine ROOT's own `:on` is the ancestor fallback: `pick-transition`
  consults it LAST, after every node on the state path has missed (Spec 005
  §Transition resolution steps 6-7). This suite pins that path end to end —
  its refs checked at registration, its targets resolved at runtime, and the
  benign no-op emitted when even the root misses.

    - Runtime resolution: `:*` fires for an otherwise-unhandled event. That
      keyword targets resolve root-relative, vector targets are absolute from
      root, a state-level handler for the same event id shadows the root
      (deepest wins), and a false guard on the root transition means NO level
      matched is pinned by the `machine-root-on-fallback` conformance fixture,
      which `machines_conformance_test` runs.
    - `:rf.machine.event/unhandled-no-op` (xstate-v5 parity) is emitted when
      no level matches an unknown USER event — op-type `:rf.machine`, NOT an
      error, and no `:rf.error/machine-unhandled-event` advisory. Reserved
      `:rf/*` framework lifecycle traffic (bootstrap,
      `:rf.machine.spawn/spawned`, stories pings) does NOT emit it: that is
      framework init, not an unknown user event. The case below uses a DOMAIN
      event (`[:nope]`), so it emits.
    - Root-`:on` `:guard` / `:action` refs are resolved at REGISTRATION
      (Spec 005:1334), not left to fail at dispatch."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter}))

;; Routed through the shared `rf.machines.test-support/with-trace-capture` — guaranteed
;; unregister in a `finally`.
(defn- record-traces! [body-fn]
  (rf.machines.test-support/with-trace-capture seen
    (body-fn)
    @seen))

(defn- ops [evs op] (filterv #(= op (:operation %)) evs))

(defn- snap-of [machine-id]
  (get-in @(rf/subscribe [:rf/machine machine-id]) [:state]))

;; ---- runtime: the root `:on` fallback is consulted last -------------------

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

;; ---- runtime: the benign no-op when no level matched ----------------------

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
          "no error advisory is emitted")
      (let [u (first no-ops)]
        (is (= :rf.machine (:op-type u))
            "op-type is the machine-activity family, not a severity")
        (is (= :rem/unhandled (-> u :tags :actor-id))
            "the live actor INSTANCE addresses the no-op")
        (is (= [:nope] (-> u :tags :event)))
        (is (= :a (-> u :tags :state)))))))

;; ---- registration: root-`:on` guard / action refs resolve -----------------

(deftest root-on-refs-validated-at-registration
  (testing "a dangling root-`:on` :guard / :action ref fails registration"
    (let [e (try (rf.machines/validate-machine!
                   {:initial :a
                    :on      {:go {:target :a :guard :missing?}}
                    :states  {:a {}}})
                 nil (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :rf.error/machine-unresolved-guard (:rf.error/id (ex-data e)))))
    (testing "a resolvable root-`:on` ref validates silently"
      (is (nil? (rf.machines/validate-machine!
                  {:initial :a
                   :guards  {:ok? (fn [_] true)}
                   :on      {:go {:target :a :guard :ok?}}
                   :states  {:a {}}}))))))
