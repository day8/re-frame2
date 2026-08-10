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

  ## Sections 4–8 are the lowered callback's contract (rf2-x874)

  Section 4 recorded a DEFECT when this suite landed: the ambient dispatch a
  boundary lowered into its callbacks closed over the frame KEYWORD and
  resolved its bundle when the callback FIRED, so where a delayed callback
  landed was decided by the memo's warmth at that instant. rf2-x874 pinned the
  incarnation into the closure at mint time, and the sections below are the
  positive contract that replaced the recording — safety (a retained callback
  refuses) and liveness (a fresh one routes) asserted together in every cache
  posture, because cache warmth is precisely what used to choose between two
  different failures. Section 8 is the negative control: it rebuilds the old
  late-binding mechanism out of the documented seam and reproduces BOTH
  failures, so nothing here can be passing because the instruments are dead.
  See `docs/design/hicasso/product/invariants.md` §7.

  ## Companion

  `reincarnation_cells_cljs_test` takes the same transition on the
  committed side: the held cell, React's own change-detection number, and
  the repair deferred to the microtask checkpoint.
  `reincarnation_paint_dom_cljs_test` takes it in a real browser, where
  the ordering of that repair against paint is what is at stake."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.error-emit :as error-emit]
            [re-frame.frame :as frame]
            [re-frame.hicasso.impl.collector :as collector]
            [re-frame.hicasso.impl.frames :as frames]
            [re-frame.hicasso.impl.generation :as generation]
            [re-frame.hicasso.impl.intent :as intent]
            [re-frame.hicasso.impl.inventory :as inventory]
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
            ;; Exactly what a render leaves behind: the memoised bundle in the
            ;; arm's one frame row, which every boundary of that incarnation
            ;; shares.
            handle-a (:ops (collector/frame-row frame-id))
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
          handle-a (:ops (collector/frame-row frame-id))
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
;; 4. THE CONTRACT — a lowered callback's destination is decided when it is
;;    MINTED, in every state the memo can be in when it fires (rf2-x874)
;; ---------------------------------------------------------------------------

;; This section replaces the DEFECT recording PR #7749 shipped here. That
;; recording measured a callback that closed over the frame KEYWORD and
;; resolved its bundle at FIRE time, and it had three branches because the
;; memo's state at that instant chose which of two different wrong answers you
;; got. The repair pins the incarnation into the closure at mint time, so the
;; memo's state stops being an input — and the way to say that is to assert the
;; SAME contract in all three states rather than to assert it once.
;;
;; The two halves are asserted together on purpose. Before the repair, cache
;; warmth traded them off: a warm memo refused the retained callback (safe) AND
;; refused the live successor's fresh one (dead controls); a cold or evicted
;; memo routed the fresh one AND silently revived the successor through the
;; retained one. A suite asserting only the refusals would be green on a
;; runtime where nothing dispatches at all.

(defn- render-dispatch
  "The ambient dispatch a boundary acquires during its render — exactly what
  `impl.collector/run-once` binds for a body's dynamic extent, and therefore
  exactly what every callback that body lowers retains."
  []
  (collector/frame-dispatch frame-id))

(def ^:private postures
  "The three states the arm's one frame row can be in when a retained callback
  fires, established AFTER the successor has seated. Each was a different wrong
  answer before rf2-x874, which is why all three are asserted:

    :cold   nothing has touched the row since the successor seated, so it still
            describes the PREDECESSOR. Pre-fix this was the single
            accidentally-correct branch — the refusal was the stale memo's
            doing rather than any check — and the branch that
            eviction-on-destruction would have destroyed.
    :warm   the successor has rendered, so the row describes the SUCCESSOR.
            Pre-fix a retained keyword-closure read this row and wrote B.
    :reset  the row was dropped outright. Pre-fix the fire-time
            `capture-frame` pinned B and wrote it with nothing emitted, which
            is the ordinary case: a boundary that rendered but that nobody
            clicked before the teardown."
  [[:cold  (fn [])]
   [:warm  (fn [] (collector/frame-dispatch frame-id))]
   [:reset (fn [] (frames/forget-frame-ops! frame-id))]])

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
            ;; case that used to leave the successor's own controls dead.
            _        (render-dispatch)
            _        (reincarnate! "B")
            _        (establish!)
            on-click (render-dispatch)
            {:keys [refusals]} (with-refusals #(on-click [:reinc/mark posture]))]
        (is (= posture (marked))
            "the live incarnation's own control WRITES its own app-db — the
             half that was red on main whenever the memo was warm")
        (is (empty? refusals)
            "and nothing is refused, because the closure was minted against
             the incarnation it is dispatching into")))))

;; ---------------------------------------------------------------------------
;; 5. One handler identity per incarnation — the property the perf budget and
;;    the fix both rest on
;; ---------------------------------------------------------------------------

(deftest the-ambient-dispatch-has-one-identity-per-live-incarnation
  (let [_     (incarnate! "A")
        a1    (render-dispatch)
        a2    (render-dispatch)
        ops-a (:ops (collector/frame-row frame-id))
        _     (reincarnate! "B")
        b1    (render-dispatch)
        b2    (render-dispatch)
        ops-b (:ops (collector/frame-row frame-id))]
    (is (identical? a1 a2)
        "repeated renders of ONE incarnation share one handler — the memo is
         still a memo, and a boundary re-render allocates nothing")
    (is (not (identical? a1 b1))
        "the same public id naming a NEW incarnation hands back a new one, and
         this lazy replacement is what makes a destruction hook unnecessary")
    (is (identical? b1 b2)
        "and it is stable again under the successor")
    (is (not (identical? ops-a ops-b))
        "the captured bundle underneath moved with it — the row is ONE record,
         so the closure and the bundle cannot describe different incarnations")
    (is (true? (frame/frame-incarnation-live?
                 frame-id (:incarnation (collector/frame-row frame-id))))
        "and the row answering now is pinned to the incarnation live now")))

;; ---------------------------------------------------------------------------
;; 6. Every lowering path inherits the same pinned ambient dispatch
;; ---------------------------------------------------------------------------

;; Four spellings reach the ambient closure by four different routes through
;; `impl.intent`, and all four capture `*dispatch*` at LOWERING time. One table
;; rather than four tests, because they share the mechanism and the question
;; asked of each is identical — if they ever stop sharing it, the table is
;; where that shows up.
(def ^:private lowering-paths
  [[:intent-vector
    (fn [tag] (intent/lower-prop :on-click [:reinc/mark tag]))]
   [:h-fn-returning-an-intent
    (fn [tag] (intent/lower-prop :on-click (intent/callback (fn [_e] [:reinc/mark tag]))))]
   [:key-map-branch
    (fn [tag] (intent/lower-prop :on-key-down {"Enter" [:reinc/mark tag]}))]
   [:handler-inside-a-render-callback
    ;; The render callback is lowered under the boundary, then INVOKED — which
    ;; is when its inner handler is lowered, against the gate — and the handler
    ;; it returned is what fires later. The gate forwards to the SUPPLYING
    ;; boundary's dispatch once the call has returned, so this path inherits
    ;; the pin one level deeper than the other three.
    (fn [tag]
      ((intent/lower-prop :row-render
                          (intent/callback
                            (fn [] (intent/lower-prop :on-click [:reinc/mark tag]))))))]])

(defn- lower-under-boundary
  "Lower through `f` inside the render-time ambient binding a boundary body
  runs under — `impl.collector/run-once`'s, spelled out."
  [f]
  (intent/with-frame frame-id (render-dispatch) f))

(defn- fire!
  "Invoke a lowered handler the way its position's invoker would. One event
  object serves all four: the key-map branch reads `.key`, and the other three
  ignore the argument."
  [h]
  (h #js {:key "Enter"}))

(deftest every-lowering-path-inherits-the-pinned-ambient-dispatch
  (doseq [[path lower] lowering-paths]
    (testing (str "SAFETY — " (name path) " lowered under A refuses once A dies")
      (incarnate! "A")
      (let [h (lower-under-boundary #(lower :stale))]
        (reincarnate! "B")
        (let [{:keys [refusals]} (with-refusals #(fire! h))]
          (is (nil? (marked))
              (str (name path) " reached the successor's app-db"))
          (is (= 1 (count refusals))
              (str (name path) " did not fan exactly one refusal"))
          (is (= :reinc/mark (:event-id (first refusals)))))))

    (testing (str "LIVENESS — " (name path) " lowered under B dispatches")
      (incarnate! "A")
      (lower-under-boundary #(lower :under-a))
      (reincarnate! "B")
      (let [h (lower-under-boundary #(lower :live))
            {:keys [refusals]} (with-refusals #(fire! h))]
        (is (= :live (marked))
            (str (name path) " did not reach the LIVE incarnation"))
        (is (empty? refusals))))))

;; ---------------------------------------------------------------------------
;; 7. Reset still empties the inventory
;; ---------------------------------------------------------------------------

(deftest reset-empties-the-frame-memo
  (incarnate! "A")
  (render-dispatch)
  (is (contains? @frames/!frame-ops frame-id)
      "a render leaves exactly one row behind — the bundle and the ambient
       dispatch are one record now, so acquiring the dispatch acquires both")
  (is (= 1 (:frames (inventory/stats)))
      "which is what the residue census counts under its `:frame-ops` token")
  (collector/reset-runtime!)
  (is (= {} @frames/!frame-ops) "and the whole-runtime reset empties it")
  (is (= 0 (:frames (inventory/stats)))))

;; ---------------------------------------------------------------------------
;; 8. NEGATIVE CONTROL — restore late keyword resolution and BOTH failures
;;    come back
;; ---------------------------------------------------------------------------

;; A control that showed only a stale callback going inert would be incomplete:
;; a cache in which nothing resolves at all would pass it too. So this one
;; asserts a positive WRITE in one branch and a positive REFUSAL in the other —
;; the two failures the defect actually had — and then runs the same two
;; scenarios through the runtime under test, which must answer the opposite way
;; in both.

(defn- late-binding-dispatch
  "**The mechanism as it stood before rf2-x874**, rebuilt out of the documented
  seam rather than by redefining a runtime var.

  `!memo` stands in for the arm's frame table as it then was, and the returned
  closure for `impl.collector/frame-dispatch`'s: it closes over the frame
  KEYWORD and resolves a `rf/capture-frame` bundle when it FIRES. The commit
  window is the arm's real [[re-frame.hicasso.impl.collector/with-commit]] — it
  batches notifications and has no say in where a write lands, but using the
  real door keeps this a reconstruction rather than a paraphrase."
  [!memo]
  (fn late-frame-dispatch [frame-kw]
    (fn late-dispatch-for-frame [event]
      (collector/with-commit
        (fn []
          (let [ops (or (get @!memo frame-kw)
                        (let [captured (rf/capture-frame frame-kw)]
                          (swap! !memo assoc frame-kw captured)
                          captured))]
            ((:dispatch-sync ops) event)))))))

(deftest NEGATIVE-CONTROL-late-keyword-resolution-reproduces-both-failures
  (testing "COLD memo — a retained PREDECESSOR callback silently writes the
            successor, which is the revival the contract forbids"
    (incarnate! "A")
    (let [!memo    (atom {})
          on-click ((late-binding-dispatch !memo) frame-id)]
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals #(on-click [:reinc/mark :late-cold]))]
        (is (= :late-cold (marked))
            "the predecessor's callback WROTE the successor's app-db")
        (is (empty? refusals)
            "and nothing was emitted, which is the worse half — the write is
             indistinguishable from a legitimate one"))))

  (testing "WARM memo — a callback minted AFTER the successor seated is refused
            by the stale bundle: perfect markup above a dead control"
    (incarnate! "A")
    (let [!memo (atom {})
          late  (late-binding-dispatch !memo)]
      ((late frame-id) [:reinc/mark :under-a])       ; fills the memo with A's bundle
      (reincarnate! "B")
      (let [on-click (late frame-id)                 ; minted AFTER B seated
            {:keys [refusals]} (with-refusals #(on-click [:reinc/mark :late-warm]))]
        (is (nil? (marked))
            "the LIVE incarnation's own control does not write its own app-db")
        (is (= 1 (count refusals))
            "it is refused — the liveness half of the same defect"))))

  (testing "and the runtime under test answers the OPPOSITE way in both, which
            is what makes the two branches above a control on the FIX rather
            than two more measurements of a dead cache"
    (incarnate! "A")
    (let [retained (render-dispatch)]
      (reincarnate! "B")
      (let [{:keys [refusals]} (with-refusals #(retained [:reinc/mark :repaired-cold]))]
        (is (nil? (marked)) "the silent write is REFUSED here")
        (is (= 1 (count refusals)) "loudly")))

    (incarnate! "A")
    (render-dispatch)
    (reincarnate! "B")
    (let [fresh (render-dispatch)
          {:keys [refusals]} (with-refusals #(fresh [:reinc/mark :repaired-warm]))]
      (is (= :repaired-warm (marked)) "and the dead control ROUTES here")
      (is (empty? refusals)))))
