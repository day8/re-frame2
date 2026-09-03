(ns re-frame.join-parallel-recordable-controls-cljs-test
  "rf2-lud4af — the exact-attempt fold fence, driven through the RECORDABLE
  `:rf.cofx` transport (`:rf.machine/join-attempt`) in the AMBIGUOUS PARALLEL
  shape.

  `join_exact_attempt_cljs_test` covers the wrong-invoke / wrong-actor /
  unstamped / unknown-child control classes in the FLAT shape via event-vector
  METADATA. This file re-drives the same control classes in the two-region
  parallel shape where both regions reuse the SAME logical child id `:worker`
  and the SAME `:child/done` / `:child/failed` completion keywords — the exact
  ambiguity `select-parallel-joins-by-exact-attempt` (rf2-wsrtlw) routes —
  and it carries every forged / legitimate carrier on the recordable
  `:rf.cofx` fact rather than metadata, so the coverage matches the delivery
  channel the rf2-lud4af transport actually uses (metadata does not survive an
  EDN round-trip / delayed dispatch).

  Named `*-cljs-test.cljc` so both the JVM run and shadow-cljs discover it."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   [re-frame.machines :as rf.machines]
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(def ^:private parent-kw :lud4af-par/parent)

(defn- mk-worker
  "A worker child that dispatches `:child/done` / `:child/failed` back to the
  parent carrying its own `:worker` logical id — through its OWN handler
  boundary, so the runtime stamps the exact `:rf/join-attempt` on the recordable
  `:rf.cofx` for its region's attempt."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{d :data e :event}] {:data (assoc d :id (second e))})
             :dispatch-done (fn [{d :data}] {:fx [[:dispatch [parent-kw [:child/done   (:id d)]]]]})
             :dispatch-err  (fn [{d :data}] {:fx [[:dispatch [parent-kw [:child/failed (:id d)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done   :action :dispatch-done}
                            :fail   {:target :failed :action :dispatch-err}}}
             :done {} :failed {}}})

;; A never-completing helper so the two-child `:all` join stays OPEN after
;; `:worker` folds (a single-child join would resolve + tear the region down,
;; clearing the join-state we assert on).
(def ^:private idle-helper {:initial :idle :states {:idle {}}})

(defn- region-spawn-all [worker-type on-complete ready-state]
  {:initial :idle
   :states  {:idle   {:on {:start :racing}}
             :racing {:spawn-all {:children       [{:id :worker :machine-id worker-type
                                                    :start [:set-id :worker]}
                                                   {:id :helper :machine-id :lud4af-par/helper}]
                                  :join           :all
                                  :on-child-done  :child/done
                                  :on-child-error :child/failed
                                  :on-all-complete on-complete}
                      :on {(first on-complete) ready-state}}
             ready-state {}}})

(defn- reg-and-start! []
  (rf/reg-machine :lud4af-par/wc-r1 (mk-worker))
  (rf/reg-machine :lud4af-par/wc-r2 (mk-worker))
  (rf/reg-machine :lud4af-par/helper idle-helper)
  (rf/reg-machine parent-kw
    {:type    :parallel
     :regions {:r1 (region-spawn-all :lud4af-par/wc-r1 [:r1/done] :r1-ready)
               :r2 (region-spawn-all :lud4af-par/wc-r2 [:r2/done] :r2-ready)}})
  (rf/dispatch-sync [parent-kw [:start]]))

(defn- spawned-joins []
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-kw]))

(defn- region-invoke+join
  "`[invoke-id join-state]` for the region whose `:on-all-complete` is
  `on-complete` (region-distinct)."
  [on-complete]
  (some (fn [[invoke js]]
          (when (= on-complete (get-in js [:spec :on-all-complete])) [invoke js]))
        (spawned-joins)))

(defn- attempt-for
  "The exact-attempt tuple for `:worker` in the region named by
  `[invoke-id join-state]`, with optional per-key `overrides` (to forge a
  wrong-spawned-id / wrong-invoke carrier)."
  ([invoke-id js] (attempt-for invoke-id js {}))
  ([invoke-id js overrides]
   (merge {:parent-id  parent-kw
           :invoke-id  invoke-id
           :child-id   :worker
           :spawned-id (get-in js [:children :worker])
           :attempt    (:rf/attempt js)}
          overrides)))

(defn- dispatch-carrier!
  "Dispatch a completion carrier `[parent [kw child-id]]` carrying `attempt` on
  the recordable `:rf.cofx` fact (nil attempt dispatches the bare, unstamped
  carrier)."
  [inner attempt]
  (if attempt
    (rf/dispatch-sync [parent-kw inner] {:rf.cofx {:rf.machine/join-attempt attempt}})
    (rf/dispatch-sync [parent-kw inner])))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags)
        (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion)))

(defn- bad-child-ids []
  (rf.machines.test-support/events-of :rf.error/machine-spawn-all-bad-child-id))

;; ---------------------------------------------------------------------------
;; legitimate success / failure through the recordable transport
;; ---------------------------------------------------------------------------

(deftest legit-parallel-completion-folds-only-its-region
  (testing "rf2-lud4af — a real :r2 worker completion carried on the recordable
            :rf.cofx transport folds ONLY into :r2 (:done #{:worker}); :r1 is
            untouched and no stale / bad-child evidence fires."
    (reg-and-start!)
    (let [[_ r2-js] (region-invoke+join [:r2/done])]
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [(get-in r2-js [:children :worker]) [:go]])
      (let [[_ r1'] (region-invoke+join [:r1/done])
            [_ r2'] (region-invoke+join [:r2/done])]
        (is (= #{:worker} (:done r2')) ":r2 folded its worker")
        (is (= #{} (:done r1')) ":r1 untouched")
        (is (empty? (stale-reasons)) "no stale evidence for the legit carrier")
        (is (empty? (bad-child-ids)) "no bad-child evidence for the legit carrier")))))

(deftest legit-parallel-failure-folds-only-its-region
  (testing "rf2-lud4af — the failure side: a real :r2 worker :fail carried on the
            recordable transport folds ONLY into :r2 (:failed #{:worker})."
    (reg-and-start!)
    (let [[_ r2-js] (region-invoke+join [:r2/done])]
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [(get-in r2-js [:children :worker]) [:fail]])
      (let [[_ r1'] (region-invoke+join [:r1/done])
            [_ r2'] (region-invoke+join [:r2/done])]
        (is (= #{:worker} (:failed r2')) ":r2 folded its worker into :failed")
        (is (= #{} (:failed r1')) ":r1 untouched")
        (is (empty? (stale-reasons)) "no stale evidence for the legit failure")))))

;; ---------------------------------------------------------------------------
;; the four control classes — driven through the recordable :rf.cofx transport
;; ---------------------------------------------------------------------------

(deftest parallel-unstamped-carrier-suppressed-unverified
  (testing "rf2-lud4af — a bare completion carrier with NO recordable coordinate
            (never flowed through a member child's boundary) folds nothing in
            either region and is classified :attempt-unverified."
    (reg-and-start!)
    (rf.machines.test-support/reset-captured!)
    (dispatch-carrier! [:child/done :worker] nil)
    (let [[_ r1'] (region-invoke+join [:r1/done])
          [_ r2'] (region-invoke+join [:r2/done])]
      (is (= #{} (:done r1')) ":r1 folded nothing")
      (is (= #{} (:done r2')) ":r2 folded nothing")
      (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
          "typed evidence: :attempt-unverified"))))

(deftest parallel-wrong-spawned-id-carrier-superseded
  (testing "rf2-lud4af — a carrier stamped for :r2's invoke + attempt + child but
            a WRONG spawned instance address fails the exact actor-identity
            clause and folds nothing (:attempt-superseded)."
    (reg-and-start!)
    (let [[r2-invoke r2-js] (region-invoke+join [:r2/done])]
      (rf.machines.test-support/reset-captured!)
      (dispatch-carrier! [:child/done :worker]
                         (attempt-for r2-invoke r2-js {:spawned-id :lud4af-par/bogus-instance}))
      (let [[_ r2'] (region-invoke+join [:r2/done])]
        (is (= #{} (:done r2')) ":r2 folded nothing on the wrong-spawned-id carrier")
        (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))
            "typed evidence: :attempt-superseded")))))

(deftest parallel-wrong-invoke-carrier-superseded
  (testing "rf2-lud4af — a carrier naming a NON-EXISTENT invoke path fails the
            parent/invoke identity clause and folds nothing (:attempt-superseded)."
    (reg-and-start!)
    (let [[r2-invoke r2-js] (region-invoke+join [:r2/done])]
      (rf.machines.test-support/reset-captured!)
      (dispatch-carrier! [:child/done :worker]
                         (attempt-for r2-invoke r2-js {:invoke-id [:r9 :nope]}))
      (let [[_ r1'] (region-invoke+join [:r1/done])
            [_ r2'] (region-invoke+join [:r2/done])]
        (is (= #{} (:done r1')) ":r1 folded nothing")
        (is (= #{} (:done r2')) ":r2 folded nothing")
        (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))
            "typed evidence: :attempt-superseded")))))

(deftest parallel-unknown-child-carrier-bad-child-id
  (testing "rf2-lud4af — a carrier claiming a child id owned by NEITHER region
            fails the seeded-children gate: no fold, and a
            :rf.error/machine-spawn-all-bad-child-id error trace fires."
    (reg-and-start!)
    (let [[r2-invoke r2-js] (region-invoke+join [:r2/done])]
      (rf.machines.test-support/reset-captured!)
      (dispatch-carrier! [:child/done :ghost]
                         (attempt-for r2-invoke r2-js {:child-id :ghost}))
      (let [[_ r1'] (region-invoke+join [:r1/done])
            [_ r2'] (region-invoke+join [:r2/done])]
        (is (= #{} (:done r1')) ":r1 folded nothing")
        (is (= #{} (:done r2')) ":r2 folded nothing")
        (is (= 1 (count (bad-child-ids)))
            "exactly one :rf.error/machine-spawn-all-bad-child-id error fired")
        (is (empty? (stale-reasons)) "the unknown-child gate fires ahead of the stale gate")))))
