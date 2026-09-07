(ns re-frame.join-parallel-attempt-select-test
  "rf2-wsrtlw — select parallel `:spawn-all` joins by EXACT-ATTEMPT COORDINATE before
  folding.

  #5839's exact-attempt check runs AFTER the runtime has selected which
  active `:spawn-all` join owns an inbound completion. That selection was by
  child-id ownership (first owning match in declaration order). When two active
  parallel regions legitimately reuse BOTH the completion event keyword AND the
  logical child id, a later region's exact-current carrier is mis-routed to the
  first region's join, rejected there as `:attempt-superseded`, and its own join
  hangs.

  The fix routes the completion to the region whose LIVE join-state IS the exact
  attempt the carrier's `:rf/join-attempt` names (parent/invoke identity + attempt
  token + spawned instance), BEFORE the fold gate; child-id ownership is only a
  fallback for unstamped / unknown carriers (which the fold gate then suppresses
  fail-closed).

  Two regions `:r1` / `:r2` each declare a `:spawn-all` with the SAME logical
  child id `:worker` and the SAME `:on-child-done` / `:on-child-error`. The test
  completes `:r2` first and asserts ONLY `:r2` resolves; `:r1` and its worker are
  untouched; and no stale/bad-child evidence fires for the legitimate carrier."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.machines :as rf.machines]
            [re-frame.machines.test-support :as rf.machines.test-support]
            [re-frame.substrate.plain-atom :as rf.substrate.plain-atom]))

(def ^:private _artefact rf.machines/machine-transition)

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture {:adapter rf.substrate.plain-atom/adapter})
  rf.machines.test-support/trace-capture-fixture)

(defn- mk-worker
  "A worker child that dispatches `done-kw` / `err-kw` back to `parent-id`
  carrying its own `:worker` logical id — through its OWN handler boundary, so
  the runtime records the exact-attempt coordinate on the recordable `:rf.cofx` fact
  `:rf.machine/join-attempt` for its region's attempt."
  [parent-id done-kw err-kw]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{d :data e :event}] {:data (assoc d :id (second e))})
             :dispatch-done (fn [{d :data}] {:fx [[:dispatch [parent-id [done-kw (:id d)]]]]})
             :dispatch-err  (fn [{d :data}] {:fx [[:dispatch [parent-id [err-kw (:id d)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done   :action :dispatch-done}
                            :fail   {:target :failed :action :dispatch-err}}}
             :done   {} :failed {}}})

;; A never-completing helper child so the two-child `:all` join stays OPEN after
;; the `:worker` folds (a single-child join would RESOLVE + tear the region
;; down, clearing the join-state we assert on). The helper just sits idle.
(def ^:private idle-helper
  {:initial :idle :states {:idle {}}})

(defn- region-spawn-all
  "A two-child (`:worker` + never-completing `:helper`) `:spawn-all` region
  sharing `:child/done` / `:child/failed` across regions but resolving to a
  region-distinct `on-complete` event. The shared `:worker` logical id + shared
  `:child/done` keyword across regions is exactly the ambiguity the fix routes by
  exact-attempt coordinate; completing `:worker` alone is NON-DECISIVE (the `:all` join
  still awaits `:helper`), so the join-state persists to be asserted on."
  [worker-type on-complete ready-state]
  {:initial :idle
   :states  {:idle   {:on {:start :racing}}
             :racing {:spawn-all {:children       [{:id :worker :machine-id worker-type
                                                    :start [:set-id :worker]}
                                                   {:id :helper :machine-id :rf2-wsrtlw/helper}]
                                  :join           :all
                                  :on-all-complete on-complete}
                      :on {(first on-complete) ready-state}}
             ready-state {}}})

(def ^:private parent-kw :rf2-wsrtlw/parent)

(defn- reg-and-start! []
  (rf/reg-machine :rf2-wsrtlw/wc-r1 (mk-worker parent-kw :child/done :child/failed))
  (rf/reg-machine :rf2-wsrtlw/wc-r2 (mk-worker parent-kw :child/done :child/failed))
  (rf/reg-machine :rf2-wsrtlw/helper idle-helper)
  (rf/reg-machine parent-kw
    {:type    :parallel
     :regions {:r1 (region-spawn-all :rf2-wsrtlw/wc-r1 [:r1/done] :r1-ready)
               :r2 (region-spawn-all :rf2-wsrtlw/wc-r2 [:r2/done] :r2-ready)}})
  (rf/dispatch-sync [parent-kw [:start]]))

(defn- spawned-joins []
  (get-in (rf.machines.test-support/runtime-db) [:rf.runtime/machines :spawned parent-kw]))

(defn- join-for
  "The join-state whose `:spec` resolves to `on-complete` (region-distinct)."
  [on-complete]
  (some (fn [[_invoke js]]
          (when (= on-complete (get-in js [:spec :on-all-complete])) js))
        (spawned-joins)))

(deftest later-region-folds-only-itself-by-exact-attempt
  (testing "two active parallel regions reuse child id :worker + event :child/done;
            completing :r2's worker folds ONLY into :r2 — :r1 and its worker are
            untouched, and no attempt-superseded / bad-child evidence fires for
            the legitimate carrier. Mutation tooth: selecting by child-id only
            routes :r2's carrier to :r1's join, which rejects it superseded, so
            :r2 never folds (:done stays empty)."
    (reg-and-start!)
    (let [r1-join (join-for [:r1/done])
          r2-join (join-for [:r2/done])]
      (is (map? r1-join) ":r1 seeded its own join-state")
      (is (map? r2-join) ":r2 seeded its own join-state")
      (is (= #{:worker :helper} (set (keys (:children r1-join)))) ":r1 owns logical child :worker")
      (is (= #{:worker :helper} (set (keys (:children r2-join)))) ":r2 owns logical child :worker")
      (is (not= (get-in r1-join [:children :worker])
                (get-in r2-join [:children :worker]))
          "the two regions spawned DISTINCT worker instances")
      (is (not= (:rf/attempt r1-join) (:rf/attempt r2-join))
          "each region minted its own per-attempt token")
      (rf.machines.test-support/reset-captured!)
      ;; Complete :r2's worker first — through its own boundary, so its carrier
      ;; carries an exact-current coordinate for :r2's attempt. Non-decisive (the two-child
      ;; :all join still awaits :helper), so both joins persist to assert on.
      (rf/dispatch-sync [(get-in r2-join [:children :worker]) [:go]])
      (let [r1' (join-for [:r1/done])
            r2' (join-for [:r2/done])]
        (is (= #{:worker} (:done r2'))
            ":r2's join folded :worker on its OWN exact-current completion")
        (is (false? (:resolved? r2')) ":r2 stays open (awaiting :helper)")
        (is (= #{} (:done r1'))
            ":r1's join is UNTOUCHED — the completion did not mis-route to it")
        (is (false? (:resolved? r1')) ":r1 stays open, folded nothing")
        (is (some? (rf.machines.test-support/snapshot (get-in r1-join [:children :worker])))
            ":r1's worker actor is still live (never reaped by a mis-routed fold)")
        (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion))
            "no stale-completion evidence fired for the legitimate carrier")
        (is (empty? (rf.machines.test-support/events-of :rf.error/machine-spawn-all-bad-child-id))
            "no bad-child-id evidence fired for the legitimate carrier")))))

(deftest later-region-failure-folds-only-itself-by-exact-attempt
  (testing "the failure side: completing :r2's worker via :fail routes the error
            carrier to :r2's join only (:r2 folds :worker into :failed); :r1 is
            untouched and no mis-route evidence fires."
    (reg-and-start!)
    (let [r1-join (join-for [:r1/done])
          r2-join (join-for [:r2/done])]
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [(get-in r2-join [:children :worker]) [:fail]])
      (let [r1' (join-for [:r1/done])
            r2' (join-for [:r2/done])]
        (is (= #{:worker} (:failed r2')) ":r2 folded :worker into :failed")
        (is (= #{} (:failed r1')) ":r1 folded no failure (not mis-routed)")
        (is (empty? (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion))
            "no stale-completion evidence for the legitimate failure carrier")))))
