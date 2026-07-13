(ns re-frame.join-exact-auth-cljs-test
  "rf2-nvxehu — join folds and reaps are authenticated to the EXACT child
  attempt and resolved join.

  The completion carrier `[<parent> [<done-kw> <child-id> & extra]]` carries
  no actor or attempt identity, so pre-fix a STALE completion from a prior
  attempt (parent re-entry / child respawn) folded into the successor join
  and could make the CURRENT child appear completed — after which the
  \"verified\" reap derived the current actor from `:children` and destroyed
  it with the cancellation-suppressing `:rf.machine/join-reaped` even though
  it never completed. Separately, the public reserved-fx reap accepted any
  folded child while the join's `:resolved?` latch was still false, so an
  app could invoke the internal cancellation-suppressing reap early during a
  still-waiting `:all` join.

  The fix is ONE exact-authority contract:

    - fold side — a carrier folds only when the `:rf/join-auth` metadata the
      member child's own handler boundary stamped
      (`join/stamp-join-completion-fx`) matches the current join's
      parent/invoke identity, logical child id, exact current actor id, and
      exact per-attempt token (minted by `spawn-all-init-fx`). Unstamped /
      superseded / duplicate carriers are suppressed stale
      (`:rf.machine.spawn-all/stale-completion`) with zero mutation.
    - reap side — the cancellation-suppressing reap additionally requires
      the exact join attempt's `:resolved?` latch (`:cause :unresolved-join`
      refusal ahead of it).

  The file is named `*-cljs-test.cljc` so it's discovered by both
  cognitect-style JVM runs and shadow-cljs (`cljs-test$` ns-regexp)."
  (:require
   #?(:clj  [clojure.test :refer [deftest is testing use-fixtures]]
      :cljs [cljs.test :refer-macros [deftest is testing use-fixtures]])
   [re-frame.core :as rf]
   ;; load the machines artefact so its fx handlers + late-bind hooks are
   ;; installed when this ns runs in isolation.
   [re-frame.machines]
   [re-frame.machines.test-support :as mtest]
   #?@(:clj  [[re-frame.substrate.plain-atom :as plain-atom]]
       :cljs [[re-frame.adapter.reagent :as reagent-adapter]])))

(use-fixtures :each
  (mtest/make-reset-runtime-fixture
    #?(:clj  {:adapter plain-atom/adapter}
       :cljs {:adapter reagent-adapter/adapter}))
  mtest/trace-capture-fixture)

(defn- join-state [parent-id]
  (get-in (mtest/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- stale-completions []
  (mtest/events-of :rf.machine.spawn-all/stale-completion))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags) (stale-completions)))

(defn- destroyed-for [actor-id]
  (filterv #(= actor-id (:actor-id (:tags %)))
           (mtest/events-of :rf.machine/destroyed)))

(defn- bad-arg-causes []
  (mapv (comp :cause :tags) (mtest/events-of :rf.error/machine-destroy-bad-arg)))

(defn- mk-child
  "A dispatching child: on `:go` / `:fail` it transitions to a PLAIN
  (non-`:final?`) terminal and dispatches its completion back to
  `parent-id` — through its OWN handler boundary, so the runtime stamps the
  `:rf/join-auth` attempt authentication."
  [parent-id]
  {:initial :running
   :data    {:id nil}
   :actions {:record-id     (fn [{data :data ev :event}]
                              {:data (assoc data :id (second ev))})
             :dispatch-done (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/done (:id data)]]]]})
             :dispatch-err  (fn [{data :data}]
                              {:fx [[:dispatch [parent-id [:child/failed (:id data)]]]]})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done   :action :dispatch-done}
                            :fail   {:target :failed :action :dispatch-err}}}
             :done   {}
             :failed {}}})

(defn- reg-join-parent!
  "Register a re-enterable two-child `:all` join parent + dispatching
  children and start it. The parent stays on `:racing` at resolution (no
  `:on` for `:all/done`) so the join slot survives for post-resolution
  probes; `:abort` exits `:racing` (tearing the attempt down) and `:start`
  re-enters it (seeding a NEW attempt). Returns the seeded join state."
  [parent-kw child-a-kw child-b-kw]
  (rf/reg-machine child-a-kw (mk-child parent-kw))
  (rf/reg-machine child-b-kw (mk-child parent-kw))
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                           {:id :b :machine-id child-b-kw :start [:set-id :b]}]
                         :join            :all
                         :on-child-done   :child/done
                         :on-child-error  :child/failed
                         :on-all-complete [:all/done]}
                        :on {:abort :idle}}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw))

(defn- dispatch-forged!
  "Hand-dispatch a completion carrier with a CRAFTED `:rf/join-auth` stamp
  (nil `auth` dispatches the bare, unstamped public carrier)."
  [parent-kw inner auth]
  (rf/dispatch-sync
    [parent-kw (if auth
                 (with-meta inner {:rf/join-auth auth})
                 inner)]))

;; ---------------------------------------------------------------------------
;; the P1 counterexample — stale prior-attempt completion after re-entry
;; ---------------------------------------------------------------------------

(deftest stale-prior-attempt-completion-cannot-fold-into-successor-join
  (testing "rf2-nvxehu — after parent re-entry (attempt 2), a carrier bound
            to attempt 1 (old actor id + old attempt token) is classified
            stale (:attempt-superseded) and folds NOTHING: no :done fold, no
            resolution, no terminal, and the current child is NEVER reaped.
            Pre-fix this folded :a into the successor join's :done."
    (let [j1     (reg-join-parent! :jea/p1 :jea/p1a :jea/p1b)
          token1 (:rf/attempt j1)
          a1     (get-in j1 [:children :a])]
      (is (some? token1) "attempt 1 minted an opaque token")
      (is (keyword? a1) "attempt 1 spawned :a")
      ;; Tear attempt 1 down (exit :racing), then re-enter (attempt 2).
      (rf/dispatch-sync [:jea/p1 [:abort]])
      (rf/dispatch-sync [:jea/p1 [:start]])
      (let [j2     (join-state :jea/p1)
            token2 (:rf/attempt j2)
            a2     (get-in j2 [:children :a])]
        (is (some? token2) "attempt 2 minted its own token")
        (is (not= token1 token2) "per-attempt tokens are distinct")
        (mtest/reset-captured!)
        ;; The stale straggler: attempt 1's exact carrier.
        (dispatch-forged! :jea/p1 [:child/done :a]
                          {:parent-id  :jea/p1
                           :invoke-id  [:racing]
                           :child-id   :a
                           :spawned-id a1
                           :attempt    token1})
        (let [j2' (join-state :jea/p1)]
          (is (= #{} (:done j2'))
              "the stale carrier folded NOTHING into the successor join")
          (is (false? (:resolved? j2')) "the successor join did not resolve"))
        (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))
            "exactly one stale-completion with :attempt-superseded evidence")
        (is (some? (mtest/snapshot a2)) "current child :a (A2) is untouched")
        (is (empty? (destroyed-for a2)) "A2 was never reaped or destroyed")))))

(deftest old-token-with-current-actor-id-is-superseded
  (testing "rf2-nvxehu — the attempt token discriminates INDEPENDENTLY of
            actor identity (the :fixed-actor-id-respawn pin, where actor ids
            are equal across attempts): a carrier naming the CURRENT actor
            but a PRIOR attempt token is stale (:attempt-superseded)"
    (let [j1     (reg-join-parent! :jea/p2 :jea/p2a :jea/p2b)
          token1 (:rf/attempt j1)]
      (rf/dispatch-sync [:jea/p2 [:abort]])
      (rf/dispatch-sync [:jea/p2 [:start]])
      (let [j2 (join-state :jea/p2)
            a2 (get-in j2 [:children :a])]
        (mtest/reset-captured!)
        (dispatch-forged! :jea/p2 [:child/done :a]
                          {:parent-id  :jea/p2
                           :invoke-id  [:racing]
                           :child-id   :a
                           :spawned-id a2       ;; CURRENT actor id
                           :attempt    token1}) ;; PRIOR attempt token
        (is (= #{} (:done (join-state :jea/p2)))
            "an old-token carrier cannot fold even when the actor id matches")
        (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons)))))))

;; ---------------------------------------------------------------------------
;; unstamped / wrong-actor / wrong-child / wrong-invoke carriers
;; ---------------------------------------------------------------------------

(deftest unstamped-carrier-is-suppressed-unverified
  (testing "rf2-nvxehu — a bare hand-dispatched completion (never flowed
            through the member child's boundary, so no runtime stamp) is
            classified :attempt-unverified and folds nothing"
    (reg-join-parent! :jea/p3 :jea/p3a :jea/p3b)
    (mtest/reset-captured!)
    (dispatch-forged! :jea/p3 [:child/done :a] nil)
    (is (= #{} (:done (join-state :jea/p3))) "no fold")
    (is (false? (:resolved? (join-state :jea/p3))) "no resolution")
    (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
        "stable typed evidence: :attempt-unverified")))

(deftest wrong-actor-for-correct-child-is-superseded
  (testing "rf2-nvxehu — a carrier naming the correct child but the WRONG
            actor (sibling :b's id, current token) fails the exact
            actor-identity clause"
    (let [j (reg-join-parent! :jea/p4 :jea/p4a :jea/p4b)]
      (mtest/reset-captured!)
      (dispatch-forged! :jea/p4 [:child/done :a]
                        {:parent-id  :jea/p4
                         :invoke-id  [:racing]
                         :child-id   :a
                         :spawned-id (get-in j [:children :b]) ;; WRONG actor
                         :attempt    (:rf/attempt j)})
      (is (= #{} (:done (join-state :jea/p4))))
      (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))))))

(deftest wrong-child-for-correct-actor-is-superseded
  (testing "rf2-nvxehu — a carrier claiming child :b with an auth record
            minted for child :a (correct actor A, correct token) fails the
            logical-child-id clause"
    (let [j (reg-join-parent! :jea/p5 :jea/p5a :jea/p5b)]
      (mtest/reset-captured!)
      (dispatch-forged! :jea/p5 [:child/done :b]
                        {:parent-id  :jea/p5
                         :invoke-id  [:racing]
                         :child-id   :a                        ;; record's child
                         :spawned-id (get-in j [:children :a])
                         :attempt    (:rf/attempt j)})
      (is (= #{} (:done (join-state :jea/p5))))
      (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))))))

(deftest wrong-invoke-identity-is-superseded
  (testing "rf2-nvxehu — a carrier stamped for a DIFFERENT invoke path fails
            the parent/invoke identity clause"
    (let [j (reg-join-parent! :jea/p6 :jea/p6a :jea/p6b)]
      (mtest/reset-captured!)
      (dispatch-forged! :jea/p6 [:child/done :a]
                        {:parent-id  :jea/p6
                         :invoke-id  [:other-invoke]           ;; WRONG invoke
                         :child-id   :a
                         :spawned-id (get-in j [:children :a])
                         :attempt    (:rf/attempt j)})
      (is (= #{} (:done (join-state :jea/p6))))
      (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))))))

;; ---------------------------------------------------------------------------
;; duplicate exact completion
;; ---------------------------------------------------------------------------

(deftest duplicate-exact-completion-is-suppressed
  (testing "rf2-nvxehu / rf2-ir4t5v — an exact re-completion of an
            already-folded child (correct actor, correct token) is suppressed
            (:duplicate-completion): the fold stays as-is and no second
            terminal can publish; the join still resolves normally afterwards"
    (let [j (reg-join-parent! :jea/p7 :jea/p7a :jea/p7b)
          a (get-in j [:children :a])
          b (get-in j [:children :b])]
      ;; Legit fold of :a (non-decisive in a 2-child :all).
      (rf/dispatch-sync [a [:go]])
      (is (= #{:a} (:done (join-state :jea/p7))) ":a folded")
      (mtest/reset-captured!)
      ;; Exact duplicate, correctly authenticated for the CURRENT attempt.
      (dispatch-forged! :jea/p7 [:child/done :a]
                        {:parent-id  :jea/p7
                         :invoke-id  [:racing]
                         :child-id   :a
                         :spawned-id a
                         :attempt    (:rf/attempt (join-state :jea/p7))})
      (is (= #{:a} (:done (join-state :jea/p7))) "the fold record is unchanged")
      (is (false? (:resolved? (join-state :jea/p7))) "no premature resolution")
      (is (= [:rf.machine.spawn-all/duplicate-completion] (stale-reasons))
          "stable typed evidence: :duplicate-completion")
      ;; The join still resolves on the genuine decisive completion.
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jea/p7)))
          "the genuine decisive completion still resolves the join"))))

;; ---------------------------------------------------------------------------
;; the reap-side latch — no early reap through the public fx boundary
;; ---------------------------------------------------------------------------

(deftest completed-child-in-unresolved-join-cannot-be-reaped
  (testing "rf2-nvxehu — a folded (completed) child of a STILL-WAITING :all
            join cannot be reaped through the public reserved-fx boundary:
            the reap is refused with :cause :unresolved-join and no teardown;
            after genuine resolution the same child IS reaped
            (:rf.machine/join-reaped) exactly once. Pre-fix the early reap
            succeeded (membership in :done was the only proof)."
    (rf/reg-event ::forge-reap
      (fn [_ [_ p invoke child-id]]
        {:fx [[:rf.machine/destroy {:rf/reap      true
                                    :rf/parent-id p
                                    :rf/invoke-id invoke
                                    :rf/child-id  child-id}]]}))
    (let [j (reg-join-parent! :jea/p8 :jea/p8a :jea/p8b)
          a (get-in j [:children :a])
          b (get-in j [:children :b])]
      ;; :a folds; the 2-child :all join is NOT resolved.
      (rf/dispatch-sync [a [:go]])
      (is (= #{:a} (:done (join-state :jea/p8))))
      (is (false? (:resolved? (join-state :jea/p8))))
      (mtest/reset-captured!)
      ;; The early reap — refused ahead of the :resolved? latch.
      (rf/dispatch-sync [::forge-reap :jea/p8 [:racing] :a])
      (is (= [:unresolved-join] (bad-arg-causes))
          "refused with the distinct :unresolved-join cause")
      (is (some? (mtest/snapshot a)) "the folded child stays live — no teardown")
      (is (empty? (destroyed-for a)) "no destroyed trace for the refused reap")
      ;; Genuine resolution: :b completes, the join resolves, and the
      ;; resolution cascade reaps BOTH completed children with the
      ;; non-cancellation reason.
      (mtest/reset-captured!)
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jea/p8))))
      (is (nil? (mtest/snapshot a)) "post-resolution, :a is reaped")
      (is (= [:rf.machine/join-reaped]
             (mapv (comp :reason :tags) (destroyed-for a)))
          "exactly one destroyed trace for :a — the genuine post-resolution reap"))))

;; ---------------------------------------------------------------------------
;; the genuine flow stays green through the stamp machinery
;; ---------------------------------------------------------------------------

(deftest genuine-child-completions-still-fold-and-resolve
  (testing "rf2-nvxehu — real member children completing through their own
            handler boundary still fold and resolve the join (the stamp path
            end-to-end), across BOTH attempts of a re-entered parent"
    (let [j1 (reg-join-parent! :jea/p9 :jea/p9a :jea/p9b)]
      ;; Attempt 1 resolves normally.
      (rf/dispatch-sync [(get-in j1 [:children :a]) [:go]])
      (rf/dispatch-sync [(get-in j1 [:children :b]) [:go]])
      (is (true? (:resolved? (join-state :jea/p9))) "attempt 1 resolved")
      ;; Re-enter; attempt 2 resolves normally too (fresh token, fresh stamps).
      (rf/dispatch-sync [:jea/p9 [:abort]])
      (rf/dispatch-sync [:jea/p9 [:start]])
      (let [j2 (join-state :jea/p9)]
        (is (false? (:resolved? j2)))
        (rf/dispatch-sync [(get-in j2 [:children :a]) [:go]])
        (rf/dispatch-sync [(get-in j2 [:children :b]) [:go]])
        (is (true? (:resolved? (join-state :jea/p9))) "attempt 2 resolved")))))
