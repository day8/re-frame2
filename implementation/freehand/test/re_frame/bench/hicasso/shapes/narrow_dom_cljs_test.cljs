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
  4. **What narrow does NOT cover, measured.** A write the *page*
     boundary reads re-renders the page, and React then re-renders all
     300 cards beneath it, because a Hicasso boundary is a plain function
     component with no props-equality bail-out — where Reagent's default
     `shouldComponentUpdate` compares argv and stops the cascade. This is
     recorded as a **finding** with a number against it, not repaired
     here: repairing it is a runtime change and this file's job is to find
     out what the shape costs. See the bead's Findings.

  Runtime: `-dom-cljs-test`. Under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.feed :as shape]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::shape-narrow)

(def ^:private moved-index
  "The row the witness writes to — deliberately deep in the page rather
  than first or last, where an off-by-one in a list rebuild would be
  hardest to tell from a correct narrow update."
  137)

(defn- skip! [why]
  (is true (str "shape 4's witness needs a real React DOM — " why)))

(defn- fresh!
  ([] (fresh! shape/seed))
  ([seed]
   (lane/leave-act-environment!)
   (m/make-frame! frame-id seed)
   (m/reseed! frame-id seed)
   (shape/reset-runs!)
   frame-id))

(defn- mount! []
  (mount/root! (mount/fresh-container!) frame-id [shape/page {}]))

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
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [slug   (shape/slug-at moved-index)
                node   (q handle (str "[data-testid=\"article-preview-" slug "\"]"))
                before (favourite-counts handle)]
            (is (= shape/article-count (count before)))
            (shape/reset-runs!)
            (rt/dispatch! frame-id [:conduit/favorite slug])
            (mount/settle!)
            (is (= 1 (:cards (shape/runs)))
                (str "exactly one card body re-ran out of " shape/article-count))
            (is (= 0 (:page (shape/runs)))
                "and the page boundary did not — it reads the order, and the
                 order did not move")
            (let [after (favourite-counts handle)
                  moved (differing-indices before after)]
              (is (= [moved-index] moved)
                  (str "exactly one of the " shape/article-count
                       " rendered counts differs, and it is the one written to"))
              (is (= (str (inc (js/parseInt (nth before moved-index) 10)))
                     (nth after moved-index))
                  "by exactly one"))
            (is (identical? node (q handle (str "[data-testid=\"article-preview-" slug "\"]")))
                "and the card is the SAME DOM node — React patched it in place
                 rather than replacing a subtree"))
          (finally (mount/release! handle)))))))

(deftest the-narrow-reading-can-answer-false
  (testing "the assertion above is not passing vacuously: the broad write on
           the same page moves every count, so `differing-indices` is a live
           comparison rather than one that always answers empty"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount!)]
          (try
            (let [before (favourite-counts handle)]
              (rt/dispatch! frame-id [:conduit/refresh-feed])
              (mount/settle!)
              (is (= shape/article-count
                     (count (differing-indices before (favourite-counts handle))))
                  "all 300 differ"))
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 3 — flat in B
;; ---------------------------------------------------------------------------

(deftest one-body-runs-whatever-the-page-size
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (doseq [n [50 150 300]]
      (fresh! {:articles n :tags shape/tag-count})
      (let [handle (mount!)]
        (try
          (is (= n (count (q* handle ".article-list > .article-preview")))
              (str n " boundaries mounted"))
          (shape/reset-runs!)
          (rt/dispatch! frame-id [:conduit/favorite (shape/slug-at (quot n 2))])
          (mount/settle!)
          (is (= {:cards 1 :page 0} (shape/runs))
              (str "one body, at B = " n " — the dirty set is flat in B, which is
                   how validation.md states this shape's win condition: a law,
                   not a ratio"))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 4 — the cascade, measured rather than discovered on a clock
;; ---------------------------------------------------------------------------

(deftest a-page-chrome-write-re-renders-every-row
  (testing "**FINDING, not a repair.** A Hicasso boundary is a plain React
           function component with no props-equality bail-out, so a write the
           PAGE reads re-renders the page and React re-renders all 300 cards
           beneath it — even though every card's props and every card's
           subscription values are unchanged. Reagent's default
           `shouldComponentUpdate` compares argv and stops exactly this
           cascade, so this is a real difference on the axis the bar is set
           on. Pinned here so it is a number in the record rather than
           something a bulk clock discovers later and cannot attribute."
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount!)]
          (try
            (let [before (favourite-counts handle)]
              (shape/reset-runs!)
              (rt/dispatch! frame-id [:conduit/show-your-feed])
              (mount/settle!)
              (is (= 1 (:page (shape/runs)))
                  "the page re-ran once, which is correct — it reads the tab")
              (is (= shape/article-count (:cards (shape/runs)))
                  (str "and so did every one of the " shape/article-count
                       " cards, none of whose reads moved"))
              (is (= before (favourite-counts handle))
                  "producing an identical DOM — which is why a DOM-only witness
                   could never have seen this"))
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; Teardown
;; ---------------------------------------------------------------------------

(deftest the-narrow-page-leaves-no-residue
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (rt/dispatch! frame-id [:conduit/favorite (shape/slug-at moved-index)])
        (mount/settle!)
        (is (pos? (:cell-refs (rt/stats))))
        (mount/unmount! handle)
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rt/residue)))
                         (rt/reset-runtime!)
                         (done))
                       8)))))
