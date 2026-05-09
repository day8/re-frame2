(ns re-frame.source-coord-dom-cljs-test
  "Per Spec 006 §Source-coord annotation (rf2-z7f7 / rf2-z9n1): when
  `interop/debug-enabled?` is true, the Reagent substrate adapter MUST
  inject `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"` on the
  rendered root DOM element of every registered view. The annotation
  lets pair-shaped tools (re-frame-pair, re-frame-10x, IDE jump-to-
  source) map a clicked DOM node back to the reg-view call site.

  Coverage:

    - DOM-keyword root with no attrs map: the wrapper splices an attrs
      map carrying data-rf2-source-coord.
    - DOM-keyword root WITH an existing attrs map: data-rf2-source-coord
      is merged in alongside the user's attrs.
    - User-supplied data-rf2-source-coord wins (don't overwrite).
    - Form-2 (render-fn returns a fn): inner-fn output gets annotated.
    - React Fragment root (`:<>`): root is exempt; no attribute injected;
      one-shot warning emitted (pair tools fall back to :rf/id).
    - Programmatic reg-view* without source-coords: annotation degrades
      gracefully — emits `<ns>:<sym>:?:?`.
    - Format: the attribute value matches `<ns>:<sym>:<line>:<col>`.

  Production elision (interop/debug-enabled? = false at build time) is
  verified separately by the elision-probe build (Spec 009 §Production
  builds, scripts/check-elision.cjs, sentinel `data-rf2-source-coord`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [re-frame.core :as rf]
            [re-frame.substrate.reagent :as reagent-adapter]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.views-macros :refer [reg-view]]))

(use-fixtures :each
  (test-support/reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

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
            attrs map carrying :data-rf2-source-coord"
    (rf/reg-view ^{:rf/id :rf.src-coord-test/no-attrs} no-attrs-view []
      [:span "hi"])
    (let [render (rf/view :rf.src-coord-test/no-attrs)
          out    (render)
          attr   (root-attr out)]
      (is (vector? out))
      (is (= :span (first out)) "root tag preserved")
      (is (string? attr) ":data-rf2-source-coord present")
      ;; Format: <ns>:<sym>:<line>:<col>. The <ns>/<sym> are taken from
      ;; the registry id keyword — here the explicit :rf/id override
      ;; (rf.src-coord-test/no-attrs), not the call-site symbol.
      (is (re-find #"^rf\.src-coord-test:no-attrs:\d+:\d+$" attr)
          (str ":data-rf2-source-coord matches <ns>:<sym>:<line>:<col>; got "
               (pr-str attr))))))

;; ---- DOM-keyword root with attrs map --------------------------------------

(deftest annotates-dom-root-with-existing-attrs
  (testing "a reg-view'd component with [:tag {:class …} children…] has
            :data-rf2-source-coord merged into the existing attrs map"
    (rf/reg-view ^{:rf/id :rf.src-coord-test/with-attrs} with-attrs-view []
      [:div {:class "card" :id "x"} "body"])
    (let [render (rf/view :rf.src-coord-test/with-attrs)
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
    (rf/reg-view ^{:rf/id :rf.src-coord-test/user-stamped} user-stamped-view []
      [:p {:data-rf2-source-coord "stamped:by-user"} "ok"])
    (let [render (rf/view :rf.src-coord-test/user-stamped)
          out    (render)]
      (is (= "stamped:by-user" (:data-rf2-source-coord (second out)))
          "user-supplied attribute survives the wrapper's merge"))))

;; ---- Form-2: render-fn returns a fn --------------------------------------

(deftest annotates-form-2-inner-output
  (testing "Form-2 render-fns return a fn; the wrapper recurses on the
            inner fn's output so annotation lands on the eventual
            rendered DOM root, not the outer fn"
    (rf/reg-view* :rf.src-coord-test/form-2
      ;; Outer Form-2 fn — captures setup, returns the inner render fn.
      (fn []
        (fn inner-render []
          [:section.f2 "form-2 body"])))
    (let [wrapper (rf/view :rf.src-coord-test/form-2)
          out     (wrapper)]
      (is (fn? out) "outer wrapper returns a fn (Form-2 shape preserved)")
      (let [inner-out (out)]
        (is (vector? inner-out) "inner fn returns hiccup")
        (is (= :section.f2 (first inner-out)))
        (is (string? (root-attr inner-out))
            ":data-rf2-source-coord landed on the inner output's root")))))

;; ---- React Fragment / non-DOM root: skip + warn ---------------------------

(deftest fragment-root-is-exempt
  (testing "a render-fn that returns a React Fragment :<> at the root is
            exempt — no attribute injected; pair tools fall back to :rf/id"
    (rf/reg-view ^{:rf/id :rf.src-coord-test/fragment} fragment-view []
      [:<> [:p "a"] [:p "b"]])
    (let [render (rf/view :rf.src-coord-test/fragment)
          out    (render)]
      (is (= :<> (first out)) "fragment marker preserved")
      ;; Per the documented exemption, the wrapper does NOT splice attrs
      ;; into a fragment — Reagent fragments don't accept attrs at the
      ;; React level.
      (is (not (and (map? (second out))
                    (contains? (second out) :data-rf2-source-coord)))
          "no :data-rf2-source-coord on fragment root"))))

(deftest interop-react-component-root-is-exempt
  (testing "a render-fn whose root is a React-component head (`[:> Cmp …]`)
            is exempt — pair tools fall back to :rf/id"
    (rf/reg-view ^{:rf/id :rf.src-coord-test/interop-root} interop-view []
      [:> "div" {} "body"])           ;; `:>` interop marker
    (let [render (rf/view :rf.src-coord-test/interop-root)
          out    (render)]
      (is (= :> (first out))
          "interop marker preserved, no :data-rf2-source-coord injected")
      ;; `[:> Cmp {} "body"]` — second slot is the React props map; we
      ;; should NOT have added :data-rf2-source-coord into THAT map
      ;; (that would set a DOM attribute via React's component, which is
      ;; the right shape only if Cmp is a DOM tag string — but the v1
      ;; rule per the bead's documented exemption is "skip and warn").
      (is (not (contains? (second out) :data-rf2-source-coord))
          "no :data-rf2-source-coord merged into the interop props map"))))

;; ---- programmatic registration without macro source-coords ---------------

(deftest programmatic-registration-degrades-gracefully
  (testing "a programmatic reg-view* (no macro coords) still annotates
            with the id-derived <ns>:<sym> portion; line/col are `?`"
    (rf/reg-view* :rf.src-coord-test/programmatic
      (fn [] [:p "p"]))
    (let [render (rf/view :rf.src-coord-test/programmatic)
          out    (render)
          attr   (root-attr out)]
      (is (string? attr))
      (is (= "rf.src-coord-test:programmatic:?:?" attr)
          "format degrades to <ns>:<sym>:?:? when coords are absent"))))

;; ---- attribute format -----------------------------------------------------

(deftest attribute-format-shape
  (testing "the attribute value is exactly <ns>:<sym>:<line>:<col>"
    (rf/reg-view ^{:rf/id :rf.src-coord-test/format-shape} format-shape-view []
      [:i "x"])
    (let [out  (->>  (rf/view :rf.src-coord-test/format-shape) (#(% nil)))
          attr (root-attr out)]
      (is (string? attr))
      (let [parts (str/split attr #":")]
        (is (= 4 (count parts))
            "exactly four colon-separated segments")
        ;; <ns>:<sym> are derived from the registry id keyword. Here the
        ;; explicit :rf/id override is :rf.src-coord-test/format-shape
        ;; so <ns>=rf.src-coord-test and <sym>=format-shape, NOT the
        ;; call-site (re-frame.source-coord-dom-cljs-test, format-shape-view).
        (is (= "rf.src-coord-test" (first parts))
            "first segment is the id keyword's namespace")
        (is (= "format-shape" (second parts))
            "second segment is the id keyword's name")
        (is (re-matches #"\d+" (nth parts 2))
            "third segment is the line integer")
        (is (re-matches #"\d+" (nth parts 3))
            "fourth segment is the column integer")))))

;; ---- id derived from call-site symbol (no override) ----------------------

(deftest annotation-uses-auto-derived-id
  (testing "without an :rf/id override, the auto-derived id (from
            (keyword (str *ns*) (str sym))) drives the <ns>:<sym>
            portion of the attribute"
    (rf/reg-view auto-id-view []
      [:em "hi"])
    (let [render (rf/view :re-frame.source-coord-dom-cljs-test/auto-id-view)
          out    (render)
          attr   (root-attr out)]
      (is (string? attr))
      (is (re-find #"^re-frame\.source-coord-dom-cljs-test:auto-id-view:\d+:\d+$" attr)
          (str "auto-derived id drives <ns>:<sym>; got " (pr-str attr))))))
