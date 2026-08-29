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
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.bench.hicasso.shapes.card :as card]
            [re-frame.bench.hicasso.shapes.feed :as shape]
            [re-frame.bench.hicasso.shapes.large-template :as lt]
            [re-frame.bench.hicasso.shapes.model :as m]
            [re-frame.test-support :as test-support]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::shape-bulk)

(def ^:private predicted-boundaries
  "One page boundary plus one per card."
  (inc shape/article-count))

(def ^:private predicted-reads
  "`3` for the page chrome, `2` per card."
  (+ 3 (* 2 shape/article-count)))

(defn- skip! [why]
  (is true (str "shape 3's witness needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (shape/make-frame! frame-id)
  (shape/reseed! frame-id)
  (shape/reset-runs!)
  frame-id)

(defn- mount! []
  (mount/root! (mount/fresh-container!) frame-id [shape/page {}]))

(defn- q [handle sel] (.querySelector (:container handle) sel))
(defn- q* [handle sel] (array-seq (.querySelectorAll (:container handle) sel)))

(defn- favourite-counts
  "Every card's rendered favourites count, in document order — one read of
  the whole page, so a claim about 300 rows is one assertion."
  [handle]
  (mapv #(.-textContent %) (q* handle "[data-testid^=\"favorites-count-\"]")))

(defn- canonical-card
  "One card's subtree with attribute names sorted. `lane/canonical` walks a
  node's CHILDREN, so the card is cloned into a box first — otherwise the
  card's own tag and attributes would sit outside the comparison, which is
  where a class difference would hide."
  [handle slug]
  (let [node (q handle (str "[data-testid=\"article-preview-" slug "\"]"))
        box  (js/document.createElement "div")]
    (.appendChild box (.cloneNode node true))
    (lane/canonical box)))

;; ---------------------------------------------------------------------------
;; 1 — the page is the shape
;; ---------------------------------------------------------------------------

(deftest the-page-is-three-hundred-boundaries
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [{:keys [boundaries edges entries]} (rt/stats)
                measured (lane/element-count (:container handle))]
            (is (= predicted-boundaries boundaries)
                (str shape/article-count " card boundaries plus the page = "
                     predicted-boundaries "; measured " boundaries))
            (is (= shape/article-count (count (q* handle ".article-list > .article-preview"))))
            (is (= (shape/element-arithmetic) measured)
                (str "chrome " lt/chrome-elements " + tags " shape/tag-count " + "
                     shape/article-count " x " card/elements-per-card
                     " = " (shape/element-arithmetic) "; the DOM holds " measured))
            (is (= predicted-reads edges))
            (is (= predicted-boundaries entries)
                "every card reads a read SEQUENCE only it reads, so the entry
                 cache holds one per boundary — the distinct-query rung
                 `runtime/retained-inventory` says it is priced on"))
          (is (= {:cards shape/article-count :page 1} (shape/runs))
              "and the mount ran every card body once and the page's once")
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; 2 + 3 — one commit, three hundred boundaries, and no parent rebuild
;; ---------------------------------------------------------------------------

(deftest one-commit-re-runs-all-three-hundred-card-bodies-and-not-the-page
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount!)]
        (try
          (let [before (favourite-counts handle)]
            (is (= shape/article-count (count before)))
            (shape/reset-runs!)
            (rt/dispatch! frame-id [:conduit/refresh-feed])
            (mount/settle!)
            (is (= shape/article-count (:cards (shape/runs)))
                (str "one commit re-ran exactly " shape/article-count " card bodies"))
            (is (= 0 (:page (shape/runs)))
                "and did NOT re-run the page. A list that rebuilt its children
                 would produce the identical DOM below and would not be this
                 shape — this is the assertion that tells the two apart")
            (testing "and every one of the 300 cards moved in the DOM"
              (let [after (favourite-counts handle)]
                (is (= (mapv #(str (inc (js/parseInt % 10))) before) after)
                    "all 300 counts incremented by one"))))
          (finally (mount/release! handle)))))))

(deftest the-broad-write-can-answer-false
  (testing "the comparison above is not passing vacuously: a commit that
           moves nothing the cards read moves no card body and no count"
    (if-not (mount/browser?)
      (skip! ":node-test has no DOM")
      (do
        (fresh!)
        (let [handle (mount!)]
          (try
            (let [before (favourite-counts handle)]
              (shape/reset-runs!)
              (rt/dispatch! frame-id [:conduit/go-to-page 2])
              (mount/settle!)
              (is (= 0 (:cards (shape/runs))) "no card body ran")
              (is (= 0 (:page (shape/runs))) "and the page reads no page number, so nor did it")
              (is (= before (favourite-counts handle)) "and nothing in the DOM moved"))
            (finally (mount/release! handle))))))))

;; ---------------------------------------------------------------------------
;; 4 — the same card, both decompositions
;; ---------------------------------------------------------------------------

(deftest the-card-is-byte-identical-to-the-large-templates
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (lane/leave-act-environment!)
      (let [slug (shape/slug-at 3)
            ;; Shape 2's page, on its own seed.
            _        (m/make-frame! frame-id lt/seed)
            _        (m/reseed! frame-id lt/seed)
            template (mount/root! (mount/fresh-container!) frame-id [lt/page {}])
            from-template (canonical-card template slug)
            _        (mount/release! template)
            ;; Shape 3's page, on its own.
            _        (m/reseed! frame-id shape/seed)
            feed     (mount! )
            from-feed (canonical-card feed slug)]
        (mount/release! feed)
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
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (async done
      (fresh!)
      (let [handle (mount!)]
        (rt/dispatch! frame-id [:conduit/refresh-feed])
        (mount/settle!)
        (is (pos? (:cell-refs (rt/stats))))
        (mount/unmount! handle)
        (js/setTimeout (fn []
                         (is (= {:cells 0 :cell-refs 0 :boundaries 0 :edges 0 :entries 0}
                                (rt/residue))
                             (str predicted-boundaries " boundaries and "
                                  predicted-reads " edges, all released by React's
                                  own cleanup"))
                         (rt/reset-runtime!)
                         (done))
                       8)))))
