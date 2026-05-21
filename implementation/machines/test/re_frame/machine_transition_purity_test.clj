(ns re-frame.machine-transition-purity-test
  "Per rf2-gr8q. Locks in the contract that `machine-transition` is an
  honest pure function — identical (machine, snapshot, event) triples
  produce identical Result values (snapshot + effects vector) INCLUDING
  the spawn-id sequencing inside emitted `:rf.machine/spawn` fx maps.

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
      the returned Result (snapshot + effects vector) is `=` to the
      first call's Result.

   2. **No global state.** The two calls happen in arbitrary order;
      neither alters any module-level state that the other observes.
      Concretely: a third call with a DIFFERENT snapshot starting at
      counter 0 still allocates `:worker#1`, not `:worker#3`.
  "
  (:require [clojure.test :refer [deftest is testing]]
            [re-frame.machines :as machines]
            [re-frame.machines.result :as result]
            [re-frame.trace :as trace]))

(def auth-flow-spec
  "A tiny declarative-`:spawn` machine. On `[:submit]` from `:idle` it
  transitions to `:authenticating`, a :spawn-bearing state that emits
  one `:rf.machine/spawn` fx for an `:http/post` child."
  {:initial :idle
   :data    {}
   :states  {:idle           {:on {:submit :authenticating}}
             :authenticating {:spawn {:machine-id :http/post
                                       :data       {:url "/api/login"}
                                       :start      [:begin]}
                              :on    {:auth/succeeded :authenticated
                                      :auth/failed    :idle}}
             :authenticated  {}}})

(deftest machine-transition-is-pure
  (testing "identical args produce identical Result values"
    (let [snap     {:state :idle :data {}}
          event    [:submit]
          {snap1 ::result/snap fx1 ::result/fx} (machines/machine-transition auth-flow-spec snap event)
          {snap2 ::result/snap fx2 ::result/fx} (machines/machine-transition auth-flow-spec snap event)]
      (is (= snap1 snap2)
          "two pure-call invocations from the same input produce the same next-snapshot")
      (is (= fx1 fx2)
          "two pure-call invocations from the same input produce the same effects vector")
      ;; Per rf2-d0wem (informed by rf2-ra1he §TE1): assert only the
      ;; load-bearing slots of the emitted spawn fx — the contract — and
      ;; leave implementation-detail keys (`:id-prefix`, exact arg-map
      ;; shape, the user-passed `:data` / `:start` echo) free to evolve
      ;; without churning this test. The contract is:
      ;;   - the fx-id is `:rf.machine/spawn`
      ;;   - `:rf/spawned-id` is `:http/post#1` (allocator deterministic)
      ;;   - `:rf/parent-id` is `:rf/transition-pure` (sentinel for the
      ;;     pure-call surface)
      ;;   - `:rf/spawn-id` is the state-path the spawn issued from
      ;;     (`[:authenticating]`)
      (is (= 1 (count fx1))
          "exactly one effect emitted by the :submit transition")
      (let [[[fx-id args]] fx1]
        (is (= :rf.machine/spawn fx-id)
            "the emitted fx is :rf.machine/spawn")
        (is (= :http/post#1 (:rf/spawned-id args))
            "the spawned-id is allocated deterministically as :http/post#1")
        (is (= :rf/transition-pure (:rf/parent-id args))
            "parent-id sentinel for the pure-call surface is :rf/transition-pure")
        (is (= [:authenticating] (:rf/spawn-id args))
            "invoke-id is the state-path the spawn issued from"))
      ;; Snapshot: the load-bearing contract is that `:state` advanced and
      ;; the in-snapshot counter bumped to 1 for the `:http/post` slot.
      ;; The exact key-set of the snapshot map (e.g. whether the counter
      ;; root is `:rf/spawn-counter` or evolves under refactor) is not
      ;; load-bearing for this contract — assert the counter slot via
      ;; `get-in`.
      (is (= :authenticating (:state snap1))
          "snapshot's :state advances to :authenticating")
      (is (= {} (:data snap1))
          "user-facing :data is unchanged (the :http/post :data is on the spawn fx, not in the parent snapshot)")
      (is (= 1 (get-in snap1 [:rf/spawn-counter :http/post]))
          "the in-snapshot counter advanced to 1 for the :http/post slot")))

  (testing "different input snapshots allocate independently — no shared module-level counter"
    ;; Two separate input snapshots, each starting at counter 0, both
    ;; allocate `:http/post#1`. Pre-rf2-gr8q the second would have
    ;; produced `:http/post#2` because the atom carried over.
    (let [snap-a {:state :idle :data {:tag :a}}
          snap-b {:state :idle :data {:tag :b}}
          {fx-a ::result/fx} (machines/machine-transition auth-flow-spec snap-a [:submit])
          {fx-b ::result/fx} (machines/machine-transition auth-flow-spec snap-b [:submit])]
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
          {snap' ::result/snap fx ::result/fx} (machines/machine-transition auth-flow-spec snap [:submit])]
      (is (= :http/post#4 (-> fx first second :rf/spawned-id))
          "spawn allocates :http/post#4 — bump of the pre-existing 3")
      (is (= {:http/post 4} (:rf/spawn-counter snap'))
          "the returned snapshot's counter is at 4"))))

(deftest invoke-all-counter-bumps-per-child
  (testing ":spawn-all spawns N children — each child of the same machine-id bumps the same counter slot"
    (let [spec {:initial :idle
                :data    {}
                :states  {:idle      {:on {:start :working}}
                          :working   {:spawn-all
                                      {:children [{:id :a :machine-id :worker}
                                                  {:id :b :machine-id :worker}
                                                  {:id :c :machine-id :worker}]
                                       :join     :all}
                                      :on        {:done :ready}}
                          :ready     {}}}
          {snap' ::result/snap} (machines/machine-transition spec {:state :idle :data {}} [:start])]
      (is (= {:worker 3} (:rf/spawn-counter snap'))
          "three :worker children bumped the slot to 3"))))

;; ---- Pure transition smoke (relocated from core/smoke_test.clj, rf2-zqar3) ----
;;
;; These pin baseline machine-transition behaviours — flat transitions,
;; :always microsteps, and pre-commit :raise routing. Co-located with the
;; allocator-purity contract above because they all exercise the pure
;; `machines/machine-transition` surface from argument to Result.

(deftest pure-machine-transition
  (testing "machine-transition is pure"
    (let [m {:id     :traffic-light
             :initial :red
             :data    {}
             :states
             {:red    {:on {:tick {:target :green}}}
              :green  {:on {:tick {:target :yellow}}}
              :yellow {:on {:tick {:target :red}}}}}]
      (let [{s1 ::result/snap} (machines/machine-transition m {:state :red :data {}} [:tick])]
        (is (= :green (:state s1))))
      (let [{s2 ::result/snap} (machines/machine-transition m {:state :green :data {}} [:tick])]
        (is (= :yellow (:state s2)))))))

(deftest machine-always-microstep
  (testing ":always fires once after the resolving event under a true guard"
    (let [m {:id     :auth
             :initial :checking
             :data    {:authed? true}
             :guards  {:authed? (fn [{data :data}] (:authed? data))}
             :states
             {:checking {:always [{:guard :authed? :target :authed}]}
              :authed   {}
              :idle     {}}}
          ;; Even with a no-op event (no match in :on), :always is checked
          ;; and the guard passes — transition to :authed.
          {s ::result/snap} (machines/machine-transition m {:state :checking :data {:authed? true}} [:noop])]
      (is (= :authed (:state s))))))

;; ---- depth-limit boundary parity (rf2-r26e2) ------------------------------
;;
;; Per Spec 005 §Bounded depth (005:1276) the `:always` microstep loop and
;; the `:raise` drain share the same default (16) and the same intent: a
;; limit of N permits exactly N steps before the cascade aborts uncommitted.
;; The `:always` loop bounds on `(>= depth limit)`; pre-rf2-r26e2 the
;; `:raise` drain bounded on `(> depth limit)`, which silently permitted one
;; extra recursion (N+1). These two tests pin the boundary to N on BOTH
;; paths so the operators can never drift apart again.
;;
;; The boundary is a pure-engine property, observed here via the `:depth`
;; tag the depth-exceeded error trace carries: with the `>=` boundary the
;; abort fires at `depth == limit`, so the trace's `:depth` equals the limit
;; (not limit+1).

(defn- capture-error-depth!
  "Drive a pure `machine-transition` while a tooling listener records traces,
  returning the `:depth` tag of the first error trace whose `:operation`
  matches `error-op` (or nil if none fired)."
  [error-op definition snapshot event]
  (let [seen (atom [])]
    (trace/register-listener! ::depth-probe (fn [ev] (swap! seen conj ev)))
    (try
      (machines/machine-transition definition snapshot event)
      (finally (trace/unregister-listener! ::depth-probe)))
    (->> @seen
         (filter #(= error-op (:operation %)))
         first
         :tags
         :depth)))

(deftest always-depth-boundary-permits-exactly-limit-microsteps
  (testing ":always loop with :always-depth-limit N aborts at depth N
   (>= boundary) — permits exactly N microsteps"
    ;; Two states ping-pong via always-true `:always` guards. With the
    ;; limit set to 4 the loop runs microsteps at depths 0..3, then aborts
    ;; at depth 4. The error trace's `:depth` is therefore 4 (== the limit).
    (let [spec {:initial :start
                :data    {}
                :always-depth-limit 4
                :guards  {:p? (fn [_] true)}
                :states  {:start {:on {:go {:target :a}}}
                          :a     {:always [{:guard :p? :target :b}]}
                          :b     {:always [{:guard :p? :target :a}]}}}
          depth (capture-error-depth!
                  :rf.error/machine-always-depth-exceeded
                  spec {:state :start :data {}} [:go])]
      (is (= 4 depth)
          ":always aborts at depth == limit (4), permitting exactly 4 microsteps"))))

(deftest raise-depth-boundary-matches-always-boundary
  (testing ":raise drain with :raise-depth-limit N aborts at depth N
   (>= boundary, rf2-r26e2) — parity with the :always loop, not N+1"
    ;; The `drain-raises` depth counts raises drained from the queue. A
    ;; fanned-out batch of more raises than the limit feeds the loop past
    ;; the bound (same shape as raise-depth-exceeded-tag-carries-frame).
    ;; With :raise-depth-limit 4 and 6 raises in one batch the drain
    ;; processes raises at depths 0..3 then aborts at depth 4 — the SAME
    ;; boundary the :always loop above hits at its limit. Pre-rf2-r26e2
    ;; (`> depth limit`) the abort fired at depth 5 (N+1), one past parity.
    (let [spec {:initial :idle
                :data    {}
                :raise-depth-limit 4
                :actions {:fan-out (fn [_]
                                     {:fx [[:raise [:noop]]
                                           [:raise [:noop]]
                                           [:raise [:noop]]
                                           [:raise [:noop]]
                                           [:raise [:noop]]
                                           [:raise [:noop]]]})}
                :states  {:idle    {:on {:start {:target :running :action :fan-out}
                                         :noop  :idle}}
                          :running {:on {:noop :idle}}}}
          depth (capture-error-depth!
                  :rf.error/machine-raise-depth-exceeded
                  spec {:state :idle :data {}} [:start])]
      (is (= 4 depth)
          ":raise aborts at depth == limit (4) — same boundary as :always (was 5 pre-fix)"))))

(deftest machine-raise-pre-commit
  (testing ":raise routes locally pre-commit (does not go to runtime fifo)"
    (let [calls (atom [])
          m {:id      :counter
             :initial :idle
             :data    {:n 0}
             :actions {:start (fn [_]
                                {:fx [[:raise [:bump]] [:raise [:bump]]]})
                       :bump  (fn [{data :data}]
                                {:data {:n (inc (:n data))}})}
             :states
             {:idle {:on {:start {:target :busy :action :start}
                          :bump  {:action :bump}}}
              :busy {:on {:bump {:action :bump}}}}}
          {s ::result/snap fx ::result/fx} (machines/machine-transition m {:state :idle :data {:n 0}} [:start])]
      ;; Two raised :bump events should have been processed pre-commit;
      ;; final data :n should be 2.
      (is (= 2 (:n (:data s))))
      ;; No :raise should escape to the outer fx.
      (is (not (some #{:raise} (map first fx)))))))
