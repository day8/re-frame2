(ns re-frame.machine-root-on-fallback-test
  "The machine ROOT's own `:on` is the ancestor fallback: `pick-transition`
  consults it LAST, after every node on the state path has missed (Spec 005
  §Transition resolution steps 6-7). This suite pins that path end to end —
  its refs checked at registration, its targets resolved at runtime, and the
  benign no-op emitted when even the root misses.

    - Runtime resolution: keyword targets resolve root-relative, vector
      targets are absolute from root, `:*` fires for an otherwise-unhandled
      event, a state-level handler for the same event id shadows the root
      (deepest wins), and a false guard on the root transition means NO level
      matched.
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
            [re-frame.machines :as machines]
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

;; ---- runtime: the root `:on` fallback is consulted last -------------------

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

;; ---- registration: root-`:on` guard / action refs resolve -----------------

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
