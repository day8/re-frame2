(ns re-frame.machine-spawn-invalid-definition-test
  "An invalid inline `:definition` is rejected AT SPAWN, fail-closed
  (rf2-3x7nj.9.6).

  Validation of an inline definition used to happen only when the lazy
  resolver first materialised the actor's handler, inside the router's
  catch-all: the actor installed as a zombie (snapshot present,
  `:rf/bootstrap-pending?` forever), its real registration error was
  discarded, and every event to it — its own bootstrap included — read
  `:rf.error/no-such-handler`. Under `:spawn-all` such a child could never
  reach `:final?`, so an `:all` join hung.

  Now the spawn materialises the definition through the same
  `handler-meta-for` the resolver uses, before anything installs, and throws
  the validator's typed error for the fx runner to surface. A `:spawn-all`
  invoke carrying one rejects atomically, like an unregistered child TYPE."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(def ^:private frame-db rf.machines.test-support/runtime-db)

(def ^:private dangling
  "A definition whose `:x` targets a state that does not exist."
  {:initial :a :states {:a {:on {:x :nowhere}}}})

(def ^:private valid
  {:initial :a :states {:a {:on {:x :b}} :b {}}})

(defn- surfaced-error-ids
  "The `:rf.error/id` of every exception an `:rf.error/fx-handler-exception`
  trace carried."
  []
  (->> (rf.machines.test-support/events-of :rf.error/fx-handler-exception)
       (keep #(some-> % :tags :exception ex-data :rf.error/id))
       vec))

(defn- spawn-then-poke! [definition]
  (rf/reg-event :pf/go (fn [_ _] {:fx [[:rf.machine/spawn {:definition     definition
                                                         :fixed-actor-id :pf/kid}]]}))
  (rf/dispatch-sync [:pf/go])
  (rf/dispatch-sync [:pf/kid [:x]]))

(deftest invalid-inline-definition-is-rejected-at-spawn
  (testing "CASE: nothing installs, and the validator's own error surfaces"
    (spawn-then-poke! dangling)
    (is (nil? (rf.machines.test-support/snapshot :pf/kid))
        "no zombie snapshot")
    (is (= [:rf.error/machine-unresolved-target] (surfaced-error-ids))
        "the spawn surfaced the validator's typed error")
    (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn/spawned))
        "no spawned trace for the rejected actor"))
  (testing "CONTROL: a valid inline definition installs and processes events"
    (rf.machines.test-support/reset-captured!)
    (rf/reg-event :pf/go2 (fn [_ _] {:fx [[:rf.machine/spawn {:definition     valid
                                                            :fixed-actor-id :pf/kid2}]]}))
    (rf/dispatch-sync [:pf/go2])
    (rf/dispatch-sync [:pf/kid2 [:x]])
    (is (= :b (rf.machines.test-support/machine-state :pf/kid2)))
    (is (empty? (surfaced-error-ids)))))

(defn- fork-parent [bad-definition]
  {:initial :idle
   :states  {:idle    {:on {:start :forking}}
             :forking {:spawn-all {:children        [{:id :ok  :definition valid
                                                      :fixed-actor-id :pf/ok}
                                                     {:id :bad :definition bad-definition
                                                      :fixed-actor-id :pf/bad}]
                                   :join            :all
                                   :on-all-complete [:all/done]}
                       :on        {:all/done :ready}}
             :ready   {}}})

(deftest spawn-all-with-an-invalid-inline-definition-rejects-atomically
  (testing "CASE: the invoke is rejected as a whole — the valid sibling is
            suppressed too — and the validator's error surfaces"
    (rf/reg-machine :pf/fork (fork-parent dangling))
    (rf/dispatch-sync [:pf/fork [:start]])
    (is (= {:rf/spawn-all-rejected? true}
           (get-in (frame-db) [:rf.runtime/machines :spawned :pf/fork [:forking]]))
        "the childless reject sentinel is seeded")
    (is (nil? (rf.machines.test-support/snapshot :pf/ok)) "no orphaned valid sibling")
    (is (nil? (rf.machines.test-support/snapshot :pf/bad)) "no zombie child")
    (is (= [:rf.error/machine-unresolved-target] (surfaced-error-ids))))
  (testing "CONTROL: with both definitions valid, both children install"
    (rf.machines.test-support/reset-captured!)
    (rf/reg-machine :pf/fork2 (fork-parent {:initial :a :states {:a {}}}))
    (rf/dispatch-sync [:pf/fork2 [:start]])
    (is (some? (rf.machines.test-support/snapshot :pf/ok)))
    (is (some? (rf.machines.test-support/snapshot :pf/bad)))
    (is (empty? (surfaced-error-ids)))))
