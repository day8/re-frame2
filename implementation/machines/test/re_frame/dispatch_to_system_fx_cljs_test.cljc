(ns re-frame.dispatch-to-system-fx-cljs-test
  "Regression for the `:rf.machine/dispatch-to-system` fx.

  Spec 005 §Cross-machine messaging by name documents the action-side
  mechanism for a machine to send a message to its spawned child actor
  addressed by `:system-id`: a machine ACTION emits
  `[:rf.machine/dispatch-to-system [<system-id> <event-vector>]]` from its
  `:fx` vector. (Actions can't read app-db and `:on-spawn`'s return is
  dropped, so the fx form — not a captured-id dispatch — is how an action
  reaches a NAMED child.)

  The fx is registered in `re-frame.machines`. The headline property here:
  a machine action emits the fx and the spawned actor's snapshot shows the
  message landed — i.e. the fx id is handled and routes the dispatch.

  Args shape is the single 2-element pair `[<system-id> <event-vector>]`
  (the framework fx contract is a `[fx-id args]` pair; `do-fx` drops
  arity-≥3 entries), matching `:rf.machine/spawn`'s single-map args and
  `:dispatch`'s single-event-vector args.

  Deterministic harness (mirrors `machine_front_of_queue_test`): one
  `dispatch-sync` drain. The action's fx queues the child dispatch, which
  rides the in-progress sync drain and pops in queue order — so the assert
  reads post-drain state without async timing.

  Named `*-cljs-test.cljc` so both cognitect.test-runner (JVM, plain-atom)
  and shadow-cljs (CLJS, reagent) discover it — the engine + fx are
  identical across runtimes."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; requiring `re-frame.machines` wires the machines artefact into the
   ;; late-bind registry so `rf/reg-machine` resolves AND registers the
   ;; `:rf.machine/dispatch-to-system` fx under test.
   [re-frame.machines :as machines]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter})))

;; snapshot lookup via the shared machines test-support
;; — no hardcoded `[:rf.runtime/machines :snapshots …]` path.
(def ^:private snapshot mtest/snapshot)

(deftest dispatch-to-system-fx-reaches-spawned-actor
  (testing "a machine action's [:rf.machine/dispatch-to-system [<sys> [:msg]]]
   reaches the spawned actor bound to that :system-id (the fx is handled)"
    ;; Child actor: records every :notify payload into its :data.
    (rf/reg-machine :notifier/proc
      {:initial :running
       :data    {:msgs []}
       :actions {:record (fn [{data :data [_ msg] :event}]
                           {:data {:msgs (conj (:msgs data) msg)}})}
       :states  {:running {:on {:notify {:action :record}}}}})
    ;; Parent: spawns the child under :system-id :notifier, then on :ping
    ;; messages the child purely by NAME via the fx (no captured id).
    (rf/reg-machine :sup/flow
      {:initial :idle
       :states  {:idle    {:on {:go :running}}
                 :running {:spawn {:machine-id :notifier/proc
                                    :system-id  :notifier}
                           :on    {:ping
                                   {:action
                                    (fn [_]
                                      {:fx [[:rf.machine/dispatch-to-system
                                             [:notifier [:notify "hello"]]]]})}}}}})
    (rf/dispatch-sync [:sup/flow [:go]])
    (let [spawned (machines/machine-by-system-id :notifier)]
      (is (= :notifier/proc#1 spawned)
          ":system-id resolves to the spawned actor")
      (is (= [] (get-in (snapshot spawned) [:data :msgs]))
          "child starts with no messages")
      ;; The action emits the fx; the queued child dispatch drains in-line.
      (rf/dispatch-sync [:sup/flow [:ping]])
      (is (= ["hello"] (get-in (snapshot spawned) [:data :msgs]))
          "the fx routed the dispatch to the named actor — the message landed"))))

(deftest dispatch-to-system-fx-no-ops-on-unbound-system-id
  (testing "the fx is a silent no-op when the :system-id is unbound
   (symmetric with the dispatch-to-system FN's no-op fall-through)"
    ;; A bare event handler emits the fx with a name nothing is bound to.
    ;; The walk must NOT raise :rf.error/no-such-fx (the fx is registered)
    ;; and must NOT throw — it simply finds no binding and does nothing.
    (rf/reg-event ::poke
      (fn [_ _]
        {:fx [[:rf.machine/dispatch-to-system [:nobody [:whatever]]]]}))
    (is (nil? (rf/dispatch-sync [::poke]))
        "emitting the fx against an unbound system-id is a harmless no-op")
    (is (nil? (machines/machine-by-system-id :nobody))
        "the unbound name still resolves to nil")))
