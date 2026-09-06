(ns re-frame.hicasso.staged-reincarnation-basis-cljs-test
  "A STAGED KEY ACROSS A SAME-ID REINCARNATION — the one scenario in which
  the generation term of `commit-basis` carries something the frame's
  install epoch does not (rf2-6c12m.19).

  `commit-basis` is three monotone terms: this runtime's flush
  generation, the frame's own install epoch, and the registry epoch. The
  generation moves only through `flush!`, and every path that reaches
  `flush!` — the cell watch, and the microtask rewire after a disposal —
  is a path on which the frame's install epoch has already moved. So the
  generation looks redundant, and rf2-6c12m.19 asks whether it is.

  ## Where the two terms part company

  The frame epoch RESTARTS at zero when a frame is destroyed and remade
  under the same id. A boundary that rendered a STAGED key — no cell yet,
  so its snapshot is the live basis — and whose commit lands after such a
  reincarnation compares `basis@render` with `basis@commit`, and if the
  successor's install count happens to equal the predecessor's at render,
  the frame term ties. Nothing the boundary read moved the generation:
  the key had no cell, so no watch, so no flush.

  What does move it is the SIDE EFFECT of the reincarnation on any OTHER
  cell the frame holds: its reaction is disposed with the frame,
  `invalidate-cell!` rewires it at the microtask checkpoint and marks it
  dirty, and that flush bumps the generation — one number, runtime-wide,
  that the staged boundary's snapshot then reads through the basis. So
  the staged boundary's `basis@commit` differs from its `basis@render`
  by exactly the generation term, React's post-subscribe re-read sees
  the store moved, and the boundary that painted the predecessor's value
  is re-rendered against the successor.

  Drop the term and that boundary keeps the predecessor's value on screen
  until the next write to its key — on a tenant switch, another tenant's
  data, with no trace left by the time anyone looks. That is the P0 class
  `generation.cljs` names, and it is why the term stays.

  ## What this row does and does not claim

  It claims the term is LOAD-BEARING: with it, the number moves; without
  it — the control run for rf2-6c12m.19 removed `@!generation` from the
  sum and this row went red — the number ties. It does not claim the
  term is a complete repair. A frame holding NO other cell at the
  reincarnation has nothing to rewire, so no flush bumps the generation
  and the basis ties with or without the term; that half of the
  `:node-key` axis is the one `commit-basis`'s docstring already
  concedes and is not this row's to close.

  The harness is the commit seam, as in `reincarnation_cells_cljs_test`:
  `render-body` is the render, `commit-boundary!` is React's `subscribe`,
  and `snapshot-of` is the number React compares."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.hicasso.checkpoint-support :as rf.hicasso.checkpoint-support]
            [re-frame.hicasso.impl.collector :as rf.hicasso.impl.collector]
            [re-frame.hicasso.test.runtime :as rf.hicasso.test.runtime]
            [re-frame.test-support :as rf.test-support]))

(def ^:private frame-id ::staged-reincarnation)

(rf/reg-event :staged/seed  (fn [_ [_ who]] {:db {:who who :n 0}}))
(rf/reg-event :staged/touch (fn [{:keys [db]} _] {:db (update db :n inc)}))
(rf/reg-sub   :staged/who   (fn [db _] (:who db)))
(rf/reg-sub   :staged/n     (fn [db _] (:n db)))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.hicasso.impl.collector/reset-runtime!))}))

(def ^:private held-key [frame-id [:staged/n]])

(defn- incarnate!
  "Make the frame under its public id, seed it with `who`, and install
  `touches` more times. Answers the frame's install epoch."
  [who touches]
  (rf.hicasso.checkpoint-support/leave-act-environment!)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:staged/seed who])
    (dotimes [_ touches] (rf/dispatch-sync [:staged/touch])))
  (rf.frame/frame-commit-epoch frame-id))

(defn- reincarnate-to-epoch!
  "Destroy the frame and remake it under the same id, seeded with `who`,
  installing until its epoch reads `epoch` — the tie the frame term
  cannot see past. Answers the successor's epoch."
  [who epoch]
  (rf/destroy-frame! frame-id)
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id
    (rf/dispatch-sync [:staged/seed who])
    (while (< (rf.frame/frame-commit-epoch frame-id) epoch)
      (rf/dispatch-sync [:staged/touch])))
  (rf.frame/frame-commit-epoch frame-id))

(defn- commit-held!
  "A committed boundary holding a cell on the frame — the OTHER cell the
  reincarnation's side effect reaches. Answers its release fn."
  []
  (rf.hicasso.impl.collector/render-body frame-id (fn [_] (rf.hicasso.impl.collector/sub [:staged/n])) {})
  (rf.hicasso.impl.collector/commit-boundary! (rf.hicasso.impl.collector/last-reads) (fn [])))

(defn- render-staged!
  "Render — and only render — a boundary reading the staged key. Answers
  what it painted, its entry, and the number React captured at render."
  []
  (let [value (rf.hicasso.impl.collector/render-body frame-id (fn [_] (rf.hicasso.impl.collector/sub [:staged/who])) {})
        entry (rf.hicasso.impl.collector/last-reads)]
    {:value value :entry entry :at-render (rf.hicasso.test.runtime/snapshot-of entry)}))

(deftest a-staged-key-committed-across-a-same-id-reincarnation-sees-the-store-move
  (async done
    (let [epoch-a      (incarnate! "A" 3)
          release-held (commit-held!)
          {:keys [value entry at-render]} (render-staged!)]
      (is (= "A" value) "the render painted the predecessor's value")
      (is (some? (rf.hicasso.test.runtime/cell-reaction held-key))
          "and the frame holds one other cell, whose reaction the teardown will dispose")

      ;; THE GAP. The frame dies and comes back under the same id, with a
      ;; different value under the staged key and the SAME install epoch
      ;; the render observed — so the frame term of the basis ties.
      (let [epoch-b (reincarnate-to-epoch! "B" epoch-a)]
        (is (= epoch-a epoch-b)
            "precondition: the successor's install epoch ties the predecessor's at render")
        (is (nil? (rf.hicasso.test.runtime/cell-reaction held-key))
            "the held cell's reaction was dropped synchronously by the teardown")

        (rf.hicasso.checkpoint-support/at-the-checkpoint
          #(some? (rf.hicasso.test.runtime/cell-reaction held-key))
          "the held cell's reincarnation rewire"
          done
          (fn [_turns]
            (let [release-staged (rf.hicasso.impl.collector/commit-boundary! entry (fn []))
                  at-commit      (rf.hicasso.test.runtime/snapshot-of entry)]
              (testing "the commit lands after the rewire, so React's
                        post-subscribe re-read of `getSnapshot` must differ
                        from the number the fiber captured at render — the
                        boundary painted A's value and the frame is now B's"
                (is (not= at-render at-commit)
                    (str "basis@render " at-render " vs basis@commit " at-commit
                         ": a tie here is the predecessor's value left on screen")))
              (testing "and the cell the commit acquired answers for the successor"
                (is (= "B" (rf.hicasso.impl.collector/render-body frame-id
                                                  (fn [_] (rf.hicasso.impl.collector/sub [:staged/who]))
                                                  {}))))
              (release-staged)
              (release-held))))))))

(deftest with-nothing-in-the-gap-the-staged-number-ties-and-nothing-re-renders
  ;; NEGATIVE CONTROL for the row above: the same render and the same
  ;; commit with no reincarnation between them. The number must NOT move,
  ;; or the row above would be reporting an instrument that always moves
  ;; rather than a transition that moved it — and a mount that raced
  ;; nothing must not re-render for nothing.
  (incarnate! "A" 3)
  (let [release-held (commit-held!)
        {:keys [entry at-render]} (render-staged!)
        release-staged (rf.hicasso.impl.collector/commit-boundary! entry (fn []))]
    (is (= at-render (rf.hicasso.test.runtime/snapshot-of entry))
        "a cell born at the basis the render read contributes the same number")
    (release-staged)
    (release-held)))
