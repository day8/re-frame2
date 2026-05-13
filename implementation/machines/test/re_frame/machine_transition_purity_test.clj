(ns re-frame.machine-transition-purity-test
  "Per rf2-gr8q. Locks in the contract that `machine-transition` is an
  honest pure function — identical (machine, snapshot, event) triples
  produce identical [next-snapshot effects] pairs INCLUDING the spawn-id
  sequencing inside emitted `:rf.machine/spawn` fx maps.

  Pre-rf2-gr8q the spawn-id allocator was a module-level
  `(defonce spawn-counter (atom {}))` keyed on `[frame-id machine-id]`
  in `re-frame.machines.transition`. The function's docstring promised
  pure / deterministic but the allocator was a side effect: a second
  identical call returned different spawn-ids (`:worker#2` vs the first
  call's `:worker#1`). The conformance corpus only worked because the
  per-fixture reset zeroed the atom between fixtures.

  Post-rf2-gr8q the counter lives inside the snapshot at
  `:rf/spawn-counter` (a per-machine-id integer map); each spawn bumps
  the slot via `update-in` and the returned snapshot carries the bumped
  value. The function is now deterministic from its arguments — the
  property this test locks in.

  Two flavours of the property:

   1. **Identical args → identical results.** Call
      `machine-transition` twice with the SAME arguments and assert
      the returned pair (snapshot + effects vector) is `=` to the
      first call's pair.

   2. **No global state.** The two calls happen in arbitrary order;
      neither alters any module-level state that the other observes.
      Concretely: a third call with a DIFFERENT snapshot starting at
      counter 0 still allocates `:worker#1`, not `:worker#3`.
  "
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as machines]))

(def auth-flow-spec
  "A tiny declarative-`:invoke` machine. On `[:submit]` from `:idle` it
  transitions to `:authenticating`, an :invoke-bearing state that emits
  one `:rf.machine/spawn` fx for an `:http/post` child."
  {:initial :idle
   :data    {}
   :states  {:idle           {:on {:submit :authenticating}}
             :authenticating {:invoke {:machine-id :http/post
                                       :data       {:url "/api/login"}
                                       :start      [:begin]}
                              :on    {:auth/succeeded :authenticated
                                      :auth/failed    :idle}}
             :authenticated  {}}})

(deftest machine-transition-is-pure
  (testing "identical args produce identical [next-snapshot effects] pairs"
    (let [snap     {:state :idle :data {}}
          event    [:submit]
          [snap1 fx1] (machines/machine-transition auth-flow-spec snap event)
          [snap2 fx2] (machines/machine-transition auth-flow-spec snap event)]
      (is (= snap1 snap2)
          "two pure-call invocations from the same input produce the same next-snapshot")
      (is (= fx1 fx2)
          "two pure-call invocations from the same input produce the same effects vector")
      ;; The spawn-id allocated by both calls must be byte-identical —
      ;; pre-rf2-gr8q the second call would have produced `:http/post#2`.
      (is (= [[:rf.machine/spawn {:machine-id    :http/post
                                  :id-prefix     :http/post
                                  :data          {:url "/api/login"}
                                  :start         [:begin]
                                  :rf/spawned-id :http/post#1
                                  :rf/parent-id  :rf/transition-pure
                                  :rf/invoke-id  [:authenticating]}]]
             fx1)
          "the spawn fx carries `:http/post#1` deterministically")
      (is (= {:state            :authenticating
              :data             {}
              :rf/spawn-counter {:http/post 1}}
             snap1)
          "the in-snapshot counter advances; the snapshot now carries `:rf/spawn-counter`")))

  (testing "different input snapshots allocate independently — no shared module-level counter"
    ;; Two separate input snapshots, each starting at counter 0, both
    ;; allocate `:http/post#1`. Pre-rf2-gr8q the second would have
    ;; produced `:http/post#2` because the atom carried over.
    (let [snap-a {:state :idle :data {:tag :a}}
          snap-b {:state :idle :data {:tag :b}}
          [_ fx-a] (machines/machine-transition auth-flow-spec snap-a [:submit])
          [_ fx-b] (machines/machine-transition auth-flow-spec snap-b [:submit])]
      (is (= :http/post#1 (-> fx-a first second :rf/spawned-id))
          "first snapshot's spawn is :http/post#1")
      (is (= :http/post#1 (-> fx-b first second :rf/spawned-id))
          "second (independent) snapshot's spawn is also :http/post#1 — no shared counter")))

  (testing "a snapshot whose counter is pre-populated keeps allocating from where it left off"
    ;; This is the in-snapshot allocator contract: the same snapshot
    ;; threaded through multiple transitions accumulates spawn-id
    ;; sequencing. We model it by manually pre-stamping the counter at
    ;; 3 and then driving a transition.
    (let [snap     {:state            :idle
                    :data             {}
                    :rf/spawn-counter {:http/post 3}}
          [snap' fx] (machines/machine-transition auth-flow-spec snap [:submit])]
      (is (= :http/post#4 (-> fx first second :rf/spawned-id))
          "spawn allocates :http/post#4 — bump of the pre-existing 3")
      (is (= {:http/post 4} (:rf/spawn-counter snap'))
          "the returned snapshot's counter is at 4"))))

(deftest invoke-all-counter-bumps-per-child
  (testing ":invoke-all spawns N children — each child of the same machine-id bumps the same counter slot"
    (let [spec {:initial :idle
                :data    {}
                :states  {:idle      {:on {:start :working}}
                          :working   {:invoke-all
                                      {:children [{:id :a :machine-id :worker}
                                                  {:id :b :machine-id :worker}
                                                  {:id :c :machine-id :worker}]
                                       :join     :all}
                                      :on        {:done :ready}}
                          :ready     {}}}
          [snap' _fx] (machines/machine-transition spec {:state :idle :data {}} [:start])]
      (is (= {:worker 3} (:rf/spawn-counter snap'))
          "three :worker children bumped the slot to 3"))))
