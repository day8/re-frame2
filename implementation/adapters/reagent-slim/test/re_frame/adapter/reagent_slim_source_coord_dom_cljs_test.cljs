(ns re-frame.adapter.reagent-slim-source-coord-dom-cljs-test
  "reagent-slim parity for the source-coord stamping contract (rf2-yrb8r;
  mirrors `re-frame.source-coord-dom-cljs-test` for the Reagent bridge).

  Per Spec 006 §Source-coord annotation: when `interop/debug-enabled?`
  is true, a registered view's rendered root DOM element MUST carry
  `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"`. The stamping is
  driven through `re-frame.views` under the *installed* adapter — so this
  file installs the slim adapter via the reset-runtime fixture and proves
  slim participates in the same stamping contract the bridge does. slim
  is positioned as a drop-in Reagent replacement; the cross-substrate
  matrix gap (zero stamping coverage under slim) is the gap this closes.

  Coverage mirrors the bridge's shape where it applies to slim:

    - DOM-keyword root with no attrs map: attrs map spliced in.
    - DOM-keyword root WITH an existing attrs map: coord merged in
      alongside the user's attrs.
    - User-supplied data-rf2-source-coord wins (don't overwrite).
    - Form-2 (render-fn returns a fn): inner-fn output gets annotated.
    - React Fragment root (`:<>`): root is exempt; no attribute injected.
    - Programmatic reg-view* without source-coords: degrades to
      `<ns>:<sym>:?:?`.
    - Format: the attribute value matches `<ns>:<sym>:<line>:<col>`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- root-attr
  "Pull the :data-rf2-source-coord value from the root attrs map of a
  hiccup vector, if any."
  [hiccup]
  (and (vector? hiccup)
       (map? (second hiccup))
       (:data-rf2-source-coord (second hiccup))))

;; ---- DOM-keyword root, no existing attrs map ------------------------------

(deftest annotates-dom-root-without-attrs
  (testing "a reg-view'd component with [:tag children…] gets a spliced
            attrs map carrying :data-rf2-source-coord under slim"
    (rf/reg-view ^{:rf/id :rf.slim-src-coord/no-attrs} no-attrs-view []
      [:span "hi"])
    (let [render (rf/view :rf.slim-src-coord/no-attrs)
          out    (render)
          attr   (root-attr out)]
      (is (vector? out))
      (is (= :span (first out)) "root tag preserved")
      (is (string? attr) ":data-rf2-source-coord present")
      (is (re-find #"^rf\.slim-src-coord:no-attrs:\d+:\d+$" attr)
          (str ":data-rf2-source-coord matches <ns>:<sym>:<line>:<col>; got "
               (pr-str attr))))))

;; ---- DOM-keyword root with attrs map --------------------------------------

(deftest annotates-dom-root-with-existing-attrs
  (testing "a reg-view'd component with [:tag {:class …} children…] has
            :data-rf2-source-coord merged into the existing attrs map"
    (rf/reg-view ^{:rf/id :rf.slim-src-coord/with-attrs} with-attrs-view []
      [:div {:class "card" :id "x"} "body"])
    (let [render (rf/view :rf.slim-src-coord/with-attrs)
          out    (render)
          attrs  (second out)]
      (is (vector? out))
      (is (= :div (first out)))
      (is (map? attrs))
      (is (= "card" (:class attrs)) "user :class preserved")
      (is (= "x"    (:id    attrs)) "user :id preserved")
      (is (string? (:data-rf2-source-coord attrs))
          ":data-rf2-source-coord merged in"))))

;; ---- user-supplied coord wins ---------------------------------------------

(deftest user-supplied-data-rf2-source-coord-wins
  (testing "a render-fn that already set :data-rf2-source-coord is not
            overwritten — composability with hand-stamped tools"
    (rf/reg-view ^{:rf/id :rf.slim-src-coord/user-stamped} user-stamped-view []
      [:p {:data-rf2-source-coord "stamped:by-user"} "ok"])
    (let [render (rf/view :rf.slim-src-coord/user-stamped)
          out    (render)]
      (is (= "stamped:by-user" (:data-rf2-source-coord (second out)))
          "user-supplied attribute survives the wrapper's merge"))))

;; ---- Form-2: render-fn returns a fn --------------------------------------

(deftest annotates-form-2-inner-output
  (testing "Form-2 render-fns return a fn; the wrapper recurses on the
            inner fn's output so annotation lands on the eventual
            rendered DOM root, not the outer fn"
    (rf/reg-view* :rf.slim-src-coord/form-2
      (fn []
        (fn inner-render []
          [:section.f2 "form-2 body"])))
    (let [wrapper (rf/view :rf.slim-src-coord/form-2)
          out     (wrapper)]
      (is (fn? out) "outer wrapper returns a fn (Form-2 shape preserved)")
      (let [inner-out (out)]
        (is (vector? inner-out) "inner fn returns hiccup")
        (is (= :section.f2 (first inner-out)))
        (is (string? (root-attr inner-out))
            ":data-rf2-source-coord landed on the inner output's root")))))

;; ---- React Fragment / non-DOM root: skip ----------------------------------

(deftest fragment-root-is-exempt
  (testing "a render-fn that returns a React Fragment :<> at the root is
            exempt — no attribute injected"
    (rf/reg-view ^{:rf/id :rf.slim-src-coord/fragment} fragment-view []
      [:<> [:p "a"] [:p "b"]])
    (let [render (rf/view :rf.slim-src-coord/fragment)
          out    (render)]
      (is (= :<> (first out)) "fragment marker preserved")
      (is (not (and (map? (second out))
                    (contains? (second out) :data-rf2-source-coord)))
          "no :data-rf2-source-coord on fragment root"))))

;; ---- programmatic registration without macro source-coords ---------------

(deftest programmatic-registration-degrades-gracefully
  (testing "a programmatic reg-view* (no macro coords) still annotates
            with the id-derived <ns>:<sym> portion; line/col are `?`"
    (rf/reg-view* :rf.slim-src-coord/programmatic
      (fn [] [:p "p"]))
    (let [render (rf/view :rf.slim-src-coord/programmatic)
          out    (render)
          attr   (root-attr out)]
      (is (string? attr))
      (is (= "rf.slim-src-coord:programmatic:?:?" attr)
          "format degrades to <ns>:<sym>:?:? when coords are absent"))))

;; ---- attribute format -----------------------------------------------------

(deftest attribute-format-shape
  (testing "the attribute value is exactly <ns>:<sym>:<line>:<col>"
    (rf/reg-view ^{:rf/id :rf.slim-src-coord/format-shape} format-shape-view []
      [:i "x"])
    (let [out  ((rf/view :rf.slim-src-coord/format-shape))
          attr (root-attr out)]
      (is (string? attr))
      (let [parts (str/split attr #":")]
        (is (= 4 (count parts))
            "exactly four colon-separated segments")
        (is (= "rf.slim-src-coord" (first parts))
            "first segment is the id keyword's namespace")
        (is (= "format-shape" (second parts))
            "second segment is the id keyword's name")
        (is (re-matches #"\d+" (nth parts 2))
            "third segment is the line integer")
        (is (re-matches #"\d+" (nth parts 3))
            "fourth segment is the column integer")))))
