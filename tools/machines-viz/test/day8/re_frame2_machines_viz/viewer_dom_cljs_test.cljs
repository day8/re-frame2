(ns day8.re-frame2-machines-viz.viewer-dom-cljs-test
  "The viewer page's mount (`viewer/run`) in a real DOM: it renders the
  share-URL in the location, and renders again when the fragment changes in
  place.

  The ns ends in `-dom-cljs-test`, so `:browser-test` (headless Chromium) runs
  it. Under `:node-test` there is no DOM and the test does nothing."
  (:require ["react" :as React]
            [cljs.test :refer-macros [deftest is testing]]
            [day8.re-frame2-machines-viz.share :as share]
            [day8.re-frame2-machines-viz.viewer :as viewer]))

(defn- browser? []
  (and (exists? js/document)
       (some? (.-createElement js/document))))

(defn- fragment
  "The `#machine=…` fragment of a share-URL for a two-state machine `machine-id`."
  [machine-id]
  (let [url (share/encode-share-url
              {:machine-id machine-id
               :definition {:initial :idle
                            :states  {:idle {:on {:go :done}}
                                      :done {:final? true}}}
               :snapshot   {:state :idle}}
              {:host "viewer.html"})]
    (subs url (.indexOf url "#"))))

(defn- shown-machine-id [^js app]
  (some-> (.querySelector app "[data-testid=\"rf-mv-chart\"]")
          (.getAttribute "data-machine-id")))

(defn- hashchange! [act-fn]
  (act-fn (fn [] (.dispatchEvent js/window (js/HashChangeEvent. "hashchange")))))

(deftest run-renders-again-on-hashchange
  (testing "a second share-URL put into an open viewer tab is a same-document
            fragment navigation, and the viewer shows the NEW machine"
    (when-let [act-fn (when (browser?) (.-act React))]
      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) true)
      (let [original (.. js/window -location -href)
            app      (.createElement js/document "div")]
        (set! (.. app -style -width) "800px")
        (set! (.. app -style -height) "600px")
        (.appendChild (.-body js/document) app)
        (try
          (.replaceState js/history nil "" (fragment :demo/first))
          ;; The runner page owns an `#app` of its own, so mount on ours.
          (act-fn (fn [] (viewer/run app)))
          (is (= ":demo/first" (shown-machine-id app)))
          (.replaceState js/history nil "" (fragment :demo/second))
          (hashchange! act-fn)
          (is (= ":demo/second" (shown-machine-id app)))
          (finally
            ;; The runner's own URL carries no `#machine=`, so the viewer
            ;; settles on its empty view before its element leaves the page.
            (.replaceState js/history nil "" original)
            (hashchange! act-fn)
            (.removeChild (.-body js/document) app)))))))
