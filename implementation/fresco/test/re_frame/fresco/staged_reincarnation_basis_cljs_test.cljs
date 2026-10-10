(ns re-frame.fresco.staged-reincarnation-basis-cljs-test
  "A STAGED KEY ACROSS A SAME-ID REINCARNATION — the boundary that rendered
  under the predecessor and commits under the successor.

  A boundary that rendered a STAGED key — no cell yet, so its snapshot is
  the live `commit-basis` — and whose commit lands after the frame was
  destroyed and remade under the same id compares `basis@render` with
  `basis@commit`. The frame's install epoch RESTARTS at zero with the
  successor, so if the successor's install count happens to equal the
  predecessor's at render, that term ties. A tie is the predecessor's
  value left on screen until the next write to the key — on a tenant
  switch, another tenant's data, with no trace left by the time anyone
  looks.

  The basis carries the transition in its retired-epoch term: destroying a
  frame folds its install epoch plus one into the sum, so the number after
  any reincarnation strictly exceeds every number a boundary could have
  read under the predecessor. That holds whether or not the frame holds
  other cells, and the rows below take both postures — one where another
  committed cell's microtask rewire also bumps the generation, and one
  where nothing else on the frame moves at all — each through a Fresco
  body and through a `native/use-sub` one-key entry, with a quiet gap as
  the negative control.

  The harness is the commit seam, as in `reincarnation_cells_cljs_test`:
  `render-body` (or `hook-entry` + `hook-read`) is the render,
  `commit-boundary!` is React's `subscribe`, and `snapshot-of` is the
  number React compares."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures async]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.core :as rf]
            [re-frame.frame :as rf.frame]
            [re-frame.fresco.checkpoint-support :as rf.fresco.checkpoint-support]
            [re-frame.fresco.impl.collector :as rf.fresco.impl.collector]
            [re-frame.fresco.test.runtime :as rf.fresco.test.runtime]
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
     :init-fn       (fn [] (rf.fresco.impl.collector/reset-runtime!))}))

(def ^:private held-key [frame-id [:staged/n]])

(defn- incarnate!
  "Make the frame under its public id, seed it with `who`, and install
  `touches` more times. Answers the frame's install epoch."
  [who touches]
  (rf.fresco.checkpoint-support/leave-act-environment!)
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
  (rf.fresco.impl.collector/render-body frame-id (fn [_] (rf.fresco.impl.collector/sub [:staged/n])) {})
  (rf.fresco.impl.collector/commit-boundary! (rf.fresco.impl.collector/last-reads) (fn [])))

(defn- render-staged!
  "Render — and only render — a boundary reading the staged key. Answers
  what it painted, its entry, and the number React captured at render."
  []
  (let [value (rf.fresco.impl.collector/render-body frame-id (fn [_] (rf.fresco.impl.collector/sub [:staged/who])) {})
        entry (rf.fresco.impl.collector/last-reads)]
    {:value value :entry entry :at-render (rf.fresco.test.runtime/snapshot-of entry)}))

(defn- render-staged-hook!
  "The `native/use-sub` render of the same staged key: the one-key entry a
  hook hands `useSyncExternalStore`, and the value it reads before React
  has called `subscribe`. Answers the same three things `render-staged!`
  does."
  []
  (let [sub-key [frame-id [:staged/who]]
        entry   (rf.fresco.impl.collector/hook-entry sub-key)
        value   (rf.fresco.impl.collector/hook-read sub-key)]
    {:value value :entry entry :at-render (rf.fresco.test.runtime/snapshot-of entry)}))

(defn- staged-commit-across
  "Render the staged key through `render!` under incarnation A, run `gap!`,
  then commit, on a frame holding NO other cell. Answers what was painted,
  the cell count at render, the two snapshots React compares, and what the
  committed key reads afterwards."
  [render! gap!]
  (let [epoch-a (incarnate! "A" 3)
        {:keys [value entry at-render]} (render!)
        cells   (:cells (rf.fresco.test.runtime/residue))
        epoch-b (gap! epoch-a)
        release (rf.fresco.impl.collector/commit-boundary! entry (fn []))
        result  {:painted   value
                 :cells     cells
                 :epochs    [epoch-a epoch-b]
                 :at-render at-render
                 :at-commit (rf.fresco.test.runtime/snapshot-of entry)
                 :now       (rf.fresco.impl.collector/hook-read [frame-id [:staged/who]])}]
    (release)
    result))

(defn- assert-reincarnation-moves-the-staged-number [render!]
  (let [{:keys [painted cells epochs at-render at-commit now]}
        (staged-commit-across render! #(reincarnate-to-epoch! "B" %))]
    (is (= ["A" 0] [painted cells])
        "precondition: the render painted the predecessor and the frame holds no other cell")
    (is (apply = epochs)
        "precondition: the successor's install epoch ties the predecessor's at render")
    (is (not= at-render at-commit)
        (str "basis@render " at-render " vs basis@commit " at-commit
             ": a tie here is the predecessor's value left on screen"))
    (is (= "B" now) "and the committed key answers for the successor")))

(defn- assert-a-quiet-gap-leaves-the-staged-number-alone [render!]
  (let [{:keys [painted at-render at-commit]}
        (staged-commit-across render! (fn [epoch] epoch))]
    (is (= "A" painted))
    (is (= at-render at-commit)
        "with nothing in the gap the number ties, so React schedules no re-render")))

(deftest a-staged-body-on-a-frame-holding-no-other-cell-sees-the-reincarnation
  (assert-reincarnation-moves-the-staged-number render-staged!))

(deftest a-staged-use-sub-entry-on-a-frame-holding-no-other-cell-sees-the-reincarnation
  (assert-reincarnation-moves-the-staged-number render-staged-hook!))

(deftest a-quiet-gap-leaves-a-staged-body-snapshot-equal
  (assert-a-quiet-gap-leaves-the-staged-number-alone render-staged!))

(deftest a-quiet-gap-leaves-a-staged-use-sub-snapshot-equal
  (assert-a-quiet-gap-leaves-the-staged-number-alone render-staged-hook!))

(deftest a-staged-key-committed-across-a-same-id-reincarnation-sees-the-store-move
  (async done
    (let [epoch-a      (incarnate! "A" 3)
          release-held (commit-held!)
          {:keys [value entry at-render]} (render-staged!)]
      (is (= "A" value) "the render painted the predecessor's value")
      (is (some? (rf.fresco.test.runtime/cell-reaction held-key))
          "and the frame holds one other cell, whose reaction the teardown will dispose")

      ;; THE GAP. The frame dies and comes back under the same id, with a
      ;; different value under the staged key and the SAME install epoch
      ;; the render observed — so the frame term of the basis ties.
      (let [epoch-b (reincarnate-to-epoch! "B" epoch-a)]
        (is (= epoch-a epoch-b)
            "precondition: the successor's install epoch ties the predecessor's at render")
        (is (nil? (rf.fresco.test.runtime/cell-reaction held-key))
            "the held cell's reaction was dropped synchronously by the teardown")

        (rf.fresco.checkpoint-support/at-the-checkpoint
          #(some? (rf.fresco.test.runtime/cell-reaction held-key))
          "the held cell's reincarnation rewire"
          done
          (fn [_turns]
            (let [release-staged (rf.fresco.impl.collector/commit-boundary! entry (fn []))
                  at-commit      (rf.fresco.test.runtime/snapshot-of entry)]
              (testing "the commit lands after the rewire, so React's
                        post-subscribe re-read of `getSnapshot` must differ
                        from the number the fiber captured at render — the
                        boundary painted A's value and the frame is now B's"
                (is (not= at-render at-commit)
                    (str "basis@render " at-render " vs basis@commit " at-commit
                         ": a tie here is the predecessor's value left on screen")))
              (testing "and the cell the commit acquired answers for the successor"
                (is (= "B" (rf.fresco.impl.collector/render-body frame-id
                                                  (fn [_] (rf.fresco.impl.collector/sub [:staged/who]))
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
        release-staged (rf.fresco.impl.collector/commit-boundary! entry (fn []))]
    (is (= at-render (rf.fresco.test.runtime/snapshot-of entry))
        "a cell born at the basis the render read contributes the same number")
    (release-staged)
    (release-held)))
