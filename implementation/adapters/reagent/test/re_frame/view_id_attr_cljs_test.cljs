(ns re-frame.view-id-attr-cljs-test
  "Per Spec 006 §View tagging contract (rf2-01il5): when
  `interop/debug-enabled?` is true, the Reagent substrate adapter MUST
  also inject `data-rf-view=\"<id>\"` on the rendered root DOM element
  of every registered view — ALONGSIDE `data-rf2-source-coord`. The
  view-id attribute is the FALLBACK data source for the runtime
  view-hierarchy walker when the Fiber-walker primary path (rf2-mxkq7)
  is unavailable.

  Coverage (mirrors `source_coord_dom_cljs_test.cljs` shape):

    - DOM-keyword root with no attrs map: the wrapper splices an attrs
      map carrying BOTH data-rf2-source-coord AND data-rf-view.
    - DOM-keyword root WITH an existing attrs map: both attributes are
      merged in alongside the user's attrs.
    - User-supplied data-rf-view wins (don't overwrite).
    - Form-2 (render-fn returns a fn): inner-fn output gets BOTH attrs.
    - React Fragment root: exempt; no attribute injected; one-shot
      warning emitted (same exemption as source-coord).
    - Format: the attribute value is `(str id)` — i.e. `\":ns/sym\"`
      for a namespaced keyword id.

  Production elision (interop/debug-enabled? = false at build time) is
  verified separately by the elision-probe build via the
  `data-rf-view` sentinel registered in `scripts/check-elision.cjs`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

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
            :data-rf2-source-coord AND :data-rf-view spliced in"
    (rf/reg-view ^{:rf/id :rf.view-id-test/no-attrs} no-attrs-view []
      [:span "hi"])
    (let [render (rf/view :rf.view-id-test/no-attrs)
          out    (render)
          view   (root-view-attr out)
          coord  (root-coord-attr out)]
      (is (vector? out))
      (is (= :span (first out)) "root tag preserved")
      (is (string? view) ":data-rf-view present alongside :data-rf2-source-coord")
      (is (string? coord) ":data-rf2-source-coord present (parity)")
      (is (= ":rf.view-id-test/no-attrs" view)
          "view attribute value is (str id) — leading-colon preserved"))))

;; ---- DOM-keyword root with attrs map --------------------------------------

(deftest tags-dom-root-with-existing-attrs
  (testing "a reg-view'd component with [:tag {:class …} children…] has
            :data-rf-view merged into the existing attrs map alongside
            user attrs (without disturbing them)"
    (rf/reg-view ^{:rf/id :rf.view-id-test/with-attrs} with-attrs-view []
      [:div {:class "card" :id "x"} "body"])
    (let [render (rf/view :rf.view-id-test/with-attrs)
          out    (render)
          attrs  (second out)]
      (is (vector? out))
      (is (= :div (first out)))
      (is (map? attrs))
      (is (= "card" (:class attrs)) "user :class preserved")
      (is (= "x"    (:id    attrs)) "user :id preserved")
      (is (= ":rf.view-id-test/with-attrs" (:data-rf-view attrs))
          ":data-rf-view merged in alongside user attrs")
      (is (string? (:data-rf2-source-coord attrs))
          ":data-rf2-source-coord still merged in (parity)"))))

;; ---- user-supplied data-rf-view wins --------------------------------------

(deftest user-supplied-data-rf-view-wins
  (testing "a render-fn that already set :data-rf-view is not overwritten
            — composability with hand-stamped tools (e.g. for tests that
            inject a synthetic view-id)"
    (rf/reg-view ^{:rf/id :rf.view-id-test/user-stamped} user-stamped-view []
      [:p {:data-rf-view "stamped:by-user"} "ok"])
    (let [render (rf/view :rf.view-id-test/user-stamped)
          out    (render)]
      (is (= "stamped:by-user" (:data-rf-view (second out)))
          "user-supplied attribute survives the wrapper's merge"))))

;; ---- Form-2: render-fn returns a fn --------------------------------------

(deftest tags-form-2-inner-output
  (testing "Form-2 render-fns return a fn; the wrapper recurses on the
            inner fn's output so :data-rf-view lands on the eventual
            rendered DOM root, not the outer fn"
    (rf/reg-view* :rf.view-id-test/form-2
      (fn []
        (fn inner-render []
          [:section.f2 "form-2 body"])))
    (let [wrapper (rf/view :rf.view-id-test/form-2)
          out     (wrapper)]
      (is (fn? out) "outer wrapper returns a fn (Form-2 shape preserved)")
      (let [inner-out (out)]
        (is (vector? inner-out) "inner fn returns hiccup")
        (is (= :section.f2 (first inner-out)))
        (is (= ":rf.view-id-test/form-2" (root-view-attr inner-out))
            ":data-rf-view landed on the inner output's root")))))

;; ---- React Fragment / non-DOM root: skip ----------------------------------

(deftest fragment-root-is-exempt-for-view-id
  (testing "a render-fn that returns a React Fragment :<> at the root is
            exempt for :data-rf-view (same exemption as source-coord);
            the walker falls back to the Fiber-walker primary path for
            fragments per Spec 006 §View tagging contract §Documented
            edge cases"
    (rf/reg-view ^{:rf/id :rf.view-id-test/fragment} fragment-view []
      [:<> [:p "a"] [:p "b"]])
    (let [render (rf/view :rf.view-id-test/fragment)
          out    (render)]
      (is (= :<> (first out)) "fragment marker preserved")
      (is (not (and (map? (second out))
                    (contains? (second out) :data-rf-view)))
          "no :data-rf-view on fragment root"))))

(deftest interop-react-component-root-is-exempt-for-view-id
  (testing "a render-fn whose root is a React-component head (`[:> Cmp …]`)
            is exempt for :data-rf-view — pair tools fall back per the
            documented edge cases"
    (rf/reg-view ^{:rf/id :rf.view-id-test/interop-root} interop-view []
      [:> "div" {} "body"])
    (let [render (rf/view :rf.view-id-test/interop-root)
          out    (render)]
      (is (= :> (first out)))
      (is (not (contains? (second out) :data-rf-view))
          "no :data-rf-view merged into the interop props map"))))

;; ---- attribute format -----------------------------------------------------

(deftest attribute-format-is-str-id
  (testing "the :data-rf-view value is exactly (str id) — preserving the
            leading colon so a walker can disambiguate keyword ids from
            raw strings"
    (rf/reg-view ^{:rf/id :rf.view-id-test/format-check} format-check-view []
      [:i "x"])
    (let [out  ((rf/view :rf.view-id-test/format-check))
          attr (root-view-attr out)]
      (is (string? attr))
      (is (= ":rf.view-id-test/format-check" attr)
          "format is (str :ns/sym) — leading-colon preserved")
      ;; A consumer reads back via (keyword (subs s 1)).
      (is (= :rf.view-id-test/format-check
             (keyword (subs attr 1)))
          "walker round-trips ':<ns>/<sym>' → keyword cleanly"))))

;; ---- id derived from call-site symbol (no override) ----------------------

(deftest tagging-uses-auto-derived-id
  (testing "without an :rf/id override, the auto-derived id (from
            (keyword (str *ns*) (str sym))) drives the :data-rf-view value"
    (rf/reg-view auto-view []
      [:em "hi"])
    (let [render (rf/view :re-frame.view-id-attr-cljs-test/auto-view)
          out    (render)
          attr   (root-view-attr out)]
      (is (string? attr))
      (is (= ":re-frame.view-id-attr-cljs-test/auto-view" attr)
          "auto-derived id drives :data-rf-view via (str id)"))))
