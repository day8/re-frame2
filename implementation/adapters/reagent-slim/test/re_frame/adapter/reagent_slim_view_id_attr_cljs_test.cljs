(ns re-frame.adapter.reagent-slim-view-id-attr-cljs-test
  "reagent-slim parity for the view-id tagging contract (rf2-yrb8r;
  mirrors `re-frame.view-id-attr-cljs-test` for the Reagent bridge).

  Per Spec 006 §View tagging contract: when `interop/debug-enabled?` is
  true, a registered view's rendered root DOM element MUST carry
  `data-rf-view=\"<id>\"` ALONGSIDE `data-rf2-source-coord`. The view-id
  attribute is the FALLBACK data source for the runtime view-hierarchy
  walker when the Fiber-walker primary path is unavailable. The tagging
  is driven through `re-frame.views` under the *installed* adapter — so
  this file installs the slim adapter via the reset-runtime fixture and
  proves slim participates in the same tagging contract the bridge does.

  Coverage mirrors the bridge's shape where it applies to slim:

    - DOM-keyword root with no attrs map: BOTH data-rf2-source-coord
      AND data-rf-view spliced in.
    - DOM-keyword root WITH an existing attrs map: both merged in
      alongside the user's attrs.
    - User-supplied data-rf-view wins (don't overwrite).
    - Form-2 (render-fn returns a fn): inner-fn output gets BOTH attrs.
    - React Fragment root: exempt; no attribute injected.
    - Format: the attribute value is `(str id)` — i.e. `\":ns/sym\"`.

  ns ends in -cljs-test so shadow-cljs's :node-test build picks it up."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent-slim :as reagent-slim-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-slim-adapter/adapter}))

;; ---- helpers ---------------------------------------------------------------

(defn- root-view-attr
  "Pull the :data-rf-view value from the root attrs map of a hiccup
  vector, if any."
  [hiccup]
  (and (vector? hiccup)
       (map? (second hiccup))
       (:data-rf-view (second hiccup))))

(defn- root-coord-attr
  "Pull the :data-rf2-source-coord value from the root attrs map of a
  hiccup vector, if any."
  [hiccup]
  (and (vector? hiccup)
       (map? (second hiccup))
       (:data-rf2-source-coord (second hiccup))))

;; ---- DOM-keyword root, no existing attrs map ------------------------------

(deftest tags-dom-root-without-attrs
  (testing "a reg-view'd component with [:tag children…] gets BOTH
            :data-rf2-source-coord AND :data-rf-view spliced in under slim"
    (rf/reg-view ^{:rf/id :rf.slim-view-id/no-attrs} no-attrs-view []
      [:span "hi"])
    (let [render (rf/view :rf.slim-view-id/no-attrs)
          out    (render)
          view   (root-view-attr out)
          coord  (root-coord-attr out)]
      (is (vector? out))
      (is (= :span (first out)) "root tag preserved")
      (is (string? view) ":data-rf-view present alongside :data-rf2-source-coord")
      (is (string? coord) ":data-rf2-source-coord present (parity)")
      (is (= ":rf.slim-view-id/no-attrs" view)
          "view attribute value is (str id) — leading-colon preserved"))))

;; ---- DOM-keyword root with attrs map --------------------------------------

(deftest tags-dom-root-with-existing-attrs
  (testing "a reg-view'd component with [:tag {:class …} children…] has
            :data-rf-view merged into the existing attrs map alongside
            user attrs (without disturbing them)"
    (rf/reg-view ^{:rf/id :rf.slim-view-id/with-attrs} with-attrs-view []
      [:div {:class "card" :id "x"} "body"])
    (let [render (rf/view :rf.slim-view-id/with-attrs)
          out    (render)
          attrs  (second out)]
      (is (vector? out))
      (is (= :div (first out)))
      (is (map? attrs))
      (is (= "card" (:class attrs)) "user :class preserved")
      (is (= "x"    (:id    attrs)) "user :id preserved")
      (is (= ":rf.slim-view-id/with-attrs" (:data-rf-view attrs))
          ":data-rf-view merged in alongside user attrs")
      (is (string? (:data-rf2-source-coord attrs))
          ":data-rf2-source-coord still merged in (parity)"))))

;; ---- user-supplied data-rf-view wins --------------------------------------

(deftest user-supplied-data-rf-view-wins
  (testing "a render-fn that already set :data-rf-view is not overwritten
            — composability with hand-stamped tools"
    (rf/reg-view ^{:rf/id :rf.slim-view-id/user-stamped} user-stamped-view []
      [:p {:data-rf-view "stamped:by-user"} "ok"])
    (let [render (rf/view :rf.slim-view-id/user-stamped)
          out    (render)]
      (is (= "stamped:by-user" (:data-rf-view (second out)))
          "user-supplied attribute survives the wrapper's merge"))))

;; ---- Form-2: render-fn returns a fn --------------------------------------

(deftest tags-form-2-inner-output
  (testing "Form-2 render-fns return a fn; the wrapper recurses on the
            inner fn's output so :data-rf-view lands on the eventual
            rendered DOM root, not the outer fn"
    (rf/reg-view* :rf.slim-view-id/form-2
      (fn []
        (fn inner-render []
          [:section.f2 "form-2 body"])))
    (let [wrapper (rf/view :rf.slim-view-id/form-2)
          out     (wrapper)]
      (is (fn? out) "outer wrapper returns a fn (Form-2 shape preserved)")
      (let [inner-out (out)]
        (is (vector? inner-out) "inner fn returns hiccup")
        (is (= :section.f2 (first inner-out)))
        (is (= ":rf.slim-view-id/form-2" (root-view-attr inner-out))
            ":data-rf-view landed on the inner output's root")))))

;; ---- React Fragment / non-DOM root: skip ----------------------------------

(deftest fragment-root-is-exempt-for-view-id
  (testing "a render-fn that returns a React Fragment :<> at the root is
            exempt for :data-rf-view (same exemption as source-coord)"
    (rf/reg-view ^{:rf/id :rf.slim-view-id/fragment} fragment-view []
      [:<> [:p "a"] [:p "b"]])
    (let [render (rf/view :rf.slim-view-id/fragment)
          out    (render)]
      (is (= :<> (first out)) "fragment marker preserved")
      (is (not (and (map? (second out))
                    (contains? (second out) :data-rf-view)))
          "no :data-rf-view on fragment root"))))

;; ---- attribute format -----------------------------------------------------

(deftest attribute-format-is-str-id
  (testing "the :data-rf-view value is exactly (str id) — preserving the
            leading colon so a walker can disambiguate keyword ids from
            raw strings"
    (rf/reg-view ^{:rf/id :rf.slim-view-id/format-check} format-check-view []
      [:i "x"])
    (let [out  ((rf/view :rf.slim-view-id/format-check))
          attr (root-view-attr out)]
      (is (string? attr))
      (is (= ":rf.slim-view-id/format-check" attr)
          "format is (str :ns/sym) — leading-colon preserved")
      (is (= :rf.slim-view-id/format-check
             (keyword (subs attr 1)))
          "walker round-trips ':<ns>/<sym>' → keyword cleanly"))))
