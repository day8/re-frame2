(ns re-frame.machine-spawn-inline-address-test
  "An inline `:definition` spawn must carry an ADDRESS — `:id-prefix` or
  `:fixed-actor-id` — and one carrying neither is refused early, typed and
  fail-closed.

  A `:machine-id` spawn defaults its id prefix to that registered type; an
  inline definition has no type to default to. Accepting the unaddressed
  shape would crash the id allocator on the nil prefix (an NPE in
  `format-spawn-id`, surfacing as `:rf.error/handler-exception`), so the
  parent would never boot; a hand-emitted `[:rf.machine/spawn …]` in the same
  shape would not crash but would install an actor at address `nil`.

  Registration refuses the declarative shapes, so the machine never
  registers, and the spawn fx refuses the hand-emitted shape before anything
  installs."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom])
  (:import [clojure.lang ExceptionInfo]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private valid
  {:initial :wait :states {:wait {}}})

(def ^:private dangling
  "A definition whose `:x` targets a state that does not exist."
  {:initial :a :states {:a {:on {:x :nowhere}}}})

(defn- refusal
  "The `:rf.error/id` `reg-machine` refuses `definition` with, or nil when it
  registers."
  [machine-id definition]
  (try (rf/reg-machine machine-id definition)
       nil
       (catch ExceptionInfo e (:rf.error/id (ex-data e)))))

(defn- spawning-parent [spawn-spec]
  {:initial :working :data {} :states {:working {:spawn spawn-spec}}})

(defn- forking-parent [child]
  {:initial :forking
   :data    {}
   :states  {:forking {:spawn-all {:children        [(assoc child :id :kid)]
                                   :on-all-complete [:all/done]}
                       :on        {:all/done :ready}}
             :ready   {}}})

(defn- snapshots []
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :snapshots]))

(defn- surfaced-error-ids
  "The `:rf.error/id` of every exception an `:rf.error/fx-handler-exception`
  trace carried."
  []
  (->> (rf.machines.test-support/events-of :rf.error/fx-handler-exception)
       (keep #(some-> % :tags :exception ex-data :rf.error/id))
       vec))

(deftest declarative-spawn-without-an-address-is-refused-at-registration
  (testing "CASE: a VALID unaddressed inline definition — the machine never registers"
    (is (= :rf.error/machine-spawn-bad-shape
           (refusal :addr/parent (spawning-parent {:definition valid}))))
    (rf/dispatch-sync [:addr/parent [:rf.machine/start]])
    (is (empty? (snapshots)) "no parent, no child, no actor at nil")
    (is (empty? (rf.machines.test-support/events-of :rf.error/handler-exception))
        "no allocator crash"))
  (testing "CASE: an INVALID unaddressed inline definition is refused the same way"
    (is (= :rf.error/machine-spawn-bad-shape
           (refusal :addr/parent-bad (spawning-parent {:definition dangling})))))
  (testing "CONTROL: :id-prefix addresses it — the child installs at <prefix>#1"
    (is (nil? (refusal :addr/prefixed (spawning-parent {:definition valid
                                                        :id-prefix  :addr/kid}))))
    (rf/dispatch-sync [:addr/prefixed [:rf.machine/start]])
    (is (= :wait (rf.machines.test-support/machine-state :addr/kid#1))))
  (testing "CONTROL: :fixed-actor-id addresses it — the child installs there"
    (is (nil? (refusal :addr/fixed (spawning-parent {:definition     valid
                                                     :fixed-actor-id :addr/the-kid}))))
    (rf/dispatch-sync [:addr/fixed [:rf.machine/start]])
    (is (= :wait (rf.machines.test-support/machine-state :addr/the-kid)))))

(deftest spawn-all-child-without-an-address-is-refused-at-registration
  (testing "CASE: a VALID unaddressed inline child — the machine never registers"
    (is (= :rf.error/machine-spawn-all-bad-shape
           (refusal :addr/fork (forking-parent {:definition valid}))))
    (rf/dispatch-sync [:addr/fork [:rf.machine/start]])
    (is (empty? (snapshots)))
    (is (empty? (rf.machines.test-support/events-of :rf.error/handler-exception))))
  (testing "CASE: an INVALID unaddressed inline child is refused the same way"
    (is (= :rf.error/machine-spawn-all-bad-shape
           (refusal :addr/fork-bad (forking-parent {:definition dangling})))))
  (testing "CONTROL: :id-prefix addresses the child"
    (is (nil? (refusal :addr/fork-prefixed (forking-parent {:definition valid
                                                            :id-prefix  :addr/fork-kid}))))
    (rf/dispatch-sync [:addr/fork-prefixed [:rf.machine/start]])
    (is (= :wait (rf.machines.test-support/machine-state :addr/fork-kid#1))))
  (testing "CONTROL: :fixed-actor-id addresses the child"
    (is (nil? (refusal :addr/fork-fixed (forking-parent {:definition     valid
                                                         :fixed-actor-id :addr/fork-the-kid}))))
    (rf/dispatch-sync [:addr/fork-fixed [:rf.machine/start]])
    (is (= :wait (rf.machines.test-support/machine-state :addr/fork-the-kid)))))

(defn- hand-spawn! [event-id spawn-args]
  (rf/reg-event event-id (fn [_ _] {:fx [[:rf.machine/spawn spawn-args]]}))
  (rf/dispatch-sync [event-id]))

(deftest hand-emitted-spawn-without-an-address-is-refused
  (testing "CASE: a VALID unaddressed inline definition — nothing installs"
    (hand-spawn! :addr/go {:definition valid})
    (is (empty? (snapshots)) "no actor at address nil")
    (is (= [:rf.error/machine-spawn-bad-shape] (surfaced-error-ids)))
    (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn/spawned))))
  (testing "CASE: an INVALID unaddressed inline definition — the address gate fires first"
    (rf.machines.test-support/reset-captured!)
    (hand-spawn! :addr/go-bad {:definition dangling})
    (is (empty? (snapshots)))
    (is (= [:rf.error/machine-spawn-bad-shape] (surfaced-error-ids))))
  (testing "CONTROL: :id-prefix addresses it"
    (rf.machines.test-support/reset-captured!)
    (hand-spawn! :addr/go-prefixed {:definition valid :id-prefix :addr/hand})
    (is (= :wait (rf.machines.test-support/machine-state :addr/hand#1)))
    (is (empty? (surfaced-error-ids))))
  (testing "CONTROL: :fixed-actor-id addresses it"
    (rf.machines.test-support/reset-captured!)
    (hand-spawn! :addr/go-fixed {:definition valid :fixed-actor-id :addr/hand-kid})
    (is (= :wait (rf.machines.test-support/machine-state :addr/hand-kid)))
    (is (empty? (surfaced-error-ids)))))
