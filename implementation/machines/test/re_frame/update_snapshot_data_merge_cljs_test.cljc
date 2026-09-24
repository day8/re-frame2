(ns re-frame.update-snapshot-data-merge-cljs-test
  "The `:rf.machine/update-snapshot` escape hatch's `:data` leg
  MERGES onto the actor's existing `:data` rather than REPLACING it, which is
  exactly what an action's `{:data ...}` return already does.

  Why this needs pinning at all. `:data` is not only the user's working
  memory: the runtime keeps framework-owned reserved `:rf/*` slots in there
  that the programmer never sees and could not carry forward by hand even if
  they wanted to. A wholesale replace would drop them SILENTLY, so the
  documented idiom `{:rf/patch {:data {:status :degraded}}}` — a patch that
  mentions one user key and nothing else — would break the actor's runtime in
  two ways at once.

  The two failures are pinned separately below because they FAIL DIFFERENTLY,
  and a test covering one leaves the other free to regress unnoticed:

    (a) `:after` timers go SILENT. The per-decl-path epoch map lives at
        `[:data :rf/after-epoch]`, and `transition/node-epoch` falls back to
        `(or ... 0)` when it is missing. So a wiped `:data` makes every
        in-flight timer — armed at a NON-ZERO epoch, since entry bumps via
        `(fnil inc 0)` — carry an epoch that no longer matches, and the
        runtime discards it as stale. Nothing throws; the timer simply never
        arrives. That is the worst shape a bug can take.

    (b) A spawned child finishes as a SINGLETON. `:rf/parent-id` lives on the
        child's `:data`, and `lifecycle-fx.finalize` reads it to decide who is
        waiting on the child. Wiped, the child completes with `:parent-id nil`
        — the singleton path — so the parent's `:spawn :on-done` fold never
        runs and the parent waits forever for a child that already finished.

  Both tests drive the escape hatch from a PLAIN event handler rather than
  from inside a machine action. That is deliberate for (a): a self-transition
  carrying the patch would EXIT and RE-ENTER the `:after`-bearing state, which
  re-arms the timer and bumps the epoch — masking the very thing under test.
  The escape hatch is documented as emittable from any event handler's `:fx`.

  Named `*_cljs_test.cljc` so both the JVM runner and the shadow-cljs
  `:node-test` build discover it — the engine is identical across runtimes."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; requiring `re-frame.machines` wires the artefact into the late-bind
   ;; registry so `rf/reg-machine` resolves.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter})))

;; snapshot lookup via the shared machines test-support — no hardcoded
;; `[:rf.runtime/machines :snapshots ...]` path.
(def ^:private snapshot rf.machines.test-support/snapshot)

(defn- spawned-id
  "The child id bound at `[:rf.runtime/machines :spawned <parent> <invoke-id>]`."
  [parent-id invoke-id]
  (-> (:rf.db/runtime (rf/frame-state-value :rf/default))
      (get-in [:rf.runtime/machines :spawned parent-id invoke-id])))

;; One generic driver for both pins: emit the escape hatch from an ordinary
;; event handler's `:fx`, targeting whichever actor the event names. Keeping
;; the patch OUT of a machine action is what makes the `:after` pin honest.
;;
;; Registered from INSIDE each test rather than at ns top level. The reset
;; fixture captures its registrar baseline when the `use-fixtures` form above
;; is evaluated and folds that baseline back before each test, so a top-level
;; registration sitting BELOW that form is stranded — the handler silently
;; vanishes and the patch dispatch becomes a no-op. That fails in the
;; reassuring direction (the snapshot simply keeps its old `:data`), and it
;; only bites in a FULL-lane run, where a sibling namespace's fixture has
;; already reset the registrar; a focused single-ns run passes.
(defn- reg-patch-event! []
  (rf/reg-event :rf2-0hi3x/patch-data
    (fn [_ [_ actor-id data-patch]]
      {:fx [[:rf.machine/update-snapshot
             {:rf/machine-id actor-id
              :rf/patch      {:data data-patch}}]]})))

;; ---------------------------------------------------------------------------
;; (a) the `:after` timer stays LIVE across a user-domain `:data` patch
;; ---------------------------------------------------------------------------

(deftest update-snapshot-data-patch-keeps-after-timer-live
  (testing "a `{:data {:status :degraded}}` patch leaves the actor's in-flight
            :after timer LIVE — the reserved :rf/after-epoch map survives, so
            the timer still arrives instead of being silently dropped as stale"
    (reg-patch-event!)
    (rf/reg-machine :rf2-0hi3x/timer
      {:initial :idle
       :data    {:status :ok}
       :states  {:idle    {:on {:fetch :loading}}
                 :loading {:after {5000 :timeout}}
                 :timeout {}}})
    (rf/dispatch-sync [:rf2-0hi3x/timer [:fetch]])
    (let [epoch (get-in (snapshot :rf2-0hi3x/timer)
                        [:data :rf/after-epoch [:loading]])]
      ;; Preconditions. The epoch MUST be non-zero for this pin to bite: a
      ;; timer armed at epoch 0 would still match the `(or ... 0)` fallback a
      ;; wipe leaves behind, and the test would pass vacuously against a
      ;; replacing :data leg.
      (is (= :loading (:state (snapshot :rf2-0hi3x/timer)))
          "precondition: the :after-bearing state is occupied")
      (is (= 1 epoch)
          "precondition: entry armed the timer at a NON-ZERO epoch, so a wiped
           epoch map reads as a mismatch rather than coincidentally matching")

      ;; The patch under test: one user key, naming none of the reserved slots.
      (rf/dispatch-sync [:rf2-0hi3x/patch-data :rf2-0hi3x/timer {:status :degraded}])

      (is (= :degraded (:status (:data (snapshot :rf2-0hi3x/timer))))
          "the user-domain patch landed")
      (is (= epoch (get-in (snapshot :rf2-0hi3x/timer)
                           [:data :rf/after-epoch [:loading]]))
          "the reserved :rf/after-epoch map SURVIVED the patch (a replacing
           :data leg would drop it, and node-epoch would then read 0)")

      ;; The observable consequence — the timer fires rather than going silent.
      (rf/dispatch-sync [:rf2-0hi3x/timer
                         [:rf.machine.timer/after-elapsed 5000 epoch [:loading]]])
      (is (= :timeout (:state (snapshot :rf2-0hi3x/timer)))
          "the in-flight :after timer ARRIVED after the patch — a replacing
           :data leg would suppress it as stale and leave the machine in
           :loading for ever with nothing reported"))))

;; ---------------------------------------------------------------------------
;; (b) a spawned child still completes to its PARENT after a `:data` patch
;; ---------------------------------------------------------------------------

(deftest update-snapshot-data-patch-keeps-spawned-child-parented
  (testing "a `{:data {...}}` patch on a SPAWNED child leaves its :rf/parent-id
            lineage intact, so it completes to the parent's :spawn :on-done
            instead of finishing as a singleton"
    (reg-patch-event!)
    (rf/reg-machine :rf2-0hi3x/child
      {:initial :running
       :data    {:status :ok}
       :states  {:running {:on {:finish {:target :done
                                         :action (fn [{data :data ev :event}]
                                                   {:data (assoc data :token (second ev))})}}}
                 :done    {:final?     true
                           :output-key :token}}})
    (rf/reg-machine :rf2-0hi3x/parent
      {:initial :idle
       :data    {}
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :rf2-0hi3x/child
                                   :on-done (fn [{data :data result :result}]
                                              (assoc data :token-from-child result))}}}})
    (rf/dispatch-sync [:rf2-0hi3x/parent [:start]])
    (let [child-id (spawned-id :rf2-0hi3x/parent [:working])]
      (is (some? child-id)
          "precondition: the child was spawned and bound in the registry")
      (is (= :rf2-0hi3x/parent (:rf/parent-id (:data (snapshot child-id))))
          "precondition: the spawn stamped the child's lineage under :data")

      ;; The patch under test, on the CHILD — one user key, naming none of the
      ;; reserved lineage slots.
      (rf/dispatch-sync [:rf2-0hi3x/patch-data child-id {:status :degraded}])

      (is (= :degraded (:status (:data (snapshot child-id))))
          "the user-domain patch landed on the child")
      (is (= :rf2-0hi3x/parent (:rf/parent-id (:data (snapshot child-id))))
          "the reserved :rf/parent-id SURVIVED the patch (a replacing :data
           leg would drop it, orphaning a live child)")

      ;; The observable consequence — the parent is notified of completion.
      (rf/dispatch-sync [child-id [:finish :auth/token]])
      (is (= :auth/token (get-in (snapshot :rf2-0hi3x/parent) [:data :token-from-child]))
          "the parent's :spawn :on-done folded the child's output — a
           replacing :data leg would finalize the child with :parent-id nil
           (the singleton path) and the parent would never be told its child
           had finished"))))
