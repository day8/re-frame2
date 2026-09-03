(ns re-frame.join-exact-attempt-cljs-test
  "rf2-nvxehu — join folds and reaps are FENCED to the EXACT child attempt and
  resolved join (a fail-closed correlation record, not authentication).

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

  The fix is ONE exact-attempt fence (rf2-cpbjfp: a fail-closed correlation
  record, NOT authentication — single-trust-domain, gate accidents):

    - fold side — a carrier folds only when its exact-attempt `:rf/join-attempt`
      COORDINATE (stamped by the member child's own handler boundary,
      `join/stamp-join-completion-fx`, and carried on the recordable `:rf.cofx`
      slot, rf2-t154jx — the metadata slot is not read, rf2-nsbwft) EQUALS the
      current join's parent/invoke identity, logical child id, exact current
      actor id, and exact per-attempt token (minted by `spawn-all-init-fx`). A
      missing / superseded / duplicate coordinate is suppressed stale
      (`:rf.machine.spawn-all/stale-completion`) with zero mutation. An
      exact-current coordinate is accepted regardless of source — including
      deliberate app authoring (unsupported, not prohibited).
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
   [re-frame.machines.test-support :as rf.machines.test-support]
   #?@(:clj  [[re-frame.substrate.plain-atom :as rf.substrate.plain-atom]]
       :cljs [[re-frame.adapter.reagent :as rf.adapter.reagent]])))

(use-fixtures :each
  (rf.machines.test-support/make-reset-runtime-fixture
    #?(:clj  {:adapter rf.substrate.plain-atom/adapter}
       :cljs {:adapter rf.adapter.reagent/adapter}))
  rf.machines.test-support/trace-capture-fixture)

(defn- join-state [parent-id]
  (get-in (rf.machines.test-support/runtime-db)
          [:rf.runtime/machines :spawned parent-id [:racing]]))

(defn- stale-completions []
  (rf.machines.test-support/events-of :rf.machine.spawn-all/stale-completion))

(defn- stale-reasons []
  (mapv (comp :rf.reply/stale-reason :tags) (stale-completions)))

(defn- destroyed-for [actor-id]
  (filterv #(= actor-id (:actor-id (:tags %)))
           (rf.machines.test-support/events-of :rf.machine/destroyed)))

(defn- bad-arg-causes []
  (mapv (comp :cause :tags) (rf.machines.test-support/events-of :rf.error/machine-destroy-bad-arg)))

(defn- mk-child
  "A dispatching child: on `:go` / `:fail` it transitions to a PLAIN
  (non-`:final?`) terminal and dispatches its completion back to
  `parent-id` — through its OWN handler boundary, so the runtime stamps its
  exact-attempt coordinate on the recordable `:rf.cofx` fact `:rf.machine/join-attempt`
  (via `stamp-join-completion-fx`; the metadata slot is not read)."
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
  "Hand-dispatch a completion carrier presenting a HAND-AUTHORED `:rf/join-attempt`
  coordinate on the recordable `:rf.cofx` slot (rf2-t154jx / rf2-nsbwft) — the ONE
  coordinate slot the parent reads (`carrier-attempt`). This is the slot the
  runtime's own `:rf.machine/join-dispatch` transport and a strict replay
  populate, and deliberate app authoring lands here too; the metadata slot is not
  read (see `exact-current-coordinate-accepted-from-any-source-metadata-slot-not-read`),
  so tests present crafted coordinates on the coeffect, not on the event. A
  MISMATCHED coordinate fails closed; an EXACT-CURRENT one folds regardless of
  source. nil `coordinate` dispatches the bare, coordinate-less public carrier."
  [parent-kw inner coordinate]
  (if coordinate
    (rf/dispatch-sync [parent-kw inner] {:rf.cofx {:rf.machine/join-attempt coordinate}})
    (rf/dispatch-sync [parent-kw inner])))

;; ---------------------------------------------------------------------------
;; rf2-nsbwft / rf2-cpbjfp — the fence is fail-closed-on-mismatch + accept-on-
;; exact, NOT a "protected channel". The metadata slot is not read (a pure
;; narrowing); the recordable `:rf.cofx` slot accepts an EXACT-CURRENT
;; coordinate regardless of source — including a coordinate the app author
;; deliberately hand-crafts onto the coeffect (unsupported, not prohibited).
;; The honesty: an exact-current tuple is not "forged" — it is what the fence
;; is defined to accept; a MISMATCHED tuple is what fails closed.
;; ---------------------------------------------------------------------------

(deftest exact-current-coordinate-accepted-from-any-source-metadata-slot-not-read
  (testing "rf2-nsbwft / rf2-cpbjfp — accept-on-exact + fail-closed-on-mismatch.
            (1) The EXACT-CURRENT coordinate on the recordable `:rf.cofx` slot is
            ACCEPTED and folds — even though the app author hand-crafted every
            field here (an exact-current coordinate is accepted regardless of
            source; deliberate authoring is unsupported, not prohibited).
            (2) The IDENTICAL tuple on event-vector METADATA folds nothing
            (`:attempt-unverified`): the metadata slot is not read — a pure
            narrowing, not a secrecy boundary. `carrier-attempt` reads the
            coordinate ONLY from the coeffect."
    (let [j (reg-join-parent! :jea/meta1 :jea/meta1a :jea/meta1b)
          a (get-in j [:children :a])
          ;; every field read straight off live runtime state — an EXACT-CURRENT
          ;; coordinate the app author assembles deliberately.
          exact {:parent-id  :jea/meta1
                 :invoke-id  [:racing]
                 :child-id   :a
                 :spawned-id a
                 :attempt    (:rf/attempt j)}]
      ;; (1) exact-current coordinate on the recordable cofx — ACCEPTED + folds,
      ;;     from deliberate app authoring.
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [:jea/meta1 [:child/done :a]]
                        {:rf.cofx {:rf.machine/join-attempt exact}})
      (is (= #{:a} (:done (join-state :jea/meta1)))
          "the hand-authored EXACT-CURRENT coordinate on the coeffect folds — accepted regardless of source")
      (is (empty? (stale-reasons))
          "no stale suppression — an exact-current coordinate is what the fence accepts, not a forgery")
      ;; (2) the IDENTICAL tuple on METADATA — folds nothing (the slot is not read).
      (reg-join-parent! :jea/meta2 :jea/meta2a :jea/meta2b)
      (let [j2 (join-state :jea/meta2)
            a2 (get-in j2 [:children :a])
            exact2 (assoc exact :parent-id :jea/meta2 :spawned-id a2 :attempt (:rf/attempt j2))]
        (rf.machines.test-support/reset-captured!)
        (rf/dispatch-sync [:jea/meta2 (with-meta [:child/done :a] {:rf/join-attempt exact2})])
        (is (= #{} (:done (join-state :jea/meta2)))
            "the metadata-borne exact-current tuple folded nothing (metadata slot not read)")
        (is (false? (:resolved? (join-state :jea/meta2))) "no resolution")
        (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
            "the metadata slot is not read — coordinate-less carrier")))))

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
        (rf.machines.test-support/reset-captured!)
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
        (is (some? (rf.machines.test-support/snapshot a2)) "current child :a (A2) is untouched")
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
        (rf.machines.test-support/reset-captured!)
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
    (rf.machines.test-support/reset-captured!)
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
      (rf.machines.test-support/reset-captured!)
      (dispatch-forged! :jea/p4 [:child/done :a]
                        {:parent-id  :jea/p4
                         :invoke-id  [:racing]
                         :child-id   :a
                         :spawned-id (get-in j [:children :b]) ;; WRONG actor
                         :attempt    (:rf/attempt j)})
      (is (= #{} (:done (join-state :jea/p4))))
      (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))))))

(deftest wrong-child-for-correct-actor-is-superseded
  (testing "rf2-nvxehu — a carrier claiming child :b with an attempt coordinate
            minted for child :a (correct actor A, correct token) fails the
            logical-child-id clause"
    (let [j (reg-join-parent! :jea/p5 :jea/p5a :jea/p5b)]
      (rf.machines.test-support/reset-captured!)
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
      (rf.machines.test-support/reset-captured!)
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
      (rf.machines.test-support/reset-captured!)
      ;; Exact duplicate: an exact-current coordinate for the CURRENT attempt.
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
      (rf.machines.test-support/reset-captured!)
      ;; The early reap — refused ahead of the :resolved? latch.
      (rf/dispatch-sync [::forge-reap :jea/p8 [:racing] :a])
      (is (= [:unresolved-join] (bad-arg-causes))
          "refused with the distinct :unresolved-join cause")
      (is (some? (rf.machines.test-support/snapshot a)) "the folded child stays live — no teardown")
      (is (empty? (destroyed-for a)) "no destroyed trace for the refused reap")
      ;; Genuine resolution: :b completes, the join resolves, and the
      ;; resolution cascade reaps BOTH completed children with the
      ;; non-cancellation reason.
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jea/p8))))
      (is (nil? (rf.machines.test-support/snapshot a)) "post-resolution, :a is reaped")
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

;; ---------------------------------------------------------------------------
;; rf2-ixjd48 — validate ownership + exact-attempt coordinate BEFORE the
;; resolved-vs-unresolved classification. Pre-fix the `:resolved?` branch ran
;; FIRST, so ANY matching event shape against a resolved join was attributed
;; to the CURRENT attempt: an old-attempt straggler, an unstamped carrier, or
;; even an unknown child forged a `:late-completion` record built from the
;; CURRENT join's `[:children child-id]`, borrowing the current attempt's
;; spawned/work identity. The fix gates the post-resolution late-completion
;; path on an EXACT-CURRENT carrier; every stale/forged carrier is classified
;; the same way it is on the pre-resolution path, with ZERO db mutation.
;; ---------------------------------------------------------------------------

(defn- late-completions []
  (rf.machines.test-support/events-of :rf.machine.spawn-all/late-completion))

(defn- bad-child-errors []
  (rf.machines.test-support/events-of :rf.error/machine-spawn-all-bad-child-id))

(defn- resolve-all-join!
  "Resolve a fresh `reg-join-parent!` `:all` join by completing BOTH children
  through their own handler boundaries (so they fold with valid attempt
  coordinates). The parent has no `:on` for `:all/done`, so it stays on
  `:racing` and the resolved join slot survives for post-resolution probes.
  Returns the resolved join state."
  [parent-kw]
  (let [j (join-state parent-kw)]
    (rf/dispatch-sync [(get-in j [:children :a]) [:go]])
    (rf/dispatch-sync [(get-in j [:children :b]) [:go]])
    (join-state parent-kw)))

(deftest old-attempt-straggler-against-resolved-successor-is-superseded
  (testing "rf2-ixjd48 — THE COUNTEREXAMPLE. Attempt A's completion is queued;
            the parent re-enters, installs attempt B, and B RESOLVES; then A
            drains. Pre-fix the `:resolved?` branch ran first and forged a
            join-resolved `:late-completion` carrying B's CURRENT spawned/work
            identity for A's straggler. The fix validates the exact-attempt coordinate first: A is
            classified `:attempt-superseded` carrying ITS OWN (attempt-A)
            identity, NO late-completion fires, and B's resolved join is
            untouched (zero db mutation)."
    (let [j1     (reg-join-parent! :jea/pr1 :jea/pr1a :jea/pr1b)
          token1 (:rf/attempt j1)
          a1     (get-in j1 [:children :a])]
      ;; Tear attempt 1 down (its completion is still 'in flight'), re-enter
      ;; (attempt 2 = B), and RESOLVE B.
      (rf/dispatch-sync [:jea/pr1 [:abort]])
      (rf/dispatch-sync [:jea/pr1 [:start]])
      (let [j2 (resolve-all-join! :jea/pr1)
            a2 (get-in j2 [:children :a])]
        (is (true? (:resolved? j2)) "attempt B resolved")
        (is (not= a1 a2) "attempt B respawned :a as a fresh instance")
        (rf.machines.test-support/reset-captured!)
        ;; Attempt A's exact carrier drains AFTER B resolved.
        (dispatch-forged! :jea/pr1 [:child/done :a]
                          {:parent-id  :jea/pr1
                           :invoke-id  [:racing]
                           :child-id   :a
                           :spawned-id a1
                           :attempt    token1})
        ;; (1) exactly :attempt-superseded evidence; NO late-completion.
        (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))
            "the old-attempt straggler is superseded, not late-completed")
        (is (empty? (late-completions))
            "no late-completion record for a superseded straggler")
        ;; (2) the evidence carries ATTEMPT A's own identity, never B's.
        (let [wid (:rf.reply/work-id (:tags (first (stale-completions))))]
          (is (= a1 (nth wid 1))
              "the superseded evidence carries the CARRIER's own (attempt-A) actor")
          (is (not= a2 (nth wid 1))
              "the superseded evidence does NOT borrow attempt B's current identity"))
        ;; (3) zero db mutation — B's resolved join is untouched.
        (let [j2' (join-state :jea/pr1)]
          (is (= #{:a :b} (:done j2')) "B's :done set unchanged")
          (is (true? (:resolved? j2')) "B stays resolved")
          (is (= (:children j2) (:children j2')) "B's children mapping unchanged"))))))

(deftest exact-current-carrier-after-resolution-is-late-completion
  (testing "rf2-ixjd48 — THE PRESERVED PATH. An EXACT-CURRENT
            carrier arriving after its OWN join resolved (a genuine current
            survivor draining post-latch) still takes the join-resolved
            `:late-completion` path — the fix gates late-completion on the
            exact-attempt fence, it does not remove it."
    (reg-join-parent! :jea/pr2 :jea/pr2a :jea/pr2b)
    (let [j (resolve-all-join! :jea/pr2)
          a (get-in j [:children :a])]
      (is (true? (:resolved? j)))
      (rf.machines.test-support/reset-captured!)
      (dispatch-forged! :jea/pr2 [:child/done :a]
                        {:parent-id  :jea/pr2
                         :invoke-id  [:racing]
                         :child-id   :a
                         :spawned-id a
                         :attempt    (:rf/attempt j)})
      (is (= 1 (count (late-completions)))
          "the exact-current carrier still fires the late-completion op")
      (is (empty? (stale-completions))
          "no pre-resolution stale-completion class fired")
      (let [tags (:tags (first (late-completions)))]
        (is (= :stale (:rf.reply/status tags)))
        (is (= :rf.machine.spawn-all/join-resolved
               (:rf.reply/stale-reason tags))))
      (is (= #{:a :b} (:done (join-state :jea/pr2))) "record frozen — no re-fold"))))

(deftest unstamped-carrier-against-resolved-join-is-unverified
  (testing "rf2-ixjd48 — acceptance: an UNSTAMPED carrier against a RESOLVED
            join is `:attempt-unverified`, NOT late-completion. Pre-fix the
            `:resolved?` branch attributed it before checking the exact-attempt coordinate."
    (reg-join-parent! :jea/pr3 :jea/pr3a :jea/pr3b)
    (resolve-all-join! :jea/pr3)
    (rf.machines.test-support/reset-captured!)
    (dispatch-forged! :jea/pr3 [:child/done :a] nil)
    (is (empty? (late-completions))
        "no late-completion for a coordinate-less carrier")
    (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
        "an unstamped post-resolution carrier is attempt-unverified")
    (is (= #{:a :b} (:done (join-state :jea/pr3))) "record frozen")))

(deftest unknown-child-against-resolved-join-is-bad-child
  (testing "rf2-ixjd48 — acceptance: an UNKNOWN child-id against a RESOLVED
            join takes the canonical bad-child-id error path, NOT the resolved
            late-completion path (pre-fix it forged a late-completion built
            from a nil `[:children child-id]`)."
    (reg-join-parent! :jea/pr4 :jea/pr4a :jea/pr4b)
    (resolve-all-join! :jea/pr4)
    (rf.machines.test-support/reset-captured!)
    (dispatch-forged! :jea/pr4 [:child/done :zzz] nil)
    (is (empty? (late-completions))
        "no late-completion for an unknown child")
    (is (= 1 (count (bad-child-errors)))
        "the canonical bad-child-id error fires against the resolved join")
    (is (= #{:a :b} (:done (join-state :jea/pr4))) "record frozen")))
