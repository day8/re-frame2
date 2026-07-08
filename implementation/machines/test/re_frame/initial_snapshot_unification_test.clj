(ns re-frame.initial-snapshot-unification-test
  "Pinning tests for the unified `build-initial-snapshot` helper in
  `re-frame.machines.parallel`. Both the singleton-registration path and
  the spawn path build their initial snapshot through this one helper, so
  both always stamp two slots:

    - `:rf/spawn-counter {}` — the in-snapshot id allocator. A spawned
      actor whose `:entry` declares a `:spawn` reads the next id from this
      present slot via the contract path of `allocate-spawned-id` (the
      defensive `(fnil inc 0)` backstop is never reached).

    - `:meta` — per Spec 005 §Snapshot shape, a spec's optional `:meta`
      propagates onto the snapshot so 3-arity ctx and downstream
      version checks see the same `:meta` the spec declares.

  These tests pin the unified behaviour at both ends: the helper itself
  (unit-level) and the end-to-end spawn path (integration-level)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.machines.parallel :as parallel]
            [re-frame.machines.test-support :as mtest]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

;; This ns keeps a bespoke reset-runtime fixture (clear-all! + frame reset +
;; machines :reload + reset-timers!) — it exercises registration-time
;; snapshot unification, so it deliberately reloads the machines ns rather
;; than reusing the shared make-reset-runtime-fixture.
(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  ;; `init!` does not synthesise `:rf/default`, and machine fxs require a
  ;; carried frame stamp. Register `:rf/default` explicitly and pin it as
  ;; the established scope for the body.
  (frame/ensure-default-frame!)
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (rf/with-frame :rf/default
    (test-fn)))

(use-fixtures :each reset-runtime)

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

;; ---- (1) `parallel/build-initial-snapshot` unit contract -------------------
;;
;; Pin the helper's shape directly. Both `:rf/spawn-counter` and `:meta`
;; (when declared) are always present on the synthesised snapshot;
;; `:rf/bootstrap-pending?` is opt-in via the option map.

(deftest build-initial-snapshot-stamps-spawn-counter-and-meta
  (testing "non-bootstrap call — singleton-registration shape"
    (let [spec {:initial :idle
                :data    {:counter 0}
                :meta    {:schema-version 7}
                :states  {:idle {}}}
          snap (parallel/build-initial-snapshot spec {:bootstrap-pending? false})]
      (is (contains? snap :rf/spawn-counter)
          ":rf/spawn-counter MUST always be present on live snapshots (rf2-gr8q)")
      (is (= {} (:rf/spawn-counter snap))
          "starts empty — the slot exists so allocations go through the contract path")
      (is (= {:schema-version 7} (:meta snap))
          ":meta from the spec MUST propagate onto the snapshot (Spec 005 §Snapshot shape)")
      (is (not (contains? snap :rf/bootstrap-pending?))
          "the singleton path stamps :rf/bootstrap-pending? lazily in `prepare-machine-ctx`, not here")))
  (testing "bootstrap call — spawn-path shape"
    (let [spec {:initial :idle
                :data    {:counter 0}
                :meta    {:schema-version 7}
                :states  {:idle {}}}
          snap (parallel/build-initial-snapshot spec {:bootstrap-pending? true})]
      (is (contains? snap :rf/spawn-counter)
          "the spawn-path snapshot ALSO carries :rf/spawn-counter — pre-rf2-fgqs4 this slot was missing")
      (is (= {} (:rf/spawn-counter snap)))
      (is (= {:schema-version 7} (:meta snap))
          "the spawn-path snapshot ALSO carries :meta — pre-rf2-fgqs4 this slot was missing")
      (is (true? (:rf/bootstrap-pending? snap))
          "the spawn path stamps the bootstrap marker so the actor's first dispatch fires the :entry cascade (rf2-0z73)")))
  (testing ":meta absent from spec → :meta absent from snapshot"
    (let [spec {:initial :idle :data {} :states {:idle {}}}
          snap (parallel/build-initial-snapshot spec {:bootstrap-pending? true})]
      (is (not (contains? snap :meta))
          "no :meta in spec — the helper does not invent one"))))

;; ---- (2) End-to-end spawn integration: :meta propagates --------------------
;;
;; A spawned actor whose spec declares `:meta` MUST carry that `:meta`
;; on its initial snapshot at `[:rf.runtime/machines :snapshots <spawned-id>]`,
;; so any `^:rf.machine/wants-ctx` action introspecting `:meta` sees it.

(deftest spawned-actor-snapshot-carries-meta
  (testing "a spawned actor whose spec declares :meta has it on its snapshot"
    (let [child  {:initial :running
                  :data    {}
                  :meta    {:schema-version 7
                            :user-tag       :probe}
                  :states  {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :worker/proc}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/main parent)
      (rf/dispatch-sync [:sup/main [:start]])
      (let [spawned-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                               [:rf.runtime/machines :spawned :sup/main [:working]])
            child-snap (snapshot spawned-id)]
        (is (= :worker/proc#1 spawned-id) "(precondition) spawn happened")
        (is (some? child-snap) "(precondition) snapshot installed")
        (is (= {:schema-version 7 :user-tag :probe} (:meta child-snap))
            "spawned actor's snapshot carries the spec's :meta (rf2-fgqs4 fix)")))))

;; ---- (3) End-to-end spawn integration: :rf/spawn-counter slot present ------
;;
;; A spawned actor's initial snapshot MUST carry `:rf/spawn-counter` so
;; an `:entry`-declared `:spawn` on the actor's initial state goes
;; through the contract path of `allocate-spawned-id` (reading from a
;; present slot) rather than the defensive `(fnil inc 0)` backstop.

(deftest spawned-actor-snapshot-carries-spawn-counter
  (testing "a spawned actor's snapshot carries :rf/spawn-counter (rf2-gr8q contract)"
    (let [child  {:initial :running
                  :data    {}
                  :states  {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:start :working}}
                            :working {:spawn {:machine-id :worker/proc}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/main parent)
      (rf/dispatch-sync [:sup/main [:start]])
      (let [spawned-id (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                               [:rf.runtime/machines :spawned :sup/main [:working]])
            child-snap (snapshot spawned-id)]
        (is (some? child-snap) "(precondition) snapshot installed")
        (is (contains? child-snap :rf/spawn-counter)
            "spawned actor's snapshot carries :rf/spawn-counter (rf2-fgqs4 fix)")))))
