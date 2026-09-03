(ns re-frame.spawn-all-init-incarnation-fence-test
  "rf2-8nxsh — fence `spawn-all-init-fx`'s admission preflight AND its durable
  join / reject-sentinel writes to the EXACT frame incarnation.

  `spawn-all-init-fx` captures an exact-incarnation `continue?` before the
  admission preflight, but on current main it wrote the reject sentinel and the
  live join through a bare-id `rf.frame/swap-runtime-db!` WITHOUT rechecking
  ownership after:

    - the preflight's `[:schemas :data]` validators (application code that can
      synchronously destroy owner frame A and publish a same-id successor B),
      and
    - the reject path's `reject-unregistered-spawn!` emissions (callback-bearing
      always-on records + dev traces).

  A validator (or a reject-record listener) that swapped A for B therefore let
  A-derived join-state / the reject sentinel install into B via the bare-id
  write, leaving B holding an IMPOSSIBLE join it never spawned — and the
  `:rf.machine.spawn-all/started` trace fired against B under A's authority.

  These fixtures drive `spawn-all-init-fx` DIRECTLY under a bound event owner
  (A's dequeue-time token), with a destroyer that publishes same-id B on the
  callback's / listener's own stack, and assert B stays byte-identical: no
  A-derived join, no reject sentinel, no started trace, no child side effect
  lands on B. Deterministic + single-threaded — the destroyer runs INSIDE the
  callback, so no latch coordination is needed.

  The ruled policy (mirrored from the completion / spawn-tail fence,
  rf2-3evq0x): already-entered authored callbacks may unwind, but loss of
  exact-incarnation ownership is a TERMINAL fence for every subsequent
  framework-owned write / tail. Cover conforming, failing, AND throwing schema
  validators plus unregistered-child rejection with a reject-record listener;
  ordinary all-valid and rejection behaviour stays unchanged (the fence is
  scoped to owner-loss only — the two live-owner controls)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.late-bind :as rf.late-bind]
            ;; Loading the machines facade registers `rf/reg-machine` + the
            ;; reserved machine fxs when this ns runs alone.
            [re-frame.machines]
            [re-frame.machines.lifecycle-fx.spawn :as rf.machines.lifecycle-fx.spawn]
            [re-frame.machines.paths :as rf.machines.paths]
            [re-frame.machines.spawn-order :as rf.machines.spawn-order]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

;; Fresh registrar + plain-atom adapter per test; the always-on error-listener
;; registry (a `defonce` atom) cleared so an `:errors` listener from one test
;; cannot leak into the next.
(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    {:adapter rf.substrate.plain-atom/adapter
     :init-fn (fn [] (rf.error-emit/clear-error-listeners!))})
  rf.machines.test-support/trace-capture-fixture)

;; The opaque schema marker the strict child declares; the destroyer validator
;; keys on it. The schemas artefact is NOT loaded — the test provides the
;; `:schemas/validate-with-registered-fn` hook directly (as the completion-fence
;; test does), so the child's spawn-time `[:schemas :data]` gate routes through
;; our destroyer.
(def ^:private child-schema ::child-schema)

(def ^:private strict-child
  {:initial :running :data {:n 1} :schemas {:data child-schema}
   :states  {:running {}}})

(def ^:private ok-child
  {:initial :running :data {} :states {:running {}}})

;; ---- faithful spawn-all-init args -----------------------------------------

(defn- init-args
  "A faithful `[:rf.machine/spawn-all-init args]` payload (transition.cljc
  §build-spawn-all-init) for `parent-id`'s invoke at slot key `invoke-id` over
  `children` — each `{:child-id :machine-id :spawned-id :data?}`. Carries the
  join-state seed and the PREPARED per-child `:child-args` the invoke-level
  admission preflight decides over."
  [parent-id invoke-id children]
  (let [children-map (into {} (map (juxt :child-id :spawned-id)) children)
        child-arg    (fn [{:keys [machine-id spawned-id child-id data]}]
                       (cond-> {:machine-id            machine-id
                                :id-prefix             machine-id
                                :rf/spawned-id         spawned-id
                                :rf/parent-id          parent-id
                                :rf/spawn-all-id       invoke-id
                                :rf/spawn-all-child-id child-id}
                         (some? data) (assoc :data data)))]
    {:rf/parent-id parent-id
     :rf/invoke-id invoke-id
     :join-state   {:children  children-map
                    :done      #{}
                    :failed    #{}
                    :resolved? false
                    :spec      {:join            :all
                                :on-child-done   :sa/done
                                :on-child-error  :sa/failed
                                :on-all-complete [:all/done]}
                    :invoke-id invoke-id}
     :child-args   (mapv child-arg children)}))

(defn- join-slot [frame-a parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db frame-a) (rf.machines.paths/spawned-path parent-id invoke-id)))

;; ---- direct-invocation runner ---------------------------------------------

(defn- run-init
  "Make frame A, capture A's token, register a destroyer keyed on `trigger`,
  then call `spawn-all-init-fx` DIRECTLY under A's bound event owner over
  `children`. When `destroy?`, the trigger destroys A + publishes same-id B on
  the callback's own stack. `trigger`:
    {:kind :validator :verdict <:true|:false|:throw>} — a
       `:schemas/validate-with-registered-fn` override that (optionally)
       destroys A then returns / throws the verdict;
    {:kind :reject-listener} — an `:errors` listener that (optionally) destroys
       A on the first `:rf.error/machine-spawn-unregistered-type` record.
  Returns observables read off B."
  [frame-a parent-id invoke-id children {:keys [trigger destroy?]}]
  (rf.machines.spawn-order/reset-all!)
  (rf/make-frame {:id frame-a})
  (let [token-a       (rf.frame/frame-incarnation-token frame-a)
        fired?        (atom false)
        records       (atom [])
        b-birth       (atom nil)
        orig-validate (rf.late-bind/get-fn :schemas/validate-with-registered-fn)
        destroy+B!    (fn []
                        (when (and destroy? (compare-and-set! fired? false true))
                          (rf.frame/destroy-frame! frame-a)  ;; destroy A
                          (rf/make-frame {:id frame-a})    ;; publish same-id B
                          (reset! b-birth (rf.machines.test-support/runtime-db frame-a))))]
    (rf/register-listener! :errors ::recorder
                           (fn [r]
                             (when (= :rf.error/machine-spawn-unregistered-type (:error r))
                               (swap! records conj r)
                               (when (= :reject-listener (:kind trigger))
                                 (destroy+B!)))))
    (try
      (when (= :validator (:kind trigger))
        (rf.late-bind/set-fn! :schemas/validate-with-registered-fn
          (fn [schema _data]
            (when (= schema child-schema)
              (destroy+B!)
              (case (:verdict trigger)
                :throw (throw (ex-info "validator boom" {}))
                :false false
                true)))))
      (rf.frame/call-with-event-owner-token frame-a token-a
        (fn [] (rf.machines.lifecycle-fx.spawn/spawn-all-init-fx
                 {:frame frame-a}
                 (init-args parent-id invoke-id children))))
      {:fired?     @fired?
       :records    @records
       :b-birth    @b-birth
       :b-runtime  (rf.machines.test-support/runtime-db frame-a)
       :join-slot  (join-slot frame-a parent-id invoke-id)
       :started    (rf.machines.test-support/events-of :rf.machine.spawn-all/started)}
      (finally
        (rf/unregister-listener! :errors ::recorder)
        (rf.late-bind/set-fn! :schemas/validate-with-registered-fn orig-validate)))))

(defn- assert-b-inert
  "The successor B carries NONE of A's derived spawn-all state: no join slot,
  runtime-db byte-identical to B's birth value, no started trace."
  [{:keys [fired? join-slot b-runtime b-birth started]}]
  (is (true? fired?) "the destroyer ran (the incarnation fence was exercised)")
  (is (nil? join-slot)
      "no A-derived join / reject sentinel landed on same-id successor B")
  (is (= b-birth b-runtime)
      "B's runtime-db is byte-identical to its birth value")
  (is (empty? started)
      "no :rf.machine.spawn-all/started trace fired against B"))

;; ===========================================================================
;; (1) Accept-path fence — a preflight schema validator destroys A.
;;     Mutation tooth: reverting the live-join write to a bare-id
;;     `swap-runtime-db!` lands A's join-state on B.
;; ===========================================================================

(deftest conforming-validator-loss-fences-live-join
  (testing "a CONFORMING `[:schemas :data]` validator that destroys A + publishes
            same-id B (a validator returning true still ran on A's stack): the
            all-valid path recomputes to the exact write, which no-ops — B holds
            no A-derived live join and gets no started trace"
    (rf/reg-machine :fence/strict strict-child)
    (assert-b-inert
      (run-init :rf2-8nxsh/accept-conform :par/a [:forking]
                [{:child-id :bad :machine-id :fence/strict :spawned-id :fence/strict#1
                  :data {:n 1}}]
                {:trigger {:kind :validator :verdict :true} :destroy? true}))))

(deftest failing-validator-loss-fences-live-join
  (testing "a FAILING validator that destroys A: the verdict is
            :rf/stale-incarnation (owner-loss short-circuits the schema verdict),
            so no sentinel OR join lands on B — a failing-yet-destroying validator
            leaks nothing"
    (rf/reg-machine :fence/strict strict-child)
    (assert-b-inert
      (run-init :rf2-8nxsh/accept-fail :par/a [:forking]
                [{:child-id :bad :machine-id :fence/strict :spawned-id :fence/strict#1
                  :data {:n 1}}]
                {:trigger {:kind :validator :verdict :false} :destroy? true}))))

(deftest throwing-validator-loss-fences-live-join
  (testing "a THROWING validator that destroys A then throws: the throw is caught
            and (A gone) swallowed to :rf/stale-incarnation, so the durable write
            no-ops and B stays byte-identical"
    (rf/reg-machine :fence/strict strict-child)
    (assert-b-inert
      (run-init :rf2-8nxsh/accept-throw :par/a [:forking]
                [{:child-id :bad :machine-id :fence/strict :spawned-id :fence/strict#1
                  :data {:n 1}}]
                {:trigger {:kind :validator :verdict :throw} :destroy? true}))))

;; ===========================================================================
;; (2) Reject-path fence — a reject-record listener destroys A.
;;     Mutation tooth: reverting the sentinel write to an unguarded bare-id
;;     `swap-runtime-db!` lands the reject sentinel on B.
;; ===========================================================================

(deftest reject-record-listener-loss-fences-sentinel
  (testing "an unregistered child fans one always-on reject record; a LISTENER on
            that record destroys A + publishes same-id B. Ownership is rechecked
            before the sentinel write and the write rides A's raw token, so no
            reject sentinel lands on B"
    (rf/reg-machine :fence/ok ok-child)
    ;; :fence/missing is NEVER reg-machine'd — the unregistered child.
    (let [result (run-init :rf2-8nxsh/reject-listener :par/a [:forking]
                           [{:child-id :ok      :machine-id :fence/ok      :spawned-id :fence/ok#1}
                            {:child-id :missing :machine-id :fence/missing :spawned-id :fence/missing#1}]
                           {:trigger {:kind :reject-listener} :destroy? true})]
      (is (= 1 (count (:records result)))
          "the reject record fired once (it precedes the loss) — the fence is not a suppression bug")
      (assert-b-inert result))))

;; ===========================================================================
;; (3) Live-owner controls — the fence is scoped to owner-loss only.
;; ===========================================================================

(deftest live-owner-accept-seeds-a-live-join
  (testing "control: an all-valid invoke whose validator does NOT destroy A seeds
            a LIVE child-bearing join and fires the started trace — unchanged"
    (rf/reg-machine :fence/strict strict-child)
    (let [{:keys [join-slot started]}
          (run-init :rf2-8nxsh/live-accept :par/a [:forking]
                    [{:child-id :good :machine-id :fence/strict :spawned-id :fence/strict#1
                      :data {:n 7}}]
                    {:trigger {:kind :validator :verdict :true} :destroy? false})]
      (is (contains? join-slot :children)
          "a LIVE child-bearing join is seeded (no false reject)")
      (is (= {:good :fence/strict#1} (:children join-slot))
          "the join names the child at its allocated id")
      (is (some? (:rf/attempt join-slot))
          "the live seed minted its per-attempt token")
      (is (= 1 (count started))
          "the :rf.machine.spawn-all/started trace fired for the live join"))))

(deftest live-owner-reject-seeds-the-sentinel
  (testing "control: an unregistered child with NO destroyer seeds the childless
            reject sentinel and fans exactly one reject record — unchanged"
    (rf/reg-machine :fence/ok ok-child)
    (let [{:keys [join-slot records started]}
          (run-init :rf2-8nxsh/live-reject :par/a [:forking]
                    [{:child-id :ok      :machine-id :fence/ok      :spawned-id :fence/ok#1}
                     {:child-id :missing :machine-id :fence/missing :spawned-id :fence/missing#1}]
                    {:trigger {:kind :reject-listener} :destroy? false})]
      (is (= {:rf/spawn-all-rejected? true} join-slot)
          "the childless reject sentinel is seeded (no live join)")
      (is (= 1 (count records))
          "exactly one always-on reject record for the one unregistered child")
      (is (empty? started)
          "a rejected invoke fires no started trace"))))
