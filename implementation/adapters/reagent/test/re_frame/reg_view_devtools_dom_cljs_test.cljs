(ns re-frame.reg-view-devtools-dom-cljs-test
  "DOM-level companion to `reg-view-devtools-cljs-test`: the name React
  DevTools actually SHOWS for a mounted reg-view, read the way DevTools
  reads it — off the committed fiber's `type` — rather than off the
  pre-mount fn property.

  WHY THIS FILE EXISTS (rf2-976bw). Spec 006 §React DevTools support
  item 1 is a claim about what a developer reads in the component tree,
  and `(.-displayName (rf/view id))` is one step short of that: on the
  Reagent path the thing React renders is a CLASS that Reagent's
  `fn-to-class` machinery builds from the wrapped fn, so whether the
  stamp survives into the tree is a property of that machinery, not of
  the stamp. rf2-fa4ly pinned the stamp and never exercised the
  machinery; this file closes that gap on the shipping Reagent
  substrate. Its UIx counterpart is
  `re-frame.adapter.react-shared-suite/assert-mounted-display-name-is-devtools-visible`.

  Browser-only — a real `react-dom` commit is required before a fiber
  exists to read. The `-dom-cljs-test` suffix opts this file into the
  `:browser-test` build; `:node-test` loads it too (matches
  `cljs-test$`) and the mount branch gates on `(browser?)`."
  (:require [cljs.test :refer-macros [deftest is testing use-fixtures]]
            [reagent.dom.client :as rdc]
            ["react-dom" :as react-dom]
            [re-frame.adapter.reagent :as reagent-adapter]
            [re-frame.adapter.react-test-support :as react-test-support]
            [re-frame.core :as rf]
            [re-frame.performance :as performance]
            [re-frame.test-support :as test-support]
            [re-frame.views])
  (:require-macros [re-frame.core :refer [reg-view]]))

(use-fixtures :each
  (test-support/make-reset-runtime-fixture
    {:adapter reagent-adapter/adapter}))

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
      (react-test-support/devtools-names-above
        (.querySelector node "[data-testid='devtools-dom-root']"))
      (finally
        (try (rdc/unmount root) (catch :default _ nil))))))

(deftest mounted-reagent-component-shows-the-colon-free-name
  (testing "rf2-976bw: with the view MOUNTED through Reagent's class
            machinery, the name React resolves for the component (the
            one DevTools renders in the tree) is the view-id's
            performance/display projection — no leading colon"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (let [expected (performance/entry-id view-id)
            names    (mount-and-read-names)]
        (is (some #{expected} names)
            (str "the mounted component is named " (pr-str expected)
                 " in the fiber tree; saw " (pr-str names)))
        (is (not-any? #{(str ":" expected)} names)
            (str "no colon-prefixed spelling survives anywhere above the "
                 "rendered root; saw " (pr-str names)))))))

(deftest mounted-name-and-render-measure-are-one-identifier
  (testing "rf2-976bw: the equality that matters is between the name a
            developer READS in DevTools and the name the rf:render:
            bracket WRITES — asserted here against the mounted fiber, so
            it covers Reagent's class machinery rather than the stamp
            alone"
    (if-not (browser?)
      (is true ":node-test: no DOM — the :browser-test runner exercises this")
      (let [visible (first (filter #{(performance/entry-id view-id)}
                                   (mount-and-read-names)))]
        (is (some? visible) "the mounted component carries a resolvable name")
        (is (= (performance/build-name :render view-id)
               (str "rf:render:" visible))
            "the render measure name is exactly \"rf:render:\" + the
             DevTools-visible component name")))))
