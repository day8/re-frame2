(ns re-frame.bench.hicasso.generation-fence-coverage-cljs-test
  "WHAT THE COMMIT BASIS STILL DOES NOT SEE (rf2-2rtt6.33, re-pointed by
  rf2-2rtt6.43).

  `hd-002-adjudication.md` §6.1 asked whether ONE generation comparison
  per boundary could stand in for the predecessor's commit-side re-read,
  which compared **three** things between the render that produced an
  element and the commit about to publish it: node identity
  (`:node-key`), version, and the frame/registry epochs. This file was
  the answer, and the answer was *no* on all three.

  **Two of the three have since been closed** — rf2-2rtt6.42 replaced the
  bare generation with [[re-frame.bench.hicasso.arm1.runtime/commit-basis]]
  (the flush generation PLUS the frame's own physical-install epoch), and
  the version axis now heals in both windows. Those rows have moved to
  `arm1/staged_read_tear_cljs_test`, against the arm's own runtime,
  mutation-proved both ways, with a real-Chromium counterpart in
  `arm1/generation_fence_dom_cljs_test`. Keeping a second copy here would
  give one assertion two homes, so this file no longer carries them.

  What is left is the registry axis, and it is the whole of this file:
  **the two terms rf2-2rtt6.42 built cannot see a `:sub` registration, so
  the basis carries a third one that can — and it reaches a staged key
  without touching a held one.**

  ## Why the first two terms cannot see it

      commit-basis(frame) = flush generation + frame-commit-epoch(frame)
                          + registry-epoch

  The first term moves only through `flush!`, whose only caller is
  `mark-dirty!`, whose only caller is the per-cell value-change watch
  `acquire-cell!` installs. A re-registration changes the *computation*
  behind a query; it does not push a new value through an acquired
  reaction, so it reaches none of them. The second term is bumped once
  per physical frame-state install at the substrate's two write
  chokepoints — and a registry write is not a frame-state install. So
  both of them sit still, and until rf2-2rtt6.50 React's post-`subscribe`
  `getSnapshot` re-check sat still with them.

  ## What the third term reaches, and what it deliberately does not

  `getSnapshot` reads the basis **live** for a key no cell holds, and
  reads a cell's **frozen** stamp for one that is held. The third term
  therefore reaches exactly one situation: a boundary inside the
  render→commit gap, whose body read one computation while the commit is
  about to acquire another. A mounted boundary's number does not move at
  all, and the row below asserts both halves one line apart.

  That is what makes this not the term rf2-2rtt6.44 costed and declined.
  That one sat in every key's live contribution, so every mounted
  boundary in the application re-rendered on every `reg-sub` — and read
  back through a cell the re-registration had just made deaf, which is
  why it bought nothing. In the gap there is no cell to be deaf: the
  commit acquires against the registration that is live then, so the one
  extra render is the whole repair. rf2-2rtt6.50.

  The `:node-key` axis is silent for the same reason and is stated rather
  than staged, because a second row would re-prove this one's arithmetic
  with a longer fixture: a same-id frame reincarnation is not a value
  change on an acquired reaction either — and `frame-commit-epoch`
  RESTARTS at 0 across one, which is precisely why Spec 006's observation
  port carries `:node-key` as a third field.

  ## The held-cell half is closed by events, and should stay that way

  **rf2-2rtt6.44 settled the held-cell half of all three axes, and this
  arithmetic is still not what closes it** — which is the point of keeping
  this file. Where a boundary already holds a cell, the commit basis is
  still blind to a re-registration, still blind to a reincarnation, and
  *should be*: the costing found that a term would have bought nothing.
  Each of those events leaves the arm's cell holding a reaction that can
  no longer answer for its key, so the cell is deaf from that instant, and
  the extra render a moved number scheduled would have read straight back
  through it. What closes that half is `arm1.runtime/invalidate-cell!`,
  costing nothing in this arithmetic — so `observation/registry-epoch*`
  stayed `^:private` and no substrate reader was added, then or now: the
  arm counts registrations on the registration hook it already installs.
  `arm1/disposed-cell-cljs-test` is the measurement for the two
  transitions that reach it as a *disposal*, armed per unique key: a
  re-registration and a frame teardown.

  The registry axis has one more transition, and it is the one no disposal
  announces: a **first** registration. `registrar/add-replacement-hook!`
  fires only when a previous handler existed, so the arm hears it from
  `registrar/add-registration-hook!` instead
  (`arm1.runtime/first-registration!`) for the held-cell half, and from
  the basis's third term for the gap half.
  `arm1/first-registration-cljs-test` is that measurement.

  These rows are therefore a **standing statement of scope**: this number
  answers the version axis, and the registry axis for staged keys only.

  ## The row carries its own control, and needs to

  Two of its four assertions are that a number does NOT move, and a still
  number is trivially still on a broken fixture. So the row first makes a
  real frame-state install and watches the basis and the snapshot move —
  same frame, same boundary, same read set, same instruments, one line
  apart — and only then re-registers. Without the positive half on the
  board this row would pass against a runtime that had no counters at
  all.

  ## Everything here runs against Arm 1's own runtime

  This file used to TRANSCRIBE `flush!`, `mark-dirty!`, `acquire-cell!`
  and the epoch-sum `getSnapshot`, because HD-017 kept runtime skeletons
  off main. They are on main now, so the transcription is gone: the row
  drives `render-body` (the render), `snapshot-of` (React's
  `useSyncExternalStore` capture and its `checkIfSnapshotChanged`) and
  `commit-boundary!` (React's `subscribe`) directly. The host is the
  React spine's adapter rather than the plain-atom substrate, and that is
  load-bearing: on an unwatchable host a subscription never notifies and
  the control half would be as still as the axis it is controlling for."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.core :as rf]
            [re-frame.frame :as frame]
            [re-frame.live-frame :as live-frame]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     ;; The carried-invariant chain resolves the dynamic-var frame tier
     ;; BEFORE React context, so a fixture-installed ambient frame would
     ;; answer reads for a frame this row never made.
     :ambient-frame nil
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private q [:genfence/v])

(defn- make-frame!
  "This row's OWN frame and OWN query: a re-registration is a global act,
  and re-registering a query some other suite reads would be a test
  writing on a neighbour."
  [id db]
  (live-frame/make-frame {:id id})
  (frame/replace-app-db! id db)
  id)

(defn- reader
  "A boundary whose whole body is one read, so the entry's read set is one
  key and the snapshot arithmetic is one term."
  [seen]
  (fn [_] (let [v (rt/sub q)] (vreset! seen v) [:li (str v)])))

;; ---------------------------------------------------------------------------
;; The registry-epoch axis reaches a staged key and not a held one
;; ---------------------------------------------------------------------------

(deftest the-commit-basis-registry-axis-reaches-a-staged-key-and-not-a-held-one
  (testing "The predecessor's third field. `obs/read` reports the registry
            epoch on every live node and `moved?` compares it, so a handler
            re-registration between a render and its commit corrects before
            paint there.

            The basis carries this axis too, since rf2-2rtt6.50 — but it
            reaches only the half that needs it, and that asymmetry is the
            row. Neither of the other two terms can see a registration: it
            is not a frame-state install, so `frame-commit-epoch` does not
            move, and it is not a value change on an acquired reaction, so
            it never reaches `mark-dirty!` and the flush generation does not
            move either. The third term, `registry-epoch`, moves.

            **What that does and does not reach.** `getSnapshot` reads the
            basis LIVE for a key no cell holds, and reads a cell's FROZEN
            stamp for one that is held. So a boundary in the render→commit
            gap sees the number move and re-renders through a cell the
            commit acquired against the live registration; a mounted
            boundary's number does not move at all. That is why this is not
            the term rf2-2rtt6.44 declined — that one sat in every key's
            live contribution and woke every mounted boundary in the
            application, to read back through a cell the re-registration had
            just made deaf. The mounted case is still repaired by the
            substrate's own events (`arm1.runtime/invalidate-cell!`, off the
            reaction's disposal), and this row asserts that its snapshot
            stays exactly where it was."
    (rf/reg-sub (first q) (fn [db _] (:v db)))
    (let [seen (volatile! nil)
          f    (make-frame! ::registry {:v 1})]
      (rt/render-body f (reader seen) {})
      (let [entry    (rt/last-reads)
            release! (rt/commit-boundary! entry (fn []))]
        (is (= 1 @seen) "the render read the value that was true when it ran")

        (testing "the CONTROL — the instruments are live on this frame, this
                  boundary and this read set. A real frame-state install
                  moves the basis and moves the number React re-checks."
          (let [basis    (rt/commit-basis f)
                snapshot (rt/snapshot-of entry)]
            (frame/replace-app-db! f {:v 2})
            (is (> (rt/commit-basis f) basis)
                "a physical frame-state install bumps `frame-commit-epoch`,
                 so the basis moves")
            (is (not= snapshot (rt/snapshot-of entry))
                "and the retained key's watch fired, so the epoch sum moved
                 too — this is what a MOVE looks like on these instruments")))

        (testing "and the axis: the same query, a different computation"
          ;; A SECOND boundary, rendered and deliberately NOT committed, so
          ;; its key is staged for the duration of the re-registration. Its
          ;; own frame, because the assertion below is that this frame's
          ;; mounted boundary is untouched and a shared frame would let one
          ;; claim borrow the other's stillness.
          (let [staged-f     (make-frame! ::registry-staged {:v 1})
                _            (rt/render-body staged-f (reader (volatile! nil)) {})
                staged-entry (rt/last-reads)
                staged-snap  (rt/snapshot-of staged-entry)
                basis        (rt/commit-basis f)
                snapshot     (rt/snapshot-of entry)
                generation   (rt/generation)]
            (rf/reg-sub (first q) (fn [db _] (* 10 (:v db))))
            (is (= generation (rt/generation))
                "the flush generation did not move — a re-registration is not
                 a value change on an acquired reaction, so it never reaches
                 `mark-dirty!`")
            (is (> (rt/commit-basis f) basis)
                "but the basis did: `registry-epoch` is its third term, and a
                 registration is the one thing that moves it (rf2-2rtt6.50)")
            (is (= snapshot (rt/snapshot-of entry))
                "and the MOUNTED boundary's number is still exactly the
                 number it was — its key is held, so its contribution is the
                 cell's frozen stamp and not a live basis read. This is the
                 assertion that separates this term from the one
                 rf2-2rtt6.44 declined")
            (is (not= staged-snap (rt/snapshot-of staged-entry))
                "while the STAGED boundary's number moved — its key has no
                 cell, so it contributes the basis live, and React's
                 post-`subscribe` re-check will see the tear")))
        (release!)))))
