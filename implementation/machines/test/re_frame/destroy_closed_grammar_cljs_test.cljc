(ns re-frame.destroy-closed-grammar-cljs-test
  "rf2-3phait — the `:rf.machine/destroy` map-form grammar is a CLOSED
  discriminated union. Presence of a discriminator key (`:rf/reap` /
  `:rf/spawn-all`) SELECTS that shape; the shape then requires the value
  exactly `true` plus the exact coordinate fields/types. Any malformed,
  overlapping, or unknown map shape emits exactly one
  `:rf.error/machine-destroy-bad-arg` and performs ZERO mutation — no slot,
  actor, child, trace, terminal-reply, or ownership change.

  The pre-fix defect: the map-shape dispatch tested TRUTHINESS of
  `(:rf/reap args)`, so `{:rf/reap false :rf/parent-id p :rf/invoke-id i
  :rf/child-id c}` fell through to the tracked single-`:spawn` branch. With
  a `:spawn-all` join at the addressed slot, that branch read the WHOLE
  join-state map as the slot's actor id, cleared the join slot, left the
  real children live and orphaned, and emitted a bogus
  `:rf.machine/destroyed` trace whose actor id was the join-state map — and
  no bad-arg error. (`destroy-machine-fx`'s `:rf/spawn-all` routing had the
  same truthy-fall-through: `{:rf/spawn-all false …}` reached the tracked
  branch too.)

  The file is named `*-cljs-test.cljc` so it's discovered by both
  cognitect-style JVM runs and shadow-cljs (`cljs-test$` ns-regexp)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load the machines artefact so its fx handlers + late-bind hooks are
   ;; installed when this ns runs in isolation.
   [re-frame.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- destroyed-traces []
  (rf.machines.test-support/events-of :rf.machine/destroyed))

(defn- bad-arg-traces []
  (rf.machines.test-support/events-of :rf.error/machine-destroy-bad-arg))

(defn- join-state [parent-id invoke-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id invoke-id]))

;; A child that stays LIVE (its states are not :final?) and never
;; auto-dispatches back — so join state stays pristine while we throw
;; malformed destroy carriers at it.
(def ^:private inert-child
  {:initial :running
   :data    {}
   :states  {:running {}}})

(defn- reg-join-parent!
  "Register a two-child :spawn-all parent + inert children and start it.
  Returns the seeded join-state map."
  [parent-kw child-a-kw child-b-kw]
  (rf/reg-machine child-a-kw inert-child)
  (rf/reg-machine child-b-kw inert-child)
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-a-kw}
                                           {:id :b :machine-id child-b-kw}]
                         :join            :all
                         :on-child-done   :child/done
                         :on-child-error  :child/failed
                         :on-all-complete [:all/done]}}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw [:racing]))

(defn- destroy-with!
  "Fire one `[:rf.machine/destroy args]` through an ordinary app event."
  [args]
  (rf/reg-event ::fire-destroy
    (fn [_ [_ a]] {:fx [[:rf.machine/destroy a]]}))
  (rf/dispatch-sync [::fire-destroy args]))

(defn- assert-zero-mutation!
  "The load-bearing zero-mutation assertion set: join slot intact (same
  value), both children still live, no destroyed trace."
  [parent-kw pre-join]
  (let [post-join (join-state parent-kw [:racing])
        children  (:children pre-join)]
    (is (= pre-join post-join)
        "the join slot is byte-identical — no slot mutation")
    (doseq [[cid spawned-id] children]
      (is (some? (rf.machines.test-support/snapshot spawned-id))
          (str "child " cid " (" spawned-id ") is still live")))
    (is (empty? (destroyed-traces))
        (str "no :rf.machine/destroyed fired; saw "
             (mapv :tags (destroyed-traces))))))

;; ---- the P1 corruption repro: {:rf/reap false …} ---------------------------

(deftest reap-false-carrier-fails-closed
  (testing "rf2-3phait — {:rf/reap false + exact coordinates} is a MALFORMED
            reap carrier: it must emit exactly one
            :rf.error/machine-destroy-bad-arg and mutate NOTHING. Pre-fix it
            fell through to the tracked branch, read the join-state map as an
            actor id, cleared the join slot, orphaned both live children, and
            emitted a bogus destroyed trace with zero bad-arg errors."
    (let [pre-join (reg-join-parent! :dcg/p1 :dcg/p1a :dcg/p1b)]
      (is (map? (:children pre-join)) "live two-child join seeded")
      (rf.machines.test-support/reset-captured!)
      (destroy-with! {:rf/reap      false
                      :rf/parent-id :dcg/p1
                      :rf/invoke-id [:racing]
                      :rf/child-id  :a})
      (is (= 1 (count (bad-arg-traces)))
          "exactly one :rf.error/machine-destroy-bad-arg fired")
      (is (keyword? (:cause (:tags (first (bad-arg-traces)))))
          "the bad-arg error carries stable typed :cause evidence")
      (assert-zero-mutation! :dcg/p1 pre-join))))

;; ---- nil / wrong-typed :rf/reap values -------------------------------------

(deftest reap-nil-value-fails-closed
  (testing "rf2-3phait — {:rf/reap nil …}: presence of :rf/reap selects the
            reap shape; a nil value is malformed (fail closed, zero mutation)"
    (let [pre-join (reg-join-parent! :dcg/p2 :dcg/p2a :dcg/p2b)]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! {:rf/reap      nil
                      :rf/parent-id :dcg/p2
                      :rf/invoke-id [:racing]
                      :rf/child-id  :a})
      (is (= 1 (count (bad-arg-traces))))
      (assert-zero-mutation! :dcg/p2 pre-join))))

(deftest reap-wrong-type-value-fails-closed
  (testing "rf2-3phait — {:rf/reap \"true\" …}: only the exact value true is
            the reap discriminator; truthy aliases are malformed"
    (let [pre-join (reg-join-parent! :dcg/p3 :dcg/p3a :dcg/p3b)]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! {:rf/reap      "true"
                      :rf/parent-id :dcg/p3
                      :rf/invoke-id [:racing]
                      :rf/child-id  :a})
      (is (= 1 (count (bad-arg-traces))))
      (assert-zero-mutation! :dcg/p3 pre-join))))

;; ---- missing / wrongly-typed coordinates -----------------------------------

(deftest reap-missing-coordinate-fails-closed
  (testing "rf2-3phait — a reap missing :rf/invoke-id is malformed"
    (let [pre-join (reg-join-parent! :dcg/p4 :dcg/p4a :dcg/p4b)]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! {:rf/reap      true
                      :rf/parent-id :dcg/p4
                      :rf/child-id  :a})
      (is (= 1 (count (bad-arg-traces))))
      (assert-zero-mutation! :dcg/p4 pre-join))))

(deftest reap-wrongly-typed-coordinate-fails-closed
  (testing "rf2-3phait — a reap whose :rf/invoke-id is not a path vector is
            malformed (exact coordinate types, no permissive coercion)"
    (let [pre-join (reg-join-parent! :dcg/p5 :dcg/p5a :dcg/p5b)]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! {:rf/reap      true
                      :rf/parent-id :dcg/p5
                      :rf/invoke-id :racing
                      :rf/child-id  :a})
      (is (= 1 (count (bad-arg-traces))))
      (assert-zero-mutation! :dcg/p5 pre-join))))

;; ---- overlapping discriminators / unknown keys -----------------------------

(deftest overlapping-discriminators-fail-closed
  (testing "rf2-3phait — a carrier declaring BOTH :rf/reap and :rf/spawn-all
            is an overlapping shape: fail closed, zero mutation"
    (let [pre-join (reg-join-parent! :dcg/p6 :dcg/p6a :dcg/p6b)]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! {:rf/reap      true
                      :rf/spawn-all true
                      :rf/parent-id :dcg/p6
                      :rf/invoke-id [:racing]
                      :rf/child-id  :a})
      (is (= 1 (count (bad-arg-traces))))
      (assert-zero-mutation! :dcg/p6 pre-join))))

(deftest spawn-all-false-carrier-fails-closed
  (testing "rf2-3phait — {:rf/spawn-all false …} is malformed (presence
            selects the spawn-all shape; the value must be exactly true).
            Pre-fix it fell through destroy-machine-fx's truthy routing into
            the tracked branch and corrupted the join slot"
    (let [pre-join (reg-join-parent! :dcg/p7 :dcg/p7a :dcg/p7b)]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! {:rf/spawn-all false
                      :rf/parent-id :dcg/p7
                      :rf/invoke-id [:racing]})
      (is (= 1 (count (bad-arg-traces))))
      (assert-zero-mutation! :dcg/p7 pre-join))))

(deftest unknown-extra-key-fails-closed
  (testing "rf2-3phait — a well-discriminated reap carrying an EXTRA unknown
            key is outside the closed grammar: fail closed"
    (let [pre-join (reg-join-parent! :dcg/p8 :dcg/p8a :dcg/p8b)]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! {:rf/reap      true
                      :rf/parent-id :dcg/p8
                      :rf/invoke-id [:racing]
                      :rf/child-id  :a
                      :rf/extra     1})
      (is (= 1 (count (bad-arg-traces))))
      (assert-zero-mutation! :dcg/p8 pre-join))))

;; ---- tracked form pointed at a spawn-all join slot -------------------------

(deftest tracked-form-at-spawn-all-slot-fails-closed
  (testing "rf2-3phait — the tracked single-:spawn form {:rf/parent-id
            :rf/invoke-id} resolving a slot that holds a spawn-all JOIN-STATE
            MAP must fail closed: the tracked branch can never consume a
            join-state map as an actor id"
    (let [pre-join (reg-join-parent! :dcg/p9 :dcg/p9a :dcg/p9b)]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! {:rf/parent-id :dcg/p9
                      :rf/invoke-id [:racing]})
      (is (= 1 (count (bad-arg-traces)))
          "the tracked form addressed at a join slot fails loud")
      (assert-zero-mutation! :dcg/p9 pre-join))))

;; ---- non-map, non-keyword args ---------------------------------------------

(deftest non-keyword-non-map-arg-fails-closed
  (testing "rf2-3phait — the imperative form requires a keyword actor id;
            any other non-map arg is outside the closed grammar"
    (let [pre-join (reg-join-parent! :dcg/p10 :dcg/p10a :dcg/p10b)]
      (rf.machines.test-support/reset-captured!)
      (destroy-with! "not-an-actor-id")
      (is (= 1 (count (bad-arg-traces))))
      (assert-zero-mutation! :dcg/p10 pre-join))))

;; ---- genuine forms stay green ----------------------------------------------

(deftest genuine-tracked-destroy-stays-green
  (testing "rf2-3phait — the genuine tracked single-:spawn exit-cascade
            destroy still tears the tracked child down exactly once"
    (rf/reg-machine :dcg/live-child inert-child)
    (rf/reg-machine :dcg/tracked-parent
      {:initial :idle
       :states  {:idle    {:on {:start :working}}
                 :working {:spawn {:machine-id :dcg/live-child}
                           :on    {:stop :idle}}}})
    (rf/dispatch-sync [:dcg/tracked-parent [:start]])
    (let [child-id (get-in (rf.machines.test-support/runtime-db)
                           [:rf.runtime/machines :spawned :dcg/tracked-parent [:working]])]
      (is (keyword? child-id) "single tracked :spawn child live")
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:dcg/tracked-parent [:stop]])
      (is (nil? (rf.machines.test-support/snapshot child-id)) "tracked child torn down on exit")
      (is (= 1 (count (filterv #(= child-id (:actor-id (:tags %)))
                               (destroyed-traces))))
          "exactly one destroyed trace for the tracked child")
      (is (empty? (bad-arg-traces))
          "no bad-arg error for the genuine tracked form"))))

(deftest genuine-spawn-all-exit-stays-green
  (testing "rf2-3phait — the genuine {:rf/spawn-all true} exit-cascade form
            still tears every child down and clears the slot"
    (rf/reg-machine :dcg/sa-child inert-child)
    (rf/reg-machine :dcg/sa-parent
      {:initial :idle
       :states  {:idle    {:on {:start :racing}}
                 :racing  {:spawn-all
                           {:children        [{:id :a :machine-id :dcg/sa-child}
                                              {:id :b :machine-id :dcg/sa-child}]
                            :join            :all
                            :on-child-done   :child/done
                            :on-child-error  :child/failed
                            :on-all-complete [:all/done]}
                           :on {:abort :idle}}}})
    (rf/dispatch-sync [:dcg/sa-parent [:start]])
    (let [children (:children (join-state :dcg/sa-parent [:racing]))]
      (is (= 2 (count children)))
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:dcg/sa-parent [:abort]])
      (is (nil? (join-state :dcg/sa-parent [:racing])) "join slot cleared")
      (doseq [[cid spawned-id] children]
        (is (nil? (rf.machines.test-support/snapshot spawned-id))
            (str "child " cid " torn down on exit")))
      (is (empty? (bad-arg-traces))
          "no bad-arg error for the genuine spawn-all exit form"))))
