(ns re-frame.bench.hicasso.arm1.staged-read-tear-cljs-test
  "A STAGED READ THAT MOVES BEFORE THE COMMIT (rf2-2rtt6.42).

  `generation_fence_coverage_cljs_test` settled what the generation
  fence covers and found the hole; this file is the hole closed, over
  Arm 1's OWN runtime rather than a transcription of it.

  ## The defect these rows are about

  Two windows, and the arm only ever guarded one of them:

      predecessor   render probes a site … COMMIT re-reads that site
                    |<------- the render->commit gap ------->|

      the fence     capture … BODY RUNS … compare … RETURN
                    |<---- one body run ---->|

  `render-body` compares and returns; the commit that follows — React
  calling the read-set entry's `subscribe`, where `acquire-cell!`
  installs the edge and the watch — compares nothing, by design (`no
  commit-phase deref`). What reached the gap instead was the epoch-sum
  `getSnapshot` plus React's own post-`subscribe` re-check, and that is
  real — but it was driven by ONE counter. An epoch moved only when
  `flush!` bumped it, `flush!` ran only from `mark-dirty!`, and
  `mark-dirty!` has exactly one caller: the value-change watch
  `acquire-cell!` installs AT COMMIT. So for a key nothing holds yet —
  no cell, no watch, no epoch — a move in the gap marked nothing, bumped
  nothing, and left `getSnapshot` answering the same number before and
  after the commit. The boundary painted the old value and, because the
  watch's baseline was already the new one, **no later notification was
  coming for a change that had already happened**. Spec 006 invariant
  5's stronger half, executed: *a staged site publishes stale and
  nothing ever corrects it.*

  A baseline deref at acquire — which this arm already performs, and
  which is the right fix for a DIFFERENT problem (a fresh reaction whose
  `unset` baseline is never `rf=` a real value reports movement on the
  first later commit whatever it did) — cannot close this one. It gives
  the cell a correct baseline and no COMPARISON: it silently adopts the
  moved value as though it had always been that. Row `the-baseline-deref-
  alone-cannot-see-the-move` states that as an assertion rather than as
  a paragraph.

  ## What closes it

  One term, in the one place React already looks. A key with no cell
  contributes its frame's `commit-basis` to `getSnapshot` instead of
  nothing, and a cell records the basis it was CREATED at:

      commit-basis(frame) = the runtime's flush generation
                          + `re-frame.frame/frame-commit-epoch`
                          + the runtime's registry epoch

  The second term is the substrate's own observation-port evidence
  counter — one bump per physical frame-state install, at both write
  chokepoints — and Spec 006 already uses it to answer exactly this
  question without watching anything. The third counts `:sub`
  registrations, which are neither a flush nor an install (rf2-2rtt6.50);
  this file's rows are the version axis and do not exercise it. So a
  staged key's number is `basis@render` while the boundary renders and
  `basis@commit` once the commit acquires it: equal when nothing moved in
  the gap, different when something did. React stores each fiber's render-time snapshot and
  re-reads `getSnapshot` immediately after `subscribe` returns
  (`updateStoreInstance` is the very next passive effect), so the
  comparison is per boundary, is one number, and holds **no record of
  what any read returned**.

  ## What these rows are, and what they are not

  They are the seam React occupies, driven by hand: `render-body` is the
  render, `snapshot-of` is `useSyncExternalStore`'s capture and React's
  `checkIfSnapshotChanged`, `commit-boundary!` is `subscribe`. Nothing
  here mocks the sub layer, the frames or the watches — the adapter is
  the React spine's, because under `plain-atom` a subscription never
  notifies and every one of these assertions would pass by never firing.
  `arm1/generation_fence_dom_cljs_test`'s staged row then proves REACT
  drives this seam, in a real browser, against the real DOM.

  Two rows fail on the pre-fix runtime and pass on it —
  `a-staged-read-that-moves-in-the-gap-is-corrected` and
  `the-fence-sees-a-mid-body-move-of-a-key-nothing-holds` — and the
  remaining rows are what stops the repair from being \"re-render
  always\": a clean mount must ask React for nothing."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.frame :as frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     :init-fn (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-staged-tear)

(defn- seeded! []
  (rt/reset-runtime!)
  (dogfood/make-frame! frame-id 3)
  frame-id)

(defn- render
  "One render pass for `body-fn`, through the shell's own fence. Returns
  the read-set entry — the object React's `subscribe` and `getSnapshot`
  hang off."
  [body-fn]
  (rt/render-body frame-id body-fn {})
  (rt/last-reads))

(defn- done-row
  "A boundary reading one row's done? flag, and recording what it read.
  The read is the whole body, so the boundary's read set is one key and
  the arithmetic below is one term."
  [seen]
  (fn [_] (let [v (rt/sub [:dogfood/done? 0])] (vreset! seen v) [:li (str v)])))

;; ---------------------------------------------------------------------------
;; The tear, and its repair
;; ---------------------------------------------------------------------------

(deftest a-staged-read-that-moves-in-the-gap-is-corrected
  (testing "hd-002-adjudication.md §6.1's adversarial witness, executed
           against Arm 1's own runtime. The value moves after the body
           returned and before React's commit acquires the key, and it
           moves WITHIN ONE GENERATION — because for a key nothing holds
           the generation cannot move. The number React re-checks must
           move anyway, or the boundary paints stale forever."
    (seeded!)
    (let [seen  (volatile! nil)
          entry (render (done-row seen))]
      (is (false? @seen) "the render read, and this is what it put on screen")
      (is (zero? (:cells (rt/stats)))
          "STAGED, not retained: nothing holds this key, so there is no
           cell, no watch and no epoch — `commit-boundary!` below is the
           first acquisition")
      (let [at-render  (rt/snapshot-of entry)
            generation (rt/generation)
            frame-e    (frame/frame-commit-epoch frame-id)]
        ;; THE GAP.
        (rt/dispatch! frame-id [:dogfood/toggle 0])
        (is (= generation (rt/generation))
            "the generation did NOT move — there was no watch to fire, so
             the change happened within one generation, which is precisely
             the witness §6.1 asks for")
        (is (> (frame/frame-commit-epoch frame-id) frame-e)
            "but the frame's own physical-install epoch did, and it moved
             without anybody watching anything")
        ;; THE COMMIT: React calls the entry's `subscribe`.
        (let [release! (rt/commit-boundary! entry (fn []))]
          (is (not= at-render (rt/snapshot-of entry))
              "so the number React stored at render is not the number it
               re-reads after `subscribe` — `updateStoreInstance` finds a
               moved store and schedules the boundary")
          (testing "and the re-render React schedules reads the value that
                   is now true, so the correction actually corrects"
            (render (done-row seen))
            (is (true? @seen)))
          (release!))))))

(deftest the-baseline-deref-alone-cannot-see-the-move
  (testing "why the arm's existing acquire-time baseline deref is not the
           fix. It gives a fresh cell a correct baseline — which is worth
           having, and is what stops every newly mounted boundary
           re-rendering once for nothing — but it performs no COMPARISON,
           so on its own it adopts the moved value as though it had
           always been that. The proof is that after the commit the dirty
           set is empty: no notification is coming for a change that
           already happened, and the only thing left that can correct the
           boundary is the number React re-checks."
    (seeded!)
    (let [seen  (volatile! nil)
          entry (render (done-row seen))]
      (rt/dispatch! frame-id [:dogfood/toggle 0])
      (let [hits     (volatile! 0)
            release! (rt/commit-boundary! entry (fn [] (vswap! hits inc)))]
        (is (= 0 @hits)
            "the commit notified nobody — the watch was armed one move too
             late to have reported it")
        (rt/dispatch! frame-id [:dogfood/commit 0])
        (is (= 0 @hits)
            "and a later write that moves nothing brings no correction
             either: the ordinary invalidation path is done with this
             change")
        (release!)))))

(deftest a-clean-mount-asks-react-for-nothing
  (testing "the half that stops the repair from being `re-render always`.
           A staged key whose value did NOT move in the gap must leave
           the snapshot exactly where the render left it, or every mount
           in the application pays a second render."
    (seeded!)
    (let [entry     (render (done-row (volatile! nil)))
          at-render (rt/snapshot-of entry)
          release!  (rt/commit-boundary! entry (fn []))]
      (is (= at-render (rt/snapshot-of entry))
          "acquisition alone moves nothing: the cell is born at the same
           basis the staged term reported")
      (release!))))

(deftest a-retained-key-moving-in-the-gap-is-still-corrected
  (testing "the path that already worked, kept working. A key some
           earlier boundary committed has a cell whose watch PRE-DATES
           the move, so the ordinary invalidation path reaches it. The
           repair must not disturb that — and the epoch it stamps is now
           a `commit-basis` reading rather than a private count, so this
           row is also the check that re-stamping still strictly
           increases."
    (seeded!)
    (let [warm  (render (done-row (volatile! nil)))
          hold! (rt/commit-boundary! warm (fn []))]
      (is (= 1 (:cells (rt/stats))) "RETAINED: an earlier commit holds the key")
      (let [entry      (render (done-row (volatile! nil)))
            at-render  (rt/snapshot-of entry)
            generation (rt/generation)]
        (rt/dispatch! frame-id [:dogfood/toggle 0])
        (is (= (inc generation) (rt/generation))
            "the pre-existing watch fired, so the generation moved here")
        (is (not= at-render (rt/snapshot-of entry))
            "and the epoch sum moved with it")
        (hold!)))))

;; ---------------------------------------------------------------------------
;; The same blind spot, inside the fence's own window
;; ---------------------------------------------------------------------------

(deftest the-fence-sees-a-mid-body-move-of-a-key-nothing-holds
  (testing "the sibling hole, and the reason the fence compares the basis
           rather than the generation alone. A body reads a key nothing
           holds, writes, and reads again. No cell means no watch means
           no `mark-dirty!` means no generation bump — so a fence
           comparing the generation saw one still number across a body
           whose two reads straddled two commits. The frame's install
           epoch moves for that write, so the basis does, and the body
           re-runs against the newer commit."
    (seeded!)
    (let [runs   (volatile! 0)
          first- (volatile! nil)
          then-  (volatile! nil)]
      (render (fn [_]
                (vswap! runs inc)
                (vreset! first- (rt/sub [:dogfood/done? 0]))
                (when (= 1 @runs)
                  (rt/dispatch! frame-id [:dogfood/toggle 0]))
                (vreset! then- (rt/sub [:dogfood/done? 0]))
                [:li (str @then-)]))
      (is (= 2 @runs) "the fence re-ran the body against the newer commit")
      (is (= @first- @then-)
          "so the winning run's two reads are on ONE commit — the DOM never
           carries a pair of values that were not simultaneously true")
      (is (true? @then-) "and the winning run read the committed value"))))

;; ---------------------------------------------------------------------------
;; The cost of the repair, stated as assertions
;; ---------------------------------------------------------------------------

(deftest the-repair-costs-no-hook-no-object-and-no-record-of-a-read
  (testing "the tripwire, checked rather than claimed. Closing the gap
           added one integer term to a number that was already being
           summed — no second scratch, nothing keyed by a render or an
           attempt, no per-read object, and no commit-phase deref of a
           subscription value."
    (is (= 2 (count rt/shell-hook-ledger))
        "still two hooks; the repair rides `useSyncExternalStore`'s own
         snapshot re-check rather than buying a third")
    (let [inv (rt/retained-inventory)]
      (is (= #{:use-ref :use-state :view-cell :candidate-ledger}
             (into #{} (map :token) (:absent inv)))
          "the enumerated absences are unchanged")))
  (testing "and a render still mutates neither the index nor a reference,
           which is the clause the repair had to stay inside: the staged
           term is READ from the frame, never recorded by the render"
    (seeded!)
    (let [before (rt/stats)]
      (render (done-row (volatile! nil)))
      (let [after (rt/stats)]
        (is (= (:cells before) (:cells after)) "no cell built")
        (is (= (:cell-refs before) (:cell-refs after)) "no reference taken")
        (is (= (:boundaries before) (:boundaries after)) "no boundary registered")
        (is (= (:edges before) (:edges after)) "no edge added")))))
