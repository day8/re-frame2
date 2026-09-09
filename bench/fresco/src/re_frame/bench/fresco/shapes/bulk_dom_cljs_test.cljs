(ns re-frame.bench.hicasso.shapes.bulk-dom-cljs-test
  "**SHAPE 3'S WITNESS — the make-or-break row** (rf2-2rtt6.51).

  > 3. Bulk re-render — ~300 boundaries on one commit; the make-or-break
  >    row.

  The charter calls it make-or-break on the clock. **No clock is read
  here**: siblings are measuring on a quiet box, and a bench driver run
  from a correctness suite would corrupt their samples. What this file
  establishes is the half a clock cannot — that the commit does the work
  it is supposed to do, to every boundary it is supposed to reach, and to
  no others.

  Four claims:

  1. **The page is 300 boundaries**, at the element count the arithmetic
     predicts, with one read-set entry per boundary (the distinct-query
     rung `runtime/retained-inventory` prices).
  2. **One commit moves all 300.** `[:conduit/refresh-feed]` re-runs
     exactly 300 card bodies and — this is the load-bearing half — **zero
     page bodies**. A list that rebuilt its children would produce the
     same DOM and the same 300 re-renders, and would not be this shape.
  3. **Every card's DOM actually moved**, checked across all 300 in one
     assertion rather than spot-checked.
  4. **The cards are byte-identical to shape 2's.** The large template and
     this page build the same card from the same source
     (`shapes.card`), so the roster's claim that the two shapes differ in
     exactly one authoring decision is a DOM comparison, not a docstring.

  Runtime: `-dom-cljs-test`. Under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.hicasso.arm1.mount :as rf.bench.hicasso.arm1.mount]
            [re-frame.bench.hicasso.arm1.runtime :as rf.bench.hicasso.arm1.runtime]
            [re-frame.bench.hicasso.lane :as rf.bench.hicasso.lane]
            [re-frame.bench.hicasso.shapes.card :as rf.bench.hicasso.shapes.card]
            [re-frame.bench.hicasso.shapes.feed :as rf.bench.hicasso.shapes.feed]
            [re-frame.bench.hicasso.shapes.large-template :as rf.bench.hicasso.shapes.large-template]
            [re-frame.bench.hicasso.shapes.model :as rf.bench.hicasso.shapes.model]
            [re-frame.test-support :as rf.test-support]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rf.bench.hicasso.arm1.runtime/reset-runtime!))}))

(def ^:private frame-id ::shape-bulk)

(def ^:private predicted-boundaries
  "One page boundary plus one per card."
  (inc rf.bench.hicasso.shapes.feed/article-count))

(def ^:private predicted-reads
  "`3` for the page chrome, `2` per card."
  (+ 3 (* 2 rf.bench.hicasso.shapes.feed/article-count)))

(defn- skip! [why]
  (is true (str "shape 3's witness needs a real React DOM — " why)))

(defn- fresh! []
  (rf.bench.hicasso.lane/leave-act-environment!)
  (rf.bench.hicasso.shapes.feed/make-frame! frame-id)
  (rf.bench.hicasso.shapes.feed/reseed! frame-id)
  (rf.bench.hicasso.shapes.feed/reset-runs!)
  frame-id)

(defn- mount! []
  (rf.bench.hicasso.arm1.mount/root! (rf.bench.hicasso.arm1.mount/fresh-container!) frame-id [rf.bench.hicasso.shapes.feed/page {}]))

(defn- q [handle sel] (.querySelector (:container handle) sel))
(defn- q* [handle sel] (array-seq (.querySelectorAll (:container handle) sel)))

(defn- favourite-counts
  "Every card's rendered favourites count, in document order — one read of
  the whole page, so a claim about 300 rows is one assertion."
  [handle]
  (mapv #(.-textContent %) (q* handle "[data-testid^=\"favorites-count-\"]")))

(defn- canonical-card
  "One card's subtree with attribute names sorted. `rf.bench.hicasso.lane/canonical` walks a
  node's CHILDREN, so the card is cloned into a box first — otherwise the
  card's own tag and attributes would sit outside the comparison, which is
  where a class difference would hide."
  [handle slug]
  (let [node (q handle (str "[data-testid=\"article-preview-" slug "\"]"))
        box  (js/document.createElement "div")]
    (.appendChild box (.cloneNode node true))
    (rf.bench.hicasso.lane/canonical box)))

;; ---------------------------------------------------------------------------
;; 1 — the page is the shape
;; ---------------------------------------------------------------------------

(deftest the-page-is-three-hundred-boundaries
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [{:keys [boundaries edges entries]} (rf.bench.hicasso.arm1.runtime/stats)
                measured (rf.bench.hicasso.lane/element-count (:container handle))]
            (is (= predicted-boundaries boundaries)
                (str rf.bench.hicasso.shapes.feed/article-count " card boundaries plus the page = "
                     predicted-boundaries "; measured " boundaries))
            (is (= rf.bench.hicasso.shapes.feed/article-count (count (q* handle ".article-list > .article-preview"))))
            (is (= (rf.bench.hicasso.shapes.feed/element-arithmetic) measured)
                (str "chrome " rf.bench.hicasso.shapes.large-template/chrome-elements " + tags " rf.bench.hicasso.shapes.feed/tag-count " + "
                     rf.bench.hicasso.shapes.feed/article-count " x " rf.bench.hicasso.shapes.card/elements-per-card
                     " = " (rf.bench.hicasso.shapes.feed/element-arithmetic) "; the DOM holds " measured))
            (is (= predicted-reads edges))
            (is (= predicted-boundaries entries)
                "every card reads a read SEQUENCE only it reads, so the entry
                 cache holds one per boundary — the distinct-query rung
                 `runtime/retained-inventory` says it is priced on"))
          (is (= {:cards rf.bench.hicasso.shapes.feed/article-count :page 1} (rf.bench.hicasso.shapes.feed/runs))
              "and the mount ran every card body once and the page's once")
          (finally (rf.bench.hicasso.arm1.mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 + 3 — one commit, three hundred boundaries, and no parent rebuild
;; ---------------------------------------------------------------------------

(deftest one-commit-re-runs-all-three-hundred-card-bodies-and-not-the-page
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [before (favourite-counts handle)]
            (is (= rf.bench.hicasso.shapes.feed/article-count (count before)))
            (rf.bench.hicasso.shapes.feed/reset-runs!)
            (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/refresh-feed])
            (rf.bench.hicasso.arm1.mount/settle!)
            (is (= rf.bench.hicasso.shapes.feed/article-count (:cards (rf.bench.hicasso.shapes.feed/runs)))
                (str "one commit re-ran exactly " rf.bench.hicasso.shapes.feed/article-count " card bodies"))
            (is (= 0 (:page (rf.bench.hicasso.shapes.feed/runs)))
                "and did NOT re-run the page. A list that rebuilt its children
                 would produce the identical DOM below and would not be this
                 shape — this is the assertion that tells the two apart")
            (testing "and every one of the 300 cards moved in the DOM"
              (let [after (favourite-counts handle)]
                (is (= (mapv #(str (inc (js/parseInt % 10))) before) after)
                    "all 300 counts incremented by one"))))
          (finally (rf.bench.hicasso.arm1.mount/release! handle)))))))

(deftest the-broad-write-can-answer-false
  (testing "the comparison above is not passing vacuously: a commit that
           moves nothing the cards read moves no card body and no count"
    (if-not (rf.bench.hicasso.arm1.mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount!)]
          (try
            (let [before (favourite-counts handle)]
              (rf.bench.hicasso.shapes.feed/reset-runs!)
              (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/go-to-page 2])
              (rf.bench.hicasso.arm1.mount/settle!)
              (is (= 0 (:cards (rf.bench.hicasso.shapes.feed/runs))) "no card body ran")
              (is (= 0 (:page (rf.bench.hicasso.shapes.feed/runs))) "and the page reads no page number, so nor did it")
              (is (= before (favourite-counts handle)) "and nothing in the DOM moved"))
            (finally (rf.bench.hicasso.arm1.mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 4 — the same card, both decompositions
;; ---------------------------------------------------------------------------

(deftest the-card-is-byte-identical-to-the-large-templates
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (rf.bench.hicasso.lane/leave-act-environment!)
      (let [slug (rf.bench.hicasso.shapes.feed/slug-at 3)
            ;; Shape 2's page, on its own seed.
            _        (rf.bench.hicasso.shapes.model/make-frame! frame-id rf.bench.hicasso.shapes.large-template/seed)
            _        (rf.bench.hicasso.shapes.model/reseed! frame-id rf.bench.hicasso.shapes.large-template/seed)
            template (rf.bench.hicasso.arm1.mount/root! (rf.bench.hicasso.arm1.mount/fresh-container!) frame-id [rf.bench.hicasso.shapes.large-template/page {}])
            from-template (canonical-card template slug)
            _        (rf.bench.hicasso.arm1.mount/release! template)
            ;; Shape 3's page, on its own.
            _        (rf.bench.hicasso.shapes.model/reseed! frame-id rf.bench.hicasso.shapes.feed/seed)
            feed     (mount! )
            from-feed (canonical-card feed slug)]
        (rf.bench.hicasso.arm1.mount/release! feed)
        (is (= from-template from-feed)
            "the ~1,200-element template and the 300-boundary page build the
             SAME card — so the only difference between the two shapes is
             where `defview` is written")
        (is (re-find #"article-preview" from-template)
            "and the comparison is of a real card, not of two empty strings")))))

;; ---------------------------------------------------------------------------
;; Teardown at three hundred boundaries
;; ---------------------------------------------------------------------------

(deftest three-hundred-boundaries-leave-no-residue
  (if-not (rf.bench.hicasso.arm1.mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (rf.bench.hicasso.arm1.runtime/dispatch! frame-id [:conduit/refresh-feed])
        (rf.bench.hicasso.arm1.mount/settle!)
        (is (pos? (:cell-refs (rf.bench.hicasso.arm1.runtime/stats))))
        (rf.bench.hicasso.arm1.mount/unmount! handle)
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rf.bench.hicasso.arm1.runtime/residue))
                             (str predicted-boundaries " boundaries and "
                                  predicted-reads " edges, all released by React's
                                  own cleanup"))
                         (rf.bench.hicasso.arm1.runtime/reset-runtime!)
                         (done))
                       8)))))
