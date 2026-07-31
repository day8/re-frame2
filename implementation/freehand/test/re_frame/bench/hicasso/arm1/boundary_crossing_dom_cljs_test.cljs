(ns re-frame.bench.hicasso.arm1.boundary-crossing-dom-cljs-test
  "A READ DEFERRED ACROSS A BOUNDARY CROSSING, IN A REAL BROWSER
  (rf2-2rtt6.45).

  `boundary-crossing-cljs-test` settles who the read belongs to, at the
  seam, without React. This file asserts the only thing that finally
  matters about it: **that a later write moves the page.**

  The distinction is the whole point of the bead. The defect's first
  paint is perfect — the values a lazy seq produces are right once it is
  realised, whichever boundary realised them — so any witness that stops
  at the mount passes on the broken runtime as happily as on the fixed
  one. What separated them was what happened next. The read landed on the
  child; a write to a row therefore notified the CHILD; the child
  re-rendered with the same props object and the same already-realised
  `LazySeq`, so `sub` was never called again; its read set collapsed to
  empty, React re-subscribed, and the row edges were dropped with no
  boundary left holding one. The cell painted its original text and no
  later write to any row would ever move it again.

  So both halves are asserted here, and only the second one can fail on a
  correct implementation's behalf:

  1. the first paint is right — which proves nothing on its own;
  2. a write to a row query the PARENT produced reaches the DOM, its
     neighbour is untouched, and the edges are still held afterwards.

  Both carriers are driven: a lazy seq at a boundary's **prop** position,
  and a nested seq in its **children** position, one level below the
  splice `realize-children` performs.

  Runtime: `-dom-cljs-test`; under `:node-test` every claim degrades to a
  stated skip."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.bench.hicasso.arm1.mount :as mount]
            [re-frame.bench.hicasso.arm1.runtime :as rt]
            [re-frame.bench.hicasso.front.dogfood :as dogfood]
            [re-frame.bench.hicasso.lane :as lane]
            [re-frame.test-support :as test-support])
  (:require-macros [re-frame.bench.hicasso.arm1.lang :refer [defview]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter uix-adapter/adapter
     :ambient-frame nil
     :init-fn (fn [] (rt/reset-runtime!))}))

(def ^:private frame-id ::arm1-boundary-crossing-dom)
(def ^:private todo-count 6)

(defn- skip! [why] (is true (str "a boundary-crossing claim needs a real React DOM — " why)))

(defn- fresh! []
  (lane/leave-act-environment!)
  (dogfood/make-frame! frame-id todo-count)
  (dogfood/reseed! frame-id todo-count)
  frame-id)

(defn- cell-text [handle id]
  (some-> (.querySelector (:container handle) (str ".cell[data-id=\"" id "\"]"))
          (.-textContent)))

(defn- cell-count [handle]
  (.-length (.querySelectorAll (:container handle) ".cell")))

(defn- retitle!
  "Move one row's `:dogfood/todo` query, through the arm's own door."
  [id title]
  (rt/dispatch! frame-id [:dogfood/edit-draft id title])
  (mount/settle!)
  (rt/dispatch! frame-id [:dogfood/commit id])
  (mount/settle!)
  nil)

;; ---------------------------------------------------------------------------
;; Carrier (a) — a lazy seq at a boundary's PROP position
;; ---------------------------------------------------------------------------

(defview crossing-child
  "Walks the seq its parent handed it. Every `sub` inside that seq was
  deferred past the parent's body return and is forced HERE, in a render
  that did not write it."
  [{:keys [rows]}]
  [:ul.crossed
   (for [[id title] rows]
     [:li.cell {:key id :data-id id} (str title)])])

(defview crossing-parent
  "Reads the ids eagerly — a `for`'s binding expression always runs — and
  defers every row read into the seq it hands across the crossing."
  [_]
  [crossing-child
   {:rows (for [id (rt/sub [:dogfood/visible-ids])]
            [id (:title (rt/sub [:dogfood/todo id]))])}])

(deftest a-read-deferred-across-a-boundarys-props-still-moves-the-dom
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [crossing-parent {}])]
        (try
          (is (= todo-count (cell-count handle)))
          (is (= "todo 2" (cell-text handle 2))
              "the first paint is right — which is exactly what the broken
               runtime also gives you, and why this row proves nothing alone")
          (let [edges-at-mount (:edges (rt/stats))]
            (retitle! 2 "crossed")
            (is (= "crossed" (cell-text handle 2))
                "**the second half.** A write to a row query the PARENT
                 produced reaches the DOM: the notification went to the
                 boundary that can rebuild the seq, and it did")
            (is (= "todo 1" (cell-text handle 1))
                "while its neighbour is untouched")
            (is (= edges-at-mount (:edges (rt/stats)))
                "and the edges survived the re-render — the failure mode was
                 that the wrong reader re-rendered ONCE, read nothing the
                 second time, and left the row with no edge at all"))
          (testing "and it is not a one-shot correction: the value keeps moving"
            (retitle! 2 "crossed again")
            (is (= "crossed again" (cell-text handle 2))))
          (finally (mount/release! handle)))))))

;; ---------------------------------------------------------------------------
;; Carrier (b) — a nested seq in the CHILDREN position
;; ---------------------------------------------------------------------------

(defview nested-child
  "Receives `(:children props)` as the realized vector the ABI promises,
  and splices it. Each element is still a seq — the one-level flatten is
  unchanged — so this body is the one that walks the inner seqs."
  [{:keys [children]}]
  (into [:ul.nested] children))

(defview nested-parent
  "One trailing form, a `for` of `for`s. `realize-children` forces the
  outer spine and hands each inner seq on as an element."
  [_]
  [nested-child
   (for [chunk (partition-all 2 (rt/sub [:dogfood/visible-ids]))]
     (for [id chunk]
       [:li.cell {:key id :data-id id} (str (:title (rt/sub [:dogfood/todo id])))]))])

(deftest a-read-deferred-into-a-boundarys-nested-children-still-moves-the-dom
  (if-not (mount/browser?)
    (skip! ":node-test has no DOM")
    (do
      (fresh!)
      (let [handle (mount/root! (mount/fresh-container!) frame-id [nested-parent {}])]
        (try
          (is (= todo-count (cell-count handle)))
          (is (= "todo 4" (cell-text handle 4)) "the first paint is right")
          (retitle! 4 "nested")
          (is (= "nested" (cell-text handle 4))
              "a read one level below the children splice belongs to the
               parent too, and its write reaches the DOM")
          (is (= "todo 3" (cell-text handle 3))
              "while its chunk-mate is untouched")
          (finally (mount/release! handle)))))))
