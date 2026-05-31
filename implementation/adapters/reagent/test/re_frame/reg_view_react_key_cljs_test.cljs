(ns re-frame.reg-view-react-key-cljs-test
  "Substrate contract: a `reg-view`'d component propagates the call-site
  `^{:key …}` (and a `:key` in metadata position) to the React element
  it becomes, at PARITY with a plain Reagent fn. Per rf2-1anbp.

  Why this matters: rendering a seq of reg-views each with a per-item
  `^{:key n}` is the normal Reagent idiom (a `(for …)` list of rows). If
  the wrapper `re-frame.views/build-frame-aware-view` interposed between
  the call site and Reagent's hiccup→element translation dropped the
  metadata `:key`, React reconciliation would see colliding keys (every
  sibling falling back to the component's munged name) and could
  duplicate / drop list children. The contract: the reg-view wrapper is
  TRANSPARENT to React's reconciliation `:key`.

  Distinct from `:rf.view/render-key` (the `[view-id instance-token]`
  INSTRUMENTATION tuple pinned by `render_key_cljs_test.cljs`) — that is
  internal trace identity, NOT React's reconciliation `:key`. This file
  pins the React-facing `:key` specifically.

  Seam: `reagent.core/as-element` is the exact point hiccup becomes a
  React element — `(.-key el)` is what React's reconciler reads. The
  wrapper sits between the call site and this seam, so asserting the key
  survives `as-element` pins the propagation precisely. A companion
  DOM-level assertion (the `-dom-cljs-test` sibling) confirms no React
  key-collision warning under a real reconciliation commit."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

;; A registered view + a plain Reagent fn rendering the SAME shape, so
;; the parity assertions compare like with like.
(reg-view ^{:rf/id :rf.key-test/row} row-view [n]
  [:div "row-" n])

(defn- plain-row [n]
  [:div "row-" n])

(defn- el-key
  "The React `:key` the reconciler will read for hiccup `v`."
  [v]
  (.-key (r/as-element v)))

;; ---- single element: metadata :key reaches the React element --------------

(deftest regview-element-carries-call-site-key
  (testing "a reg-view'd component with ^{:key} yields a React element
            whose .-key is that key (React coerces to string)"
    (let [view (rf/view :rf.key-test/row)]
      (is (= "7" (el-key ^{:key 7} [view 7]))
          "the call-site ^{:key 7} reaches the React element as the key")
      (is (= "alpha" (el-key ^{:key "alpha"} [view 1]))
          "a string key reaches the React element unchanged"))))

(deftest regview-key-parity-with-plain-fn
  (testing "the reg-view wrapper is transparent to React's :key — a
            reg-view'd component and a plain Reagent fn produce the SAME
            React key for the same call-site ^{:key}"
    (let [view (rf/view :rf.key-test/row)]
      (is (= (el-key ^{:key 3} [plain-row 3])
             (el-key ^{:key 3} [view 3]))
          "reg-view'd and plain-fn elements get identical React keys"))))

;; ---- seq of reg-views: distinct keys, no collision -------------------------

(deftest seq-of-regviews-yields-distinct-react-keys
  (testing "a seq of reg-views each with a distinct ^{:key} yields
            DISTINCT React keys — the no-collision contract that prevents
            React's 'two children with the same key' reconciliation bug
            (rf2-1anbp). This is the (for [n …] ^{:key n} [view n]) idiom."
    (let [view (rf/view :rf.key-test/row)
          ;; The exact failing shape from the bead's repro: a for-loop
          ;; producing a seq of reg-views, each keyed by its item.
          children (for [n [1 2 3 4 5]]
                     ^{:key n} [view n])
          keys     (mapv el-key children)]
      (is (= ["1" "2" "3" "4" "5"] keys)
          "each reg-view in the seq carries its own call-site key")
      (is (= (count keys) (count (set keys)))
          "no two siblings collide — every React key is distinct")
      (is (not-any? #(re-find #"row" %) keys)
          "no key fell back to the component name (the rf2-1anbp failure
           mode: every sibling keyed by the munged component name)"))))

(deftest seq-mixed-regview-kinds-distinct-keys
  (testing "a seq mixing two reg-view kinds (the ladder's section-heading
            + ladder-button shape) keeps each call-site key — keys do not
            collapse to a per-kind component name"
    (rf/reg-view* :rf.key-test/heading (fn [label] [:div "h-" label]))
    (let [row     (rf/view :rf.key-test/row)
          heading (rf/view :rf.key-test/heading)
          rows    [[:heading "A"] [:row 1] [:row 2] [:heading "B"] [:row 3]]
          children (for [[kind v] rows]
                     (if (= :heading kind)
                       ^{:key (str "h-" v)} [heading v]
                       ^{:key v} [row v]))
          keys     (mapv el-key children)]
      (is (= ["h-A" "1" "2" "h-B" "3"] keys)
          "every call-site key survives across mixed reg-view kinds")
      (is (= (count keys) (count (set keys)))
          "no collisions in the mixed seq"))))
