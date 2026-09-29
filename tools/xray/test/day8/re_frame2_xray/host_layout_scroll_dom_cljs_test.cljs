(ns day8.re-frame2-xray.host-layout-scroll-dom-cljs-test
  "WHERE A ROUTED APP'S PAGE LANDS inside Xray's recommended host layout, and
  what that layout keeps true of the Xray pane beside it.

  Routing scrolls the WINDOW: a navigation opens its page at the top, and Back
  restores the offset saved when the page was left (Spec 012 §Scroll
  restoration). So the recommended host snippet leaves the window as the app's
  scroller and pins the Xray pane beside it. A layout whose `#app` scrolls on
  its own inside a viewport-tall row leaves the window at 0: the new page
  opens at the old page's depth, and Back restores nothing.

  The layout under test is the SNIPPET ITSELF — its markup and `<style>` are
  parsed out of `config/default-layout-host-snippet`, so an edit to the
  recommendation is what these rows grade. The navigation rows also run in a
  plain container the window scrolls, as the control: they read the same
  there, so a red on the snippet is the snippet's.

  Offsets are read off the page as a user sees it — how far the page's first
  element has moved up — so the rows do not care which element scrolls, and
  the app is scrolled with `scrollIntoView`, which moves whichever one does.

  ns ends in `-dom-cljs-test`; under `:node-test` every row is a stated skip."
  (:require [cljs.test :refer-macros [async deftest is testing use-fixtures]]
            [clojure.string :as str]
            ["react-dom" :as react-dom]
            [reagent.dom.client :as rdc]
            [day8.re-frame2-xray.config :as config]
            [re-frame.adapter.reagent :as rf.adapter.reagent]
            [re-frame.core :as rf]
            [re-frame.interop :as rf.interop]
            [re-frame.routing :as rf.routing]
            [re-frame.test-support :as rf.test-support]))

(defn- browser? []
  (and (exists? js/document) (some? (.-createElement js/document))))

(defn- skip! [why]
  (is true (str "a host-layout row needs a real browser — " why)))

;; ---- The two layouts --------------------------------------------------------

(defn- snippet-parts
  "The recommended snippet split into its markup and its stylesheet."
  []
  (let [s     config/default-layout-host-snippet
        open  (str/index-of s "<style>")
        close (str/index-of s "</style>")]
    {:markup (str/trim (subs s 0 open))
     :css    (subs s (+ open (count "<style>")) close)}))

(defn- hide-the-rest!
  "Hide every other child of `<body>` for the row's duration and answer the
  thunk that puts them back. The bundle shares one page, and what earlier
  namespaces leave behind would make it tall however short the row's own
  pages are."
  [keep]
  (let [saved (->> (js/Array.from (.-children js/document.body))
                   (remove #(identical? % keep))
                   (mapv (fn [el] [el (.. el -style -display)])))]
    (doseq [[el _] saved] (set! (.. el -style -display) "none"))
    (fn [] (doseq [[el d] saved] (set! (.. el -style -display) d)))))

(defn- install-layout!
  "Put `layout` on the page and answer `{:app <element the app mounts into>
  :host <the Xray host, or nil> :remove! <thunk>}`. `:recommended` is the
  shipped snippet; `:window` is a plain container the window scrolls."
  [layout]
  (let [{:keys [markup css]} (snippet-parts)
        style   (.createElement js/document "style")
        wrapper (.createElement js/document "div")]
    (set! (.-textContent style) (if (= :recommended layout) css "body { margin: 0; }"))
    (when (= :recommended layout) (set! (.-innerHTML wrapper) markup))
    (.appendChild js/document.head style)
    (.appendChild js/document.body wrapper)
    (let [restore! (hide-the-rest! wrapper)]
      (.scrollTo js/window 0 0)
      {:app     (if (= :recommended layout) (.querySelector wrapper "#app") wrapper)
       :host    (.querySelector wrapper "[data-rf-xray-host]")
       :remove! (fn []
                  (restore!)
                  (.remove wrapper)
                  (.remove style)
                  (.. js/document -documentElement -style
                      (removeProperty config/default-layout-host-css-var))
                  (.scrollTo js/window 0 0))})))

;; ---- Navigation to a tall page, and Back -------------------------------------

(def ^:private first-url "/rf2-host-layout-scroll/first")
(def ^:private second-url "/rf2-host-layout-scroll/second")
(def ^:private page-height 6000)
(def ^:private first-depth
  "Where the first page's marker sits, and so how far it is scrolled before
  leaving it."
  3000)
(def ^:private second-depth 1000)

(defn- register-routes! []
  (rf.routing/reg-route ::first {:doc "A TALL page."} first-url)
  (rf.routing/reg-route ::second {:doc "Another TALL page, so a scroll left over from the first is not clamped."}
                        second-url)
  nil)

(rf/reg-view* ::page
  (fn page []
    (let [depth (if (= ::second @(rf/subscribe [:rf.route/id])) second-depth first-depth)]
      [:div {:id "rf2-host-layout-page"}
       [:div {:style {:height (str depth "px")}}]
       [:div {:id "rf2-host-layout-marker" :style {:height "1px"}}]
       [:div {:style {:height (str (- page-height depth) "px")}}]])))

(use-fixtures :each
  (rf.test-support/make-reset-runtime-fixture
    {:adapter       rf.adapter.reagent/adapter
     :ambient-frame nil
     :async?        true
     :init-fn       (fn []
                      (set! (.-IS_REACT_ACT_ENVIRONMENT js/globalThis) false)
                      (rf.routing/reset-counters!)
                      (rf.routing/reset-scroll-cache!)
                      (register-routes!))}))

(defn- page-top []
  (.-top (.getBoundingClientRect (.getElementById js/document "rf2-host-layout-page"))))

(defn- scroll-to-marker!
  "Scroll the app the way a reader would reach the marker: through whichever
  element scrolls it."
  []
  (.scrollIntoView (.getElementById js/document "rf2-host-layout-marker") #js {:block "start"}))

(defn- after-commit
  "A promise of `(read)`, taken inside an after-render callback queued right
  after `(act!)`, behind whatever `act!` queued."
  [act! read]
  (js/Promise.
    (fn [resolve]
      (act!)
      (rf.interop/after-render (fn [] (resolve (read)))))))

(defn- navigate-and-back!
  "In `layout`, scroll the first page to its marker, navigate to the second
  page, scroll that to ITS marker, press Back, and call `check` with the
  depth read after the navigation and the depth read after Back."
  [layout check done]
  (let [frame-id   ::frame
        runner-url (.-href js/location)
        prior      (.-scrollRestoration js/window.history)
        {:keys [app remove!]} (install-layout! layout)
        _          (set! (.-scrollRestoration js/window.history) "manual")
        _          (.replaceState js/window.history nil "" first-url)
        _          (rf/make-frame {:id frame-id :url-bound? true})
        root       (rdc/create-root app)
        _          (react-dom/flushSync
                     (fn [] (rdc/render root [rf/frame-provider {:frame frame-id} [(rf/view ::page)]])))
        origin     (page-top)
        depth      (fn [] (js/Math.round (- origin (page-top))))
        readings   (atom {})]
    (-> (after-commit (fn []) (fn [] nil))
        (.then
          (fn [_]
            (scroll-to-marker!)
            (is (= first-depth (depth))
                (str (name layout) ": precondition — the first page scrolls to "
                     first-depth ", and reads " (depth)))
            (after-commit
              #(rf/dispatch-sync [:rf.route/navigate {:to ::second}] {:frame frame-id})
              depth)))
        (.then
          (fn [arrived]
            (swap! readings assoc :arrived arrived)
            (scroll-to-marker!)
            (is (= second-depth (depth))
                (str (name layout) ": precondition — the second page scrolls to "
                     second-depth ", and reads " (depth)))
            (js/Promise.
              (fn [resolve]
                (let [on-pop (fn on-pop [_]
                               (.removeEventListener js/window "popstate" on-pop)
                               (rf.interop/after-render (fn [] (resolve (depth)))))]
                  (.addEventListener js/window "popstate" on-pop)
                  (.back js/window.history))))))
        (.then
          (fn [back]
            (check (assoc @readings :back back))))
        (.catch (fn [e] (is false (str (name layout) ": the row never settled: " e))))
        (.then (fn [_]
                 (try (.unmount root) (catch :default _ nil))
                 (remove!)
                 (.replaceState js/window.history nil "" runner-url)
                 (set! (.-scrollRestoration js/window.history) prior)
                 (try (rf/destroy-frame! frame-id) (catch :default _ nil))
                 (done))))))

(defn- check-navigation [layout]
  (fn [{:keys [arrived back]}]
    (is (= 0 arrived)
        (str (name layout) ": the second page opened " arrived "px down rather than at"
             " its top. Routing's scroll-to-top moves the window, so a layout whose"
             " app scrolls in an element of its own keeps the old page's depth"))
    (is (= first-depth back)
        (str (name layout) ": Back restored " back " rather than " first-depth
             ". Routing saves and restores the window's offset, so a layout whose"
             " app scrolls in an element of its own saves 0 and restores nothing"))))

(deftest a-tall-page-opens-at-the-top-and-back-restores-under-the-recommended-host
  (if-not (browser?)
    (skip! ":node-test has no layout, history or scroll model")
    (async done (navigate-and-back! :recommended (check-navigation :recommended) done))))

(deftest control-the-same-navigation-in-a-page-the-window-scrolls
  (if-not (browser?)
    (skip! ":node-test has no layout, history or scroll model")
    (async done (navigate-and-back! :window (check-navigation :window) done))))

;; ---- The Xray pane beside the app --------------------------------------------

(defn- rect [el] (.getBoundingClientRect el))

(defn- near? [a b] (<= (js/Math.abs (- a b)) 1))

(deftest the-recommended-host-keeps-xray-pinned-clipped-and-resizable
  (if-not (browser?)
    (skip! ":node-test has no layout model")
    (async done
      (let [{:keys [app host remove!]} (install-layout! :recommended)
            doc   (.-documentElement js/document)
            tall  (.createElement js/document "div")
            wide  (.createElement js/document "div")]
        (try
          (set! (.. tall -style -height) (str page-height "px"))
          (set! (.. wide -style -width) "3000px")
          (set! (.. wide -style -height) "1px")
          (.append app tall wide)
          (testing "the window scrolls the app, and the pane stays in view"
            (.scrollTo js/window 0 first-depth)
            (is (= first-depth (js/Math.round (.-scrollY js/window)))
                "the window scrolls the app — routing's scroll-to-top and Back act on it")
            (is (near? 0 (.-top (rect host)))
                (str "the pane stays at the viewport's top; it reads " (.-top (rect host))))
            (is (near? (.-innerHeight js/window) (.-height (rect host)))
                "the pane is exactly the viewport's height, so it scrolls inside itself"))
          (testing "an app wider than its column scrolls sideways without moving the pane"
            (.scrollTo js/window 100000 first-depth)
            (is (pos? (.-scrollX js/window))
                "precondition: the wide app scrolls the page sideways")
            (is (near? (.-clientWidth doc) (.-right (rect host)))
                (str "the pane stays at the viewport's right edge; its right edge reads "
                     (.-right (rect host)) " in a viewport " (.-clientWidth doc) " wide"))
            (is (<= (.-right (rect wide)) (+ 1 (.-left (rect host))))
                "at the end of the scroll, the app's widest content clears the pane"))
          (.remove wide)
          (.scrollTo js/window 0 0)
          (testing "nothing inside Xray widens the page"
            (let [inside (.createElement js/document "div")]
              (set! (.. inside -style -width) "4000px")
              (set! (.. inside -style -height) "1px")
              (.appendChild host inside)
              (is (<= (.-scrollWidth doc) (.-clientWidth doc))
                  (str "the page is " (.-scrollWidth doc) " wide in a viewport "
                       (.-clientWidth doc) " wide"))
              (.remove inside)))
          (testing "the splitter's variable resizes the pane, and the app takes the rest"
            (.setProperty (.-style doc) config/default-layout-host-css-var "700px")
            (is (near? 700 (.-width (rect host))))
            (is (near? (- (.-clientWidth doc) 700) (.-width (rect app)))))
          (finally
            (remove!)
            (done)))))))
