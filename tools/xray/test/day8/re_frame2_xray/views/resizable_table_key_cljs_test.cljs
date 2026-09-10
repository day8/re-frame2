(ns day8.re-frame2-xray.views.resizable-table-key-cljs-test
  "rf2-fcy5 — the shared resizable-table's React keys must ride the
  ATTRIBUTE MAP, not Clojure metadata.

  ## What is being pinned, and why it is not the rf2-hxfy defect

  rf2-hxfy fixed `^{:key …}` sitting on a CALL form, where the reader
  meta attaches to the source list and the returned value carries none
  of it — those keys reached React on NO substrate. These seven sites
  are the OTHER half of the class: `with-meta` on a vector LITERAL,
  which Reagent DOES honour (`reagent.impl.template` reads meta first,
  the props map second). So they work today, and moving them is a no-op
  today.

  They stop working, silently, the moment this shared widget renders
  under a Fresco boundary. `re-frame.fresco.impl.codec`'s component-ABI
  table (HD-016) takes a literal `:key` from the ATTRIBUTE MAP for every
  head kind it accepts — native tag, `defview` boundary, host, fragment
  — and the words `meta` / `with-meta` do not occur anywhere in that
  file. Both consumer panels (`panels/trace.cljs`,
  `panels/epoch/view.cljs`) would lose every row key and every header
  cell key with no error and no warning, leaving React to reconcile by
  position.

  ## TWO renderers, because ONE of them cannot see this defect

  Every key row below reads BOTH doors:

    - `reagent-key` — `(.-key (r/as-element node))`, today's substrate.
      This is the rf2-hxfy instrument, and against THIS defect it is
      HOLLOW BY ITSELF: Reagent honours meta AND props, so it returns
      the same key before and after the change. It is kept because it
      is the half that pins the change as a NO-OP today.

    - `fresco-key` — `(.-key (rf.fresco.impl.codec/as-element node))`,
      the codec's own hiccup→element door, which reads what a Fresco
      boundary would actually commit. This is the half that goes RED
      on a revert to `with-meta`, and so the half that makes these rows
      a gate rather than a description.

  Asserting on `(:key (meta node))` would be hollow in both directions
  — it passes on metadata Fresco reads nowhere, and FAILS on an
  attrs-map key that works perfectly. `meta-is-not-where-the-key-lives`
  states that as an executable claim instead of a comment.

  ## The gutter IS graded by the codec now (rf2-fcy5 slice 1b)

  It was not, at slice 1: `[header-gutter {…}]` was a plain `defn` in
  head position, which the codec grades `:invalid` and refuses outright,
  so its key could only be read at the Reagent door and out of the props
  map. Slice 1b retired the Form-2 hover Ratom that made it a component
  at all — the hover paint is a CSS rule keyed on the gutter's own
  `data-testid` — so `header-gutter` is now a pure fn CALLED like
  `body-spacer`, there is no head to refuse, and the gutter's key rides
  the emitted div's attrs map like every other woven node. It is graded
  at BOTH doors below.

  ## The helper's three cases

  `weave-header` / `weave-body` key a `cell` the CONSUMER built, whose
  second element may be an attrs map, a string, a nested vector, or
  absent — so the obvious `(assoc-in cell [1 :key] k)` would REPLACE a
  child with a map and silently delete it. `consumer-cell-*` drives all
  five shapes through `:row-cells` and asserts both that the key reaches
  React and that the cell's own children SURVIVE."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            ;; rf2-fcy5 slice 1b — the gutter's hover paint is a global CSS
            ;; rule now, so its selector is part of this widget's contract
            ;; and is pinned here beside the markup it selects on.
            [day8.re-frame2-xray.theme.global-styles :as gs]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

;; ---- fixture ------------------------------------------------------------

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture
    {:post-reset (fn []
                   (rt/clear!)
                   (rt/set-storage-key! nil))}))

(defn- xray-setup!
  "Register Xray handlers + the `:rf/xray` frame. Mirrors
  `resizable_table_persistence_cljs_test`'s helper of the same name."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

;; ---- the two renderer doors ---------------------------------------------

(defn- reagent-key
  "The key Reagent hands React. Honours meta AND props, so this alone
  cannot see the defect — see the ns docstring."
  [node]
  (.-key (r/as-element node)))

(defn- fresco-key
  "The key a Fresco boundary would commit. `as-element` is the codec's
  own hiccup→element door, so this reads what the substrate commits
  rather than what the hiccup happens to be carrying (the instrument
  `cancellation_cascade_cljs_test/emitted-key` uses). Reads nil for a
  meta-only key, which is the whole point."
  [node]
  (.-key (rf.fresco.impl.codec/as-element node)))

;; ---- structural navigation ----------------------------------------------
;;
;; Navigated by INDEX rather than through `rf.test-helpers/expand-tree`:
;; that helper rebuilds nested vectors with `mapv`, which would rewrite
;; the very vectors under test. Each step asserts the landmark it expects
;; so a layout drift fails loudly here instead of turning a key assertion
;; into a confusing nil.

(defn- render
  "Render the shared widget for `opts` inside the `:rf/xray` frame."
  [opts]
  (rf/with-frame :rf/xray
    (rt/resizable-table opts)))

(defn- container-children
  "The container's children: header hiccup first (nil when `:header?
  false`), body rows after it."
  [tree]
  (is (vector? tree) "the view returned a hiccup vector")
  (is (= :div (nth tree 0)) "container is a :div")
  (is (some? (:data-rf-xray-resizable-table (nth tree 1)))
      "structural navigation landed on the resizable-table container")
  (vec (drop 2 tree)))

(defn- header-woven
  "The (2N-1) woven header nodes — header cells interleaved with gutters."
  [tree]
  (let [header (first (container-children tree))]
    (is (vector? header) "header hiccup is present")
    (is (= "grid" (get-in header [1 :style :display]))
        "header carries the grid layout")
    (vec (drop 2 header))))

(defn- body-rows
  "The body-row wrapper nodes."
  [tree]
  (vec (rest (container-children tree))))

(defn- row-woven
  "The (2N-1) woven cell/spacer nodes inside one DEFAULT-path body row.
  That row is `[:div attrs woven]` and `woven` is a `[:<> …]` fragment,
  so the nodes start at index 1 of the fragment."
  [row]
  (let [woven (nth row 2)]
    (is (= :<> (nth woven 0)) "row cells are wrapped in a fragment")
    (vec (rest woven))))

;; ---- fixtures for the widget --------------------------------------------

(def ^:private three-columns
  [{:id :a :label "a" :default-flex "1fr"}
   {:id :b :label "b" :default-flex "1fr"}
   {:id :c :label "c" :default-flex "1fr"}])

(defn- three-column-opts
  [extra]
  (merge {:table-id  :rf.xray.test/keys
          :columns   three-columns
          :rows      [{:v 1} {:v 2}]
          :row-key   (fn [_row i] (str "row-" i))
          :row-attrs (fn [_row i] {:data-testid (str "r-" i)})
          :row-cells (fn [row _i]
                       (for [col three-columns]
                         [:div {:data-col (name (:id col))} (str (:v row))]))}
         extra))

;; ---- (1) header cells reach BOTH renderers with keys ---------------------

(deftest header-cells-reach-both-renderers-with-keys
  (testing "rf2-fcy5 — the three header cells carry their key where BOTH
            substrates look. The Fresco reading is the one that goes red
            on a revert to `with-meta`; the Reagent reading pins the
            change as a no-op today."
    (xray-setup!)
    (let [woven (header-woven (render (three-column-opts {})))
          cells (vec (take-nth 2 woven))]
      (is (= 5 (count woven)) "3 header cells interleaved with 2 gutters")
      (is (= ["h-a" "h-b" "h-c"] (mapv reagent-key cells))
          "REAGENT — unchanged by this fix, and that is the point")
      (is (= ["h-a" "h-b" "h-c"] (mapv fresco-key cells))
          "FRESCO — reads nil for every one of these under `with-meta`")
      (is (= 3 (count (distinct (mapv fresco-key cells))))
          "sibling keys are distinct"))))

;; ---- (2) gutters: a CALLED pure fn, graded at BOTH doors ----------------

(deftest header-gutters-reach-both-renderers-with-keys
  (testing "rf2-fcy5 slice 1b — the gutter is no longer a head. Retiring
            its Form-2 hover Ratom made `header-gutter` a pure fn, so
            `weave-header` CALLS it and what lands in the woven seq is the
            gutter's own `[:div …]`, keyed in its attrs map and gradeable
            at the Fresco door like every other node. At slice 1 this row
            could only read the Reagent door and the props map."
    (xray-setup!)
    (let [woven   (header-woven (render (three-column-opts {})))
          gutters (vec (take-nth 2 (rest woven)))]
      (is (= 2 (count gutters)) "N-1 gutters for N columns")
      (is (every? #(= :div (nth % 0)) gutters)
          "a CALLED pure fn — the node is a native div, not a component
           call form the codec would grade `:invalid`")
      (is (= ["g-a" "g-b"] (mapv reagent-key gutters)) "REAGENT")
      (is (= ["g-a" "g-b"] (mapv fresco-key gutters))  "FRESCO")
      (is (= ["rf-xray-resizable-gutter-keys-a"
              "rf-xray-resizable-gutter-keys-b"]
             (mapv #(:data-testid (nth % 1)) gutters))
          "the stable testid survives — it is what the CSS hover rule
           selects on, so the widget's affordance rides on this string")
      (is (every? #(fn? (:on-pointer-down (nth % 1))) gutters)
          "the drag affordance survives the key injection")
      (is (every? #(and (not (contains? (nth % 1) :on-pointer-enter))
                        (not (contains? (nth % 1) :on-pointer-leave)))
                  gutters)
          "THE RETIREMENT ROW — the hover handlers the Ratom needed are
           gone; the paint is CSS now"))))

;; ---- (2b) the hover paint is stateless, and the CSS is its other half ---

(deftest gutter-carries-no-hover-state
  (testing "rf2-fcy5 slice 1b — the gutter renders ONE style regardless of
            pointer state, because there is no state left to render from.
            Two independent renders of the same table produce byte-equal
            gutter nodes bar their handler identities; the style map is
            the transparent base, never an accent fill."
    (xray-setup!)
    (let [style-of (fn [] (mapv #(:style (nth % 1))
                                (take-nth 2 (rest (header-woven
                                                    (render (three-column-opts {})))))))
          a (style-of)
          b (style-of)]
      (is (= a b) "the same style on every render — nothing to toggle")
      (is (every? #(= "transparent" (:background %)) a)
          "the base state is transparent; the accent is the CSS rule's")
      (is (every? #(= "col-resize" (:cursor %)) a)
          "the always-visible affordance signal stays inline"))))

(deftest global-styles-paints-the-gutter-hover
  (testing "rf2-fcy5 slice 1b — the OTHER half of the retirement. The
            hover affordance is now a global CSS rule keyed on the
            gutter's `data-testid` prefix, so this row pins the selector,
            the accent token, and the `!important` WITHOUT which the
            gutter's inline `background: transparent` would win and the
            handle would never light up. Nothing else in the tree can see
            a broken CSS rule — no gate renders CSS."
    (let [css @#'gs/motion-css]
      (is (string? css))
      (is (str/includes? css "[data-testid^=\"rf-xray-resizable-gutter-\"]:hover")
          "the selector matches the prefix `header-gutter` stamps")
      (is (str/includes? css
                         (str "[data-testid^=\"rf-xray-resizable-gutter-\"]:hover {\n"
                              "  background: var(--rf-xray-accent) !important;\n"
                              "}\n"))
          "accent token + !important — the inline `background: transparent`
           beats a stylesheet rule without it")
      (is (str/includes? css "[data-testid^=\"rf-xray-event-list-col-divider-\"]:hover")
          "the sibling rule this one was modelled on is still there — a
           control, so a broken read of `motion-css` cannot pass this
           test vacuously"))))

;; ---- (3) body cells + spacers reach BOTH renderers with keys ------------

(deftest body-cells-and-spacers-reach-both-renderers-with-keys
  (testing "rf2-fcy5 — every woven body node (3 cells + 2 spacers) on
            every row carries its key in the attrs map."
    (xray-setup!)
    (let [rows (body-rows (render (three-column-opts {})))]
      (is (= 2 (count rows)) "two body rows")
      (doseq [row rows]
        (let [woven (row-woven row)]
          (is (= 5 (count woven)) "3 cells interleaved with 2 spacers")
          (is (= ["c-a" "s-a" "c-b" "s-b" "c-c"] (mapv reagent-key woven))
              "REAGENT")
          (is (= ["c-a" "s-a" "c-b" "s-b" "c-c"] (mapv fresco-key woven))
              "FRESCO — all nil under `with-meta`")
          (is (= 5 (count (distinct (mapv fresco-key woven))))
              "sibling keys are distinct"))))))

;; ---- (4) row wrappers reach BOTH renderers with the consumer's key ------

(deftest row-wrappers-reach-both-renderers-with-keys
  (testing "rf2-fcy5 — the row weaver's own key (the consumer's
            `:row-key`) reaches both doors, on the default path AND on
            the `:row-extras` path, which builds a different wrapper."
    (xray-setup!)
    (let [plain  (body-rows (render (three-column-opts {})))
          extras (body-rows (render (three-column-opts
                                      {:row-extras (fn [_row i]
                                                     [:div (str "extra-" i)])})))]
      (is (= ["row-0" "row-1"] (mapv reagent-key plain)) "default path, REAGENT")
      (is (= ["row-0" "row-1"] (mapv fresco-key plain))  "default path, FRESCO")
      (is (= ["row-0" "row-1"] (mapv reagent-key extras)) "extras path, REAGENT")
      (is (= ["row-0" "row-1"] (mapv fresco-key extras))  "extras path, FRESCO")
      (is (= "r-0" (:data-testid (nth (first extras) 1)))
          "the consumer's row attrs survive the key injection"))))

;; ---- (5) the helper's three cases, driven through the public view -------

(def ^:private shape-columns
  [{:id :attrs  :label "attrs"}
   {:id :bare   :label "bare"}
   {:id :string :label "string"}
   {:id :nested :label "nested"}
   {:id :novec  :label "novec"}])

(defn- shape-cells
  "One cell per shape the helper must handle:
     :attrs  — a vector WITH an attrs map
     :bare   — a vector with no second element at all
     :string — a vector whose second element is a string
     :nested — a vector whose second element is a nested vector
     :novec  — not a vector at all"
  [_row _i]
  [[:div {:data-testid "with-attrs"} "A"]
   [:div]
   [:span "txt"]
   [:div [:span "nested"]]
   "bare string"])

(defn- shape-tree []
  (render {:table-id  :rf.xray.test/shapes
           :columns   shape-columns
           :rows      [{:v 1}]
           :row-key   (fn [_row i] (str "row-" i))
           :row-cells shape-cells}))

(deftest consumer-cell-shapes-reach-react-with-keys
  (testing "rf2-fcy5 — `:row-cells` is the CONSUMER's fn, so a woven cell
            may or may not carry an attrs map. Every VECTOR shape reaches
            both renderers with its key; the non-vector is returned
            untouched, because it has no attrs map to reach and wrapping
            one round it would change the consumer's markup."
    (xray-setup!)
    (let [cells (vec (take-nth 2 (row-woven (first (body-rows (shape-tree))))))]
      (is (= 5 (count cells)) "five cells, one per column")
      (is (= ["c-attrs" "c-bare" "c-string" "c-nested"]
             (mapv reagent-key (subvec cells 0 4)))
          "REAGENT — every vector shape, attrs map or not")
      (is (= ["c-attrs" "c-bare" "c-string" "c-nested"]
             (mapv fresco-key (subvec cells 0 4)))
          "FRESCO — every vector shape, attrs map or not")
      (is (= "bare string" (nth cells 4))
          "a non-vector cell is returned untouched"))))

(deftest consumer-cell-children-survive-the-key-injection
  (testing "rf2-fcy5 — the guard against `(assoc-in cell [1 :key] k)`. On
            `[:span \"txt\"]` that expression REPLACES the string child
            with a map and the text vanishes with nothing thrown. These
            rows pin that each shape's own children survive."
    (xray-setup!)
    (let [cells (vec (take-nth 2 (row-woven (first (body-rows (shape-tree))))))]
      (is (= [:div {:data-testid "with-attrs" :key "c-attrs"} "A"]
             (nth cells 0))
          "an existing attrs map is preserved and merely gains :key")
      (is (= [:div {:key "c-bare"}] (nth cells 1))
          "a vector with no attrs map gains one")
      (is (= [:span {:key "c-string"} "txt"] (nth cells 2))
          "THE REGRESSION ROW — the string child survives; assoc-in would
           have deleted it")
      (is (= [:div {:key "c-nested"} [:span "nested"]] (nth cells 3))
          "a nested-vector child survives and is not mistaken for attrs"))))

;; ---- (6) the hollow-gate claim, stated executably -----------------------

(deftest meta-is-not-where-the-key-lives
  (testing "rf2-fcy5 — the reason no row above asserts on `(meta …)`.
            After this change the key rides the attrs map, so `(meta
            node)` carries NO key while both renderers receive one. A
            metadata assertion would therefore FAIL on correct code —
            and, before the change, PASSED on keys Fresco reads nowhere.
            Hollow in both directions."
    (xray-setup!)
    (let [cell (first (header-woven (render (three-column-opts {}))))]
      (is (= "h-a" (reagent-key cell)) "Reagent receives the key")
      (is (= "h-a" (fresco-key cell))  "Fresco receives the key")
      (is (nil? (:key (meta cell)))
          "…and Clojure metadata does not carry it — so a `(meta …)`
           assertion would be a hollow gate here")
      (is (= "h-a" (:key (nth cell 1)))
          "the key lives in the attrs map, the one place BOTH Reagent
           and Fresco's codec look"))))
