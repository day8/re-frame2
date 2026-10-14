(ns re-frame.fresco.reincarnation-routing-cljs-test
  "SAME-PUBLIC-ID FRAME REINCARNATION — where a delayed operation lands.

  A frame is destroyed and a new one created under the SAME public keyword
  id. Every handle, bundle and lowered callback the arm minted against the
  first incarnation is still reachable from application code: a
  `setTimeout`, a promise continuation, a WebSocket `onmessage`, a
  predecessor React root's deferred effect cleanup. The law is that none
  of them may write into the successor.
  `re-frame.fresco.impl.frames` names this as its reason for existing,
  and `re-frame.fresco.impl.generation/commit-basis` carries the
  reincarnation in its retired-epoch term.

  ## Why these observables, and not the rendered markup

  **The DOM is the wrong instrument for this fault, and measurably so.**
  The arm's READ path is address-directed — a cell's key is
  `[frame-kw query-v]` on the PUBLIC id, and `subs/subscribe` resolves
  that id when it is called — so a boundary re-rendered after the
  reincarnation reads the SUCCESSOR's value and paints it correctly. The
  WRITE path resolves through the memoised `rf/capture-frame` bundle in
  [[re-frame.fresco.impl.frames/!frame-ops]], which is pinned to one
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

  ## Section 4 is the lowered callback's contract

  The DEFECT this suite is built around is LATE BINDING: an ambient dispatch
  that closes over the frame KEYWORD and resolves its bundle when the callback
  FIRES lands wherever the memo's warmth decides at that instant. The
  incarnation is pinned into the closure at mint time instead, and section 4
  is the positive contract — safety (a retained callback refuses) and
  liveness (a fresh one routes) asserted together in every cache posture,
  because cache warmth is precisely what chooses between two different failures
  under late binding.
  See `implementation/fresco/spec/invariants.md` §7.

  ## Companion

  `reincarnation_cells_cljs_test` takes the same transition on the
  committed side: the held cell, React's own change-detection number, and
  the repair deferred to the microtask checkpoint.
  `reincarnation_paint_dom_cljs_test` takes it in a real browser, where
  the ordering of that repair against paint is what is at stake."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.error-emit :as rf.error-emit]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.impl.frames :as rf.fresco.impl.frames]
            [re-frame.fresco.impl.generation :as rf.fresco.impl.generation]
            [re-frame.test-support :as rf.test-support]))

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
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn []
                      (rf.fresco.impl.collector/reset-runtime!)
                      (rf.error-emit/clear-error-listeners!))}))

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
  (when (rf.frame/frame-incarnation-token frame-id) (rf/destroy-frame! frame-id))
  (rf.fresco.impl.frames/forget-frame-ops! frame-id)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:reinc/seed who]))
  (rf.frame/frame-incarnation-token frame-id))

(defn- reincarnate!
  "Destroy the live incarnation and seat a fresh one under the same public
  id, seeded with a DIFFERENT value so a read cannot confuse the two."
  [who]
  (rf/destroy-frame! frame-id)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:reinc/seed who]))
  (rf.frame/frame-incarnation-token frame-id))

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
    (rf.error-emit/register-error-listener!
      k (fn [r] (when (= :rf.error/frame-destroyed (:error r)) (swap! seen conj r))))
    (try {:result (thunk) :refusals @seen}
         (finally (rf.error-emit/unregister-error-listener! k)))))

;; ---------------------------------------------------------------------------
;; 1. The public id is stable; the incarnation is not — and the number the
;;    runtime judges invariant 5 against moves across the transition
;; ---------------------------------------------------------------------------

(deftest the-commit-basis-moves-across-a-same-id-reincarnation
  (testing "`commit-basis` MOVES across the transition even though the
            frame's install epoch RESTARTS with the successor and here ties
            the predecessor's: destroying the predecessor retired its epoch
            into the basis, so the successor's number strictly exceeds it"
    (let [_       (incarnate! "A")
          epoch-a (rf.frame/frame-commit-epoch frame-id)
          basis-a (rf.fresco.impl.generation/commit-basis frame-id)
          _       (reincarnate! "B")
          epoch-b (rf.frame/frame-commit-epoch frame-id)
          basis-b (rf.fresco.impl.generation/commit-basis frame-id)]
      (is (= "B" (:who (rf/app-db-value frame-id)))
          "sanity: the successor really does hold a different value")
      (is (= epoch-a epoch-b)
          "precondition: the install epochs tie, so that term alone cannot see it")
      (is (> basis-b basis-a)
          "the basis sees the reincarnation, so a staged read rendered under
           the predecessor and committed under the successor re-renders"))))

;; ---------------------------------------------------------------------------
;; 2. A bundle pinned to the predecessor is inert against the successor
;; ---------------------------------------------------------------------------

(deftest a-bundle-pinned-to-the-dead-incarnation-cannot-write-the-successor
  (doseq [[op invoke] [[:dispatch-sync (fn [h] ((:dispatch-sync h) [:reinc/mark :stale-handle]))]
                       [:dispatch      (fn [h] ((:dispatch h)      [:reinc/mark :stale-handle]))]]]
    (testing (str "a stale " (name op) " is refused, never retargeted")
      (let [_        (incarnate! "A")
            ;; Exactly what a render leaves behind: the memoised bundle in the
            ;; arm's one frame row, which every boundary of that incarnation
            ;; shares.
            handle-a (:ops (rf.fresco.impl.collector/frame-row frame-id))
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
          handle-a (:ops (rf.fresco.impl.collector/frame-row frame-id))
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
  ;; It is also the sabotage that shows removing the incarnation check makes
  ;; the revived-handle case red, performed through the documented seam rather
  ;; than by redefining a substrate var.
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
;; 4. THE CONTRACT — a lowered callback's destination is decided when it is
;;    MINTED, in every state the memo can be in when it fires
;; ---------------------------------------------------------------------------

;; A callback that closed over the frame KEYWORD and resolved its bundle at
;; FIRE time needs three branches to describe, because the memo's state at that
;; instant chooses which of two different wrong answers you get. Pinning the
;; incarnation into the closure at mint time takes the memo's state out of the
;; inputs — and the way to say that is to assert the SAME contract in all
;; three states rather than to assert it once.
;;
;; The two halves are asserted together on purpose. Under late binding, cache
;; warmth trades them off: a warm memo refuses the retained callback (safe) AND
;; refuses the live successor's fresh one (dead controls); a cold or evicted
;; memo routes the fresh one AND silently revives the successor through the
;; retained one. A suite asserting only the refusals would be green on a
;; runtime where nothing dispatches at all.

(defn- render-dispatch
  "The ambient dispatch a boundary acquires during its render — exactly what
  `impl.collector/run-once` binds for a body's dynamic extent, and therefore
  exactly what every callback that body lowers retains."
  []
  (rf.fresco.impl.collector/frame-dispatch frame-id))

(def ^:private postures
  "The three states the arm's one frame row can be in when a retained callback
  fires, established AFTER the successor has seated. Under late binding
  (section 8) each gives a different wrong answer, which is why all three
  are asserted:

    :cold   nothing has touched the row since the successor seated, so it still
            describes the PREDECESSOR. Under late binding this is the single
            accidentally-correct branch — the refusal is the stale memo's
            doing rather than any check — and the branch that
            eviction-on-destruction would destroy.
    :warm   the successor has rendered, so the row describes the SUCCESSOR.
            Under late binding a retained keyword-closure reads this row and
            writes B.
    :reset  the row was dropped outright. Under late binding the fire-time
            `capture-frame` pins B and writes it with nothing emitted, which
            is the ordinary case: a boundary that rendered but that nobody
            clicked before the teardown."
  [[:cold  (fn [])]
   [:warm  (fn [] (rf.fresco.impl.collector/frame-dispatch frame-id))]
   [:reset (fn [] (rf.fresco.impl.frames/forget-frame-ops! frame-id))]])

(deftest a-retained-callback-is-pinned-to-the-incarnation-that-lowered-it
  (doseq [[posture establish!] postures]
    (testing (str "SAFETY, " (name posture)
                  " — a callback minted under A refuses once A is destroyed")
      (let [_        (incarnate! "A")
            on-click (render-dispatch)]
        (reincarnate! "B")
        (establish!)
        (let [{:keys [refusals]} (with-refusals #(on-click [:reinc/mark posture]))]
          (is (nil? (marked))
              "the successor's app-db is UNTOUCHED by a predecessor-era callback")
          (is (= 1 (count refusals))
              "and the drop is LOUD — exactly one always-on
               :rf.error/frame-destroyed, the only observable separating
               refused from silently delivered to the wrong frame")
          (is (= frame-id (:frame (first refusals)))
              "attributed to the captured id")
          (is (= :reinc/mark (:event-id (first refusals)))
              "carrying the refused event's head"))))

    (testing (str "LIVENESS, " (name posture)
                  " — a callback minted under B dispatches, with no refusal")
      (let [_ (incarnate! "A")
            ;; A render under the PREDECESSOR, so the row genuinely describes A
            ;; when the successor seats. Without it the `:cold` posture would be
            ;; an empty table rather than a stale row, and the stale row is the
            ;; case where late binding leaves the successor's own controls dead.
            _        (render-dispatch)
            _        (reincarnate! "B")
            _        (establish!)
            on-click (render-dispatch)
            {:keys [refusals]} (with-refusals #(on-click [:reinc/mark posture]))]
        (is (= posture (marked))
            "the live incarnation's own control WRITES its own app-db — the
             half late binding reddens whenever the memo is warm")
        (is (empty? refusals)
            "and nothing is refused, because the closure was minted against
             the incarnation it is dispatching into")))))
