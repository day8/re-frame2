(ns re-frame.hicasso.reincarnation-routing-cljs-test
  "SAME-PUBLIC-ID FRAME REINCARNATION — where a delayed operation lands
  (rf2-hic-013).

  A frame is destroyed and a new one created under the SAME public keyword
  id. Every handle, bundle and lowered callback the arm minted against the
  first incarnation is still reachable from application code: a
  `setTimeout`, a promise continuation, a WebSocket `onmessage`, a
  predecessor React root's deferred effect cleanup. The law is that none
  of them may write into the successor.
  `re-frame.hicasso.impl.frames` names this as its reason for existing,
  and `re-frame.hicasso.impl.generation/commit-basis` names the
  reincarnation as the one axis its number is structurally blind to.

  ## Why these observables, and not the rendered markup

  **The DOM is the wrong instrument for this fault, and measurably so.**
  The arm's READ path is address-directed — a cell's key is
  `[frame-kw query-v]` on the PUBLIC id, and `subs/subscribe` resolves
  that id when it is called — so a boundary re-rendered after the
  reincarnation reads the SUCCESSOR's value and paints it correctly. The
  WRITE path resolves through the memoised `rf/capture-frame` bundle in
  [[re-frame.hicasso.impl.frames/!frame-ops]], which is pinned to one
  incarnation. The two halves of the same runtime therefore disagree about
  which frame they are talking to, and the visible symptom of the
  disagreement is markup that is byte-for-byte correct above controls that
  write to the wrong frame or to none. A value-level assertion on rendered
  output is green throughout, in both directions.

  So the observables are taken where the disagreement lives: the
  incarnation token, the memo table's contents by identity, the
  successor's app-db after the operation, and the always-on
  `:rf.error/frame-destroyed` corpus record. That last one is the only
  thing separating *refused* from *silently dropped* — and, in section 4,
  *refused* from *silently delivered to the wrong frame*, which no other
  observable here distinguishes.

  ## Section 4 records a DEFECT, not a contract

  Sections 1–3 are contracts. Section 4 is a measurement of behaviour that
  is wrong, recorded because the remedy is a runtime change outside this
  bead's surface. It is written so that it FAILS when the defect is fixed,
  and its docstring says what to replace it with. See
  `docs/design/hicasso/product/invariants.md` §7.

  ## Companion

  `reincarnation_cells_cljs_test` takes the same transition on the
  committed side: the held cell, React's own change-detection number, and
  the macrotask-deferred repair."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.frames :as frames]
            [re-frame.hicasso.impl.generation :as generation]
            [re-frame.test-support :as test-support]))

(def ^:private frame-id ::reincarnation)

;; Registered above `use-fixtures`, as the package smoke's are and for the same
;; reason: the reset fixture captures its source-store baseline when the
;; `use-fixtures` form is evaluated.
;;
;; `:reinc/mark` writes a distinctive leaf, so a write that reached the WRONG
;; incarnation is unmistakable in that incarnation's app-db rather than merely
;; absent from the right one. Both handlers resolve in EVERY incarnation (the
;; registry is global), which is what makes an absent mark a real refusal
;; rather than an unresolved miss.
(rf/reg-event :reinc/seed (fn [_ [_ who]] {:db {:who who}}))
(rf/reg-event :reinc/mark (fn [{:keys [db]} [_ tag]] {:db (assoc db :marked tag)}))
(rf/reg-sub   :reinc/who  (fn [db _] (:who db)))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (collector/reset-runtime!)
                      (error-emit/clear-error-listeners!))}))

;; ---------------------------------------------------------------------------
;; Harness
;; ---------------------------------------------------------------------------

(defn- incarnate!
  "Seat a FIRST incarnation under the fixed public id and answer its exact
  token.

  Each `testing` block below is an independent scenario, but the fixture
  is per-`deftest`, so this is where a scenario's preconditions are
  established rather than inherited from the block above it. Two things
  are reset: any live predecessor is destroyed (`commit-basis` is a sum of
  counters, and a leftover frame would carry its installs into the next
  block's arithmetic), and the arm's frame memo is emptied — which is the
  state the arm is genuinely in when a page first mounts, and which
  section 4 depends on being able to distinguish from a warm one.

  React's `act` queue is not the browser's scheduler and none of this runs
  inside one — set outright, as the package smoke does, rather than
  importing the bench tree's helper."
  [who]
  (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
  (when (frame/frame-incarnation-token frame-id) (rf/destroy-frame! frame-id))
  (frames/forget-frame-ops! frame-id)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:reinc/seed who]))
  (frame/frame-incarnation-token frame-id))

(defn- reincarnate!
  "Destroy the live incarnation and seat a fresh one under the same public
  id, seeded with a DIFFERENT value so a read cannot confuse the two."
  [who]
  (rf/destroy-frame! frame-id)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:reinc/seed who]))
  (frame/frame-incarnation-token frame-id))

(defn- marked [] (:marked (rf/app-db-value frame-id)))

(defn- with-refusals
  "Run `thunk` and collect the always-on corpus `:rf.error/frame-destroyed`
  records it fans. Answers `{:result … :refusals [<record>…]}`.

  Axis 1 (`error-emit`) rather than the dev trace deliberately: it
  survives `-Dre-frame.debug=false`, so these assertions hold under the
  production posture too. Gensym'd listener key, unregistered on the way
  out."
  [thunk]
  (let [seen (atom [])
        k    (keyword "rf2-hic-013" (name (gensym "refusal")))]
    (error-emit/register-error-listener!
      k (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! seen conj r))))
    (try {:result (thunk) :refusals @seen}
         (finally (error-emit/unregister-error-listener! k)))))

;; ---------------------------------------------------------------------------
;; 1. The public id is stable; the incarnation is not — and the number the
;;    runtime judges invariant 5 against cannot tell them apart
;; ---------------------------------------------------------------------------

(deftest the-commit-basis-is-blind-to-a-same-id-reincarnation
  (testing "a reincarnation is invisible in the id and visible only in the token"
    (let [token-a (incarnate! "A")
          token-b (reincarnate! "B")]
      (is (some? token-a) "a live incarnation pins a token")
      (is (some? token-b))
      (is (not (identical? token-a token-b))
          "destroy + same-id recreate is a NEW incarnation, distinct by identity")
      (is (false? (frame/frame-incarnation-live? frame-id token-a))
          "and the predecessor's token is no longer live under the id")
      (is (true? (frame/frame-incarnation-live? frame-id token-b)))))

  (testing "`commit-basis` TIES across the transition — the fourth axis
            `re-frame.hicasso.impl.generation/commit-basis` documents as not
            carryable there. The frame's install epoch RESTARTS with the
            successor, and neither of the two terms that namespace owns is a
            frame fact, so a successor holding a DIFFERENT value reports the
            same number its predecessor did"
    (let [_       (incarnate! "A")
          basis-a (generation/commit-basis frame-id)
          _       (reincarnate! "B")
          basis-b (generation/commit-basis frame-id)]
      (is (= "B" (:who (rf/app-db-value frame-id)))
          "sanity: the successor really does hold a different value")
      (is (= basis-a basis-b)
          "the basis cannot see the reincarnation — this is WHY the render
           fence, and React's change detection built on it, pass the
           transition through unnoticed")))

  ;; NEGATIVE CONTROL for the tie. A tie proves nothing if the instrument is
  ;; dead, so an ORDINARY write inside one incarnation must move the very
  ;; number that just failed to move.
  (testing "the basis is a live instrument: an ordinary in-incarnation write moves it"
    (incarnate! "A")
    (let [before (generation/commit-basis frame-id)]
      (rf/with-frame frame-id (rf/dispatch-sync [:reinc/mark :ordinary]))
      (is (> (generation/commit-basis frame-id) before)
          "an ordinary write advances the basis, so the tie above is the
           reincarnation's property and not a stuck counter"))))

;; ---------------------------------------------------------------------------
;; 2. A bundle pinned to the predecessor is inert against the successor
;; ---------------------------------------------------------------------------

(deftest a-bundle-pinned-to-the-dead-incarnation-cannot-write-the-successor
  (doseq [[op invoke] [[:dispatch-sync (fn [h] ((:dispatch-sync h) [:reinc/mark :stale-handle]))]
                       [:dispatch      (fn [h] ((:dispatch h)      [:reinc/mark :stale-handle]))]]]
    (testing (str "a stale " (name op) " is refused, never retargeted")
      (let [_        (incarnate! "A")
            ;; Exactly what a render leaves behind: the memoised bundle
            ;; `re-frame.hicasso.impl.frames/frame-ops` hands every boundary.
            handle-a (frames/frame-ops frame-id)
            _        (reincarnate! "B")
            _        (is (nil? (marked)) "sanity: the successor starts unmarked")
            {:keys [refusals]} (with-refusals #(invoke handle-a))]
        (is (nil? (marked))
            "the successor's app-db is UNTOUCHED by the predecessor's bundle")
        (is (= 1 (count refusals))
            "and the drop is LOUD — exactly one always-on :rf.error/frame-destroyed")
        (is (= frame-id (:frame (first refusals)))
            "attributed to the captured id")
        (is (= :reinc/mark (:event-id (first refusals)))
            "carrying the refused event's head"))))

  (testing "a stale `:subscribe` is refused too — it must not read the
            successor's app-db nor leave a reaction in the successor's
            sub-cache"
    (let [_        (incarnate! "A")
          handle-a (frames/frame-ops frame-id)
          _        (reincarnate! "B")
          {:keys [result refusals]} (with-refusals #((:subscribe handle-a) [:reinc/who]))]
      (is (nil? result) "the recovery is nil, never the successor's value")
      (is (= 1 (count refusals)))
      (is (= :subscribe (:op (first refusals)))))))

;; ---------------------------------------------------------------------------
;; 3. NEGATIVE CONTROL for section 2 — the silence is the PIN's doing
;; ---------------------------------------------------------------------------

(deftest an-unpinned-bundle-does-reach-the-successor
  ;; Section 2 asserts a NON-event: nothing was written. A non-event is the
  ;; easiest assertion in the world to pass for the wrong reason — an
  ;; unregistered handler, a misspelled key, a frame that was never really
  ;; there. This is the same bundle shape, the same op, the same event and the
  ;; same app-db read, differing in ONE fact: the bundle was captured while no
  ;; frame was live under the id, so `capture-frame` pinned nothing and the op
  ;; stays address-directed.
  ;;
  ;; It is also the bead's named sabotage — "removing the incarnation check
  ;; must make the revived-handle case red" — performed through the documented
  ;; seam rather than by redefining a substrate var.
  (testing "an UNPINNED capture, address-directed by construction, writes the
            successor — so section 2's silence is the incarnation pin and
            nothing else"
    (let [unpinned (rf/capture-frame frame-id)]      ; captured with no live frame
      (incarnate! "A")
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals
                                 #((:dispatch-sync unpinned) [:reinc/mark :unpinned]))]
        (is (= :unpinned (marked))
            "the write LANDS — the identical assertion in section 2 is therefore
             capable of failing, and passes there because of the pin")
        (is (empty? refusals) "and nothing is refused, because nothing was pinned")))))

;; ---------------------------------------------------------------------------
;; 4. DEFECT — a lowered callback's destination is decided when it FIRES
;; ---------------------------------------------------------------------------

;; **This test records a defect and is expected to FAIL when it is fixed.**
;;
;; `re-frame.hicasso.impl.intent` captures the ambient dispatch at LOWERING
;; time and the browser invokes it long after the render's dynamic extent
;; has unwound. That closure is
;; [[re-frame.hicasso.impl.collector/frame-dispatch]]'s, and it closes over
;; the frame KEYWORD — so `dispatch!` resolves
;; [[re-frame.hicasso.impl.frames/frame-ops]] when the user CLICKS, not
;; when the body rendered. The closure carries no incarnation.
;;
;; What it reaches is therefore whatever the memo happens to hold at that
;; instant, and the memo is filled lazily by the first dispatch and evicted
;; by nothing on a frame destroy. The three branches below are the three
;; states that produces, and only the first looks like the law:
;;
;;   warm    — a dispatch happened under the predecessor, so the memo still
;;             holds its pinned bundle: the click is REFUSED. Correct, and
;;             correct by accident.
;;   cold    — the boundary rendered and lowered its callback but nobody
;;             clicked before the teardown, so the memo is empty: the click
;;             captures a bundle pinned to the SUCCESSOR and writes it,
;;             silently. This is the revival the bead forbids, and it needs
;;             no unusual sequence — a first click after a reincarnation is
;;             an ordinary thing for a user to do.
;;   evicted — the memo was warm and then cleared, which is what *exact
;;             eviction on destruction* (one of the two remedies the bead
;;             names) would do on every destroy: the same silent revival.
;;
;; So eviction alone is not the remedy — it converts the one correct branch
;; into the broken one. The incarnation has to be pinned into what lowering
;; captures, which is the bead's other named option (incarnation-keyed
;; operation bundles) and a change to
;; `implementation/hicasso/src/re_frame/hicasso/impl/frames.cljs`, outside
;; this bead's declared surface.
;;
;; **When the remedy lands**, delete this test and assert the contract in
;; its place: all three branches leave `(marked)` nil and fan exactly one
;; `:rf.error/frame-destroyed`.
(deftest DEFECT-a-retained-callback-can-revive-the-successor
  (testing "WARM memo — refused, which is the only branch that looks correct"
    (let [_        (incarnate! "A")
          on-click (collector/frame-dispatch frame-id)]
      (collector/dispatch! frame-id [:reinc/mark :under-a])   ; warms !frame-ops
      (is (contains? @frames/!frame-ops frame-id) "the memo is warm")
      (reincarnate! "B")
      (is (identical? (frames/frame-ops frame-id) (frames/frame-ops frame-id))
          "and survives the destroy — nothing evicts it")
      (let [{:keys [refusals]} (with-refusals #(on-click [:reinc/mark :warm]))]
        (is (nil? (marked)) "the retained click is refused")
        (is (= 1 (count refusals)) "loudly"))))

  (testing "COLD memo — the SAME retained closure writes the successor, silently.
            The defect: nothing about the callback changed, only whether some
            earlier click had happened to fill the memo"
    (let [_        (incarnate! "A")
          on-click (collector/frame-dispatch frame-id)]
      (is (not (contains? @frames/!frame-ops frame-id))
          "no dispatch has happened under the predecessor, so the memo is cold")
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals #(on-click [:reinc/mark :cold]))]
        (is (= :cold (marked))
            "REVIVAL — a predecessor-era callback wrote the successor's app-db")
        (is (empty? refusals)
            "and nothing was emitted, which is the worse half: the write is
             indistinguishable from a legitimate one"))))

  (testing "EVICTED memo — what eviction-on-destruction would produce on every
            destroy, and the reason it is refused as a remedy on its own"
    (let [_        (incarnate! "A")
          on-click (collector/frame-dispatch frame-id)]
      (collector/dispatch! frame-id [:reinc/mark :under-a])
      (reincarnate! "B")
      (frames/forget-frame-ops! frame-id)
      (let [{:keys [refusals]} (with-refusals #(on-click [:reinc/mark :evicted]))]
        (is (= :evicted (marked))
            "the warm branch's refusal was the STALE MEMO's doing; evict it and
             the same click revives")
        (is (empty? refusals))))))
