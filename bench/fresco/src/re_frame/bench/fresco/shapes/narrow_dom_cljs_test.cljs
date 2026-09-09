(ns re-frame.bench.hicasso.shapes.narrow-dom-cljs-test
  "**SHAPE 4'S WITNESS** — one cell moves in a large mounted page
  (rf2-2rtt6.51).

  validation.md states this shape's win condition as **a law rather than
  a ratio**: the commit-side dirty set is flat in `B` across mounted
  subscribing boundaries. No clock is read here; what is established is
  the law's correctness half — that one commit reaches exactly one
  boundary and exactly one cell, and that the number does not move when
  the page grows.

  Four claims:

  1. **One body runs.** A favourite on one of 300 cards re-runs that
     card's body and nothing else — including, load-bearingly, not the
     page above it.
  2. **One cell moves.** Across all 300 rendered counts, exactly one
     differs after the commit, by exactly one, at exactly the index
     written to. And the card's DOM node is the SAME node — React patched
     text in place rather than replacing a subtree.
  3. **It is flat in B.** The same commit on pages of 50, 150 and 300
     boundaries re-runs one body every time.
  4. **A page-chrome write stops at the cards.** A write the *page*
     boundary reads re-renders the page and **no** card beneath it. This
     row is where the roster earned its keep: it read 300 of 300 when
     first taken, because a Hicasso boundary was a plain function
     component with no value-equality bail-out — where Reagent's default
     `shouldComponentUpdate` compares argv and stops the cascade. That
     was the evidence HD-006 had pre-registered as its own reopen
     condition, and it fired: the bail-out is now the boundary default
     (rf2-2rtt6.52), and the number here is 0.

  Runtime: `-dom-cljs-test`. Under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.mount :as rf.bench.hicasso.arm1.mount]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.shapes.feed :as rf.bench.hicasso.shapes.feed]
            [re-frame.bench.hicasso.shapes.model :as rf.bench.hicasso.shapes.model]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.bench.hicasso.arm1.runtime/reset-runtime!))}))

(def ^:private frame-id ::shape-narrow)

(def ^:private moved-index
  "The row the witness writes to — deliberately deep in the page rather
  than first or last, where an off-by-one in a list rebuild would be
  hardest to tell from a correct narrow update."
  137)

(defn- skip! [why]
  (is true (str "shape 4's witness needs a real React DOM — " why)))

(defn- fresh!
  ([] (fresh! rf.bench.hicasso.shapes.feed/seed))
  ([seed]
   (rf.bench.hicasso.lane/leave-act-environment!)
   (rf.bench.hicasso.shapes.model/make-frame! frame-id seed)
   (rf.bench.hicasso.shapes.model/reseed! frame-id seed)
   (rf.bench.hicasso.shapes.feed/reset-runs!)
   frame-id))

(defn- mount! []
  (rf.bench.hicasso.arm1.mount/root! (rf.bench.hicasso.arm1.mount/fresh-container!) frame-id [rf.bench.hicasso.shapes.feed/page {}]))

(defn- q [handle sel] (.querySelector (:container handle) sel))
(defn- q* [handle sel] (array-seq (.querySelectorAll (:container handle) sel)))

(defn- favourite-counts [handle]
  (mapv #(.-textContent %) (q* handle "[data-testid^=\"favorites-count-\"]")))

(defn- differing-indices [before after]
  (into [] (keep-indexed (fn [i b] (when (not= b (nth after i)) i))) before))

;; ---------------------------------------------------------------------------
;; 1 + 2 — one body, one cell
;; ---------------------------------------------------------------------------

(deftest one-write-re-runs-one-body-out-of-three-hundred
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [slug   (rf.bench.hicasso.shapes.feed/slug-at moved-index)
                node   (q handle (str "[data-testid=\"article-preview-" slug "\"]"))
                before (favourite-counts handle)]
            (is (= rf.bench.hicasso.shapes.feed/article-count (count before)))
            (rf.bench.hicasso.shapes.feed/reset-runs!)
            (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/favorite slug])
            (rf.bench.hicasso.arm1.mount/settle!)
            (is (= 1 (:cards (rf.bench.hicasso.shapes.feed/runs)))
                (str "exactly one card body re-ran out of " rf.bench.hicasso.shapes.feed/article-count))
            (is (= 0 (:page (rf.bench.hicasso.shapes.feed/runs)))
                "and the page boundary did not — it reads the order, and the
                 order did not move")
            (let [after (favourite-counts handle)
                  moved (differing-indices before after)]
              (is (= [moved-index] moved)
                  (str "exactly one of the " rf.bench.hicasso.shapes.feed/article-count
                       " rendered counts differs, and it is the one written to"))
              (is (= (str (inc (js/parseInt (nth before moved-index) 10)))
                     (nth after moved-index))
                  "by exactly one"))
            (is (identical? node (q handle (str "[data-testid=\"article-preview-" slug "\"]")))
                "and the card is the SAME DOM node — React patched it in place
                 rather than replacing a subtree"))
          (finally (rf.bench.hicasso.arm1.mount/release! handle)))))))

(deftest the-narrow-reading-can-answer-false
  (testing "the assertion above is not passing vacuously: the broad write on
           the same page moves every count, so `differing-indices` is a live
           comparison rather than one that always answers empty"
    (if-not (rf.bench.hicasso.arm1.mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount!)]
          (try
            (let [before (favourite-counts handle)]
              (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/refresh-feed])
              (rf.bench.hicasso.arm1.mount/settle!)
              (is (= rf.bench.hicasso.shapes.feed/article-count
                     (count (differing-indices before (favourite-counts handle))))
                  "all 300 differ"))
            (finally (rf.bench.hicasso.arm1.mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 3 — flat in B
;; ---------------------------------------------------------------------------

(deftest one-body-runs-whatever-the-page-size
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (doseq [n [50 150 300]]
      (fresh! {:articles n :tags rf.bench.hicasso.shapes.feed/tag-count})
      (let [handle (mount!)]
        (try
          (is (= n (count (q* handle ".article-list > .article-preview")))
              (str n " boundaries mounted"))
          (rf.bench.hicasso.shapes.feed/reset-runs!)
          (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/favorite (rf.bench.hicasso.shapes.feed/slug-at (quot n 2))])
          (rf.bench.hicasso.arm1.mount/settle!)
          (is (= {:cards 1 :page 0} (rf.bench.hicasso.shapes.feed/runs))
              (str "one body, at B = " n " — the dirty set is flat in B, which is
                   how validation.md states this shape's win condition: a law,
                   not a ratio"))
          (finally (rf.bench.hicasso.arm1.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 4 — the cascade, measured rather than discovered on a clock
;; ---------------------------------------------------------------------------

(deftest a-page-chrome-write-re-renders-no-unchanged-row
  (testing "**REPAIRED, and this was the finding that carried it** — the
           reopen condition HD-006 pre-registered for itself. This read 300
           of 300 when the roster first took it: a write the PAGE reads
           re-rendered the page, and React re-rendered every card beneath
           it, though every card's props and every card's subscription
           values were equal. HD-006 is amended (rf2-2rtt6.52) and a
           value-equality bail-out is now the boundary default, so the same
           write re-runs the page and NOT ONE card. The card count is what
           witnesses this and not a DOM comparison — the tab chrome does
           move."
    (if-not (rf.bench.hicasso.arm1.mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount!)]
          (try
            (let [before (favourite-counts handle)]
              (rf.bench.hicasso.shapes.feed/reset-runs!)
              (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/show-your-feed])
              (rf.bench.hicasso.arm1.mount/settle!)
              (is (= 1 (:page (rf.bench.hicasso.shapes.feed/runs)))
                  "the page re-ran once, which is correct — it reads the tab")
              (is (= 0 (:cards (rf.bench.hicasso.shapes.feed/runs)))
                  (str "and not one of the " rf.bench.hicasso.shapes.feed/article-count
                       " cards did, none of whose reads moved — this read "
                       rf.bench.hicasso.shapes.feed/article-count " before the repair"))
              (is (= before (favourite-counts handle))
                  "and the cards' own DOM is untouched")
              (is (some? (q handle "[data-testid=\"your-feed-tab\"].active"))
                  "while the chrome the write was ABOUT did move, so the
                   row above is not passing because the write did nothing"))
            (finally (rf.bench.hicasso.arm1.mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; Teardown
;; ---------------------------------------------------------------------------

(deftest the-narrow-page-leaves-no-residue
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/favorite (rf.bench.hicasso.shapes.feed/slug-at moved-index)])
        (rf.bench.hicasso.arm1.mount/settle!)
        (is (pos? (:cell-refs (rf.bench.hicasso.arm1.runtime/stats))))
        (rf.bench.hicasso.arm1.mount/unmount! handle)
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rf.bench.hicasso.arm1.runtime/residue)))
                         (rf.bench.hicasso.arm1.runtime/reset-runtime!)
                         (done))
                       8)))))
