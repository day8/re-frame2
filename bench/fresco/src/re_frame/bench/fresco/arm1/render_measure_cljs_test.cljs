(ns re-frame.bench.fresco.arm1.render-measure-cljs-test
  "Render ids match display names; flag-off renders produce real output without measures."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [re-frame.adapter.uix :as rf.adapter.uix]
            [re-frame.bench.fresco.arm1.mount :as rf.bench.fresco.arm1.mount]
            [re-frame.bench.fresco.arm1.runtime :as rf.bench.fresco.arm1.runtime]
            [re-frame.bench.fresco.front.codec :as rf.bench.fresco.front.codec]
            [re-frame.core :as rf]
            [re-frame.performance :as rf.performance :include-macros true]
            [re-frame.test-support :as rf.test-support]
            ["react-dom/server" :as react-dom-server])
  (:require-macros [re-frame.bench.fresco.arm1.lang :refer [defview]]))

(def ^:private frame-id ::render-measure-off)

;; Registered above `use-fixtures`, deliberately — the reset fixture
;; captures its source-store baseline when the `use-fixtures` form is
;; evaluated (the sibling suites' convention).

(rf/reg-sub :rm/title (fn [db _] (:title db)))

(rf/reg-event :rm/seed (fn [_ _] {:db {:title "quarterly"}}))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.uix/adapter
     :ambient-frame nil
     :init-fn       (fn [] (rf.bench.fresco.arm1.runtime/reset-runtime!) (rf.bench.fresco.arm1.runtime/reset-body-runs!))}))

;; The page — two boundaries, so "one measure per boundary" has something
;; to be one of

(defview title-row
  "A boundary that reads, so its body is a real body and not a constant."
  [_]
  [:h1.title (rf.bench.fresco.arm1.runtime/sub [:rm/title])])

(defview measured-page
  "The outer boundary. Two heads render per pass, which is what makes the
  count in the ON half a count."
  [_]
  [:div.page
   [title-row {}]
   [:p.note "below"]])

;; Helpers

(defn- rf-measure-names
  "Every retained User-Timing measure whose name starts with `rf:`."
  []
  (->> (.getEntriesByType js/performance "measure")
       (map #(.-name %))
       (filterv #(.startsWith % "rf:"))))

(defn- clear-measures! []
  (when (exists? js/performance.clearMeasures)
    (.clearMeasures js/performance))
  (when (exists? js/performance.clearMarks)
    (.clearMarks js/performance)))

(defn- fresh! []
  (rf/make-frame {:id frame-id})
  (rf/with-frame frame-id (rf/dispatch-sync [:rm/seed]))
  frame-id)

(defn- server-html
  "The page under React's server renderer — the same component fn the DOM
  renderer calls, minus the browser."
  [hiccup]
  (react-dom-server/renderToString
    (rf.bench.fresco.arm1.mount/provider frame-id (rf.bench.fresco.front.codec/root-element frame-id hiccup))))

;; 1 — the id rule (no flag needed; it is a property of the mint)

(deftest the-render-entry-id-is-the-heads-display-name
  (testing "`defview` stamps `\"<ns>/<sym>\"` as the head's displayName, and
            that same string is the `<view-id>` in `rf:render:<view-id>` —
            so the identifier a consumer filters the User-Timing stream on
            and the identifier React DevTools shows are one string, not two
            that happen to agree (Spec 009 §Naming convention)."
    (is (= [["re-frame.bench.fresco.arm1.render-measure-cljs-test/title-row"
             "rf:render:re-frame.bench.fresco.arm1.render-measure-cljs-test/title-row"]
            ["re-frame.bench.fresco.arm1.render-measure-cljs-test/measured-page"
             "rf:render:re-frame.bench.fresco.arm1.render-measure-cljs-test/measured-page"]]
           (mapv (fn [head]
                   [(.-displayName head) (rf.performance/build-name :render (.-displayName head))])
                 [title-row measured-page])))))

;; 2 — the off path: the render happens, and nothing is measured

(deftest a-flag-off-build-renders-the-page-and-emits-no-render-measure
  (testing "With `re-frame.performance/enabled?` at its goog-define default
            the `:render` bracket is shape-equivalent to a bare call. The
            markup and the body-run count say the render REALLY HAPPENED —
            without them 'no rf: measures landed' is a statement about a
            page that never rendered — and the empty measure stream says
            the bracket added nothing to it."
    (when-not rf.performance/enabled?
      (fresh!)
      (clear-measures!)
      (rf.bench.fresco.arm1.runtime/reset-body-runs!)
      (let [html (server-html [measured-page {}])]
        (is (= [true true 2 []]
               [(some? (re-find #"quarterly" html))
                (some? (re-find #"class=\"page\"" html))
                (rf.bench.fresco.arm1.runtime/body-runs)
                (rf-measure-names)])
            "the page rendered its subscription value in the ordinary markup,
             two boundary bodies ran — the page and the row — and NOTHING
             reached the User-Timing stream")))))
