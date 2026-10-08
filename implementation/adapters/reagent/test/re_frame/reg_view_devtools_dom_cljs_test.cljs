(ns re-frame.reg-view-devtools-dom-cljs-test
  "DOM-level companion to `reg-view-devtools-cljs-test`: the name React
  DevTools actually SHOWS for a mounted reg-view, read the way DevTools
  reads it — off the committed fiber's `type` — rather than off the
  pre-mount fn property.

  Spec 006 §React DevTools support item 1 is a claim about the component
  tree, and on the Reagent path React renders a CLASS that Reagent's
  `fn-to-class` builds from the wrapped fn, so whether the stamp survives
  is a property of that machinery. The UIx counterpart is
  `re-frame.adapter.react-shared-suite/assert-mounted-display-name-is-devtools-visible`.
  A fiber exists only after a real `react-dom` commit, so `:node-test`
  loads this and exits early; `:browser-test` asserts."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.adapter.react-test-support :as rf.adapter.react-test-support]
            [re-frame.core :as rf]
            [re-frame.performance :as rf.performance]
            [re-frame.test-support :as rf.test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter rf.adapter.reagent/adapter}))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(def ^:private view-id :rf.devtools-dom/mounted-name)

(reg-view ^{:rf/id :rf.devtools-dom/mounted-name} mounted-name-view []
  [:div {:data-testid "devtools-dom-root"} "body"])

(defn- mount-and-read-names
  "Mount the registered view, return the DevTools-visible component names
  at and above its rendered root element (innermost first)."
  []
  (let [render-fn (rf/view view-id)
        node      (.createElement js/document "div")
        root      (rdc/create-root node)]
    (try
      (react-dom/flushSync (fn [] (rdc/render root [render-fn])))
      (rf.adapter.react-test-support/devtools-names-above
        (.querySelector node "[data-testid='devtools-dom-root']"))
      (finally
        (try (rdc/unmount root) (catch :default _ nil))))))

(deftest mounted-reagent-component-shows-the-colon-free-name
  (testing "with the view MOUNTED through Reagent's class
            machinery, the name React resolves for the component (the
            one DevTools renders in the tree) is the view-id's
            performance/display projection — no leading colon"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (let [expected (rf.performance/entry-id view-id)
            names    (mount-and-read-names)]
        (is (and (some #{expected} names) (not-any? #{(str ":" expected)} names))
            (str "the mounted component is named " (pr-str expected)
                 " in the fiber tree, with no colon-prefixed spelling anywhere "
                 "above the rendered root; saw " (pr-str names)))))))
