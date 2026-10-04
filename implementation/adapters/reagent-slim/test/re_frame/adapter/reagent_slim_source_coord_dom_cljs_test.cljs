(ns re-frame.adapter.reagent-slim-source-coord-dom-cljs-test
  "reagent-slim parity for the source-coord stamping contract (mirrors
  `re-frame.source-coord-dom-cljs-test` for the Reagent bridge).

  Per Spec 006 §Source-coord annotation: when `interop/debug-enabled?`
  is true, a registered view's rendered root DOM element MUST carry
  `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"`. The stamping is
  driven through `re-frame.views` under the *installed* adapter — so this
  file installs the slim adapter via the reset-runtime fixture and proves
  slim participates in the same stamping contract the bridge does. slim
  is positioned as a drop-in Reagent replacement, so the cross-substrate
  matrix needs stamping coverage under slim as well.

  Coverage mirrors the bridge's shape where it applies to slim:

    - User-supplied data-rf2-source-coord wins (don't overwrite).
    - React Fragment root (`:<>`): root is exempt; no attribute injected.
    - Programmatic reg-view* without source-coords: degrades to
      `<ns>:<sym>:?:?`.

  A DOM-keyword root with or without an attrs map, and the inner render of
  a Form-2 render-fn, get both attributes from the same splice;
  `re-frame.adapter.reagent-slim-view-id-attr-cljs-test` pins those cases
  for both of them. The `<ns>:<sym>:<line>:<col>` value comes from the
  `reg-view` macro and the shared formatter, which the Reagent bridge's
  `annotates-dom-root-without-attrs` pins.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as rf.adapter.reagent-slim]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent-slim/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- root-attr
  "Pull the :data-rf2-source-coord value from the root attrs map of a
  hiccup vector, if any."
  [hiccup]
  (and (vector? hiccup)
       (map? (second hiccup))
       (:data-rf2-source-coord (second hiccup))))

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
