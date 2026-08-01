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

  What is left is the axis nothing closed, and it is the whole of this
  file: **a `:sub` re-registration moves neither term of the basis.**

  ## Why the basis cannot see it

      commit-basis(frame) = flush generation + frame-commit-epoch(frame)

  The first term moves only through `flush!`, whose only caller is
  `mark-dirty!`, whose only caller is the per-cell value-change watch
  `acquire-cell!` installs. A re-registration changes the *computation*
  behind a query; it does not push a new value through an acquired
  reaction, so it reaches none of them. The second term is bumped once
  per physical frame-state install at the substrate's two write
  chokepoints — and a registry write is not a frame-state install. So
  both terms sit still, and React's post-`subscribe` `getSnapshot`
  re-check is equally blind: the number is the sum of the same epochs it
  was before.

  The `:node-key` axis is silent for the same reason and is stated rather
  than staged, because a second row would re-prove this one's arithmetic
  with a longer fixture: a same-id frame reincarnation is not a value
  change on an acquired reaction either — and `frame-commit-epoch`
  RESTARTS at 0 across one, which is precisely why Spec 006's observation
  port carries `:node-key` as a third field.

  ## The axes are closed; this arithmetic is not what closed them

  **rf2-2rtt6.44 settled both, and the rows below survive it unchanged** —
  which is the point of keeping this file. The commit basis is still blind
  to a re-registration, still blind to a reincarnation, and *should be*:
  the costing found that a registry term would have bought nothing. Every
  one of those events leaves the arm's cell holding a reaction that can no
  longer answer for its key, so the cell is deaf from that instant; the
  extra render a moved number scheduled would have read straight back
  through it. What closes them is `arm1.runtime/invalidate-cell!`, costing
  nothing in this arithmetic — so `observation/registry-epoch*` stayed
  `^:private` and no substrate reader was added.
  `arm1/disposed-cell-cljs-test` is the measurement for the two
  transitions that reach it as a *disposal*, armed per unique key: a
  re-registration and a frame teardown.

  The registry axis has one more transition, and it is the one this
  arithmetic is least able to help with: a **first** registration. It
  disposes nothing — `registrar/add-replacement-hook!` fires only when a
  previous handler existed — and it moves neither term of the basis for
  the same reason a re-registration does not. The arm hears it from
  `registrar/add-registration-hook!` instead
  (`arm1.runtime/first-registration!`), still off this arithmetic and
  still without a registry reader.
  `arm1/first-registration-cljs-test` is that measurement. All three
  failures pinned and mutation-proved.

  These rows are therefore a **standing statement of scope**: this number
  answers the version axis and only the version axis.

  ## The row carries its own control, and needs to

  The assertion is that a number does NOT move, and a still number is
  trivially still on a broken fixture. So the row first makes a real
  frame-state install and watches the basis and the snapshot move — same
  frame, same boundary, same read set, same instruments, one line apart —
  and only then re-registers and reads them still. Without the positive
  half on the board this row would pass against a runtime that had no
  counters at all.

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
;; The registry-epoch axis has no counterpart in the commit basis
;; ---------------------------------------------------------------------------

(deftest the-commit-basis-has-no-registry-epoch-axis
  (testing "The predecessor's third field, and the one rf2-2rtt6.42 did not
            close. `obs/read` reports the registry epoch on every live node
            and `moved?` compares it, so a handler re-registration between a
            render and its commit corrects before paint there.

            Here there is nothing to compare it against. A re-registration
            is not a frame-state install, so `frame-commit-epoch` does not
            move; and it is not a value change on an acquired reaction, so
            it cannot reach `mark-dirty!` and the flush generation does not
            move either. Both terms of the basis sit still, and the epoch
            sum React re-checks sits still with them.

            That is correct and permanent, not a gap awaiting a term:
            rf2-2rtt6.44 closed this axis (and the `:node-key` axis the
            same argument covers) off the substrate's own events instead
            — the reaction's disposal for a re-registration and a frame
            teardown, the registration itself for a first `reg-sub`,
            which disposes nothing — leaving this arithmetic exactly as
            it stands."
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
          (let [basis      (rt/commit-basis f)
                snapshot   (rt/snapshot-of entry)
                generation (rt/generation)]
            (rf/reg-sub (first q) (fn [db _] (* 10 (:v db))))
            (is (= generation (rt/generation))
                "the flush generation did not move — a re-registration is not
                 a value change on an acquired reaction, so it never reaches
                 `mark-dirty!`")
            (is (= basis (rt/commit-basis f))
                "and neither did the basis — a registry write is not a
                 frame-state install, so `frame-commit-epoch` is untouched")
            (is (= snapshot (rt/snapshot-of entry))
                "so React's post-`subscribe` `getSnapshot` re-check is blind
                 to it: the number is exactly the number it was")))
        (release!)))))
