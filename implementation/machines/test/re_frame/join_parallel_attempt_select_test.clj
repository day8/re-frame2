(ns re-frame.join-parallel-attempt-select-test
  "rf2-wsrtlw — select parallel `:spawn-all` joins by EXACT-ATTEMPT COORDINATE before
  folding.

  #5839's exact-attempt check runs AFTER the runtime has selected which
  active `:spawn-all` join owns an inbound completion. That selection was by
  child-id ownership (first owning match in declaration order). When two active
  parallel regions legitimately reuse the logical child id, a later region's
  exact-current completion is mis-routed to the first region's join, rejected
  there as `:attempt-superseded`, and its own join hangs.

  The fix routes the completion to the region whose LIVE join-state IS the exact
  attempt the completion's coordinate names (parent/invoke identity + attempt
  token + spawned instance), BEFORE the fold gate; child-id ownership is only a
  fallback for unstamped / unknown completions (which the fold gate then
  suppresses fail-closed).

  Under the child-completion protocol the completion is no longer a child-authored
  event: the child reaches a `:final?` leaf and the runtime's finalize cascade
  mints the carrier, reading the coordinate straight off the child's own
  `:rf/join-child` record. That REMOVES one half of the original ambiguity — there
  is no shared completion keyword any more, because there is no child-authored
  keyword at all — and leaves the half this test exists for: two live joins under
  ONE parent, both owning a child logically named `:worker`. Selection still has to
  be by coordinate rather than by that name.

  Two regions `:r1` / `:r2` each declare a `:spawn-all` with the SAME logical
  child id `:worker`. The test completes `:r2`'s worker first and asserts ONLY
  `:r2` folds; `:r1` and its worker are untouched; and no stale/bad-child evidence
  fires for the legitimate completion."
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
  "A worker child that completes the ONE way every machine completes: `:go`
  reaches the top-level `:final?` leaf `:done`, `:fail` the `:error? true` leaf
  `:failed`. It names no parent and no completion event. The runtime's finalize
  cascade mints the completion carrier from the child's own `:rf/join-child`
  record, so the carrier is exact-current for THIS region's attempt by
  construction — which is the property this file selects on."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id (fn [{d :data e :event}] {:data (assoc d :id (second e))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done}
                            :fail   {:target :failed}}}
             :done   {:final? true :output-key :id}
             :failed {:final? true :error? true :output-key :id}}})

;; A never-completing helper child so the two-child `:all` join stays OPEN after
;; the `:worker` folds (a single-child join would RESOLVE + tear the region
;; down, clearing the join-state we assert on). The helper just sits idle.
(def ^:private idle-helper
  {:initial :idle :states {:idle {}}})

(defn- region-spawn-all
  "A two-child (`:worker` + never-completing `:helper`) `:spawn-all` region
  resolving to a region-distinct `on-complete` event. The `:worker` logical id
  is shared across the two regions, which is exactly the ambiguity the fix
  routes past by exact-attempt coordinate; completing `:worker` alone is
  NON-DECISIVE (the `:all` join still awaits `:helper`), so the join-state
  persists to be asserted on."
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
  (rf/reg-machine :rf2-wsrtlw/wc-r1 (mk-worker))
  (rf/reg-machine :rf2-wsrtlw/wc-r2 (mk-worker))
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
  (testing "two active parallel regions reuse the logical child id :worker;
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
