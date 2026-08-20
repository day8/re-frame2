(ns re-frame.hicasso.examples.grid.row-total-layer2-dom-cljs-test
  "L3 — THE LAYER-1 / LAYER-2 CONTRAST THE PER-KEYSTROKE CENSUS NAMES AND
  DECLINES TO CLAIM.

  > The remedy, where one is wanted, is the ordinary one and is not a new
  > mechanism: a derived read stated as a layer-2 subscription over its own
  > inputs is memoised on *those inputs* rather than on app-db, so it does
  > not re-run when they have not moved. Nothing in this corpus has
  > measured that contrast yet, and this page does not claim it — it is
  > named as the next question rather than as an answer.
  >
  > — `docs/design/hicasso/product/per-keystroke.md` §4

  This file measures it. Nothing else about the grid changes: the
  application's `::subs/row-total` is a LAYER-1 reader over the whole of
  `app-db`, so a keystroke re-runs every mounted row's fold and nine of
  the ten at 10x10 recompute a total that did not move. [[row-total-l2]]
  below is the same arithmetic stated as a layer-2 subscription over the
  row's own cells, and the question is what the census reads with that one
  substitution made.

  ## The arm is HERE, and the application is untouched

  `examples.grid.subs` and `examples.grid.views` are byte-identical to
  what they were before this file existed, and rf2-18u0's fence is why:
  *the witness applications model proper re-frame2 and are evidence about
  the public door. If a layer-2 spelling wins, changing `examples/grid` is
  a separate decision with its own bead — this one measures a contrast, it
  does not adopt one.* So the contrast arm lives in the suite, beside
  `grid.scaling-dom-cljs-test`'s coarse anti-shapes, for the same reason
  those do.

  ## Both arms on ONE instrument, in one file

  [[per-keystroke/with-counted-subs]] is the census's own counter, made
  public for this file rather than copied into it. Both arms are measured
  here — the application's layer-1 spelling AND the layer-2 restatement —
  so the contrast is a reading rather than an arithmetic comparison
  between two instruments run in two places. The census's published
  figures (111 at 10x10, 31 at 5x5) are then a CROSS-CHECK on this file's
  layer-1 arm rather than one half of its subtraction.

  ## What the layer-2 spelling costs the author, and it is not nothing

  A `:parametric` sub's `input-fn` is pure in the QUERY VECTOR — it cannot
  read `app-db` — so a row's width has to arrive in the query itself:
  `[::row-total-l2 row cols]`, where the layer-1 spelling was
  `[::subs/row-total row]`. The width is to hand (the row's own body
  already read the dimensions to lay its cells out), but the read is no
  longer addressed by row alone, and a page whose derived read's INPUT SET
  is not knowable from its query vector cannot take this spelling at all.
  That is a real authoring difference and it is reported beside the
  counts rather than under them.

  It buys a property as well as a cost: `input-fn` runs ONCE per cache
  entry at materialisation (`re-frame.subs/build-and-cache!*` — *the
  entry's topology is FIXED for its lifetime*), so `[::row-total-l2 3 10]`
  and `[::row-total-l2 3 12]` are distinct entries and a resize cannot
  leave an entry folding a stale width.

  ## What this file does NOT measure

  A clock, for `budgets.md` §2's reason and the census's: every figure
  here is a monotone counter, so it reads the same on a loaded box as on a
  quiet one. It also does not measure the fold's per-cell work directly —
  what is counted is BODY RUNS, and the `cols` `parseInt`s inside one body
  are read off the body rather than counted by anything."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as uix-adapter]
            [re-frame.core :as rf]
            [re-frame.hicasso :as h]
            [re-frame.hicasso.examples.grid.app :as app]
            [re-frame.hicasso.examples.grid.events :as events]
            [re-frame.hicasso.examples.grid.subs :as subs]
            [re-frame.hicasso.examples.grid.views :as views]
            [re-frame.hicasso.examples.per-keystroke-dom-cljs-test :as census]
            [re-frame.hicasso.test.mounted :as hm]
            [re-frame.test-support :as test-support]))

;; ---------------------------------------------------------------------------
;; The contrast arm — the SAME arithmetic, stated over its own inputs
;; ---------------------------------------------------------------------------

(rf/reg-sub ::row-total-l2
  {:doc "The sum of one row's cells, as a LAYER-2 read over that row's
  cells.

  `examples.grid.subs/row-total` computes exactly this from `app-db`, and
  the difference is which value the memo wrapper compares. A layer-1
  reader is memoised on the whole of `app-db`, which a keystroke always
  moves, so its body runs once per mounted row per keystroke. This one is
  memoised on the `cols` cell values it names, so it runs when one of
  THOSE moved.

  The `cols` in the query vector is not decoration — see the namespace
  docstring. `input-fn` is pure in the query vector and cannot read
  `app-db`, so the row's width has to be told to it."}
  ;; input-fn — pure `query-v -> [query-vector*]`, run once per cache entry.
  (fn [[_ row cols]]
    (mapv (fn [col] [::subs/cell row col]) (range cols)))
  ;; computation fn — a parametric sub is handed a VECTOR of input values.
  (fn [cell-values _]
    (transduce (map (fn [s] (if (seq s) (js/parseInt s 10) 0)))
               +
               0
               cell-values)))

(h/defview row-total-l2
  "`views/row-total` with its subscription restated. Same markup, same
  `.total` class, same `data-total` — so the same selector reads it and
  the arms differ in exactly one expression."
  [{:keys [row cols]}]
  [:td.total {:data-total (str row)} (str (h/sub [::row-total-l2 row cols]))])

(h/defview grid-row-l2
  "`views/grid-row` with [[row-total-l2]] in place of `views/row-total`.

  The cells are the APPLICATION's `views/cell`, required and used
  unchanged: the contrast is one subscription's spelling, and a re-typed
  cell would put a second difference into a two-arm measurement."
  [{:keys [row]}]
  (let [{:keys [cols]} (h/sub [::subs/dimensions])]
    [:tr {:data-row (str row)}
     (for [col (range cols)]
       [views/cell {:key (str col) :row row :col col}])
     [row-total-l2 {:key "total" :row row :cols cols}]]))

(h/defview grid-l2
  "`views/grid` over [[grid-row-l2]]."
  [_]
  (let [{:keys [rows]} (h/sub [::subs/dimensions])]
    [:main#hundred-cell-grid
     [:h1 "Grid"]
     [:table
      [:tbody
       (for [row (range rows)]
         [grid-row-l2 {:key (str row) :row row}])]]]))

;; The fixture snapshots the registrar where THIS form is evaluated, so it
;; sits below the registration above — a `use-fixtures` at the top of the
;; file would strand `::row-total-l2` and every layer-2 mount would refuse.
;; Same reason `grid.scaling-dom-cljs-test` puts its own here.
(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter       uix-adapter/adapter
     :ambient-frame nil}))

;; ---------------------------------------------------------------------------
;; The instrument — the census's counter, plus a keystroke
;; ---------------------------------------------------------------------------

(defn- browser? []
  (and (exists? js/document) (some? js/document) (some? (.-body js/document))))

(defn- skip! [why]
  (is true (str "a mounted React root needs a real DOM — " why)))

(def ^:private counted-sub-ids
  "Both arms count the same four ids, so an arm that never ran its own
  total shows up as an ABSENT key rather than as a smaller sum."
  [::subs/cell ::subs/dimensions ::subs/row-total ::row-total-l2])

(defn- grid-node [m row col]
  (.querySelector (:container m) (str "#" (events/cell-id row col))))

(defn- total-text [m row]
  (.-textContent (.querySelector (:container m) (str "[data-total='" row "']"))))

(defn- set-native-value! [n v]
  (let [d (js/Object.getOwnPropertyDescriptor js/HTMLInputElement.prototype "value")]
    (.call (.-set d) n v)))

(defn- type-into! [n text]
  (set-native-value! n (str (.-value n) text))
  (.dispatchEvent n (js/Event. "input" #js {:bubbles true}))
  nil)

(defn- census-of
  "Mount `form` at `dimensions`, type one accepted digit into `[3 4]`, and
  answer the reading.

  `:total-before` / `:total-after` are the row's rendered total either
  side of the keystroke, and they are the arm's POSITIVE CONTROL: a
  layer-2 body run count of one is only interesting if that one run
  produced the new number. A total that did not move would make the count
  a measure of a dead subscription."
  [form dimensions]
  (census/with-counted-subs counted-sub-ids
    (fn [sub-runs reset-subs!]
      (let [m (hm/mount! form {:initial-events (app/initial-events dimensions)})
            n (grid-node m 3 4)]
        (hm/settle! m)
        (let [before (total-text m 3)]
          (reset-subs!)
          (let [bodies (hm/bodies-run (fn [] (type-into! n "1") (hm/settle! m)))
                runs   (sub-runs)]
            (try
              {:sub-runs     (census/total runs)
               :by-sub       runs
               :bodies       bodies
               :total-before before
               :total-after  (total-text m 3)
               :cell-value   (.-value (grid-node m 3 4))}
              (finally (hm/unmount! m)))))))))

(def ^:private small {:rows 5 :cols 5})
(def ^:private full {:rows 10 :cols 10})

;; ---------------------------------------------------------------------------
;; The restatement is FAITHFUL before it is fast
;; ---------------------------------------------------------------------------

(deftest the-layer-2-total-renders-what-the-layer-1-total-renders
  ;; The premise. A cheaper subscription that answers a different number
  ;; is not a contrast, it is a bug, and every count below would be
  ;; measuring the wrong thing.
  (if-not (browser?)
    (skip! "the faithfulness premise")
    (let [l1 (hm/mount! [views/grid {}] {:initial-events (app/initial-events full)})
          l2 (hm/mount! [grid-l2 {}] {:initial-events (app/initial-events full)})]
      (is (= (mapv #(total-text l1 %) (range 10))
             (mapv #(total-text l2 %) (range 10)))
          "every row's total, both spellings, at 10x10")
      (is (= "345" (total-text l1 3))
          "and the value is the seeded row's real sum (30+31+…+39), so the
           row above is not two spellings agreeing on nil")
      (hm/unmount! l1)
      (hm/unmount! l2))))

;; ---------------------------------------------------------------------------
;; The contrast
;; ---------------------------------------------------------------------------

(deftest the-layer-2-row-total-recomputes-once-where-the-layer-1-one-recomputes-per-row
  (if-not (browser?)
    (skip! "the contrast")
    (let [l1-100 (census-of [views/grid {}] full)
          l2-100 (census-of [grid-l2 {}] full)
          l1-25  (census-of [views/grid {}] small)
          l2-25  (census-of [grid-l2 {}] small)]

      (testing "the arm is live — the one run produced the new number"
        ;; Before the counts, because a count of 1 that recomputed nothing
        ;; would read exactly the same.
        (is (= ["345" "652"] [(:total-before l2-100) (:total-after l2-100)])
            "row 3 seeds as 30+31+…+39 = 345; the keystroke turns cell
             [3 4] from `34` into `341`, so the total becomes
             345 - 34 + 341 = 652. The single layer-2 body run counted
             below is therefore a real recompute of a real new value, and
             not a subscription that has stopped firing")
        (is (= ["345" "652"] [(:total-before l1-100) (:total-after l1-100)])
            "and the layer-1 arm answers the same, either side")
        (is (= "341" (:cell-value l2-100))
            "and the accepted digit is on the glass in the layer-2 arm too")
        (is (= 2 (:bodies l1-100) (:bodies l2-100) (:bodies l1-25) (:bodies l2-25))
            "AND THE BOUNDARY COUNT DID NOT MOVE — two bodies, both arms,
             both sizes: the cell that was typed into and its row's total.
             The contrast is about which SUBSCRIPTION BODIES re-run, and
             this row is what says the layer-2 spelling bought that
             without changing what the page re-renders. A restatement
             that had broadened notification would read higher here and a
             dead one would read lower, and either would make the
             recomputation saving below meaningless."))

      (testing "the layer-1 arm — the census's own figures, re-read here"
        (is (= {::subs/cell 100 ::subs/row-total 10 ::subs/dimensions 1}
               (:by-sub l1-100))
            "10x10: every mounted cell, every row total, and the
             dimensions cell that nothing moved. Identical to
             `examples.per-keystroke-dom-cljs-test`'s attribution, taken
             on the same counter — so this file's subtraction is between
             two readings it took itself")
        (is (= 111 (:sub-runs l1-100)))
        (is (= {::subs/cell 25 ::subs/row-total 5 ::subs/dimensions 1}
               (:by-sub l1-25)))
        (is (= 31 (:sub-runs l1-25))))

      (testing "the layer-2 arm — the row-total term collapses to ONE"
        (is (= {::subs/cell 100 ::row-total-l2 1 ::subs/dimensions 1}
               (:by-sub l2-100))
            "10x10: ONE row-total body where the layer-1 spelling ran ten.
             The nine that did not run are the nine rows whose cells did
             not move — the layer-2 memo compares those `cols` cell VALUES
             and finds them `=`, where the layer-1 memo compares the whole
             of `app-db` and finds it moved")
        (is (= 102 (:sub-runs l2-100)))
        (is (= {::subs/cell 25 ::row-total-l2 1 ::subs/dimensions 1}
               (:by-sub l2-25))
            "and at 5x5 the same ONE — the term is flat in the grid, where
             the layer-1 spelling's was linear in `rows`")
        (is (= 27 (:sub-runs l2-25))))

      (testing "the contrast, stated as the two shapes"
        (is (= [10 5] [(::subs/row-total (:by-sub l1-100))
                       (::subs/row-total (:by-sub l1-25))])
            "layer-1 row-total runs = rows: 10 at 10x10, 5 at 5x5 — it
             GROWS with the page")
        (is (= [1 1] [(::row-total-l2 (:by-sub l2-100))
                      (::row-total-l2 (:by-sub l2-25))])
            "layer-2 row-total runs = 1 at both sizes — it does NOT")

        (is (= 100 (::subs/cell (:by-sub l1-100)) (::subs/cell (:by-sub l2-100)))
            "AND THE LINEAR TERM SURVIVES, which is the half of this
             measurement a reader is likeliest to misread. `::cell` is a
             layer-1 reader in BOTH arms and there are a hundred of them
             mounted, so a hundred bodies run either way. What the layer-2
             spelling removes is the row-total term and nothing else.")
        (is (= [9 4] [(- (:sub-runs l1-100) (:sub-runs l2-100))
                      (- (:sub-runs l1-25) (:sub-runs l2-25))])
            "so the whole saving is nine recomputations out of 111 at
             10x10 (111 -> 102) and four out of 31 at 5x5 (31 -> 27):
             `rows - 1`, exactly the row totals that were recomputing a
             value that had not moved. Stated as a fraction it is 8% and
             13% of the census's number, and stated as a shape it is the
             difference between a term that grows with the page and one
             that does not — which is why the fraction is the less
             interesting of the two readings.")))))
