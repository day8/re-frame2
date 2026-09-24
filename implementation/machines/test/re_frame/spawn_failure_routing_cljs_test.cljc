(ns re-frame.spawn-failure-routing-cljs-test
  "A child's FAILURE never reaches a SUCCESS route, in either
  spawn form (Spec 005 §`:on-error`, §Child completion protocol).

  The success routes are the `:on-done` fold and the `:rf.machine.spawn/done`
  event. A failure is an `:error? true` final leaf or an uncaught child action
  exception.

    - `:spawn-all` — a child's per-child `:on-done` runs only for a `:done`
      completion. A failed child reaches the parent through `:on-any-failed`
      and the join's `:failed` set, and through nothing else.
    - single `:spawn` — EVERY failure rides the failure carrier
      `[:rf.machine.spawn/error <invoke-id> <error> <attempt>]`, whether or not
      the parent declares `:on-error`. It resolves through `:on-error`, else
      an explicit `:on {:rf.machine.spawn/error …}` walked leaf to root and
      then the root `:on`, else nowhere. The done carrier therefore only ever
      carries a success.

  Folding a failure into the success slot would break every route: a join
  child's `:on-done` would run for a failure, and so would a single `:spawn`'s
  whenever the parent declared no `:on-error`. An explicit
  `:on {:rf.machine.spawn/done …}` would take its success transition on a
  failure, and an explicit `:on {:rf.machine.spawn/error …}` without
  `:on-error` would never fire. Under a typed `[:schemas :data]`, the folded
  error map would be rejected and the rollback would swallow the whole
  carrier, so the join would never resolve.

  Rows J1/J3/J-schema/P1/P2/P3/P7 pin those failure routes. The rest are
  controls.

  Named `*-cljs-test.cljc`, so both the machines JVM artefact and the
  shadow-cljs node lane run it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; Loading the machines facade installs the late-bind hooks `reg-machine`
   ;; resolves through.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   ;; The `[:schemas :data]` boundary the J-schema row exercises routes
   ;; through the registered validator the Malli adapter publishes.
   [re-frame.schemas]
   [re-frame.schemas.malli]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private state rf.machines.test-support/machine-state)
(def ^:private data  rf.machines.test-support/machine-data)
(def ^:private snapshot rf.machines.test-support/snapshot)

(def ^:private failure-payload {:status 503})

(defn- child-def
  "The child every row spawns. `:ok` ends on a plain `:final?` leaf whose
  output is `:the-value`; `:fail` ends on an `:error? true` leaf whose output
  is `failure-payload`; `:throw` raises from an action and stays alive."
  []
  {:initial :running
   :data    {:value :the-value :err failure-payload}
   :states  {:running {:on {:ok    :done
                            :fail  :failed
                            :throw {:action (fn [_] (throw (ex-info "child action threw" {:row :throw})))}}}
             :done    {:final? true :output-key :value}
             :failed  {:final? true :error? true :output-key :err}}})

(defn- recording-fold
  "An `:on-done` fold that records every result it is handed into `folds` and
  lands it in the parent's `:slot`."
  [folds]
  (fn [{:keys [data result]}]
    (swap! folds conj result)
    (assoc data :slot result)))

(def ^:private catch-error
  "An explicit failure-route transition that records the carried error."
  {:target :caught
   :action (fn [{d :data ev :event}] {:data (assoc d :caught (nth ev 2))})})

(defn- spawned
  [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:working]]))

(defn- start-spawn-parent!
  "Register `child-id` and a single-`:spawn` parent at `parent-id` whose
  `:working` state spawns it, start the parent, and return the child's
  address. `spawn-extra` merges into the `:spawn` map, `working-on` becomes
  `:working`'s `:on`, and `root-on` the machine root's `:on`."
  [parent-id child-id {:keys [spawn-extra working-on root-on]}]
  (rf/reg-machine child-id (child-def))
  (rf/reg-machine parent-id
    (cond-> {:initial :idle
             :data    {:slot nil}
             :states  {:idle    {:on {:start :working}}
                       :working (cond-> {:spawn (merge {:machine-id child-id} spawn-extra)}
                                  working-on (assoc :on working-on))
                       :next    {}
                       :errored {}
                       :caught  {}}}
      root-on (assoc :on root-on)))
  (rf/dispatch-sync [parent-id [:start]])
  (spawned parent-id))

(defn- start-join-parent!
  "Register `child-id` and a one-child `:spawn-all` parent at `parent-id`,
  start it, and return the child's address. The child's `:on-done` is
  `fold`. `:on-any-failed` is declared only when `on-any-failed?`, and
  `schema` (when given) becomes the parent's `[:schemas :data]`."
  [parent-id child-id fold {:keys [on-any-failed? schema]}]
  (rf/reg-machine child-id (child-def))
  (rf/reg-machine parent-id
    (cond-> {:initial :idle
             :data    {:slot nil}
             :states  {:idle    {:on {:start :working}}
                       :working {:spawn-all (cond-> {:children        [{:id         :a
                                                                         :machine-id child-id
                                                                         :on-done    fold}]
                                                     :on-all-complete [:all-ok]}
                                              on-any-failed? (assoc :on-any-failed [:any-failed]))
                                 :on        {:all-ok     :next
                                             :any-failed {:target :errored
                                                          :action (fn [{d :data ev :event}]
                                                                    {:data (assoc d :failure (nth ev 2))})}}}
                       :next    {}
                       :errored {}}}
      schema (assoc :schemas {:data schema})))
  (rf/dispatch-sync [parent-id [:start]])
  (get-in (spawned parent-id) [:children :a]))

(defn- done-traces []
  (rf.machines.test-support/events-of :rf.machine/done))

(defn- data-schema-failures []
  (filterv #(= :machine-data (-> % :tags :where))
           (rf.machines.test-support/events-of :rf.error/schema-validation-failure)))

;; ---------------------------------------------------------------------------
;; :spawn-all — the per-child :on-done is success-only
;; ---------------------------------------------------------------------------

(deftest j1-failed-join-child-reaches-on-any-failed-and-never-its-fold
  (testing "J1 — a join child reaching an :error? leaf fires :on-any-failed; its per-child :on-done does not run"
    (let [folds (atom [])
          child (start-join-parent! ::j1 ::j1-child (recording-fold folds) {:on-any-failed? true})]
      (is (some? child) "the join child spawned")
      (rf/dispatch-sync [child [:fail]])
      (is (= :errored (state ::j1)) "the parent took the :on-any-failed transition")
      (is (= failure-payload (:failure (data ::j1)))
          "the failure payload rode the :on-any-failed event — the failure route")
      (is (= [] @folds) "the per-child :on-done fold never saw the failure")
      (is (nil? (:slot (data ::j1))) "the success slot stayed empty"))))

(deftest j2-succeeded-join-child-folds-and-completes
  (testing "J2 (control) — a join child reaching a plain :final? leaf folds its result and completes the join"
    (let [folds (atom [])
          child (start-join-parent! ::j2 ::j2-child (recording-fold folds) {:on-any-failed? true})]
      (rf/dispatch-sync [child [:ok]])
      (is (= :next (state ::j2)) "the parent took :on-all-complete")
      (is (= [:the-value] @folds) "the fold ran once, with the success value")
      (is (= :the-value (:slot (data ::j2)))))))

(deftest j3-failed-join-child-without-on-any-failed-lands-in-the-failed-set-only
  (testing "J3 — without :on-any-failed a failed join child lands in the join's :failed set and nowhere else"
    (let [folds (atom [])
          child (start-join-parent! ::j3 ::j3-child (recording-fold folds) {:on-any-failed? false})]
      (rf/dispatch-sync [child [:fail]])
      (is (= :working (state ::j3)) "the parent is unmoved — no join event fired")
      (is (= #{:a} (:failed (spawned ::j3))) "the join recorded the failure")
      (is (= [] @folds) "the per-child :on-done fold never saw the failure")
      (is (nil? (:slot (data ::j3))) "the success slot stayed empty"))))

(deftest j-schema-failed-join-child-under-typed-data-resolves-the-join
  (testing "J-schema — under a typed [:schemas :data], a failed join child resolves the join instead of hanging it"
    (let [folds (atom [])
          child (start-join-parent! ::js ::js-child (recording-fold folds)
                                    {:on-any-failed? true
                                     :schema         [:map [:slot [:maybe :int]]]})]
      (rf/dispatch-sync [child [:fail]])
      (is (= [] (data-schema-failures))
          "no :machine-data rejection — the error map never reached the [:maybe :int] slot")
      (is (= :errored (state ::js))
          "the parent reached its :on-any-failed target (a rollback would leave it at :working for ever)")
      (is (= [] @folds) "the per-child :on-done fold never saw the failure"))))

;; ---------------------------------------------------------------------------
;; single :spawn — every failure rides the failure carrier
;; ---------------------------------------------------------------------------

(deftest p1-failed-child-never-reaches-on-done
  (testing "P1 — with :on-done and no :on-error, a child's error leaf does not run the fold"
    (let [folds (atom [])
          child (start-spawn-parent! ::p1 ::p1-child {:spawn-extra {:on-done (recording-fold folds)}})]
      (rf/dispatch-sync [child [:fail]])
      (is (= :working (state ::p1)) "the parent is unmoved — nothing handles the failure")
      (is (= [] @folds) "the :on-done fold never saw the failure")
      (is (nil? (:slot (data ::p1))) "the success slot stayed empty"))))

(deftest p2-failed-child-never-takes-an-explicit-done-transition
  (testing "P2 — an explicit :on {:rf.machine.spawn/done …} is not taken for a failure"
    (let [folds (atom [])
          child (start-spawn-parent! ::p2 ::p2-child {:spawn-extra {:on-done (recording-fold folds)}
                                                      :working-on  {:rf.machine.spawn/done :next}})]
      (rf/dispatch-sync [child [:fail]])
      (is (= :working (state ::p2)) "the success transition to :next was NOT taken")
      (is (= [] @folds) "the :on-done fold never saw the failure"))))

(deftest p3-failed-child-reaches-an-explicit-error-handler-without-on-error
  (testing "P3 — with no :on-error, a root :on {:rf.machine.spawn/error …} catches an error leaf (spec 005 §:on-error arm 2)"
    (let [folds (atom [])
          child (start-spawn-parent! ::p3 ::p3-child {:spawn-extra {:on-done (recording-fold folds)}
                                                      :root-on     {:rf.machine.spawn/error catch-error}})]
      (rf/dispatch-sync [child [:fail]])
      (is (= :caught (state ::p3)) "the explicit failure handler fired")
      (is (= failure-payload (:caught (data ::p3))) "it carried the child's error output")
      (is (= [] @folds) "the :on-done fold never saw the failure"))))

(deftest p4-failed-child-with-on-error-takes-on-error
  (testing "P4 (control) — :on-done + :on-error, the child fails: :on-error fires and the fold is skipped"
    (let [folds (atom [])
          child (start-spawn-parent! ::p4 ::p4-child {:spawn-extra {:on-done  (recording-fold folds)
                                                                    :on-error {:target :errored}}})]
      (rf/dispatch-sync [child [:fail]])
      (is (= :errored (state ::p4)))
      (is (= [] @folds)))))

(deftest p5-succeeded-child-folds-and-takes-the-explicit-done-transition
  (testing "P5 (control) — :on-done + explicit :on {:rf.machine.spawn/done …}, the child succeeds"
    (let [folds (atom [])
          child (start-spawn-parent! ::p5 ::p5-child {:spawn-extra {:on-done (recording-fold folds)}
                                                      :working-on  {:rf.machine.spawn/done :next}})]
      (rf/dispatch-sync [child [:ok]])
      (is (= :next (state ::p5)) "the success transition was taken")
      (is (= [:the-value] @folds) "the fold ran once, with the success value")
      (is (= :the-value (:slot (data ::p5)))))))

(deftest p6-failed-child-with-no-hooks-still-finishes
  (testing "P6 (control) — no hooks at all: the error leaf still auto-destroys the child and emits one :rf.machine/done carrying :error? true"
    (let [child (start-spawn-parent! ::p6 ::p6-child {})]
      (rf/dispatch-sync [child [:fail]])
      (is (= :working (state ::p6)) "the parent is unmoved")
      (is (nil? (snapshot child)) "the child auto-destroyed")
      (let [dones (filterv #(= child (-> % :tags :actor-id)) (done-traces))]
        (is (= 1 (count dones)))
        (is (true? (-> dones first :tags :error?)))))))

(deftest p7-throwing-child-reaches-an-explicit-error-handler-without-on-error
  (testing "P7 — with no :on-error, a root :on {:rf.machine.spawn/error …} catches an uncaught child action exception"
    (let [child (start-spawn-parent! ::p7 ::p7-child {:root-on {:rf.machine.spawn/error catch-error}})]
      (rf/dispatch-sync [child [:throw]])
      (is (= :caught (state ::p7)) "the explicit failure handler fired")
      (is (= :rf.error/machine-action-exception (:rf.error/id (:caught (data ::p7))))
          "it carried the exception envelope"))))

(deftest p8-throwing-child-with-no-hooks-stays-alive
  (testing "P8 (control) — no hooks at all: an action exception moves nothing and finishes nothing"
    (let [child (start-spawn-parent! ::p8 ::p8-child {})]
      (rf/dispatch-sync [child [:throw]])
      (is (= :working (state ::p8)) "the parent is unmoved")
      (is (some? (snapshot child)) "the child is still alive")
      (is (= [] (done-traces)) "no :rf.machine/done — an exception is not a completion"))))

(deftest p9-throwing-child-with-on-error-takes-on-error
  (testing "P9 (control) — :on-error, the child throws: :on-error fires"
    (let [child (start-spawn-parent! ::p9 ::p9-child {:spawn-extra {:on-error {:target :errored}}})]
      (rf/dispatch-sync [child [:throw]])
      (is (= :errored (state ::p9))))))
