(ns re-frame.source-coord-dom-cljs-test
  "In debug builds the Reagent adapter stamps every registered view's DOM
  root with `data-rf2-source-coord=\"<ns>:<sym>:<line>:<col>\"` (Spec 006
  §Source-coord annotation), so pair-shaped tools can map a clicked node back
  to its `reg-view`: spliced into a bare root, never over a user-supplied
  value, never on a Fragment or `[:> Cmp …]` root, and as `<ns>:<sym>:?:?`
  for a programmatic `reg-view*`. The existing-attrs and Form-2 cases are in
  `re-frame.view-id-attr-cljs-test`; production elision is the elision-probe
  build's."
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
    (let [out  ((rf/view :rf.src-coord-test/no-attrs))
          attr (root-attr out)]
      ;; <ns>/<sym> come from the registry id — here the :rf/id override,
      ;; not the call-site symbol.
      (is (= [:span true]
             [(first out) (boolean (and (string? attr)
                                        (re-find #"^rf\.src-coord-test:no-attrs:\d+:\d+$" attr)))])
          (str "the root tag is kept and the coord is <ns>:<sym>:<line>:<col>; got "
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
      ;; A Reagent fragment takes no attrs at the React level.
      (is (= [:<> false]
             [(first out) (boolean (and (map? (second out))
                                        (contains? (second out) :data-rf2-source-coord)))])
          "the fragment root is kept, with no :data-rf2-source-coord"))))

(deftest interop-react-component-root-is-exempt
  (testing "a render-fn whose root is a React-component head (`[:> Cmp …]`)
            is exempt — pair tools fall back to :rf/id"
    (rf/reg-view ^{:rf/id :rf.src-coord-test/interop-root} interop-view []
      [:> "div" {} "body"])           ;; `:>` interop marker
    (let [render (rf/view :rf.src-coord-test/interop-root)
          out    (render)]
      ;; The second slot is the component's props map; the exemption is
      ;; skip-and-warn, never a merge into it.
      (is (= [:> false] [(first out) (contains? (second out) :data-rf2-source-coord)])
          "the interop marker is kept, with no :data-rf2-source-coord in its props map"))))

;; ---- programmatic registration without macro source-coords ---------------

(deftest programmatic-registration-degrades-gracefully
  (testing "a programmatic reg-view* (no macro coords) still annotates
            with the id-derived <ns>:<sym> portion; line/col are `?`"
    (rf/reg-view* :rf.src-coord-test/programmatic
      (fn [] [:p "p"]))
    (let [render (rf/view :rf.src-coord-test/programmatic)
          out    (render)
          attr   (root-attr out)]
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
      (is (and (string? attr)
               (re-find #"^re-frame\.source-coord-dom-cljs-test:auto-id-view:\d+:\d+$" attr))
          (str "auto-derived id drives <ns>:<sym>; got " (pr-str attr))))))
