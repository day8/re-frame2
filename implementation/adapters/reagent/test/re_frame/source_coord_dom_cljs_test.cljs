(ns re-frame.source-coord-dom-cljs-test
  "Per Spec 006 §Source-coord annotation: when
  `interop/debug-enabled?` is true, the Reagent substrate adapter MUST
  inject `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"` on the
  rendered root DOM element of every registered view. The annotation
  lets pair-shaped tools (re-frame-pair, re-frame-10x, IDE jump-to-
  source) map a clicked DOM node back to the reg-view call site.

  Coverage:

    - DOM-keyword root with no attrs map: the wrapper splices an attrs
      map carrying data-rf2-source-coord.
    - User-supplied data-rf2-source-coord wins (don't overwrite).
    - React Fragment root (`:<>`): root is exempt; no attribute injected;
      one-shot warning emitted (pair tools fall back to :rf/id).
    - Programmatic reg-view* without source-coords: annotation degrades
      gracefully — emits `<ns>:<sym>:?:?`.
    - Format: the attribute value matches `<ns>:<sym>:<line>:<col>`.

  A root WITH an existing attrs map, and the inner render of a Form-2
  render-fn, get both attributes from the same splice;
  `re-frame.view-id-attr-cljs-test` pins those cases for both of them.

  Production elision (interop/debug-enabled? = false at build time) is
  verified separately by the elision-probe build (Spec 009 §Production
  builds, scripts/check-elision.cjs, sentinel `data-rf2-source-coord`)."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.core :as rf]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

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
      ;; the right shape only if Cmp is a DOM tag string — but the rule,
      ;; per the documented exemption, is "skip and warn").
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
