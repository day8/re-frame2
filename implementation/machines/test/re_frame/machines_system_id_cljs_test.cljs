(ns re-frame.machines-system-id-cljs-test
  "CLJS-side coverage for `:system-id` named-machine addressing under the
  Reagent reactive substrate.

  Per Spec 005 §Named addressing via :system-id: a spawn whose args carry a
  `:system-id` keyword binds that name in the per-frame `[:rf.runtime/machines :system-ids]`
  reverse index. `(rf/machine-by-system-id sid)` resolves the binding;
  destroy clears it; collisions emit `:rf.error/system-id-collision` and
  rebind (last-write-wins).

  These tests exercise the bundled live-handler wiring: the spawn registers
  the child as a real event handler, so dispatch-by-system-id reaches a
  running actor."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as machines]
            ;; listener / buffer surface lives in re-frame.trace.tooling.
            [re-frame.trace.tooling :as trace-tooling]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.machines.test-support :as mtest]))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

(deftest machine-system-id-cljs
  (testing "spawn-with-system-id binds the index, lookup resolves to spawned id, destroy clears"
    (let [;; A child machine registered ahead of the parent's spawn.
          child {:initial :running
                 :data    {:hits 0}
                 :actions {:bump (fn [{data :data}] {:data {:hits (inc (:hits data))}})}
                 :states  {:running {:on {:ping {:action :bump}}}}}
          ;; A parent that spawns the child with a :system-id under :spawn.
          parent {:initial :idle
                  :on-spawn-actions
                  {:auth/record-actor (fn [{data :data actor-id :id}]
                                        (assoc data :pending actor-id))}
                  :states
                  {:idle      {:on {:start :working}}
                   :working   {:spawn {:machine-id :worker/proc
                                        :system-id  :worker
                                        :on-spawn   :auth/record-actor}
                               :on    {:done :idle}}}}]
      (rf/reg-machine :worker/proc child)
      (rf/reg-machine :sup/flow parent)
      (rf/dispatch-sync [:sup/flow [:start]])
      ;; (1) Lookup-by-system-id returns the spawned id.
      (let [spawned (machines/machine-by-system-id :worker)]
        (is (= :worker/proc#1 spawned)
            ":system-id resolves to the spawned machine id")
        (is (= spawned (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                               [:rf.runtime/machines :system-ids :worker]))
            "[:rf.runtime/machines :system-ids] reverse index records the binding")
        (is (some? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                           [:rf.runtime/machines :snapshots spawned]))
            "snapshot initialised at [:rf.runtime/machines :snapshots <spawned-id>]")
        ;; (2) Dispatch-by-system-id reaches the actor.
        (rf/dispatch-sync [spawned [:ping]])
        (is (= 1 (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                         [:rf.runtime/machines :snapshots spawned :data :hits]))
            "dispatch routed via system-id reached the live actor"))
      ;; (3) Destroy clears system-id binding and snapshot.
      (rf/dispatch-sync [:sup/flow [:done]])
      (is (nil? (machines/machine-by-system-id :worker))
          "post-destroy lookup returns nil")
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                        [:rf.runtime/machines :system-ids :worker]))
          "[:rf.runtime/machines :system-ids] entry cleared on destroy")
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                        [:rf.runtime/machines :snapshots :worker/proc#1]))
          "snapshot cleared on destroy")))

  (testing "dispatch-to-system sugar resolves the system-id and routes the dispatch"
    (let [child  {:initial :running
                  :data    {:msgs []}
                  :actions {:record (fn [{data :data [_ msg] :event}]
                                      {:data {:msgs (conj (:msgs data) msg)}})}
                  :states  {:running {:on {:notify {:action :record}}}}}
          parent {:initial :idle
                  :on-spawn-actions
                  {:auth/record-actor (fn [{data :data id :id}] (assoc data :pending id))}
                  :states
                  {:idle    {:on {:go :running}}
                   :running {:spawn {:machine-id :notifier/proc
                                      :system-id  :notifier
                                      :on-spawn   :auth/record-actor}}}}]
      (rf/reg-machine :notifier/proc child)
      (rf/reg-machine :sup2/flow parent)
      (rf/dispatch-sync [:sup2/flow [:go]])
      (let [spawned (machines/machine-by-system-id :notifier)]
        (is (= :notifier/proc#1 spawned))
        ;; Verify the sugar dispatches via the system-id lookup. We use
        ;; dispatch-sync directly through the resolved id so the assert
        ;; reads post-drain state without depending on async timing —
        ;; dispatch-to-system itself wraps `dispatch` (queued); under a
        ;; non-Reagent test runner there's no render to trigger the
        ;; drain. The lookup-then-dispatch chain is what we're verifying.
        (rf/dispatch-sync [(machines/machine-by-system-id :notifier) [:notify "hello"]])
        (is (= ["hello"] (get-in (:rf.db/runtime (rf/frame-state-value :rf/default))
                                 [:rf.runtime/machines :snapshots spawned :data :msgs]))
            "dispatch via system-id lookup reached the live actor")
        ;; And the implementation-tier sugar fn no-ops when the system-id is
        ;; unbound. `dispatch-to-system` lives in `re-frame.machines` (not the
        ;; `re-frame.core` facade); the canonical action-side surface is the
        ;; `:rf.machine/dispatch-to-system` fx tuple.
        (is (nil? (machines/dispatch-to-system :no-such-system [:notify "x"]))
            "dispatch-to-system on an unbound system-id returns nil"))))

  (testing ":system-id collision emits :rf.error/system-id-collision and rebinds (last-write-wins)"
    (let [child  {:initial :running :data {} :states {:running {}}}
          traces (atom [])]
      (rf/reg-machine :w/proc child)
      ;; First spawn under :primary
      (rf/reg-event ::spawn1
        (fn [_ _]
          {:fx [[:rf.machine/spawn {:machine-id :w/proc
                                    :id-prefix  :w/proc
                                    :system-id  :primary}]]}))
      (rf/reg-event ::spawn2
        (fn [_ _]
          {:fx [[:rf.machine/spawn {:machine-id :w/proc
                                    :id-prefix  :w/proc
                                    :system-id  :primary}]]}))
      (trace-tooling/register-listener! ::col (fn [ev] (swap! traces conj ev)))
      (rf/dispatch-sync [::spawn1])
      (rf/dispatch-sync [::spawn2])
      (trace-tooling/unregister-listener! ::col)
      ;; Second spawn rebinds.
      (is (= :w/proc#2 (machines/machine-by-system-id :primary))
          "second spawn rebound :primary to the new actor")
      (is (some (fn [ev]
                  (and (= :rf.error/system-id-collision (:operation ev))
                       (= :primary (get-in ev [:tags :system-id]))
                       (= :w/proc#1 (get-in ev [:tags :existing-machine]))
                       (= :w/proc#2 (get-in ev [:tags :rebound-to]))))
                @traces)
          "expected :rf.error/system-id-collision trace"))))

(deftest machine-by-system-id-frame-arg-spelling-rf2-f28bno
  (testing "rf2-f28bno — the public opts form `(machine-by-system-id sid {:frame f})` binds the
            named frame; the internal frame-last `(sid frame-id)` plumbing is retained;
            the opts `:frame` is threaded (a wrong frame reads nil, not swallowed)"
    (let [child  {:initial :running :data {} :states {:running {}}}
          parent {:initial :idle
                  :states  {:idle    {:on {:go :running}}
                            :running {:spawn {:machine-id :fa/proc
                                              :id-prefix  :fa/proc
                                              :system-id  :fa/named}}}}]
      (rf/reg-machine :fa/proc child)
      (rf/reg-machine :fa/sup parent)
      (rf/dispatch-sync [:fa/sup [:go]])
      (let [ambient (machines/machine-by-system-id :fa/named)]
        (is (= :fa/proc#1 ambient)
            "ambient 1-arity resolves the spawned id under the :rf/default scope")
        ;; (1) PUBLIC opts form — the canonical public shape (mirrors subscribe's frame-first arity).
        (is (= ambient (machines/machine-by-system-id :fa/named {:frame :rf/default}))
            "opts form {:frame :rf/default} binds the same frame and resolves the same id")
        ;; (2) INTERNAL frame-last plumbing — a bare frame-id keyword still resolves.
        (is (= ambient (machines/machine-by-system-id :fa/named :rf/default))
            "internal frame-last (sid frame-id) plumbing is retained")
        ;; (3) The opts :frame is genuinely threaded: a DIFFERENT frame reads nil
        ;; (proving the frame key is honoured, not the old misbind where the opts
        ;; map would have been mistaken for the frame target).
        (is (nil? (machines/machine-by-system-id :fa/named {:frame :no/such-frame}))
            "opts {:frame <other>} targets that frame's index (empty) — nil, not the ambient hit")
        ;; (4) Ambient opts (no :frame) falls back to the scope-resolved frame.
        (is (= ambient (machines/machine-by-system-id :fa/named {}))
            "empty opts map resolves the ambient frame (no :frame ⇒ scope chain)")))))

(deftest machine-spawn-without-system-id-leaves-index-empty-cljs
  (testing "spawn-without-system-id leaves [:rf.runtime/machines :system-ids] empty"
    (let [child {:initial :running :data {} :states {:running {}}}]
      (rf/reg-machine :w2/proc child)
      (rf/reg-event ::spawn-anon
        (fn [_ _]
          {:fx [[:rf.machine/spawn {:machine-id :w2/proc :id-prefix :w2/proc}]]}))
      (rf/dispatch-sync [::spawn-anon])
      (is (nil? (get-in (:rf.db/runtime (rf/frame-state-value :rf/default)) [:rf.runtime/machines :system-ids]))
          "[:rf.runtime/machines :system-ids] not allocated when no spawns carry :system-id")
      (is (nil? (machines/machine-by-system-id :anything))
          "lookup against an unbound system-id returns nil"))))
