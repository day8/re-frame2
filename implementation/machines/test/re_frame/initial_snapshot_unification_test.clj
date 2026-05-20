(ns re-frame.initial-snapshot-unification-test
  "Per rf2-fgqs4 — pinning tests for the unified `build-initial-snapshot`
  helper in `re-frame.machines.parallel`. Pre-rf2-fgqs4 the spawn path
  had its own `compute-initial-snapshot` that silently OMITTED two slots
  the singleton-registration path stamped:

    - `:rf/spawn-counter {}` — the in-snapshot id allocator (rf2-gr8q).
      A spawned actor whose `:entry` declares a `:spawn` would fall
      onto `allocate-spawned-id`'s defensive `(fnil inc 0)` backstop
      instead of the contract path of reading from a present slot.

    - `:meta` — per Spec 005 §Snapshot shape, a spec's optional `:meta`
      propagates onto the snapshot so 3-arity ctx and downstream
      version checks see the same `:meta` the spec declares. The spawn
      path dropped it on the floor.

  These tests pin the unified behaviour at both ends: the helper itself
  (unit-level) and the end-to-end spawn path (integration-level).

  Pre-fix the integration tests below FAIL on the spawn path; post-fix
  they PASS on both paths."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.machines :as machines]
            [re-frame.machines.parallel :as parallel]
            [re-frame.registrar :as registrar]
            [re-frame.substrate.plain-atom :as plain-atom]))

(defn- reset-runtime [test-fn]
  (registrar/clear-all!)
  (reset! frame/frames {})
  (rf/init! plain-atom/adapter)
  (require 're-frame.machines :reload)
  (machines/reset-timers!)
  (test-fn))

(use-fixtures :each reset-runtime)

(defn- snapshot
  "Read the snapshot for `machine-id` from the default frame's app-db."
  [machine-id]
  (get-in (rf/get-frame-db :rf/default) [:rf/machines machine-id]))

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
;; on its initial snapshot at `[:rf/machines <spawned-id>]`. Pre-rf2-fgqs4
;; the spawn-path helper silently dropped `:meta`, so any
;; `^:rf.machine/wants-ctx` action introspecting `:meta` saw nil.

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
      (let [spawned-id (get-in (rf/get-frame-db :rf/default)
                               [:rf/spawned :sup/main [:working]])
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
;; Pre-rf2-fgqs4 the spawn-path snapshot lacked the slot entirely.

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
      (let [spawned-id (get-in (rf/get-frame-db :rf/default)
                               [:rf/spawned :sup/main [:working]])
            child-snap (snapshot spawned-id)]
        (is (some? child-snap) "(precondition) snapshot installed")
        (is (contains? child-snap :rf/spawn-counter)
            "spawned actor's snapshot carries :rf/spawn-counter (rf2-fgqs4 fix)")))))
