(ns day8.re-frame2-xray.views.resizable-table-key-cljs-test
  "The shared resizable-table's React keys must ride the ATTRIBUTE MAP,
  not Clojure metadata.

  Reagent honours a `with-meta` key on a vector literal, but a Fresco
  boundary does not: `re-frame.fresco.impl.codec`'s component-ABI table
  (HD-016) takes a literal `:key` from the attribute map for every head
  kind it accepts and reads metadata nowhere. Both consumer panels
  (`panels/trace.cljs`, `panels/epoch/view.cljs`) are boundaries, so a
  metadata key would leave every row and header cell unkeyed, with no
  error and no warning.

  So each key row reads `(.-key (rf.fresco.impl.codec/as-element node))`,
  the codec's own hiccup→element door, which reads nil for a
  metadata-only key; `(:key (meta node))` would instead pass on a key
  Fresco never sees. The widget renders through the Reagent head, the
  only public pure-render door into the private `render-table`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.fresco.impl.codec :as rf.fresco.impl.codec]
            [day8.re-frame2-xray.registry :as registry]
            [day8.re-frame2-xray.test-support :as xray-test-support]
            ;; The gutter's hover paint is a global CSS rule, so its
            ;; selector is part of this widget's contract.
            [day8.re-frame2-xray.theme.global-styles :as gs]
            [day8.re-frame2-xray.views.resizable-table :as rt]))

(use-fixtures :each
  (xray-test-support/make-xray-runtime-fixture))

(defn- xray-setup!
  "Register Xray handlers + the `:rf/xray` frame."
  []
  (registry/register-xray-handlers!)
  (rf/make-frame {:id :rf/xray}))

(defn- fresco-key
  "The key a Fresco boundary would commit for `node`."
  [node]
  (.-key (rf.fresco.impl.codec/as-element node)))

;; ---- structural navigation ----------------------------------------------
;;
;; Navigated by INDEX rather than through `rf.test-helpers/expand-tree`:
;; that helper rebuilds nested vectors with `mapv`, which would rewrite
;; the very vectors under test. The container is
;; `[:div attrs header row…]`.

(defn- render
  "Render the shared widget for `opts` inside the `:rf/xray` frame."
  [opts]
  (rf/with-frame :rf/xray
    (rt/resizable-table opts)))

(defn- header-woven
  "The (2N-1) woven header nodes — header cells interleaved with gutters."
  [tree]
  (vec (drop 2 (nth tree 2))))

(defn- body-rows
  "The body-row wrapper nodes."
  [tree]
  (vec (drop 3 tree)))

(defn- row-woven
  "The woven cell/spacer nodes of one default-path body row,
  `[:div attrs [:<> …]]`."
  [row]
  (vec (rest (nth row 2))))

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

;; ---- header cells and gutters -------------------------------------------

(deftest header-cells-and-gutters-reach-the-codec-with-keys
  (testing "header cells and the gutters between them carry their keys in
            the attrs map. The gutter is a pure fn `weave-header` CALLS, so
            it lands as a native div the codec can grade; as a component
            call form `[header-gutter {…}]` the codec would refuse it."
    (xray-setup!)
    (let [woven   (header-woven (render (three-column-opts {})))
          gutters (take-nth 2 (rest woven))]
      (is (= ["h-a" "g-a" "h-b" "g-b" "h-c"] (mapv fresco-key woven)))
      (is (= ["rf-xray-resizable-gutter-keys-a"
              "rf-xray-resizable-gutter-keys-b"]
             (mapv #(:data-testid (nth % 1)) gutters))
          "the testid the CSS hover rule selects on survives the key injection")
      (is (every? #(fn? (:on-pointer-down (nth % 1))) gutters)
          "and so does the drag affordance"))))

(deftest global-styles-paints-the-gutter-hover
  (testing "the hover paint is a global CSS rule keyed on the gutter's
            `data-testid` prefix. Without `!important` the gutter's inline
            `background: transparent` would win and the handle would never
            light up. No gate renders CSS, so this is its only check."
    (is (str/includes? @#'gs/motion-css
                       (str "[data-testid^=\"rf-xray-resizable-gutter-\"]:hover {\n"
                            "  background: var(--rf-xray-accent) !important;\n"
                            "}\n")))))

;; ---- body cells, spacers and row wrappers -------------------------------

(deftest body-cells-and-spacers-reach-the-codec-with-keys
  (testing "every woven body node (3 cells + 2 spacers) on every row
            carries its key in the attrs map"
    (xray-setup!)
    (is (= (repeat 2 ["c-a" "s-a" "c-b" "s-b" "c-c"])
           (map #(mapv fresco-key (row-woven %))
                (body-rows (render (three-column-opts {}))))))))

(deftest row-wrappers-reach-the-codec-with-keys
  (testing "the consumer's `:row-key` reaches the row wrapper on the
            default path AND on the `:row-extras` path, which builds a
            different wrapper"
    (xray-setup!)
    (let [plain  (body-rows (render (three-column-opts {})))
          extras (body-rows (render (three-column-opts
                                      {:row-extras (fn [_row i]
                                                     [:div (str "extra-" i)])})))]
      (is (= [["row-0" "row-1"] ["row-0" "row-1"]]
             [(mapv fresco-key plain) (mapv fresco-key extras)]))
      (is (= "r-0" (:data-testid (nth (first extras) 1)))
          "the consumer's row attrs survive the key injection"))))

;; ---- consumer-built cells, in every shape the helper must handle --------

(def ^:private shape-columns
  [{:id :attrs  :label "attrs"}
   {:id :bare   :label "bare"}
   {:id :string :label "string"}
   {:id :nested :label "nested"}
   {:id :novec  :label "novec"}])

(defn- shape-cells
  "One cell per shape the helper must handle: a vector with an attrs map,
  one with no second element, one whose second element is a string, one
  whose second element is a nested vector, and a non-vector."
  [_row _i]
  [[:div {:data-testid "with-attrs"} "A"]
   [:div]
   [:span "txt"]
   [:div [:span "nested"]]
   "bare string"])

(deftest consumer-cell-children-survive-the-key-injection
  (testing "`:row-cells` is the CONSUMER's fn, so a woven cell may or may
            not carry an attrs map. Every vector shape gains its key in an
            attrs map with its own children intact — `(assoc-in cell [1
            :key] k)` would REPLACE `[:span \"txt\"]`'s string child — and
            a non-vector cell is returned untouched."
    (xray-setup!)
    (is (= [[:div {:data-testid "with-attrs" :key "c-attrs"} "A"]
            [:div {:key "c-bare"}]
            [:span {:key "c-string"} "txt"]
            [:div {:key "c-nested"} [:span "nested"]]
            "bare string"]
           (take-nth 2 (row-woven (first (body-rows
                                           (render {:table-id  :rf.xray.test/shapes
                                                    :columns   shape-columns
                                                    :rows      [{:v 1}]
                                                    :row-key   (fn [_row i] (str "row-" i))
                                                    :row-cells shape-cells})))))))))
