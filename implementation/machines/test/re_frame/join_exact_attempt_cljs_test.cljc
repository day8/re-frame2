(ns re-frame.join-exact-attempt-cljs-test
  "rf2-nvxehu — join folds are FENCED to the EXACT child attempt and resolved
  join (a fail-closed correlation record, not authentication).

  Per Spec 005 §Child completion protocol a join child completes by reaching a
  `:final?` state, and `lifecycle-fx.finalize` mints ONE reserved carrier for
  it —

      [<parent-id> [:rf.machine.spawn/done <invoke-id> <completion>]]

  — copying the exact-attempt COORDINATE (`:parent-id` / `:invoke-id` /
  `:child-id` / `:spawned-id` / `:attempt` / `:work-generation`) straight off
  the child's runtime-stamped `:rf/join-child` membership record. Strip that
  coordinate and a completion carries no actor or attempt identity at all, so a
  STALE completion from a prior attempt (parent re-entry / child respawn) would
  fold into the SUCCESSOR join and make the CURRENT child appear completed —
  most sharply for a `:fixed-actor-id` child, whose address is identical across
  attempts.

  The fence is ONE gate (rf2-cpbjfp: a fail-closed correlation record, NOT
  authentication — single-trust-domain, gate accidents). A carrier folds only
  when its coordinate EQUALS the current join's parent/invoke identity, logical
  child id, exact current actor id, and exact per-attempt token (minted by
  `spawn-all-init-fx`). A missing / superseded / duplicate coordinate is
  suppressed stale (`:rf.machine.spawn-all/stale-completion`) with zero
  mutation. An exact-current coordinate is accepted regardless of source —
  including deliberate app authoring (unsupported, not prohibited), which is
  what every hand-authored carrier below relies on. The coordinate rides ON THE
  CARRIER and nowhere else: no coeffect, metadata or other side channel can
  supply it.

  Teardown is no longer part of the fence. Completion IS finality, so a child
  that folds into a join destroys ITSELF at its own completion (`:reason
  :rf.machine/finished`) and is already gone by the time the join resolves;
  only SURVIVORS are destroyed at resolution, as genuine cancellations. The
  verified-reap destroy form, its `:resolved?` latch and its
  `:rf.machine/join-reaped` reason are all retired with the old carrier.

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

(defn- mk-child
  "A join child that completes the ONE way every machine completes: on `:go`
  it reaches the top-level `:final?` leaf `:done` (`:output-key :id` selects
  its result), on `:fail` the `:error? true` leaf `:failed`. It dispatches
  nothing and carries no parent vocabulary, so the runtime's own finalize
  cascade mints the completion carrier — with the exact-attempt coordinate
  copied off the child's `:rf/join-child` membership record."
  []
  {:initial :running
   :data    {:id nil}
   :actions {:record-id (fn [{data :data ev :event}]
                          {:data (assoc data :id (second ev))})}
   :states  {:running {:on {:set-id {:action :record-id}
                            :go     {:target :done}
                            :fail   {:target :failed}}}
             :done   {:final? true :output-key :id}
             :failed {:final? true :error? true :output-key :id}}})

(defn- reg-join-parent!
  "Register a re-enterable two-child `:all` join parent + `:final?`-completing
  children and start it. The parent stays on `:racing` at resolution (no
  `:on` for `:all/done`) so the join slot survives for post-resolution
  probes; `:abort` exits `:racing` (tearing the attempt down) and `:start`
  re-enters it (seeding a NEW attempt). Returns the seeded join state."
  [parent-kw child-a-kw child-b-kw]
  (rf/reg-machine child-a-kw (mk-child))
  (rf/reg-machine child-b-kw (mk-child))
  (rf/reg-machine parent-kw
    {:initial :idle
     :states  {:idle   {:on {:start :racing}}
               :racing {:spawn-all
                        {:children        [{:id :a :machine-id child-a-kw :start [:set-id :a]}
                                           {:id :b :machine-id child-b-kw :start [:set-id :b]}]
                         :join            :all
                         :on-all-complete [:all/done]}
                        :on {:abort :idle}}}})
  (rf/dispatch-sync [parent-kw [:start]])
  (join-state parent-kw))

(defn- dispatch-forged!
  "Hand-dispatch the reserved completion carrier

      [<parent> [:rf.machine.spawn/done <invoke-id> <completion>]]

  that `lifecycle-fx.finalize` mints at a child's finality — here with a
  HAND-AUTHORED `completion`, so a test can present the stale / cross-attempt /
  wrong-actor / unstamped / duplicate coordinate the runtime itself would never
  mint. The coordinate rides ON THE CARRIER and nowhere else: an EXACT-CURRENT
  one folds regardless of who authored it, a MISMATCHED one fails closed, and
  one bearing no `:attempt` is unverifiable.

  `completion` must carry `:child-id` — that is what marks it a JOIN child's
  completion and routes it to the join fold rather than to the `:spawn`
  `:on-done` path. The OUTER `invoke-id` is what the fold looks the join state
  up by; the coordinate's own `:invoke-id` is then checked against it."
  ([parent-kw completion]
   (dispatch-forged! parent-kw [:racing] completion))
  ([parent-kw invoke-id completion]
   (rf/dispatch-sync [parent-kw [:rf.machine.spawn/done invoke-id completion]])))

(defn- exact-completion
  "The `:done` completion the runtime WOULD mint for `child-id` at the CURRENT
  attempt of the join at `[parent-kw [:racing]]` — every coordinate field read
  straight off live runtime state, then hand-assembled. Tests `assoc` one field
  off-current to exercise a single fence clause."
  [parent-kw child-id]
  (let [j (join-state parent-kw)]
    {:result     child-id
     :error?     false
     :child-id   child-id
     :parent-id  parent-kw
     :invoke-id  [:racing]
     :spawned-id (get-in j [:children child-id])
     :attempt    (:rf/attempt j)}))

(defn- unstamped-completion
  "A completion bearing NO exact-attempt coordinate at all — a hand-authored
  carrier that never came from a child's finality."
  [child-id]
  {:result child-id :error? false :child-id child-id})

;; ---------------------------------------------------------------------------
;; rf2-nsbwft / rf2-cpbjfp — the fence is fail-closed-on-mismatch + accept-on-
;; exact, NOT a "protected channel". The coordinate is read from ONE place, the
;; carrier the runtime minted; an event-metadata side channel is not read (a
;; pure narrowing). An EXACT-CURRENT coordinate is accepted regardless of
;; source — including one the app author deliberately hand-crafts onto the
;; carrier (unsupported, not prohibited). The honesty: an exact-current tuple
;; is not "forged" — it is what the fence is defined to accept; a MISMATCHED
;; tuple is what fails closed.
;; ---------------------------------------------------------------------------

(deftest exact-current-coordinate-accepted-from-any-source-metadata-slot-not-read
  (testing "rf2-nsbwft / rf2-cpbjfp — accept-on-exact + fail-closed-on-mismatch.
            (1) The EXACT-CURRENT coordinate ON THE CARRIER is ACCEPTED and
            folds — even though the app author hand-crafted every field here
            (an exact-current coordinate is accepted regardless of source;
            deliberate authoring is unsupported, not prohibited).
            (2) The IDENTICAL tuple on event-vector METADATA, over a carrier
            bearing no coordinate of its own, folds nothing
            (`:attempt-unverified`): the metadata slot is not read — a pure
            narrowing, not a secrecy boundary. The fold reads the coordinate
            ONLY off the carrier's own completion map."
    (reg-join-parent! :jea/meta1 :jea/meta1a :jea/meta1b)
    ;; (1) exact-current coordinate on the carrier — ACCEPTED + folds, from
    ;;     deliberate app authoring.
    (rf.machines.test-support/reset-captured!)
    (dispatch-forged! :jea/meta1 (exact-completion :jea/meta1 :a))
    (is (= #{:a} (:done (join-state :jea/meta1)))
        "the hand-authored EXACT-CURRENT coordinate folds — accepted regardless of source")
    (is (empty? (stale-reasons))
        "no stale suppression — an exact-current coordinate is what the fence accepts, not a forgery")
    ;; (2) the IDENTICAL tuple on METADATA — folds nothing (the slot is not read).
    (reg-join-parent! :jea/meta2 :jea/meta2a :jea/meta2b)
    (let [exact2 (exact-completion :jea/meta2 :a)]
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync
        [:jea/meta2 (with-meta [:rf.machine.spawn/done [:racing] (unstamped-completion :a)]
                               {:rf/join-attempt exact2})])
      (is (= #{} (:done (join-state :jea/meta2)))
          "the metadata-borne exact-current tuple folded nothing (metadata slot not read)")
      (is (false? (:resolved? (join-state :jea/meta2))) "no resolution")
      (is (= [:rf.machine.spawn-all/attempt-unverified] (stale-reasons))
          "the metadata slot is not read — coordinate-less carrier"))))

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
          a1     (get-in j1 [:children :a])
          ;; attempt 1's OWN completion, captured while attempt 1 is live.
          c1     (exact-completion :jea/p1 :a)]
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
        (dispatch-forged! :jea/p1 c1)
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
      ;; CURRENT actor id (read off attempt 2), PRIOR attempt token.
      (let [c (assoc (exact-completion :jea/p2 :a) :attempt token1)]
        (is (= (get-in (join-state :jea/p2) [:children :a]) (:spawned-id c))
            "the carrier names attempt 2's CURRENT actor for :a")
        (rf.machines.test-support/reset-captured!)
        (dispatch-forged! :jea/p2 c)
        (is (= #{} (:done (join-state :jea/p2)))
            "an old-token carrier cannot fold even when the actor id matches")
        (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons)))))))

;; ---------------------------------------------------------------------------
;; unstamped / wrong-actor / wrong-child / wrong-invoke carriers
;; ---------------------------------------------------------------------------

(deftest unstamped-carrier-is-suppressed-unverified
  (testing "rf2-nvxehu — a bare hand-authored completion (never minted by a
            member child's finality, so it bears no exact-attempt coordinate)
            is classified :attempt-unverified and folds nothing"
    (reg-join-parent! :jea/p3 :jea/p3a :jea/p3b)
    (rf.machines.test-support/reset-captured!)
    (dispatch-forged! :jea/p3 (unstamped-completion :a))
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
      (dispatch-forged! :jea/p4 (assoc (exact-completion :jea/p4 :a)
                                       ;; WRONG actor — sibling :b's id
                                       :spawned-id (get-in j [:children :b])))
      (is (= #{} (:done (join-state :jea/p4))))
      (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))))))

(deftest wrong-child-for-correct-actor-is-superseded
  (testing "rf2-nvxehu — the MIRROR of the wrong-actor arc: a carrier claiming
            child :b while bearing child :a's spawned actor (correct parent,
            invoke and token) fails the exact actor-identity clause the other
            way round. The child id and the actor address must agree with the
            join's OWN `:children` mapping — either one alone proves nothing."
    (let [j (reg-join-parent! :jea/p5 :jea/p5a :jea/p5b)]
      (rf.machines.test-support/reset-captured!)
      (dispatch-forged! :jea/p5 (assoc (exact-completion :jea/p5 :b)
                                       ;; claims :b, carries :a's actor
                                       :spawned-id (get-in j [:children :a])))
      (is (= #{} (:done (join-state :jea/p5))))
      (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons))))))

(deftest wrong-invoke-identity-is-superseded
  (testing "rf2-nvxehu — a carrier whose COORDINATE names a different invoke
            path than the one it was routed to fails the parent/invoke identity
            clause. The outer invoke-id is what looks the join up; the
            coordinate's own `:invoke-id` is checked against it, so the two
            disagreeing is a mis-routed carrier and folds nothing."
    (reg-join-parent! :jea/p6 :jea/p6a :jea/p6b)
    (rf.machines.test-support/reset-captured!)
    (dispatch-forged! :jea/p6 [:racing]
                      (assoc (exact-completion :jea/p6 :a)
                             :invoke-id [:other-invoke])) ;; WRONG invoke
    (is (= #{} (:done (join-state :jea/p6))))
    (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons)))))

(deftest wrong-parent-identity-is-superseded
  (testing "rf2-nvxehu — the parent half of the same clause: a coordinate
            naming a DIFFERENT parent, delivered to this one, folds nothing"
    (reg-join-parent! :jea/p6b1 :jea/p6b1a :jea/p6b1b)
    (rf.machines.test-support/reset-captured!)
    (dispatch-forged! :jea/p6b1 (assoc (exact-completion :jea/p6b1 :a)
                                       :parent-id :jea/some-other-parent))
    (is (= #{} (:done (join-state :jea/p6b1))))
    (is (= [:rf.machine.spawn-all/attempt-superseded] (stale-reasons)))))

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
      ;; Legit fold of :a (non-decisive in a 2-child :all): :a reaches its
      ;; `:final?` leaf and the runtime mints its completion carrier.
      (rf/dispatch-sync [a [:go]])
      (is (= #{:a} (:done (join-state :jea/p7))) ":a folded")
      (rf.machines.test-support/reset-captured!)
      ;; Exact duplicate: an exact-current coordinate for the CURRENT attempt.
      (dispatch-forged! :jea/p7 (exact-completion :jea/p7 :a))
      (is (= #{:a} (:done (join-state :jea/p7))) "the fold record is unchanged")
      (is (false? (:resolved? (join-state :jea/p7))) "no premature resolution")
      (is (= [:rf.machine.spawn-all/duplicate-completion] (stale-reasons))
          "stable typed evidence: :duplicate-completion")
      ;; The join still resolves on the genuine decisive completion.
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jea/p7)))
          "the genuine decisive completion still resolves the join"))))

;; ---------------------------------------------------------------------------
;; teardown — completion IS finality, so a folded child is already gone
;; ---------------------------------------------------------------------------

(deftest folded-child-closes-itself-at-finality-and-the-join-reaps-nothing
  (testing "Spec 005 §Final states D4 — a child that folds into a STILL-WAITING
            :all join tears ITSELF down at its own completion, with the
            non-cancellation reason :rf.machine/finished, BEFORE the parent
            ever sees the carrier. At resolution the join therefore emits NO
            destroy for it — only SURVIVORS are destroyed, as genuine
            :explicit cancellations. This is what retired the verified-reap
            destroy form, its :resolved? latch (:cause :unresolved-join) and
            the cancellation-suppressing :rf.machine/join-reaped reason: there
            is no second, contradictory terminal left to suppress."
    (let [j (reg-join-parent! :jea/p8 :jea/p8a :jea/p8b)
          a (get-in j [:children :a])
          b (get-in j [:children :b])]
      (rf.machines.test-support/reset-captured!)
      ;; :a folds; the 2-child :all join is NOT resolved.
      (rf/dispatch-sync [a [:go]])
      (is (= #{:a} (:done (join-state :jea/p8))) ":a folded")
      (is (false? (:resolved? (join-state :jea/p8))) "the join still waits on :b")
      (is (nil? (rf.machines.test-support/snapshot a))
          "the folded child is ALREADY gone — finality tore it down at completion")
      (is (= [:rf.machine/finished] (mapv (comp :reason :tags) (destroyed-for a)))
          "exactly one destroyed trace for :a, and it is its own finality — never a reap")
      (is (some? (rf.machines.test-support/snapshot b)) "the survivor :b is still live")
      ;; Resolution: :b completes decisively. :a is long gone, so the join has
      ;; nothing to tear down for it, and :b closed itself the same way.
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [b [:go]])
      (is (true? (:resolved? (join-state :jea/p8))) "the join resolved")
      (is (empty? (destroyed-for a))
          "the resolution emitted NO destroy for the already-folded child")
      (is (= [:rf.machine/finished] (mapv (comp :reason :tags) (destroyed-for b)))
          "the decisive child also closed itself at its finality"))))

(deftest join-resolution-destroys-only-survivors
  (testing "Spec 005 §Spawn-and-join — an :any join resolves on the first
            completion: the decisive child is already gone (it finished), and
            the SURVIVOR is destroyed as a genuine :explicit cancellation
            carrying :rf.machine.spawn/cancelled-on-join-resolution. A test
            that counted a destroy per completed child now counts survivors
            only."
    (rf/reg-machine :jea/p10a (mk-child))
    (rf/reg-machine :jea/p10b (mk-child))
    (rf/reg-machine :jea/p10
      {:initial :idle
       :states  {:idle   {:on {:start :racing}}
                 :racing {:spawn-all
                          {:children         [{:id :a :machine-id :jea/p10a :start [:set-id :a]}
                                              {:id :b :machine-id :jea/p10b :start [:set-id :b]}]
                           :join             :any
                           :on-some-complete [:any/done]}}}})
    (rf/dispatch-sync [:jea/p10 [:start]])
    (let [j (join-state :jea/p10)
          a (get-in j [:children :a])
          b (get-in j [:children :b])]
      (rf.machines.test-support/reset-captured!)
      (rf/dispatch-sync [a [:go]])
      (is (true? (:resolved? (join-state :jea/p10))) "the :any join resolved on :a")
      (is (= [:rf.machine/finished] (mapv (comp :reason :tags) (destroyed-for a)))
          "the decisive child closed itself — the join added no destroy for it")
      (is (= [:explicit] (mapv (comp :reason :tags) (destroyed-for b)))
          "the SURVIVOR is destroyed by the join, as a genuine :explicit cancellation")
      (is (= [:b] (mapv (comp :child-id :tags)
                        (rf.machines.test-support/events-of
                          :rf.machine.spawn/cancelled-on-join-resolution)))
          "exactly one cancelled-on-join-resolution trace, for the survivor"))))

;; ---------------------------------------------------------------------------
;; the genuine flow stays green through the runtime-minted carrier
;; ---------------------------------------------------------------------------

(deftest genuine-child-completions-still-fold-and-resolve
  (testing "rf2-nvxehu — real member children reaching their `:final?` leaves
            still fold and resolve the join (the runtime-minted carrier
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
  "Resolve a fresh `reg-join-parent!` `:all` join by driving BOTH children to
  their `:final?` leaves (so each folds through the runtime-minted carrier and
  its exact-attempt coordinate). The parent has no `:on` for `:all/done`, so it
  stays on `:racing` and the resolved join slot survives for post-resolution
  probes. Returns the resolved join state."
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
    (let [j1 (reg-join-parent! :jea/pr1 :jea/pr1a :jea/pr1b)
          a1 (get-in j1 [:children :a])
          ;; attempt A's OWN completion, captured while attempt A is live.
          cA (exact-completion :jea/pr1 :a)]
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
        (dispatch-forged! :jea/pr1 cA)
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
    (let [j (resolve-all-join! :jea/pr2)]
      (is (true? (:resolved? j)))
      (rf.machines.test-support/reset-captured!)
      (dispatch-forged! :jea/pr2 (exact-completion :jea/pr2 :a))
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
    (dispatch-forged! :jea/pr3 (unstamped-completion :a))
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
    (dispatch-forged! :jea/pr4 (unstamped-completion :zzz))
    (is (empty? (late-completions))
        "no late-completion for an unknown child")
    (is (= 1 (count (bad-child-errors)))
        "the canonical bad-child-id error fires against the resolved join")
    (is (= #{:a :b} (:done (join-state :jea/pr4))) "record frozen")))
