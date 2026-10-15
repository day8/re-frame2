(ns re-frame.bench.fresco.arm1.staged-read-tear-cljs-test
  "Snapshot checks detect staged and retained changes while a clean mount requests no extra render."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.dogfood :as rf.bench.fresco.front.dogfood]
            [re-frame.frame :as rf.frame]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.uix/adapter
     :init-fn (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!))}))

(def ^:private frame-id ::arm1-staged-tear)

(defn- seeded! []
  (rf.bench.fresco.arm1.runtime/reset-runtime!)
  (rf.bench.fresco.front.dogfood/make-frame! frame-id 3)
  frame-id)

(defn- render
  "One render pass for `body-fn`, through the shell's own fence. Returns
  the read-set entry — the object React's `subscribe` and `getSnapshot`
  hang off."
  [body-fn]
  (rf.bench.fresco.arm1.runtime/render-body frame-id body-fn {})
  (rf.bench.fresco.arm1.runtime/last-reads))

(defn- done-row
  "A boundary reading one row's done? flag, and recording what it read.
  The read is the whole body, so the boundary's read set is one key and
  the arithmetic below is one term."
  [seen]
  (fn [_] (let [v (rf.bench.fresco.arm1.runtime/sub [:dogfood/done? 0])] (vreset! seen v) [:li (str v)])))

;; The tear, and what closes it

(deftest a-staged-read-that-moves-in-the-gap-is-corrected
  (testing "hd-002-adjudication.md §6.1's adversarial witness, executed
           against Arm 1's own runtime. The value moves after the body
           returned and before React's commit acquires the key, and it
           moves WITHIN ONE GENERATION — because for a key nothing holds
           the generation cannot move. The number React re-checks must
           move anyway, or the boundary paints stale forever."
    (seeded!)
    (let [seen      (volatile! nil)
          entry     (render (done-row seen))
          painted   @seen
          cells     (:cells (rf.bench.fresco.arm1.runtime/stats))
          at-render (rf.bench.fresco.arm1.runtime/snapshot-of entry)
          generation (rf.bench.fresco.arm1.runtime/generation)
          frame-e   (rf.frame/frame-commit-epoch frame-id)]
      ;; THE GAP.
      (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 0])
      (let [gap {:generation-moved (not= generation (rf.bench.fresco.arm1.runtime/generation))
                 :install-epoch-moved (> (rf.frame/frame-commit-epoch frame-id) frame-e)}
            ;; THE COMMIT: React calls the entry's `subscribe`.
            release! (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn []))
            tear?    (not= at-render (rf.bench.fresco.arm1.runtime/snapshot-of entry))]
        (render (done-row seen))
        (is (= {:painted false :cells 0
                :gap {:generation-moved false :install-epoch-moved true}
                :tear true :re-render true}
               {:painted painted :cells cells :gap gap :tear tear? :re-render @seen})
            "STAGED, not retained: the render painted false and nothing holds
             the key, so there is no cell, no watch and no epoch. The
             generation did NOT move — no watch to fire, so the change
             happened within one generation, which is precisely the witness
             §6.1 asks for — but the frame's own physical-install epoch did.
             So the number React stored at render is not the number it
             re-reads after `subscribe`, `updateStoreInstance` schedules the
             boundary, and the re-render reads the value that is now true")
        (release!)))))

(deftest a-clean-mount-asks-react-for-nothing
  (testing "the half that stops the staged term from meaning `re-render always`.
           A staged key whose value did NOT move in the gap must leave
           the snapshot exactly where the render left it, or every mount
           in the application pays a second render."
    (seeded!)
    (let [entry     (render (done-row (volatile! nil)))
          at-render (rf.bench.fresco.arm1.runtime/snapshot-of entry)
          release!  (rf.bench.fresco.arm1.runtime/commit-boundary! entry (fn []))]
      (is (= at-render (rf.bench.fresco.arm1.runtime/snapshot-of entry))
          "acquisition alone moves nothing: the cell is born at the same
           basis the staged term reported")
      (release!))))

(deftest a-retained-key-moving-in-the-gap-is-still-corrected
  (testing "the retained path. A key some earlier boundary committed
           has a cell whose watch PRE-DATES the move, so the ordinary
           invalidation path reaches it. The staged term must not
           disturb that — and the epoch a flush stamps is a
           `commit-basis` reading rather than a private count, so this
           row is also the check that re-stamping strictly increases."
    (seeded!)
    (let [warm       (render (done-row (volatile! nil)))
          hold!      (rf.bench.fresco.arm1.runtime/commit-boundary! warm (fn []))
          cells      (:cells (rf.bench.fresco.arm1.runtime/stats))
          entry      (render (done-row (volatile! nil)))
          at-render  (rf.bench.fresco.arm1.runtime/snapshot-of entry)
          generation (rf.bench.fresco.arm1.runtime/generation)]
      (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 0])
      (is (= [1 (inc generation) true]
             [cells
              (rf.bench.fresco.arm1.runtime/generation)
              (not= at-render (rf.bench.fresco.arm1.runtime/snapshot-of entry))])
          "RETAINED: an earlier commit holds the key, so the pre-existing
           watch fired and the generation moved here, and the epoch sum
           moved with it")
      (hold!))))

;; The same blind spot, inside the fence's own window

(deftest the-fence-sees-a-mid-body-move-of-a-key-nothing-holds
  (testing "the sibling hole, and the reason the fence compares the basis
           rather than the generation alone. A body reads a key nothing
           holds, writes, and reads again. No cell means no watch means
           no `mark-dirty!` means no generation bump — so a fence
           comparing the generation would see one still number across a
           body whose two reads straddle two commits. The frame's install
           epoch moves for that write, so the basis does, and the body
           re-runs against the newer commit."
    (seeded!)
    (let [runs   (volatile! 0)
          first- (volatile! nil)
          then-  (volatile! nil)]
      (render (fn [_]
                (vswap! runs inc)
                (vreset! first- (rf.bench.fresco.arm1.runtime/sub [:dogfood/done? 0]))
                (when (= 1 @runs)
                  (rf.bench.fresco.arm1.runtime/dispatch! frame-id [:dogfood/toggle 0]))
                (vreset! then- (rf.bench.fresco.arm1.runtime/sub [:dogfood/done? 0]))
                [:li (str @then-)]))
      (is (= [2 true true] [@runs @first- @then-])
          "the fence re-ran the body against the newer commit, so the
           winning run's two reads are on ONE commit and both read the
           committed value — the DOM never carries a pair of values that
           were not simultaneously true"))))

;; A render recording nothing is `cold_read_cljs_test`'s
;; `a-cold-read-leaves-the-world-as-it-found-it`.
